package dev.holmes;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 网页服务默认只监听本机；可选访问密码，空值关闭登录认证。 */
public record WebConfig(String host, int port, String password, boolean secureCookie) {
    public WebConfig {
        if (host == null || !host.matches("[A-Za-z0-9:.%_-]{1,253}")) {
            throw new IllegalArgumentException("HOLMES_BIND_HOST 必须是有效的主机名或监听地址");
        }
        if (port < 0 || port > 65535) throw new IllegalArgumentException("HOLMES_PORT 必须介于 0 与 65535");
        password = password == null ? "" : password;
        if (!password.isEmpty() && (password.isBlank() || password.codePointCount(0, password.length()) < 12
                || password.codePointCount(0, password.length()) > 256 || password.getBytes(StandardCharsets.UTF_8).length > 1024
                || password.codePoints().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("请配置 12–256 个字符的 HOLMES_WEB_PASSWORD，且不能包含控制字符");
        }
    }

    public static WebConfig from(Map<String, String> env) {
        String host = value(env, "HOLMES_BIND_HOST", "127.0.0.1");
        int port;
        try { port = Integer.parseInt(value(env, "HOLMES_PORT", "8080")); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("HOLMES_PORT 必须是有效端口"); }
        String secure = value(env, "HOLMES_WEB_SECURE_COOKIE", "false");
        if (!secure.equalsIgnoreCase("true") && !secure.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException("HOLMES_WEB_SECURE_COOKIE 必须为 true 或 false");
        }
        return new WebConfig(host, port, env.get("HOLMES_WEB_PASSWORD"), Boolean.parseBoolean(secure));
    }

    private static String value(Map<String, String> env, String key, String fallback) {
        String value = env.get(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    @Override public String toString() {
        return "WebConfig[host=" + host + ", port=" + port + ", password=***, secureCookie=" + secureCookie + "]";
    }
}
