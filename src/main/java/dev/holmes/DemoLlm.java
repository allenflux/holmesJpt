package dev.holmes;

import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;

/** 离线演示只模拟模型决策，文件读取仍经过真实工具执行。 */
public final class DemoLlm implements LlmClient {
    @Override public Reply complete(ArrayNode messages, ArrayNode tools) throws IOException {
        int results = 0;
        for (var message : messages) if ("tool".equals(message.path("role").asText())) results++;
        var assistant = Json.MAPPER.createObjectNode().put("role", "assistant");
        if (results < 2 && !tools.isEmpty()) {
            var call = assistant.putArray("tool_calls").addObject();
            call.put("id", "demo-" + results).put("type", "function");
            call.putObject("function").put("name", results == 0 ? "list_files" : "read_file")
                    .put("arguments", results == 0 ? "{}" : "{\"path\":\"pod-events.log\"}");
            return new Reply(assistant, "tool_calls");
        }
        StringBuilder evidence = new StringBuilder();
        for (var message : messages) {
            if ("tool".equals(message.path("role").asText())) {
                var result = Json.MAPPER.readTree(message.path("content").asText());
                if (result.path("success").asBoolean()) evidence.append(result.path("output").asText()).append('\n');
            }
        }
        String answer = evidence.toString().contains("OOMKilled")
                ? """
                  【离线演示：模型决策由脚本模拟】
                  现象：示例 Pod 发生重启。
                  证据：read_file 读取 pod-events.log，发现 OOMKilled 事件。
                  可能根因：容器内存使用超过限制；仍需指标确认峰值及增长原因。
                  建议动作：核查内存指标与 limits，排查泄漏，再决定调整限制或修复应用。
                  未知项：此演示没有连接真实集群，不能确认生产环境故障。
                  """
                : "【离线演示】未从工具结果找到 OOMKilled 证据，无法确认根因。请检查示例文件。";
        assistant.put("content", answer);
        return new Reply(assistant, "stop");
    }
}
