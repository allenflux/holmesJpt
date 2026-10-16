package dev.holmes.logs;

import dev.holmes.WebStatus;
import dev.holmes.logs.LogModels.AssociationScope;
import dev.holmes.logs.LogModels.FetchResult;
import dev.holmes.logs.LogModels.LogEvent;
import dev.holmes.logs.LogModels.Task;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static dev.holmes.logs.LogInvestigationServiceTest.fixtureConfig;
import static dev.holmes.logs.LogInvestigationServiceTest.fixtureRequest;

class SchedulerBatchTest {
    @TempDir Path directory;

    @Test void submissionDoesNotWaitForRemoteSelectionAndCandidatesArePreserved() throws Exception {
        LogConfig config = fixtureConfig(directory, Map.of());
        CountDownLatch gate = new CountDownLatch(1), entered = new CountDownLatch(1);
        TaskSource source = asynchronous(request -> {
            entered.countDown(); gate.await(); return selected();
        });
        try (var service = service(config, source, events())) {
            var created = service.submit(fixtureRequest());
            assertEquals("PENDING", created.path("selection_status").asText());
            assertEquals(0, created.path("selected_count").asInt());
            assertFalse(created.has("_selection_pending"));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            gate.countDown();
            var result = service.await(created.path("batch_id").asText());
            assertEquals("PARTIAL", result.path("state").asText());
            assertEquals("READY", result.path("selection_status").asText());
            assertEquals(1, result.path("selected_count").asInt());
            assertEquals(1, result.path("processed_count").asInt());
            var task = result.path("tasks").get(0);
            assertEquals("TASK_ONLY", task.path("association_scope").asText());
            assertEquals("INCOMPLETE", task.path("signature").asText());
            assertEquals("INCOMPLETE_EVIDENCE", task.path("analysis_status").asText());
            assertEquals("CUDA_OOM", task.path("observed_signatures").get(0).asText());
            assertTrue(result.path("evidence").get(0).path("candidate_only").asBoolean());
            assertTrue(result.path("evidence").get(0).path("attempt_id").isNull());
            assertEquals("CUDA_OOM", result.path("observed_clusters").get(0).path("signature").asText());
            assertEquals(1, result.path("observed_clusters").get(0).path("task_count").asInt());
        } finally { gate.countDown(); }
    }

    @Test void recoveryUsesFixedTasksWithoutRefetchAndKeepsUnknownAttemptScope() throws Exception {
        LogConfig config = fixtureConfig(directory, Map.of());
        CountDownLatch logStarted = new CountDownLatch(1), hold = new CountDownLatch(1);
        AtomicInteger selections = new AtomicInteger();
        TaskSource source = asynchronous(request -> { selections.incrementAndGet(); return selected(); });
        String id;
        try (var original = service(config, source, (query, maxEvents, maxBytes, deadline) -> {
            logStarted.countDown(); hold.await(); return new FetchResult(List.of(), true, List.of(), 0);
        })) {
            id = original.submit(fixtureRequest()).path("batch_id").asText();
            assertTrue(logStarted.await(3, TimeUnit.SECONDS));
        }
        TaskSource noRefetch = asynchronous(request -> { throw new AssertionError("fixed selection must not be reread"); });
        try (var recovered = service(config, noRefetch, events())) {
            var result = recovered.await(id);
            assertEquals(1, selections.get());
            assertEquals(1, result.path("selected_count").asInt());
            assertEquals("TASK_ONLY", result.path("tasks").get(0).path("association_scope").asText());
            assertTrue(result.path("evidence").get(0).path("candidate_only").asBoolean());
        }
    }

    @Test void sourceFailureIsExplicitAndDoesNotBecomeAnEmptySuccessfulAnalysis() throws Exception {
        LogConfig config = fixtureConfig(directory, Map.of());
        TaskSource unavailable = asynchronous(request -> { throw new IOException("SCHEDULER_HTTP_503"); });
        try (var service = service(config, unavailable, events())) {
            var result = service.await(service.submit(fixtureRequest()).path("batch_id").asText());
            assertEquals("FAILED", result.path("state").asText());
            assertEquals("FAILED", result.path("selection_status").asText());
            assertTrue(result.path("limitations").toString().contains("TASK_SELECTION_FAILED"));
            assertTrue(result.path("limitations").toString().contains("SCHEDULER_HTTP_503"));
        }
    }

    @Test void remoteReadinessMeansConfiguredWithoutInventingCountsOrConnectionSuccess() throws Exception {
        LogConfig config = fixtureConfig(directory, Map.of());
        var status = new WebStatus(config, true, false, true).get();
        assertEquals("scheduler_api", status.path("task_source").asText());
        assertEquals("created_at", status.path("task_time_basis").asText());
        assertTrue(status.path("snapshot").path("ready").asBoolean());
        assertTrue(status.path("snapshot").path("failed_count").isNull());
        assertTrue(status.path("snapshot").path("observed_at").isNull());
    }

    private static TaskSnapshot.Selection selected() {
        Task task = new Task("task-remote-1", "remote-task-1", "failed", List.of(), "", "", null,
                "synthetic-version", AssociationScope.TASK_ONLY);
        return new TaskSnapshot.Selection(List.of(task), "2026-10-15T06:00:00Z",
                List.of("TASK_STATUS_FROM_SCHEDULER_API", "TASK_ATTEMPT_UNVERIFIED"));
    }
    private static LogSource events() {
        return (query, maxEvents, maxBytes, deadline) -> new FetchResult(List.of(new LogEvent(
                Instant.parse("2026-10-15T05:13:00Z"),
                "{\"task_id\":\"remote-task-1\",\"event\":\"execution_error\",\"exception_type\":\"torch.OutOfMemoryError\",\"exception_message\":\"CUDA out of memory\"}",
                Map.of("namespace", "prod", "pod", "synthetic-worker"), "synthetic-remote-event")), true, List.of(), 100);
    }
    private static LogInvestigationService service(LogConfig config, TaskSource source, LogSource logs) throws IOException {
        return new LogInvestigationService(config, logs, new FluxDropArchiveSink(config.fluxDropUrl(), "",
                config.dataDir().resolve("spool"), config.spoolBytes(), config.chunkBytes(), config.requestTimeout()), source);
    }
    private static TaskSource asynchronous(Selection action) {
        return new TaskSource() {
            @Override public boolean asynchronousSelection() { return true; }
            @Override public TaskSnapshot.Selection select(LogRequest request) throws IOException, InterruptedException { return action.select(request); }
        };
    }
    @FunctionalInterface private interface Selection {
        TaskSnapshot.Selection select(LogRequest request) throws IOException, InterruptedException;
    }
}
