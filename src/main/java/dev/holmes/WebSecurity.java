package dev.holmes;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 内存中的有界会话与失败限速；密码只用于常量时摘要比较。 */
final class WebSecurity {
    static final String COOKIE = "holmes_session";
    private static final Duration SESSION_TTL = Duration.ofHours(8);
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(5);
    private static final int MAX_FAILURES = 5;
    private static final int MAX_SESSIONS = 256;
    private static final int MAX_FAILURE_CLIENTS = 1024;
    private static final String CSP = "default-src 'self'; script-src 'self' https://cdn.jsdelivr.net; "
            + "style-src 'self' https://cdn.jsdelivr.net; img-src 'self' data:; "
            + "connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'";
    private final byte[] passwordDigest;
    private final boolean authenticationRequired;
    private final boolean secureCookie;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Instant> sessions = new HashMap<>();
    private final Map<String, Failures> failures = new HashMap<>();

    WebSecurity(WebConfig config) { this(config, Clock.systemUTC()); }
    WebSecurity(WebConfig config, Clock clock) {
        this.passwordDigest = digest(config.password());
        this.authenticationRequired = !config.password().isEmpty();
        this.secureCookie = config.secureCookie();
        this.clock = clock;
    }

    boolean authenticationRequired() { return authenticationRequired; }

    Filter filter(boolean requireAuthentication) {
        return new Filter() {
            @Override public String description() { return "网页安全响应头、同源写请求与登录会话"; }
            @Override public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
                headers(exchange);
                if (unsafe(exchange.getRequestMethod()) && !sameOrigin(exchange)) {
                    send(exchange, 403, Map.of("error", "请从当前网页提交请求")); exchange.close(); return;
                }
                if (requireAuthentication && !authenticated(exchange)) {
                    send(exchange, 401, Map.of("error", "请先登录")); exchange.close(); return;
                }
                chain.doFilter(exchange);
            }
        };
    }

    void auth(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getRawPath();
            if (path.equals("/auth/session")) {
                if (!exchange.getRequestMethod().equals("GET")) { send(exchange, 405, Map.of("error", "请使用 GET")); return; }
                send(exchange, 200, Map.of("authenticated", authenticated(exchange), "authentication_required", authenticationRequired)); return;
            }
            if (!path.equals("/auth/login") && !path.equals("/auth/logout")) {
                send(exchange, 404, Map.of("error", "接口不存在")); return;
            }
            if (!exchange.getRequestMethod().equals("POST")) { send(exchange, 405, Map.of("error", "请使用 POST")); return; }
            if (path.equals("/auth/logout")) {
                synchronized (this) { sessions.remove(cookie(exchange)); }
                exchange.getResponseHeaders().set("Set-Cookie", cookieHeader("", 0));
                send(exchange, 200, Map.of("authenticated", !authenticationRequired, "authentication_required", authenticationRequired)); return;
            }
            if (!authenticationRequired) {
                send(exchange, 200, Map.of("authenticated", true, "authentication_required", false)); return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(4097);
            if (body.length > 4096) { send(exchange, 413, Map.of("error", "登录请求过大")); return; }
            JsonNode request;
            try { request = Json.MAPPER.readTree(body); }
            catch (IOException e) { send(exchange, 400, Map.of("error", "登录请求格式无效")); return; }
            if (request == null || !request.isObject() || request.size() != 1 || !request.path("password").isTextual()
                    || request.path("password").asText().length() > 1024) {
                send(exchange, 400, Map.of("error", "登录请求格式无效")); return;
            }
            login(exchange, request.path("password").asText());
        } catch (RuntimeException e) {
            // 解析和认证错误不能回显密码或原始请求。
            send(exchange, 500, Map.of("error", "登录服务暂时不可用"));
        } finally { exchange.close(); }
    }

    private synchronized void login(HttpExchange exchange, String password) throws IOException {
        Instant now = clock.instant();
        cleanup(now);
        String client = exchange.getRemoteAddress().getAddress().getHostAddress();
        Failures previous = failures.get(client);
        if (previous != null && previous.count >= MAX_FAILURES || previous == null && failures.size() >= MAX_FAILURE_CLIENTS) {
            long seconds = previous == null ? FAILURE_WINDOW.toSeconds()
                    : Math.max(1, Duration.between(now, previous.expiresAt).toSeconds());
            exchange.getResponseHeaders().set("Retry-After", Long.toString(seconds));
            send(exchange, 429, Map.of("error", "登录失败次数过多，请稍后重试")); return;
        }
        if (!MessageDigest.isEqual(passwordDigest, digest(password))) {
            failures.put(client, previous == null ? new Failures(1, now.plus(FAILURE_WINDOW))
                    : new Failures(previous.count + 1, previous.expiresAt));
            send(exchange, 401, Map.of("error", "密码错误")); return;
        }
        failures.remove(client);
        if (sessions.size() >= MAX_SESSIONS) {
            send(exchange, 503, Map.of("error", "会话容量已满，请稍后重试")); return;
        }
        // 每次登录都生成新会话，不接受客户端提供的固定会话 ID。
        byte[] token = new byte[32]; random.nextBytes(token);
        String session = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        sessions.put(session, now.plus(SESSION_TTL));
        exchange.getResponseHeaders().set("Set-Cookie", cookieHeader(session, SESSION_TTL.toSeconds()));
        send(exchange, 200, Map.of("authenticated", true, "authentication_required", true));
    }

    synchronized boolean authenticated(HttpExchange exchange) {
        if (!authenticationRequired) return true;
        String token = cookie(exchange);
        Instant expiration = sessions.get(token);
        if (expiration == null) return false;
        if (!expiration.isAfter(clock.instant())) { sessions.remove(token); return false; }
        return true;
    }

    private void cleanup(Instant now) {
        sessions.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
        failures.entrySet().removeIf(entry -> !entry.getValue().expiresAt.isAfter(now));
    }

    private boolean sameOrigin(HttpExchange exchange) {
        if ("cross-site".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Sec-Fetch-Site"))) return false;
        List<String> origins = exchange.getRequestHeaders().get("Origin");
        if (origins == null || origins.isEmpty()) return true; // 无 Origin 的 CLI / curl 调用仍受会话认证约束。
        List<String> hosts = exchange.getRequestHeaders().get("Host");
        if (origins.size() != 1 || hosts == null || hosts.size() != 1) return false;
        try {
            URI origin = URI.create(origins.getFirst());
            String scheme = secureCookie ? "https" : "http";
            if (!scheme.equals(origin.getScheme()) || origin.getHost() == null || origin.getUserInfo() != null
                    || origin.getQuery() != null || origin.getFragment() != null
                    || origin.getRawPath() != null && !origin.getRawPath().isEmpty()) return false;
            URI host = URI.create(scheme + "://" + hosts.getFirst());
            return host.getHost() != null && host.getUserInfo() == null && host.getQuery() == null && host.getFragment() == null
                    && (host.getRawPath() == null || host.getRawPath().isEmpty())
                    && origin.getHost().equalsIgnoreCase(host.getHost()) && port(origin) == port(host);
        } catch (IllegalArgumentException e) { return false; }
    }

    private static int port(URI uri) { return uri.getPort() >= 0 ? uri.getPort() : "https".equals(uri.getScheme()) ? 443 : 80; }
    private static boolean unsafe(String method) { return !List.of("GET", "HEAD", "OPTIONS").contains(method); }

    private static String cookie(HttpExchange exchange) {
        List<String> headers = exchange.getRequestHeaders().get("Cookie");
        if (headers == null) return "";
        String found = "";
        for (String header : headers) {
            if (header.length() > 8192) return "";
            for (String item : header.split(";")) {
                String value = item.strip();
                if (!value.startsWith(COOKIE + "=")) continue;
                String candidate = value.substring(COOKIE.length() + 1);
                if (!found.isEmpty() || !candidate.matches("[A-Za-z0-9_-]{43}")) return "";
                found = candidate;
            }
        }
        return found;
    }

    private String cookieHeader(String value, long seconds) {
        return COOKIE + "=" + value + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + seconds + (secureCookie ? "; Secure" : "");
    }

    private void headers(HttpExchange exchange) {
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Security-Policy", CSP);
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        headers.set("Cache-Control", "no-store");
        if (secureCookie) headers.set("Strict-Transport-Security", "max-age=31536000");
    }

    static void send(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = Json.MAPPER.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static byte[] digest(String password) {
        try { return MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private record Failures(int count, Instant expiresAt) { }
}
