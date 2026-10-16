package dev.holmes.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.holmes.Json;
import dev.holmes.logs.LogModels.ArchivePart;
import dev.holmes.logs.LogModels.ArchiveResult;
import dev.holmes.logs.LogModels.LogEvent;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/** 仅按需写入短期 spool；每个分块上传结束后立即删除本次临时文件。 */
public final class FluxDropArchiveSink {
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final String OWNER_MARKER = ".holmes-fluxdrop-owner";
    private static final byte[] OWNER_CONTENT = "holmes-fluxdrop-spool-v1\n".getBytes(StandardCharsets.UTF_8);
    private static final Pattern OWN_PART_NAME = Pattern.compile("part-[1-9][0-9]*\\.jsonl\\.gz");
    // 同进程内的 sink 共用锁，避免两个实例同时各自占满同一 spool 配额。
    private static final Semaphore SPOOL_LOCK = new Semaphore(1, true);
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)(\\b(?:proxy-)?authorization[\\\"']?\\s*[:=]\\s*[\\\"']?)(?:(?:bearer|basic)\\s+)?[^\\s\\\"',;\\r\\n}]+");
    private static final Pattern COOKIES = Pattern.compile("(?im)(\\b(?:set-cookie|cookie)\\s*[:=]\\s*)[^\\r\\n]+");
    private static final Pattern SECRETS = Pattern.compile(
            "(?i)(\\b(?:password|passwd|secret|client[-_]?secret|api[-_]?key|x-api-key|(?:access[-_]?|refresh[-_]?|auth[-_]?|x-auth-)?token|signature|sig|x-amz-signature|x-amz-credential|credential)[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s\\\"',;&}\\r\\n]+" );
    private static final Pattern QUOTED_SECRETS = Pattern.compile(
            "(?i)(\\b(?:authorization|proxy-authorization|password|passwd|secret|client[-_]?secret|api[-_]?key|x-api-key|(?:access[-_]?|refresh[-_]?|auth[-_]?|x-auth-)?token|signature|sig|x-amz-signature|x-amz-credential|credential)[\\\"']?\\s*[:=]\\s*)([\\\"'])([^\\r\\n]*?)\\2");
    private static final Pattern QUERY_VALUES = Pattern.compile("([?&][A-Za-z0-9._~%+-]+=)[^\\s\\\"'&#<>]+" );
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)(https?://)[^\\s/@]+:[^\\s/@]+@");
    private static final Pattern SECRET_LABEL = Pattern.compile(
            "(?i).*(?:authorization|cookie|password|passwd|secret|token|signature|credential|api[-_]?key).*" );

    private final URI origin;
    private final String token;
    private final Path spoolDir;
    private final long maxSpoolBytes;
    private final long chunkBytes;
    private final Duration timeout;
    private final HttpClient http;

    public FluxDropArchiveSink(URI baseUrl, String token, Path spoolDir, long maxSpoolBytes,
                               long chunkBytes, Duration timeout) {
        Objects.requireNonNull(baseUrl, "FluxDrop 地址不能为空");
        if (!("http".equalsIgnoreCase(baseUrl.getScheme()) || "https".equalsIgnoreCase(baseUrl.getScheme()))
                || baseUrl.getHost() == null || baseUrl.getUserInfo() != null
                || baseUrl.getQuery() != null || baseUrl.getFragment() != null) {
            throw new IllegalArgumentException("FluxDrop 地址必须是无凭证的 HTTP(S) 地址");
        }
        if (maxSpoolBytes <= 0 || chunkBytes <= 0 || chunkBytes > Integer.MAX_VALUE - 1L) {
            throw new IllegalArgumentException("归档配额与分块上限必须为有效正数");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("归档超时必须为正数");
        }
        this.token = token == null ? "" : token;
        if (this.token.indexOf('\r') >= 0 || this.token.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("FluxDrop 凭证格式无效");
        }
        this.origin = URI.create(baseUrl.getScheme().toLowerCase(Locale.ROOT) + "://" + baseUrl.getRawAuthority() + "/");
        this.spoolDir = Objects.requireNonNull(spoolDir).toAbsolutePath().normalize();
        this.maxSpoolBytes = maxSpoolBytes;
        this.chunkBytes = chunkBytes;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(timeout).build();
    }

    /** 仅在服务持有实例锁、恢复 worker 尚未启动时调用，回收进程崩溃遗留的自有分块。 */
    public void cleanupAbandoned() throws IOException {
        boolean acquired;
        try { acquired = SPOOL_LOCK.tryAcquire(durationNanos(timeout), TimeUnit.NANOSECONDS); }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("归档临时目录恢复清理被中断");
        }
        if (!acquired) throw new IOException("归档临时目录仍在使用，未执行恢复清理");
        try {
            if (!Files.exists(spoolDir, LinkOption.NOFOLLOW_LINKS)) return;
            StoragePaths.createDirectory(spoolDir);
            try (var directories = Files.newDirectoryStream(spoolDir, "fluxdrop-*")) {
                for (Path directory : directories) {
                    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || !ownedDirectory(directory)) continue;
                    try (var children = Files.newDirectoryStream(directory)) {
                        for (Path child : children) {
                            if (OWN_PART_NAME.matcher(child.getFileName().toString()).matches()
                                    && Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(child);
                        }
                    }
                    // 未知文件、子目录及符号链接全部保留，同时保留 marker 供以后识别。
                    removeEmptyWorkingDirectory(directory);
                }
            }
        } finally { SPOOL_LOCK.release(); }
    }

    public ArchiveResult archive(String batchId, Iterable<LogEvent> events, Instant deadline) throws InterruptedException {
        Objects.requireNonNull(events, "归档日志不能为空");
        Objects.requireNonNull(deadline, "归档截止时间不能为空");
        List<ArchivePart> parts = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        long waitNanos = remainingNanos(deadline);
        if (waitNanos <= 0 || !SPOOL_LOCK.tryAcquire(waitNanos, TimeUnit.NANOSECONDS)) {
            return new ArchiveResult("UNAVAILABLE", parts, List.of("归档截止时间已到，未获得临时空间使用权"));
        }
        Path workingDir = null;
        Chunk chunk = null;
        try {
            checkDeadline(deadline);
            StoragePaths.createDirectory(spoolDir);
            if (OWNER_CONTENT.length > maxSpoolBytes - spoolSize(deadline)) throw new QuotaExceededException();
            workingDir = Files.createTempDirectory(spoolDir, "fluxdrop-");
            Files.write(workingDir.resolve(OWNER_MARKER), OWNER_CONTENT, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            String safeBatch = safeBatch(batchId);
            int partNumber = 1;
            for (LogEvent event : events) {
                checkDeadline(deadline);
                byte[] line;
                try {
                    line = jsonLine(event);
                } catch (EventTooLargeException ex) {
                    addLimitation(limitations, "单条日志超过未压缩分块上限，该条未归档");
                    continue;
                }
                if (chunk != null && line.length > chunkBytes - chunk.uncompressedBytes) {
                    ArchivePart part = uploadChunk(chunk, safeBatch, deadline, limitations);
                    parts.add(part);
                    chunk = null;
                    if ("FAILED".equals(part.state())) {
                        addLimitation(limitations, "分块上传失败，剩余日志未归档");
                        break;
                    }
                }
                if (chunk == null) {
                    long occupied = spoolSize(deadline);
                    if (occupied >= maxSpoolBytes) throw new QuotaExceededException();
                    chunk = new Chunk(workingDir.resolve("part-" + partNumber + ".jsonl.gz"),
                            "part-%04d".formatted(partNumber++), maxSpoolBytes - occupied);
                }
                chunk.gzip.write(line);
                chunk.uncompressedBytes += line.length;
            }
            if (chunk != null) {
                parts.add(uploadChunk(chunk, safeBatch, deadline, limitations));
                chunk = null;
            }
            if (parts.isEmpty() && limitations.isEmpty()) limitations.add("没有可归档的日志");
        } catch (InterruptedException ex) {
            throw ex;
        } catch (DeadlineExceededException ex) {
            addLimitation(limitations, "归档截止时间已到，未完成的日志未归档");
        } catch (QuotaExceededException ex) {
            addLimitation(limitations, "临时目录总容量达到上限，未完成的日志未归档");
        } catch (IOException ex) {
            addLimitation(limitations, "归档临时文件处理失败，未完成的日志未归档");
        } catch (RuntimeException ex) {
            addLimitation(limitations, "归档输入处理失败，未完成的日志未归档");
        } finally {
            if (chunk != null) {
                try { chunk.gzip.close(); } catch (IOException ignored) { }
                cleanup(chunk.path, limitations);
            }
            if (workingDir != null) {
                try {
                    if (!removeEmptyWorkingDirectory(workingDir))
                        addLimitation(limitations, "本次临时归档文件清理失败，需要检查 spool 目录");
                } catch (IOException | RuntimeException ex) {
                    addLimitation(limitations, "本次临时归档文件清理失败，需要检查 spool 目录");
                }
            }
            SPOOL_LOCK.release();
        }
        boolean available = parts.stream().anyMatch(p -> "AVAILABLE".equals(p.state()));
        boolean unverified = parts.stream().anyMatch(p -> "UNVERIFIED".equals(p.state()));
        boolean allAvailable = !parts.isEmpty() && parts.stream().allMatch(p -> "AVAILABLE".equals(p.state()));
        boolean allUnverified = !parts.isEmpty() && parts.stream().allMatch(p -> "UNVERIFIED".equals(p.state()));
        String state = allAvailable && limitations.isEmpty() ? "AVAILABLE"
                : available ? "PARTIAL"
                : allUnverified && limitations.isEmpty() ? "UNVERIFIED"
                : unverified ? "PARTIAL" : "UNAVAILABLE";
        return new ArchiveResult(state, parts, limitations);
    }

    private ArchivePart uploadChunk(Chunk chunk, String safeBatch, Instant deadline, List<String> limitations)
            throws InterruptedException {
        long bytes = 0;
        String sha256 = "";
        try {
            chunk.gzip.close();
            checkDeadline(deadline);
            bytes = Files.size(chunk.path);
            sha256 = fileSha256(chunk.path, deadline);
            String filename = safeBatch + "-" + chunk.partId + "-" + sha256.substring(0, 16) + ".jsonl.gz";
            Duration requestTimeout = min(timeout, Duration.ofNanos(remainingNanos(deadline)));
            HttpRequest.Builder builder = HttpRequest.newBuilder(origin.resolve("upload/" + filename))
                    .timeout(requestTimeout).header("Content-Type", "application/gzip")
                    .PUT(HttpRequest.BodyPublishers.ofFile(chunk.path));
            if (!token.isEmpty()) builder.header("Authorization", "Bearer " + token);
            // 远端幂等契约尚未验证；首版不自动重试，避免把响应丢失误判为上传未执行。
            CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(builder.build(), ignored -> new BoundedBody());
            HttpResponse<byte[]> response;
            try {
                response = pending.get(Math.min(durationNanos(timeout), remainingNanos(deadline)), TimeUnit.NANOSECONDS);
            } catch (InterruptedException ex) {
                pending.cancel(true);
                throw ex;
            } catch (TimeoutException ex) {
                pending.cancel(true);
                return new ArchivePart(chunk.partId, null, bytes, sha256, "FAILED", "FluxDrop 上传超时，远端接收情况未知");
            } catch (ExecutionException ex) {
                pending.cancel(true);
                return new ArchivePart(chunk.partId, null, bytes, sha256, "FAILED", "FluxDrop 上传未确认，远端接收情况未知");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new ArchivePart(chunk.partId, null, bytes, sha256, "FAILED", "FluxDrop 返回 HTTP " + response.statusCode());
            }
            String link = downloadReference(response);
            if (link == null) {
                return new ArchivePart(chunk.partId, null, bytes, sha256, "UNVERIFIED", "上传返回成功，但未返回可验证的同源下载引用");
            }
            return new ArchivePart(chunk.partId, link, bytes, sha256, "AVAILABLE", "上传成功；sha256 为客户端上传文件摘要");
        } catch (InterruptedException ex) {
            throw ex;
        } catch (DeadlineExceededException ex) {
            return new ArchivePart(chunk.partId, null, bytes, sha256, "FAILED", "归档截止时间已到");
        } catch (QuotaExceededException ex) {
            return new ArchivePart(chunk.partId, null, bytes, sha256, "FAILED", "临时目录总容量达到上限");
        } catch (IOException | RuntimeException ex) {
            return new ArchivePart(chunk.partId, null, bytes, sha256, "FAILED", "归档分块处理失败");
        } finally {
            cleanup(chunk.path, limitations);
        }
    }

    private byte[] jsonLine(LogEvent event) throws IOException {
        Objects.requireNonNull(event);
        // 先拒绝明显超大字符串，避免为单条超大日志分配相同大小的副本。
        long minimumChars = (long) event.message().length() + event.eventId().length();
        for (var label : event.labels().entrySet()) minimumChars += (long) label.getKey().length() + label.getValue().length();
        if (minimumChars > chunkBytes) throw new EventTooLargeException();
        ObjectNode json = Json.MAPPER.createObjectNode().put("time", event.time().toString())
                .put("message", redact(event.message())).put("eventId", redact(event.eventId()));
        ObjectNode labels = json.putObject("labels");
        event.labels().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry ->
                labels.put(entry.getKey(), SECRET_LABEL.matcher(entry.getKey()).matches() ? "[REDACTED]" : redact(entry.getValue())));
        LimitedOutput bytes = new LimitedOutput(chunkBytes);
        try {
            Json.MAPPER.writeValue(bytes, json);
        } catch (IOException ex) {
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof EventTooLargeException) throw new EventTooLargeException();
            }
            throw ex;
        }
        bytes.write('\n');
        return bytes.toByteArray();
    }

    private static String redact(String value) {
        String safe = URL_CREDENTIALS.matcher(value).replaceAll("$1[REDACTED]@");
        safe = QUOTED_SECRETS.matcher(safe).replaceAll("$1$2[REDACTED]$2");
        safe = AUTHORIZATION.matcher(safe).replaceAll("$1[REDACTED]");
        safe = COOKIES.matcher(safe).replaceAll("$1[REDACTED]");
        safe = SECRETS.matcher(safe).replaceAll("$1[REDACTED]");
        return QUERY_VALUES.matcher(safe).replaceAll("$1[REDACTED]");
    }

    private String downloadReference(HttpResponse<byte[]> response) {
        for (String location : response.headers().allValues("Location")) {
            String valid = validReference(location);
            if (valid != null) return valid;
        }
        String body = new String(response.body(), StandardCharsets.UTF_8).strip();
        try {
            JsonNode json = Json.MAPPER.readTree(body);
            String link = jsonReference(json, 0);
            if (link != null) return link;
        } catch (IOException ignored) { }
        // 文本响应只接受完整的一个下载引用，不从任意 HTML 或错误正文里猜链接。
        return validReference(body);
    }

    private String jsonReference(JsonNode node, int depth) {
        if (node == null || depth > 16) return null;
        if (node.isTextual()) return validReference(node.asText());
        if (node.isObject()) {
            for (String field : List.of("url", "download_url", "downloadUrl", "download_link", "file_url")) {
                JsonNode value = node.get(field);
                if (value != null && value.isTextual()) {
                    String link = validReference(value.asText());
                    if (link != null) return link;
                }
            }
            for (JsonNode child : node) {
                if (child.isContainerNode()) {
                    String link = jsonReference(child, depth + 1);
                    if (link != null) return link;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String link = jsonReference(child, depth + 1);
                if (link != null) return link;
            }
        }
        return null;
    }

    private String validReference(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI supplied = URI.create(value.strip());
            if (!supplied.isAbsolute() && !value.strip().startsWith("/f/")) return null;
            URI uri = origin.resolve(supplied);
            if (!origin.getScheme().equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null || !origin.getHost().equalsIgnoreCase(uri.getHost())
                    || effectivePort(origin) != effectivePort(uri) || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.getPath() == null || !uri.getPath().startsWith("/f/") || uri.getPath().length() <= 3
                    || !uri.normalize().getPath().equals(uri.getPath()) || uri.getPath().contains("\\")
                    || uri.getPath().codePoints().anyMatch(Character::isISOControl)) return null;
            for (String segment : uri.getPath().split("/")) if (segment.equals(".") || segment.equals("..")) return null;
            return uri.toASCIIString();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private long spoolSize(Instant deadline) throws IOException, InterruptedException {
        long[] bytes = {0};
        try {
            Files.walkFileTree(spoolDir, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedWalkException();
                    if (remainingNanos(deadline) <= 0) throw new DeadlineExceededException();
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedWalkException();
                    if (remainingNanos(deadline) <= 0) throw new DeadlineExceededException();
                    if (attrs.isRegularFile()) {
                        if (attrs.size() > maxSpoolBytes - bytes[0]) throw new QuotaExceededException();
                        bytes[0] += attrs.size();
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (InterruptedWalkException ex) {
            throw new InterruptedException("归档已中断");
        }
        return bytes[0];
    }

    private static void checkDeadline(Instant deadline) throws InterruptedException, DeadlineExceededException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("归档已中断");
        if (remainingNanos(deadline) <= 0) throw new DeadlineExceededException();
    }

    private static String fileSha256(Path file, Instant deadline) throws IOException, InterruptedException {
        MessageDigest digest = digest();
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) >= 0) {
                checkDeadline(deadline);
                digest.update(buffer, 0, length);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String safeBatch(String batchId) {
        String original = batchId == null ? "batch" : batchId;
        String safe = original.replaceAll("[^A-Za-z0-9_-]", "_");
        if (safe.isBlank()) safe = "batch";
        if (safe.length() > 40) safe = safe.substring(0, 40);
        String hash = HexFormat.of().formatHex(digest().digest(original.getBytes(StandardCharsets.UTF_8))).substring(0, 8);
        return safe + "-" + hash + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 不可用"); }
    }

    private static int effectivePort(URI uri) { return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80; }
    private static Duration min(Duration first, Duration second) { return first.compareTo(second) < 0 ? first : second; }
    private static long durationNanos(Duration duration) {
        try { return duration.toNanos(); } catch (ArithmeticException ex) { return Long.MAX_VALUE; }
    }
    private static long remainingNanos(Instant deadline) { return durationNanos(Duration.between(Instant.now(), deadline)); }
    private static void addLimitation(List<String> limitations, String limitation) {
        if (!limitations.contains(limitation)) limitations.add(limitation);
    }
    private static void cleanup(Path file, List<String> limitations) {
        try { Files.deleteIfExists(file); }
        catch (IOException | RuntimeException ex) { addLimitation(limitations, "本次临时归档文件清理失败，需要检查 spool 目录"); }
    }

    private static boolean ownedDirectory(Path directory) throws IOException {
        Path marker = directory.resolve(OWNER_MARKER);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) != OWNER_CONTENT.length) return false;
        try (var input = Files.newInputStream(marker, LinkOption.NOFOLLOW_LINKS)) {
            return java.util.Arrays.equals(input.readNBytes(OWNER_CONTENT.length + 1), OWNER_CONTENT);
        }
    }

    private static boolean removeEmptyWorkingDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return false;
        try (var children = Files.newDirectoryStream(directory)) {
            for (Path child : children) if (!child.getFileName().toString().equals(OWNER_MARKER)) return false;
        }
        Files.deleteIfExists(directory.resolve(OWNER_MARKER));
        Files.deleteIfExists(directory);
        return true;
    }

    @Override public String toString() { return "FluxDropArchiveSink{origin=" + origin + ", maxSpoolBytes=" + maxSpoolBytes + "}"; }

    private static final class Chunk {
        final Path path;
        final String partId;
        final GZIPOutputStream gzip;
        long uncompressedBytes;
        Chunk(Path path, String partId, long compressedLimit) throws IOException {
            this.path = path;
            this.partId = partId;
            OutputStream file = Files.newOutputStream(path);
            try { this.gzip = new GZIPOutputStream(new QuotaOutput(file, compressedLimit)); }
            catch (IOException | RuntimeException ex) {
                try { file.close(); } catch (IOException ignored) { }
                Files.deleteIfExists(path);
                throw ex;
            }
        }
    }

    private static final class QuotaOutput extends FilterOutputStream {
        private final long limit;
        private long bytes;
        QuotaOutput(OutputStream output, long limit) { super(output); this.limit = limit; }
        @Override public void write(int value) throws IOException {
            if (bytes >= limit) throw new QuotaExceededException();
            out.write(value); bytes++;
        }
        @Override public void write(byte[] data, int offset, int length) throws IOException {
            if (length > limit - bytes) throw new QuotaExceededException();
            out.write(data, offset, length); bytes += length;
        }
    }

    private static final class LimitedOutput extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final long limit;
        LimitedOutput(long limit) { this.limit = limit; }
        @Override public void write(int value) throws IOException {
            if (bytes.size() >= limit) throw new EventTooLargeException();
            bytes.write(value);
        }
        @Override public void write(byte[] data, int offset, int length) throws IOException {
            if (length > limit - bytes.size()) throw new EventTooLargeException();
            bytes.write(data, offset, length);
        }
        byte[] toByteArray() { return bytes.toByteArray(); }
    }

    /** 在 HTTP 客户端内就限制响应，避免先把未知大小正文全部读入内存。 */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public java.util.concurrent.CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            try {
                for (ByteBuffer buffer : buffers) {
                    if (buffer.remaining() > MAX_RESPONSE_BYTES - bytes.size()) throw new IOException("response_limit");
                    byte[] data = new byte[buffer.remaining()];
                    buffer.get(data);
                    bytes.writeBytes(data);
                }
                subscription.request(1);
            } catch (IOException | RuntimeException ex) {
                subscription.cancel();
                body.completeExceptionally(ex);
            }
        }
        @Override public void onError(Throwable throwable) { body.completeExceptionally(throwable); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }

    private static final class QuotaExceededException extends IOException { }
    private static final class EventTooLargeException extends IOException { }
    private static final class DeadlineExceededException extends IOException { }
    private static final class InterruptedWalkException extends IOException { }
}
