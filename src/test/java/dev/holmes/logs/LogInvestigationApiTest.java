package dev.holmes.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import dev.holmes.HttpApi;
import dev.holmes.Json;
import dev.holmes.LlmClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LogInvestigationApiTest {
    private static final String BASE = "/api/log-investigations";
    @TempDir Path directory;
    private LogInvestigationService service;
    private HttpServer server;
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach void close() { if (server != null) server.stop(0); if (service != null) service.close(); }

    @Test void createsAcceptedBatchAndScopesTaskAndEvidenceLookupsToThatBatch() throws Exception {
        start(null);
        var firstResponse = request("POST", BASE, LogInvestigationServiceTest.explicit("none", "task-A").toString());
        assertEquals(202, firstResponse.statusCode());
        assertEquals("no-store", firstResponse.headers().firstValue("Cache-Control").orElseThrow());
        JsonNode accepted = Json.MAPPER.readTree(firstResponse.body());
        var first = LogInvestigationServiceTest.completed(service, accepted);
        var secondResponse = request("POST", BASE, LogInvestigationServiceTest.explicit("none", "task-B").toString());
        var second = LogInvestigationServiceTest.completed(service, Json.MAPPER.readTree(secondResponse.body()));
        String firstId = first.path("batch_id").asText(), secondId = second.path("batch_id").asText();
        String taskRef = first.path("tasks").get(0).path("task_ref").asText();
        String evidenceId = first.path("evidence").get(0).path("evidence_id").asText();

        assertEquals(200, request("GET", BASE + "/" + firstId, "").statusCode());
        assertEquals(200, request("GET", BASE + "/" + firstId + "/tasks/" + taskRef, "").statusCode());
        assertEquals(200, request("GET", BASE + "/" + firstId + "/evidence/" + evidenceId, "").statusCode());
        assertEquals(404, request("GET", BASE + "/" + secondId + "/tasks/" + taskRef, "").statusCode());
        assertEquals(404, request("GET", BASE + "/" + secondId + "/evidence/" + evidenceId, "").statusCode());
        assertFalse(request("GET", BASE + "/" + firstId, "").body().contains("_selected_tasks"));
    }

    @Test void automaticAcceptsAsyncAndReturnsExistingBatchEvidenceContract() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var file = new FileLogSource(Path.of("examples/logs/events.jsonl"));
        start((query, maxEvents, maxBytes, deadline) -> {
            entered.countDown(); release.await(); return file.fetch(query, maxEvents, maxBytes, deadline);
        });
        try {
            var response = request("POST", BASE + "/automatic", "{}");
            assertEquals(202, response.statusCode());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            JsonNode accepted = Json.MAPPER.readTree(response.body());
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(50, accepted.path("requested_count").asInt());
            assertEquals("latest_failed", accepted.path("request").path("selection").path("mode").asText());
            assertEquals("2026-10-15T06:00:00.001Z", accepted.path("request").path("selection").path("to").asText());
            assertEquals("none", accepted.path("request").path("raw_archive").asText());
            assertEquals("none", accepted.path("request").path("narrative").asText());
            String id = accepted.path("batch_id").asText();
            JsonNode running = Json.MAPPER.readTree(request("GET", BASE + "/" + id, "").body());
            assertTrue(List.of("QUEUED", "RUNNING").contains(running.path("state").asText()));
            release.countDown();
            JsonNode report = service.await(id);
            assertEquals(6, report.path("selected_count").asInt());
            assertFalse(report.path("engineer_view").path("groups").isEmpty());
            String evidence = report.path("evidence").get(0).path("evidence_id").asText();
            assertEquals(200, request("GET", BASE + "/" + id + "/evidence/" + evidence, "").statusCode());
        } finally { release.countDown(); }
    }

    @Test void automaticRequiresSmallEmptyObjectAndDoesNotEchoRejectedFields() throws Exception {
        start(null);
        for (String body : List.of("", "null", "[]", "not-json", "{} {}", "{} true", "{\"narrative\":\"llm\"}",
                "{\"source\":\"private-request-secret\"}", "{\"selection\":{\"requested_count\":100}}")) {
            var rejected = request("POST", BASE + "/automatic", body);
            assertEquals(400, rejected.statusCode(), body);
            assertFalse(rejected.body().contains("private-request-secret"));
        }
        assertEquals(413, request("POST", BASE + "/automatic", "{}" + " ".repeat(1023)).statusCode());
        for (String method : List.of("GET", "PUT", "DELETE")) assertEquals(405, request(method, BASE + "/automatic", "{}").statusCode());
        try (var files = Files.list(directory.resolve("data/reports"))) { assertEquals(0, files.count()); }
    }

    @Test void automaticInvalidSnapshotReturnsSafe422WithoutSavingBatch() throws Exception {
        start(null);
        Files.writeString(directory.resolve("tasks.json"), "{\"observed_at\":\"private-snapshot-secret\"}");

        var rejected = request("POST", BASE + "/automatic", "{}");

        assertEquals(422, rejected.statusCode());
        assertFalse(rejected.body().contains("private-snapshot-secret"));
        try (var files = Files.list(directory.resolve("data/reports"))) { assertEquals(0, files.count()); }
    }

    @Test void rejectsMalformedOversizedAndInvalidParametersWithoutSavingSensitiveRequestFields() throws Exception {
        start(null);
        for (String body : List.of("not-json", "null", "[]", "{}")) assertEquals(400, request("POST", BASE, body).statusCode());
        ObjectNode sensitive = LogInvestigationServiceTest.fixtureRequest().put("Authorization", "private-request-secret");
        var rejected = request("POST", BASE, sensitive.toString());
        assertEquals(400, rejected.statusCode());
        assertFalse(rejected.body().contains("private-request-secret"));
        ObjectNode badCount = LogInvestigationServiceTest.fixtureRequest();
        ((ObjectNode) badCount.get("selection")).put("requested_count", 0);
        assertEquals(400, request("POST", BASE, badCount.toString()).statusCode());
        ObjectNode badNamespace = LogInvestigationServiceTest.fixtureRequest().put("namespace", "unapproved");
        assertEquals(400, request("POST", BASE, badNamespace.toString()).statusCode());
        assertEquals(422, request("POST", BASE, LogInvestigationServiceTest.explicit("none", "task-G").toString()).statusCode());
        assertEquals(422, request("POST", BASE, LogInvestigationServiceTest.explicit("none", "missing-task").toString()).statusCode());
        assertEquals(413, request("POST", BASE, "x".repeat(65537)).statusCode());
        try (var files = Files.list(directory.resolve("data/reports"))) { assertEquals(0, files.count()); }
    }

    @Test void enforcesHttpPathsAndMethodsAndStandaloneLogModeDoesNotInvokeLlm() throws Exception {
        start(null);

        assertEquals(405, request("GET", BASE, "").statusCode());
        assertEquals(405, request("POST", BASE + "/00000000-0000-0000-0000-000000000000", "{}").statusCode());
        assertEquals(404, request("GET", BASE + "/00000000-0000-0000-0000-000000000000", "").statusCode());
        assertEquals(400, request("GET", BASE + "/bad-batch-id", "").statusCode());
        assertEquals(404, request("GET", BASE + "/one/two", "").statusCode());
        assertEquals(404, request("GET", BASE + "-other", "").statusCode());
        assertEquals(503, request("POST", "/api/investigate", "{\"question\":\"检查日志\"}").statusCode());
        assertEquals(200, request("GET", "/health", "").statusCode());
    }

    @Test void boundsActiveAndQueuedBatchesAndReturns429AtCapacity() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1);
        start((query, maxEvents, maxBytes, deadline) -> {
            blocked.await();
            return new LogModels.FetchResult(List.of(), true, List.of(), 0);
        });
        try {
            for (int i = 0; i < 12; i++) assertEquals(202, request("POST", BASE, LogInvestigationServiceTest.explicit("none", "task-B").toString()).statusCode());
            assertEquals(429, request("POST", BASE, LogInvestigationServiceTest.explicit("none", "task-B").toString()).statusCode());
        } finally { blocked.countDown(); }
    }

    @Test void explicitNarrativeWithoutConfiguredGeneratorReturnsSafe503AndMissingBatchStays404() throws Exception {
        start(null);
        assertEquals(503, request("POST", BASE, LogInvestigationServiceTest.explicit("none", "task-B").put("narrative", "llm").toString()).statusCode());
        var base = service.await(Json.MAPPER.readTree(request("POST", BASE,
                LogInvestigationServiceTest.explicit("none", "task-B").toString()).body()).path("batch_id").asText());
        String path = BASE + "/" + base.path("batch_id").asText() + "/narrative";
        var response = request("POST", path, "{\"narrative\":\"llm\"}");
        assertEquals(503, response.statusCode());
        assertEquals("NARRATIVE_DISABLED", Json.MAPPER.readTree(response.body()).path("error_code").asText());
        assertEquals(404, request("POST", BASE + "/00000000-0000-0000-0000-000000000000/narrative", "{\"narrative\":\"llm\"}").statusCode());
        assertEquals(405, request("GET", path, "").statusCode());
        assertEquals(400, request("POST", path, "{\"narrative\":\"llm\",\"token\":\"private-request-secret\"}").statusCode());
    }

    @Test void engineerViewIsDerivedForSavedReportsWithoutRecollectingOrChangingRuleResults() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        FileLogSource file = new FileLogSource(Path.of("examples/logs/events.jsonl"));
        start((query, maxEvents, maxBytes, deadline) -> {
            calls.incrementAndGet(); return file.fetch(query, maxEvents, maxBytes, deadline);
        });
        var accepted = Json.MAPPER.readTree(request("POST", BASE,
                LogInvestigationServiceTest.explicit("none", "task-A").toString()).body());
        var base = service.await(accepted.path("batch_id").asText());
        String id = base.path("batch_id").asText();
        Path saved = directory.resolve("data/reports").resolve(id + ".json");
        byte[] before = Files.readAllBytes(saved);
        assertFalse(Json.MAPPER.readTree(before).has("engineer_view"));
        int fetched = calls.get();
        JsonNode view = Json.MAPPER.readTree(request("GET", BASE + "/" + id, "").body());
        assertEquals("engineer-v1", view.path("engineer_view").path("version").asText());
        assertEquals(1, view.path("engineer_view").path("summary").path("error_task_count").asInt());
        assertFalse(view.path("engineer_view").path("groups").isEmpty());
        for (JsonNode group : view.path("engineer_view").path("groups")) {
            assertEquals("UNASSIGNED", group.path("assignment_status").asText());
            for (JsonNode evidenceId : group.path("representative_evidence_ids"))
                assertTrue(view.path("tasks").get(0).path("evidence_ids").toString().contains(evidenceId.asText()));
        }
        for (String field : List.of("tasks", "evidence", "clusters", "selected_count", "retrieved_event_count", "narrative_status"))
            assertEquals(base.path(field), view.path(field));
        assertEquals(fetched, calls.get());
        assertArrayEquals(before, Files.readAllBytes(saved));
    }

    @Test void engineerViewListsUnmatchedTasksForEvidenceCollectionWithoutInventingErrors() throws Exception {
        start((query, maxEvents, maxBytes, deadline) -> new LogModels.FetchResult(List.of(), true, List.of(), 0));
        var accepted = Json.MAPPER.readTree(request("POST", BASE,
                LogInvestigationServiceTest.explicit("none", "task-B").toString()).body());
        var base = service.await(accepted.path("batch_id").asText());
        var view = base.path("engineer_view");
        assertTrue(view.path("groups").isEmpty());
        assertEquals(0, view.path("summary").path("error_task_count").asInt());
        assertEquals(1, view.path("summary").path("pending_task_count").asInt());
        assertEquals("NO_LOG_MATCH", view.path("pending_tasks").get(0).path("reason_code").asText());
        assertEquals("NOT_REQUESTED", base.path("narrative_status").asText());
    }

    @Test void onDemandNarrativeEndpointAcceptsAsyncAndReusesExistingModelWork() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        LogNarrativeGenerator generator = new LogNarrativeGenerator((messages, tools) -> {
            calls.incrementAndGet(); entered.countDown(); release.await();
            return new LlmClient.Reply(Json.MAPPER.createObjectNode().put("role", "assistant").put("content",
                    "{\"summary\":\"合成解释\",\"findings\":[],\"recommendations\":[],\"limitations\":[]}"), "stop");
        }, "synthetic-model", "https://example.invalid/v1");
        start(null, generator);
        try {
            var base = service.await(Json.MAPPER.readTree(request("POST", BASE,
                    LogInvestigationServiceTest.explicit("none", "task-B").toString()).body()).path("batch_id").asText());
            String id = base.path("batch_id").asText(), path = BASE + "/" + id + "/narrative";
            assertEquals(202, request("POST", path, "{\"narrative\":\"llm\"}").statusCode());
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(202, request("POST", path, "{\"narrative\":\"llm\"}").statusCode());
            assertEquals(1, calls.get());
            release.countDown();
            var result = service.await(id);
            assertEquals("AVAILABLE", result.path("narrative_status").asText());
            assertEquals(base.path("tasks"), result.path("tasks"));
            assertEquals(202, request("POST", path, "{\"narrative\":\"llm\"}").statusCode());
            assertEquals(1, calls.get());
        } finally { release.countDown(); }
    }

    private void start(LogSource source) throws IOException {
        start(source, null);
    }
    private void start(LogSource source, LogNarrativeGenerator generator) throws IOException {
        var config = LogInvestigationServiceTest.fixtureConfig(directory, Map.of());
        service = new LogInvestigationService(config, source == null ? new FileLogSource(config.logsFile()) : source,
                new FluxDropArchiveSink(config.fluxDropUrl(), "", config.dataDir().resolve("spool"), config.spoolBytes(), config.chunkBytes(), config.requestTimeout()),
                TaskSource.file(config), generator);
        server = new HttpApi(null, service).start(0);
    }
    private HttpResponse<String> request(String method, String path, String body) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
                .timeout(Duration.ofSeconds(4)).header("Content-Type", "application/json")
                .method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
