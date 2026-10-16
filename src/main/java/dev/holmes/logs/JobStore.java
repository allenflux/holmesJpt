package dev.holmes.logs;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.holmes.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 单实例、有 TTL 和硬容量上限的轻量报告库；不保存原始日志全集。 */
public final class JobStore {
    private final Path directory;
    private final long maxBytes, maxReportBytes;

    public JobStore(Path directory, long maxBytes, long maxReportBytes) throws IOException {
        this.directory = StoragePaths.createDirectory(directory);
        this.maxBytes = maxBytes; this.maxReportBytes = maxReportBytes;
        cleanup();
    }

    public synchronized void save(String batchId, ObjectNode job) throws IOException {
        Path target = path(batchId);
        byte[] bytes = Json.MAPPER.writeValueAsBytes(job);
        if (bytes.length > maxReportBytes) throw new StorageLimit("单批报告超过容量限制");
        cleanup();
        // 原子替换时旧文件和临时文件会同时存在，二者都算入峰值额度。
        if (usedBytes() + bytes.length > maxBytes) throw new StorageLimit("轻量报告总容量已满");
        Path temp = Files.createTempFile(directory, "holmes-report-", ".tmp");
        try {
            Files.write(temp, bytes);
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    /** 为每个活跃批次预留最大报告空间，并额外预留一次原子替换的临时空间。 */
    public synchronized void checkNewJobCapacity() throws IOException {
        cleanup();
        int active = unfinished().size();
        if (usedBytes() + maxReportBytes * (active + 2L) > maxBytes)
            throw new StorageLimit("报告库无法为新批次预留检查点与终态空间");
    }

    public synchronized ObjectNode get(String batchId) throws IOException {
        Path target = path(batchId);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return null;
        ObjectNode job = read(target);
        try {
            if (!Instant.parse(job.path("expires_at").asText()).isAfter(Instant.now())) return null;
        } catch (RuntimeException e) { throw new IOException("报告过期时间无效"); }
        return job;
    }

    public synchronized List<ObjectNode> unfinished() throws IOException {
        cleanup();
        List<ObjectNode> jobs = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(p -> p.getFileName().toString().matches("[a-f0-9-]{36}\\.json"))
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                ObjectNode job;
                try { job = read(file); } catch (IOException e) { continue; }
                if (List.of("QUEUED", "RUNNING").contains(job.path("state").asText())) jobs.add(job);
            }
        }
        return jobs;
    }

    public synchronized void cleanup() throws IOException {
        Instant now = Instant.now();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                String name = file.getFileName().toString();
                if (name.startsWith("holmes-report-") && name.endsWith(".tmp")
                        && Files.getLastModifiedTime(file).toInstant().isBefore(now.minusSeconds(3600))) {
                    Files.deleteIfExists(file); continue;
                }
                if (!name.matches("[a-f0-9-]{36}\\.json")) continue;
                try {
                    ObjectNode job = read(file);
                    if (!Instant.parse(job.path("expires_at").asText()).isAfter(now)) Files.deleteIfExists(file);
                } catch (RuntimeException | IOException ignored) { /* 损坏文件不自动删除，仍计入容量。 */ }
            }
        }
    }

    private long usedBytes() throws IOException {
        long total = 0;
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) total += Files.size(path);
        }
        return total;
    }
    private ObjectNode read(Path path) throws IOException {
        if (Files.size(path) > maxReportBytes) throw new IOException("报告文件超过限制");
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes((int) Math.min(Integer.MAX_VALUE, maxReportBytes + 1));
            if (bytes.length > maxReportBytes) throw new IOException("报告文件超过限制");
            var node = Json.MAPPER.readTree(bytes);
            if (!(node instanceof ObjectNode object)) throw new IOException("报告格式错误");
            return object;
        }
    }
    private Path path(String batchId) {
        if (batchId == null || !batchId.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new IllegalArgumentException("batch_id 格式无效");
        Path path = directory.resolve(batchId + ".json");
        if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("报告文件不能是符号链接");
        return path;
    }
    public static final class StorageLimit extends IOException {
        public StorageLimit(String message) { super(message); }
    }
}
