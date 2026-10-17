package dev.holmes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.holmes.logs.LogConfig;
import dev.holmes.logs.LogRequest;
import dev.holmes.logs.LogNarrativeGenerator;
import dev.holmes.logs.TaskSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Supplier;

/** 浏览器接收操作所需的公开配置与模型发送目的地；凭据、采集源地址和本机路径不进入响应。 */
public final class WebStatus implements Supplier<JsonNode> {
    private final LogConfig logs;
    private final boolean askEnabled;
    private final boolean demo;
    private final boolean scheduler;
    private final LogNarrativeGenerator narrative;

    public WebStatus(LogConfig logs, boolean askEnabled, boolean demo) {
        this(logs, askEnabled, demo, false);
    }

    public WebStatus(LogConfig logs, boolean askEnabled, boolean demo, boolean scheduler) {
        this(logs, askEnabled, demo, scheduler, null);
    }

    public WebStatus(LogConfig logs, boolean askEnabled, boolean demo, boolean scheduler, LogNarrativeGenerator narrative) {
        this.logs = logs; this.askEnabled = askEnabled; this.demo = demo; this.scheduler = scheduler;
        this.narrative = narrative;
    }

    @Override public JsonNode get() {
        ObjectNode result = Json.MAPPER.createObjectNode()
                .put("app_name", "Holmes").put("version", "0.1.0")
                .put("demo", demo).put("data_mode", logs.logsFile() == null ? "grafana" : "file")
                .put("log_source", logs.source()).put("task_source", scheduler ? "scheduler_api" : "snapshot_file");
        result.putObject("capabilities").put("logs", true).put("ask", askEnabled).put("log_narrative", narrative != null);
        var model = result.putObject("log_narrative").put("enabled", narrative != null).put("demo", demo);
        if (narrative != null) model.put("model", narrative.model()).put("destination", narrative.destination())
                .put("mode", narrative.toolDriven() ? "tool_driven" : "summary");
        var namespaces = result.putArray("namespaces");
        logs.namespaces().stream().sorted().forEach(namespaces::add);
        ObjectNode snapshot = result.putObject("snapshot").put("ready", false).put("failed_count", 0);
        snapshot.putNull("observed_at").putNull("namespace").putNull("error");
        Instant to = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        if (scheduler) {
            // 这里只说明来源已配置；联网读取在异步批次中进行，不能伪称已读到失败数。
            snapshot.put("ready", true).put("mode", "scheduler_api").putNull("failed_count");
            result.put("task_time_basis", "created_at");
        } else if (logs.tasksFile() == null) {
            snapshot.put("error", "尚未配置任务状态快照");
        } else {
            try {
                JsonNode root;
                try (var input = Files.newInputStream(logs.tasksFile())) {
                    byte[] bytes = input.readNBytes((8 << 20) + 1);
                    if (bytes.length > (8 << 20)) throw new IOException();
                    root = Json.MAPPER.readTree(bytes);
                }
                if (root == null || !root.isObject() || !root.path("tasks").isArray()
                        || !root.path("source").asText().equals(logs.source())
                        || !logs.namespaces().contains(root.path("namespace").asText())) throw new IOException();
                Instant observed = Instant.parse(root.path("observed_at").asText());
                // select 同时验证终态、重试与 ID 映射，不能仅凭文件存在显示“就绪”。
                Instant upper = observed.truncatedTo(ChronoUnit.MILLIS).plusMillis(1);
                new TaskSnapshot().select(logs.tasksFile(), new LogRequest(logs.source(),
                        root.path("namespace").asText(), "latest_failed", List.of(), 50,
                        upper.minusSeconds(86400), upper, false));
                int failed = 0;
                for (JsonNode task : root.path("tasks")) {
                    if (task.path("status").asText().equals("failed") && !task.path("retry_pending").asBoolean()) failed++;
                }
                snapshot.put("ready", true).put("failed_count", failed)
                        .put("observed_at", observed.toString()).put("namespace", root.path("namespace").asText());
                if (logs.logsFile() != null) to = upper;
            } catch (IOException | RuntimeException e) {
                snapshot.put("error", "任务状态快照不可用，请检查服务端配置与快照内容");
            }
        }
        result.putObject("defaults").put("selection_mode", "latest_failed")
                .put("requested_count", demo && snapshot.path("ready").asBoolean()
                        ? Math.max(1, Math.min(20, snapshot.path("failed_count").asInt())) : 20)
                .put("from", to.minusSeconds(3600).toString()).put("to", to.toString())
                .put("raw_archive", "none").put("narrative", demo && narrative != null ? "llm" : "none");
        return result;
    }
}
