package dev.holmes.logs;

import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;

/** 服务端调度器连接；地址与凭据不接受浏览器请求覆盖。 */
public record SchedulerConfig(URI url, String token, ZoneId timezone, int pageSize,
                              int maxTasks, Duration timeout, Duration scanTimeout) {
    public static SchedulerConfig from(Map<String, String> env) {
        URI url = LogConfig.uri(value(env, "HOLMES_SCHEDULER_URL", "http://216.176.194.30:18080"));
        if (!url.getPath().isEmpty() && !url.getPath().equals("/"))
            throw new IllegalArgumentException("HOLMES_SCHEDULER_URL 请填写服务根地址，不含页面路径");
        String token = env.getOrDefault("HOLMES_SCHEDULER_TOKEN", "").trim();
        if (!token.isEmpty() && !token.matches("[A-Za-z0-9._~+/-]+=*"))
            throw new IllegalArgumentException("HOLMES_SCHEDULER_TOKEN 格式无效");
        ZoneId zone;
        try { zone = ZoneId.of(value(env, "HOLMES_SCHEDULER_TIMEZONE", "Asia/Hong_Kong")); }
        catch (RuntimeException e) { throw new IllegalArgumentException("HOLMES_SCHEDULER_TIMEZONE 无效"); }
        return new SchedulerConfig(url, token, zone,
                number(env, "HOLMES_SCHEDULER_PAGE_SIZE", 500, 1, 500),
                number(env, "HOLMES_SCHEDULER_MAX_TASKS", 200000, 1, 200000),
                Duration.ofSeconds(number(env, "HOLMES_SCHEDULER_TIMEOUT_SECONDS", 30, 1, 60)),
                Duration.ofSeconds(number(env, "HOLMES_SCHEDULER_SCAN_SECONDS", 120, 1, 300)));
    }

    private static String value(Map<String, String> env, String key, String fallback) {
        String value = env.get(key); return value == null || value.isBlank() ? fallback : value.trim();
    }
    private static int number(Map<String, String> env, String key, int fallback, int min, int max) {
        try { int n = Integer.parseInt(value(env, key, Integer.toString(fallback))); if (n >= min && n <= max) return n; }
        catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(key + " 必须介于 " + min + " 与 " + max);
    }
    @Override public String toString() { return "SchedulerConfig[token=***, pageSize=" + pageSize + ", maxTasks=" + maxTasks + "]"; }
}
