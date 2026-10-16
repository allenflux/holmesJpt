package dev.holmes;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.Map;

/** 只提供打包在 classpath 中的白名单网页资源，不能读取工作目录文件。 */
final class WebAssets {
    private static final Map<String, Asset> ASSETS = Map.of(
            "/", new Asset("/web/index.html", "text/html; charset=utf-8"),
            "/app.js", new Asset("/web/app.js", "text/javascript; charset=utf-8"),
            "/styles.css", new Asset("/web/styles.css", "text/css; charset=utf-8"));
    private WebAssets() { }

    static void handle(HttpExchange exchange) throws IOException {
        try {
            Asset asset = ASSETS.get(exchange.getRequestURI().getRawPath());
            if (asset == null) { WebSecurity.send(exchange, 404, Map.of("error", "资源不存在")); return; }
            String method = exchange.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) { WebSecurity.send(exchange, 405, Map.of("error", "请使用 GET")); return; }
            try (var input = WebAssets.class.getResourceAsStream(asset.resource)) {
                if (input == null) { WebSecurity.send(exchange, 404, Map.of("error", "资源不存在")); return; }
                byte[] body = input.readNBytes((2 << 20) + 1);
                if (body.length > 2 << 20) { WebSecurity.send(exchange, 500, Map.of("error", "网页资源不可用")); return; }
                exchange.getResponseHeaders().set("Content-Type", asset.contentType);
                if (method.equals("HEAD")) {
                    exchange.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
                    exchange.sendResponseHeaders(200, -1);
                } else {
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            }
        } finally { exchange.close(); }
    }
    private record Asset(String resource, String contentType) { }
}
