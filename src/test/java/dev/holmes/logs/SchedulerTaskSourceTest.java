package dev.holmes.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.holmes.Json;
import dev.holmes.logs.LogModels.AssociationScope;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SchedulerTaskSourceTest {
    private static final Instant FROM = Instant.parse("2026-10-14T16:00:00Z");
    private static final Instant TO = FROM.plusSeconds(86400);
    private final List<HttpServer> servers = new ArrayList<>();
    private final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach void stop() {
        servers.forEach(server -> server.stop(0));
        executors.forEach(ExecutorService::shutdownNow);
    }

    @Test void readsEveryPageWithoutAssumingCreatedOrderAndMarksTaskOnlyEvidence() throws Exception {
        List<String> methods = new CopyOnWriteArrayList<>(), headers = new CopyOnWriteArrayList<>(), queries = new CopyOnWriteArrayList<>();
        URI base = start(exchange -> {
            methods.add(exchange.getRequestMethod()); headers.add(exchange.getRequestHeaders().getFirst("Authorization"));
            queries.add(exchange.getRequestURI().getRawQuery());
            int page = page(exchange);
            List<ObjectNode> rows = switch (page) {
                case 1 -> List.of(row("old-failed", "r1", -1, FROM.plusSeconds(1)), row("pending", "r2", 0, FROM.plusSeconds(2)));
                case 2 -> List.of(row("running", "r3", 1, FROM.plusSeconds(3)), row("success", "r4", 2, FROM.plusSeconds(4)));
                default -> List.of(row("watermark", "r5", 3, FROM.plusSeconds(5)), row("new-failed", "r6", -1, FROM.plusSeconds(6)));
            };
            reply(exchange, 200, response(page, 2, 6, rows));
        });

        TaskSnapshot.Selection selected = source(base, 2, 20).select(request(1));

        assertEquals(List.of("new-failed"), selected.tasks().stream().map(LogModels.Task::taskId).toList());
        var task = selected.tasks().getFirst();
        assertEquals(AssociationScope.TASK_ONLY, task.associationScope());
        assertNull(task.failedAt()); assertTrue(task.attempts().isEmpty()); assertEquals("", task.finalAttemptId());
        assertEquals("r6", task.schedulerFacts().recordId());
        assertEquals("", task.schedulerFacts().taskType()); assertEquals("", task.schedulerFacts().errorCode());
        assertTrue(selected.limitations().containsAll(List.of("TASK_STATUS_FROM_SCHEDULER_API", "TASK_ATTEMPT_UNVERIFIED",
                "SCHEDULER_CREATED_TIME_SELECTION", "SCHEDULER_NON_ATOMIC_PAGINATION")));
        assertFalse(selected.limitations().contains("TASK_SELECTION_INCOMPLETE"));
        assertEquals(3, methods.size()); assertTrue(methods.stream().allMatch("GET"::equals));
        assertTrue(headers.stream().allMatch("Bearer synthetic-token"::equals));
        assertTrue(queries.stream().allMatch(q -> q.contains("start_date=2026-10-15&end_date=2026-10-15")));
    }

    @Test void selectsByHalfOpenCreatedTimeInConfiguredTimezoneAndNeverUsesUpdatedAsFailureTime() throws Exception {
        URI base = start(exchange -> reply(exchange, 200, response(1, 4, 4, List.of(
                row("at-from", "r1", -1, FROM).put("created_at", "2026-10-15 00:00:00"),
                row("at-to", "r2", -1, TO), row("before", "r3", -1, FROM.minusMillis(1)),
                row("inside", "r4", -1, TO.minusMillis(1)).put("updated_at", TO.plusSeconds(120).toString())))));

        var selected = source(base, 4, 20).select(request(20));

        assertEquals(List.of("inside", "at-from"), selected.tasks().stream().map(LogModels.Task::taskId).toList());
        assertTrue(selected.tasks().stream().allMatch(task -> task.failedAt() == null));
        assertEquals(FROM, selected.tasks().get(1).schedulerFacts().createdAt());
    }

    @Test void preservesOnlyWhitelistedSourceFactsWithoutInventingExecutionOrFailureTime() throws Exception {
        Instant created = FROM.plusSeconds(1), updated = TO.plusSeconds(120);
        ObjectNode row = row("failed", "source-record", -1, created).put("task_type", "generate_video")
                .put("error_code", "FAILED").put("updated_at", updated.toString());
        row.putObject("payload").put("prompt", "raw-private-prompt").put("url", "https://private.invalid/raw");
        row.put("prompt_id", "not-a-verified-prompt").put("instance_id", "not-a-verified-instance");
        URI base = start(exchange -> reply(exchange, 200, response(1, 1, 1, List.of(row))));

        var task = source(base, 1, 20).select(request(1)).tasks().getFirst();

        assertEquals(new LogModels.SchedulerFacts("source-record", "generate_video", "FAILED", created, updated), task.schedulerFacts());
        assertNull(task.failedAt()); assertNull(task.finalAttempt()); assertTrue(task.attempts().isEmpty());
        assertEquals(AssociationScope.TASK_ONLY, task.associationScope());
        ObjectNode stored = TaskSnapshot.schedulerFactsJson(task.schedulerFacts());
        assertEquals(5, stored.size()); assertFalse(stored.toString().contains("raw-private-prompt"));
        assertFalse(stored.toString().contains("private.invalid")); assertFalse(stored.toString().contains("not-a-verified"));
    }

    @Test void mergedTaskKeepsFactsFromNewestObservedSourceRecord() throws Exception {
        URI base = start(exchange -> {
            int page = page(exchange);
            ObjectNode row = row("failed", page == 1 ? "old-record" : "latest-record", -1, FROM.plusSeconds(1))
                    .put("task_type", page == 1 ? "old_type" : "new_type").put("error_code", page == 1 ? "FAILED" : "TIMEOUT")
                    .put("updated_at", FROM.plusSeconds(page == 1 ? 2 : 20).toString());
            reply(exchange, 200, response(page, 1, 2, List.of(row)));
        });

        var selection = source(base, 1, 20).select(request(1));

        var facts = selection.tasks().getFirst().schedulerFacts();
        assertEquals("latest-record", facts.recordId()); assertEquals("new_type", facts.taskType()); assertEquals("TIMEOUT", facts.errorCode());
        assertEquals(FROM.plusSeconds(20), facts.updatedAt()); assertNull(selection.tasks().getFirst().failedAt());
        assertTrue(selection.limitations().contains("SCHEDULER_TASK_RECORDS_MERGED"));
    }

    @Test void malformedOrConflictingSourceFactsCannotSilentlyBecomeSelectedMetadata() throws Exception {
        ObjectNode malformed = row("bad", "bad-record", -1, FROM.plusSeconds(1)); malformed.putObject("task_type").put("private", "secret");
        URI base = start(exchange -> reply(exchange, 200, response(1, 4, 4, List.of(malformed,
                row("conflict", "c1", -1, FROM.plusSeconds(2)).put("error_code", "FAILED"),
                row("conflict", "c2", -1, FROM.plusSeconds(2)).put("error_code", "TIMEOUT"),
                row("good", "good-record", -1, FROM.plusSeconds(3)).putNull("task_type").putNull("error_code")))));

        var selection = source(base, 4, 20).select(request(20));

        assertEquals(List.of("good"), selection.tasks().stream().map(LogModels.Task::taskId).toList());
        assertTrue(selection.limitations().containsAll(List.of("SCHEDULER_INVALID_TASK_RECORD", "SCHEDULER_TASK_STATE_CONFLICT", "TASK_SELECTION_INCOMPLETE")));
        assertEquals("", selection.tasks().getFirst().schedulerFacts().taskType());
    }

    @Test void mergesLatestStateAcrossRecordsAndExcludesTaskThatLaterSucceeded() throws Exception {
        URI base = start(exchange -> {
            int page = page(exchange);
            var rows = page == 1 ? List.of(row("retried", "r1", -1, FROM.plusSeconds(1)), row("still-failed", "r2", -1, FROM.plusSeconds(2)))
                    : List.of(row("retried", "r3", 2, FROM.plusSeconds(1)).put("updated_at", FROM.plusSeconds(20).toString()),
                    row("still-failed", "r4", -1, FROM.plusSeconds(2)).put("updated_at", FROM.plusSeconds(21).toString()));
            reply(exchange, 200, response(page, 2, 4, rows));
        });

        var selected = source(base, 2, 20).select(request(20));

        assertEquals(List.of("still-failed"), selected.tasks().stream().map(LogModels.Task::taskId).toList());
        assertTrue(selected.limitations().contains("SCHEDULER_TASK_RECORDS_MERGED"));
        assertFalse(selected.limitations().contains("TASK_SELECTION_INCOMPLETE"));
    }

    @Test void dynamicTotalsAndDuplicatePagesReturnFlaggedSubsetRatherThanFailingEveryScan() throws Exception {
        URI base = start(exchange -> {
            int page = page(exchange);
            reply(exchange, 200, response(page, 2, page == 1 ? 4 : 5,
                    List.of(row("failed", "r1", -1, FROM.plusSeconds(1)), row("pending", "r2", 0, FROM.plusSeconds(2)))));
        });

        var selected = source(base, 2, 20).select(request(20));

        assertEquals(1, selected.tasks().size());
        assertTrue(selected.limitations().containsAll(List.of("SCHEDULER_TOTAL_CHANGED", "SCHEDULER_DUPLICATE_RECORD", "TASK_SELECTION_INCOMPLETE")));
    }

    @Test void mismatchedUuidAndTaskIdCannotFabricateEvidenceAndValidRowsSurvive() throws Exception {
        URI base = start(exchange -> reply(exchange, 200, response(1, 3, 3, List.of(
                row("bad", "r1", -1, FROM.plusSeconds(1)).put("uuid", "different"),
                row("good", "r2", -1, FROM.plusSeconds(2)),
                row("bad", "r3", -1, FROM.plusSeconds(3))))));

        var selected = source(base, 3, 20).select(request(20));

        assertEquals(List.of("good"), selected.tasks().stream().map(LogModels.Task::taskId).toList());
        assertTrue(selected.limitations().containsAll(List.of("SCHEDULER_INVALID_TASK_RECORD", "TASK_SELECTION_INCOMPLETE")));
    }

    @Test void explicitIdsMustBeObservedFailedTasksWithinScope() throws Exception {
        URI base = start(exchange -> reply(exchange, 200, response(1, 2, 2, List.of(
                row("failed", "r1", -1, FROM.plusSeconds(1)), row("success", "r2", 2, FROM.plusSeconds(2))))));
        var source = source(base, 2, 20);

        var selected = source.select(explicit("failed"));

        assertEquals("failed", selected.tasks().getFirst().taskId());
        for (String unavailable : List.of("unknown", "success")) {
            IOException failure = assertThrows(IOException.class, () -> source.select(explicit(unavailable)));
            assertEquals("SCHEDULER_REQUESTED_TASK_UNAVAILABLE", failure.getMessage());
            assertFalse(failure.toString().contains(unavailable));
        }
    }

    @Test void scanBudgetNeverReadsBeyondTaskCapEvenWhenTotalCouldOverflowPageArithmetic() throws Exception {
        AtomicInteger count = new AtomicInteger();
        List<String> queries = new CopyOnWriteArrayList<>();
        URI base = start(exchange -> {
            count.incrementAndGet(); queries.add(exchange.getRequestURI().getRawQuery());
            reply(exchange, 200, response(1, 1, Long.MAX_VALUE, List.of(row("failed", "r1", -1, FROM.plusSeconds(1)))));
        });

        var selected = source(base, 500, 1).select(request(20));

        assertEquals(1, count.get()); assertTrue(queries.getFirst().endsWith("page=1&page_size=1"));
        assertEquals(1, selected.tasks().size());
        assertTrue(selected.limitations().containsAll(List.of("SCHEDULER_TASK_LIMIT", "TASK_SELECTION_INCOMPLETE")));
    }

    @Test void transientGatewayFailureRetriesReadOnlyOnceAndSensitiveBodiesNeverEscape() throws Exception {
        AtomicInteger count = new AtomicInteger();
        URI base = start(exchange -> {
            if (count.incrementAndGet() == 1) reply(exchange, 502, "secret upstream data");
            else reply(exchange, 200, response(1, 2, 1, List.of(row("failed", "r1", -1, FROM.plusSeconds(1)))));
        });
        assertEquals(1, source(base, 2, 20).select(request(20)).tasks().size());
        assertEquals(2, count.get());

        AtomicInteger failures = new AtomicInteger();
        URI broken = start(exchange -> { failures.incrementAndGet(); reply(exchange, 502, "secret synthetic-token upstream data"); });
        IOException failure = assertThrows(IOException.class, () -> source(broken, 2, 20).select(request(20)));
        assertEquals(2, failures.get()); assertSafe(failure, "SCHEDULER_HTTP_502");
    }

    @Test void invalidJsonAndOversizedResponseAreBoundedSanitizedFailures() throws Exception {
        URI invalid = start(exchange -> reply(exchange, 200, "secret synthetic-token {not json"));
        assertSafe(assertThrows(IOException.class, () -> source(invalid, 2, 20).select(request(20))), "SCHEDULER_INVALID_RESPONSE");
        byte[] oversized = ("secret synthetic-token " + "x".repeat((8 << 20) + 1)).getBytes(StandardCharsets.UTF_8);
        URI huge = start(exchange -> reply(exchange, 200, oversized));
        assertSafe(assertThrows(IOException.class, () -> source(huge, 2, 20).select(request(20))), "SCHEDULER_BYTE_LIMIT");
    }

    @Test void redirectsAreNotFollowedOrGivenAuthorization() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        URI target = start(exchange -> { hits.incrementAndGet(); reply(exchange, 200, "secret"); });
        URI redirect = start(exchange -> { exchange.getResponseHeaders().set("Location", target.toString()); reply(exchange, 302, "secret"); });

        assertSafe(assertThrows(IOException.class, () -> source(redirect, 2, 20).select(request(20))), "SCHEDULER_HTTP_302");

        assertEquals(0, hits.get());
    }

    @Test void stalledLaterPagePreservesObservedCandidatesAndMakesIncompleteExplicit() throws Exception {
        URI base = start(exchange -> {
            int page = page(exchange);
            if (page != 1) pause(600);
            reply(exchange, 200, response(page, 2, 4, List.of(
                    row("failed-" + page, "r" + page, -1, FROM.plusSeconds(page)), row("pending-" + page, "p" + page, 0, FROM.plusSeconds(page)))));
        });
        var source = new SchedulerTaskSource(LogConfig.from(Map.of()), new SchedulerConfig(base, "", ZoneId.of("Asia/Hong_Kong"),
                2, 20, Duration.ofMillis(150), Duration.ofSeconds(3)));

        var selected = source.select(request(20));

        assertEquals(List.of("failed-1"), selected.tasks().stream().map(LogModels.Task::taskId).toList());
        assertTrue(selected.limitations().containsAll(List.of("SCHEDULER_TIMEOUT", "TASK_SELECTION_INCOMPLETE")));
    }

    @Test void simultaneousProvidersShareAtMostTwoInFlightRequests() throws Exception {
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
        URI base = start(exchange -> {
            int current = active.incrementAndGet(); peak.accumulateAndGet(current, Math::max);
            try {
                pause(50);
                int page = page(exchange);
                reply(exchange, 200, response(page, 2, 6, List.of(row("failed-" + page, "r" + page, -1, FROM.plusSeconds(page)),
                        row("pending-" + page, "p" + page, 0, FROM.plusSeconds(page)))));
            } finally { active.decrementAndGet(); }
        });
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> source(base, 2, 20).select(request(20)));
            var second = workers.submit(() -> source(base, 2, 20).select(request(20)));
            assertEquals(3, first.get().tasks().size()); assertEquals(3, second.get().tasks().size());
        }
        assertEquals(2, peak.get());
    }

    private SchedulerTaskSource source(URI url, int pageSize, int maxTasks) {
        return new SchedulerTaskSource(LogConfig.from(Map.of()), new SchedulerConfig(url, "synthetic-token", ZoneId.of("Asia/Hong_Kong"),
                pageSize, maxTasks, Duration.ofSeconds(3), Duration.ofSeconds(10)));
    }

    private static LogRequest request(int count) {
        return new LogRequest("scheduler-victorialogs", "prod", "latest_failed", List.of(), count, FROM, TO, false);
    }

    private static LogRequest explicit(String id) {
        return new LogRequest("scheduler-victorialogs", "prod", "explicit_task_ids", List.of(id), 1, FROM, TO, false);
    }

    private static ObjectNode row(String id, String recordId, int status, Instant created) {
        return Json.MAPPER.createObjectNode().put("task_id", id).put("record_id", recordId).put("status", status)
                .put("created_at", created.toString()).put("updated_at", created.plusSeconds(1).toString());
    }

    private static ObjectNode response(int page, int pageSize, long total, List<ObjectNode> rows) {
        ObjectNode root = Json.MAPPER.createObjectNode();
        ObjectNode data = root.putObject("data").put("page", page).put("page_size", pageSize).put("total_count", total)
                .put("timezone", "Asia/Hong_Kong").put("fetched_at", "2026-10-15T12:00:00+08:00");
        var tasks = data.putArray("tasks"); rows.forEach(tasks::add);
        return root;
    }

    private URI start(HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor); server.createContext("/", handler); server.start();
        servers.add(server); executors.add(executor);
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static int page(HttpExchange exchange) {
        return Integer.parseInt(exchange.getRequestURI().getRawQuery().split("&page=")[1].split("&")[0]);
    }

    private static void reply(HttpExchange exchange, int status, JsonNode json) throws IOException {
        reply(exchange, status, Json.MAPPER.writeValueAsBytes(json));
    }

    private static void reply(HttpExchange exchange, int status, String text) throws IOException {
        reply(exchange, status, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void reply(HttpExchange exchange, int status, byte[] body) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try { exchange.getResponseBody().write(body); } catch (IOException ignored) { /* 超限、超时取消会关闭客户端连接。 */ }
        }
    }

    private static void pause(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void assertSafe(IOException failure, String code) {
        assertEquals(code, failure.getMessage()); assertNull(failure.getCause());
        assertFalse(failure.toString().contains("secret")); assertFalse(failure.toString().contains("synthetic-token"));
        assertFalse(failure.toString().contains("127.0.0.1"));
    }
}
