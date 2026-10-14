package dev.holmes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.holmes.tools.ToolRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AgentEngineTest {
    @TempDir Path directory;

    @Test void multiRoundInvestigationPairsEveryToolResultWithItsRequest() throws Exception {
        Files.writeString(directory.resolve("incident.log"), "ERROR database timeout");
        List<ArrayNode> requests = new ArrayList<>();
        AtomicInteger round = new AtomicInteger();
        LlmClient llm = (messages, tools) -> {
            requests.add(messages);
            return switch (round.incrementAndGet()) {
                case 1 -> call("list-1", "list_files", "{}");
                case 2 -> call("read-2", "read_file", "{\"path\":\"incident.log\"}");
                default -> answer("日志证明数据库超时", "stop");
            };
        };

        var result = engine(llm, 8, 24, 120000).investigate("为何请求失败？");

        assertEquals("completed", result.stopReason());
        assertEquals(3, result.steps());
        assertEquals(2, result.toolCalls());
        assertEquals(2, result.trace().size());
        assertEquals("list-1", requests.get(1).get(3).path("tool_call_id").asText());
        assertEquals("read-2", requests.get(2).get(5).path("tool_call_id").asText());
        assertTrue(requests.get(2).get(5).path("content").asText().contains("database timeout"));
        for (int i : List.of(2, 4)) {
            JsonNode assistant = requests.get(2).get(i);
            JsonNode tool = requests.get(2).get(i + 1);
            assertEquals("assistant", assistant.path("role").asText());
            assertEquals("tool", tool.path("role").asText());
            assertEquals(assistant.path("tool_calls").get(0).path("id").asText(),
                    tool.path("tool_call_id").asText());
        }
    }

    @Test void repeatedArgumentsReuseEvidenceWithoutRunningToolAgain() throws Exception {
        Path evidence = directory.resolve("incident.log");
        Files.writeString(evidence, "original evidence");
        AtomicInteger round = new AtomicInteger();
        LlmClient llm = (messages, tools) -> {
            int n = round.incrementAndGet();
            if (n == 2) Files.writeString(evidence, "changed evidence");
            if (n <= 2) return call("read-" + n, "read_file", "{\"path\":\"incident.log\"}");
            assertTrue(messages.get(5).path("content").asText().contains("original evidence"));
            assertFalse(messages.get(5).path("content").asText().contains("changed evidence"));
            return answer("已有证据", "stop");
        };

        var result = engine(llm, 8, 24, 120000).investigate("读取日志");

        assertEquals(1, result.toolCalls());
        assertFalse(result.trace().get(0).reused());
        assertTrue(result.trace().get(1).reused());
        assertEquals(result.trace().get(0).output(), result.trace().get(1).output());
    }

    @Test void malformedArgumentsAreReturnedAsToolFailureAndInvestigationContinues() throws Exception {
        AtomicInteger round = new AtomicInteger();
        LlmClient llm = (messages, tools) -> {
            if (round.incrementAndGet() == 1) return call("bad-1", "read_file", "not-json");
            JsonNode result = Json.MAPPER.readTree(messages.get(3).path("content").asText());
            assertFalse(result.path("success").asBoolean());
            assertEquals("bad-1", messages.get(3).path("tool_call_id").asText());
            return answer("参数错误，证据不足", "stop");
        };

        var result = engine(llm, 8, 24, 120000).investigate("检查日志");

        assertEquals("completed", result.stopReason());
        assertEquals(1, result.toolCalls());
        assertFalse(result.trace().getFirst().success());
    }

    @Test void toolBudgetPreservesPairingForUnexecutedCallsAndForcesFinalAnswer() throws Exception {
        AtomicInteger round = new AtomicInteger();
        LlmClient llm = (messages, tools) -> {
            if (round.incrementAndGet() == 1) {
                ObjectNode message = call("a", "list_files", "{}").message();
                ((ArrayNode) message.path("tool_calls")).add(call("b", "list_files", "{}").message().path("tool_calls").get(0));
                return new LlmClient.Reply(message, "tool_calls");
            }
            assertTrue(tools.isEmpty());
            assertEquals("a", messages.get(3).path("tool_call_id").asText());
            assertEquals("b", messages.get(4).path("tool_call_id").asText());
            assertFalse(Json.MAPPER.readTree(messages.get(4).path("content").asText()).path("success").asBoolean());
            return answer("工具预算已用尽", "stop");
        };

        var result = engine(llm, 8, 1, 120000).investigate("检查证据");

        assertEquals("tool_budget", result.stopReason());
        assertEquals(1, result.toolCalls());
        assertEquals(2, result.trace().size());
    }

    @Test void lastStepDisablesToolsAndReportsStepBudget() throws Exception {
        AtomicInteger round = new AtomicInteger();
        LlmClient llm = (messages, tools) -> {
            if (round.incrementAndGet() == 1) return call("a", "list_files", "{}");
            assertTrue(tools.isEmpty());
            assertTrue(messages.get(messages.size() - 1).path("content").asText().contains("停止取证"));
            return answer("无法继续确认", "stop");
        };

        var result = engine(llm, 2, 24, 120000).investigate("检查证据");

        assertEquals("step_budget", result.stopReason());
        assertEquals(2, result.steps());
    }

    @Test void oversizedToolEvidenceStopsBeforeAnotherLlmRequest() throws Exception {
        Files.writeString(directory.resolve("large.log"), "证据".repeat(3000));
        AtomicInteger requests = new AtomicInteger();
        LlmClient llm = (messages, tools) -> {
            requests.incrementAndGet();
            return call("large", "read_file", "{\"path\":\"large.log\"}");
        };

        var result = engine(llm, 8, 24, 4096).investigate("读取证据");

        assertEquals("context_budget", result.stopReason());
        assertEquals(1, requests.get());
        assertEquals(1, result.toolCalls());
        assertEquals(1, result.trace().size());
    }

    @Test void truncatedModelAnswerIsMarkedIncomplete() throws Exception {
        var result = engine((messages, tools) -> answer("未完整输出的结论", "length"), 8, 24, 120000)
                .investigate("检查证据");
        assertEquals("model_truncated", result.stopReason());
        assertEquals(0, result.toolCalls());
    }

    @Test void duplicateToolIdsAreRejectedBeforeAnyExecution() throws Exception {
        ObjectNode message = call("same", "list_files", "{}").message();
        ((ArrayNode) message.path("tool_calls")).add(message.path("tool_calls").get(0).deepCopy());
        AgentEngine engine = engine((messages, tools) -> new LlmClient.Reply(message, "tool_calls"), 8, 24, 120000);
        assertThrows(IOException.class, () -> engine.investigate("检查证据"));
    }

    private AgentEngine engine(LlmClient llm, int steps, int calls, int context) throws IOException {
        Path config = directory.resolve("tools.json");
        ObjectNode definition = Json.MAPPER.createObjectNode().put("fileRoot", directory.toString());
        definition.putArray("commands");
        Files.writeString(config, definition.toString());
        return new AgentEngine(llm, ToolRegistry.load(config, 2, 12000), "", steps, calls, context);
    }

    private static LlmClient.Reply call(String id, String name, String arguments) {
        ObjectNode message = Json.MAPPER.createObjectNode().put("role", "assistant");
        ObjectNode call = message.putArray("tool_calls").addObject().put("id", id).put("type", "function");
        call.putObject("function").put("name", name).put("arguments", arguments);
        return new LlmClient.Reply(message, "tool_calls");
    }

    private static LlmClient.Reply answer(String text, String reason) {
        return new LlmClient.Reply(Json.MAPPER.createObjectNode().put("role", "assistant").put("content", text), reason);
    }
}
