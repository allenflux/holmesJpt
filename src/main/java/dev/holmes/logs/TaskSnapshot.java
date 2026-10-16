package dev.holmes.logs;

import com.fasterxml.jackson.databind.JsonNode;
import dev.holmes.Json;
import dev.holmes.logs.LogModels.Attempt;
import dev.holmes.logs.LogModels.Task;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;

/** 以明确的任务状态快照启动首版；不将 ERROR 候选事件冒充最终失败状态。 */
public final class TaskSnapshot {
    public record Selection(List<Task> tasks, String observedAt, List<String> limitations) {
        public Selection { tasks = List.copyOf(tasks); limitations = List.copyOf(limitations); }
    }

    public Selection select(Path file, LogRequest request) throws IOException {
        if (file == null) throw new Unprocessable("请配置 HOLMES_LOG_TASKS_FILE；尚未提供调度器任务状态接口");
        JsonNode root;
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes((8 << 20) + 1);
            if (bytes.length > 8 << 20) throw new Unprocessable("任务快照超过 8 MiB");
            root = Json.MAPPER.readTree(bytes);
        } catch (com.fasterxml.jackson.core.JacksonException e) { throw new Unprocessable("任务快照 JSON 无效"); }
        if (root == null || !root.isObject() || !root.path("tasks").isArray()) throw new Unprocessable("任务快照缺少 tasks 数组");
        if (!request.source().equals(root.path("source").asText()) || !request.namespace().equals(root.path("namespace").asText()))
            throw new Unprocessable("任务快照 source / namespace 与请求不匹配");
        String scheduler = LogRequest.text(root, "scheduler_instance", "");
        if (!LogRequest.validId(scheduler)) throw new Unprocessable("快照需要稳定 scheduler_instance");
        Instant observed = LogRequest.instant(root, "observed_at");
        if (root.path("tasks").size() > 10000) throw new Unprocessable("任务快照超过 10000 个任务");
        List<Task> all = new ArrayList<>();
        HashSet<String> keys = new HashSet<>();
        for (JsonNode item : root.path("tasks")) {
            if (!item.isObject()) throw new Unprocessable("快照任务必须是对象");
            String id = LogRequest.text(item, "task_id", "");
            if (!LogRequest.validId(id) || !keys.add(id)) throw new Unprocessable("快照 task_id 无效或不唯一");
            String status = LogRequest.text(item, "status", "");
            if (!List.of("failed", "succeeded", "running", "pending", "cancelled").contains(status))
                throw new Unprocessable("任务状态枚举无效");
            if (item.has("retry_pending") && !item.path("retry_pending").isBoolean()) throw new Unprocessable("retry_pending 必须是布尔值");
            if (item.path("retry_pending").asBoolean(false)) status = "pending";
            Instant failed = status.equals("failed") ? LogRequest.instant(item, "failed_at") : null;
            if (failed != null && failed.isAfter(observed)) throw new Unprocessable("failed_at 不能晚于快照观察时间");
            JsonNode attemptsNode = item.path("attempts");
            if (!attemptsNode.isMissingNode() && (!attemptsNode.isArray() || attemptsNode.size() > 20))
                throw new Unprocessable("attempts 必须为不超过 20 次的执行清单");
            List<Attempt> attempts = new ArrayList<>();
            HashSet<String> attemptIds = new HashSet<>();
            for (JsonNode a : attemptsNode) {
                String aid = LogRequest.text(a, "attempt_id", ""), prompt = LogRequest.text(a, "prompt_id", id);
                if (!LogRequest.validId(aid) || !LogRequest.validId(prompt) || !attemptIds.add(aid))
                    throw new Unprocessable("attempt / prompt ID 无效或重复");
                Instant start = a.hasNonNull("started_at") ? LogRequest.instant(a, "started_at") : null;
                Instant end = a.hasNonNull("ended_at") ? LogRequest.instant(a, "ended_at") : null;
                if (status.equals("failed") && (start == null || end == null)) throw new Unprocessable("最终失败任务的 attempt 需要完整起止时间");
                if (end != null && (start == null || end.isBefore(start) || end.isAfter(observed))) throw new Unprocessable("attempt 时间范围无效");
                attempts.add(new Attempt(aid, prompt, start, end, LogRequest.text(a, "instance_id", "")));
            }
            String finalId = LogRequest.text(item, "final_attempt_id", attempts.size() == 1 ? attempts.getFirst().id() : "");
            if (status.equals("failed") && !attempts.isEmpty() && !attemptIds.contains(finalId)) throw new Unprocessable("请明确 final_attempt_id");
            String ref = "task-" + hex(request.source() + "\n" + request.namespace() + "\n" + scheduler + "\n" + id).substring(0, 24);
            all.add(new Task(ref, id, status, attempts, finalId, LogRequest.text(item, "failure_reason", ""), failed,
                    LogRequest.text(item, "status_version", "snapshot")));
        }
        List<Task> selected;
        java.util.Map<String, String> aliases = new java.util.HashMap<>();
        for (Task task : all) aliases.put(task.taskId(), task.taskId());
        for (Task task : all) {
            for (Attempt a : task.attempts()) {
                String owner = aliases.putIfAbsent(a.promptId(), task.taskId());
                if (owner != null && !owner.equals(task.taskId())) throw new Unprocessable("快照 prompt_id 映射到多个逻辑任务");
            }
            Attempt last = task.finalAttempt();
            if (task.status().equals("failed") && last != null && (task.attempts().stream().anyMatch(a -> a.endedAt().isAfter(last.endedAt()))
                    || task.failedAt() != null && last.endedAt().isAfter(task.failedAt())))
                throw new Unprocessable("final_attempt_id 或 failed_at 与执行次序冲突");
        }
        if (request.mode().equals("explicit_task_ids")) {
            selected = new ArrayList<>();
            for (String id : request.taskIds()) {
                Task task = all.stream().filter(t -> t.taskId().equals(id)).findFirst().orElse(null);
                if (task == null || !task.status().equals("failed")) throw new Unprocessable("显式任务不存在、非最终失败或仍有待执行重试：" + id);
                selected.add(task);
            }
        } else selected = all.stream().filter(t -> t.status().equals("failed"))
                .filter(t -> !t.failedAt().isBefore(request.from()) && t.failedAt().isBefore(request.to()))
                .sorted(Comparator.comparing(Task::failedAt).reversed().thenComparing(Task::taskId))
                .limit(request.requestedCount()).toList();
        List<String> limitations = new ArrayList<>();
        limitations.add("TASK_STATUS_FROM_SNAPSHOT");
        if (selected.size() < request.requestedCount()) limitations.add("FEWER_FAILED_TASKS_THAN_REQUESTED");
        return new Selection(selected, observed.toString(), limitations);
    }

    static String hex(String text) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public static final class Unprocessable extends IllegalArgumentException {
        public Unprocessable(String message) { super(message); }
    }
}
