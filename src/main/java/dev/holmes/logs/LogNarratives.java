package dev.holmes.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import dev.holmes.AppConfig;
import dev.holmes.Json;
import dev.holmes.LlmClient;
import dev.holmes.OpenAiClient;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** 日志解释独立于工具问答初始化；只有明确请求时才会调用模型。 */
public final class LogNarratives {
    private LogNarratives() { }

    public static LogNarrativeGenerator from(Map<String, String> env, boolean demo) {
        if (demo) return new LogNarrativeGenerator(LogNarratives::demoReply,
                "demo-log-narrative", "本机演示（无网络请求）", true);
        if (env.getOrDefault("LLM_API_KEY", "").isBlank()) return null;
        Map<String, String> modelEnv = new HashMap<>(env);
        // 网页随机端口不参与模型请求，避免 AppConfig 的服务端口检查影响测试入口。
        if ("0".equals(modelEnv.get("HOLMES_PORT"))) modelEnv.put("HOLMES_PORT", "8080");
        AppConfig config = AppConfig.from(modelEnv);
        return new LogNarrativeGenerator(new OpenAiClient(config), config.model(), config.baseUrl().toString(), true);
    }

    private static LlmClient.Reply demoReply(ArrayNode messages, ArrayNode tools) throws IOException {
        JsonNode input;
        JsonNode material = messages.get(messages.size() - 1);
        if (tools.isEmpty()) {
            // 独立收束轮末尾是系统收束提示；演示结论仍只引用之前实际读取的工具结果。
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i).path("role").asText().equals("tool")) { material = messages.get(i); break; }
            }
        }
        try { input = Json.MAPPER.readTree(material.path("content").asText()); }
        catch (RuntimeException e) { throw new IOException("演示证据包无效"); }
        String evidenceId = firstEvidenceId(input);
        if (evidenceId == null) throw new IOException("演示证据包缺少引用");
        if (messages.get(messages.size() - 1).path("role").asText().equals("user") && !tools.isEmpty()) {
            var message = Json.MAPPER.createObjectNode().put("role", "assistant").putNull("content");
            message.putArray("tool_calls").addObject().put("id", "demo-log-evidence").put("type", "function")
                    .putObject("function").put("name", "read_evidence").put("arguments",
                            Json.MAPPER.createObjectNode().put("evidence_id", evidenceId).toString());
            return new LlmClient.Reply(message, "tool_calls");
        }
        var result = Json.MAPPER.createObjectNode()
                .put("summary", "这是本机合成样本的演示解释：请结合规则签名和证据引用检查已观察现象，最终执行归属以报告为准。");
        var finding = result.putArray("findings").addObject()
                .put("title", "代表证据已进入解释链路")
                .put("explanation", "本条仅演示证据引用与展示；不会将候选日志提升为最终执行根因。");
        finding.putArray("evidence_ids").add(evidenceId);
        result.putArray("recommendations").add("打开引用证据，并核对执行时间窗、Prompt 映射和重试记录。");
        result.putArray("limitations").add("演示模式使用固定文本，没有调用外部模型，也不验证生产环境根因。");
        return new LlmClient.Reply(Json.MAPPER.createObjectNode().put("role", "assistant")
                .put("content", result.toString()), "stop");
    }

    private static String firstEvidenceId(JsonNode node) {
        if (node == null) return null;
        if (node.isObject() && node.path("evidence_id").isTextual()) return node.path("evidence_id").asText();
        for (JsonNode child : node) {
            String found = firstEvidenceId(child);
            if (found != null) return found;
        }
        return null;
    }
}
