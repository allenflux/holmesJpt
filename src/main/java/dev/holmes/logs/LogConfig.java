package dev.holmes.logs;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 日志接入只读取管理员配置；密钥不会进入请求、检查点或 toString。 */
public record LogConfig(String source, Set<String> namespaces, URI grafanaUrl, String grafanaToken,
                        String datasourceUid, Path tasksFile, Path logsFile, Path dataDir,
                        URI fluxDropUrl, String fluxDropToken, int pageSize, Duration requestTimeout,
                        int maxEvents, long maxBytes, long spoolBytes, long chunkBytes,
                        long storeBytes, long reportBytes, Duration retention, Duration jobTimeout) {
    public static LogConfig from(Map<String, String> env) {
        Set<String> namespaces = Arrays.stream(value(env, "HOLMES_LOG_NAMESPACES", "prod").split(","))
                .map(String::trim).collect(Collectors.toUnmodifiableSet());
        if (namespaces.isEmpty() || namespaces.stream().anyMatch(s -> !s.matches("[a-z0-9][a-z0-9-]{0,62}")))
            throw new IllegalArgumentException("HOLMES_LOG_NAMESPACES 格式无效");
        String source = value(env, "HOLMES_LOG_SOURCE", "scheduler-victorialogs");
        String uid = value(env, "HOLMES_GRAFANA_DATASOURCE_UID", "victorialogs");
        if (!LogRequest.validId(source) || !LogRequest.validId(uid)) throw new IllegalArgumentException("日志来源或数据源 UID 无效");
        String tasks = env.getOrDefault("HOLMES_LOG_TASKS_FILE", "").trim();
        String logs = env.getOrDefault("HOLMES_LOG_FILE", "").trim();
        return new LogConfig(source, namespaces,
                uri(value(env, "HOLMES_GRAFANA_URL", "https://sch-grafana.aiaipool.com")),
                value(env, "HOLMES_GRAFANA_TOKEN", ""), uid,
                tasks.isEmpty() ? null : Path.of(tasks), logs.isEmpty() ? null : Path.of(logs),
                Path.of(value(env, "HOLMES_LOG_DATA_DIR", "data/holmes-log")),
                uri(value(env, "HOLMES_FLUXDROP_URL", "http://allenflux.tech:8090")),
                value(env, "HOLMES_FLUXDROP_TOKEN", ""),
                (int) number(env, "HOLMES_LOG_PAGE_SIZE", 1000, 2, 1000),
                Duration.ofSeconds(number(env, "HOLMES_LOG_REQUEST_SECONDS", 15, 1, 60)),
                (int) number(env, "HOLMES_LOG_MAX_EVENTS", 100000, 1, 100000),
                number(env, "HOLMES_LOG_MAX_BYTES", 64L << 20, 1024, 64L << 20),
                number(env, "HOLMES_LOG_SPOOL_BYTES", 256L << 20, 1024, 256L << 20),
                number(env, "HOLMES_LOG_CHUNK_BYTES", 16L << 20, 256, 16L << 20),
                number(env, "HOLMES_LOG_STORE_BYTES", 100L << 20, 4096, 100L << 20),
                number(env, "HOLMES_LOG_REPORT_BYTES", 2L << 20, 4096, 2L << 20),
                Duration.ofHours(number(env, "HOLMES_LOG_RETENTION_HOURS", 168, 1, 168)),
                Duration.ofSeconds(number(env, "HOLMES_LOG_JOB_SECONDS", 300, 1, 300)));
    }

    static URI uri(String text) {
        URI uri;
        try { uri = URI.create(text); } catch (RuntimeException e) { throw new IllegalArgumentException("数据源地址无效"); }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())))
            throw new IllegalArgumentException("数据源地址必须为不含凭据/查询参数的 HTTP(S) 地址");
        return uri;
    }
    private static String value(Map<String, String> env, String key, String fallback) {
        String v = env.get(key); return v == null || v.isBlank() ? fallback : v.trim();
    }
    private static long number(Map<String, String> env, String key, long fallback, long min, long max) {
        try { long n = Long.parseLong(value(env, key, Long.toString(fallback))); if (n >= min && n <= max) return n; }
        catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(key + " 必须介于 " + min + " 与 " + max);
    }
    @Override public String toString() {
        return "LogConfig[source=" + source + ", datasourceUid=" + datasourceUid + ", tokens=***]";
    }
}
