package dev.holmes.logs;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 日志首版的不可变数据契约；跨环境范围由批次 source 与 namespace 固定。 */
public final class LogModels {
    private LogModels() { }

    public record LogEvent(Instant time, String message, Map<String, String> labels, String eventId) {
        public LogEvent {
            if (time == null || message == null) throw new IllegalArgumentException("日志缺少时间或正文");
            labels = Map.copyOf(labels == null ? Map.of() : labels);
            eventId = eventId == null ? "" : eventId;
        }
    }

    public record Attempt(String id, String promptId, Instant startedAt, Instant endedAt, String instanceId) { }

    /** TASK_ONLY 只证明逻辑任务 ID 关联，不证明 prompt、重试或最终执行归属。 */
    public enum AssociationScope { VERIFIED_ATTEMPTS, TASK_ONLY }

    public record Task(String taskRef, String taskId, String status, List<Attempt> attempts,
                       String finalAttemptId, String failureReason, Instant failedAt, String statusVersion,
                       AssociationScope associationScope) {
        public Task {
            attempts = List.copyOf(attempts);
            associationScope = associationScope == null ? AssociationScope.VERIFIED_ATTEMPTS : associationScope;
        }
        // 原快照与测试仍使用严格的已验证 attempt 语义，缺字段不能隐式启用回退。
        public Task(String taskRef, String taskId, String status, List<Attempt> attempts,
                    String finalAttemptId, String failureReason, Instant failedAt, String statusVersion) {
            this(taskRef, taskId, status, attempts, finalAttemptId, failureReason, failedAt, statusVersion,
                    AssociationScope.VERIFIED_ATTEMPTS);
        }
        public Attempt finalAttempt() {
            return attempts.stream().filter(a -> a.id().equals(finalAttemptId)).findFirst().orElse(null);
        }
    }

    public record LogQuery(String namespace, List<String> identifiers, Instant from, Instant to,
                           Map<String, String> streamFilters) {
        public LogQuery {
            identifiers = List.copyOf(identifiers);
            streamFilters = Map.copyOf(streamFilters == null ? Map.of() : streamFilters);
        }
    }

    public record FetchResult(List<LogEvent> events, boolean complete, List<String> limitations,
                              long responseBytes) {
        public FetchResult { events = List.copyOf(events); limitations = List.copyOf(limitations); }
    }

    public record ArchivePart(String partId, String downloadUrl, long bytes, String sha256,
                              String state, String message) { }
    public record ArchiveResult(String state, List<ArchivePart> parts, List<String> limitations) {
        public ArchiveResult { parts = List.copyOf(parts); limitations = List.copyOf(limitations); }
    }
}
