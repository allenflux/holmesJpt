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
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;

/** 采集和计数不依赖 LLM；每任务原子检查点支持固定样本的单实例恢复。 */
public final class LogInvestigationService implements AutoCloseable {
    private final LogConfig config;
    private final LogSource logs;
    private final TaskSource tasks;
    private final FluxDropArchiveSink archive;
    private final LogNarrativeGenerator narrativeGenerator;
    private final Set<String> activeJobs = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<String> activeNarratives = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ExecutorService modelWorkers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
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
        this(config, tasks, null);
    }

    public LogInvestigationService(LogConfig config, TaskSource tasks, LogNarrativeGenerator generator) throws IOException {
        this(config, config.logsFile() == null
                ? new GrafanaVictoriaLogsSource(config.grafanaUrl(), config.grafanaToken(), config.datasourceUid(), config.pageSize(), config.requestTimeout())
                : new FileLogSource(config.logsFile()),
                new FluxDropArchiveSink(config.fluxDropUrl(), config.fluxDropToken(), config.dataDir().resolve("spool"),
                        config.spoolBytes(), config.chunkBytes(), config.requestTimeout()), tasks, generator);
    }

    public LogInvestigationService(LogConfig config, LogSource logs, FluxDropArchiveSink archive, TaskSource tasks) throws IOException {
        this(config, logs, archive, tasks, null);
    }

    public LogInvestigationService(LogConfig config, LogSource logs, FluxDropArchiveSink archive, TaskSource tasks,
                                   LogNarrativeGenerator generator) throws IOException {
        this.config = config; this.logs = logs; this.archive = archive; this.tasks = java.util.Objects.requireNonNull(tasks);
        this.narrativeGenerator = generator;
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
                if (narrativeBusy(job)) interruptAbandonedNarrative(job);
                capacity.acquireUninterruptibly();
                activeJobs.add(job.path("batch_id").asText());
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
        if (request.narrative().equals("llm") && narrativeGenerator == null) throw new NarrativeUnavailable();
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
                .put("signature_is_root_cause", false).put("narrative_status", request.narrative().equals("llm") ? "PENDING" : "NOT_REQUESTED")
                .put("narrative_incomplete", false)
                .put("collection_incomplete", false).put("attribution_incomplete", false)
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
            activeJobs.add(id);
            workers.execute(() -> runJob(job));
        } catch (IOException | RuntimeException e) {
            activeJobs.remove(id);
            capacity.release();
            throw e;
        }
        return publicView(job);
    }

    /** 自动调查沿用现有只读批次预算，时间边界在提交时由服务器确定。 */
    public synchronized ObjectNode submitAutomatic() throws IOException {
        Instant to = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        if (config.tasksFile() != null) {
            JsonNode snapshot;
            try (var input = Files.newInputStream(config.tasksFile())) {
                byte[] bytes = input.readNBytes((8 << 20) + 1);
                if (bytes.length > 8 << 20) throw new TaskSnapshot.Unprocessable("任务快照超过 8 MiB");
                snapshot = Json.MAPPER.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
            } catch (com.fasterxml.jackson.core.JacksonException e) { throw new TaskSnapshot.Unprocessable("任务快照 JSON 无效"); }
            if (snapshot == null || !snapshot.isObject() || !snapshot.path("observed_at").isTextual())
                throw new TaskSnapshot.Unprocessable("任务快照需要有效 observed_at");
            try {
                // 快照回放以观察时间为准；多留一毫秒，包含恰在 observed_at 失败的任务。
                to = Instant.parse(snapshot.path("observed_at").asText()).plusMillis(1).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            } catch (java.time.DateTimeException | ArithmeticException e) { throw new TaskSnapshot.Unprocessable("任务快照 observed_at 无效"); }
        }
        Instant from;
        try { from = to.minus(Duration.ofHours(24)); }
        catch (java.time.DateTimeException | ArithmeticException e) { throw new TaskSnapshot.Unprocessable("任务快照 observed_at 无效"); }
        String namespace = config.namespaces().contains("prod") ? "prod" : config.namespaces().stream().sorted().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("缺少授权 namespace"));
        ObjectNode input = Json.MAPPER.createObjectNode().put("source", config.source()).put("namespace", namespace)
                .put("attempt_scope", "final_failed").put("raw_archive", "none").put("narrative", narrativeGenerator == null ? "none" : "llm")
                .put("log_window", "automatic");
        input.putObject("selection").put("mode", "latest_failed").put("requested_count", 50)
                .put("from", from.toString()).put("to", to.toString());
        return submit(input);
    }

    public synchronized ObjectNode get(String batchId) throws IOException {
        ObjectNode fallback = failedPersistence.get(batchId);
        if (fallback != null) {
            if (Instant.parse(fallback.path("expires_at").asText()).isAfter(Instant.now())) return publicView(fallback);
            failedPersistence.remove(batchId, fallback);
        }
        ObjectNode job = store.get(batchId);
        if (job != null && narrativeBusy(job) && !activeJobs.contains(batchId) && !activeNarratives.contains(batchId))
            interruptAbandonedNarrative(job);
        return job == null ? null : publicView(job);
    }

    /** 已完成批次按需总结；复用现有证据，不重新选择任务或采集日志。 */
    public synchronized ObjectNode requestNarrative(String batchId) throws IOException {
        if (workers.isShutdown()) throw new Busy("日志服务已关闭");
        ObjectNode visible = get(batchId);
        if (visible == null) return null;
        if (visible.path("narrative_status").asText().equals("AVAILABLE") || activeNarratives.contains(batchId)
                || narrativeBusy(visible) && activeJobs.contains(batchId)) return visible;
        if (List.of("QUEUED", "RUNNING").contains(visible.path("state").asText()))
            throw new NarrativeConflict();
        ObjectNode job = store.get(batchId);
        if (job == null) throw new IOException("日志批次存储不可用");
        if (narrativeGenerator == null) {
            finishNarrative(job, "DISABLED", "NARRATIVE_DISABLED");
            throw new NarrativeUnavailable();
        }
        if (!LogNarrativeGenerator.hasEvidence(job)) {
            finishNarrative(job, "SKIPPED_NO_EVIDENCE", null);
            return publicView(job);
        }
        if (!capacity.tryAcquire()) throw new Busy("日志批次处理容量已满，请稍后重试");
        activeNarratives.add(batchId);
        try {
            Instant deadline = Instant.now().plus(config.jobTimeout());
            job.put("_narrative_base_stage", job.path("stage").asText("DONE"));
            job.put("narrative_status", "PENDING").put("narrative_incomplete", false);
            job.remove(List.of("narrative", "narrative_error_code", "narrative_diagnostic"));
            persist(job);
            failedPersistence.remove(batchId);
            workers.execute(() -> {
                try { runNarrative(batchId, deadline); }
                finally { activeNarratives.remove(batchId); capacity.release(); }
            });
            return publicView(job);
        } catch (IOException | RuntimeException e) {
            activeNarratives.remove(batchId); capacity.release(); throw e;
        }
    }

    /** CLI 与 HTTP 使用相同持久化和恢复路径。 */
    public ObjectNode await(String batchId) throws IOException, InterruptedException {
        while (true) {
            ObjectNode job = get(batchId);
            if (job == null) throw new IOException("批次已过期或不存在");
            if (!List.of("QUEUED", "RUNNING").contains(job.path("state").asText()) && !narrativeBusy(job)) return job;
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
            Set<String> aliasConflicts = taskAliasConflicts(tasks);
            Set<String> completed = new LinkedHashSet<>();
            for (JsonNode node : job.path("tasks")) {
                completed.add(node.path("task_ref").asText());
                if (node.path("signature").asText().equals("INCOMPLETE")) job.put("attribution_incomplete", true);
            }
            for (Task task : tasks) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (completed.contains(task.taskRef())) continue;
                List<String> limitations = new ArrayList<>();
                List<LogEvent> events = new ArrayList<>();
                boolean complete = true;
                String collectionStatus = null;
                LogLookupPlan.Window window = LogLookupPlan.forTask(task, request, Instant.now());
                String schedulerTaskId = task.associationScope() == LogModels.AssociationScope.TASK_ONLY && task.schedulerFacts() != null
                        && LogRequest.validId(task.schedulerFacts().recordId()) ? task.schedulerFacts().recordId() : "";
                boolean sourceAlias = !schedulerTaskId.isEmpty() && !schedulerTaskId.equals(task.taskId());
                ObjectNode lookup = Json.MAPPER.createObjectNode().put("strategy", window.basis())
                        .put("mapping_status", sourceAlias ? "SOURCE_RECORD_TASK_ID" : task.associationScope().name())
                        .put("logical_task_id", task.taskId()).put("scheduler_task_id", schedulerTaskId)
                        .put("searched_all_pods", false).put("query_count", 0);
                ArrayNode lookupWindows = lookup.putArray("windows");
                if (window.truncated()) { complete = false; limitations.add("TASK_LIFECYCLE_WINDOW_TRUNCATED"); }
                LinkedHashSet<String> identifiers = new LinkedHashSet<>();
                identifiers.add(task.taskId());
                if (sourceAlias) identifiers.add(schedulerTaskId);
                task.attempts().forEach(a -> identifiers.add(a.promptId()));
                int remainingEvents = config.maxEvents() - job.path("retrieved_event_count").asInt();
                long remainingBytes = config.maxBytes() - job.path("response_bytes").asLong();
                if (aliasConflicts.contains(task.taskRef())) {
                    complete = false; limitations.add("TASK_ID_ALIAS_CONFLICT"); collectionStatus = "SKIPPED";
                } else if (remainingEvents <= 0 || remainingBytes <= 0 || !Instant.now().isBefore(collectionDeadline)
                        || job.path("collection_stopped").asBoolean()) {
                    complete = false; limitations.add(job.path("collection_stopped").asBoolean()
                            ? "SOURCE_UNAVAILABLE_FOR_REMAINDER" : "COLLECTION_BUDGET_EXHAUSTED");
                    if (job.path("collection_stopped").asBoolean()) collectionStatus = "SKIPPED";
                } else {
                    try {
                        lookup.put("query_count", 1);
                        ObjectNode primaryQuery = lookupWindows.addObject().put("from", window.from().toString()).put("to", window.to().toString())
                                .put("basis", window.basis()).put("complete", false).putNull("returned_count");
                        FetchResult result = logs.fetch(new LogQuery(request.namespace(), List.copyOf(identifiers), window.from(), window.to(), Map.of()),
                                remainingEvents, remainingBytes, collectionDeadline);
                        lookup.put("searched_all_pods", true);
                        primaryQuery.put("returned_count", result.events().size()).put("complete", result.complete());
                        events.addAll(result.events());
                        complete &= result.complete(); limitations.addAll(result.limitations());
                        recordUsage(job, result);
                        // 补取同日志流执行上下文；无 ID 的普通日志不会升级为确定归因。
                        List<Map<String, String>> streams = streams(events);
                        Attempt attempt = task.finalAttempt();
                        if (attempt != null) {
                            if (streams.size() > 8) { complete = false; limitations.add("CONTEXT_STREAM_LIMIT"); }
                            Instant contextFrom = later(window.from(), attempt.startedAt().minusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
                            Instant endMargin = attempt.endedAt().plusSeconds(120);
                            Instant roundedEnd = endMargin.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
                            if (!roundedEnd.equals(endMargin)) roundedEnd = roundedEnd.plusMillis(1);
                            Instant contextTo = earlier(window.to(), roundedEnd);
                            for (Map<String, String> stream : streams.stream().limit(8).toList()) {
                                if (!contextFrom.isBefore(contextTo)) break;
                                remainingEvents = config.maxEvents() - job.path("retrieved_event_count").asInt();
                                remainingBytes = config.maxBytes() - job.path("response_bytes").asLong();
                                if (remainingEvents <= 0 || remainingBytes <= 0 || !Instant.now().isBefore(collectionDeadline)) {
                                    complete = false; limitations.add("CONTEXT_BUDGET_EXHAUSTED"); break;
                                }
                                lookup.put("query_count", lookup.path("query_count").asInt() + 1);
                                ObjectNode contextQuery = lookupWindows.addObject().put("from", contextFrom.toString()).put("to", contextTo.toString())
                                        .put("basis", "VERIFIED_STREAM_CONTEXT").putNull("returned_count").put("complete", false);
                                FetchResult context = logs.fetch(new LogQuery(request.namespace(), List.of(), contextFrom, contextTo, stream),
                                        remainingEvents, remainingBytes, collectionDeadline);
                                contextQuery.put("returned_count", context.events().size()).put("complete", context.complete());
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
                        collectionStatus = "FAILED";
                        String message = e.getMessage() == null ? "" : e.getMessage();
                        if (message.matches("Grafana 查询失败，HTTP [1-5][0-9]{2}"))
                            limitations.add("GRAFANA_HTTP_" + message.substring(message.length() - 3));
                        // 异常不能证明完整的读取量，停止后续采集而不伪造已读取字节数。
                        job.put("collection_stopped", true).put("bytes_accounting_incomplete", true);
                    }
                }
                Attempt last = task.finalAttempt();
                if (last != null && (last.startedAt().isBefore(window.from()) || !last.endedAt().isBefore(window.to()))) {
                    complete = false; limitations.add("ATTEMPT_OUTSIDE_QUERY_WINDOW");
                }
                ObjectNode single = new LogAnalyzer().analyze(List.of(task), events, request.namespace(), complete, limitations);
                ObjectNode taskResult = (ObjectNode) single.path("tasks").get(0);
                taskResult.put("analysis_status", complete
                        && task.associationScope() != LogModels.AssociationScope.TASK_ONLY ? "ANALYZED" : "INCOMPLETE_EVIDENCE");
                if (collectionStatus == null) collectionStatus = !complete ? "INCOMPLETE" : events.isEmpty() ? "NO_MATCHES" : "COLLECTED";
                // 采集计数只来自实际返回并合并的事件，不等于已验证归属的证据数。
                taskResult.put("collection_status", collectionStatus).put("collection_event_count", events.size());
                lookup.put("status", "FAILED".equals(collectionStatus) ? "QUERY_FAILED" : !complete ? "INCOMPLETE" : events.isEmpty() ? "NO_MATCH" : "MATCHED");
                taskResult.set("log_lookup", lookup);
                if (task.schedulerFacts() != null) taskResult.set("scheduler_facts", TaskSnapshot.schedulerFactsJson(task.schedulerFacts()));
                limitations.stream().distinct().forEach(taskResult.putArray("collection_limitations")::add);
                ((ArrayNode) job.get("tasks")).add(single.path("tasks").get(0));
                single.path("evidence").forEach(((ArrayNode) job.get("evidence"))::add);
                mergeStrings((ArrayNode) job.get("quality_flags"), single.path("quality_flags"));
                mergeStrings((ArrayNode) job.get("limitations"), single.path("limitations"));
                job.put("observed_error_event_count", job.path("observed_error_event_count").asLong() + single.path("observed_error_event_count").asLong());
                if (!complete) job.put("collection_incomplete", true);
                if (taskResult.path("signature").asText().equals("INCOMPLETE")) job.put("attribution_incomplete", true);
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
            boolean incomplete = job.path("collection_incomplete").asBoolean() || job.path("attribution_incomplete").asBoolean()
                    || !job.path("selection_complete").asBoolean(true) || job.path("archive_incomplete").asBoolean();
            job.put("state", incomplete ? "PARTIAL" : "COMPLETED").put("stage", "DONE");
            if (request.archiveRequired()) job.put("archive_status", job.path("archive_incomplete").asBoolean() ? "RAW_ARCHIVE_UNAVAILABLE"
                    : job.path("archive_parts").isEmpty() ? "EMPTY" : "AVAILABLE");
            persist(job);
            if (request.narrative().equals("llm") && job.path("narrative_status").asText().equals("PENDING")) {
                activeNarratives.add(id);
                try { runNarrative(id, deadline); }
                finally { activeNarratives.remove(id); }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 保留 RUNNING 与已提交检查点；下次启动继续固定样本。
        } catch (Exception e) {
            if (narrativeBusy(job)) job.put("narrative_status", "INTERRUPTED").put("narrative_incomplete", true)
                    .put("narrative_error_code", "NARRATIVE_RULES_INTERRUPTED");
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
        } finally { activeJobs.remove(id); capacity.release(); }
    }

    private static boolean narrativeBusy(JsonNode job) {
        return List.of("PENDING", "RUNNING").contains(job.path("narrative_status").asText());
    }

    private void interruptAbandonedNarrative(ObjectNode job) throws IOException {
        finishNarrative(job, "INTERRUPTED", "NARRATIVE_INTERRUPTED");
    }

    private void finishNarrative(ObjectNode job, String status, String code) throws IOException {
        finishNarrative(job, status, code, code == null ? null : unknownNarrativeDiagnostic(code));
    }

    private void finishNarrative(ObjectNode job, String status, String code, ObjectNode diagnostic) throws IOException {
        job.put("narrative_status", status).put("narrative_incomplete", code != null);
        if (code == null) job.remove("narrative_error_code"); else job.put("narrative_error_code", code);
        if (diagnostic == null) job.remove("narrative_diagnostic"); else job.set("narrative_diagnostic", diagnostic.deepCopy());
        if (!status.equals("AVAILABLE")) job.remove("narrative");
        if (job.has("_narrative_base_stage")) job.put("stage", job.path("_narrative_base_stage").asText("DONE"));
        else if (job.path("stage").asText().equals("SUMMARIZING")) job.put("stage", "DONE");
        job.remove("_narrative_base_stage");
        boundNarrativeDiagnostic(job);
        persist(job);
        failedPersistence.remove(job.path("batch_id").asText());
    }

    private void runNarrative(String batchId, Instant jobDeadline) {
        ObjectNode job = null;
        Future<ObjectNode> pending = null;
        boolean interrupted = false;
        try {
            job = store.get(batchId);
            if (job == null) return;
            if (narrativeGenerator == null) { finishNarrative(job, "DISABLED", "NARRATIVE_DISABLED"); return; }
            if (!LogNarrativeGenerator.hasEvidence(job)) { finishNarrative(job, "SKIPPED_NO_EVIDENCE", null); return; }
            Instant deadline = earlier(jobDeadline, Instant.now().plusSeconds(60));
            if (!Instant.now().isBefore(deadline)) { finishNarrative(job, "FAILED", "NARRATIVE_TIMEOUT"); return; }
            if (!job.has("_narrative_base_stage")) job.put("_narrative_base_stage", job.path("stage").asText("DONE"));
            job.put("narrative_status", "RUNNING").put("stage", "SUMMARIZING");
            persist(job);
            // 模型只能读取副本，并且输出只写入 narrative，不能改动规则数量、签名或归属。
            ObjectNode input = publicView(job);
            pending = modelWorkers.submit(() -> narrativeGenerator.generate(input));
            ObjectNode generated = pending.get(Math.max(1, Duration.between(Instant.now(), deadline).toNanos()), TimeUnit.NANOSECONDS);
            ObjectNode candidate = job.deepCopy();
            candidate.set("narrative", generated);
            candidate.put("narrative_status", "AVAILABLE").put("narrative_incomplete", false);
            candidate.remove(List.of("narrative_error_code", "narrative_diagnostic"));
            candidate.put("stage", candidate.path("_narrative_base_stage").asText("DONE"));
            candidate.remove("_narrative_base_stage");
            if (Json.MAPPER.writeValueAsBytes(candidate).length > config.reportBytes()) {
                finishNarrative(job, "FAILED", "NARRATIVE_REPORT_LIMIT"); return;
            }
            // 不让报告压缩逻辑把模型输出超限误标为日志采集不完整。
            persist(candidate);
            failedPersistence.remove(batchId);
        } catch (InterruptedException e) {
            interrupted = true;
            if (job != null) saveNarrativeFailure(job, "INTERRUPTED", "NARRATIVE_INTERRUPTED");
        } catch (TimeoutException e) {
            if (job != null) saveNarrativeFailure(job, "FAILED", "NARRATIVE_TIMEOUT");
        } catch (ExecutionException e) {
            if (job != null && e.getCause() instanceof LogNarrativeFailure failure)
                saveNarrativeFailure(job, "FAILED", failure.code(), failure.diagnostic());
            else if (job != null) saveNarrativeFailure(job, "FAILED", "NARRATIVE_GENERATION_FAILED");
        } catch (IOException | RuntimeException e) {
            if (job != null) saveNarrativeFailure(job, "FAILED", "NARRATIVE_GENERATION_FAILED");
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void saveNarrativeFailure(ObjectNode job, String status, String code) {
        saveNarrativeFailure(job, status, code, unknownNarrativeDiagnostic(code));
    }

    private void saveNarrativeFailure(ObjectNode job, String status, String code, ObjectNode diagnostic) {
        try { finishNarrative(job, status, code, diagnostic); }
        catch (IOException unavailable) { failedPersistence.put(job.path("batch_id").asText(), job.deepCopy()); }
    }

    private static ObjectNode unknownNarrativeDiagnostic(String code) {
        ObjectNode diagnostic = Json.MAPPER.createObjectNode().put("stage", "UNKNOWN").put("code", code)
                .putNull("model_rounds").putNull("tool_calls");
        diagnostic.putArray("steps");
        return diagnostic;
    }

    private void boundNarrativeDiagnostic(ObjectNode job) throws IOException {
        if (!(job.get("narrative_diagnostic") instanceof ObjectNode diagnostic)) return;
        // 诊断超限只裁剪工具轨迹，不能压缩规则或证据，也不能误标采集不完整。
        ArrayNode steps = diagnostic.withArray("steps");
        while (Json.MAPPER.writeValueAsBytes(job).length > config.reportBytes() && !steps.isEmpty()) {
            steps.remove(steps.size() - 1);
            diagnostic.put("truncated", true);
        }
        if (Json.MAPPER.writeValueAsBytes(job).length > config.reportBytes()) job.remove("narrative_diagnostic");
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
            placeholder.put("collection_status", "SKIPPED").put("collection_event_count", 0);
            placeholder.putArray("collection_limitations").add("STOPPED_BEFORE_TASK_ANALYSIS");
            ((ArrayNode) job.get("tasks")).add(placeholder);
            mergeStrings((ArrayNode) job.get("quality_flags"), result.path("quality_flags"));
            mergeStrings((ArrayNode) job.get("limitations"), result.path("limitations"));
        }
        job.put("reported_count", job.path("tasks").size()).put("collection_incomplete", true).put("attribution_incomplete", true);
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
    /** 分任务分析前先检查整份固定名单，避免单任务分析看不到跨任务的 ID 冲突。 */
    private static Set<String> taskAliasConflicts(List<Task> tasks) {
        Map<String, String> owners = new LinkedHashMap<>();
        Set<String> conflicts = new LinkedHashSet<>();
        for (Task task : tasks) {
            LinkedHashSet<String> ids = new LinkedHashSet<>(); ids.add(task.taskId());
            if (task.associationScope() == LogModels.AssociationScope.TASK_ONLY && task.schedulerFacts() != null
                    && LogRequest.validId(task.schedulerFacts().recordId())) ids.add(task.schedulerFacts().recordId());
            task.attempts().forEach(attempt -> ids.add(attempt.promptId()));
            for (String id : ids) {
                if (id == null || id.isBlank()) continue;
                String previous = owners.putIfAbsent(id, task.taskRef());
                if (previous != null && !previous.equals(task.taskRef())) { conflicts.add(previous); conflicts.add(task.taskRef()); }
            }
        }
        return conflicts;
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
        if (task.schedulerFacts() != null) node.set("scheduler_facts", TaskSnapshot.schedulerFactsJson(task.schedulerFacts()));
        return node;
    }
    private static Task taskFromJson(JsonNode node) {
        List<Attempt> attempts = new ArrayList<>();
        for (JsonNode a : node.path("attempts")) attempts.add(new Attempt(a.path("attempt_id").asText(), a.path("prompt_id").asText(),
                Instant.parse(a.path("started_at").asText()), Instant.parse(a.path("ended_at").asText()), a.path("instance_id").asText()));
        return new Task(node.path("task_ref").asText(), node.path("task_id").asText(), node.path("status").asText(), attempts,
                node.path("final_attempt_id").asText(), node.path("failure_reason").asText(),
                node.hasNonNull("failed_at") ? Instant.parse(node.path("failed_at").asText()) : null, node.path("status_version").asText(),
                LogModels.AssociationScope.valueOf(node.path("association_scope").asText("VERIFIED_ATTEMPTS")),
                TaskSnapshot.schedulerFactsFromJson(node.path("scheduler_facts")));
    }
    private static ObjectNode publicView(ObjectNode job) {
        ObjectNode result = job.deepCopy();
        result.remove(List.of("_selected_tasks", "_archive_inflight_task", "_selection_pending", "_narrative_base_stage"));
        // 工程师视图按已保存证据即时派生，旧报告也能使用；不修改规则统计或重发采集、模型请求。
        result.set("engineer_view", LogErrorGroups.build(result));
        return result;
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
        cleaner.shutdownNow(); workers.shutdownNow(); modelWorkers.shutdownNow();
        try {
            if (workers.awaitTermination(5, TimeUnit.SECONDS)) { instanceLock.release(); lockChannel.close(); }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (IOException ignored) { }
    }
    public static final class Busy extends IllegalStateException { public Busy(String message) { super(message); } }
    public static final class NarrativeUnavailable extends IOException { public NarrativeUnavailable() { super("日志模型解释未配置"); } }
    public static final class NarrativeConflict extends IllegalStateException { public NarrativeConflict() { super("请等待日志批次处理完成后再生成模型解释"); } }
}
