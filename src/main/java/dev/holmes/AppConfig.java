package dev.holmes;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** 集中管理运行参数；密钥只从环境变量读取。 */
public record AppConfig(URI baseUrl, String apiKey, String model, Duration llmTimeout,
                        int maxSteps, int maxToolCalls, int toolTimeoutSeconds,
                        int maxOutputChars, int maxContextChars, Path toolsFile,
                        Path runbooksDir, int port) {
    public static AppConfig load() { return from(System.getenv()); }

    public static AppConfig from(Map<String, String> env) {
        URI base = URI.create(value(env, "LLM_BASE_URL", "https://llm.aiaipool.com/v1"));
        if (base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null
                || base.getFragment() != null || !("https".equals(base.getScheme())
                || ("http".equals(base.getScheme()) && ("localhost".equals(base.getHost())
                || "127.0.0.1".equals(base.getHost()))))) {
            throw new IllegalArgumentException("LLM_BASE_URL 必须是 HTTPS 地址；本机测试可用 HTTP");
        }
        return new AppConfig(base, value(env, "LLM_API_KEY", ""),
                value(env, "LLM_MODEL", "deepseek-v41-flash"),
                Duration.ofSeconds(number(env, "LLM_TIMEOUT_SECONDS", 90, 1, 600)),
                number(env, "HOLMES_MAX_STEPS", 8, 2, 100),
                number(env, "HOLMES_MAX_TOOL_CALLS", 24, 1, 200),
                number(env, "HOLMES_TOOL_TIMEOUT_SECONDS", 15, 1, 300),
                number(env, "HOLMES_MAX_OUTPUT_CHARS", 12000, 256, 100000),
                number(env, "HOLMES_MAX_CONTEXT_CHARS", 120000, 4096, 2000000),
                Path.of(value(env, "HOLMES_TOOLS_FILE", "config/tools.json")),
                Path.of(value(env, "HOLMES_RUNBOOKS_DIR", "runbooks")),
                number(env, "HOLMES_PORT", 8080, 1, 65535));
    }

    private static String value(Map<String, String> env, String key, String fallback) {
        String value = env.get(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int number(Map<String, String> env, String key, int fallback, int min, int max) {
        try {
            int n = Integer.parseInt(value(env, key, Integer.toString(fallback)));
            if (n >= min && n <= max) return n;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(key + " 必须介于 " + min + " 与 " + max + " 之间");
    }

    @Override public String toString() {
        return "AppConfig[baseUrl=" + baseUrl + ", model=" + model + ", apiKey=***]";
    }
}
