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
            assertEquals("COLLECTED", task.path("collection_status").asText());
            assertEquals(1, task.path("collection_event_count").asInt());
            assertTrue(task.path("collection_limitations").isEmpty());
            assertFalse(result.path("collection_incomplete").asBoolean());
            assertTrue(result.path("attribution_incomplete").asBoolean());
            assertEquals("CUDA_OOM", task.path("observed_signatures").get(0).asText());
            assertTrue(result.path("evidence").get(0).path("candidate_only").asBoolean());
            assertTrue(result.path("evidence").get(0).path("attempt_id").isNull());
            assertEquals("CUDA_OOM", result.path("observed_clusters").get(0).path("signature").asText());
            assertEquals(1, result.path("observed_clusters").get(0).path("task_count").asInt());
        } finally { gate.countDown(); }
    }

    @Test void taskOnlyCollectionAcrossNineStreamsDoesNotInventSkippedContextCollection() throws Exception {
        LogConfig config = fixtureConfig(directory, Map.of());
        AtomicInteger calls = new AtomicInteger();
        LogSource logs = (query, maxEvents, maxBytes, deadline) -> {
            calls.incrementAndGet();
            List<LogEvent> events = java.util.stream.IntStream.range(0, 9).mapToObj(i -> new LogEvent(
                    Instant.parse("2026-10-15T05:13:00Z"),
                    "{\"task_id\":\"remote-task-1\",\"error_message\":\"CUDA out of memory\"}",
                    Map.of("namespace", "prod", "pod", "synthetic-worker-" + i), "synthetic-event-" + i)).toList();
            return new FetchResult(events, true, List.of(), 900);
        };
        try (var service = service(config, asynchronous(request -> selected()), logs)) {
            var result = service.await(service.submit(fixtureRequest()).path("batch_id").asText());

            assertEquals(1, calls.get());
            var task = result.path("tasks").get(0);
            assertEquals("COLLECTED", task.path("collection_status").asText());
            assertEquals(9, task.path("collection_event_count").asInt());
            assertFalse(task.path("collection_limitations").toString().contains("CONTEXT_STREAM_LIMIT"));
            assertFalse(task.path("quality_flags").toString().contains("INCOMPLETE_INPUT"));
            assertFalse(result.path("collection_incomplete").asBoolean());
            assertTrue(result.path("attribution_incomplete").asBoolean());
            assertEquals("PARTIAL", result.path("state").asText());
            assertEquals("INCOMPLETE", task.path("signature").asText());
        }
    }

    @Test void incompleteTaskSelectionDoesNotMislabelSuccessfulLogCollection() throws Exception {
        LogConfig config = fixtureConfig(directory, Map.of());
        var fixed = new TaskSnapshot().select(config.tasksFile(), LogRequest.parse(
                LogInvestigationServiceTest.explicit("none", "task-B"), config));
        TaskSource source = asynchronous(request -> new TaskSnapshot.Selection(fixed.tasks(), fixed.observedAt(),
                List.of("TASK_SELECTION_INCOMPLETE", "SCHEDULER_PAGE_GAP")));
        LogSource logs = (query, maxEvents, maxBytes, deadline) -> new FetchResult(List.of(), true, List.of(), 37);
        try (var service = service(config, source, logs)) {
            var result = service.await(service.submit(fixtureRequest()).path("batch_id").asText());

            assertFalse(result.path("selection_complete").asBoolean());
            assertFalse(result.path("collection_incomplete").asBoolean());
            assertFalse(result.path("attribution_incomplete").asBoolean());
            assertEquals("NO_MATCHES", result.path("tasks").get(0).path("collection_status").asText());
            assertEquals("PARTIAL", result.path("state").asText());
        }
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
