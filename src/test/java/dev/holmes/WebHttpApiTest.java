package dev.holmes;

import com.sun.net.httpserver.HttpServer;
import dev.holmes.logs.LogConfig;
import dev.holmes.logs.LogInvestigationService;
import dev.holmes.tools.ToolRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WebHttpApiTest {
    private static final String PASSWORD = "synthetic-web-password";
    @TempDir Path directory;
    private final HttpClient http = HttpClient.newHttpClient();
    private HttpServer server;
    private LogInvestigationService logs;

    @AfterEach void stop() {
        if (server != null) server.stop(0);
        if (logs != null) logs.close();
    }

    @Test void anonymousApiAccessIsDeniedButMinimalHealthAndSessionRemainPublic() throws Exception {
        AtomicInteger invoked = new AtomicInteger();
        start((messages, tools) -> { invoked.incrementAndGet(); return answer(); }, false);

        assertEquals(200, request("GET", "/health", "", "", "").statusCode());
        assertEquals(Map.of("status", "ok"), Json.MAPPER.readValue(request("GET", "/health", "", "", "").body(), Map.class));
        assertFalse(Json.MAPPER.readTree(request("GET", "/auth/session", "", "", "").body()).path("authenticated").asBoolean());
        assertEquals(401, request("GET", "/api/config", "", "", "").statusCode());
        assertEquals(401, request("POST", "/api/investigate", "{\"question\":\"合成问题\"}", "", "").statusCode());
        assertEquals(401, request("GET", "/api/not-found", "", "", "").statusCode());
        assertEquals(0, invoked.get());
        assertTrue(server.getAddress().getAddress().isLoopbackAddress());
    }

    @Test void loginCookieAllowsConfigAndQuestionThenLogoutInvalidatesIt() throws Exception {
        start((messages, tools) -> answer(), false);
        var login = login(PASSWORD);
        assertEquals(200, login.statusCode());
        assertTrue(Json.MAPPER.readTree(login.body()).path("authenticated").asBoolean());
        String setCookie = login.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Strict"));
        assertTrue(setCookie.contains("Max-Age=28800"));
        assertFalse(setCookie.contains("; Secure"));
        assertFalse(setCookie.contains(PASSWORD));
        String cookie = cookie(login);
        assertTrue(Json.MAPPER.readTree(request("GET", "/auth/session", "", cookie, "").body()).path("authenticated").asBoolean());
        var config = request("GET", "/api/config", "", cookie, "");
        assertEquals(200, config.statusCode());
        assertTrue(Json.MAPPER.readTree(config.body()).path("question_enabled").asBoolean());
        assertTrue(Json.MAPPER.readTree(config.body()).path("authentication_required").asBoolean());
        assertFalse(config.body().contains(PASSWORD));
        assertEquals(200, request("POST", "/api/investigate", "{\"question\":\"合成问题\"}", cookie, "").statusCode());

        var logout = request("POST", "/auth/logout", "{}", cookie, "");
        assertEquals(200, logout.statusCode());
        assertTrue(logout.headers().firstValue("Set-Cookie").orElseThrow().contains("Max-Age=0"));
        assertEquals(401, request("GET", "/api/config", "", cookie, "").statusCode());
        assertFalse(Json.MAPPER.readTree(request("GET", "/auth/session", "", cookie, "").body()).path("authenticated").asBoolean());
    }

    @Test void emptyPasswordAllowsDirectApiAccessWhileCrossOriginWritesRemainBlocked() throws Exception {
        start((messages, tools) -> answer(), false, "");
        var session = Json.MAPPER.readTree(request("GET", "/auth/session", "", "", "").body());
        assertTrue(session.path("authenticated").asBoolean());
        assertFalse(session.path("authentication_required").asBoolean());
        var config = request("GET", "/api/config", "", "", "");
        assertEquals(200, config.statusCode());
        assertFalse(Json.MAPPER.readTree(config.body()).path("authentication_required").asBoolean());
        assertTrue(config.headers().firstValue("Set-Cookie").isEmpty());
        assertEquals(200, request("POST", "/api/investigate", "{\"question\":\"合成问题\"}", "", "").statusCode());
        assertEquals(403, request("POST", "/api/investigate", "{\"question\":\"合成问题\"}", "", "http://attacker.invalid").statusCode());
        assertEquals(404, request("GET", "/api/not-found", "", "", "").statusCode());
        var login = request("POST", "/auth/login", "{}", "", "");
        assertEquals(200, login.statusCode());
        assertTrue(Json.MAPPER.readTree(login.body()).path("authenticated").asBoolean());
        assertTrue(login.headers().firstValue("Set-Cookie").isEmpty());
        var logout = request("POST", "/auth/logout", "{}", "", "");
        assertEquals(200, logout.statusCode());
        assertTrue(Json.MAPPER.readTree(logout.body()).path("authenticated").asBoolean());
        assertFalse(Json.MAPPER.readTree(logout.body()).path("authentication_required").asBoolean());
        assertTrue(logout.headers().firstValue("Set-Cookie").orElseThrow().contains("Max-Age=0"));
    }

    @Test void invalidLoginIsGenericAndFailedAttemptsAreRateLimited() throws Exception {
        start(null, false);
        for (int i = 0; i < 5; i++) {
            var failed = login("synthetic-wrong-password");
            assertEquals(401, failed.statusCode());
            assertFalse(failed.body().contains("synthetic-wrong-password"));
            assertFalse(failed.body().contains(PASSWORD));
            assertTrue(failed.headers().firstValue("Set-Cookie").isEmpty());
        }
        var blocked = login(PASSWORD);
        assertEquals(429, blocked.statusCode());
        assertTrue(Long.parseLong(blocked.headers().firstValue("Retry-After").orElseThrow()) > 0);
        assertEquals(400, request("POST", "/auth/login", "invalid-private-password-json", "", "").statusCode());
        assertEquals(413, request("POST", "/auth/login", "x".repeat(4097), "", "").statusCode());
    }

    @Test void crossOriginUnsafeRequestsAreRejectedAndSameOriginOrCurlAreAllowed() throws Exception {
        start((messages, tools) -> answer(), false);
        String body = Json.MAPPER.createObjectNode().put("password", PASSWORD).toString();
        for (String origin : List.of("https://attacker.invalid", "null", base().replace("http:", "https:"), base() + "/path")) {
            assertEquals(403, request("POST", "/auth/login", body, "", origin).statusCode());
        }
        var login = request("POST", "/auth/login", body, "", base());
        assertEquals(200, login.statusCode());
        String cookie = cookie(login);
        assertEquals(403, request("POST", "/api/investigate", "{\"question\":\"合成问题\"}", cookie, "http://attacker.invalid").statusCode());
        assertEquals(403, request("POST", "/auth/logout", "{}", cookie, "http://attacker.invalid").statusCode());
        assertEquals(200, request("POST", "/api/investigate", "{\"question\":\"合成问题\"}", cookie, base()).statusCode());
        assertEquals(200, request("POST", "/auth/logout", "{}", cookie, "").statusCode());
    }

    @Test void logApiContextRequiresTheSameSession() throws Exception {
        Path file = directory.resolve("synthetic-events.jsonl"); Files.writeString(file, "");
        logs = new LogInvestigationService(LogConfig.from(Map.of("HOLMES_LOG_FILE", file.toString(),
                "HOLMES_LOG_DATA_DIR", directory.resolve("log-data").toString())));
        start(null, false);
        String path = "/api/log-investigations/00000000-0000-0000-0000-000000000000";
        assertEquals(401, request("GET", path, "", "", "").statusCode());
        assertEquals(401, request("POST", "/api/log-investigations", "{}", "", "").statusCode());
        String cookie = cookie(login(PASSWORD));
        assertEquals(404, request("GET", path, "", cookie, "").statusCode());
        assertEquals(403, request("POST", "/api/log-investigations", "{}", cookie, "http://attacker.invalid").statusCode());
        var unavailable = request("POST", "/api/investigate", "{\"question\":\"合成问题\"}", cookie, "");
        assertEquals(503, unavailable.statusCode());
        assertTrue(Json.MAPPER.readTree(unavailable.body()).path("error").isTextual());
    }

    @Test void staticWhitelistAndCdnIntegrityHaveRestrictiveSecurityPolicy() throws Exception {
        start(null, false);
        for (String path : List.of("/.env", "/../.env", "/%2e%2e/.env", "/web/index.html", "/pom.xml", "/%61pp.js",
                "/vendor/unknown.js", "/vendor/.env", "/vendor/../app.js", "/vendor/%62ootstrap.min.css", "/vendor/bootstrap.min.css.map",
                "/vendor/bootstrap.min.css", "/vendor/bootstrap.bundle.min.js")) {
            assertEquals(404, request("GET", path, "", "", "").statusCode(), path);
        }
        for (String path : List.of("/", "/app.js", "/styles.css")) {
            var resource = request("GET", path, "", "", "");
            assertEquals(200, resource.statusCode(), path);
            assertEquals("nosniff", resource.headers().firstValue("X-Content-Type-Options").orElseThrow());
            String csp = resource.headers().firstValue("Content-Security-Policy").orElseThrow();
            assertTrue(csp.contains("default-src 'self'"));
            assertTrue(csp.contains("script-src 'self' https://cdn.jsdelivr.net"));
            assertTrue(csp.contains("style-src 'self' https://cdn.jsdelivr.net"));
            assertTrue(csp.contains("frame-ancestors 'none'"));
            assertFalse(csp.contains("unsafe-inline"));
            assertFalse(resource.body().contains(PASSWORD));
            if (path.equals("/")) {
                assertCdnAsset(resource.body(), "link", "href", "https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/css/bootstrap.min.css",
                        "sha384-sRIl4kxILFvY47J16cr9ZwB07vP4J8+LH7qKQnuqkuIAvNWLzeN8tE5YBujZqJLB");
                assertCdnAsset(resource.body(), "script", "src", "https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/js/bootstrap.bundle.min.js",
                        "sha384-FKyoEForCGlyvwx9Hj09JcYn3nv7wiPVlz7YYwJrWVcXK/BmnVDxM+D2scQbITxI");
            }
        }
        assertEquals(200, request("HEAD", "/app.js", "", "", "").statusCode());
        assertEquals("", request("HEAD", "/app.js", "", "", "").body());
    }

    @Test void secureCookieFlagAndEightHourServerExpiryAreEnforced() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-15T00:00:00Z"));
        WebSecurity security = new WebSecurity(new WebConfig("127.0.0.1", 0, PASSWORD, true), clock);
        server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var auth = server.createContext("/auth", security::auth); auth.getFilters().add(security.filter(false));
        server.start();
        var login = login(PASSWORD);
        String cookie = cookie(login);
        assertTrue(login.headers().firstValue("Set-Cookie").orElseThrow().contains("; Secure"));
        clock.now = clock.now.plusSeconds(8 * 60 * 60 - 1);
        assertTrue(Json.MAPPER.readTree(request("GET", "/auth/session", "", cookie, "").body()).path("authenticated").asBoolean());
        clock.now = clock.now.plusSeconds(1);
        assertFalse(Json.MAPPER.readTree(request("GET", "/auth/session", "", cookie, "").body()).path("authenticated").asBoolean());
    }

    private void start(LlmClient llm, boolean secure) throws IOException {
        start(llm, secure, PASSWORD);
    }

    private void start(LlmClient llm, boolean secure, String password) throws IOException {
        AgentEngine engine = null;
        if (llm != null) {
            Path tools = directory.resolve("synthetic-tools.json");
            var definition = Json.MAPPER.createObjectNode().put("fileRoot", directory.toString()); definition.putArray("commands");
            Files.writeString(tools, definition.toString());
            engine = new AgentEngine(llm, ToolRegistry.load(tools, 2, 12000), "", 8, 24, 120000);
        }
        server = new HttpApi(engine, logs).startWeb(new WebConfig("127.0.0.1", 0, password, secure),
                () -> Json.MAPPER.createObjectNode().put("question_enabled", true).put("source", "synthetic"));
    }

    private HttpResponse<String> login(String password) throws Exception {
        return request("POST", "/auth/login", Json.MAPPER.createObjectNode().put("password", password).toString(), "", "");
    }
    private static String cookie(HttpResponse<String> response) { return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0]; }
    private static void assertCdnAsset(String html, String tag, String attribute, String url, String integrity) {
        var element = java.util.regex.Pattern.compile("<" + tag + "\\b[^>]*" + attribute + "=\""
                + java.util.regex.Pattern.quote(url) + "\"[^>]*>").matcher(html);
        assertTrue(element.find(), url);
        assertTrue(element.group().contains("integrity=\"" + integrity + "\""));
        assertTrue(element.group().contains("crossorigin=\"anonymous\""));
    }
    private String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    private HttpResponse<String> request(String method, String path, String body, String cookie, String origin) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(base() + path)).header("Content-Type", "application/json");
        if (!cookie.isEmpty()) request.header("Cookie", cookie);
        if (!origin.isEmpty()) request.header("Origin", origin);
        return http.send(request.method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static LlmClient.Reply answer() { return new LlmClient.Reply(Json.MAPPER.createObjectNode().put("role", "assistant").put("content", "合成回答"), "stop"); }

    private static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
