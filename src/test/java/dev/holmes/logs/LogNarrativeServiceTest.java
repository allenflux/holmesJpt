package dev.holmes.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.holmes.Json;
import dev.holmes.LlmClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static dev.holmes.logs.LogInvestigationServiceTest.*;

class LogNarrativeServiceTest {
    @TempDir Path directory;

    @Test void narrativeNoneNeverCallsModelAndMissingBatchKeepsNullContract() throws Exception {
        LogConfig config = fixtureConfig(directory, Map.of());
        AtomicInteger calls = new AtomicInteger();
        try (var service = service(config, new FileLogSource(config.logsFile()), generator((messages, tools) -> {
            calls.incrementAndGet(); return reply("合成解释");
        }))) {
            var report = completed(service, service.submit(explicit("none", "task-B")));
            assertEquals("NOT_REQUESTED", report.path("narrative_status").asText());
            assertEquals(0, calls.get());
            assertNull(service.get("00000000-0000-0000-0000-000000000000"));
        }
    }

    @Test void newBatchGeneratesNarrativeWithoutChangingRulesOrEvidence() throws Exception {
        LogConfig config = fixtureConfig(directory, Map.of());
        AtomicInteger calls = new AtomicInteger();
        try (var service = service(config, new FileLogSource(config.logsFile()), generator((messages, tools) -> {
            calls.incrementAndGet(); assertTrue(tools.isEmpty()); return reply("合成解释");
        }))) {
            var report = completed(service, service.submit(explicit("none", "task-B").put("narrative", "llm")));
            assertEquals("AVAILABLE", report.path("narrative_status").asText());
            assertEquals("合成解释", report.path("narrative").path("summary").asText());
            assertEquals("CUDA_OOM", report.path("tasks").get(0).path("signature").asText());
            assertEquals(1, report.path("selected_count").asInt());
            assertFalse(report.path("evidence").isEmpty());
            assertFalse(report.path("narrative_incomplete").asBoolean());
            assertEquals(1, calls.get());
        }
    }

    @Test void emptyCollectionSkipsModelAndExplicitLlmWithoutGeneratorIsUnavailable() throws Exception {
        LogConfig config = fixtureConfig(directory, Map.of());
        AtomicInteger calls = new AtomicInteger();
        LogSource empty = (query, maxEvents, maxBytes, deadline) -> new LogModels.FetchResult(List.of(), true, List.of(), 0);
        try (var service = service(config, empty, generator((messages, tools) -> {
            calls.incrementAndGet(); return reply("不应调用");
        }))) {
            var report = completed(service, service.submit(explicit("none", "task-B").put("narrative", "llm")));
            assertEquals("SKIPPED_NO_EVIDENCE", report.path("narrative_status").asText());
            assertEquals(0, calls.get());
        }
        try (var service = service(config, empty, null)) {
            assertThrows(LogInvestigationService.NarrativeUnavailable.class,
                    () -> service.submit(explicit("none", "task-B").put("narrative", "llm")));
        }
    }

    @Test void providerFailurePreservesRuleReportAndOnlyStoresSafeErrorCode() throws Exception {
        LogConfig config = fixtureConfig(directory, Map.of());
        try (var service = service(config, new FileLogSource(config.logsFile()), generator((messages, tools) -> {
            throw new IOException("private-provider-token synthetic-secret");
        }))) {
            var base = completed(service, service.submit(explicit("none", "task-B")));
            service.requestNarrative(base.path("batch_id").asText());
            var report = service.await(base.path("batch_id").asText());
            assertEquals("FAILED", report.path("narrative_status").asText());
            assertEquals("NARRATIVE_GENERATION_FAILED", report.path("narrative_error_code").asText());
            assertTrue(report.path("narrative_incomplete").asBoolean());
            assertEquals(base.path("state"), report.path("state"));
            assertEquals(base.path("tasks"), report.path("tasks"));
            assertEquals(base.path("evidence"), report.path("evidence"));
            assertFalse(report.toString().contains("synthetic-secret"));
            assertFalse(report.path("collection_incomplete").asBoolean());
        }
    }

    @Test void typedFailurePersistsSafeToolTraceWithinReportBudgetWithoutCompactingEvidence() throws Exception {
        LogConfig config = fixtureConfig(directory, Map.of("HOLMES_LOG_REPORT_BYTES", "16384"));
        var steps = Json.MAPPER.createArrayNode();
        for (int i = 0; i < 6; i++) steps.addObject().put("tool", "read_evidence").put("status", "OK").put("evidence_count", 1)
                .put("provider_body", "synthetic-private-provider-body");
        var failure = new LogNarrativeFailure("NARRATIVE_PROVIDER_HTTP_429", 2, 6, steps);
        try (var service = service(config, new FileLogSource(config.logsFile()), generator((messages, tools) -> { throw failure; }))) {
            var base = completed(service, service.submit(explicit("none", "task-B")));
            String id = base.path("batch_id").asText();
            Path file = config.dataDir().resolve("reports").resolve(id + ".json");
            ObjectNode stored = (ObjectNode) Json.MAPPER.readTree(Files.readAllBytes(file));
            stored.put("synthetic_padding", "");
            int padding = (int) config.reportBytes() - Json.MAPPER.writeValueAsBytes(stored).length - 500;
            assertTrue(padding > 0);
            stored.put("synthetic_padding", "x".repeat(padding));
            Files.writeString(file, stored.toString());

            service.requestNarrative(id);
            var report = service.await(id);

            assertEquals("NARRATIVE_PROVIDER_HTTP_429", report.path("narrative_error_code").asText());
            JsonNode diagnostic = report.path("narrative_diagnostic");
            assertEquals("PROVIDER", diagnostic.path("stage").asText());
            assertEquals("NARRATIVE_PROVIDER_HTTP_429", diagnostic.path("code").asText());
            assertEquals(2, diagnostic.path("model_rounds").asInt());
            assertEquals(6, diagnostic.path("tool_calls").asInt());
            assertTrue(diagnostic.path("steps").size() > 0 && diagnostic.path("steps").size() < 6);
            assertTrue(diagnostic.path("truncated").asBoolean());
            assertFalse(report.toString().contains("synthetic-private-provider-body"));
            assertTrue(Files.size(file) <= config.reportBytes());
            assertEquals(diagnostic, Json.MAPPER.readTree(Files.readAllBytes(file)).path("narrative_diagnostic"));
            for (String field : List.of("state", "tasks", "evidence", "clusters", "retrieved_event_count", "collection_incomplete", "attribution_incomplete"))
                assertEquals(base.path(field), report.path(field), field);
        }
    }

    @Test void ordinaryExceptionCannotForgeProviderStatusOrPersistUnsafeMessageOrNestedCause() throws Exception {
        LogConfig config = fixtureConfig(directory, Map.of());
        try (var service = service(config, new FileLogSource(config.logsFile()), generator((messages, tools) -> {
            throw new IOException("NARRATIVE_PROVIDER_HTTP_401 token=synthetic-private-message",
                    new IOException("https://example.invalid/private synthetic-private-cause"));
        }))) {
            var base = completed(service, service.submit(explicit("none", "task-B")));
            String id = base.path("batch_id").asText();
            service.requestNarrative(id);
            var report = service.await(id);

            assertEquals("NARRATIVE_GENERATION_FAILED", report.path("narrative_error_code").asText());
            assertEquals("UNKNOWN", report.path("narrative_diagnostic").path("stage").asText());
            for (String unsafe : List.of("synthetic-private-message", "synthetic-private-cause", "example.invalid/private", "HTTP_401"))
                assertFalse(report.toString().contains(unsafe), unsafe);
            assertEquals(base.path("tasks"), report.path("tasks"));
            assertEquals(base.path("evidence"), report.path("evidence"));
        }
    }

    @Test void retryClearsOldFailureDiagnosticAndAvailableReuseRetainsSuccessfulAnalysis() throws Exception {
        LogConfig config = fixtureConfig(directory, Map.of());
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        LogNarrativeGenerator generator = new LogNarrativeGenerator((messages, tools) -> {
            if (calls.incrementAndGet() == 1) throw new LogNarrativeFailure("NARRATIVE_PROVIDER_HTTP_429", 1, 0, null);
            if (messages.size() == 2 && !tools.isEmpty()) {
                entered.countDown(); release.await();
                String evidence = Json.MAPPER.readTree(messages.get(1).path("content").asText()).path("evidence").get(0).path("evidence_id").asText();
                ObjectNode call = Json.MAPPER.createObjectNode().put("role", "assistant").putNull("content");
                call.putArray("tool_calls").addObject().put("id", "synthetic-retry-call").put("type", "function")
                        .putObject("function").put("name", "read_evidence").put("arguments",
                                Json.MAPPER.createObjectNode().put("evidence_id", evidence).toString());
                return new LlmClient.Reply(call, "tool_calls");
            }
            return reply("合成重试成功");
        }, "synthetic-model", "https://example.invalid/v1", true);
        try (var service = service(config, new FileLogSource(config.logsFile()), generator)) {
            var base = completed(service, service.submit(explicit("none", "task-B")));
            String id = base.path("batch_id").asText();
            service.requestNarrative(id);
            var failed = service.await(id);
            assertTrue(failed.has("narrative_diagnostic"));

            var accepted = service.requestNarrative(id);
            assertFalse(accepted.has("narrative_diagnostic"));
            assertFalse(accepted.has("narrative_error_code"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertFalse(service.get(id).has("narrative_diagnostic"));
            release.countDown();
            var available = service.await(id);

            assertEquals("AVAILABLE", available.path("narrative_status").asText());
            assertFalse(available.has("narrative_diagnostic"));
            assertFalse(available.has("narrative_error_code"));
            assertEquals("TOOL_DRIVEN", available.path("narrative").path("analysis").path("mode").asText());
            assertEquals(1, available.path("narrative").path("analysis").path("tool_calls").asInt());
            int finishedCalls = calls.get();
            assertEquals(available.path("narrative"), service.requestNarrative(id).path("narrative"));
            assertEquals(finishedCalls, calls.get());
            assertEquals(base.path("tasks"), available.path("tasks"));
            assertEquals(base.path("evidence"), available.path("evidence"));
        } finally { release.countDown(); }
    }

    @Test void onDemandIsAsynchronousDeduplicatedAndAvailableResultIsReusedWithoutCollectingAgain() throws Exception {
        LogConfig config = fixtureConfig(directory, Map.of());
        AtomicInteger modelCalls = new AtomicInteger(), collections = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        FileLogSource file = new FileLogSource(config.logsFile());
        try (var service = service(config, (query, maxEvents, maxBytes, deadline) -> {
            collections.incrementAndGet(); return file.fetch(query, maxEvents, maxBytes, deadline);
        }, generator((messages, tools) -> {
            modelCalls.incrementAndGet(); entered.countDown(); release.await(); return reply("合成解释");
        }))) {
            var base = completed(service, service.submit(explicit("none", "task-B")));
            String id = base.path("batch_id").asText();
            int fetched = collections.get();
            var accepted = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> service.requestNarrative(id));
            assertEquals("PENDING", accepted.path("narrative_status").asText());
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals("RUNNING", service.requestNarrative(id).path("narrative_status").asText());
            assertEquals("SUMMARIZING", service.get(id).path("stage").asText());
            assertEquals(base.path("state"), service.get(id).path("state"));
            CountDownLatch finished = new CountDownLatch(1);
            Thread awaiting = Thread.ofVirtual().start(() -> {
                try { service.await(id); finished.countDown(); }
                catch (IOException | InterruptedException error) { throw new AssertionError(error); }
            });
            assertFalse(finished.await(50, TimeUnit.MILLISECONDS));
            release.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS)); awaiting.join();
            assertEquals("AVAILABLE", service.requestNarrative(id).path("narrative_status").asText());
            assertEquals(1, modelCalls.get());
            assertEquals(fetched, collections.get());
            assertEquals(base.path("tasks"), service.get(id).path("tasks"));
        } finally { release.countDown(); }
    }

    @Test void modelDeadlineCancelsFutureAndRetainsCollectedEvidence() throws Exception {
        LogConfig config = fixtureConfig(directory, Map.of("HOLMES_LOG_JOB_SECONDS", "1"));
        CountDownLatch cancelled = new CountDownLatch(1);
        try (var service = service(config, new FileLogSource(config.logsFile()), generator((messages, tools) -> {
            try { new CountDownLatch(1).await(); return reply("不应返回"); }
            catch (InterruptedException interrupted) { cancelled.countDown(); throw interrupted; }
        }))) {
            var base = completed(service, service.submit(explicit("none", "task-B")));
            String id = base.path("batch_id").asText();
            service.requestNarrative(id);
            var report = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> service.await(id));
            assertEquals("FAILED", report.path("narrative_status").asText());
            assertEquals("NARRATIVE_TIMEOUT", report.path("narrative_error_code").asText());
            JsonNode diagnostic = report.path("narrative_diagnostic");
            assertEquals("UNKNOWN", diagnostic.path("stage").asText());
            assertEquals("NARRATIVE_TIMEOUT", diagnostic.path("code").asText());
            assertTrue(diagnostic.path("model_rounds").isNull());
            assertTrue(diagnostic.path("tool_calls").isNull());
            assertTrue(diagnostic.path("steps").isEmpty());
            assertEquals(base.path("evidence"), report.path("evidence"));
            assertTrue(cancelled.await(1, TimeUnit.SECONDS));
        }
    }

    @Test void abandonedTerminalModelStateIsInterruptedOnReadAndNeverAutomaticallyResent() throws Exception {
        LogConfig config = fixtureConfig(directory, Map.of());
        String id;
        ObjectNode base;
        try (var service = service(config, new FileLogSource(config.logsFile()), null)) {
            base = completed(service, service.submit(explicit("none", "task-B")));
            id = base.path("batch_id").asText();
        }
        Path reportFile = config.dataDir().resolve("reports").resolve(id + ".json");
        ObjectNode saved = (ObjectNode) Json.MAPPER.readTree(Files.readAllBytes(reportFile));
        saved.put("narrative_status", "RUNNING").put("stage", "SUMMARIZING").put("_narrative_base_stage", "DONE");
        Files.writeString(reportFile, saved.toString());
        AtomicInteger calls = new AtomicInteger();
        try (var recovered = service(config, new FileLogSource(config.logsFile()), generator((messages, tools) -> {
            calls.incrementAndGet(); return reply("不应自动重发");
        }))) {
            var report = recovered.get(id);
            assertEquals("INTERRUPTED", report.path("narrative_status").asText());
            assertEquals("DONE", report.path("stage").asText());
            assertEquals(base.path("tasks"), report.path("tasks"));
            assertEquals(0, calls.get());
            assertFalse(report.has("_narrative_base_stage"));
        }
    }

    @Test void oversizedModelResultFailsNarrativeWithoutCompactingOrInvalidatingCollection() throws Exception {
        LogConfig config = fixtureConfig(directory, Map.of("HOLMES_LOG_REPORT_BYTES", "16384"));
        try (var service = service(config, new FileLogSource(config.logsFile()), generator((messages, tools) -> {
            ObjectNode body = Json.MAPPER.createObjectNode().put("summary", "字".repeat(3_000));
            body.putArray("findings"); body.putArray("limitations");
            var recommendations = body.putArray("recommendations");
            for (int i = 0; i < 12; i++) recommendations.add("字".repeat(600));
            return new LlmClient.Reply(Json.MAPPER.createObjectNode().put("role", "assistant").put("content", body.toString()), "stop");
        }))) {
            var base = completed(service, service.submit(explicit("none", "task-B")));
            String id = base.path("batch_id").asText();
            service.requestNarrative(id);
            var report = service.await(id);
            assertEquals("FAILED", report.path("narrative_status").asText());
            assertEquals("NARRATIVE_REPORT_LIMIT", report.path("narrative_error_code").asText());
            assertFalse(report.path("collection_incomplete").asBoolean());
            assertEquals(base.path("tasks"), report.path("tasks"));
            assertEquals(base.path("evidence"), report.path("evidence"));
            assertFalse(report.has("narrative"));
        }
    }

    @Test void pendingNarrativeDuringRecoveredCollectionIsInterruptedWithoutModelReplay() throws Exception {
        LogConfig config = fixtureConfig(directory, Map.of());
        String id;
        try (var original = service(config, new FileLogSource(config.logsFile()), null)) {
            id = completed(original, original.submit(explicit("none", "task-B"))).path("batch_id").asText();
        }
        Path file = config.dataDir().resolve("reports").resolve(id + ".json");
        ObjectNode saved = (ObjectNode) Json.MAPPER.readTree(Files.readAllBytes(file));
        saved.put("state", "RUNNING").put("stage", "COLLECTING").put("narrative_status", "PENDING");
        ((ObjectNode) saved.path("request")).put("narrative", "llm");
        Files.writeString(file, saved.toString());
        AtomicInteger calls = new AtomicInteger();
        try (var recovered = service(config, new FileLogSource(config.logsFile()), generator((messages, tools) -> {
            calls.incrementAndGet(); return reply("不得自动重发");
        }))) {
            var report = recovered.await(id);
            assertEquals("INTERRUPTED", report.path("narrative_status").asText());
            assertEquals("COMPLETED", report.path("state").asText());
            assertEquals(0, calls.get());
        }
    }

    private static LogInvestigationService service(LogConfig config, LogSource logs, LogNarrativeGenerator generator) throws IOException {
        return new LogInvestigationService(config, logs, new FluxDropArchiveSink(config.fluxDropUrl(), "",
                config.dataDir().resolve("spool"), config.spoolBytes(), config.chunkBytes(), config.requestTimeout()),
                TaskSource.file(config), generator);
    }
    private static LogNarrativeGenerator generator(LlmClient client) { return new LogNarrativeGenerator(client, "synthetic-model", "https://example.invalid/v1"); }
    private static LlmClient.Reply reply(String summary) {
        ObjectNode body = Json.MAPPER.createObjectNode().put("summary", summary);
        body.putArray("findings"); body.putArray("recommendations"); body.putArray("limitations").add("合成测试，不认定最终根因。");
        return new LlmClient.Reply(Json.MAPPER.createObjectNode().put("role", "assistant").put("content", body.toString()), "stop");
    }
}
