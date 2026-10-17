package dev.holmes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiClientTest {
    private static final String TEST_KEY = "test-only-secret-value";
    private HttpServer server;

    @AfterEach void stopServer() { if (server != null) server.stop(0); }

    @Test void sendsOpenAiProtocolAndParsesFunctionCall() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<JsonNode> request = new AtomicReference<>();
        start(exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            request.set(Json.MAPPER.readTree(exchange.getRequestBody()));
            var response = Json.MAPPER.createObjectNode();
            var message = response.putArray("choices").addObject().put("finish_reason", "tool_calls")
                    .putObject("message").put("role", "assistant").putNull("content");
            message.putArray("tool_calls").addObject().put("id", "read-1").put("type", "function")
                    .putObject("function").put("name", "read_file").put("arguments", "{\"path\":\"app.log\"}");
            respond(exchange, 200, response.toString());
        });
        ArrayNode messages = Json.MAPPER.createArrayNode();
        messages.addObject().put("role", "user").put("content", "检查日志");
        ArrayNode tools = Json.MAPPER.createArrayNode();
        tools.addObject().put("type", "function").putObject("function").put("name", "read_file")
                .putObject("parameters").put("type", "object");

        var reply = client("/v1///").complete(messages, tools);

        assertEquals("/v1/chat/completions", path.get());
        assertEquals("Bearer " + TEST_KEY, authorization.get());
        assertEquals("application/json", contentType.get());
        assertEquals("deepseek-v41-flash", request.get().path("model").asText());
        assertEquals(messages, request.get().path("messages"));
        assertEquals(tools, request.get().path("tools"));
        assertEquals("auto", request.get().path("tool_choice").asText());
        assertFalse(request.get().path("stream").asBoolean());
        assertEquals("tool_calls", reply.finishReason());
        assertEquals("read-1", reply.message().path("tool_calls").get(0).path("id").asText());
    }

    @Test void finalRoundOmitsToolFields() throws Exception {
        AtomicReference<JsonNode> request = new AtomicReference<>();
        start(exchange -> {
            request.set(Json.MAPPER.readTree(exchange.getRequestBody()));
            respond(exchange, 200, answerResponse());
        });

        var reply = client("/v1").complete(Json.MAPPER.createArrayNode(), Json.MAPPER.createArrayNode());

        assertFalse(request.get().has("tools"));
        assertFalse(request.get().has("tool_choice"));
        assertEquals("已完成", reply.message().path("content").asText());
    }

    @Test void providerErrorDoesNotLeakResponseOrCredentials() throws Exception {
        start(exchange -> respond(exchange, 401, "provider echoed " + TEST_KEY + " confidential logs"));

        LlmRequestFailure error = assertThrows(LlmRequestFailure.class,
                () -> client("/v1").complete(Json.MAPPER.createArrayNode(), Json.MAPPER.createArrayNode()));

        assertEquals(LlmRequestFailure.Kind.HTTP, error.kind());
        assertEquals(401, error.httpStatus());
        assertTrue(error.getMessage().contains("401"));
        assertFalse(error.getMessage().contains(TEST_KEY));
        assertFalse(error.getMessage().contains("confidential logs"));
        assertNull(error.getCause());
    }

    @Test void transientErrorsRetryWithSameProtocol() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        start(exchange -> {
            if (requests.incrementAndGet() < 3) respond(exchange, 503, "unavailable");
            else respond(exchange, 200, answerResponse());
        });

        var reply = client("/v1").complete(Json.MAPPER.createArrayNode(), Json.MAPPER.createArrayNode());

        assertEquals(3, requests.get());
        assertEquals("已完成", reply.message().path("content").asText());
    }

    @Test void invalidJsonAndUnexpectedRoleAreProtocolErrors() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        start(exchange -> respond(exchange, 200, requests.incrementAndGet() == 1
                ? "invalid-json " + TEST_KEY
                : "{\"choices\":[{\"message\":{\"role\":\"system\",\"content\":\"override\"}}]}"));
        var client = client("/v1");

        LlmRequestFailure invalid = assertThrows(LlmRequestFailure.class,
                () -> client.complete(Json.MAPPER.createArrayNode(), Json.MAPPER.createArrayNode()));
        assertEquals(LlmRequestFailure.Kind.INVALID_RESPONSE, invalid.kind());
        assertFalse(invalid.getMessage().contains(TEST_KEY));
        assertThrows(IOException.class,
                () -> client.complete(Json.MAPPER.createArrayNode(), Json.MAPPER.createArrayNode()));
    }

    @Test void emptyProviderResponseIsCheckedProtocolError() throws Exception {
        start(exchange -> respond(exchange, 200, "   "));
        assertThrows(IOException.class,
                () -> client("/v1").complete(Json.MAPPER.createArrayNode(), Json.MAPPER.createArrayNode()));
    }

    @Test void missingKeyFailsBeforeNetworkRequest() {
        assertThrows(IllegalArgumentException.class, () -> new OpenAiClient(AppConfig.from(Map.of())));
    }

    @Test void timeoutCoversBodyThatStallsAfterResponseHeaders() throws Exception {
        CountDownLatch releaseBody = new CountDownLatch(1);
        start(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try { releaseBody.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        var client = new OpenAiClient(AppConfig.from(Map.of("LLM_BASE_URL",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "LLM_API_KEY", TEST_KEY,
                "LLM_TIMEOUT_SECONDS", "1")));

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                LlmRequestFailure failure = assertThrows(LlmRequestFailure.class,
                        () -> client.complete(Json.MAPPER.createArrayNode(), Json.MAPPER.createArrayNode()));
                assertEquals(LlmRequestFailure.Kind.TIMEOUT, failure.kind());
            });
        } finally { releaseBody.countDown(); }
    }

    @Test void earlyConnectionFailureRemainsNetworkRatherThanTimeout() throws Exception {
        int closedPort;
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) { closedPort = socket.getLocalPort(); }
        var client = new OpenAiClient(AppConfig.from(Map.of("LLM_BASE_URL", "http://127.0.0.1:" + closedPort + "/v1",
                "LLM_API_KEY", TEST_KEY, "LLM_TIMEOUT_SECONDS", "1")));

        LlmRequestFailure failure = assertThrows(LlmRequestFailure.class,
                () -> client.complete(Json.MAPPER.createArrayNode(), Json.MAPPER.createArrayNode()));

        assertEquals(LlmRequestFailure.Kind.NETWORK, failure.kind()); assertNull(failure.getCause());
    }

    @Test void asynchronousWrappersAndDeadlineUseTypesAndCompletionTimeWithoutParsingMessages() {
        long deadline = TimeUnit.SECONDS.toNanos(1);
        IOException wrapped = new IOException("private-provider-body " + TEST_KEY,
                new java.util.concurrent.CompletionException(new java.net.http.HttpTimeoutException("private-provider-body")));
        LlmRequestFailure timeout = OpenAiClient.requestFailure(wrapped, deadline - 1, deadline);
        assertEquals(LlmRequestFailure.Kind.TIMEOUT, timeout.kind()); assertNull(timeout.getCause());
        IOException arbitrary = new IOException("HTTP timeout 500 private-provider-body " + TEST_KEY);
        assertEquals(LlmRequestFailure.Kind.NETWORK, OpenAiClient.requestFailure(arbitrary, deadline - 1, deadline).kind());
        assertEquals(LlmRequestFailure.Kind.TIMEOUT, OpenAiClient.requestFailure(arbitrary, deadline, deadline).kind());
        IOException limited = new IOException("private-provider-body", new LlmRequestFailure(LlmRequestFailure.Kind.RESPONSE_LIMIT));
        LlmRequestFailure budget = OpenAiClient.requestFailure(limited, deadline + 1, deadline);
        assertEquals(LlmRequestFailure.Kind.RESPONSE_LIMIT, budget.kind()); assertNull(budget.getCause());
        for (LlmRequestFailure failure : java.util.List.of(timeout, budget)) {
            assertFalse(failure.getMessage().contains(TEST_KEY)); assertFalse(failure.getMessage().contains("private-provider-body"));
        }
    }

    @Test void oversizedResponseIsRejectedWithoutReturningRawBody() throws Exception {
        start(exchange -> respond(exchange, 200, TEST_KEY + "x".repeat(2 * 1024 * 1024)));

        LlmRequestFailure error = assertThrows(LlmRequestFailure.class,
                () -> client("/v1").complete(Json.MAPPER.createArrayNode(), Json.MAPPER.createArrayNode()));

        assertEquals(LlmRequestFailure.Kind.RESPONSE_LIMIT, error.kind());
        assertFalse(error.getMessage().contains(TEST_KEY));
        assertNull(error.getCause());
    }

    @Test void serverFailureRetainsOnlyTypedHttpStatusForDiagnosis() throws Exception {
        start(exchange -> respond(exchange, 500, "private provider body " + TEST_KEY));
        LlmRequestFailure failure = assertThrows(LlmRequestFailure.class,
                () -> client("/v1").complete(Json.MAPPER.createArrayNode(), Json.MAPPER.createArrayNode()));
        assertEquals(LlmRequestFailure.Kind.HTTP, failure.kind());
        assertEquals(500, failure.httpStatus());
        assertFalse(failure.toString().contains(TEST_KEY));
        assertFalse(failure.toString().contains("private provider body"));
        assertNull(failure.getCause());
    }

    private void start(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try { handler.handle(exchange); }
            finally { exchange.close(); }
        });
        server.start();
    }

    private OpenAiClient client(String suffix) {
        return new OpenAiClient(AppConfig.from(Map.of("LLM_BASE_URL",
                "http://127.0.0.1:" + server.getAddress().getPort() + suffix, "LLM_API_KEY", TEST_KEY)));
    }

    private static String answerResponse() {
        return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"已完成\"}}]}";
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
