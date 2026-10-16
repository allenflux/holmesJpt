package dev.holmes;

import dev.holmes.logs.LogConfig;
import dev.holmes.logs.LogInvestigationService;
import dev.holmes.logs.TaskSource;
import dev.holmes.tools.ToolRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** 统一网页入口：本地调试与远端部署使用同一服务，模型问答为可选能力。 */
public final class WebCli {
    private WebCli() { }

    public static void run(String[] args, Map<String, String> baseEnv) throws Exception {
        if (Arrays.asList(args).contains("--help")) { help(); return; }
        boolean demo = Arrays.asList(args).contains("--demo");
        Map<String, String> env = new HashMap<>(baseEnv);
        if (demo) {
            env.put("HOLMES_LOG_SOURCE", "scheduler-victorialogs");
            env.put("HOLMES_LOG_NAMESPACES", "prod");
            env.put("HOLMES_LOG_TASKS_FILE", "examples/logs/tasks.json");
            env.put("HOLMES_LOG_FILE", "examples/logs/events.jsonl");
            env.put("HOLMES_LOG_DATA_DIR", "data/holmes-log-demo");
            env.put("HOLMES_TOOLS_FILE", "config/tools.json");
            env.put("HOLMES_RUNBOOKS_DIR", "runbooks");
        } else if (env.getOrDefault("HOLMES_TOOLS_FILE", "").isBlank()) {
            env.put("HOLMES_TOOLS_FILE", "config/tools-web.json");
        }
        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            if (flag.equals("--demo")) continue;
            String variable = switch (flag) {
                case "--host" -> "HOLMES_BIND_HOST";
                case "--port" -> "HOLMES_PORT";
                case "--tasks" -> "HOLMES_LOG_TASKS_FILE";
                case "--log-file" -> "HOLMES_LOG_FILE";
                case "--data-dir" -> "HOLMES_LOG_DATA_DIR";
                case "--tools" -> "HOLMES_TOOLS_FILE";
                case "--runbooks" -> "HOLMES_RUNBOOKS_DIR";
                case "--max-steps" -> "HOLMES_MAX_STEPS";
                default -> throw new IllegalArgumentException("无效 web 参数：" + flag);
            };
            if (++i == args.length) throw new IllegalArgumentException(flag + " 缺少参数");
            if (demo && (flag.equals("--tasks") || flag.equals("--log-file") || flag.equals("--tools") || flag.equals("--runbooks"))) {
                throw new IllegalArgumentException("--demo 使用固定演示文件，请去掉 --demo 后配置实际来源");
            }
            env.put(variable, args[i]);
        }
        WebConfig web = WebConfig.from(env);
        LogConfig logConfig = LogConfig.from(env);
        if (demo && (!Files.isRegularFile(logConfig.tasksFile()) || !Files.isRegularFile(logConfig.logsFile()))) {
            throw new IOException("演示文件不可用，请从项目根目录启动 web --demo");
        }
        AgentEngine engine = null;
        if (demo || !env.getOrDefault("LLM_API_KEY", "").isBlank()) {
            try {
                Map<String, String> engineEnv = new HashMap<>(env);
                // WebConfig 允许本地测试选择随机端口；调查引擎不使用 AppConfig.port。
                if (web.port() == 0) engineEnv.put("HOLMES_PORT", "8080");
                AppConfig app = AppConfig.from(engineEnv);
                ToolRegistry tools = ToolRegistry.load(app.toolsFile(), app.toolTimeoutSeconds(), app.maxOutputChars());
                LlmClient llm = demo ? new DemoLlm() : new OpenAiClient(app);
                engine = new AgentEngine(llm, tools, Runbooks.load(app.runbooksDir()),
                        app.maxSteps(), app.maxToolCalls(), app.maxContextChars());
            } catch (IOException | RuntimeException e) {
                if (demo) throw new IOException("演示问答配置不可用，请检查项目示例文件");
                System.err.println("通用问答未启用：请检查服务端模型与工具配置");
            }
        }
        TaskSource tasks = TaskSource.from(logConfig, env);
        LogInvestigationService logs = new LogInvestigationService(logConfig, tasks);
        try {
            var server = new HttpApi(engine, logs).startWeb(web, new WebStatus(logConfig, engine != null, demo, tasks.asynchronousSelection()));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.stop(1); logs.close(); }));
            String host = web.host().contains(":") ? "[" + web.host() + "]" : web.host();
            System.out.println("Holmes 网页已启动：http://" + host + ":" + server.getAddress().getPort());
            if (demo) System.out.println("演示模式：合成日志与脚本问答；可用于本地调试");
        } catch (Exception e) { logs.close(); throw e; }
    }

    private static void help() {
        System.out.println("""
                Holmes 网页服务
                web [--host 127.0.0.1] [--port 8080] [--tasks 状态快照.json]
                web --demo [--port 8080]
                启动时自动读取当前目录 .env；可用 --env-file 文件 显式指定，首版默认无需登录。
                可选 HOLMES_WEB_PASSWORD（至少 12 个字符）启用密码访问。
                日志配置沿用 HOLMES_LOG_* / HOLMES_GRAFANA_* / HOLMES_FLUXDROP_*。
                默认自动读取 HOLMES_SCHEDULER_URL 任务接口；--tasks 文件 可改用任务快照。
                配置 LLM_API_KEY 后启用通用问答；--demo 使用明确标注的固定合成样本。
                远端 Docker Compose 与 HTTPS 反向代理步骤见 docs/网页服务与部署.md。
                """);
    }
}
