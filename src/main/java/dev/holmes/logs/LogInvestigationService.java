package dev.holmes.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.holmes.Json;
import dev.holmes.logs.LogModels.Attempt;
import dev.holmes.logs.LogModels.FetchResult;
import dev.holmes.logs.LogModels.LogEvent;
import dev.holmes.logs.LogModels.LogQuery;
import dev.holmes.logs.LogModels.Task;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 采集和计数不依赖 LLM；每任务原子检查点支持固定样本的单实例恢复。 */
public final class LogInvestigationService implements AutoCloseable {
    private final LogConfig config;
    private final LogSource logs;
    private final TaskSource tasks;
    private final FluxDropArchiveSink archive;
    private final JobStore store;
    private final FileChannel lockChannel;
    private final FileLock instanceLock;
    private final java.util.concurrent.ConcurrentHashMap<String, ObjectNode> failedPersistence = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ScheduledExecutorService cleaner = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("holmes-log-ttl").factory());
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(10), Thread.ofVirtual().name("holmes-log-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    private final Semaphore capacity = new Semaphore(12);

    public LogInvestigationService(LogConfig config) throws IOException {
        this(config, config.logsFile() == null
                ? new GrafanaVictoriaLogsSource(config.grafanaUrl(), config.grafanaToken(), config.datasourceUid(), config.pageSize(), config.requestTimeout())
                : new FileLogSource(config.logsFile()),
                new FluxDropArchiveSink(config.fluxDropUrl(), config.fluxDropToken(), config.dataDir().resolve("spool"),
                        config.spoolBytes(), config.chunkBytes(), config.requestTimeout()));
    }

    public LogInvestigationService(LogConfig config, LogSource logs, FluxDropArchiveSink archive) throws IOException {
        this(config, logs, archive, TaskSource.file(config));
    }

    public LogInvestigationService(LogConfig config, TaskSource tasks) throws IOException {
        this(config, config.logsFile() == null
                ? new GrafanaVictoriaLogsSource(config.grafanaUrl(), config.grafanaToken(), config.datasourceUid(), config.pageSize(), config.requestTimeout())
                : new FileLogSource(config.logsFile()),
                new FluxDropArchiveSink(config.fluxDropUrl(), config.fluxDropToken(), config.dataDir().resolve("spool"),
                        config.spoolBytes(), config.chunkBytes(), config.requestTimeout()), tasks);
    }

    public LogInvestigationService(LogConfig config, LogSource logs, FluxDropArchiveSink archive, TaskSource tasks) throws IOException {
        this.config = config; this.logs = logs; this.archive = archive; this.tasks = java.util.Objects.requireNonNull(tasks);
        StoragePaths.createDirectory(config.dataDir());
        this.lockChannel = FileChannel.open(config.dataDir().resolve(".instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired = lockChannel.tryLock(); }
        catch (java.nio.channels.OverlappingFileLockException e) { lockChannel.close(); throw new IOException("日志数据目录已被另一个实例使用"); }
        if (acquired == null) { lockChannel.close(); throw new IOException("日志数据目录已被另一个实例使用"); }
        this.instanceLock = acquired;
        try {
            this.store = new JobStore(config.dataDir().resolve("reports"), config.storeBytes(), config.reportBytes());
            archive.cleanupAbandoned();
            var recovery = store.unfinished();
            if (recovery.size() > 12) throw new IOException("未完成检查点超过队列限制，请先检查旧实例状态");
            for (ObjectNode job : recovery) {
                capacity.acquireUninterruptibly();
                workers.execute(() -> runJob(job));
            }
            cleaner.scheduleWithFixedDelay(() -> {
                try { store.cleanup(); } catch (IOException ignored) { }
                failedPersistence.entrySet().removeIf(entry -> !Instant.parse(entry.getValue().path("expires_at").asText()).isAfter(Instant.now()));
            }, 60, 60, TimeUnit.SECONDS);
        } catch (IOException | RuntimeException e) {
            close(); throw e;
        }
    }

    public synchronized ObjectNode submit(JsonNode input) throws IOException {
        if (workers.isShutdown()) throw new Busy("日志服务已关闭");
        if (failedPersistence.size() >= 12) throw new Busy("存在未能落盘的终态，请先恢复存储空间");
        LogRequest request = LogRequest.parse(input, config);
        boolean pendingSelection = tasks.asynchronousSelection();
        TaskSnapshot.Selection selection;
        try { selection = pendingSelection ? new TaskSnapshot.Selection(List.of(), "", List.of()) : tasks.select(request); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("任务选择已中断"); }
        if (!capacity.tryAcquire()) throw new Busy("已有 2 个日志批次运行、10 个排队，请稍后重试");
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        ObjectNode job = Json.MAPPER.createObjectNode().put("batch_id", id).put("state", "QUEUED")
                .put("stage", "QUEUED").put("created_at", now.toString()).put("updated_at", now.toString())
                .put("expires_at", now.plus(config.retention()).toString()).put("source", request.source())
                .put("namespace", request.namespace()).put("requested_count", request.requestedCount())
                .put("selected_count", selection.tasks().size()).put("processed_count", 0)
                .put("selection_observed_at", selection.observedAt()).put("rule_version", LogAnalyzer.RULE_VERSION)
                .put("signature_is_root_cause", false).put("narrative_status", "NOT_REQUESTED")
                .put("archive_status", request.archiveRequired() ? "PENDING" : "NOT_REQUESTED")
                .put("retrieved_event_count", 0).put("response_bytes", 0);
        job.put("_selection_pending", pendingSelection).put("selection_status", pendingSelection ? "PENDING" : "READY");
        job.put("task_source", pendingSelection ? "scheduler_api" : "snapshot_file");
        job.set("request", input.deepCopy());
        ArrayNode savedTasks = job.putArray("_selected_tasks");
        selection.tasks().forEach(t -> savedTasks.add(taskJson(t)));
        job.putArray("tasks"); job.putArray("evidence"); job.putArray("clusters"); job.putArray("archive_parts");
        ArrayNode limitations = job.putArray("limitations");
        selection.limitations().forEach(limitations::add);
        if (config.logsFile() != null) limitations.add("LOG_SOURCE_FILE_FIXTURE");
        job.putArray("quality_flags");
        try {
            store.checkNewJobCapacity();
            store.save(id, job);
            workers.execute(() -> runJob(job));
        } catch (IOException | RuntimeException e) {
            capacity.release();
            throw e;
        }
        return publicView(job);
    }

    public ObjectNode get(String batchId) throws IOException {
        ObjectNode fallback = failedPersistence.get(batchId);
        if (fallback != null) {
            if (Instant.parse(fallback.path("expires_at").asText()).isAfter(Instant.now())) return publicView(fallback);
            failedPersistence.remove(batchId, fallback);
        }
        ObjectNode job = store.get(batchId); return job == null ? null : publicView(job);
    }

    /** CLI 与 HTTP 使用相同持久化和恢复路径。 */
    public ObjectNode await(String batchId) throws IOException, InterruptedException {
        while (true) {
            ObjectNode job = get(batchId);
            if (job == null) throw new IOException("批次已过期或不存在");
            if (!List.of("QUEUED", "RUNNING").contains(job.path("state").asText())) return job;
            Thread.sleep(50);
        }
    }

    private void runJob(ObjectNode original) {
        ObjectNode job = original.deepCopy();
        String id = job.path("batch_id").asText();
        try {
            LogRequest request = LogRequest.parse(job.path("request"), config);
            if (job.path("_selection_pending").asBoolean()) {
                job.put("state", "RUNNING").put("stage", "SELECTING_TASKS");
                persist(job);
                TaskSnapshot.Selection selection = tasks.select(request);
                job.put("selected_count", selection.tasks().size()).put("selection_observed_at", selection.observedAt());
                ArrayNode fixed = job.putArray("_selected_tasks");
                selection.tasks().forEach(task -> fixed.add(taskJson(task)));
                selection.limitations().forEach(value -> addUnique(job.withArray("limitations"), value));
                job.put("selection_complete", !selection.limitations().contains("TASK_SELECTION_INCOMPLETE"));
                if (!job.path("selection_complete").asBoolean()) job.put("collection_incomplete", true);
                job.put("selection_status", "READY").put("_selection_pending", false);
                // 先提交固定名单；之后恢复与日志分析不会重新读取线上任务改变分母。
                persist(job);
            }
            Instant deadline = Instant.now().plus(config.jobTimeout());
            Instant collectionDeadline = Instant.now().plusSeconds(Math.min(120, config.jobTimeout().toSeconds()));
            job.put("state", "RUNNING").put("stage", "COLLECTING");
            if (job.has("_archive_inflight_task")) {
                // 上一次可能已上传但尚未提交结果，不自动重传同一外部副作用。
                job.put("archive_incomplete", true);
                addUnique((ArrayNode) job.get("limitations"), "RAW_ARCHIVE_OUTCOME_UNVERIFIED_AFTER_RESTART");
                job.remove("_archive_inflight_task");
            }
            persist(job);
            List<Task> tasks = new ArrayList<>();
            for (JsonNode node : job.path("_selected_tasks")) tasks.add(taskFromJson(node));
            Set<String> completed = new LinkedHashSet<>();
            for (JsonNode node : job.path("tasks")) completed.add(node.path("task_ref").asText());
            for (Task task : tasks) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (completed.contains(task.taskRef())) continue;
                List<String> limitations = new ArrayList<>();
                List<LogEvent> events = new ArrayList<>();
                boolean complete = true;
                LinkedHashSet<String> identifiers = new LinkedHashSet<>();
                identifiers.add(task.taskId());
                task.attempts().forEach(a -> identifiers.add(a.promptId()));
                int remainingEvents = config.maxEvents() - job.path("retrieved_event_count").asInt();
                long remainingBytes = config.maxBytes() - job.path("response_bytes").asLong();
                if (remainingEvents <= 0 || remainingBytes <= 0 || !Instant.now().isBefore(collectionDeadline)
                        || job.path("collection_stopped").asBoolean()) {
                    complete = false; limitations.add(job.path("collection_stopped").asBoolean()
                            ? "SOURCE_UNAVAILABLE_FOR_REMAINDER" : "COLLECTION_BUDGET_EXHAUSTED");
                } else {
                    try {
                        FetchResult result = logs.fetch(new LogQuery(request.namespace(), List.copyOf(identifiers), request.from(), request.to(), Map.of()),
                                remainingEvents, remainingBytes, collectionDeadline);
                        events.addAll(result.events());
                        complete = result.complete(); limitations.addAll(result.limitations());
                        recordUsage(job, result);
                        // 补取同日志流执行上下文；无 ID 的普通日志不会升级为确定归因。
                        List<Map<String, String>> streams = streams(events);
                        if (streams.size() > 8) { complete = false; limitations.add("CONTEXT_STREAM_LIMIT"); }
                        Attempt attempt = task.finalAttempt();
                        if (attempt != null) {
                            Instant contextFrom = later(request.from(), attempt.startedAt().minusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
                            Instant endMargin = attempt.endedAt().plusSeconds(120);
                            Instant roundedEnd = endMargin.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
                            if (!roundedEnd.equals(endMargin)) roundedEnd = roundedEnd.plusMillis(1);
                            Instant contextTo = earlier(request.to(), roundedEnd);
                            for (Map<String, String> stream : streams.stream().limit(8).toList()) {
                                if (!contextFrom.isBefore(contextTo)) break;
                                remainingEvents = config.maxEvents() - job.path("retrieved_event_count").asInt();
                                remainingBytes = config.maxBytes() - job.path("response_bytes").asLong();
                                if (remainingEvents <= 0 || remainingBytes <= 0 || !Instant.now().isBefore(collectionDeadline)) {
                                    complete = false; limitations.add("CONTEXT_BUDGET_EXHAUSTED"); break;
                                }
                                FetchResult context = logs.fetch(new LogQuery(request.namespace(), List.of(), contextFrom, contextTo, stream),
                                        remainingEvents, remainingBytes, collectionDeadline);
                                // 按记录多重集抵消两次查询重叠，不能用 ID 子串吞掉普通堆栈或真实重复行。
                                Map<LogEvent, Integer> seen = new java.util.HashMap<>();
                                events.forEach(e -> seen.merge(e, 1, Integer::sum));
                                for (LogEvent e : context.events()) {
                                    int duplicate = seen.getOrDefault(e, 0);
                                    if (duplicate > 0) seen.put(e, duplicate - 1); else events.add(e);
                                }
                                complete &= context.complete(); limitations.addAll(context.limitations()); recordUsage(job, context);
                            }
                        }
                    } catch (IOException e) {
                        complete = false; limitations.add("LOG_QUERY_FAILED");
                        String message = e.getMessage() == null ? "" : e.getMessage();
                        if (message.matches("Grafana 查询失败，HTTP [1-5][0-9]{2}"))
                            limitations.add("GRAFANA_HTTP_" + message.substring(message.length() - 3));
                        // 异常不能证明完整的读取量，停止后续采集而不伪造已读取字节数。
                        job.put("collection_stopped", true).put("bytes_accounting_incomplete", true);
                    }
                }
                Attempt last = task.finalAttempt();
                if (last != null && (last.startedAt().isBefore(request.from()) || !last.endedAt().isBefore(request.to()))) {
                    complete = false; limitations.add("ATTEMPT_OUTSIDE_QUERY_WINDOW");
                }
                ObjectNode single = new LogAnalyzer().analyze(List.of(task), events, request.namespace(), complete, limitations);
                ((ObjectNode) single.path("tasks").get(0)).put("analysis_status", complete
                        && task.associationScope() != LogModels.AssociationScope.TASK_ONLY ? "ANALYZED" : "INCOMPLETE_EVIDENCE");
                ((ArrayNode) job.get("tasks")).add(single.path("tasks").get(0));
                single.path("evidence").forEach(((ArrayNode) job.get("evidence"))::add);
                mergeStrings((ArrayNode) job.get("quality_flags"), single.path("quality_flags"));
                mergeStrings((ArrayNode) job.get("limitations"), single.path("limitations"));
                job.put("observed_error_event_count", job.path("observed_error_event_count").asLong() + single.path("observed_error_event_count").asLong());
                if (!complete) job.put("collection_incomplete", true);
                if (single.path("tasks").get(0).path("signature").asText().equals("INCOMPLETE")) job.put("collection_incomplete", true);
                if (request.archiveRequired() && !events.isEmpty()) {
                    job.put("stage", "ARCHIVING");
                    job.put("_archive_inflight_task", task.taskRef());
                    job.put("processed_count", job.path("tasks").size());
                    rebuildClusters(job); compact(job); persist(job);
                    var result = archive.archive(id + "-" + task.taskRef(), events, deadline);
                    ArrayNode rawRefs = ((ObjectNode) single.path("tasks").get(0)).putArray("raw_refs");
                    result.parts().forEach(part -> {
                        String partId = task.taskRef() + "-" + part.partId();
                        ObjectNode partNode = ((ArrayNode) job.get("archive_parts")).addObject()
                                .put("part_id", partId).put("task_ref", task.taskRef()).put("bytes", part.bytes())
                                .put("sha256", part.sha256()).put("state", part.state()).put("message", part.message());
                        if (part.downloadUrl() == null) partNode.putNull("download_url"); else partNode.put("download_url", part.downloadUrl());
                        partNode.putNull("expires_at"); rawRefs.add(partId);
                    });
                    mergeStrings((ArrayNode) job.get("limitations"), Json.MAPPER.valueToTree(result.limitations()));
                    if (!result.state().equals("AVAILABLE")) job.put("archive_incomplete", true);
                    job.remove("_archive_inflight_task");
                } else if (request.archiveRequired() && (!complete || events.isEmpty())) {
                    job.put("archive_incomplete", true);
                    addUnique((ArrayNode) job.get("limitations"), "NO_RAW_EVENTS_TO_ARCHIVE");
                }
                job.put("processed_count", job.path("tasks").size()).put("stage", "COLLECTING");
                rebuildClusters(job);
                compact(job);
                persist(job); // 已完成任务的统计、关键证据与归档引用一起提交。
            }
            boolean incomplete = job.path("collection_incomplete").asBoolean() || job.path("archive_incomplete").asBoolean();
            job.put("state", incomplete ? "PARTIAL" : "COMPLETED").put("stage", "DONE");
            if (request.archiveRequired()) job.put("archive_status", job.path("archive_incomplete").asBoolean() ? "RAW_ARCHIVE_UNAVAILABLE"
                    : job.path("archive_parts").isEmpty() ? "EMPTY" : "AVAILABLE");
            persist(job);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 保留 RUNNING 与已提交检查点；下次启动继续固定样本。
        } catch (Exception e) {
            if (job.path("_selection_pending").asBoolean()) {
                job.put("selection_status", "FAILED");
                addUnique(job.withArray("limitations"), "TASK_SELECTION_FAILED");
                if (e.getMessage() != null && e.getMessage().matches("(?:SCHEDULER_[A-Z0-9_]+|TASK_SELECTION_INCOMPLETE)"))
                    addUnique(job.withArray("limitations"), e.getMessage());
            }
            try { finishUnprocessed(job); } catch (RuntimeException ignored) { }
            job.put("state", job.path("processed_count").asInt() > 0 ? "PARTIAL" : "FAILED").put("stage", "STOPPED");
            if (job.path("request").path("raw_archive").asText("none").equals("required"))
                job.put("archive_status", job.has("_archive_inflight_task") ? "UNVERIFIED" : "RAW_ARCHIVE_UNAVAILABLE");
            addUnique((ArrayNode) job.withArray("limitations"), e instanceof JobStore.StorageLimit ? "STORAGE_LIMIT" : "INTERNAL_OR_SOURCE_ERROR");
            try { compact(job); persist(job); }
            catch (IOException failed) {
                ObjectNode minimal = minimalTerminal(job);
                try { persist(minimal); }
                catch (IOException unavailable) {
                    minimal.put("checkpoint_status", "UNAVAILABLE");
                    failedPersistence.put(id, minimal); // 客户端可得到明确终态，避免永久等待旧 RUNNING。
                }
            }
        } finally { capacity.release(); }
    }

    private void persist(ObjectNode job) throws IOException {
        job.put("updated_at", Instant.now().toString());
        store.save(job.path("batch_id").asText(), job);
    }
    private static void recordUsage(ObjectNode job, FetchResult result) {
        job.put("retrieved_event_count", job.path("retrieved_event_count").asInt() + result.events().size());
        job.put("response_bytes", job.path("response_bytes").asLong() + result.responseBytes());
    }
    private static List<Map<String, String>> streams(List<LogEvent> events) {
        LinkedHashSet<Map<String, String>> streams = new LinkedHashSet<>();
        for (LogEvent event : events) {
            Map<String, String> labels = event.labels();
            String pod = first(labels, "kubernetes.pod_name", "pod", "pod_name");
            if (pod.isBlank()) continue;
            Map<String, String> stream = new LinkedHashMap<>(); stream.put("kubernetes.pod_name", pod);
            String container = first(labels, "kubernetes.container_name", "container", "container_name");
            if (!container.isBlank()) stream.put("kubernetes.container_name", container);
            String podUid = first(labels, "kubernetes.pod_uid", "pod_uid");
            if (!podUid.isBlank()) stream.put("kubernetes.pod_uid", podUid);
            String restart = first(labels, "kubernetes.container_restart_count", "restart", "restart_count");
            if (!restart.isBlank()) stream.put("kubernetes.container_restart_count", restart);
            streams.add(Map.copyOf(stream));
        }
        return List.copyOf(streams);
    }
    private static String first(Map<String, String> map, String... keys) {
        for (String key : keys) if (map.containsKey(key) && !map.get(key).isBlank()) return map.get(key);
        return "";
    }
    private static void finishUnprocessed(ObjectNode job) {
        Set<String> done = new LinkedHashSet<>();
        for (JsonNode task : job.path("tasks")) done.add(task.path("task_ref").asText());
        for (JsonNode selected : job.path("_selected_tasks")) {
            if (done.contains(selected.path("task_ref").asText())) continue;
            Task task = taskFromJson(selected);
            ObjectNode result = new LogAnalyzer().analyze(List.of(task), List.of(), job.path("namespace").asText(), false,
                    List.of("STOPPED_BEFORE_TASK_ANALYSIS"));
            ObjectNode placeholder = (ObjectNode) result.path("tasks").get(0);
            placeholder.put("signature", "INCOMPLETE").put("analysis_status", "NOT_PROCESSED");
            ((ArrayNode) job.get("tasks")).add(placeholder);
            mergeStrings((ArrayNode) job.get("quality_flags"), result.path("quality_flags"));
            mergeStrings((ArrayNode) job.get("limitations"), result.path("limitations"));
        }
        job.put("reported_count", job.path("tasks").size()).put("collection_incomplete", true);
        rebuildClusters(job);
    }
    private static void rebuildClusters(ObjectNode job) {
        Map<String, List<String>> groups = new TreeMap<>();
        for (JsonNode task : job.path("tasks")) groups.computeIfAbsent(task.path("signature").asText("UNKNOWN"), k -> new ArrayList<>())
                .add(task.path("task_ref").asText());
        ArrayNode clusters = job.putArray("clusters");
        int total = job.path("selected_count").asInt();
        groups.forEach((signature, refs) -> {
            ObjectNode cluster = clusters.addObject().put("signature", signature).put("task_count", refs.size());
            if (total == 0) cluster.putNull("ratio"); else cluster.put("ratio", (double) refs.size() / total);
            ArrayNode list = cluster.putArray("task_refs"); refs.forEach(list::add);
        });
        Map<String, Set<String>> observed = new TreeMap<>();
        for (JsonNode task : job.path("tasks")) {
            if (!task.path("association_scope").asText().equals("TASK_ONLY")) continue;
            for (JsonNode signature : task.path("observed_signatures"))
                observed.computeIfAbsent(signature.asText(), ignored -> new LinkedHashSet<>()).add(task.path("task_ref").asText());
        }
        ArrayNode candidates = job.putArray("observed_clusters");
        observed.forEach((signature, refs) -> {
            ObjectNode cluster = candidates.addObject().put("signature", signature).put("task_count", refs.size())
                    .put("ratio", total == 0 ? 0d : (double) refs.size() / total).put("candidate_only", true);
            refs.forEach(cluster.putArray("task_refs")::add);
        });
    }
    private void compact(ObjectNode job) throws IOException {
        if (Json.MAPPER.writeValueAsBytes(job).length <= config.reportBytes()) return;
        addUnique((ArrayNode) job.get("quality_flags"), "REPORT_TRUNCATED");
        job.put("collection_incomplete", true);
        for (JsonNode evidence : job.path("evidence")) {
            ObjectNode e = (ObjectNode) evidence;
            String excerpt = e.path("excerpt").asText();
            if (excerpt.length() > 256) {
                e.put("excerpt", excerpt.substring(0, 256));
                e.put("content_sha256", TaskSnapshot.hex(e.path("excerpt").asText()));
                e.put("excerpt_truncated", true);
            }
        }
        for (JsonNode task : job.path("tasks")) {
            ArrayNode timeline = ((ObjectNode) task).withArray("timeline");
            while (timeline.size() > 3) timeline.remove(timeline.size() - 1);
        }
    }
    private static void mergeStrings(ArrayNode target, JsonNode values) { for (JsonNode v : values) addUnique(target, v.asText()); }
    private static void addUnique(ArrayNode target, String value) {
        for (JsonNode existing : target) if (existing.asText().equals(value)) return;
        if (target.size() < 128) target.add(value);
    }
    private static ObjectNode taskJson(Task task) {
        ObjectNode node = Json.MAPPER.createObjectNode().put("task_ref", task.taskRef()).put("task_id", task.taskId())
                .put("status", task.status()).put("final_attempt_id", task.finalAttemptId())
                .put("failure_reason", LogAnalyzer.redact(task.failureReason()))
                .put("failed_at", task.failedAt() == null ? null : task.failedAt().toString())
                .put("status_version", task.statusVersion()).put("association_scope", task.associationScope().name());
        ArrayNode attempts = node.putArray("attempts");
        for (Attempt a : task.attempts()) attempts.addObject().put("attempt_id", a.id()).put("prompt_id", a.promptId())
                .put("started_at", a.startedAt().toString()).put("ended_at", a.endedAt().toString()).put("instance_id", a.instanceId());
        return node;
    }
    private static Task taskFromJson(JsonNode node) {
        List<Attempt> attempts = new ArrayList<>();
        for (JsonNode a : node.path("attempts")) attempts.add(new Attempt(a.path("attempt_id").asText(), a.path("prompt_id").asText(),
                Instant.parse(a.path("started_at").asText()), Instant.parse(a.path("ended_at").asText()), a.path("instance_id").asText()));
        return new Task(node.path("task_ref").asText(), node.path("task_id").asText(), node.path("status").asText(), attempts,
                node.path("final_attempt_id").asText(), node.path("failure_reason").asText(),
                node.hasNonNull("failed_at") ? Instant.parse(node.path("failed_at").asText()) : null, node.path("status_version").asText(),
                LogModels.AssociationScope.valueOf(node.path("association_scope").asText("VERIFIED_ATTEMPTS")));
    }
    private static ObjectNode publicView(ObjectNode job) {
        ObjectNode result = job.deepCopy(); result.remove(List.of("_selected_tasks", "_archive_inflight_task", "_selection_pending")); return result;
    }
    private static Instant earlier(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    private static Instant later(Instant a, Instant b) { return a.isAfter(b) ? a : b; }
    private static ObjectNode minimalTerminal(ObjectNode job) {
        ObjectNode minimal = Json.MAPPER.createObjectNode();
        for (String key : List.of("batch_id", "created_at", "expires_at", "source", "namespace", "selected_count", "processed_count"))
            if (job.has(key)) minimal.set(key, job.get(key));
        minimal.put("state", "PARTIAL").put("stage", "STOPPED").put("results_unavailable", true)
                .put("checkpoint_status", "MINIMAL_TERMINAL");
        minimal.putArray("limitations").add("STORAGE_LIMIT_OR_CHECKPOINT_FAILURE");
        return minimal;
    }
    @Override public synchronized void close() {
        cleaner.shutdownNow(); workers.shutdownNow();
        try {
            if (workers.awaitTermination(5, TimeUnit.SECONDS)) { instanceLock.release(); lockChannel.close(); }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (IOException ignored) { }
    }
    public static final class Busy extends IllegalStateException { public Busy(String message) { super(message); } }
}
