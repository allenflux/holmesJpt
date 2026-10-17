package dev.holmes.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.holmes.Json;
import dev.holmes.LlmClient;
import dev.holmes.LlmRequestFailure;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LogNarrativeGeneratorTest {
    private static final String TASK_ID = "6c1c7395-f938-45e6-987b-09004099fa12";

    @Test void sendsOnlyWhitelistedAssociatedEvidenceAndPreservesChineseTaskOnlyBoundary() throws Exception {
        ObjectNode report = report();
        report.put("private_request", "raw-request-never-send");
        ((ObjectNode) report.path("tasks").get(0)).put("workflow", "raw-workflow-never-send");
        ((ObjectNode) report.path("evidence").get(0)).put("excerpt", "raw-payload-never-send");
        Capturing client = new Capturing(messages -> valid(messages));
        ObjectNode before = report.deepCopy();

        ObjectNode result = generator(client).generate(report);

        assertEquals(1, client.calls);
        assertTrue(client.tools.isEmpty());
        assertEquals(2, client.messages.size());
        String system = client.messages.get(0).path("content").asText();
        assertTrue(system.contains("简体中文"));
        assertTrue(system.contains("不可信数据"));
        assertTrue(system.contains("TASK_ONLY"));
        assertTrue(system.contains("不得修改"));
        JsonNode input = input(client.messages);
        assertEquals("TASK_ONLY", input.path("tasks").get(0).path("association_scope").asText());
        assertEquals("ev_1", input.path("evidence").get(0).path("evidence_id").asText());
        assertFalse(client.messages.toString().contains(TASK_ID));
        for (String excluded : List.of("raw-request-never-send", "raw-workflow-never-send", "raw-payload-never-send"))
            assertFalse(client.messages.toString().contains(excluded));
        assertEquals(before, report);
        assertEquals("synthetic-model", result.path("model").asText());
        assertFalse(result.path("generated_at").asText().isBlank());
        assertFalse(result.has("tasks"));
        assertFalse(result.has("clusters"));
        assertEquals("ev_1", result.path("findings").get(0).path("evidence_ids").get(0).asText());
        assertFalse(result.path("input_truncated").asBoolean());
    }

    @Test void redactsCredentialsAndKeepsSameAddressStableWithoutMergingDifferentPaths() throws Exception {
        ObjectNode report = report();
        ObjectNode evidence = (ObjectNode) report.path("evidence").get(0);
        evidence.put("pod", "private-worker-pod").put("observed_instance_id", "private-instance-7");
        evidence.put("error_summary", "task " + TASK_ID + " private-worker-pod private-instance-7 "
                + "Authorization: Bearer synthetic-input-secret API Key: synthetic-api-secret "
                + "https://private.example.test/a?token=synthetic-url-secret "
                + "https://private.example.test:443/b https://private.example.test:443/a?token=synthetic-url-secret https://other.example.test/c");
        Capturing client = new Capturing(messages -> {
            ObjectNode answer = answerBody(messages);
            answer.put("summary", "候选结果 " + TASK_ID + " private-worker-pod private-instance-7 "
                    + "https://private.example.test/a?token=synthetic-url-secret token=synthetic-output-secret");
            return reply(answer.toString(), "stop");
        });

        ObjectNode result = generator(client).generate(report);

        String sent = input(client.messages).path("evidence").get(0).path("error_summary").asText();
        assertEquals(2, occurrences(sent, "resource-1"));
        assertTrue(sent.contains("resource-2"));
        assertTrue(sent.contains("resource-3"));
        String all = client.messages + result.toString();
        for (String secret : List.of(TASK_ID, "private-worker-pod", "private-instance-7", "synthetic-input-secret", "synthetic-api-secret",
                "synthetic-url-secret", "synthetic-output-secret", "private.example.test", "other.example.test")) assertFalse(all.contains(secret), secret);
        assertTrue(result.path("summary").asText().contains("resource-1"));
    }

    @Test void boundedInputPrioritizesErrorEvidenceAndMarksDroppedSourceData() throws Exception {
        ObjectNode report = report();
        ArrayNode evidence = (ArrayNode) report.path("evidence"); evidence.removeAll();
        ArrayNode ids = (ArrayNode) report.path("tasks").get(0).path("evidence_ids"); ids.removeAll();
        for (int i = 0; i < 220; i++) {
            String id = "ev_normal_" + i; ids.add(id);
            evidence.addObject().put("evidence_id", id).put("task_ref", "ref-a").put("event", "task_started")
                    .put("exception_message", "普通上下文".repeat(180));
        }
        ids.add("ev_actual_error");
        evidence.addObject().put("evidence_id", "ev_actual_error").put("task_ref", "ref-a").put("event", "execution_error")
                .put("failure_signature", "EXECUTION_ERROR").put("error_summary", "已观察失败事件，异常细节未知。");
        report.put("retrieved_event_count", 221);
        Capturing client = new Capturing(messages -> valid(messages));

        ObjectNode result = generator(client).generate(report);

        assertTrue(Json.MAPPER.writeValueAsBytes(client.messages).length <= 48 * 1024);
        JsonNode input = input(client.messages);
        assertEquals("ev_actual_error", input.path("evidence").get(0).path("evidence_id").asText());
        assertTrue(input.path("evidence").size() < 221);
        assertTrue(input.path("input_truncated").asBoolean());
        assertTrue(result.path("input_truncated").asBoolean());
        assertTrue(result.path("limitations").toString().contains("输入已按预算裁剪"));
    }

    @Test void referencesMustBelongToEvidenceActuallySentNotMerelyToOriginalReport() throws Exception {
        ObjectNode report = report();
        ((ArrayNode) report.path("tasks").get(0).path("evidence_ids")).add("ev_unlinked");
        report.withArray("evidence").addObject().put("evidence_id", "ev_unlinked").put("task_ref", "other-ref")
                .put("event", "execution_error").put("error_summary", "不得发送的未关联证据");
        Capturing client = new Capturing(messages -> {
            ObjectNode body = answerBody(messages);
            ((ArrayNode) body.path("findings").get(0).path("evidence_ids")).removeAll().add("ev_unlinked");
            return reply(body.toString(), "stop");
        });

        IOException error = assertThrows(IOException.class, () -> generator(client).generate(report));

        assertEquals("NARRATIVE_OUTPUT_CITATION", ((LogNarrativeFailure) error).code());
        assertFalse(input(client.messages).toString().contains("ev_unlinked"));
    }

    @Test void nonemptyFindingsRequireAtLeastOneVerifiableEvidenceReference() {
        Capturing client = new Capturing(messages -> {
            ObjectNode body = answerBody(messages);
            ((ArrayNode) body.path("findings").get(0).path("evidence_ids")).removeAll();
            return reply(body.toString(), "stop");
        });
        IOException error = assertThrows(IOException.class, () -> generator(client).generate(report()));
        assertEquals("NARRATIVE_OUTPUT_SCHEMA", ((LogNarrativeFailure) error).code());
    }

    @Test void acceptsOnlyCompleteJsonFenceAndAllowsEmptyFindingArrays() throws Exception {
        Capturing client = new Capturing(messages -> reply("```json\n{\"summary\":\"证据有限。\",\"findings\":[],\"recommendations\":[],\"limitations\":[]}\n```", "stop"));

        ObjectNode result = generator(client).generate(report());

        assertEquals("证据有限。", result.path("summary").asText());
        assertTrue(result.path("findings").isEmpty());
    }

    @Test void rejectsToolsLegacyFunctionCallsEmptyAndTruncatedResponses() {
        List<LlmClient.Reply> invalid = new ArrayList<>();
        ObjectNode tool = Json.MAPPER.createObjectNode().put("role", "assistant").put("content", "供应商敏感正文");
        tool.putArray("tool_calls").addObject().put("id", "unsafe-call");
        invalid.add(new LlmClient.Reply(tool, "stop"));
        ObjectNode function = Json.MAPPER.createObjectNode().put("role", "assistant").put("content", "供应商敏感正文");
        function.putObject("function_call").put("name", "unsafe"); invalid.add(new LlmClient.Reply(function, "stop"));
        invalid.add(reply("", "stop")); invalid.add(reply("供应商敏感正文", "length"));
        invalid.add(reply("供应商敏感正文", "content_filter")); invalid.add(reply("供应商敏感正文", null));
        for (LlmClient.Reply response : invalid) {
            Capturing client = new Capturing(messages -> response);
            IOException error = assertThrows(IOException.class, () -> generator(client).generate(report()));
            assertFalse(error.getMessage().contains("供应商敏感正文")); assertNull(error.getCause()); assertEquals(1, client.calls);
        }
    }

    @Test void rejectsInvalidJsonUnknownFieldsDuplicatesAndTrailingJsonWithoutEchoingContent() {
        for (String response : List.of("provider-private-body", "{\"summary\":\"provider-private-body\"}",
                "```json\n{\"summary\":\"provider-private-body\"}",
                "{\"summary\":\"a\",\"summary\":\"provider-private-body\",\"findings\":[],\"recommendations\":[],\"limitations\":[]}",
                "{\"summary\":\"a\",\"findings\":[],\"recommendations\":[],\"limitations\":[],\"tasks\":[]}",
                "{\"summary\":\"a\",\"findings\":[],\"recommendations\":[],\"limitations\":[]} {}")) {
            Capturing client = new Capturing(messages -> reply(response, "stop"));
            IOException error = assertThrows(IOException.class, () -> generator(client).generate(report()));
            assertFalse(error.getMessage().contains("provider-private-body")); assertNull(error.getCause());
        }
    }

    @Test void boundsReturnedNarrativeEvenWhenValidModelOutputIsVeryLong() throws Exception {
        Capturing client = new Capturing(messages -> {
            ObjectNode body = answerBody(messages); body.put("summary", "中文说明".repeat(1_000));
            ArrayNode findings = (ArrayNode) body.path("findings"); findings.removeAll();
            for (int i = 0; i < 14; i++) {
                ObjectNode finding = findings.addObject().put("title", "候选现象 " + i).put("explanation", "详细解释".repeat(200));
                finding.putArray("evidence_ids").add("ev_1");
            }
            body.withArray("recommendations").add("建议".repeat(500));
            return reply(body.toString(), "stop");
        });

        ObjectNode result = generator(client).generate(report());

        assertTrue(result.toString().length() <= 12_000);
        assertTrue(result.path("limitations").toString().contains("已裁剪"));
        assertFalse(result.path("input_truncated").asBoolean());
        for (JsonNode finding : result.path("findings")) assertEquals("ev_1", finding.path("evidence_ids").get(0).asText());
    }

    @Test void controlCharactersCannotEscapeTheSerializedOutputBudget() throws Exception {
        Capturing client = new Capturing(messages -> {
            ObjectNode body = answerBody(messages); body.put("summary", "\u0001".repeat(3_000) + "解释");
            return reply(body.toString(), "stop");
        });
        assertTrue(generator(client).generate(report()).toString().length() <= 12_000);
    }

    @Test void zeroEventsUnlinkedOrRawOnlyEvidenceNeverCallsTheModel() {
        List<ObjectNode> invalid = new ArrayList<>();
        invalid.add(report().put("retrieved_event_count", 0));
        ObjectNode missing = report(); missing.withArray("evidence").removeAll(); invalid.add(missing);
        ObjectNode unlinked = report(); ((ArrayNode) unlinked.path("tasks").get(0).path("evidence_ids")).removeAll(); invalid.add(unlinked);
        ObjectNode rawOnly = report(); ObjectNode evidence = (ObjectNode) rawOnly.path("evidence").get(0);
        evidence.remove(List.of("event", "failure_signature", "error_summary")); evidence.put("excerpt", "raw payload"); invalid.add(rawOnly);
        for (ObjectNode report : invalid) {
            Capturing client = new Capturing(messages -> valid(messages));
            assertFalse(LogNarrativeGenerator.hasEvidence(report));
            assertThrows(IOException.class, () -> generator(client).generate(report));
            assertEquals(0, client.calls);
        }
        assertFalse(LogNarrativeGenerator.hasEvidence(null));
        assertTrue(LogNarrativeGenerator.hasEvidence(report()));
    }

    @Test void supplierFailuresDoNotExposeOriginalErrorOrCause() {
        Capturing client = new Capturing(messages -> { throw new IOException("provider-private-body token=synthetic-secret"); });

        IOException failure = assertThrows(IOException.class, () -> generator(client).generate(report()));

        assertEquals("NARRATIVE_GENERATION_FAILED", ((LogNarrativeFailure) failure).code()); assertNull(failure.getCause()); assertEquals(1, client.calls);
        assertEquals("synthetic-model", generator(client).model());
        assertEquals("https://model.example.test/v1", generator(client).destination());
    }

    @Test void typedProviderAndUpstreamNarrativeFailuresKeepTheirSafeDiagnostics() {
        Capturing provider = new Capturing(messages -> { throw new LlmRequestFailure(LlmRequestFailure.Kind.HTTP, 500); });
        LogNarrativeFailure failure = assertThrows(LogNarrativeFailure.class, () -> generator(provider).generate(report()));
        assertEquals("NARRATIVE_PROVIDER_HTTP_500", failure.code()); assertEquals("PROVIDER", failure.diagnostic().path("stage").asText());
        assertEquals(1, failure.diagnostic().path("model_rounds").asInt()); assertEquals(0, failure.diagnostic().path("tool_calls").asInt());
        assertNull(failure.getCause());
        ArrayNode steps = Json.MAPPER.createArrayNode(); steps.addObject().put("tool", "read_evidence").put("status", "OK").put("evidence_count", 1);
        LogNarrativeFailure upstream = new LogNarrativeFailure("NARRATIVE_OUTPUT_CITATION", 3, 1, steps);
        Capturing typed = new Capturing(messages -> { throw upstream; });
        LogNarrativeFailure preserved = assertThrows(LogNarrativeFailure.class, () -> generator(typed).generate(report()));
        assertEquals(upstream.diagnostic(), preserved.diagnostic());
    }

    @Test void inputAndOutputFailuresExposeOnlyFixedStageCodesAndKnownCallCounts() {
        Capturing unused = new Capturing(messages -> valid(messages));
        LogNarrativeFailure input = assertThrows(LogNarrativeFailure.class, () -> generator(unused).generate(report().put("retrieved_event_count", 0)));
        assertEquals("NARRATIVE_INPUT_INVALID", input.code()); assertEquals("INPUT", input.diagnostic().path("stage").asText());
        assertEquals(0, input.diagnostic().path("model_rounds").asInt()); assertEquals(0, unused.calls);
        for (String code : List.of("NARRATIVE_OUTPUT_JSON", "NARRATIVE_OUTPUT_SCHEMA", "NARRATIVE_OUTPUT_CITATION", "NARRATIVE_OUTPUT_INCOMPLETE")) {
            Capturing client = new Capturing(messages -> switch (code) {
                case "NARRATIVE_OUTPUT_JSON" -> reply("provider-private-body", "stop");
                case "NARRATIVE_OUTPUT_SCHEMA" -> reply("{\"summary\":\"provider-private-body\"}", "stop");
                case "NARRATIVE_OUTPUT_CITATION" -> {
                    ObjectNode body = answerBody(messages); ((ArrayNode) body.path("findings").get(0).path("evidence_ids")).removeAll().add("not-sent"); yield reply(body.toString(), "stop");
                }
                default -> reply("provider-private-body", "length");
            });
            LogNarrativeFailure failure = assertThrows(LogNarrativeFailure.class, () -> generator(client).generate(report()));
            assertEquals(code, failure.code()); assertEquals("OUTPUT", failure.diagnostic().path("stage").asText());
            assertEquals(1, failure.diagnostic().path("model_rounds").asInt()); assertNull(failure.getCause());
            assertFalse(failure.diagnostic().toString().contains("provider-private-body"));
        }
    }

    @Test void toolDrivenGeneratorAcceptsFiftyTasksAndSeventyEightSyntheticAssociatedEvents() throws Exception {
        ObjectNode report = Json.MAPPER.createObjectNode().put("retrieved_event_count", 259);
        ArrayNode tasks = report.putArray("tasks"), evidence = report.putArray("evidence");
        for (int i = 0; i < 50; i++) {
            ObjectNode task = tasks.addObject().put("task_ref", "synthetic-ref-" + i).put("association_scope", "TASK_ONLY")
                    .put("signature", "INCOMPLETE").put("collection_status", i < 10 ? "COLLECTED" : "NO_MATCHES");
            task.putArray("evidence_ids"); task.putArray("quality_flags").add("TASK_ATTEMPT_UNVERIFIED"); task.putArray("observed_signatures");
        }
        for (int i = 0; i < 78; i++) {
            String id = "synthetic-ev-" + i;
            ((ObjectNode) tasks.get(i % 10)).withArray("evidence_ids").add(id);
            ObjectNode item = evidence.addObject().put("evidence_id", id).put("task_ref", "synthetic-ref-" + (i % 10))
                    .put("event", i < 10 ? "execution_error" : "task_progress");
            if (i < 10) item.put("failure_signature", "HTTP_404").put("error_summary", "人工示例资源 resource-" + i + " 返回404。");
        }
        int[] calls = {0};
        LlmClient client = (messages, tools) -> {
            calls[0]++;
            if (calls[0] == 1) {
                ObjectNode assistant = Json.MAPPER.createObjectNode().put("role", "assistant"); assistant.putNull("content");
                assistant.putArray("tool_calls").addObject().put("id", "synthetic-call-1").put("type", "function")
                        .putObject("function").put("name", "get_error_samples").put("arguments", "{}");
                return new LlmClient.Reply(assistant, "tool_calls");
            }
            ObjectNode body = Json.MAPPER.createObjectNode().put("summary", "人工样本候选错误。");
            body.putArray("findings").addObject().put("title", "候选错误").put("explanation", "仅为人工测试，不能判断最终归属。")
                    .putArray("evidence_ids").add("synthetic-ev-0"); body.putArray("recommendations"); body.putArray("limitations").add("代表事件。");
            return reply(body.toString(), "stop");
        };

        ObjectNode result = new LogNarrativeGenerator(client, "synthetic-model", "synthetic-destination", true).generate(report);

        assertEquals(2, calls[0]); assertEquals(2, result.path("analysis").path("model_rounds").asInt());
        assertEquals(1, result.path("analysis").path("tool_calls").asInt()); assertEquals(50, report.path("tasks").size()); assertEquals(78, report.path("evidence").size());
    }

    private static LogNarrativeGenerator generator(LlmClient client) { return new LogNarrativeGenerator(client, "synthetic-model", "https://model.example.test/v1"); }

    private static ObjectNode report() {
        ObjectNode report = Json.MAPPER.createObjectNode().put("retrieved_event_count", 1);
        ObjectNode task = report.putArray("tasks").addObject().put("task_ref", "ref-a").put("task_id", TASK_ID)
                .put("association_scope", "TASK_ONLY").put("signature", "INCOMPLETE").put("collection_status", "COLLECTED").put("collection_event_count", 1);
        task.putArray("evidence_ids").add("ev_1"); task.putArray("quality_flags").add("TASK_ATTEMPT_UNVERIFIED");
        task.putArray("observed_signatures").add("EXECUTION_ERROR");
        report.putArray("evidence").addObject().put("evidence_id", "ev_1").put("task_ref", "ref-a")
                .put("event", "execution_error").put("event_time", "2026-10-17T01:00:00Z")
                .put("failure_signature", "EXECUTION_ERROR").put("error_summary", "已观察失败事件，异常细节未知。");
        return report;
    }

    private static JsonNode input(ArrayNode messages) throws IOException { return Json.MAPPER.readTree(messages.get(1).path("content").asText()); }
    private static ObjectNode answerBody(ArrayNode messages) throws IOException {
        String id = input(messages).path("evidence").get(0).path("evidence_id").asText();
        ObjectNode body = Json.MAPPER.createObjectNode().put("summary", "已根据有限证据整理候选解释。");
        body.putArray("findings").addObject().put("title", "候选执行失败事件").put("explanation", "可观察失败事件，尚不能认定最终执行根因。")
                .putArray("evidence_ids").add(id);
        body.putArray("recommendations").add("核实执行记录与重试归属。");
        body.putArray("limitations").add("TASK_ONLY 仅提供候选证据。");
        return body;
    }
    private static LlmClient.Reply valid(ArrayNode messages) throws IOException { return reply(answerBody(messages).toString(), "stop"); }
    private static LlmClient.Reply reply(String text, String finish) { return new LlmClient.Reply(Json.MAPPER.createObjectNode().put("role", "assistant").put("content", text), finish); }
    private static int occurrences(String text, String value) { return (text.length() - text.replace(value, "").length()) / value.length(); }

    @FunctionalInterface private interface Response { LlmClient.Reply answer(ArrayNode messages) throws IOException; }
    private static final class Capturing implements LlmClient {
        private final Response response; private int calls; private ArrayNode messages; private ArrayNode tools;
        private Capturing(Response response) { this.response = response; }
        @Override public Reply complete(ArrayNode messages, ArrayNode tools) throws IOException {
            calls++; this.messages = messages.deepCopy(); this.tools = tools.deepCopy(); return response.answer(messages);
        }
    }
}
