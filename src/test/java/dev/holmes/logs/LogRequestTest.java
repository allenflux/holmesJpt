package dev.holmes.logs;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.holmes.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class LogRequestTest {
    private final LogConfig config = LogConfig.from(Map.of("HOLMES_LOG_NAMESPACES", "prod,dev"));

    @Test void explicitTaskIdsAreDeduplicatedAndDefaultsAreBoundToConfiguredSource() {
        ObjectNode request = explicit();
        request.withObject("/selection").withArray("task_ids").add("task-a");
        LogRequest result = LogRequest.parse(request, config);
        assertEquals("scheduler-victorialogs", result.source());
        assertEquals("prod", result.namespace());
        assertEquals(List.of("task-a", "task-b"), result.taskIds());
        assertEquals(2, result.requestedCount());
        assertFalse(result.archiveRequired());
        assertEquals("none", result.narrative());
    }

    @Test void narrativeDefaultsToNoneAndOnlyExplicitLlmEnablesIt() {
        assertEquals("llm", LogRequest.parse(explicit().put("narrative", "llm"), config).narrative());
        assertEquals("none", LogRequest.parse(explicit().put("narrative", "none"), config).narrative());
        for (String value : List.of("LLM", "automatic", ""))
            assertThrows(IllegalArgumentException.class, () -> LogRequest.parse(explicit().put("narrative", value), config));
        assertThrows(IllegalArgumentException.class, () -> LogRequest.parse(explicit().put("narrative", true), config));
        LogRequest parsed = LogRequest.parse(explicit(), config);
        assertEquals("none", new LogRequest(parsed.source(), parsed.namespace(), parsed.mode(), parsed.taskIds(),
                parsed.requestedCount(), parsed.from(), parsed.to(), parsed.archiveRequired()).narrative());
    }

    @Test void acceptsOnlyNoneOrRequiredArchiveAndFinalFailedAttemptScope() {
        for (String mode : List.of("none", "required")) {
            ObjectNode request = explicit().put("raw_archive", mode).put("attempt_scope", "final_failed");
            assertEquals(mode.equals("required"), LogRequest.parse(request, config).archiveRequired());
        }
        for (String mode : List.of("optional", "always", "", "NONE"))
            assertThrows(IllegalArgumentException.class, () -> LogRequest.parse(explicit().put("raw_archive", mode), config));
        assertThrows(IllegalArgumentException.class, () -> LogRequest.parse(explicit().put("raw_archive", true), config));
        assertThrows(IllegalArgumentException.class, () -> LogRequest.parse(explicit().put("attempt_scope", "all_attempts"), config));
    }

    @Test void rejectsUnapprovedTopLevelAndSelectionFields() {
        for (String field : List.of("grafana_url", "datasource_uid", "query", "logs_ql", "fluxdrop_url", "token", "pod")) {
            ObjectNode request = explicit().put(field, "caller-supplied");
            assertThrows(IllegalArgumentException.class, () -> LogRequest.parse(request, config), field);
        }
        ObjectNode query = explicit();
        query.withObject("/selection").put("query", "* | delete");
        assertThrows(IllegalArgumentException.class, () -> LogRequest.parse(query, config));
    }

    @Test void validatesSourceNamespaceAndStringSchema() {
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                node -> node.put("source", "other-source"), node -> node.put("namespace", "staging"),
                node -> node.put("namespace", "PROD"), node -> node.put("source", 1),
                node -> node.putNull("namespace"), node -> node.put("source", ""),
                node -> node.putArray("selection"), node -> node.withObject("/selection").put("mode", "scan_all"))) {
            ObjectNode request = explicit(); mutation.accept(request);
            assertThrows(IllegalArgumentException.class, () -> LogRequest.parse(request, config));
        }
        assertEquals("dev", LogRequest.parse(explicit().put("namespace", "dev"), config).namespace());
        assertThrows(IllegalArgumentException.class, () -> LogRequest.parse(null, config));
        assertThrows(IllegalArgumentException.class, () -> LogRequest.parse(Json.MAPPER.createArrayNode(), config));
    }

    @Test void validatesLatestCountTypeBoundsAndPreventsMixedModes() {
        assertEquals(20, LogRequest.parse(latest(), config).requestedCount());
        for (int count : List.of(1, 50)) {
            ObjectNode request = latest(); request.withObject("/selection").put("requested_count", count);
            assertEquals(count, LogRequest.parse(request, config).requestedCount());
        }
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                node -> node.put("requested_count", 0), node -> node.put("requested_count", 51),
                node -> node.put("requested_count", -1), node -> node.put("requested_count", 1.5),
                node -> node.put("requested_count", "2"), node -> node.put("requested_count", Long.MAX_VALUE),
                node -> node.putNull("requested_count"), node -> node.putArray("task_ids").add("task-a"))) {
            ObjectNode request = latest(); mutation.accept(request.withObject("/selection"));
            assertThrows(IllegalArgumentException.class, () -> LogRequest.parse(request, config));
        }
        ObjectNode explicitWithCount = explicit(); explicitWithCount.withObject("/selection").put("requested_count", 2);
        assertThrows(IllegalArgumentException.class, () -> LogRequest.parse(explicitWithCount, config));
    }

    @Test void validatesExplicitIdsAndArrayBounds() {
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                node -> node.putArray("task_ids"), node -> node.put("task_ids", "task-a"),
                node -> node.putArray("task_ids").add(42), node -> node.putArray("task_ids").add(" task-a"),
                node -> node.putArray("task_ids").add("task/a"), node -> node.putArray("task_ids").add("a".repeat(129)))) {
            ObjectNode request = explicit(); mutation.accept(request.withObject("/selection"));
            assertThrows(IllegalArgumentException.class, () -> LogRequest.parse(request, config));
        }
        ObjectNode oversized = explicit(); var ids = oversized.withObject("/selection").putArray("task_ids");
        for (int i = 0; i < 51; i++) ids.add("task-" + i);
        assertThrows(IllegalArgumentException.class, () -> LogRequest.parse(oversized, config));
    }

    @Test void absoluteTimeRangeMustIncreaseAndBeAtMostTwentyFourHours() {
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                node -> node.put("from", "2026-10-15T06:00:00Z"),
                node -> node.put("from", "2026-10-15T07:00:00Z"),
                node -> node.put("from", "2026-10-14T05:59:59Z"),
                node -> node.put("from", "2026-10-15T05:00:00"),
                node -> node.put("from", "now-30m"), node -> node.put("to", "now"),
                node -> node.putNull("from"), node -> node.put("to", 123))) {
            ObjectNode request = explicit(); mutation.accept(request.withObject("/selection"));
            assertThrows(IllegalArgumentException.class, () -> LogRequest.parse(request, config));
        }
        ObjectNode oneDay = explicit(); oneDay.withObject("/selection").put("from", "2026-10-14T06:00:00Z");
        assertEquals(86_400, java.time.Duration.between(LogRequest.parse(oneDay, config).from(), LogRequest.parse(oneDay, config).to()).toSeconds());
    }

    private static ObjectNode explicit() {
        ObjectNode request = Json.MAPPER.createObjectNode();
        request.putObject("selection").put("mode", "explicit_task_ids")
                .put("from", "2026-10-15T05:00:00Z").put("to", "2026-10-15T06:00:00Z")
                .putArray("task_ids").add("task-a").add("task-b");
        return request;
    }

    private static ObjectNode latest() {
        ObjectNode request = explicit();
        request.withObject("/selection").remove("task_ids");
        request.withObject("/selection").put("mode", "latest_failed");
        return request;
    }
}
