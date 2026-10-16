package dev.holmes.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.holmes.Json;
import dev.holmes.logs.LogModels.LogQuery;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GrafanaVictoriaLogsSourceTest {
    private static final Instant FROM = Instant.parse("2026-10-15T00:00:00Z");
    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach void stop() { servers.forEach(server -> server.stop(0)); }

    @Test void searchesAllPodsWithEscapedIdentifiersAndBearerOnConfiguredEndpoint() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        List<String> authorization = new CopyOnWriteArrayList<>();
        URI base = start(exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("/api/ds/query?ds_type=victoriametrics-logs-datasource", exchange.getRequestURI().toString());
            authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            requests.add(Json.MAPPER.readTree(exchange.getRequestBody()));
            reply(exchange, 200, result(frame(List.of("labels", "Line", "Time"),
                    List.of(List.of(Map.of("kubernetes.pod_name", "pod-a", "kubernetes.pod_namespace", "prod"),
                                    "{\"kubernetes.pod_name\":\"pod-b\",\"kubernetes.container_name\":\"worker\",\"task_id\":\"task-1\"}"),
                            List.of("task-1 failure", "task-1 error"),
                            List.of(FROM.plusSeconds(1).toEpochMilli(), FROM.plusSeconds(2).toEpochMilli())))));
        });
        String candidate = "task\" OR * | delete \\ dangerous";

        var fetched = source(base, 10).fetch(new LogQuery("prod", List.of(candidate, "task-1"), FROM, FROM.plusSeconds(10), Map.of()),
                100, 100_000, Instant.now().plusSeconds(5));

        assertTrue(fetched.complete());
        assertEquals(2, fetched.events().size());
        assertEquals(List.of("pod-a", "pod-b"), fetched.events().stream().map(event -> event.labels().get("pod")).toList());
        assertEquals("worker", fetched.events().get(1).labels().get("container"));
        assertEquals("prod", fetched.events().get(1).labels().get("namespace"));
        assertEquals("", fetched.events().get(0).eventId());
        assertEquals(List.of("Bearer fixture-token"), authorization);
        JsonNode definition = requests.getFirst().path("queries").get(0);
        assertEquals("victorialogs", definition.path("datasource").path("uid").asText());
        assertEquals("range", definition.path("queryType").asText());
        assertEquals(10, definition.path("maxLines").asInt());
        String expr = definition.path("expr").asText();
        assertTrue(expr.contains("_stream: {kubernetes.pod_namespace=\"prod\"}"));
        assertFalse(expr.contains("kubernetes.pod_name="));
        assertTrue(expr.contains("*:" + Json.MAPPER.writeValueAsString(candidate)));
        assertTrue(expr.contains("_time:[2026-10-15T00:00:00Z,2026-10-15T00:00:10Z)"));
    }

    @Test void parsesMultipleFramesAndPreservesRealSameTimestampDuplicates() throws Exception {
        JsonNode first = frame(List.of("Time", "Line", "labels"), List.of(
                List.of(FROM.toEpochMilli(), FROM.toEpochMilli()), List.of("task-1 error", "task-1 error"),
                List.of(Map.of("namespace", "prod", "pod", "a"), Map.of("namespace", "prod", "pod", "a"))));
        JsonNode second = frame(List.of("Line", "event_id", "Time", "labels"), List.of(
                List.of("task-1 detail"), List.of("real-id"), List.of(FROM.plusMillis(1).toEpochMilli()),
                List.of("{kubernetes.pod_name=\"b\",kubernetes.pod_namespace=\"prod\"}")));
        URI base = start(exchange -> reply(exchange, 200, result(first, second)));

        var fetched = source(base, 10).fetch(query(), 100, 100_000, Instant.now().plusSeconds(5));

        assertTrue(fetched.complete());
        assertEquals(3, fetched.events().size());
        assertEquals("", fetched.events().getFirst().eventId());
        assertEquals("real-id", fetched.events().getLast().eventId());
        assertEquals("b", fetched.events().getLast().labels().get("pod"));
    }

    @Test void onlyExplicitEmptyFramesOrValidEmptyColumnsProveEmpty() throws Exception {
        for (JsonNode response : List.of(result(), result(frame(List.of("Time", "Line"), List.of(List.of(), List.of()))))) {
            URI base = start(exchange -> reply(exchange, 200, response));
            var fetched = source(base, 10).fetch(query(), 100, 100_000, Instant.now().plusSeconds(5));
            assertTrue(fetched.complete());
            assertTrue(fetched.events().isEmpty());
        }
        for (String body : List.of("{}", "{\"results\":{\"A\":{\"status\":200}}}",
                "{\"results\":{\"A\":{\"frames\":[{\"schema\":{\"fields\":[]},\"data\":{\"values\":[]}}]}}}")) {
            URI base = start(exchange -> reply(exchange, 200, body));
            assertThrows(IOException.class, () -> source(base, 10).fetch(query(), 100, 100_000, Instant.now().plusSeconds(5)));
        }
    }

    @Test void refIdAndHttpErrorsNeverBecomeEmptyOrLeakBody() throws Exception {
        for (String body : List.of("{\"error\":\"secret-token private-data\"}",
                "{\"results\":{\"A\":{\"status\":200,\"error\":\"secret-token private-data\",\"frames\":[]}}}",
                "{\"results\":{\"A\":{\"status\":200,\"errors\":[\"secret-token private-data\"],\"frames\":[]}}}",
                "{\"results\":{\"A\":{\"status\":502,\"frames\":[]}}}", "secret-token private-data")) {
            URI base = start(exchange -> reply(exchange, 200, body));
            IOException error = assertThrows(IOException.class,
                    () -> source(base, 10).fetch(query(), 100, 100_000, Instant.now().plusSeconds(5)));
            assertFalse(error.toString().contains("secret-token"));
            assertFalse(error.toString().contains("private-data"));
            assertNull(error.getCause());
        }
        URI base = start(exchange -> reply(exchange, 401, "secret-token private-data"));
        IOException error = assertThrows(IOException.class,
                () -> source(base, 10).fetch(query(), 100, 100_000, Instant.now().plusSeconds(5)));
        assertTrue(error.getMessage().contains("401"));
        assertFalse(error.toString().contains("private-data"));
    }

    @Test void forbiddenWithSensitiveBodyReportsHttpStatusBeforeAnyResponseLimit() throws Exception {
        URI base = start(exchange -> reply(exchange, 403, "secret-token private-data " + "x".repeat(10_000)));

        IOException error = assertThrows(IOException.class,
                () -> source(base, 10).fetch(query(), 100, 32, Instant.now().plusSeconds(5)));

        assertTrue(error.getMessage().contains("Grafana"));
        assertTrue(error.getMessage().contains("HTTP 403"));
        assertFalse(error.getMessage().contains("上限"));
        assertFalse(error.toString().contains("secret-token"));
        assertFalse(error.toString().contains("private-data"));
        assertFalse(error.toString().contains("fixture-token"));
        assertNull(error.getCause());
    }

    @Test void fullPagesSplitTimeWindowsWithoutAddingParentOrDroppingBoundary() throws Exception {
        List<Long> offsets = List.of(1L, 5L, 9L);
        AtomicInteger count = new AtomicInteger();
        List<Integer> responseSizes = new CopyOnWriteArrayList<>();
        URI base = start(exchange -> {
            count.incrementAndGet();
            JsonNode request = Json.MAPPER.readTree(exchange.getRequestBody());
            long from = Long.parseLong(request.path("from").asText());
            long to = Long.parseLong(request.path("to").asText());
            List<Long> matched = offsets.stream().map(offset -> FROM.toEpochMilli() + offset)
                    .filter(time -> time >= from && time < to).sorted(java.util.Comparator.reverseOrder()).limit(2).toList();
            JsonNode response = result(frame(List.of("Time", "Line", "labels"), List.of(matched,
                    matched.stream().map(time -> "task-1 " + time).toList(), matched.stream().map(time -> Map.of("namespace", "prod")).toList())));
            responseSizes.add(Json.MAPPER.writeValueAsBytes(response).length);
            reply(exchange, 200, response);
        });

        var fetched = source(base, 2).fetch(new LogQuery("prod", List.of("task-1"), FROM, FROM.plusMillis(10), Map.of()),
                100, 100_000, Instant.now().plusSeconds(5));

        assertTrue(fetched.complete(), fetched.limitations().toString());
        assertTrue(count.get() > 1);
        assertEquals(3, fetched.events().size());
        assertEquals(offsets, fetched.events().stream().map(event -> event.time().toEpochMilli() - FROM.toEpochMilli()).toList());
        assertEquals(responseSizes.stream().mapToLong(Integer::longValue).sum(), fetched.responseBytes());
    }

    @Test void sameMillisecondSaturationIsPartialAndPreservesDuplicateRows() throws Exception {
        AtomicInteger count = new AtomicInteger();
        URI base = start(exchange -> {
            count.incrementAndGet();
            reply(exchange, 200, result(frame(List.of("Time", "Line"), List.of(
                    List.of(FROM.toEpochMilli(), FROM.toEpochMilli()), List.of("task-1 error", "task-1 error")))));
        });

        var fetched = source(base, 2).fetch(new LogQuery("prod", List.of("task-1"), FROM, FROM.plusMillis(1), Map.of()),
                100, 100_000, Instant.now().plusSeconds(5));

        assertFalse(fetched.complete());
        assertEquals(2, fetched.events().size());
        assertEquals(1, count.get());
        assertTrue(fetched.limitations().toString().contains("同一毫秒"));
    }

    @Test void oddMillisecondWindowsNeverDropSubmillisecondRowsRoundedByGrafana() throws Exception {
        List<Instant> actualTimes = List.of(FROM.plusNanos(200_000), FROM.plusNanos(1_800_000), FROM.plusNanos(2_800_000));
        List<Instant> boundaries = new CopyOnWriteArrayList<>();
        URI base = start(exchange -> {
            JsonNode request = Json.MAPPER.readTree(exchange.getRequestBody());
            String expr = request.path("queries").get(0).path("expr").asText();
            var range = java.util.regex.Pattern.compile("_time:\\[([^,]+),([^\\)]+)\\)").matcher(expr);
            assertTrue(range.find());
            Instant from = Instant.parse(range.group(1)), to = Instant.parse(range.group(2));
            boundaries.add(from); boundaries.add(to);
            // 后端按真实纳秒匹配；Grafana 帧中的 Time 丢掉小于毫秒的部分。
            List<Instant> matched = actualTimes.stream().filter(time -> !time.isBefore(from) && time.isBefore(to))
                    .sorted(java.util.Comparator.reverseOrder()).limit(2).toList();
            reply(exchange, 200, result(frame(List.of("Time", "Line"), List.of(
                    matched.stream().map(Instant::toEpochMilli).toList(), matched.stream().map(time -> "task-1 " + time).toList()))));
        });

        var fetched = source(base, 2).fetch(new LogQuery("prod", List.of("task-1"), FROM, FROM.plusMillis(3), Map.of()),
                100, 100_000, Instant.now().plusSeconds(5));

        assertTrue(fetched.complete(), fetched.limitations().toString());
        assertEquals(3, fetched.events().size());
        assertEquals(List.of(0L, 1L, 2L), fetched.events().stream().map(event -> event.time().toEpochMilli() - FROM.toEpochMilli()).toList());
        assertTrue(boundaries.stream().allMatch(time -> time.getNano() % 1_000_000 == 0));
    }

    @Test void submillisecondOuterBoundariesPreserveCandidatesAndCannotClaimComplete() throws Exception {
        List<String> expressions = new CopyOnWriteArrayList<>();
        URI base = start(exchange -> {
            expressions.add(Json.MAPPER.readTree(exchange.getRequestBody()).path("queries").get(0).path("expr").asText());
            reply(exchange, 200, result(frame(List.of("Time", "Line"), List.of(
                    List.of(FROM.toEpochMilli(), FROM.plusMillis(1).toEpochMilli()),
                    List.of("task-1 actual 0.8ms", "task-1 actual 1.2ms")))));
        });

        var fetched = source(base, 10).fetch(new LogQuery("prod", List.of("task-1"), FROM.plusNanos(500_000), FROM.plusNanos(1_500_000), Map.of()),
                100, 100_000, Instant.now().plusSeconds(5));

        assertFalse(fetched.complete());
        assertEquals(2, fetched.events().size());
        assertTrue(fetched.limitations().toString().contains("子毫秒"));
        assertTrue(expressions.getFirst().contains("_time:[2026-10-15T00:00:00Z,2026-10-15T00:00:00.002Z)"));
    }

    @Test void metadataTruncationBelowRequestedPageSizeStillSplits() throws Exception {
        AtomicInteger count = new AtomicInteger();
        URI base = start(exchange -> {
            JsonNode request = Json.MAPPER.readTree(exchange.getRequestBody());
            long from = Long.parseLong(request.path("from").asText());
            long to = Long.parseLong(request.path("to").asText());
            JsonNode frame = frame(List.of("Time", "Line"), List.of(List.of(from), List.of("task-1 error")));
            if (to - from > 1) ((ObjectNode) frame.path("schema")).putObject("meta").putObject("custom").put("truncated", true);
            count.incrementAndGet();
            reply(exchange, 200, result(frame));
        });

        var fetched = source(base, 10).fetch(new LogQuery("prod", List.of("task-1"), FROM, FROM.plusMillis(2), Map.of()),
                100, 100_000, Instant.now().plusSeconds(5));

        assertTrue(fetched.complete());
        assertEquals(3, count.get());
        assertEquals(2, fetched.events().size());
    }

    @Test void boundedResponseEventsAndExpiredDeadlineReturnPartial() throws Exception {
        AtomicInteger count = new AtomicInteger();
        URI base = start(exchange -> {
            count.incrementAndGet();
            reply(exchange, 200, result(frame(List.of("Time", "Line"), List.of(
                    List.of(FROM.toEpochMilli(), FROM.plusMillis(1).toEpochMilli()), List.of("task-1 error", "task-1 " + "x".repeat(8192))))));
        });
        var tooLarge = source(base, 10).fetch(query(), 100, 512, Instant.now().plusSeconds(5));
        assertFalse(tooLarge.complete());
        assertTrue(tooLarge.events().isEmpty());
        assertEquals(512, tooLarge.responseBytes());
        var tooMany = source(base, 10).fetch(query(), 1, 100_000, Instant.now().plusSeconds(5));
        assertFalse(tooMany.complete());
        assertEquals(1, tooMany.events().size());
        int before = count.get();
        var expired = source(base, 10).fetch(query(), 100, 100_000, Instant.now().minusMillis(1));
        assertFalse(expired.complete());
        assertEquals(0, expired.responseBytes());
        assertEquals(before, count.get());
    }

    @Test void oversizedUtf8MessageIsPartialAndFollowingSmallRowIsRetained() throws Exception {
        String large = "task-1 " + "界".repeat(90_000);
        assertTrue(large.length() < 256 * 1024);
        assertTrue(large.getBytes(StandardCharsets.UTF_8).length > 256 * 1024);
        URI base = start(exchange -> reply(exchange, 200, result(frame(List.of("Time", "Line"), List.of(
                List.of(FROM.toEpochMilli(), FROM.plusMillis(1).toEpochMilli()), List.of(large, "task-1 small row"))))));

        var fetched = source(base, 10).fetch(query(), 100, 1_000_000, Instant.now().plusSeconds(5));

        assertFalse(fetched.complete());
        assertEquals(List.of("task-1 small row"), fetched.events().stream().map(LogModels.LogEvent::message).toList());
        assertTrue(fetched.limitations().toString().contains("256 KiB"));
    }

    @Test void deadlineCoversSlowResponseBodyAndRedirectIsNeverFollowed() throws Exception {
        URI slow = start(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        Instant started = Instant.now();
        var timedOut = source(slow, 10).fetch(query(), 100, 100_000, Instant.now().plusMillis(100));
        assertFalse(timedOut.complete());
        assertTrue(Duration.between(started, Instant.now()).toMillis() < 1000);
        assertEquals(1, timedOut.responseBytes());

        AtomicInteger redirected = new AtomicInteger();
        URI other = start(exchange -> { redirected.incrementAndGet(); reply(exchange, 200, result()); });
        URI base = start(exchange -> {
            exchange.getResponseHeaders().set("Location", other.resolve("/api/ds/query").toString());
            reply(exchange, 302, "redirect");
        });
        IOException error = assertThrows(IOException.class,
                () -> source(base, 10).fetch(query(), 100, 100_000, Instant.now().plusSeconds(5)));
        assertTrue(error.getMessage().contains("302"));
        assertEquals(0, redirected.get());
    }

    @Test void contextIsExplicitAndArbitraryFiltersAreRejected() throws Exception {
        List<String> expressions = new CopyOnWriteArrayList<>();
        URI base = start(exchange -> {
            expressions.add(Json.MAPPER.readTree(exchange.getRequestBody()).path("queries").get(0).path("expr").asText());
            reply(exchange, 200, result());
        });
        var source = source(base, 10);
        source.fetch(new LogQuery("prod", List.of(), FROM, FROM.plusSeconds(1), Map.of("pod", "worker\"x", "container", "main")),
                10, 100_000, Instant.now().plusSeconds(5));
        assertTrue(expressions.getFirst().contains("kubernetes.pod_name=\"worker\\\"x\""));
        assertTrue(expressions.getFirst().contains("kubernetes.container_name=\"main\""));
        assertThrows(IllegalArgumentException.class, () -> source.fetch(new LogQuery("prod", List.of(), FROM, FROM.plusSeconds(1), Map.of()),
                10, 100_000, Instant.now().plusSeconds(5)));
        assertThrows(IllegalArgumentException.class, () -> source.fetch(new LogQuery("prod", List.of("task-1"), FROM, FROM.plusSeconds(1), Map.of("expr", "*")),
                10, 100_000, Instant.now().plusSeconds(5)));
        assertEquals(1, expressions.size());
    }

    @Test void oneSourceLimitsConcurrentRequestsAcrossBatches() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        URI base = start(exchange -> {
            int now = active.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try { Thread.sleep(75); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { active.decrementAndGet(); }
            reply(exchange, 200, result());
        });
        var source = source(base, 10);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<LogModels.FetchResult>> pending = new ArrayList<>();
            for (int i = 0; i < 8; i++) pending.add(executor.submit(() -> source.fetch(query(), 100, 100_000, Instant.now().plusSeconds(5))));
            for (var future : pending) assertTrue(future.get().complete());
        }
        assertTrue(peak.get() <= 4);
        assertTrue(peak.get() > 1);
    }

    @Test void unknownImplicitLimitFromMetadataAndInvalidLabelsCannotClaimComplete() throws Exception {
        ObjectNode frame = (ObjectNode) frame(List.of("Time", "Line"), List.of(List.of(FROM.toEpochMilli()), List.of("task-1 error")));
        frame.withObject("schema").putObject("meta").putObject("custom").put("limit", 1);
        URI limited = start(exchange -> reply(exchange, 200, result(frame)));
        var fetched = source(limited, 100).fetch(new LogQuery("prod", List.of("task-1"), FROM, FROM.plusMillis(1), Map.of()),
                100, 100_000, Instant.now().plusSeconds(5));
        assertFalse(fetched.complete());
        URI invalidLabels = start(exchange -> reply(exchange, 200, result(frame(List.of("Time", "Line", "labels"),
                List.of(List.of(FROM.toEpochMilli()), List.of("task-1 error"), List.of("{private_secret=\"invalid\\q\"}"))))));
        IOException error = assertThrows(IOException.class,
                () -> source(invalidLabels, 10).fetch(query(), 100, 100_000, Instant.now().plusSeconds(5)));
        assertFalse(error.toString().contains("private_secret"));
        assertNull(error.getCause());
    }

    private static GrafanaVictoriaLogsSource source(URI base, int size) {
        return new GrafanaVictoriaLogsSource(base, "fixture-token", "victorialogs", size, Duration.ofSeconds(3));
    }

    private static LogQuery query() { return new LogQuery("prod", List.of("task-1"), FROM, FROM.plusSeconds(10), Map.of()); }

    private URI start(Handler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try { handler.handle(exchange); }
            catch (Throwable e) { exchange.close(); throw e; }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start(); servers.add(server);
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static JsonNode frame(List<String> names, List<? extends List<?>> columns) {
        ObjectNode frame = Json.MAPPER.createObjectNode();
        ArrayNode fields = frame.putObject("schema").putArray("fields");
        for (String name : names) fields.addObject().put("name", name);
        frame.putObject("data").set("values", Json.MAPPER.valueToTree(columns));
        return frame;
    }

    private static JsonNode result(JsonNode... frames) {
        ObjectNode response = Json.MAPPER.createObjectNode();
        ArrayNode array = response.putObject("results").putObject("A").put("status", 200).putArray("frames");
        for (JsonNode frame : frames) array.add(frame);
        return response;
    }

    private static void reply(HttpExchange exchange, int code, JsonNode body) throws IOException { reply(exchange, code, body.toString()); }
    private static void reply(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface private interface Handler { void handle(HttpExchange exchange) throws IOException; }
}
