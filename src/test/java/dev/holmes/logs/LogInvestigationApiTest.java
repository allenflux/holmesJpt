package dev.holmes.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import dev.holmes.HttpApi;
import dev.holmes.Json;
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

    private void start(LogSource source) throws IOException {
        var config = LogInvestigationServiceTest.fixtureConfig(directory, Map.of());
        service = source == null ? new LogInvestigationService(config) : new LogInvestigationService(config, source,
                new FluxDropArchiveSink(config.fluxDropUrl(), "", config.dataDir().resolve("spool"), config.spoolBytes(), config.chunkBytes(), config.requestTimeout()));
        server = new HttpApi(null, service).start(0);
    }
    private HttpResponse<String> request(String method, String path, String body) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
                .timeout(Duration.ofSeconds(4)).header("Content-Type", "application/json")
                .method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
