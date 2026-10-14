package dev.holmes;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import dev.holmes.tools.ToolRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HttpApiTest {
    @TempDir Path directory;
    private HttpServer server;
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach void stopServer() { if (server != null) server.stop(0); }

    @Test void healthIsLocalAndRequiresExactGetPath() throws Exception {
        start((messages, tools) -> answer("完成"));
        assertTrue(server.getAddress().getAddress().isLoopbackAddress());

        var health = request("GET", "/health", "");

        assertEquals(200, health.statusCode());
        assertEquals("ok", Json.MAPPER.readTree(health.body()).path("status").asText());
        assertEquals("no-store", health.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(health.headers().firstValue("Content-Type").orElseThrow().contains("application/json"));
        assertEquals(405, request("POST", "/health", "{}").statusCode());
        assertEquals(404, request("GET", "/health/extra", "").statusCode());
    }

    @Test void malformedAndOversizedRequestsDoNotInvokeLlm() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        start((messages, tools) -> { calls.incrementAndGet(); return answer("完成"); });

        for (String body : List.of("not-json", "null", "[]", "{}", "{\"question\":3}", "{\"question\":\"  \"}")) {
            assertEquals(400, request("POST", "/api/investigate", body).statusCode(), body);
        }
        assertEquals(413, request("POST", "/api/investigate", "x".repeat(65537)).statusCode());
        assertEquals(405, request("GET", "/api/investigate", "").statusCode());
        assertEquals(404, request("POST", "/api/investigate/extra", "{}").statusCode());
        assertEquals(0, calls.get());
    }

    @Test void successfulInvestigationsHaveIndependentHistory() throws Exception {
        List<String> questions = new ArrayList<>();
        start((messages, tools) -> {
            assertEquals(2, messages.size());
            questions.add(messages.get(1).path("content").asText());
            return answer("结论：" + questions.getLast());
        });

        var first = request("POST", "/api/investigate", "{\"question\":\"问题一\"}");
        var second = request("POST", "/api/investigate", "{\"question\":\"问题二\"}");

        assertEquals(200, first.statusCode());
        assertEquals(200, second.statusCode());
        assertEquals(List.of("问题一", "问题二"), questions);
        var body = Json.MAPPER.readTree(second.body());
        assertEquals("结论：问题二", body.path("answer").asText());
        assertEquals("completed", body.path("stopReason").asText());
        assertEquals(1, body.path("steps").asInt());
        assertEquals(0, body.path("toolCalls").asInt());
        assertTrue(body.path("trace").isArray());
    }

    @Test void llmFailureReturnsGatewayErrorWithoutSensitiveDetails() throws Exception {
        start((messages, tools) -> { throw new IOException("private-key and confidential evidence"); });

        var response = request("POST", "/api/investigate", "{\"question\":\"检查日志\"}");

        assertEquals(502, response.statusCode());
        assertTrue(Json.MAPPER.readTree(response.body()).has("error"));
        assertFalse(response.body().contains("private-key"));
        assertFalse(response.body().contains("confidential evidence"));
    }

    private void start(LlmClient llm) throws IOException {
        Path config = directory.resolve("tools.json");
        ObjectNode definition = Json.MAPPER.createObjectNode().put("fileRoot", directory.toString());
        definition.putArray("commands");
        Files.writeString(config, definition.toString());
        var engine = new AgentEngine(llm, ToolRegistry.load(config, 2, 12000), "", 8, 24, 120000);
        server = new HttpApi(engine).start(0);
    }

    private HttpResponse<String> request(String method, String path, String body) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
                .header("Content-Type", "application/json")
                .method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static LlmClient.Reply answer(String text) {
        return new LlmClient.Reply(Json.MAPPER.createObjectNode().put("role", "assistant").put("content", text), "stop");
    }
}
