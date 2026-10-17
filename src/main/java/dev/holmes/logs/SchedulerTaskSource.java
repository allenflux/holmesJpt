package dev.holmes.logs;

import com.fasterxml.jackson.databind.JsonNode;
import dev.holmes.Json;
import dev.holmes.logs.LogModels.AssociationScope;
import dev.holmes.logs.LogModels.Task;
import dev.holmes.logs.LogModels.SchedulerFacts;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 只读调度器分页列表；列表状态不冒充已验证的 prompt / 重试执行清单。 */
public final class SchedulerTaskSource implements TaskSource {
    private static final int MAX_PAGE_BYTES = 8 << 20;
    private static final long MAX_SCAN_BYTES = 64L << 20;
    private static final Semaphore REQUESTS = new Semaphore(2, true);
    private final LogConfig logs;
    private final SchedulerConfig scheduler;
    private final URI origin;
    private final HttpClient http;
    private final int pageSize;

    public SchedulerTaskSource(LogConfig logs, SchedulerConfig scheduler) {
        this.logs = logs; this.scheduler = scheduler;
        URI url = LogConfig.uri(scheduler.url().toString());
        if (!url.getPath().isEmpty() && !url.getPath().equals("/")) throw new IllegalArgumentException("调度器必须使用服务根地址");
        if (scheduler.pageSize() < 1 || scheduler.pageSize() > 500 || scheduler.maxTasks() < 1 || scheduler.maxTasks() > 200_000
                || scheduler.timeout().isZero() || scheduler.timeout().isNegative()
                || scheduler.scanTimeout().isZero() || scheduler.scanTimeout().isNegative()
                || scheduler.timezone() == null || scheduler.token() == null
                || !scheduler.token().isEmpty() && !scheduler.token().matches("[A-Za-z0-9._~+/-]+=*")) {
            throw new IllegalArgumentException("调度器读取配置无效");
        }
        pageSize = Math.min(scheduler.pageSize(), scheduler.maxTasks());
        origin = URI.create(url.getScheme() + "://" + url.getRawAuthority() + "/");
        http = HttpClient.newBuilder().connectTimeout(scheduler.timeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public boolean asynchronousSelection() { return true; }

    @Override public TaskSnapshot.Selection select(LogRequest request) throws IOException, InterruptedException {
        if (!request.source().equals(logs.source()) || !logs.namespaces().contains(request.namespace())
                || !request.from().isBefore(request.to())) throw new IllegalArgumentException("调度器请求范围无效");
        String dates = "start_date=" + request.from().atZone(scheduler.timezone()).toLocalDate()
                + "&end_date=" + request.to().minusNanos(1).atZone(scheduler.timezone()).toLocalDate();
        Instant deadline = Instant.now().plus(scheduler.scanTimeout());
        Scan scan = new Scan();
        // 首次请求失败不退回手工文件或制造一个空的健康任务名单。
        Page first = fetch(1, dates, MAX_PAGE_BYTES, deadline);
        scan.accept(first);
        long pages = Math.max(1, first.total / pageSize + (first.total % pageSize == 0 ? 0 : 1));
        // 不为不足一页的剩余额度再读取整页，扫描行数不会超过硬上限。
        long maxPages = Math.max(1, scheduler.maxTasks() / pageSize);
        if (pages > maxPages) { pages = maxPages; scan.incomplete("SCHEDULER_TASK_LIMIT"); }
        var workers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (int next = 2; next <= pages;) {
                if (!Instant.now().isBefore(deadline)) { scan.incomplete("SCHEDULER_SCAN_TIMEOUT"); break; }
                if (scan.rawRows >= scheduler.maxTasks()) { scan.incomplete("SCHEDULER_TASK_LIMIT"); break; }
                long remainingBytes = MAX_SCAN_BYTES - scan.bytes;
                int count = (int) Math.min(2, pages - next + 1);
                if (remainingBytes < count) { scan.incomplete("SCHEDULER_BYTE_LIMIT"); break; }
                // 两个在途响应先分配各自额度，避免并行读取超过全批字节上限。
                long allowance = Math.min(MAX_PAGE_BYTES, remainingBytes / count);
                List<Future<Page>> pending = new ArrayList<>();
                for (int index = 0; index < count; index++) {
                    final int page = next++;
                    pending.add(workers.submit(() -> fetch(page, dates, allowance, deadline)));
                }
                boolean stop = false;
                try {
                    for (Future<Page> future : pending) {
                        long nanos = Duration.between(Instant.now(), deadline).toNanos();
                        if (nanos <= 0) throw new TimeoutException();
                        Page page = future.get(nanos, TimeUnit.NANOSECONDS);
                        scan.accept(page);
                        if (page.rows.isEmpty() && (long) (page.number - 1) * pageSize < page.total) {
                            scan.incomplete("SCHEDULER_PAGE_GAP"); stop = true;
                        }
                    }
                } catch (TimeoutException e) {
                    scan.incomplete("SCHEDULER_SCAN_TIMEOUT"); stop = true;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof InterruptedException) throw new InterruptedException();
                    scan.incomplete(cause instanceof SourceFailure failure ? failure.code : "SCHEDULER_QUERY_FAILED"); stop = true;
                } finally {
                    for (Future<Page> future : pending) if (!future.isDone()) future.cancel(true);
                }
                if (stop) break;
            }
        } finally { workers.shutdownNow(); }
        if (!Instant.now().isBefore(deadline)) scan.incomplete("SCHEDULER_SCAN_TIMEOUT");
        return scan.selection(request);
    }

    private Page fetch(int page, String dates, long allowance, Instant deadline) throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            try { return fetchOnce(page, dates, allowance, deadline); }
            catch (SourceFailure failure) {
                // GET 只读可安全重试一次瞬时网关错误，仍使用同一个整体截止时间。
                if (attempt != 0 || !Set.of("SCHEDULER_HTTP_502", "SCHEDULER_HTTP_503", "SCHEDULER_HTTP_504").contains(failure.code)
                        || !Instant.now().isBefore(deadline)) throw failure;
            }
        }
    }

    private Page fetchOnce(int page, String dates, long allowance, Instant deadline) throws IOException, InterruptedException {
        long remaining = Duration.between(Instant.now(), deadline).toNanos();
        if (remaining <= 0 || !REQUESTS.tryAcquire(remaining, TimeUnit.NANOSECONDS)) throw new SourceFailure("SCHEDULER_SCAN_TIMEOUT");
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            remaining = Duration.between(Instant.now(), deadline).toNanos();
            if (remaining <= 0) throw new SourceFailure("SCHEDULER_SCAN_TIMEOUT");
            long timeout = Math.min(remaining, scheduler.timeout().toNanos());
            URI endpoint = origin.resolve("api/task-statistics/page?" + dates + "&page=" + page + "&page_size=" + pageSize);
            var builder = HttpRequest.newBuilder(endpoint).GET().timeout(Duration.ofNanos(timeout)).header("Accept", "application/json");
            if (!scheduler.token().isEmpty()) builder.header("Authorization", "Bearer " + scheduler.token());
            pending = http.sendAsync(builder.build(), info -> info.statusCode() == 200
                    ? new LimitedBody(allowance) : new StatusBody(info.statusCode()));
            HttpResponse<byte[]> response = pending.get(timeout, TimeUnit.NANOSECONDS);
            return parse(response.body(), page);
        } catch (TimeoutException e) {
            if (pending != null) pending.cancel(true);
            throw new SourceFailure("SCHEDULER_TIMEOUT");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            while (!(cause instanceof SourceFailure) && cause != null && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof java.net.http.HttpTimeoutException) throw new SourceFailure("SCHEDULER_TIMEOUT");
            throw cause instanceof SourceFailure failure ? failure : new SourceFailure("SCHEDULER_CONNECTION_FAILED");
        } catch (InterruptedException e) {
            if (pending != null) pending.cancel(true);
            throw e;
        } finally { REQUESTS.release(); }
    }

    private Page parse(byte[] body, int number) throws IOException {
        JsonNode data;
        try {
            JsonNode root = Json.MAPPER.readTree(body);
            data = root == null ? null : root.get("data");
        } catch (IOException | RuntimeException e) { throw new SourceFailure("SCHEDULER_INVALID_RESPONSE"); }
        if (data == null || !data.isObject() || !data.path("tasks").isArray()
                || !data.path("page").isIntegralNumber() || data.path("page").asInt() != number
                || !data.path("page_size").isIntegralNumber() || data.path("page_size").asInt() != pageSize
                || !data.path("total_count").isIntegralNumber() || !data.path("total_count").canConvertToLong()
                || data.path("total_count").asLong() < 0 || data.path("tasks").size() > pageSize) {
            throw new SourceFailure("SCHEDULER_INVALID_RESPONSE");
        }
        if (!data.path("timezone").asText().equals(scheduler.timezone().getId())) throw new SourceFailure("SCHEDULER_TIMEZONE_MISMATCH");
        Instant fetched;
        try { fetched = time(data.path("fetched_at")); }
        catch (RuntimeException e) { throw new SourceFailure("SCHEDULER_INVALID_RESPONSE"); }
        List<Row> rows = new ArrayList<>(); Set<String> blocked = new HashSet<>(); LinkedHashSet<String> flags = new LinkedHashSet<>();
        for (JsonNode task : data.path("tasks")) {
            String taskId = task.path("task_id").asText("");
            if (taskId.isBlank()) taskId = task.path("uuid").asText("");
            try {
                if (!task.isObject() || !LogRequest.validId(taskId)
                        || task.hasNonNull("uuid") && !task.path("uuid").isTextual()
                        || task.hasNonNull("task_id") && !task.path("task_id").isTextual()
                        || task.hasNonNull("uuid") && !task.path("uuid").asText().equals(taskId)
                        || task.hasNonNull("task_id") && !task.path("task_id").asText().equals(taskId)
                        || !task.path("record_id").isTextual() || !LogRequest.validId(task.path("record_id").asText())
                        || !task.path("status").isIntegralNumber() || !task.path("status").canConvertToInt()) throw new IllegalArgumentException();
                int status = task.path("status").asInt();
                if (!Set.of(-1, 0, 1, 2, 3).contains(status)) throw new IllegalArgumentException();
                Instant created = time(task.path("created_at")), updated = time(task.path("updated_at"));
                if (updated.isBefore(created)) throw new IllegalArgumentException();
                rows.add(new Row(taskId, task.path("record_id").asText(), status, created, updated,
                        factCode(task.path("task_type")), factCode(task.path("error_code"))));
            } catch (RuntimeException e) {
                if (LogRequest.validId(taskId)) blocked.add(taskId);
                flags.add("SCHEDULER_INVALID_TASK_RECORD");
            }
        }
        long total = data.path("total_count").asLong();
        long expected = Math.min(pageSize, Math.max(0, total - (long) (number - 1) * pageSize));
        if (data.path("tasks").size() != expected) flags.add("SCHEDULER_PAGE_GAP");
        return new Page(number, total, fetched, rows, blocked, flags, data.path("tasks").size(), body.length);
    }

    private Instant time(JsonNode value) {
        if (!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException();
        String text = value.asText().trim();
        try { return Instant.parse(text); } catch (DateTimeParseException ignored) { }
        try { return OffsetDateTime.parse(text).toInstant(); } catch (DateTimeParseException ignored) { }
        return LocalDateTime.parse(text.replace(' ', 'T')).atZone(scheduler.timezone()).toInstant();
    }

    private static String factCode(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return "";
        if (!value.isTextual() || value.asText().length() > 256 || value.asText().codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        return value.asText();
    }

    private record Row(String taskId, String recordId, int status, Instant created, Instant updated, String taskType, String errorCode) { }
    private record Page(int number, long total, Instant fetched, List<Row> rows, Set<String> blocked,
                        Set<String> flags, int rawRows, long bytes) { }

    private final class Scan {
        final Map<String, Row> records = new HashMap<>();
        final Map<String, Row> newest = new HashMap<>();
        final Set<String> blocked = new HashSet<>();
        final LinkedHashSet<String> flags = new LinkedHashSet<>(List.of("TASK_STATUS_FROM_SCHEDULER_API",
                "TASK_ATTEMPT_UNVERIFIED", "SCHEDULER_CREATED_TIME_SELECTION", "SCHEDULER_NON_ATOMIC_PAGINATION"));
        long firstTotal = -1, rawRows, bytes;
        Instant observed;

        void incomplete(String flag) { flags.add(flag); flags.add("TASK_SELECTION_INCOMPLETE"); }
        void accept(Page page) {
            bytes += page.bytes;
            if (firstTotal < 0) firstTotal = page.total;
            else if (firstTotal != page.total) incomplete("SCHEDULER_TOTAL_CHANGED");
            if (observed == null || page.fetched.isAfter(observed)) observed = page.fetched;
            blocked.addAll(page.blocked);
            page.flags.forEach(this::incomplete);
            for (Row row : page.rows) {
                Row duplicate = records.put(row.recordId, row);
                if (duplicate != null) {
                    incomplete("SCHEDULER_DUPLICATE_RECORD");
                    if (!duplicate.taskId.equals(row.taskId)) {
                        blocked.add(duplicate.taskId); blocked.add(row.taskId); incomplete("SCHEDULER_RECORD_ID_CONFLICT"); continue;
                    }
                }
                Row previous = newest.get(row.taskId);
                if (previous == null || row.updated.isAfter(previous.updated)) newest.put(row.taskId, row);
                else if (row.updated.equals(previous.updated) && (row.status != previous.status || !row.created.equals(previous.created)
                        || !row.taskType.equals(previous.taskType) || !row.errorCode.equals(previous.errorCode))) {
                    blocked.add(row.taskId); incomplete("SCHEDULER_TASK_STATE_CONFLICT");
                }
                if (previous != null && !previous.recordId.equals(row.recordId)) flags.add("SCHEDULER_TASK_RECORDS_MERGED");
            }
            // 重复、冲突、格式无效的行同样消耗扫描预算。
            rawRows += page.rawRows;
        }

        TaskSnapshot.Selection selection(LogRequest request) throws IOException {
            List<Row> candidates = newest.values().stream().filter(row -> !blocked.contains(row.taskId) && row.status == -1)
                    .filter(row -> !row.created.isBefore(request.from()) && row.created.isBefore(request.to()))
                    .sorted(Comparator.comparing(Row::created).reversed().thenComparing(Row::taskId)).toList();
            List<Row> selected;
            if (request.mode().equals("explicit_task_ids")) {
                Set<String> ids = Set.copyOf(request.taskIds());
                selected = candidates.stream().filter(row -> ids.contains(row.taskId)).toList();
                if (selected.size() != ids.size()) {
                    if (!flags.contains("TASK_SELECTION_INCOMPLETE") || selected.isEmpty()) throw new SourceFailure("SCHEDULER_REQUESTED_TASK_UNAVAILABLE");
                    incomplete("SCHEDULER_REQUESTED_TASKS_NOT_ALL_FOUND");
                }
            } else selected = candidates.stream().limit(request.requestedCount()).toList();
            if (selected.isEmpty() && flags.contains("TASK_SELECTION_INCOMPLETE")) throw new SourceFailure("TASK_SELECTION_INCOMPLETE");
            if (selected.size() < request.requestedCount()) flags.add("FEWER_FAILED_TASKS_THAN_REQUESTED");
            List<Task> tasks = selected.stream().map(row -> new Task("task-" + TaskSnapshot.hex(request.source() + "\n"
                            + request.namespace() + "\n" + origin + "\n" + row.taskId).substring(0, 24), row.taskId, "failed", List.of(),
                    "", "", null, row.updated + ":" + row.status, AssociationScope.TASK_ONLY,
                    new SchedulerFacts(row.recordId, row.taskType, row.errorCode, row.created, row.updated))).toList();
            return new TaskSnapshot.Selection(tasks, observed.toString(), List.copyOf(flags));
        }
    }

    private static final class SourceFailure extends IOException {
        final String code;
        SourceFailure(String code) { super(code); this.code = code; }
    }

    /** 在响应头收到非 200 时就取消订阅，不读取远端错误正文。 */
    private static final class StatusBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> result;
        StatusBody(int status) { result = CompletableFuture.failedFuture(new SourceFailure("SCHEDULER_HTTP_" + status)); }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) { subscription.cancel(); }
        @Override public void onNext(List<ByteBuffer> items) { }
        @Override public void onError(Throwable error) { }
        @Override public void onComplete() { }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        final long allowance;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final CompletableFuture<byte[]> result = new CompletableFuture<>();
        Flow.Subscription subscription;
        LimitedBody(long allowance) { this.allowance = allowance; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> values) {
            for (ByteBuffer value : values) {
                if (value.remaining() > allowance - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new SourceFailure("SCHEDULER_BYTE_LIMIT")); return;
                }
                byte[] next = new byte[value.remaining()]; value.get(next); bytes.writeBytes(next);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
