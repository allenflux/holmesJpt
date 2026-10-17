package dev.holmes.logs;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.holmes.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class TaskSnapshotTest {
    @TempDir Path directory;
    private final TaskSnapshot source = new TaskSnapshot();

    @Test void selectsOnlyExplicitFinalFailedTasksAndRetainsVersion() throws Exception {
        ObjectNode root = snapshot(failed("task-a"), failed("task-b"));
        root.withArray("tasks").get(0).withObject("").put("status_version", "revision-7");
        var result = source.select(write(root), explicit("task-a"));
        assertEquals(1, result.tasks().size());
        assertEquals("task-a", result.tasks().get(0).taskId());
        assertEquals("revision-7", result.tasks().get(0).statusVersion());
        assertEquals("2026-10-15T06:30:00Z", result.observedAt());
        assertEquals(List.of("TASK_STATUS_FROM_SNAPSHOT"), result.limitations());
        assertTrue(result.tasks().get(0).taskRef().matches("task-[a-f0-9]{24}"));
    }

    @Test void rejectsMissingSuccessfulCancelledRunningAndRetryPendingExplicitTasks() throws Exception {
        for (String status : List.of("succeeded", "cancelled", "running", "pending")) {
            ObjectNode task = failed("task-a").put("status", status);
            Path file = write(snapshot(task));
            assertThrows(TaskSnapshot.Unprocessable.class, () -> source.select(file, explicit("task-a")), status);
        }
        ObjectNode retry = failed("task-a").put("retry_pending", true);
        Path retryFile = write(snapshot(retry));
        assertThrows(TaskSnapshot.Unprocessable.class, () -> source.select(retryFile, explicit("task-a")));
        Path missingFile = write(snapshot(failed("task-b")));
        assertThrows(TaskSnapshot.Unprocessable.class, () -> source.select(missingFile, explicit("task-a")));
        assertThrows(TaskSnapshot.Unprocessable.class, () -> source.select(null, explicit("task-a")));
    }

    @Test void latestSelectionUsesStableOrderExactWindowAndActualAvailableCount() throws Exception {
        ObjectNode a = failed("task-a"), b = failed("task-b"), c = failed("task-c");
        c.put("failed_at", "2026-10-15T05:58:00Z");
        ObjectNode old = failed("task-old").put("failed_at", "2026-10-15T04:59:00Z");
        attempt(old, 0).put("started_at", "2026-10-15T04:00:00Z").put("ended_at", "2026-10-15T04:58:00Z");
        ObjectNode upperBoundary = failed("task-upper").put("failed_at", "2026-10-15T06:00:00Z");
        ObjectNode success = failed("task-success").put("status", "succeeded");
        ObjectNode retry = failed("task-retry").put("retry_pending", true);
        var result = source.select(write(snapshot(b, old, upperBoundary, success, c, retry, a)), latest(5));
        assertEquals(List.of("task-c", "task-a", "task-b"), result.tasks().stream().map(LogModels.Task::taskId).toList());
        assertTrue(result.limitations().contains("FEWER_FAILED_TASKS_THAN_REQUESTED"));
        var limited = source.select(write(snapshot(b, c, a)), latest(2));
        assertEquals(List.of("task-c", "task-a"), limited.tasks().stream().map(LogModels.Task::taskId).toList());
        assertFalse(limited.limitations().contains("FEWER_FAILED_TASKS_THAN_REQUESTED"));
    }

    @Test void validatesSourceNamespaceSchedulerAndSnapshotSchema() throws Exception {
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                node -> node.put("source", "other-source"), node -> node.put("namespace", "staging"),
                node -> node.remove("scheduler_instance"), node -> node.put("scheduler_instance", "pod/ephemeral"),
                node -> node.put("observed_at", "now"), node -> node.putObject("tasks"))) {
            ObjectNode root = snapshot(failed("task-a")); mutation.accept(root);
            Path file = write(root);
            assertThrows(IllegalArgumentException.class, () -> source.select(file, explicit("task-a")));
        }
        Path invalid = directory.resolve("invalid.json"); Files.writeString(invalid, "not json");
        assertThrows(TaskSnapshot.Unprocessable.class, () -> source.select(invalid, explicit("task-a")));
    }

    @Test void duplicateTaskIdsAndUnknownStatesAreRejected() throws Exception {
        Path duplicate = write(snapshot(failed("task-a"), failed("task-a")));
        assertThrows(TaskSnapshot.Unprocessable.class, () -> source.select(duplicate, explicit("task-a")));
        Path invalidStatus = write(snapshot(failed("task-a").put("status", "error")));
        assertThrows(TaskSnapshot.Unprocessable.class, () -> source.select(invalidStatus, explicit("task-a")));
        Path invalidRetry = write(snapshot(failed("task-a").put("retry_pending", "true")));
        assertThrows(TaskSnapshot.Unprocessable.class, () -> source.select(invalidRetry, explicit("task-a")));
    }

    @Test void promptAliasCannotBelongToTwoLogicalTasksOrAnotherTaskId() throws Exception {
        ObjectNode a = failed("task-a"), b = failed("task-b");
        attempt(a, 0).put("prompt_id", "shared-prompt");
        attempt(b, 0).put("prompt_id", "shared-prompt");
        Path samePrompt = write(snapshot(a, b));
        assertThrows(TaskSnapshot.Unprocessable.class, () -> source.select(samePrompt, explicit("task-a")));
        attempt(b, 0).put("prompt_id", "task-a");
        Path aliasToTask = write(snapshot(a, b));
        assertThrows(TaskSnapshot.Unprocessable.class, () -> source.select(aliasToTask, explicit("task-a")));
    }

    @Test void sameTaskCanReusePromptAcrossRetriesButMustDeclareLatestAttempt() throws Exception {
        ObjectNode task = failed("task-a");
        attempt(task, 0).put("started_at", "2026-10-15T05:00:00Z").put("ended_at", "2026-10-15T05:10:00Z");
        task.withArray("attempts").addObject().put("attempt_id", "try-2").put("prompt_id", "task-a")
                .put("started_at", "2026-10-15T05:20:00Z").put("ended_at", "2026-10-15T05:54:00Z");
        task.put("final_attempt_id", "try-2");
        var selected = source.select(write(snapshot(task)), explicit("task-a")).tasks().get(0);
        assertEquals(2, selected.attempts().size());
        assertEquals("try-2", selected.finalAttempt().id());
        task.put("final_attempt_id", "try-1");
        Path wrongFinal = write(snapshot(task));
        assertThrows(TaskSnapshot.Unprocessable.class, () -> source.select(wrongFinal, explicit("task-a")));
        task.remove("final_attempt_id");
        Path missingFinal = write(snapshot(task));
        assertThrows(TaskSnapshot.Unprocessable.class, () -> source.select(missingFinal, explicit("task-a")));
    }

    @Test void finalAttemptEndCannotFollowFailureAndAttemptsMustHaveValidBounds() throws Exception {
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                task -> task.put("failed_at", "2026-10-15T05:53:00Z"),
                task -> task.put("failed_at", "2026-10-15T06:31:00Z"),
                task -> attempt(task, 0).put("ended_at", "2026-10-15T04:59:00Z"),
                task -> attempt(task, 0).put("ended_at", "2026-10-15T06:31:00Z"),
                task -> attempt(task, 0).remove("ended_at"), task -> attempt(task, 0).remove("started_at"))) {
            ObjectNode task = failed("task-a"); mutation.accept(task);
            Path file = write(snapshot(task));
            assertThrows(IllegalArgumentException.class, () -> source.select(file, explicit("task-a")));
        }
    }

    @Test void unrelatedRunningTaskWithNoEndTimeDoesNotBlockFailedSelection() throws Exception {
        ObjectNode running = failed("task-running").put("status", "running");
        running.remove("failed_at"); attempt(running, 0).remove("ended_at");
        ObjectNode pending = Json.MAPPER.createObjectNode().put("task_id", "task-pending").put("status", "pending");
        var selected = source.select(write(snapshot(running, pending, failed("task-a"))), latest(5));
        assertEquals(List.of("task-a"), selected.tasks().stream().map(LogModels.Task::taskId).toList());
    }

    @Test void taskReferenceIncludesStableSchedulerScope() throws Exception {
        ObjectNode root = snapshot(failed("task-a"));
        String first = source.select(write(root), explicit("task-a")).tasks().get(0).taskRef();
        String same = source.select(write(root), explicit("task-a")).tasks().get(0).taskRef();
        root.put("scheduler_instance", "scheduler-other");
        String other = source.select(write(root), explicit("task-a")).tasks().get(0).taskRef();
        assertEquals(first, same);
        assertNotEquals(first, other);
    }

    @Test void schedulerSourceFactsRoundTripWithoutReplacingVerifiedFailureOrAttemptTimes() throws Exception {
        ObjectNode task = failed("task-a");
        var facts = new LogModels.SchedulerFacts("source-record", "generate_video", "FAILED",
                Instant.parse("2026-10-15T04:55:00Z"), Instant.parse("2026-10-15T06:20:00Z"));
        task.set("scheduler_facts", TaskSnapshot.schedulerFactsJson(facts));

        var selected = source.select(write(snapshot(task)), explicit("task-a")).tasks().getFirst();

        assertEquals(facts, selected.schedulerFacts());
        assertEquals(facts, TaskSnapshot.schedulerFactsFromJson(TaskSnapshot.schedulerFactsJson(selected.schedulerFacts())));
        assertEquals(Instant.parse("2026-10-15T05:55:00Z"), selected.failedAt());
        assertEquals(Instant.parse("2026-10-15T05:54:00Z"), selected.finalAttempt().endedAt());
        assertEquals(LogModels.AssociationScope.VERIFIED_ATTEMPTS, selected.associationScope());
    }

    @Test void oldSnapshotsAndBothTaskConstructorsKeepMissingSourceFactsUnknown() throws Exception {
        var old = source.select(write(snapshot(failed("task-a"))), explicit("task-a")).tasks().getFirst();
        assertNull(old.schedulerFacts()); assertNull(TaskSnapshot.schedulerFactsJson(null));
        assertNull(TaskSnapshot.schedulerFactsFromJson(null)); assertNull(TaskSnapshot.schedulerFactsFromJson(Json.MAPPER.nullNode()));
        assertNull(TaskSnapshot.schedulerFactsFromJson(Json.MAPPER.createObjectNode().path("missing")));
        var eight = new LogModels.Task("ref", "task", "failed", List.of(), "", "", null, "v1");
        var nine = new LogModels.Task("ref", "task", "failed", List.of(), "", "", null, "v1", LogModels.AssociationScope.TASK_ONLY);
        assertNull(eight.schedulerFacts()); assertNull(nine.schedulerFacts());
        assertEquals(LogModels.AssociationScope.VERIFIED_ATTEMPTS, eight.associationScope());
        assertEquals(LogModels.AssociationScope.TASK_ONLY, nine.associationScope());
    }

    @Test void sourceFactsRejectNonWhitelistedOrInvalidValuesWithoutEchoingThem() {
        ObjectNode valid = TaskSnapshot.schedulerFactsJson(new LogModels.SchedulerFacts("r1", "type", "FAILED",
                Instant.parse("2026-10-15T05:00:00Z"), Instant.parse("2026-10-15T05:01:00Z")));
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                node -> node.put("prompt_id", "private-prompt-secret"), node -> node.putObject("task_type").put("value", "secret"),
                node -> node.put("error_code", "secret\ncontrol"), node -> node.put("error_code", "secret".repeat(100)),
                node -> node.put("record_id", "private/invalid"), node -> node.put("created_at", "private-secret-time"),
                node -> node.put("updated_at", "2026-10-15T04:59:00Z"))) {
            ObjectNode invalid = valid.deepCopy(); mutation.accept(invalid);
            TaskSnapshot.Unprocessable failure = assertThrows(TaskSnapshot.Unprocessable.class, () -> TaskSnapshot.schedulerFactsFromJson(invalid));
            assertFalse(failure.getMessage().contains("secret")); assertFalse(failure.getMessage().contains("private"));
        }
    }

    private Path write(ObjectNode snapshot) throws Exception {
        Path file = directory.resolve("tasks.json"); Files.writeString(file, snapshot.toString()); return file;
    }
    private static ObjectNode snapshot(ObjectNode... tasks) {
        ObjectNode root = Json.MAPPER.createObjectNode().put("source", "scheduler-victorialogs").put("namespace", "prod")
                .put("scheduler_instance", "scheduler-production").put("observed_at", "2026-10-15T06:30:00Z");
        var list = root.putArray("tasks"); for (ObjectNode task : tasks) list.add(task); return root;
    }
    private static ObjectNode failed(String id) {
        ObjectNode task = Json.MAPPER.createObjectNode().put("task_id", id).put("status", "failed")
                .put("failed_at", "2026-10-15T05:55:00Z").put("status_version", "v1");
        task.putArray("attempts").addObject().put("attempt_id", "try-1").put("prompt_id", id)
                .put("started_at", "2026-10-15T05:00:00Z").put("ended_at", "2026-10-15T05:54:00Z");
        return task;
    }
    private static ObjectNode attempt(ObjectNode task, int index) { return (ObjectNode) task.withArray("attempts").get(index); }
    private static LogRequest explicit(String... ids) { return new LogRequest("scheduler-victorialogs", "prod", "explicit_task_ids", List.of(ids), ids.length,
            Instant.parse("2026-10-15T05:00:00Z"), Instant.parse("2026-10-15T06:00:00Z"), false); }
    private static LogRequest latest(int count) { return new LogRequest("scheduler-victorialogs", "prod", "latest_failed", List.of(), count,
            Instant.parse("2026-10-15T05:00:00Z"), Instant.parse("2026-10-15T06:00:00Z"), false); }
}
