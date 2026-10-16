package dev.holmes;

import dev.holmes.tools.ToolRegistry;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** 薄入口：解析命令，然后交给同一套配置、工具与调查引擎。 */
public final class Main {
    private Main() { }
    public static void main(String[] args) {
        try { run(args); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); System.err.println("操作已中断"); System.exit(130); }
        catch (Exception e) { System.err.println("错误：" + e.getMessage()); System.exit(1); }
    }

    static void run(String[] args) throws Exception {
        run(args, System::getenv);
    }

    /** 测试可注入合成系统环境，不读取真实 .env 或进程中的密钥。 */
    static void run(String[] args, Map<String, String> systemEnv) throws Exception {
        run(args, () -> systemEnv);
    }

    private static void run(String[] input, Supplier<Map<String, String>> systemEnv) throws Exception {
        StartupOptions options = StartupOptions.parse(input);
        String[] args = options.arguments().toArray(String[]::new);
        if (options.helpRequested()) {
            if (args.length > 0 && args[0].equals("web")) WebCli.run(new String[]{"--help"}, Map.of());
            else if (args.length > 0 && args[0].equals("logs")) {
                dev.holmes.logs.LogCli.run(new String[]{"--help"}, Map.of());
                System.out.println("全局参数：--env-file PATH，可放在子命令前后；只选择配置文件，不改变工作目录。");
            }
            else help();
            return;
        }
        Map<String, String> env = new HashMap<>(options.explicitEnvFile()
                ? EnvironmentConfig.loadRequired(options.envFile(), systemEnv.get())
                : EnvironmentConfig.load(options.envFile(), systemEnv.get()));
        if ("web".equals(args[0])) {
            WebCli.run(java.util.Arrays.copyOfRange(args, 1, args.length), env); return;
        }
        if ("logs".equals(args[0])) {
            dev.holmes.logs.LogCli.run(java.util.Arrays.copyOfRange(args, 1, args.length), env); return;
        }
        String command = args[0];
        if (!java.util.Set.of("ask", "demo", "tools", "serve").contains(command)) {
            throw new IllegalArgumentException("未知命令：" + command + "；使用 --help 查看用法");
        }
        boolean trace = false;
        String question = null;
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if ("--".equals(arg) && "ask".equals(command) && question == null) {
                if (++i == args.length) throw new IllegalArgumentException("ask -- 缺少问题");
                question = args[i];
                continue;
            }
            if ("--trace".equals(arg)) { trace = true; continue; }
            String variable = switch (arg) {
                case "--tools" -> "HOLMES_TOOLS_FILE";
                case "--runbooks" -> "HOLMES_RUNBOOKS_DIR";
                case "--max-steps" -> "HOLMES_MAX_STEPS";
                case "--port" -> "HOLMES_PORT";
                default -> null;
            };
            if (variable != null) {
                if (++i == args.length) throw new IllegalArgumentException(arg + " 缺少参数");
                env.put(variable, args[i]);
            } else if ("ask".equals(command) && question == null && !arg.startsWith("--")) question = arg;
            else throw new IllegalArgumentException("无效参数：" + arg + "；问题请用引号包围");
        }
        AppConfig config = AppConfig.from(env);
        ToolRegistry registry = ToolRegistry.load(config.toolsFile(), config.toolTimeoutSeconds(), config.maxOutputChars());
        if ("tools".equals(command)) {
            System.out.println(Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(registry.definitions())); return;
        }
        LlmClient llm = "demo".equals(command) ? new DemoLlm() : new OpenAiClient(config);
        AgentEngine engine = new AgentEngine(llm, registry, Runbooks.load(config.runbooksDir()),
                config.maxSteps(), config.maxToolCalls(), config.maxContextChars());
        if ("serve".equals(command)) {
            var server = new HttpApi(engine).start(config.port());
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(1)));
            System.out.println("Holmes Java 已启动：http://127.0.0.1:" + config.port());
            return;
        }
        if ("demo".equals(command)) question = "请调查示例 Pod 重启原因，并引用文件中的证据";
        var result = engine.investigate(question);
        System.out.println(result.answer());
        System.out.printf("%n[结束状态=%s，模型轮数=%d，工具尝试=%d]%n", result.stopReason(), result.steps(), result.toolCalls());
        if (trace) System.out.println(Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result.trace()));
    }

    private static void help() {
        System.out.println("""
                Holmes Java Lite（JDK 27）
                java -jar target/holmes-java-0.1.0.jar web [--demo] [--port 8080]
                java -jar target/holmes-java-0.1.0.jar demo [--trace]
                java -jar target/holmes-java-0.1.0.jar ask "问题" [--trace]
                java -jar target/holmes-java-0.1.0.jar tools
                java -jar target/holmes-java-0.1.0.jar serve [--port 8080]
                java -jar target/holmes-java-0.1.0.jar logs --help
                通用参数：--env-file PATH --tools 配置文件 --runbooks 目录 --max-steps 8
                --env-file 可放在子命令前后，仅选择配置文件，不改变当前工作目录。
                默认读取当前目录 .env；优先级：命令行 > 系统环境 > 所选 .env > 默认值。
                真实调查需配置 LLM_API_KEY；其余配置见 .env.example。
                """);
    }
}
