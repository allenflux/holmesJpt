package dev.holmes;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/** 本机 HTTP 入口与 CLI 共用引擎；限制请求体和并发调查数量。 */
public final class HttpApi {
    private final AgentEngine engine;
    private final Semaphore capacity = new Semaphore(4);
    public HttpApi(AgentEngine engine) { this.engine = engine; }

    public HttpServer start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 16);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/health", exchange -> {
            try {
                if (!"/health".equals(exchange.getRequestURI().getPath())) send(exchange, 404, Map.of("error", "接口不存在"));
                else if (!"GET".equals(exchange.getRequestMethod())) send(exchange, 405, Map.of("error", "请使用 GET"));
                else send(exchange, 200, Map.of("status", "ok"));
            } finally { exchange.close(); }
        });
        server.createContext("/api/investigate", this::investigate);
        server.start();
        return server;
    }

    private void investigate(HttpExchange exchange) throws IOException {
        boolean acquired = false;
        try {
            if (!"/api/investigate".equals(exchange.getRequestURI().getPath())) {
                send(exchange, 404, Map.of("error", "接口不存在")); return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, Map.of("error", "请使用 POST")); return;
            }
            if (!(acquired = capacity.tryAcquire())) {
                send(exchange, 429, Map.of("error", "已有 4 个调查在运行，请稍后重试")); return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(65537);
            if (body.length > 65536) { send(exchange, 413, Map.of("error", "请求体超过 64 KiB")); return; }
            com.fasterxml.jackson.databind.JsonNode request;
            try { request = Json.MAPPER.readTree(body); }
            catch (IOException e) { send(exchange, 400, Map.of("error", "请求体必须是有效 JSON")); return; }
            if (request == null || !request.isObject() || !request.path("question").isTextual()) {
                send(exchange, 400, Map.of("error", "请提供字符串字段 question")); return;
            }
            send(exchange, 200, engine.investigate(request.path("question").asText()));
        } catch (IllegalArgumentException e) {
            send(exchange, 400, Map.of("error", e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            send(exchange, 503, Map.of("error", "调查已中断"));
        } catch (IOException e) {
            send(exchange, 502, Map.of("error", "调查失败：LLM 网络、协议或供应商响应异常；请通过 CLI 检查配置"));
        } catch (RuntimeException e) {
            send(exchange, 500, Map.of("error", "调查发生内部错误"));
        } finally {
            if (acquired) capacity.release();
            exchange.close();
        }
    }

    private static void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = Json.MAPPER.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
