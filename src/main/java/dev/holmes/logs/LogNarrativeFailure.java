package dev.holmes.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.holmes.Json;
import dev.holmes.LlmRequestFailure;

import java.io.IOException;
import java.util.Set;

/** 只携带固定错误码与有界工具轨迹，不保留供应商原文、参数或异常原因。 */
public final class LogNarrativeFailure extends IOException {
    private static final Set<String> CODES = Set.of("INPUT_INVALID", "INPUT_BUDGET", "PROVIDER_TIMEOUT", "PROVIDER_NETWORK", "PROVIDER_RESPONSE",
            "TOOL_PROTOCOL", "TOOL_NO_EVIDENCE", "TOOL_CONTEXT_LIMIT", "TOOL_BUDGET", "OUTPUT_JSON", "OUTPUT_SCHEMA", "OUTPUT_CITATION", "OUTPUT_INCOMPLETE", "GENERATION_FAILED");
    private static final Set<String> TOOLS = Set.of("get_error_samples", "read_task_timeline", "read_evidence", "compare_tasks");
    private static final Set<String> STATUSES = Set.of("OK", "REJECTED", "TRUNCATED");
    private final String code;
    private final ObjectNode diagnostic;

    public LogNarrativeFailure(String code) { this(code, -1, -1, null); }

    public LogNarrativeFailure(String code, int modelRounds, int toolCalls, JsonNode steps) {
        super("日志模型解释失败：" + safeCode(code), null);
        this.code = safeCode(code);
        String suffix = this.code.substring("NARRATIVE_".length());
        String stage = suffix.startsWith("INPUT_") ? "INPUT" : suffix.startsWith("PROVIDER_") ? "PROVIDER"
                : suffix.startsWith("TOOL_") ? "TOOLS" : suffix.startsWith("OUTPUT_") ? "OUTPUT" : "UNKNOWN";
        diagnostic = Json.MAPPER.createObjectNode().put("stage", stage).put("code", this.code);
        // 未掌握的计数使用 null；真正尚未调用模型时由调用方明确传入 0。
        if (modelRounds < 0) diagnostic.putNull("model_rounds"); else diagnostic.put("model_rounds", Math.clamp(modelRounds, 0, 4));
        if (toolCalls < 0) diagnostic.putNull("tool_calls"); else diagnostic.put("tool_calls", Math.clamp(toolCalls, 0, 6));
        ArrayNode safeSteps = diagnostic.putArray("steps");
        if (steps != null && steps.isArray()) for (JsonNode step : steps) {
            if (safeSteps.size() == 6) break;
            String tool = step.path("tool").asText(""), status = step.path("status").asText("");
            safeSteps.addObject().put("tool", TOOLS.contains(tool) ? tool : "UNKNOWN").put("status", STATUSES.contains(status) ? status : "REJECTED")
                    .put("evidence_count", step.path("evidence_count").isIntegralNumber() ? Math.clamp(step.path("evidence_count").asLong(), 0, 128) : 0);
        }
    }

    public String code() { return code; }
    public ObjectNode diagnostic() { return diagnostic.deepCopy(); }

    LogNarrativeFailure withTrace(ObjectNode trace) {
        return new LogNarrativeFailure(code, trace.path("model_rounds").isIntegralNumber() ? trace.path("model_rounds").asInt() : -1,
                trace.path("tool_calls").isIntegralNumber() ? trace.path("tool_calls").asInt() : -1, trace.path("steps"));
    }

    static LogNarrativeFailure provider(LlmRequestFailure failure) {
        return new LogNarrativeFailure("NARRATIVE_" + switch (failure.kind()) {
            case HTTP -> "PROVIDER_HTTP_" + failure.httpStatus();
            case TIMEOUT -> "PROVIDER_TIMEOUT";
            case NETWORK -> "PROVIDER_NETWORK";
            case RESPONSE_LIMIT, INVALID_RESPONSE -> "PROVIDER_RESPONSE";
        });
    }

    private static String safeCode(String value) {
        if (value != null && value.startsWith("NARRATIVE_")) {
            String suffix = value.substring("NARRATIVE_".length());
            if (CODES.contains(suffix) || suffix.matches("PROVIDER_HTTP_[1-5][0-9]{2}")) return value;
        }
        return "NARRATIVE_GENERATION_FAILED";
    }
}
