package dev.holmes.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpServer;
import dev.holmes.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 新日志接口采用批次作业；不改变原 question API 的协议。 */
public final class LogInvestigationApi {
    private static final String BASE = "/api/log-investigations";
    private final LogInvestigationService service;
    public LogInvestigationApi(LogInvestigationService service) { this.service = service; }
    public HttpContext register(HttpServer server) { return server.createContext(BASE, this::handle); }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.equals(BASE)) {
                if (!method.equals("POST")) { send(exchange, 405, Map.of("error", "请使用 POST 创建日志批次")); return; }
                byte[] body = exchange.getRequestBody().readNBytes(65537);
                if (body.length > 65536) { send(exchange, 413, Map.of("error", "请求体超过 64 KiB")); return; }
                JsonNode input;
                try { input = Json.MAPPER.readTree(body); }
                catch (com.fasterxml.jackson.core.JacksonException e) { send(exchange, 400, Map.of("error", "请求体必须是有效 JSON")); return; }
                send(exchange, 202, service.submit(input)); return;
            }
            if (!path.startsWith(BASE + "/")) { send(exchange, 404, Map.of("error", "接口不存在")); return; }
            String[] parts = path.substring(BASE.length() + 1).split("/", -1);
            if (!(parts.length == 1 || parts.length == 3 && (parts[1].equals("tasks") || parts[1].equals("evidence")))) {
                send(exchange, 404, Map.of("error", "接口不存在")); return;
            }
            if (!method.equals("GET")) { send(exchange, 405, Map.of("error", "请使用 GET 查询日志批次")); return; }
            JsonNode job = service.get(parts[0]);
            if (job == null) { send(exchange, 404, Map.of("error", "批次不存在或已过期")); return; }
            if (parts.length == 1) { send(exchange, 200, job); return; }
            if (!LogRequest.validId(parts[2])) { send(exchange, 400, Map.of("error", "任务或证据引用无效")); return; }
            String field = parts[1].equals("tasks") ? "task_ref" : "evidence_id";
            for (JsonNode node : job.path(parts[1])) {
                if (node.path(field).asText().equals(parts[2])) { send(exchange, 200, node); return; }
            }
            send(exchange, 404, Map.of("error", "该任务或证据不属于本批次，或尚未完成"));
        } catch (TaskSnapshot.Unprocessable e) { send(exchange, 422, Map.of("error", e.getMessage())); }
        catch (IllegalArgumentException e) { send(exchange, 400, Map.of("error", e.getMessage())); }
        catch (LogInvestigationService.Busy e) { send(exchange, 429, Map.of("error", e.getMessage())); }
        catch (JobStore.StorageLimit e) { send(exchange, 503, Map.of("error", e.getMessage())); }
        catch (IOException e) { send(exchange, 503, Map.of("error", "日志批次存储或任务快照不可用")); }
        catch (RuntimeException e) { send(exchange, 500, Map.of("error", "日志批次处理失败")); }
        finally { exchange.close(); }
    }

    private static void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = Json.MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
