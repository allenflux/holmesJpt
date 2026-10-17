package dev.holmes.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.holmes.Json;
import dev.holmes.LlmClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static dev.holmes.logs.LogInvestigationServiceTest.*;

/** 合成来源验证一键发现、双ID取证、模型工具分析和持久化的完整链路。 */
class LogAutomaticPipelineTest {
    @TempDir Path directory;

    @Test void automaticAssociatesSchedulerIdsAndExplainsFrequentErrorsWithoutAnotherCollection() throws Exception {
        var config = fixtureConfig(directory, Map.of("HOLMES_LOG_TASKS_FILE", ""));
        var time = Instant.now().minusSeconds(60);
        var selected = List.of(sourceTask("a", time), sourceTask("b", time));
        AtomicInteger fetches = new AtomicInteger(), modelCalls = new AtomicInteger();
        LogSource source = (query, maxEvents, maxBytes, deadline) -> {
            fetches.incrementAndGet();
            String suffix = query.identifiers().contains("scheduler-a") ? "a" : "b";
            assertTrue(query.identifiers().containsAll(List.of("platform-" + suffix, "scheduler-" + suffix)));
            assertTrue(query.from().isBefore(time));
            assertTrue(query.to().isAfter(time));
            return new LogModels.FetchResult(List.of(new LogModels.LogEvent(time,
                    "{\"task_id\":\"scheduler-" + suffix + "\",\"prompt_id\":\"scheduler-" + suffix
                            + "\",\"event\":\"execution_error\",\"node_type\":\"FaceFusionNode\","
                            + "\"exception_type\":\"RuntimeError\",\"exception_message\":\"No output was generated\"}",
                    Map.of("namespace", "prod", "pod", "synthetic-pod-" + suffix, "container", "comfyui"), "event-" + suffix)),
                    true, List.of(), 250);
        };
        LlmClient client = (messages, tools) -> {
            int round = modelCalls.incrementAndGet();
            for (String privateId : List.of("platform-a", "platform-b", "scheduler-a", "scheduler-b", "synthetic-pod-a", "synthetic-pod-b"))
                assertFalse(messages.toString().contains(privateId));
            if (round == 1) {
                JsonNode overview = Json.MAPPER.readTree(messages.get(1).path("content").asText());
                assertEquals(2, overview.path("tasks").size());
                assertEquals(2, overview.path("error_groups").get(0).path("task_count").asInt());
                var assistant = Json.MAPPER.createObjectNode().put("role", "assistant").putNull("content");
                assistant.putArray("tool_calls").addObject().put("id", "synthetic-read").put("type", "function")
                        .putObject("function").put("name", "get_error_samples").put("arguments", "{}");
                return new LlmClient.Reply(assistant, "tool_calls");
            }
            JsonNode result = null;
            for (JsonNode message : messages) if (message.path("role").asText().equals("tool"))
                result = Json.MAPPER.readTree(message.path("content").asText());
            assertNotNull(result);
            String evidenceId = result.path("evidence").get(0).path("evidence_id").asText();
            var answer = Json.MAPPER.createObjectNode().put("summary", "合成样本中两个任务出现节点无输出。");
            answer.putArray("findings").addObject().put("title", "FaceFusion节点无输出")
                    .put("explanation", "合成排查建议：检查节点输出文件和检测器参数；当前仅为候选错误现象。")
                    .putArray("evidence_ids").add(evidenceId);
            answer.putArray("recommendations").add("核对代表证据后单项复测。");
            answer.putArray("limitations").add("合成测试，不确认最终执行归属。");
            return new LlmClient.Reply(Json.MAPPER.createObjectNode().put("role", "assistant").put("content", answer.toString()), "stop");
        };
        var generator = new LogNarrativeGenerator(client, "synthetic-model", "local", true);
        var sink = new FluxDropArchiveSink(config.fluxDropUrl(), "", config.dataDir().resolve("spool"),
                config.spoolBytes(), config.chunkBytes(), config.requestTimeout());
        try (var service = new LogInvestigationService(config, source, sink,
                request -> new TaskSnapshot.Selection(selected, Instant.now().toString(), List.of()), generator)) {
            var report = completed(service, service.submitAutomatic());
            assertEquals("llm", report.path("request").path("narrative").asText());
            assertEquals("automatic", report.path("request").path("log_window").asText());
            assertEquals("AVAILABLE", report.path("narrative_status").asText());
            assertEquals(2, fetches.get());
            assertEquals(2, modelCalls.get());
            assertEquals(2, report.path("engineer_view").path("groups").get(0).path("task_count").asInt());
            for (JsonNode task : report.path("tasks")) {
                assertEquals("COLLECTED", task.path("collection_status").asText());
                assertEquals("SOURCE_RECORD_TASK_ID", task.path("log_lookup").path("mapping_status").asText());
                assertEquals("TASK_ONLY", task.path("association_scope").asText());
                assertEquals("INCOMPLETE", task.path("signature").asText());
            }
            String id = report.path("batch_id").asText();
            var stored = Json.MAPPER.readTree(Files.readAllBytes(config.dataDir().resolve("reports").resolve(id + ".json")));
            assertEquals(report.path("narrative"), stored.path("narrative"));
            assertEquals(report.path("evidence"), stored.path("evidence"));
            assertEquals(report.path("narrative"), service.requestNarrative(id).path("narrative"));
            assertEquals(2, modelCalls.get());
            assertEquals(2, fetches.get());
        }
    }

    private static LogModels.Task sourceTask(String suffix, Instant time) {
        return new LogModels.Task("ref-" + suffix, "platform-" + suffix, "failed", List.of(), "", "", null, "version-1",
                LogModels.AssociationScope.TASK_ONLY,
                new LogModels.SchedulerFacts("scheduler-" + suffix, "face_video", "FAILED", time.minusSeconds(5), time));
    }
}
