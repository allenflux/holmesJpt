package dev.holmes.logs;

import dev.holmes.logs.LogModels.Task;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** 任务选择范围与日志范围独立；源更新时间只用于检索，不能证明执行起止。 */
final class LogLookupPlan {
    private static final Duration MAX_WINDOW = Duration.ofHours(72);
    record Window(Instant from, Instant to, String basis, boolean truncated) { }

    static Window forTask(Task task, LogRequest request, Instant observedAt) {
        if (!request.automaticWindow()) return new Window(request.from(), request.to(), "BATCH_WINDOW", false);
        Instant from = null, to = null;
        String basis = "BATCH_WINDOW";
        if (task.finalAttempt() != null && task.finalAttempt().startedAt() != null && task.finalAttempt().endedAt() != null) {
            from = task.attempts().stream().map(LogModels.Attempt::startedAt).filter(java.util.Objects::nonNull).min(Instant::compareTo).orElseThrow();
            to = task.attempts().stream().map(LogModels.Attempt::endedAt).filter(java.util.Objects::nonNull).max(Instant::compareTo).orElseThrow();
            basis = "VERIFIED_ATTEMPT_WINDOW";
        } else if (task.schedulerFacts() != null && task.schedulerFacts().createdAt() != null && task.schedulerFacts().updatedAt() != null) {
            from = task.schedulerFacts().createdAt(); to = task.schedulerFacts().updatedAt();
            basis = "AUTOMATIC_TASK_LIFECYCLE";
        }
        if (from == null || to == null || to.isBefore(from) || from.isAfter(observedAt) || to.isAfter(observedAt))
            return new Window(request.from(), request.to(), "BATCH_WINDOW", false);
        try {
            from = from.minusSeconds(120).truncatedTo(ChronoUnit.MILLIS);
            to = to.plusSeconds(120).truncatedTo(ChronoUnit.MILLIS).plusMillis(1);
            if (to.isAfter(observedAt)) to = observedAt.truncatedTo(ChronoUnit.MILLIS).plusMillis(1);
        } catch (java.time.DateTimeException | ArithmeticException invalid) {
            // 极端或损坏的时间事实不能中止其余任务的采集。
            return new Window(request.from(), request.to(), "BATCH_WINDOW", false);
        }
        boolean truncated = Duration.between(from, to).compareTo(MAX_WINDOW) > 0;
        if (truncated) from = to.minus(MAX_WINDOW);
        if (!from.isBefore(to)) return new Window(request.from(), request.to(), "BATCH_WINDOW", false);
        return new Window(from, to, basis, truncated);
    }
}
