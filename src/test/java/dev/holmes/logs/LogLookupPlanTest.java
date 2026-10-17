package dev.holmes.logs;

import dev.holmes.logs.LogModels.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LogLookupPlanTest {
    private final Instant from = Instant.parse("2026-10-16T10:00:00Z"), to = from.plus(Duration.ofHours(24));
    private LogRequest request(boolean auto) { return new LogRequest("scheduler-victorialogs", "prod", "latest_failed", List.of(), 50, from, to, false, "none", auto); }
    private Task task(SchedulerFacts facts) { return new Task("task-ref", "task-id", "failed", List.of(), "", "", null, "source", AssociationScope.TASK_ONLY, facts); }

    @Test void sourceDatesChooseWindowWithoutClaimingAttempt() {
        Instant created = to.minusSeconds(900), updated = to.minusSeconds(300);
        Task task = task(new SchedulerFacts("record", "face_swap", "FAILED", created, updated));
        var plan = LogLookupPlan.forTask(task, request(true), to);
        assertEquals(created.minusSeconds(120), plan.from());
        assertEquals(updated.plusSeconds(120).plusMillis(1), plan.to());
        assertEquals("AUTOMATIC_TASK_LIFECYCLE", plan.basis());
        assertEquals(AssociationScope.TASK_ONLY, task.associationScope());
        assertNull(task.finalAttempt()); assertNull(task.failedAt());
    }
    @Test void updatedOutsideSelectionIsSearchedAutomatically() {
        var facts = new SchedulerFacts("record", "type", "FAILED", to.minusSeconds(30), to.plusSeconds(600));
        var plan = LogLookupPlan.forTask(task(facts), request(true), to.plusSeconds(1000));
        assertTrue(plan.to().isAfter(to));
        assertEquals(to.plusSeconds(720).plusMillis(1), plan.to());
    }
    @Test void manualAndLegacyKeepTheirExplicitBoundaries() {
        var facts = new SchedulerFacts("record", "type", "FAILED", to.minusSeconds(30), to.plusSeconds(600));
        var plan = LogLookupPlan.forTask(task(facts), request(false), to.plusSeconds(1000));
        assertEquals(from, plan.from()); assertEquals(to, plan.to()); assertEquals("BATCH_WINDOW", plan.basis());
    }
    @Test void lookupIsCappedAndTruncationRemainsVisible() {
        var facts = new SchedulerFacts("record", "type", "FAILED", to.minus(Duration.ofDays(10)), to.minusSeconds(600));
        var plan = LogLookupPlan.forTask(task(facts), request(true), to);
        assertTrue(plan.truncated()); assertEquals(Duration.ofHours(72), Duration.between(plan.from(), plan.to()));
    }
    @Test void futureOrAbsentFactsNeverInventAWindow() {
        for (var task : List.of(task(null), task(new SchedulerFacts("record", "type", "FAILED", to.plusSeconds(1), to.plusSeconds(10))),
                task(new SchedulerFacts("record", "type", "FAILED", Instant.MIN, to.minusSeconds(10))))) {
            var plan = LogLookupPlan.forTask(task, request(true), to);
            assertEquals(from, plan.from()); assertEquals(to, plan.to()); assertEquals("BATCH_WINDOW", plan.basis());
        }
    }
    @Test void verifiedAttemptsTakePrecedenceAndIncludePreviousAttempts() {
        var first = new Attempt("a1", "p1", from.minusSeconds(400), from.minusSeconds(300), "i1");
        var last = new Attempt("a2", "p2", to.minusSeconds(400), to.minusSeconds(300), "i2");
        var task = new Task("task-ref", "task-id", "failed", List.of(first, last), "a2", "", to.minusSeconds(300), "source");
        var plan = LogLookupPlan.forTask(task, request(true), to);
        assertEquals("VERIFIED_ATTEMPT_WINDOW", plan.basis());
        assertEquals(first.startedAt().minusSeconds(120), plan.from());
        assertEquals(last.endedAt().plusSeconds(120).plusMillis(1), plan.to());
    }
}
