package dev.holmes.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.holmes.Json;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LogErrorGroupsTest {
    @Test void sameErrorAcrossTasksGroupsWithDistinctRepresentativesAndStableIdentity() {
        ObjectNode report = report(2, 2);
        ObjectNode first = task(report, "a", "COLLECTED"), second = task(report, "b", "COLLECTED");
        ObjectNode a = error(report, first, "ev-a", "HTTP_404", "LoadVideoFromURL", "https://example.invalid/assets/video.mp4?token=first&expires=100");
        ObjectNode b = error(report, second, "ev-b", "HTTP_404", "LoadVideoFromURL", "https://example.invalid/assets/video.mp4?expires=200&token=second");
        b.put("component", "other-component");
        a.put("error_summary", "HTTP_404; node_id=332; task_id=task-a; 2026-10-17T01:00:00Z; " + a.path("exception_message").asText());
        b.put("error_summary", "HTTP_404; node_id=999; task_id=task-b; 2026-10-18T02:00:00Z; " + b.path("exception_message").asText());
        report.withArray("evidence").add(a.deepCopy());
        ObjectNode before = report.deepCopy();

        var view = LogErrorGroups.build(report);

        assertEquals(1, view.path("summary").path("group_count").asInt());
        assertEquals(2, view.path("summary").path("error_task_count").asInt());
        JsonNode group = view.path("groups").get(0);
        assertEquals(2, group.path("task_count").asInt());
        assertEquals(2, group.path("evidence_count").asInt());
        assertEquals(2, group.path("representative_evidence_ids").size());
        assertTrue(group.path("representative_evidence_ids").toString().contains("ev-a"));
        assertTrue(group.path("representative_evidence_ids").toString().contains("ev-b"));
        assertTrue(group.path("candidate_only").asBoolean());
        assertEquals("UNASSIGNED", group.path("assignment_status").asText());
        assertTrue(group.path("title").asText().contains("视频"));
        assertEquals(before, report);
        report.withArray("tasks").removeAll().add(second).add(first);
        report.withArray("evidence").removeAll().add(b).add(a);
        assertEquals(view, LogErrorGroups.build(report));
    }

    @Test void statusResourcePathHostNodeAndExceptionKeepDistinctGroups() {
        ObjectNode report = report(6, 6);
        String[] signatures = {"HTTP_404", "HTTP_503", "HTTP_404", "HTTP_404", "HTTP_404", "HTTP_404"};
        String[] resources = {"https://a.example.invalid/video.mp4", "https://a.example.invalid/video.mp4", "https://a.example.invalid/other.mp4",
                "https://b.example.invalid/video.mp4", "https://a.example.invalid/video.mp4", "https://a.example.invalid/video.mp4"};
        for (int i = 0; i < signatures.length; i++) {
            var evidence = error(report, task(report, "t" + i, "COLLECTED"), "ev-" + i, signatures[i],
                    i == 4 ? "OtherVideoNode" : "LoadVideoFromURL", resources[i]);
            if (i == 5) evidence.put("exception_type", "OtherException");
        }
        assertEquals(6, LogErrorGroups.build(report).path("summary").path("group_count").asInt());
    }

    @Test void uuidInsideResourcePathAndStableQueryParameterRemainResourceIdentity() {
        ObjectNode report = report(3, 3);
        for (int i = 0; i < 3; i++) error(report, task(report, "t" + i, "COLLECTED"), "ev-" + i, "HTTP_404", "LoadVideoFromURL",
                "https://example.invalid/assets/" + (i == 1 ? "00000000-0000-0000-0000-000000000002" : "00000000-0000-0000-0000-000000000001")
                        + ".mp4?version=" + (i == 2 ? "2" : "1"));
        assertEquals(3, LogErrorGroups.build(report).path("summary").path("group_count").asInt());
    }

    @Test void nonHttpResourcesAndNonTemporaryModelPathsKeepIdentityWithoutUserinfo() {
        ObjectNode report = report(6, 6);
        String[] resources = {"s3://user:synthetic-s3-secret@bucket/assets/00000000-0000-0000-0000-000000000001.mp4",
                "s3://bucket/assets/00000000-0000-0000-0000-000000000002.mp4",
                "/data/models/00000000-0000-0000-0000-000000000001.safetensors", "/data/models/00000000-0000-0000-0000-000000000002.safetensors",
                "https://example.invalid/" + "long-path-".repeat(300) + "a.mp4", "https://example.invalid/" + "long-path-".repeat(300) + "b.mp4"};
        for (int i = 0; i < resources.length; i++) error(report, task(report, "t" + i, "COLLECTED"), "ev-" + i, "HTTP_404", "Node", resources[i]);

        var view = LogErrorGroups.build(report);

        assertEquals(6, view.path("summary").path("group_count").asInt());
        assertFalse(view.toString().contains("synthetic-s3-secret"));
        assertTrue(view.toString().contains("s3://bucket/assets/"));
        assertTrue(view.path("truncated").asBoolean());
    }

    @Test void legacyFailureEventFallbackIsExactAndDoesNotInferFromOrdinaryText() {
        for (String event : List.of("execution_error", "execution_failed", "execution_failure", "task_failed", "task_failure")) {
            ObjectNode report = report(1, 1);
            var item = error(report, task(report, "a", "COLLECTED"), "ev-a", "HTTP_404", "Node", "https://example.invalid/a");
            item.put("event", event).remove("failure_signature");
            assertEquals("EXECUTION_ERROR", LogErrorGroups.build(report).path("groups").get(0).path("signature").asText());
            item.put("event", "example_" + event).put("excerpt", "HTTP 404 RuntimeError: user text");
            assertTrue(LogErrorGroups.build(report).path("groups").isEmpty());
        }
    }

    @Test void unlinkedHistoricalContextAndNonErrorEvidenceCannotBecomeCards() {
        ObjectNode report = report(2, 2);
        ObjectNode a = task(report, "a", "COLLECTED"), b = task(report, "b", "COLLECTED");
        error(report, a, "historical", "HTTP_404", "Node", "https://example.invalid/a").put("historical_attempt", true);
        error(report, a, "context", "HTTP_404", "Node", "https://example.invalid/a").put("context_only", true);
        error(report, a, "unknown", "UNKNOWN", "Node", "https://example.invalid/a");
        error(report, a, "incomplete", "INCOMPLETE", "Node", "https://example.invalid/a");
        error(report, a, "ambiguous", "AMBIGUOUS", "Node", "https://example.invalid/a");
        var unlinked = error(report, b, "unlinked", "HTTP_404", "Node", "https://example.invalid/a");
        b.withArray("evidence_ids").removeAll();
        unlinked.put("task_ref", "missing-task");

        var view = LogErrorGroups.build(report);

        assertEquals(0, view.path("summary").path("error_task_count").asInt());
        assertEquals(2, view.path("summary").path("pending_task_count").asInt());
        assertTrue(view.path("groups").isEmpty());
    }

    @Test void conflictingEvidenceIdsAreExcludedAndRepresentativeSelectionCoversDifferentTasks() {
        ObjectNode report = report(2, 2);
        var a = task(report, "a", "COLLECTED");
        var b = task(report, "b", "COLLECTED");
        error(report, a, "a-first", "HTTP_404", "Node", "https://example.invalid/a");
        error(report, a, "a-second", "HTTP_404", "Node", "https://example.invalid/a");
        error(report, b, "b-first", "HTTP_404", "Node", "https://example.invalid/a");
        ObjectNode conflict = error(report, a, "conflict", "HTTP_503", "Node", "https://example.invalid/a");
        report.withArray("evidence").add(conflict.deepCopy().put("exception_message", "different content"));

        var view = LogErrorGroups.build(report);

        assertEquals(1, view.path("summary").path("group_count").asInt());
        JsonNode group = view.path("groups").get(0);
        assertEquals(3, group.path("evidence_count").asInt());
        assertEquals(2, group.path("representative_evidence_ids").size());
        assertTrue(group.path("representative_evidence_ids").toString().contains("b-first"));
    }

    @Test void legacyOnlyExplicitFailureEventCanProduceGenericCardAndNoLogTasksStayPending() {
        ObjectNode report = report(8, 5);
        var legacy = task(report, "legacy", "COLLECTED");
        error(report, legacy, "legacy-error", "HTTP_404", "", "https://example.invalid/a")
                .remove(List.of("failure_signature", "error_summary", "exception_type", "exception_message"));
        var info = task(report, "info", "COLLECTED");
        error(report, info, "ordinary", "UNKNOWN", "", "https://example.invalid/a")
                .put("event", "task_started").put("excerpt", "HTTP 404 RuntimeError: only user text").remove("failure_signature");
        task(report, "no-match", "NO_MATCHES"); task(report, "failed", "FAILED"); task(report, "skipped", "SKIPPED");

        var view = LogErrorGroups.build(report);

        assertEquals(8, view.path("summary").path("selected_task_count").asInt());
        assertEquals(5, view.path("summary").path("processed_task_count").asInt());
        assertEquals(1, view.path("summary").path("error_task_count").asInt());
        assertEquals(4, view.path("summary").path("pending_task_count").asInt());
        assertEquals("EXECUTION_ERROR", view.path("groups").get(0).path("signature").asText());
        assertTrue(view.path("pending_tasks").toString().contains("NO_LOG_MATCH"));
        assertTrue(view.path("pending_tasks").toString().contains("QUERY_FAILED"));
        assertTrue(view.path("pending_tasks").toString().contains("QUERY_SKIPPED"));
        assertEquals("INCOMPLETE", legacy.path("signature").asText());
    }

    @Test void groupingRedactsCredentialsAndDoesNotCopyPayloadOrPrivatePrompt() {
        ObjectNode report = report(1, 1);
        var item = error(report, task(report, "a", "COLLECTED"), "ev-a", "HTTP_404", "LoadVideoFromURL",
                "https://user:synthetic-password@example.invalid/assets/a.mp4?api_key=synthetic-api-key&X-Amz-Signature=synthetic-signature");
        item.put("error_summary", "RuntimeError token=synthetic-token; Network error " + item.path("exception_message").asText());
        item.put("excerpt", "raw payload private-user-prompt");

        var view = LogErrorGroups.build(report);

        for (String secret : List.of("synthetic-password", "synthetic-api-key", "synthetic-signature", "synthetic-token", "private-user-prompt"))
            assertFalse(view.toString().contains(secret), secret);
        assertTrue(view.path("groups").get(0).path("normalized_message").asText().contains("example.invalid/assets/a.mp4"));
    }

    @Test void viewBudgetAndGroupLimitDoNotChangeFullCounts() {
        ObjectNode report = report(180, 180);
        for (int i = 0; i < 180; i++) {
            var item = error(report, task(report, "task-" + i, "COLLECTED"), "ev-" + i, "HTTP_404", "节点".repeat(64),
                    "https://example.invalid/assets/" + i + ".mp4");
            item.put("error_summary", "详情".repeat(1_500) + " https://example.invalid/assets/" + i + ".mp4");
        }
        var view = LogErrorGroups.build(report);

        assertTrue(view.path("truncated").asBoolean());
        assertEquals(180, view.path("summary").path("group_count").asInt());
        assertEquals(180, view.path("summary").path("error_task_count").asInt());
        assertEquals(0, view.path("summary").path("pending_task_count").asInt());
        assertTrue(view.path("groups").size() <= 40);
        assertEquals(view.path("groups").size(), view.path("summary").path("displayed_group_count").asInt());
        assertTrue(view.toString().getBytes(StandardCharsets.UTF_8).length <= 64 * 1024);
    }

    private static ObjectNode report(int selected, int processed) {
        ObjectNode report = Json.MAPPER.createObjectNode().put("selected_count", selected).put("processed_count", processed);
        report.putArray("tasks"); report.putArray("evidence"); return report;
    }
    private static ObjectNode task(ObjectNode report, String id, String status) {
        ObjectNode task = report.withArray("tasks").addObject().put("task_ref", "ref-" + id).put("task_id", "task-" + id)
                .put("association_scope", "TASK_ONLY").put("signature", "INCOMPLETE").put("collection_status", status);
        task.putArray("evidence_ids"); return task;
    }
    private static ObjectNode error(ObjectNode report, ObjectNode task, String id, String signature, String node, String resource) {
        task.withArray("evidence_ids").add(id);
        return report.withArray("evidence").addObject().put("evidence_id", id).put("task_ref", task.path("task_ref").asText())
                .put("event", "execution_error").put("failure_signature", signature).put("exception_type", "RuntimeError")
                .put("node_type", node).put("component", "comfyui").put("exception_message", "Network error for " + resource)
                .put("error_summary", "Network error for " + resource).put("event_time", "2026-10-17T01:00:00Z")
                .put("candidate_only", true).putNull("historical_attempt");
    }
}
