package dev.holmes.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import dev.holmes.Json;
import dev.holmes.LlmClient;
import dev.holmes.logs.LogModels.FetchResult;
import dev.holmes.logs.LogModels.LogEvent;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LogInvestigationServiceTest {
    @TempDir Path directory;
    private final List<LogInvestigationService> services = new ArrayList<>();
    private HttpServer fluxDrop;
    private final AtomicInteger uploads = new AtomicInteger();

    @AfterEach void close() {
        services.forEach(LogInvestigationService::close);
        if (fluxDrop != null) fluxDrop.stop(0);
    }

    @Test void offlineFixtureAggregatesSixFinalFailedTasksAndPreservesClusterDenominator() throws Exception {
        var config = fixtureConfig(directory, Map.of());
        var service = start(config, new FileLogSource(config.logsFile()));

        var report = completed(service, service.submit(fixtureRequest()));

        assertEquals(6, report.path("selected_count").asInt());
        assertEquals(6, report.path("processed_count").asInt());
        assertEquals(6, clusterTotal(report));
        assertEquals(2, clusterCount(report, "CUDA_OOM"));
        assertEquals(1, clusterCount(report, "TIMEOUT"));
        assertEquals(1, clusterCount(report, "HTTP_403"));
        assertEquals(1, clusterCount(report, "UNKNOWN"));
        assertEquals(1, clusterCount(report, "INCOMPLETE"));
        assertFalse(report.toString().contains("task-running"));
        assertFalse(report.toString().contains("task-G"));
        assertFalse(report.has("_selected_tasks"));
    }

    @Test void latestFailedSelectionHonorsHalfOpenFailureTimeWindow() throws Exception {
        var config = fixtureConfig(directory, Map.of());
        var service = start(config, new FileLogSource(config.logsFile()));
        ObjectNode request = fixtureRequest();
        ((ObjectNode) request.get("selection")).put("from", "2026-10-15T05:24:00Z").put("to", "2026-10-15T05:39:00Z");

        var report = completed(service, service.submit(request));

        assertEquals(2, report.path("selected_count").asInt());
        assertEquals(2, clusterTotal(report));
        assertEquals(List.of("task-C", "task-B"), taskIds(report));
        assertTrue(report.path("limitations").toString().contains("FEWER_FAILED_TASKS_THAN_REQUESTED"));
    }

    @Test void automaticUsesFreshServerWindowAndOnlyConfiguredSourceAndNamespaces() throws Exception {
        for (String namespaces : List.of("zulu,alpha", "zulu,prod,alpha")) {
            var config = fixtureConfig(directory.resolve(namespaces.contains("prod") ? "prod" : "alpha"), Map.of(
                    "HOLMES_LOG_TASKS_FILE", "", "HOLMES_LOG_NAMESPACES", namespaces, "HOLMES_LOG_SOURCE", "synthetic-readonly"));
            AtomicReference<LogRequest> selected = new AtomicReference<>();
            AtomicInteger logCalls = new AtomicInteger();
            var service = start(config, (query, maxEvents, maxBytes, deadline) -> {
                logCalls.incrementAndGet(); return new FetchResult(List.of(), true, List.of(), 0);
            }, request -> {
                selected.set(request); return new TaskSnapshot.Selection(List.of(), Instant.now().toString(), List.of());
            }, null);
            Instant before = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            ObjectNode accepted = service.submitAutomatic();
            Instant after = Instant.now();
            var report = completed(service, accepted);
            LogRequest actual = selected.get();

            assertFalse(actual.to().isBefore(before));
            assertFalse(actual.to().isAfter(after));
            assertEquals(0, actual.to().getNano() % 1_000_000);
            assertEquals(Duration.ofHours(24), Duration.between(actual.from(), actual.to()));
            assertEquals("synthetic-readonly", actual.source());
            assertEquals(namespaces.contains("prod") ? "prod" : "alpha", actual.namespace());
            assertEquals("latest_failed", actual.mode());
            assertEquals(50, actual.requestedCount());
            assertTrue(actual.taskIds().isEmpty());
            assertFalse(actual.archiveRequired());
            assertEquals("none", actual.narrative());
            assertEquals("final_failed", report.path("request").path("attempt_scope").asText());
            assertEquals("none", report.path("request").path("raw_archive").asText());
            assertEquals("NOT_REQUESTED", report.path("narrative_status").asText());
            assertEquals(0, logCalls.get());
            JsonNode saved = Json.MAPPER.readTree(Files.readAllBytes(config.dataDir().resolve("reports")
                    .resolve(accepted.path("batch_id").asText() + ".json")));
            assertEquals(report.path("request"), saved.path("request"));
        }
    }

    @Test void automaticSnapshotUsesObservationUpperBoundAndPreservesExistingRuleEvidence() throws Exception {
        var config = fixtureConfig(directory, Map.of());
        ObjectNode snapshot = (ObjectNode) Json.MAPPER.readTree(Files.readAllBytes(config.tasksFile()));
        Instant observed = Instant.parse(snapshot.path("observed_at").asText());
        ObjectNode atBoundary = ((ObjectNode) snapshot.path("tasks").get(1)).deepCopy();
        atBoundary.put("failed_at", observed.toString());
        snapshot.putArray("tasks").add(atBoundary);
        Files.writeString(config.tasksFile(), snapshot.toString());
        var service = start(config, new FileLogSource(config.logsFile()));

        var automatic = completed(service, service.submitAutomatic());
        LogRequest actual = LogRequest.parse(automatic.path("request"), config);
        var manual = completed(service, service.submit(automatic.path("request")));

        assertEquals(observed.plusMillis(1), actual.to());
        assertEquals(Duration.ofHours(24), Duration.between(actual.from(), actual.to()));
        assertEquals(50, automatic.path("requested_count").asInt());
        assertEquals(1, automatic.path("selected_count").asInt());
        assertEquals("task-B", automatic.path("tasks").get(0).path("task_id").asText());
        assertEquals("CUDA_OOM", automatic.path("tasks").get(0).path("signature").asText());
        for (String field : List.of("tasks", "evidence", "clusters", "engineer_view", "observed_error_event_count", "retrieved_event_count"))
            assertEquals(manual.path(field), automatic.path(field), field);
        assertEquals("NOT_REQUESTED", automatic.path("narrative_status").asText());
    }

    @Test void automaticSnapshotRoundsSubmillisecondObservationToStrictMillisecondUpperBound() throws Exception {
        var config = fixtureConfig(directory, Map.of());
        ObjectNode snapshot = (ObjectNode) Json.MAPPER.readTree(Files.readAllBytes(config.tasksFile()));
        snapshot.put("observed_at", "2026-10-15T06:00:00.000500Z");
        ObjectNode atBoundary = ((ObjectNode) snapshot.path("tasks").get(1)).deepCopy().put("failed_at", "2026-10-15T06:00:00.000500Z");
        snapshot.putArray("tasks").add(atBoundary);
        Files.writeString(config.tasksFile(), snapshot.toString());
        var service = start(config, new FileLogSource(config.logsFile()));

        var report = completed(service, service.submitAutomatic());

        assertEquals("2026-10-15T06:00:00.001Z", report.path("request").path("selection").path("to").asText());
        assertEquals(1, report.path("selected_count").asInt());
    }

    @Test void automaticEnablesConfiguredNarrativeWithoutChangingRuleResultsOrArchiving() throws Exception {
        var config = fixtureConfig(directory, Map.of());
        AtomicInteger modelCalls = new AtomicInteger();
        var generator = new LogNarrativeGenerator((messages, tools) -> {
            modelCalls.incrementAndGet();
            return new LlmClient.Reply(Json.MAPPER.createObjectNode().put("role", "assistant").put("content",
                    "{\"summary\":\"人工测试摘要\",\"findings\":[],\"recommendations\":[],\"limitations\":[]}"), "stop");
        }, "synthetic-model", "https://example.invalid/v1");
        var service = start(config, new FileLogSource(config.logsFile()), TaskSource.file(config), generator);
        var automatic = completed(service, service.submitAutomatic());
        ObjectNode manualRequest = automatic.path("request").deepCopy();
        manualRequest.put("narrative", "none");
        var manual = completed(service, service.submit(manualRequest));

        assertEquals("llm", automatic.path("request").path("narrative").asText());
        assertEquals("AVAILABLE", automatic.path("narrative_status").asText());
        assertEquals(1, modelCalls.get());
        assertEquals("NOT_REQUESTED", automatic.path("archive_status").asText());
        assertTrue(automatic.path("archive_parts").isEmpty());
        for (String field : List.of("tasks", "evidence", "clusters", "engineer_view", "selected_count", "retrieved_event_count"))
            assertEquals(manual.path(field), automatic.path(field), field);
        assertSpoolEmpty(config);
    }

    @Test void automaticConfiguredNarrativeSkipsModelWhenNoEvidenceExists() throws Exception {
        var config = fixtureConfig(directory, Map.of());
        AtomicInteger calls = new AtomicInteger();
        var generator = new LogNarrativeGenerator((messages, tools) -> { calls.incrementAndGet(); throw new IOException("must-not-run"); },
                "synthetic-model", "https://example.invalid/v1");
        var service = start(config, (query, maxEvents, maxBytes, deadline) -> new FetchResult(List.of(), true, List.of(), 0),
                TaskSource.file(config), generator);

        var report = completed(service, service.submitAutomatic());

        assertEquals("llm", report.path("request").path("narrative").asText());
        assertEquals("SKIPPED_NO_EVIDENCE", report.path("narrative_status").asText());
        assertEquals(0, calls.get());
        assertEquals(6, report.path("engineer_view").path("summary").path("pending_task_count").asInt());
    }

    @Test void automaticSnapshotReadsAreBoundedAndMalformedTimesNeverLeakInput() throws Exception {
        var config = fixtureConfig(directory, Map.of());
        var service = start(config, new FileLogSource(config.logsFile()));
        for (String body : List.of("{\"private\":\"synthetic-private-secret\",", "{}", "{\"observed_at\":23}",
                "{\"observed_at\":\"synthetic-private-secret\"}", "{\"observed_at\":\"-1000000000-01-01T00:00:00Z\"}",
                "{\"observed_at\":\"2026-10-15T06:00:00Z\"} {}", " ".repeat((8 << 20) + 1))) {
            Files.writeString(config.tasksFile(), body);
            var failure = assertThrows(TaskSnapshot.Unprocessable.class, service::submitAutomatic);
            assertFalse(failure.getMessage().contains("synthetic-private-secret"));
        }
        try (var files = Files.list(config.dataDir().resolve("reports"))) { assertEquals(0, files.count()); }
    }

    @Test void automaticRetainsSourceFactsAndCollectsPastTaskSelectionUpperBound() throws Exception {
        var config = fixtureConfig(directory, Map.of());
        var created = Instant.parse("2026-10-15T05:59:50Z");
        var updated = Instant.parse("2026-10-15T06:10:00Z");
        var facts = new LogModels.SchedulerFacts("source-record", "external_video", "FAILED", created, updated);
        var selected = new LogModels.Task("task-source", "task-source", "failed", List.of(), "", "", null, "source-version",
                LogModels.AssociationScope.TASK_ONLY, facts);
        AtomicReference<LogModels.LogQuery> actual = new AtomicReference<>();
        var service = start(config, (query, maxEvents, maxBytes, deadline) -> {
            actual.set(query);
            if (!query.from().isAfter(updated) && query.to().isAfter(updated))
                return new FetchResult(List.of(new LogEvent(updated, "{\"task_id\":\"task-source\",\"event\":\"execution_error\",\"exception_message\":\"HTTP 404\"}",
                        Map.of("namespace", "prod"), "synthetic-event")), true, List.of(), 80);
            return new FetchResult(List.of(), true, List.of(), 0);
        }, request -> new TaskSnapshot.Selection(List.of(selected), updated.plusSeconds(1).toString(), List.of()), null);
        var request = fixtureRequest().put("log_window", "automatic");
        var report = completed(service, service.submit(request));
        var result = report.path("tasks").get(0);
        assertTrue(actual.get().to().isAfter(Instant.parse(request.path("selection").path("to").asText())));
        assertEquals("COLLECTED", result.path("collection_status").asText());
        assertEquals("TASK_ONLY", result.path("association_scope").asText());
        assertEquals("INCOMPLETE", result.path("signature").asText());
        assertEquals(TaskSnapshot.schedulerFactsJson(facts), result.path("scheduler_facts"));
        assertEquals("AUTOMATIC_TASK_LIFECYCLE", result.path("log_lookup").path("strategy").asText());
        assertEquals("MATCHED", result.path("log_lookup").path("status").asText());
        assertEquals(1, result.path("log_lookup").path("windows").get(0).path("returned_count").asInt());
        JsonNode saved = Json.MAPPER.readTree(Files.readAllBytes(config.dataDir().resolve("reports").resolve(report.path("batch_id").asText() + ".json")));
        assertEquals(TaskSnapshot.schedulerFactsJson(facts), saved.path("_selected_tasks").get(0).path("scheduler_facts"));
        assertNull(selected.finalAttempt()); assertNull(selected.failedAt());
    }

    @Test void failedLookupRecordsAttemptedWindowWithoutInventingZeroReturnedRows() throws Exception {
        var config = fixtureConfig(directory, Map.of());
        var selected = new LogModels.Task("task-source", "task-source", "failed", List.of(), "", "", null, "source-version", LogModels.AssociationScope.TASK_ONLY);
        var service = start(config, (query, maxEvents, maxBytes, deadline) -> { throw new IOException("synthetic-query-failure"); },
                request -> new TaskSnapshot.Selection(List.of(selected), Instant.now().toString(), List.of()), null);
        var report = completed(service, service.submit(fixtureRequest()));
        var lookup = report.path("tasks").get(0).path("log_lookup");
        assertEquals("QUERY_FAILED", lookup.path("status").asText());
        assertEquals(1, lookup.path("query_count").asInt());
        assertTrue(lookup.path("windows").get(0).path("returned_count").isNull());
        assertFalse(lookup.path("windows").get(0).path("complete").asBoolean());
    }

    @Test void sourceRecordIdFindsSchedulerPromptWhilePreservingLogicalTaskAndCandidateBoundary() throws Exception {
        var config = fixtureConfig(directory, Map.of());
        var time = Instant.parse("2026-10-15T05:30:00Z");
        var selected = new LogModels.Task("platform-ref", "platform-task", "failed", List.of(), "", "", null, "source-version",
                LogModels.AssociationScope.TASK_ONLY, new LogModels.SchedulerFacts("scheduler-task", "face_video", "FAILED", time.minusSeconds(5), time));
        AtomicReference<LogModels.LogQuery> actual = new AtomicReference<>();
        var service = start(config, (query, maxEvents, maxBytes, deadline) -> {
            actual.set(query);
            return new FetchResult(query.identifiers().contains("scheduler-task") ? List.of(new LogEvent(time,
                    "{\"prompt_id\":\"scheduler-task\",\"event\":\"execution_error\",\"exception_type\":\"RuntimeError\",\"exception_message\":\"Image detection failed\"}",
                    Map.of("namespace", "prod"), "synthetic-alias-event")) : List.of(), true, List.of(), 200);
        }, request -> new TaskSnapshot.Selection(List.of(selected), time.plusSeconds(10).toString(), List.of()), null);
        var report = completed(service, service.submit(fixtureRequest().put("log_window", "automatic")));
        var result = report.path("tasks").get(0);
        assertEquals(List.of("platform-task", "scheduler-task"), actual.get().identifiers());
        assertEquals("platform-task", result.path("task_id").asText());
        assertEquals("COLLECTED", result.path("collection_status").asText());
        assertEquals("INCOMPLETE", result.path("signature").asText());
        assertEquals("SOURCE_RECORD_TASK_ID", result.path("log_lookup").path("mapping_status").asText());
        assertEquals("scheduler-task", result.path("log_lookup").path("scheduler_task_id").asText());
        assertEquals("SOURCE_RECORD_TASK_ID_ATTEMPT_UNVERIFIED", report.path("evidence").get(0).path("association_basis").asText());
        assertTrue(report.path("evidence").get(0).path("candidate_only").asBoolean());
        assertEquals(1, report.path("engineer_view").path("summary").path("error_task_count").asInt());
    }

    @Test void fixedListAliasConflictsAreBlockedBeforePerTaskCollection() throws Exception {
        var config = fixtureConfig(directory, Map.of());
        var time = Instant.parse("2026-10-15T05:30:00Z");
        var first = new LogModels.Task("ref-a", "platform-task", "failed", List.of(), "", "", null, "source-version",
                LogModels.AssociationScope.TASK_ONLY, new LogModels.SchedulerFacts("other-task", "type", "FAILED", time, time));
        var other = new LogModels.Task("ref-b", "other-task", "failed", List.of(), "", "", null, "source-version", LogModels.AssociationScope.TASK_ONLY);
        AtomicInteger calls = new AtomicInteger();
        var service = start(config, (query, maxEvents, maxBytes, deadline) -> { calls.incrementAndGet(); return new FetchResult(List.of(), true, List.of(), 0); },
                request -> new TaskSnapshot.Selection(List.of(first, other), time.plusSeconds(10).toString(), List.of()), null);
        var report = completed(service, service.submit(fixtureRequest()));
        assertEquals(0, calls.get()); assertEquals(2, report.path("processed_count").asInt());
        for (var task : report.path("tasks")) {
            assertEquals("SKIPPED", task.path("collection_status").asText());
            assertTrue(task.path("collection_limitations").toString().contains("TASK_ID_ALIAS_CONFLICT"));
            assertEquals(0, task.path("log_lookup").path("query_count").asInt());
            assertTrue(task.path("evidence_ids").isEmpty());
        }
    }

    @Test void automaticEmptyLifecycleFallsBackWithoutManualWindowSelection() throws Exception {
        var config = fixtureConfig(directory, Map.of());
        var time = Instant.parse("2026-10-15T05:30:00Z");
        var selected = new LogModels.Task("platform-ref", "platform-task", "failed", List.of(), "", "", null, "source-version",
                LogModels.AssociationScope.TASK_ONLY, new LogModels.SchedulerFacts("scheduler-task", "type", "FAILED", time.minusSeconds(5), time));
        List<LogModels.LogQuery> queries = new ArrayList<>();
        var service = start(config, (query, maxEvents, maxBytes, deadline) -> {
            queries.add(query);
            var event = new LogEvent(time.minusSeconds(3600), "{\"prompt_id\":\"scheduler-task\",\"event\":\"execution_error\",\"exception_message\":\"HTTP 404\"}",
                    Map.of("namespace", "prod"), "synthetic-early-event");
            return new FetchResult(query.from().isBefore(event.time()) ? List.of(event) : List.of(), true, List.of(), 80);
        }, request -> new TaskSnapshot.Selection(List.of(selected), time.plusSeconds(10).toString(), List.of()), null);
        var request = fixtureRequest().put("log_window", "automatic");
        var report = completed(service, service.submit(request));
        assertEquals(2, queries.size());
        assertEquals(Duration.ofHours(72), Duration.between(queries.get(1).from(), queries.get(1).to()));
        assertEquals(Instant.parse(request.path("selection").path("to").asText()), queries.get(1).to());
        assertEquals(queries.get(0).identifiers(), queries.get(1).identifiers());
        var task = report.path("tasks").get(0);
        assertEquals("COLLECTED", task.path("collection_status").asText());
        assertEquals(2, task.path("log_lookup").path("query_count").asInt());
        assertEquals("AUTOMATIC_ID_FALLBACK", task.path("log_lookup").path("windows").get(1).path("basis").asText());
        assertEquals("TASK_ONLY", task.path("association_scope").asText());
    }

    @Test void incompleteSourceForOneTaskDoesNotInvalidateOtherTaskSignature() throws Exception {
        var config = fixtureConfig(directory, Map.of());
        var file = new FileLogSource(config.logsFile());
        LogSource source = (query, maxEvents, maxBytes, deadline) -> {
            FetchResult result = file.fetch(query, maxEvents, maxBytes, deadline);
            return query.identifiers().contains("task-A")
                    ? new FetchResult(result.events(), false, List.of("SYNTHETIC_PARTIAL_A"), result.responseBytes()) : result;
        };
        var service = start(config, source);

        var report = completed(service, service.submit(explicit("none", "task-A", "task-B")));

        assertEquals("PARTIAL", report.path("state").asText());
        assertEquals("INCOMPLETE", task(report, "task-A").path("signature").asText());
        assertEquals("CUDA_OOM", task(report, "task-B").path("signature").asText());
        assertEquals("INCOMPLETE", task(report, "task-A").path("collection_status").asText());
        assertTrue(task(report, "task-A").path("collection_limitations").toString().contains("SYNTHETIC_PARTIAL_A"));
        assertEquals("COLLECTED", task(report, "task-B").path("collection_status").asText());
        assertFalse(task(report, "task-B").path("quality_flags").toString().contains("INCOMPLETE_INPUT"));
        assertEquals(2, clusterTotal(report));
    }

    @Test void contextMultisetDedupPreservesRepeatedEventsAndStackContainingShortIdentifier() throws Exception {
        var config = fixtureConfig(directory, Map.of());
        ObjectNode snapshot = (ObjectNode) Json.MAPPER.readTree(Files.readAllBytes(config.tasksFile()));
        ObjectNode selected = ((ObjectNode) snapshot.path("tasks").get(1)).deepCopy();
        selected.put("task_id", "at");
        ((ObjectNode) selected.path("attempts").get(0)).put("prompt_id", "p");
        snapshot.putArray("tasks").add(selected);
        Files.writeString(config.tasksFile(), snapshot.toString());
        Map<String, String> labels = new HashMap<>(Map.of("namespace", "prod", "pod", "pod-1", "pod_uid", "uid-1",
                "container", "worker", "restart", "0", "task_id", "at", "prompt_id", "p", "level", "ERROR"));
        LogEvent head = new LogEvent(Instant.parse("2026-10-15T05:23:40Z"),
                "{\"event\":\"execution_error\",\"error_message\":\"RuntimeError: synthetic failure\"}", labels, "");
        Map<String, String> contextLabels = new HashMap<>(labels);
        contextLabels.remove("task_id"); contextLabels.remove("prompt_id");
        LogEvent stack = new LogEvent(head.time().plusSeconds(1), "  File \"/synthetic/at/worker.py\", line 42, in execute", contextLabels, "");
        var service = start(config, (query, maxEvents, maxBytes, deadline) -> new FetchResult(
                query.identifiers().isEmpty() ? List.of(head, head, stack) : List.of(head, head), true, List.of(), 100));

        var report = completed(service, service.submit(explicit("none", "at")));

        assertEquals(2, report.path("observed_error_event_count").asInt());
        assertEquals(2, report.path("evidence").size());
        assertEquals(3, task(report, "at").path("collection_event_count").asInt());
        assertTrue(report.path("evidence").toString().contains("/synthetic/at/worker.py"));
        assertTrue(report.path("quality_flags").toString().contains("DUPLICATE_AMBIGUITY"));
    }

    @Test void sourceFailureStopsFurtherCollectionAndReturnsPartialWithoutPrivateError() throws Exception {
        var config = fixtureConfig(directory, Map.of());
        var file = new FileLogSource(config.logsFile());
        var service = start(config, (query, maxEvents, maxBytes, deadline) -> {
            if (query.identifiers().contains("task-E")) throw new IOException("private-source-password");
            return file.fetch(query, maxEvents, maxBytes, deadline);
        });

        var report = completed(service, service.submit(fixtureRequest()));

        assertEquals("PARTIAL", report.path("state").asText());
        assertEquals(6, clusterTotal(report));
        assertTrue(report.path("limitations").toString().contains("LOG_QUERY_FAILED"));
        assertFalse(report.toString().contains("private-source-password"));
        assertTrue(report.path("response_bytes").asLong() < config.maxBytes());
        assertTrue(report.path("bytes_accounting_incomplete").asBoolean());
        assertTrue(report.path("collection_stopped").asBoolean());
        assertTrue(report.path("limitations").toString().contains("SOURCE_UNAVAILABLE_FOR_REMAINDER"));
    }

    @Test void grafanaUnauthorizedMarksFirstTaskFailedAndLaterTasksSkippedWithoutInventingLogs() throws Exception {
        var config = fixtureConfig(directory, Map.of());
        AtomicInteger requests = new AtomicInteger();
        HttpServer grafana = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        grafana.createContext("/api/ds/query", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(401, -1); exchange.close();
        });
        grafana.start();
        try {
            URI origin = URI.create("http://127.0.0.1:" + grafana.getAddress().getPort());
            var source = new GrafanaVictoriaLogsSource(origin, "", "synthetic-datasource", 100, Duration.ofSeconds(2));
            var service = start(config, source);

            var report = completed(service, service.submit(explicit("none", "task-B", "task-C", "task-E", "task-F")));

            assertEquals(1, requests.get());
            assertEquals("PARTIAL", report.path("state").asText());
            assertEquals(0, report.path("retrieved_event_count").asInt());
            assertEquals(0, report.path("response_bytes").asLong());
            JsonNode failed = task(report, "task-B");
            assertEquals("FAILED", failed.path("collection_status").asText());
            assertTrue(failed.path("collection_limitations").toString().contains("GRAFANA_HTTP_401"));
            for (String id : List.of("task-C", "task-E", "task-F")) {
                JsonNode skipped = task(report, id);
                assertEquals("SKIPPED", skipped.path("collection_status").asText());
                assertTrue(skipped.path("collection_limitations").toString().contains("SOURCE_UNAVAILABLE_FOR_REMAINDER"));
                assertEquals(0, skipped.path("log_lookup").path("query_count").asInt());
                assertTrue(skipped.path("log_lookup").path("windows").isEmpty());
                assertFalse(skipped.path("log_lookup").path("searched_all_pods").asBoolean());
            }
            for (JsonNode result : report.path("tasks")) {
                assertEquals(0, result.path("collection_event_count").asInt());
                assertEquals("INCOMPLETE", result.path("signature").asText());
                assertTrue(result.path("evidence_ids").isEmpty());
            }
        } finally { grafana.stop(0); }
    }

    @Test void successfulEmptyCollectionIsNoMatchesRatherThanCollectionFailure() throws Exception {
        var config = fixtureConfig(directory, Map.of());
        var service = start(config, (query, maxEvents, maxBytes, deadline) -> new FetchResult(List.of(), true, List.of(), 37));

        var report = completed(service, service.submit(explicit("none", "task-B")));

        JsonNode task = task(report, "task-B");
        assertEquals("NO_MATCHES", task.path("collection_status").asText());
        assertEquals(0, task.path("collection_event_count").asInt());
        assertTrue(task.path("collection_limitations").isEmpty());
        assertFalse(report.path("collection_incomplete").asBoolean());
        assertFalse(report.path("attribution_incomplete").asBoolean());
        assertEquals("COMPLETED", report.path("state").asText());
        assertEquals("UNKNOWN", task.path("signature").asText());
    }

    @Test void successfullyCollectedButUnparseableEvidenceKeepsAttributionIncomplete() throws Exception {
        var config = fixtureConfig(directory, Map.of());
        LogEvent invalid = new LogEvent(Instant.parse("2026-10-15T05:23:40Z"), "payload: {'task_id':}",
                Map.of("namespace", "prod", "task_id", "task-B"), "synthetic-invalid-payload");
        var service = start(config, (query, maxEvents, maxBytes, deadline) -> new FetchResult(List.of(invalid), true, List.of(), 100));

        var report = completed(service, service.submit(explicit("none", "task-B")));

        JsonNode task = task(report, "task-B");
        assertEquals("COLLECTED", task.path("collection_status").asText());
        assertEquals(1, task.path("collection_event_count").asInt());
        assertTrue(task.path("quality_flags").toString().contains("PARSE_ERROR"));
        assertFalse(report.path("collection_incomplete").asBoolean());
        assertTrue(report.path("attribution_incomplete").asBoolean());
        assertEquals("INCOMPLETE", task.path("signature").asText());
        assertEquals("PARTIAL", report.path("state").asText());
    }

    @Test void exhaustedCollectionBudgetIsIncompleteRatherThanSuccessfulNoMatches() throws Exception {
        var config = fixtureConfig(directory, Map.of("HOLMES_LOG_MAX_EVENTS", "1"));
        AtomicInteger calls = new AtomicInteger();
        LogEvent event = new LogEvent(Instant.parse("2026-10-15T05:23:40Z"), "CUDA out of memory",
                Map.of("namespace", "prod", "task_id", "task-B", "level", "ERROR"), "synthetic-event");
        var service = start(config, (query, maxEvents, maxBytes, deadline) -> {
            calls.incrementAndGet(); return new FetchResult(List.of(event), true, List.of(), 100);
        });

        var report = completed(service, service.submit(explicit("none", "task-B", "task-C")));

        assertEquals(1, calls.get());
        assertEquals("COLLECTED", task(report, "task-B").path("collection_status").asText());
        JsonNode second = task(report, "task-C");
        assertEquals("INCOMPLETE", second.path("collection_status").asText());
        assertEquals(0, second.path("collection_event_count").asInt());
        assertTrue(second.path("collection_limitations").toString().contains("COLLECTION_BUDGET_EXHAUSTED"));
        assertTrue(report.path("collection_incomplete").asBoolean());
        assertEquals("PARTIAL", report.path("state").asText());
    }

    @Test void stoppedBeforeAnalysisMarksUnprocessedTasksAsSkipped() throws Exception {
        var config = fixtureConfig(directory, Map.of());
        var service = start(config, (query, maxEvents, maxBytes, deadline) -> {
            throw new IllegalStateException("synthetic-internal-failure");
        });

        var report = completed(service, service.submit(explicit("none", "task-B", "task-C")));

        assertEquals("FAILED", report.path("state").asText());
        for (JsonNode result : report.path("tasks")) {
            assertEquals("NOT_PROCESSED", result.path("analysis_status").asText());
            assertEquals("SKIPPED", result.path("collection_status").asText());
            assertEquals(0, result.path("collection_event_count").asInt());
            assertTrue(result.path("collection_limitations").toString().contains("STOPPED_BEFORE_TASK_ANALYSIS"));
        }
    }

    @Test void requiredArchiveHasTaskScopedUniquePartsAndNoneMakesNoUpload() throws Exception {
        startFluxDrop(null, null);
        var config = fixtureConfig(directory, Map.of("HOLMES_FLUXDROP_URL", fluxOrigin().toString()));
        var service = start(config, new FileLogSource(config.logsFile()));
        completed(service, service.submit(explicit("none", "task-A", "task-B")));
        assertEquals(0, uploads.get());

        var report = completed(service, service.submit(explicit("required", "task-A", "task-B")));

        assertEquals("AVAILABLE", report.path("archive_status").asText());
        assertEquals(2, uploads.get());
        HashSet<String> ids = new HashSet<>();
        for (JsonNode part : report.path("archive_parts")) {
            assertTrue(ids.add(part.path("part_id").asText()));
            assertEquals("AVAILABLE", part.path("state").asText());
            assertTrue(part.path("download_url").asText().startsWith(fluxOrigin() + "/f/"));
            JsonNode owner = find(report.path("tasks"), "task_ref", part.path("task_ref").asText());
            assertNotNull(owner);
            assertTrue(owner.path("raw_refs").toString().contains(part.path("part_id").asText()));
        }
        assertEquals(2, ids.size());
        assertSpoolEmpty(config);
    }

    @Test void uncertainInflightUploadIsNotRepeatedAfterRestart() throws Exception {
        CountDownLatch received = new CountDownLatch(1), release = new CountDownLatch(1);
        startFluxDrop(received, release);
        var config = fixtureConfig(directory, Map.of("HOLMES_FLUXDROP_URL", fluxOrigin().toString()));
        var original = start(config, new FileLogSource(config.logsFile()));
        var accepted = original.submit(explicit("required", "task-B"));
        assertTrue(received.await(4, TimeUnit.SECONDS));
        String id = accepted.path("batch_id").asText();
        JsonNode checkpoint = Json.MAPPER.readTree(Files.readAllBytes(config.dataDir().resolve("reports").resolve(id + ".json")));
        assertTrue(checkpoint.has("_archive_inflight_task"));
        assertEquals(1, checkpoint.path("processed_count").asInt());
        original.close(); release.countDown();

        var resumed = start(config, new FileLogSource(config.logsFile()));
        var report = assertTimeoutPreemptively(Duration.ofSeconds(4), () -> resumed.await(id));

        assertEquals("PARTIAL", report.path("state").asText());
        assertEquals(1, uploads.get());
        assertEquals(1, report.path("processed_count").asInt());
        assertEquals(1, report.path("tasks").size());
        assertTrue(report.path("limitations").toString().contains("RAW_ARCHIVE_OUTCOME_UNVERIFIED_AFTER_RESTART"));
        assertFalse(report.has("_archive_inflight_task"));
        assertSpoolEmpty(config);
    }

    @Test void restartUsesFixedSelectionAndSkipsAlreadyCheckpointedTask() throws Exception {
        var config = fixtureConfig(directory, Map.of());
        var file = new FileLogSource(config.logsFile());
        CountDownLatch secondTask = new CountDownLatch(1);
        var original = start(config, (query, maxEvents, maxBytes, deadline) -> {
            if (query.identifiers().contains("task-E")) {
                secondTask.countDown();
                new CountDownLatch(1).await();
            }
            return file.fetch(query, maxEvents, maxBytes, deadline);
        });
        var accepted = original.submit(fixtureRequest());
        assertTrue(secondTask.await(4, TimeUnit.SECONDS));
        String id = accepted.path("batch_id").asText();
        assertEquals(1, original.get(id).path("processed_count").asInt());
        original.close();
        // 模拟升级前已完成的任务：恢复不能从批次总量猜出它的采集状态或事件数。
        Path checkpointFile = config.dataDir().resolve("reports").resolve(id + ".json");
        ObjectNode checkpoint = (ObjectNode) Json.MAPPER.readTree(Files.readAllBytes(checkpointFile));
        ((ObjectNode) checkpoint.path("tasks").get(0)).remove(List.of("collection_status", "collection_event_count", "collection_limitations"));
        Files.writeString(checkpointFile, checkpoint.toString());
        ObjectNode snapshot = (ObjectNode) Json.MAPPER.readTree(Files.readAllBytes(config.tasksFile()));
        snapshot.putArray("tasks");
        Files.writeString(config.tasksFile(), snapshot.toString());
        AtomicInteger recollectedF = new AtomicInteger();
        var resumed = start(config, (query, maxEvents, maxBytes, deadline) -> {
            if (query.identifiers().contains("task-F")) recollectedF.incrementAndGet();
            return file.fetch(query, maxEvents, maxBytes, deadline);
        });

        var report = assertTimeoutPreemptively(Duration.ofSeconds(4), () -> resumed.await(id));

        assertEquals(6, report.path("selected_count").asInt());
        assertEquals(6, report.path("processed_count").asInt());
        assertEquals(6, new HashSet<>(taskIds(report)).size());
        assertEquals(6, clusterTotal(report));
        assertEquals(0, recollectedF.get());
        assertFalse(task(report, "task-F").has("collection_status"));
        assertFalse(task(report, "task-F").has("collection_event_count"));
        assertFalse(task(report, "task-F").has("collection_limitations"));
    }

    @Test void storageFailureReturnsBoundedTerminalInsteadOfLeavingAwaitRunning() throws Exception {
        var config = fixtureConfig(directory, Map.of("HOLMES_LOG_REPORT_BYTES", "4096", "HOLMES_LOG_STORE_BYTES", "65536"));
        var service = start(config, (query, maxEvents, maxBytes, deadline) -> {
            Files.write(config.dataDir().resolve("reports").resolve("foreign-owner.log"), new byte[65536]);
            throw new IOException("private-storage-secret");
        });

        var report = completed(service, service.submit(explicit("none", "task-B")));

        assertEquals("PARTIAL", report.path("state").asText());
        assertEquals("STOPPED", report.path("stage").asText());
        assertEquals("UNAVAILABLE", report.path("checkpoint_status").asText());
        assertTrue(report.path("results_unavailable").asBoolean());
        assertTrue(Json.MAPPER.writeValueAsBytes(report).length <= config.reportBytes());
        assertFalse(report.toString().contains("private-storage-secret"));
    }

    @Test void dataDirectoryRejectsSecondInstanceAndCanReopenAfterClose() throws Exception {
        var config = fixtureConfig(directory, Map.of());
        var original = start(config, new FileLogSource(config.logsFile()));
        assertThrows(IOException.class, () -> new LogInvestigationService(config));
        original.close();

        var reopened = start(config, new FileLogSource(config.logsFile()));

        assertEquals(1, completed(reopened, reopened.submit(explicit("none", "task-B"))).path("selected_count").asInt());
    }

    @Test void unknownSensitiveRequestFieldsAreRejectedBeforePersistence() throws Exception {
        var config = fixtureConfig(directory, Map.of());
        var service = start(config, new FileLogSource(config.logsFile()));
        ObjectNode request = fixtureRequest().put("Authorization", "private-request-secret");

        assertThrows(IllegalArgumentException.class, () -> service.submit(request));

        try (var files = Files.list(config.dataDir().resolve("reports"))) { assertEquals(0, files.count()); }
    }

    private LogInvestigationService start(LogConfig config, LogSource source) throws IOException {
        return start(config, source, TaskSource.file(config), null);
    }
    private LogInvestigationService start(LogConfig config, LogSource source, TaskSource tasks, LogNarrativeGenerator generator) throws IOException {
        var service = new LogInvestigationService(config, source, new FluxDropArchiveSink(config.fluxDropUrl(), "", config.dataDir().resolve("spool"),
                config.spoolBytes(), config.chunkBytes(), config.requestTimeout()), tasks, generator);
        services.add(service); return service;
    }
    private void startFluxDrop(CountDownLatch received, CountDownLatch release) throws IOException {
        fluxDrop = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        fluxDrop.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        fluxDrop.createContext("/upload/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int upload = uploads.incrementAndGet();
            if (received != null) received.countDown();
            if (release != null) try { release.await(4, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            byte[] response = ("{\"url\":\"/f/synthetic-" + upload + "/log.gz\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
            } catch (IOException ignored) { /* 中断上传的测试允许客户端主动关闭连接。 */ }
            finally { exchange.close(); }
        });
        fluxDrop.start();
    }
    private URI fluxOrigin() { return URI.create("http://127.0.0.1:" + fluxDrop.getAddress().getPort()); }
    static LogConfig fixtureConfig(Path root, Map<String, String> overrides) throws IOException {
        Files.createDirectories(root);
        Path snapshot = root.resolve("tasks.json");
        Files.copy(Path.of("examples/logs/tasks.json"), snapshot);
        Map<String, String> env = new HashMap<>(Map.of("HOLMES_LOG_TASKS_FILE", snapshot.toString(),
                "HOLMES_LOG_FILE", Path.of("examples/logs/events.jsonl").toAbsolutePath().toString(),
                "HOLMES_LOG_DATA_DIR", root.resolve("data").toString(), "HOLMES_FLUXDROP_URL", "http://127.0.0.1:1",
                "HOLMES_LOG_JOB_SECONDS", "5", "HOLMES_LOG_REQUEST_SECONDS", "2"));
        env.putAll(overrides); return LogConfig.from(env);
    }
    static ObjectNode fixtureRequest() throws IOException { return (ObjectNode) Json.MAPPER.readTree(Files.readAllBytes(Path.of("examples/logs/request.json"))); }
    static ObjectNode explicit(String archive, String... ids) throws IOException {
        ObjectNode request = fixtureRequest().put("raw_archive", archive);
        ObjectNode selection = (ObjectNode) request.get("selection");
        selection.put("mode", "explicit_task_ids").remove("requested_count");
        ArrayNode tasks = selection.putArray("task_ids"); for (String id : ids) tasks.add(id);
        return request;
    }
    static ObjectNode completed(LogInvestigationService service, JsonNode accepted) {
        return assertTimeoutPreemptively(Duration.ofSeconds(5), () -> service.await(accepted.path("batch_id").asText()));
    }
    static JsonNode find(JsonNode array, String key, String value) { for (JsonNode item : array) if (item.path(key).asText().equals(value)) return item; return null; }
    static JsonNode task(JsonNode report, String id) { JsonNode result = find(report.path("tasks"), "task_id", id); assertNotNull(result, id); return result; }
    static int clusterTotal(JsonNode report) { int count = 0; for (JsonNode cluster : report.path("clusters")) count += cluster.path("task_count").asInt(); return count; }
    private static int clusterCount(JsonNode report, String signature) { JsonNode found = find(report.path("clusters"), "signature", signature); return found == null ? 0 : found.path("task_count").asInt(); }
    private static List<String> taskIds(JsonNode report) { List<String> ids = new ArrayList<>(); report.path("tasks").forEach(task -> ids.add(task.path("task_id").asText())); return ids; }
    private static void assertSpoolEmpty(LogConfig config) throws IOException {
        Path spool = config.dataDir().resolve("spool"); if (Files.exists(spool)) try (var files = Files.list(spool)) { assertEquals(0, files.count()); }
    }
}
