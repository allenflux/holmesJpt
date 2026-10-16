package dev.holmes.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import dev.holmes.Json;
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
        var service = new LogInvestigationService(config, source, new FluxDropArchiveSink(config.fluxDropUrl(), "", config.dataDir().resolve("spool"),
                config.spoolBytes(), config.chunkBytes(), config.requestTimeout()));
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
