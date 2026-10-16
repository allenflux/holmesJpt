package dev.holmes.logs;

import com.fasterxml.jackson.databind.JsonNode;
import dev.holmes.HttpApi;
import dev.holmes.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** 日志模式可在没有模型 Key 的环境中采集、关联、聚合与归档。 */
public final class LogCli {
    private LogCli() { }
    public static void run(String[] args, Map<String, String> baseEnv) throws Exception {
        if (args.length == 0 || args[0].equals("--help")) { help(); return; }
        if (!args[0].equals("analyze") && !args[0].equals("serve")) throw new IllegalArgumentException("logs 子命令必须是 analyze 或 serve");
        Map<String, String> env = new HashMap<>(baseEnv);
        Path requestFile = null, output = null;
        int port = 8080;
        for (int i = 1; i < args.length; i++) {
            String flag = args[i];
            if (++i == args.length) throw new IllegalArgumentException(flag + " 缺少参数");
            String value = args[i];
            switch (flag) {
                case "--tasks" -> env.put("HOLMES_LOG_TASKS_FILE", value);
                case "--log-file" -> env.put("HOLMES_LOG_FILE", value);
                case "--data-dir" -> env.put("HOLMES_LOG_DATA_DIR", value);
                case "--request" -> requestFile = Path.of(value);
                case "--output" -> output = Path.of(value);
                case "--port" -> { try { port = Integer.parseInt(value); } catch (NumberFormatException e) { throw new IllegalArgumentException("端口无效"); } }
                default -> throw new IllegalArgumentException("无效 logs 参数：" + flag);
            }
        }
        if (port < 0 || port > 65535) throw new IllegalArgumentException("端口必须介于 0 与 65535");
        LogConfig config = LogConfig.from(env);
        if (args[0].equals("serve")) {
            if (requestFile != null || output != null) throw new IllegalArgumentException("logs serve 不接受 --request / --output");
            LogInvestigationService service = new LogInvestigationService(config, TaskSource.from(config, env));
            try {
                var server = new HttpApi(null, service).start(port);
                Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.stop(1); service.close(); }));
                System.out.println("Holmes Log 已启动：http://127.0.0.1:" + server.getAddress().getPort());
            } catch (Exception e) { service.close(); throw e; }
            return;
        }
        if (requestFile == null) throw new IllegalArgumentException("logs analyze 需要 --request 文件");
        JsonNode request;
        try (var input = Files.newInputStream(requestFile)) {
            byte[] bytes = input.readNBytes(65537);
            if (bytes.length > 65536) throw new IllegalArgumentException("请求文件超过 64 KiB");
            request = Json.MAPPER.readTree(bytes);
        } catch (com.fasterxml.jackson.core.JacksonException e) { throw new IllegalArgumentException("请求文件必须是有效 JSON"); }
        try (LogInvestigationService service = new LogInvestigationService(config, TaskSource.from(config, env))) {
            var accepted = service.submit(request);
            var result = service.await(accepted.path("batch_id").asText());
            String json = Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result);
            if (output == null) System.out.println(json);
            else { Files.writeString(output, json); System.out.println("日志批次：" + result.path("state").asText() + "；报告：" + output.toAbsolutePath()); }
        }
    }

    private static void help() {
        System.out.println("""
                Holmes Log 首版（无需 LLM_API_KEY）
                logs analyze --request 请求.json [--tasks 状态快照.json] [--output 报告.json]
                logs serve [--tasks 状态快照.json] [--port 8080]
                默认自动从 HOLMES_SCHEDULER_URL 获取任务；--tasks 切换为明确的文件快照。
                离线样本同时指定 --tasks examples/logs/tasks.json --log-file examples/logs/events.jsonl
                数据源与归档配置使用 HOLMES_GRAFANA_* / HOLMES_FLUXDROP_* 环境变量。
                --data-dir 指定有容量上限和 TTL 的检查点目录；大日志只在显式 required 模式归档。
                """);
    }
}
