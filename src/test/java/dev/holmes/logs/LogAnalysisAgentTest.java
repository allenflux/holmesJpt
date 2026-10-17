package dev.holmes.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.holmes.Json;
import dev.holmes.LlmClient;
import dev.holmes.LlmRequestFailure;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LogAnalysisAgentTest {
    @Test void modelChoosesEvidenceAndValidatedEarlyFinalAvoidsExtraCallAndKeepsInputImmutable() throws Exception {
        ArrayNode input = input(); ArrayNode before = input.deepCopy();
        Capturing client = new Capturing((round, messages, tools) -> round == 1
                ? calls(call("chosen-1", "read_evidence", "{\"evidence_id\":\"ev-a\"}")) : answer("ev-a"));

        LogAnalysisAgent.Result result = new LogAnalysisAgent(client).run(input);

        assertEquals(before, input); assertEquals(2, client.messages.size());
        assertFalse(client.tools.get(0).isEmpty()); assertFalse(client.tools.get(1).isEmpty());
        JsonNode catalog = payload(client.messages.get(0));
        assertFalse(catalog.path("evidence").get(0).has("error_summary"));
        assertFalse(catalog.path("evidence").get(1).has("exception_message"));
        assertFalse(client.messages.get(0).toString().contains("resource-1"));
        assertTrue(client.messages.get(0).get(0).path("content").asText().contains("TASK_ONLY"));
        assertTrue(client.messages.get(0).get(0).path("content").asText().contains("不访问日志服务"));
        JsonNode resultEvidence = toolResults(client.messages.get(1)).get(0).path("evidence").get(0);
        assertEquals("ev-a", resultEvidence.path("evidence_id").asText());
        assertEquals("资源 resource-1 返回404", resultEvidence.path("error_summary").asText());
        assertEquals("TOOL_DRIVEN", result.analysis().path("mode").asText());
        assertEquals(2, result.analysis().path("model_rounds").asInt());
        assertEquals(1, result.analysis().path("tool_calls").asInt());
        assertEquals("OK", result.analysis().path("steps").get(0).path("status").asText());
        assertFalse(result.reply().message().has("tool_calls"));
        assertEquals("stop", result.reply().finishReason());
        assertPairs(client.messages.get(1));
    }

    @Test void toolSchemasAreClosedAndExposeOnlyMemoryReaders() throws Exception {
        Capturing client = new Capturing((round, messages, tools) -> round == 1 ? calls(call("c1", "get_error_samples", "{}")) : answer("ev-a"));
        new LogAnalysisAgent(client).run(input());

        assertEquals(List.of("get_error_samples", "read_task_timeline", "read_evidence", "compare_tasks"),
                names(client.tools.get(0)));
        for (JsonNode tool : client.tools.get(0)) {
            assertFalse(tool.path("function").has("strict"));
            assertFalse(tool.path("function").path("parameters").path("additionalProperties").asBoolean(true));
            assertFalse(tool.toString().contains("url")); assertFalse(tool.toString().contains("query"));
        }
    }

    @Test void timelineUsesOnlySelectedTaskSortsTimeAndAdmitsOnlyRepresentativeHistory() throws Exception {
        ObjectNode payload = (ObjectNode) payload(input());
        payload.withArray("evidence").addObject().put("evidence_id", "ev-early").put("task_ref", "ref-a")
                .put("event", "task_started").put("event_time", "2026-10-17T01:00:00Z");
        Capturing client = new Capturing((round, messages, tools) -> round == 1
                ? calls(call("c1", "read_task_timeline", "{\"task_ref\":\"ref-a\"}")) : answer("ev-a"));

        new LogAnalysisAgent(client).run(messages(payload));

        JsonNode result = toolResults(client.messages.get(1)).get(0);
        assertTrue(result.path("representative_only").asBoolean());
        assertEquals(2, result.path("evidence").size());
        assertEquals("ev-early", result.path("evidence").get(0).path("evidence_id").asText());
        assertEquals("ev-a", result.path("evidence").get(1).path("evidence_id").asText());
        assertFalse(result.toString().contains("ev-b"));
    }

    @Test void compareReturnsTaskSummariesAndAssociatedErrorsOnly() throws Exception {
        Capturing client = new Capturing((round, messages, tools) -> round == 1
                ? calls(call("c1", "compare_tasks", "{\"task_refs\":[\"ref-a\",\"ref-b\"]}")) : answer("ev-b"));

        new LogAnalysisAgent(client).run(input());

        JsonNode result = toolResults(client.messages.get(1)).get(0);
        assertEquals(2, result.path("tasks").size()); assertEquals(2, result.path("evidence").size());
        assertTrue(result.path("representative_only").asBoolean());
        assertEquals("TASK_ONLY", result.path("tasks").get(0).path("association_scope").asText());
    }

    @Test void rejectsExtraParametersCrossBatchReferencesAndUnknownToolsWithoutEchoingThem() throws Exception {
        Capturing client = new Capturing((round, messages, tools) -> round == 1 ? calls(
                call("c1", "read_evidence", "{\"evidence_id\":\"ev-a\"}"),
                call("c2", "read_evidence", "{\"evidence_id\":\"ev-a\",\"url\":\"https://private.invalid/secret\"}"),
                call("c3", "read_task_timeline", "{\"task_ref\":\"foreign-batch-ref\"}"),
                call("c4", "shell-private-command", "{\"path\":\"private-path-secret\"}")) : answer("ev-a"));

        LogAnalysisAgent.Result result = new LogAnalysisAgent(client).run(input());

        List<JsonNode> tools = toolResults(client.messages.get(1));
        assertEquals("INVALID_ARGUMENTS", tools.get(1).path("error").path("code").asText());
        assertEquals("UNKNOWN_REFERENCE", tools.get(2).path("error").path("code").asText());
        assertEquals("UNKNOWN_TOOL", tools.get(3).path("error").path("code").asText());
        assertEquals("UNKNOWN", result.analysis().path("steps").get(3).path("tool").asText());
        assertEquals("REJECTED", result.analysis().path("steps").get(1).path("status").asText());
        for (String excluded : List.of("private.invalid", "secret", "foreign-batch-ref", "shell-private-command", "private-path-secret")) {
            assertFalse(client.messages.get(1).toString().contains(excluded)); assertFalse(result.analysis().toString().contains(excluded));
        }
        assertPairs(client.messages.get(1));
    }

    @Test void duplicateReadsStayPairedButDoNotAuthorizeOtherCatalogEvidence() throws Exception {
        Capturing client = new Capturing((round, messages, tools) -> round == 1 ? calls(
                call("c1", "read_evidence", "{\"evidence_id\":\"ev-a\"}"),
                call("c2", "read_evidence", "{\"evidence_id\":\"ev-a\"}")) : answer("ev-b"));

        IOException failure = assertThrows(IOException.class, () -> new LogAnalysisAgent(client).run(input()));

        assertSafe(failure); assertEquals(3, client.messages.size());
        List<JsonNode> outputs = toolResults(client.messages.get(1)); assertEquals(outputs.get(0), outputs.get(1));
        assertPairs(client.messages.get(1));
    }

    @Test void withoutSuccessfulToolEvidenceFinalResponseIsRejected() {
        for (boolean invalidCall : List.of(false, true)) {
            Capturing client = new Capturing((round, messages, tools) -> invalidCall && round == 1
                    ? calls(call("c1", "read_evidence", "{\"evidence_id\":\"not-this-batch\"}")) : answer("ev-a"));
            assertSafe(assertThrows(IOException.class, () -> new LogAnalysisAgent(client).run(input())));
        }
    }

    @Test void toolBudgetNeverSendsMoreThanSixCallsAndFinalToolsAreEmpty() throws Exception {
        List<ObjectNode> proposed = new ArrayList<>();
        for (int i = 0; i < 8; i++) proposed.add(call("c" + i, "read_evidence", "{\"evidence_id\":\"ev-a\"}"));
        Capturing client = new Capturing((round, messages, tools) -> round == 1 ? calls(proposed.toArray(ObjectNode[]::new)) : answer("ev-a"));

        LogAnalysisAgent.Result result = new LogAnalysisAgent(client).run(input());

        assertEquals(2, client.messages.size()); assertTrue(client.tools.get(1).isEmpty());
        assertEquals(6, result.analysis().path("tool_calls").asInt());
        assertEquals(6, toolResults(client.messages.get(1)).size()); assertPairs(client.messages.get(1));
    }

    @Test void modelRoundBudgetForcesFourthRequestToReturnFinalWithoutTools() throws Exception {
        Capturing client = new Capturing((round, messages, tools) -> round < 4
                ? calls(call("c" + round, "read_evidence", "{\"evidence_id\":\"ev-a\"}")) : answer("ev-a"));

        LogAnalysisAgent.Result result = new LogAnalysisAgent(client).run(input());

        assertEquals(4, client.messages.size()); assertEquals(4, result.analysis().path("model_rounds").asInt());
        assertTrue(client.tools.get(3).isEmpty());
        assertPairs(client.messages.get(3));
    }

    @Test void toolResultAndContextBudgetsRetainErrorEvidenceAndRejectDroppedReferences() throws Exception {
        ObjectNode payload = (ObjectNode) payload(input()); ArrayNode evidence = payload.putArray("evidence");
        for (int i = 0; i < 10; i++) evidence.addObject().put("evidence_id", "ev-" + i).put("task_ref", "ref-a")
                .put("event", "execution_error").put("failure_signature", "HTTP_404").put("error_summary", "中文".repeat(500));
        Capturing client = new Capturing((round, messages, tools) -> round == 1 ? calls(call("c1", "get_error_samples", "{}")) : answer("ev-0"));

        LogAnalysisAgent.Result result = new LogAnalysisAgent(client).run(messages(payload));

        JsonNode output = toolResults(client.messages.get(1)).get(0);
        assertTrue(output.toString().getBytes(StandardCharsets.UTF_8).length <= 12 * 1024);
        assertTrue(output.path("truncated").asBoolean()); assertTrue(output.path("evidence").size() < 8);
        assertEquals("TRUNCATED", result.analysis().path("steps").get(0).path("status").asText());
        for (int i = 0; i < client.messages.size(); i++) assertTrue(byteSize(client.messages.get(i)) + byteSize(client.tools.get(i)) <= 48 * 1024);
        assertFalse(result.analysis().toString().contains("中文"));

        Capturing invalid = new Capturing((round, messages, tools) -> round == 1 ? calls(call("c1", "get_error_samples", "{}")) : answer("ev-7"));
        assertSafe(assertThrows(IOException.class, () -> new LogAnalysisAgent(invalid).run(messages(payload))));
    }

    @Test void cumulativeToolResultsStopAtContextBudgetWithoutBreakingPairs() throws Exception {
        ObjectNode payload = (ObjectNode) payload(input()); ArrayNode evidence = payload.putArray("evidence");
        for (int i = 0; i < 10; i++) evidence.addObject().put("evidence_id", "ev-" + i).put("task_ref", "ref-a")
                .put("event", "execution_error").put("failure_signature", "HTTP_404").put("error_summary", "中文".repeat(500));
        List<ObjectNode> reads = new ArrayList<>(); for (int i = 0; i < 6; i++) reads.add(call("c" + i, "get_error_samples", "{}"));
        Capturing client = new Capturing((round, messages, tools) -> round == 1 ? calls(reads.toArray(ObjectNode[]::new)) : answer("ev-0"));

        LogAnalysisAgent.Result result = new LogAnalysisAgent(client).run(messages(payload));

        assertEquals(2, client.messages.size()); assertTrue(client.tools.get(1).isEmpty()); assertEquals(6, result.analysis().path("tool_calls").asInt());
        assertPairs(client.messages.get(1));
        List<JsonNode> outputs = toolResults(client.messages.get(1)); assertEquals(6, outputs.size());
        assertTrue(outputs.get(5).path("evidence").size() < outputs.get(0).path("evidence").size());
        for (int i = 0; i < client.messages.size(); i++) assertTrue(byteSize(client.messages.get(i)) + byteSize(client.tools.get(i)) <= 48 * 1024);
    }

    @Test void compareBudgetDropsVerboseTaskFlagsBeforeErrorEvidence() throws Exception {
        ObjectNode payload = (ObjectNode) payload(input());
        for (JsonNode task : payload.path("tasks")) {
            ArrayNode flags = ((ObjectNode) task).putArray("quality_flags");
            for (int i = 0; i < 24; i++) flags.add("质量标记".repeat(50));
        }
        Capturing client = new Capturing((round, messages, tools) -> round == 1
                ? calls(call("c1", "compare_tasks", "{\"task_refs\":[\"ref-a\",\"ref-b\"]}")) : answer("ev-a"));

        new LogAnalysisAgent(client).run(messages(payload));

        JsonNode result = toolResults(client.messages.get(1)).get(0);
        assertTrue(result.path("truncated").asBoolean()); assertEquals(2, result.path("evidence").size());
        assertEquals("TASK_ONLY", result.path("tasks").get(0).path("association_scope").asText());
        assertTrue(byteSize(result) <= 12 * 1024);
    }

    @Test void rejectsBadToolParametersAndDuplicateCallIds() throws Exception {
        for (String arguments : List.of("{\"task_refs\":[\"ref-a\"]}", "{\"task_refs\":[\"ref-a\",\"ref-a\"]}",
                "{\"task_refs\":[\"ref-a\",\"ref-b\"],\"query\":\"sensitive-provider-body\"}", "[]", "{} {}",
                "{\"task_refs\":[\"ref-a\",\"ref-b\"],\"task_refs\":[\"ref-a\",\"ref-b\"]}")) {
            Capturing client = new Capturing((round, messages, tools) -> round == 1 ? calls(call("c1", "compare_tasks", arguments)) : answer("ev-a"));
            assertSafe(assertThrows(IOException.class, () -> new LogAnalysisAgent(client).run(input())));
            assertEquals("INVALID_ARGUMENTS", toolResults(client.messages.get(1)).get(0).path("error").path("code").asText());
        }
        Capturing duplicate = new Capturing((round, messages, tools) -> calls(call("c1", "get_error_samples", "{}"), call("c1", "get_error_samples", "{}")));
        assertSafe(assertThrows(IOException.class, () -> new LogAnalysisAgent(duplicate).run(input())));
    }

    @Test void rejectsUnapprovedInputFieldsAndConflictingEvidenceBeforeAnyModelCall() throws Exception {
        List<ArrayNode> invalid = new ArrayList<>();
        ObjectNode raw = (ObjectNode) payload(input()); raw.withArray("evidence").get(0).withObject("/payload").put("prompt", "private-prompt"); invalid.add(messages(raw));
        ObjectNode cross = (ObjectNode) payload(input()); ((ObjectNode) cross.path("evidence").get(0)).put("task_ref", "foreign-batch"); invalid.add(messages(cross));
        ObjectNode conflict = (ObjectNode) payload(input()); conflict.withArray("evidence").add(((ObjectNode) conflict.path("evidence").get(0)).deepCopy().put("error_summary", "conflicting")); invalid.add(messages(conflict));
        ObjectNode duplicate = (ObjectNode) payload(input()); duplicate.withArray("tasks").add(duplicate.path("tasks").get(0).deepCopy()); invalid.add(messages(duplicate));
        for (ArrayNode input : invalid) {
            Capturing client = new Capturing((round, messages, tools) -> answer("ev-a"));
            assertSafe(assertThrows(IOException.class, () -> new LogAnalysisAgent(client).run(input))); assertTrue(client.messages.isEmpty());
        }
    }

    @Test void finalJsonMustBeCompleteAndCannotRequestToolsInClosingRound() {
        for (LlmClient.Reply finalReply : List.of(answer("ev-a", "length"), textReply("sensitive-provider-body", "stop"),
                textReply("{\"findings\":[],\"findings\":[]}", "stop"),
                textReply("{\"findings\":[]} {}", "stop"), calls(call("late", "get_error_samples", "{}")))) {
            Capturing client = new Capturing((round, messages, tools) -> round == 1 ? calls(call("c1", "get_error_samples", "{}")) : finalReply);
            assertSafe(assertThrows(IOException.class, () -> new LogAnalysisAgent(client).run(input())));
        }
    }

    @Test void supplierFailureAndInterruptDoNotExposeProviderDataOrCause() {
        LlmClient failure = (messages, tools) -> { throw new IOException("sensitive-provider-body"); };
        assertSafe(assertThrows(IOException.class, () -> new LogAnalysisAgent(failure).run(input())));
        LlmClient runtime = (messages, tools) -> { throw new IllegalStateException("sensitive-provider-body"); };
        assertSafe(assertThrows(IOException.class, () -> new LogAnalysisAgent(runtime).run(input())));
        try {
            LlmClient interrupt = (messages, tools) -> { throw new InterruptedException("sensitive-provider-body"); };
            InterruptedException exception = assertThrows(InterruptedException.class, () -> new LogAnalysisAgent(interrupt).run(input()));
            assertTrue(Thread.currentThread().isInterrupted()); assertNull(exception.getCause()); assertFalse(exception.getMessage().contains("sensitive"));
        } finally { Thread.interrupted(); }
    }

    @Test void noToolEarlyAnswerGetsOneFixedCorrectionThenEvidenceCanBeRead() throws Exception {
        Capturing client = new Capturing((round, messages, tools) -> round == 2
                ? calls(call("c1", "read_evidence", "{\"evidence_id\":\"ev-a\"}")) : answer("ev-a"));

        LogAnalysisAgent.Result result = new LogAnalysisAgent(client).run(input());

        assertEquals(3, result.analysis().path("model_rounds").asInt()); assertEquals(1, result.analysis().path("tool_calls").asInt());
        assertTrue(client.messages.get(1).get(2).path("content").asText().contains("尚未成功读取任何证据"));
        Capturing refuses = new Capturing((round, messages, tools) -> answer("ev-a"));
        LogNarrativeFailure failure = assertThrows(LogNarrativeFailure.class, () -> new LogAnalysisAgent(refuses).run(input()));
        assertEquals("NARRATIVE_TOOL_NO_EVIDENCE", failure.code()); assertEquals(2, failure.diagnostic().path("model_rounds").asInt());
        assertEquals(0, failure.diagnostic().path("tool_calls").asInt());
    }

    @Test void badCitationGetsOneCorrectionListingOnlyEvidenceActuallyRead() throws Exception {
        Capturing client = new Capturing((round, messages, tools) -> round == 1
                ? calls(call("c1", "read_evidence", "{\"evidence_id\":\"ev-a\"}")) : answer(round == 2 ? "ev-b" : "ev-a"));

        LogAnalysisAgent.Result result = new LogAnalysisAgent(client).run(input());

        assertEquals(3, result.analysis().path("model_rounds").asInt()); assertTrue(client.tools.get(2).isEmpty());
        JsonNode correction = client.messages.get(2).get(client.messages.get(2).size() - 1);
        assertTrue(correction.path("content").asText().contains("ev-a")); assertFalse(correction.path("content").asText().contains("ev-b"));
        assertTrue(correction.path("content").asText().contains("目录中未读取的ID禁止引用"));
    }

    @Test void earlyFinalRequiresCompleteSchemaAndInvalidOutputFailsWithTypedTrace() throws Exception {
        Capturing repaired = new Capturing((round, messages, tools) -> round == 1 ? calls(call("c1", "get_error_samples", "{}"))
                : round == 2 ? textReply("{\"findings\":[]}", "stop") : answer("ev-a"));
        assertEquals(3, new LogAnalysisAgent(repaired).run(input()).analysis().path("model_rounds").asInt());
        Capturing invalid = new Capturing((round, messages, tools) -> round == 1
                ? calls(call("c1", "read_evidence", "{\"evidence_id\":\"ev-a\"}")) : answer("ev-b"));

        LogNarrativeFailure failure = assertThrows(LogNarrativeFailure.class, () -> new LogAnalysisAgent(invalid).run(input()));

        assertEquals("NARRATIVE_OUTPUT_CITATION", failure.code()); assertEquals("OUTPUT", failure.diagnostic().path("stage").asText());
        assertEquals(3, failure.diagnostic().path("model_rounds").asInt()); assertEquals(1, failure.diagnostic().path("tool_calls").asInt());
        assertEquals("read_evidence", failure.diagnostic().path("steps").get(0).path("tool").asText());
        assertFalse(failure.diagnostic().toString().contains("ev-b")); assertNull(failure.getCause());
    }

    @Test void providerTypedFailuresKeepAttemptedRoundAndSafePriorToolTrace() {
        for (LlmRequestFailure.Kind kind : LlmRequestFailure.Kind.values()) {
            Capturing client = new Capturing((round, messages, tools) -> {
                if (round == 1) return calls(call("c1", "read_evidence", "{\"evidence_id\":\"ev-a\"}"));
                throw kind == LlmRequestFailure.Kind.HTTP ? new LlmRequestFailure(kind, 429) : new LlmRequestFailure(kind);
            });
            LogNarrativeFailure failure = assertThrows(LogNarrativeFailure.class, () -> new LogAnalysisAgent(client).run(input()));
            assertEquals("NARRATIVE_" + switch (kind) {
                case HTTP -> "PROVIDER_HTTP_429"; case TIMEOUT -> "PROVIDER_TIMEOUT"; case NETWORK -> "PROVIDER_NETWORK";
                case RESPONSE_LIMIT, INVALID_RESPONSE -> "PROVIDER_RESPONSE";
            }, failure.code());
            assertEquals(2, failure.diagnostic().path("model_rounds").asInt()); assertEquals(1, failure.diagnostic().path("tool_calls").asInt());
            assertEquals(1, failure.diagnostic().path("steps").size()); assertNull(failure.getCause());
        }
        Capturing unknown = new Capturing((round, messages, tools) -> { throw new IOException("HTTP 429 sensitive-provider-body"); });
        LogNarrativeFailure failure = assertThrows(LogNarrativeFailure.class, () -> new LogAnalysisAgent(unknown).run(input()));
        assertEquals("NARRATIVE_GENERATION_FAILED", failure.code()); assertEquals(1, failure.diagnostic().path("model_rounds").asInt());
        assertFalse(failure.diagnostic().toString().contains("429"));
    }

    @Test void fiftyTasksAndSeventyEightSyntheticEventsCompleteWithinBudgets() throws Exception {
        ObjectNode payload = (ObjectNode) payload(input()); ArrayNode tasks = payload.putArray("tasks"), evidence = payload.putArray("evidence");
        for (int i = 0; i < 50; i++) {
            ObjectNode task = tasks.addObject().put("task_ref", "synthetic-ref-" + i).put("association_scope", "TASK_ONLY")
                    .put("signature", "INCOMPLETE").put("collection_status", i < 10 ? "COLLECTED" : "NO_MATCHES").put("collection_event_count", i < 10 ? 8 : 0);
            task.putArray("observed_signatures"); task.putArray("quality_flags").add("TASK_ATTEMPT_UNVERIFIED"); task.putArray("collection_limitations");
        }
        for (int i = 0; i < 78; i++) {
            ObjectNode item = evidence.addObject().put("evidence_id", "synthetic-ev-" + i).put("task_ref", "synthetic-ref-" + (i % 10))
                    .put("event", i < 10 ? "execution_error" : "task_progress").put("event_time", java.time.Instant.parse("2026-10-17T01:00:00Z").plusSeconds(i).toString());
            if (i < 10) item.put("failure_signature", "HTTP_404").put("error_summary", "人工样本资源 resource-" + i + " 返回404");
        }
        Capturing client = new Capturing((round, messages, tools) -> round == 1 ? calls(call("c1", "get_error_samples", "{}")) : answer("synthetic-ev-0"));

        LogAnalysisAgent.Result result = new LogAnalysisAgent(client).run(messages(payload));

        assertEquals(2, result.analysis().path("model_rounds").asInt()); assertEquals(8, result.analysis().path("steps").get(0).path("evidence_count").asInt());
        for (int i = 0; i < client.messages.size(); i++) assertTrue(byteSize(client.messages.get(i)) + byteSize(client.tools.get(i)) <= 48 * 1024);
    }

    private static ArrayNode input() {
        ObjectNode payload = Json.MAPPER.createObjectNode().put("input_truncated", false);
        ArrayNode tasks = payload.putArray("tasks");
        for (String ref : List.of("ref-a", "ref-b")) {
            ObjectNode task = tasks.addObject().put("task_ref", ref).put("association_scope", "TASK_ONLY").put("signature", "INCOMPLETE")
                    .put("collection_status", "COLLECTED").put("collection_event_count", 2);
            task.putArray("observed_signatures").add("HTTP_404"); task.putArray("quality_flags").add("TASK_ATTEMPT_UNVERIFIED"); task.putArray("collection_limitations");
        }
        ArrayNode evidence = payload.putArray("evidence");
        evidence.addObject().put("evidence_id", "ev-a").put("task_ref", "ref-a").put("event", "execution_error")
                .put("event_time", "2026-10-17T01:00:02Z").put("failure_signature", "HTTP_404").put("node_type", "ImageLoader").put("error_summary", "资源 resource-1 返回404");
        evidence.addObject().put("evidence_id", "ev-b").put("task_ref", "ref-b").put("event", "execution_error")
                .put("event_time", "2026-10-17T01:00:03Z").put("failure_signature", "HTTP_404").put("exception_message", "另一资源 resource-2 返回404");
        payload.putArray("quality_flags").add("TASK_ATTEMPT_UNVERIFIED"); payload.putArray("limitations");
        return messages(payload);
    }

    private static ArrayNode messages(ObjectNode payload) {
        ArrayNode messages = Json.MAPPER.createArrayNode(); messages.addObject().put("role", "system").put("content", "请用中文解释。TASK_ONLY仅表示候选，输出约定JSON。");
        messages.addObject().put("role", "user").put("content", payload.toString()); return messages;
    }
    private static JsonNode payload(ArrayNode messages) throws IOException { return Json.MAPPER.readTree(messages.get(1).path("content").asText()); }
    private static ObjectNode call(String id, String name, String arguments) {
        ObjectNode call = Json.MAPPER.createObjectNode().put("id", id).put("type", "function"); call.putObject("function").put("name", name).put("arguments", arguments); return call;
    }
    private static LlmClient.Reply calls(ObjectNode... calls) {
        ObjectNode assistant = Json.MAPPER.createObjectNode().put("role", "assistant"); assistant.putNull("content");
        ArrayNode list = assistant.putArray("tool_calls"); for (ObjectNode call : calls) list.add(call); return new LlmClient.Reply(assistant, "tool_calls");
    }
    private static LlmClient.Reply answer(String id) { return answer(id, "stop"); }
    private static LlmClient.Reply answer(String id, String finish) {
        ObjectNode body = Json.MAPPER.createObjectNode().put("summary", "候选资源读取错误，需工程师核实。");
        body.putArray("findings").addObject().put("title", "资源读取失败").put("explanation", "代表证据显示错误；最终执行归属仍未知。").putArray("evidence_ids").add(id);
        body.putArray("recommendations").add("核实资源可用性。"); body.putArray("limitations").add("代表事件并非完整执行历史。");
        return textReply(body.toString(), finish);
    }
    private static LlmClient.Reply textReply(String content, String finish) { return new LlmClient.Reply(Json.MAPPER.createObjectNode().put("role", "assistant").put("content", content), finish); }
    private static List<JsonNode> toolResults(ArrayNode messages) throws IOException {
        List<JsonNode> result = new ArrayList<>(); for (JsonNode message : messages) if (message.path("role").asText().equals("tool")) result.add(Json.MAPPER.readTree(message.path("content").asText())); return result;
    }
    private static void assertPairs(ArrayNode messages) {
        List<String> calls = new ArrayList<>(), results = new ArrayList<>();
        for (JsonNode message : messages) {
            for (JsonNode call : message.path("tool_calls")) calls.add(call.path("id").asText());
            if (message.path("role").asText().equals("tool")) results.add(message.path("tool_call_id").asText());
        }
        assertEquals(calls, results);
    }
    private static List<String> names(ArrayNode tools) { List<String> result = new ArrayList<>(); for (JsonNode tool : tools) result.add(tool.path("function").path("name").asText()); return result; }
    private static int byteSize(JsonNode value) { return value.toString().getBytes(StandardCharsets.UTF_8).length; }
    private static void assertSafe(IOException error) {
        assertNull(error.getCause()); assertInstanceOf(LogNarrativeFailure.class, error);
        assertTrue(((LogNarrativeFailure) error).code().startsWith("NARRATIVE_"));
        assertFalse(error.getMessage().contains("sensitive-provider-body"));
    }
    @FunctionalInterface private interface Handler { LlmClient.Reply reply(int round, ArrayNode messages, ArrayNode tools) throws IOException, InterruptedException; }
    private static final class Capturing implements LlmClient {
        final List<ArrayNode> messages = new ArrayList<>(), tools = new ArrayList<>(); private final Handler handler;
        Capturing(Handler handler) { this.handler = handler; }
        @Override public Reply complete(ArrayNode messages, ArrayNode tools) throws IOException, InterruptedException {
            this.messages.add(messages.deepCopy()); this.tools.add(tools.deepCopy()); return handler.reply(this.messages.size(), messages, tools);
        }
    }
}
