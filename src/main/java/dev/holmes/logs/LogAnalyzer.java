package dev.holmes.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.holmes.logs.LogModels.Attempt;
import dev.holmes.logs.LogModels.AssociationScope;
import dev.holmes.logs.LogModels.LogEvent;
import dev.holmes.logs.LogModels.Task;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 按权威任务快照关联证据并计数；模型输出不参与任务归属或失败簇计算。 */
public final class LogAnalyzer {
    public static final String RULE_VERSION = "holmes-log-v1.1";
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Duration CLOCK_MARGIN = Duration.ofMinutes(2);
    private static final int EXCERPT_LIMIT = 1_800;
    private static final int EVIDENCE_LIMIT = 8;
    private static final int TIMELINE_LIMIT = 20;
    private static final List<String> TASK_ONLY_FLAGS = List.of("TASK_ATTEMPT_UNVERIFIED",
            "PROMPT_ATTEMPT_MAPPING_UNVERIFIED", "EXECUTION_WINDOW_UNVERIFIED",
            "RETRY_MEMBERSHIP_UNVERIFIED", "TASK_FAILURE_FINALITY_UNVERIFIED");
    private static final Pattern PAYLOAD = Pattern.compile("(?i)\\bpayload\\s*[:=]\\s*");
    private static final Pattern EXCEPTION = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_.$]*(?:Error|Exception))\\b");
    private static final Pattern HTTP = Pattern.compile("(?i)(?:\\bHTTP(?:/\\d(?:\\.\\d)?)?\\s*(?:status(?:_code)?\\s*[:=]?\\s*)?|\\bstatus(?:_code)?\\s*[:=]\\s*|\\bresponse(?:\\s+code)?\\s*[:=]?\\s*)([45]\\d\\d)\\b");
    private static final Pattern STACK_LINE = Pattern.compile("^(?:\\s+(?:at\\s|File\\s|raise\\s|\\^)|Traceback\\s|Caused by:|[A-Za-z_][A-Za-z0-9_.$]*(?:Error|Exception):).*");
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)(https?://)[^\\s/@\\\"'<>]+@");
    private static final Pattern BEARER = Pattern.compile("(?i)(\\b(?:Bearer|Basic)\\s+)[A-Za-z0-9._~+/-]+=*");
    private static final String SECRET_NAMES = "(?:authorization|proxy[-_]?authorization|(?:access|refresh|auth)[_-]?token|x-auth-token|client[_-]?secret|api[_-]?key|x-api-key|token|password|passwd|secret|credential|private[_-]?key|cookie|set-cookie|signature|sig|x-amz-signature|x-amz-credential|x-amz-security-token|x-goog-signature|x-goog-credential)";
    private static final Pattern SECRET = Pattern.compile("(?i)([\\\"']?" + SECRET_NAMES + "[\\\"']?\\s*[:=]\\s*)(?:\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|'(?:\\\\.|[^'\\\\])*'|[^\\s,;}&\\\"'#]+)");
    // Cookie 头的整行都是凭据；限定为独立头，避免吞掉 URL 后面的 task_id 参数。
    private static final Pattern COOKIE_HEADER = Pattern.compile("(?im)((?<!\\S)(?:set-cookie|cookie)\\s*[:=][\\t ]*+)(?![\\\"'])[^\\r\\n]+");
    private static final Pattern PRIVATE_KEY = Pattern.compile("(?s)-----BEGIN ([A-Z ]*PRIVATE KEY)-----.*?(?:-----END \\1-----|\\z)");
    private static final Pattern QUERY_SECRET = Pattern.compile("(?i)([?&](?:access[_-]?token|refresh[_-]?token|auth[_-]?token|token|api[_-]?key|key|password|client[_-]?secret|secret|credential|private[_-]?key|signature|sig|x-amz-signature|x-amz-credential|x-amz-security-token|x-goog-signature|x-goog-credential|AWSAccessKeyId|GoogleAccessId)=)[^&#\\s\\\"']+");

    public ObjectNode analyze(List<Task> tasks, List<LogEvent> events, String namespace,
                              boolean complete, List<String> limitations) {
        Map<String, Boolean> completeness = new LinkedHashMap<>();
        for (Task task : tasks) completeness.put(task.taskRef(), complete);
        return analyze(tasks, events, namespace, completeness, limitations);
    }

    public ObjectNode analyze(List<Task> tasks, List<LogEvent> events, String namespace,
                              Map<String, Boolean> taskCompleteness, List<String> limitations) {
        TreeSet<String> quality = new TreeSet<>();
        LinkedHashMap<String, TaskState> states = new LinkedHashMap<>();
        Map<String, TaskState> taskIds = new LinkedHashMap<>();
        // 批次作用域已经固定；重复输入同一任务不能抬高分母。
        for (Task task : tasks.stream().sorted(Comparator.comparing(Task::taskRef)).toList()) {
            TaskState existing = taskIds.get(task.taskId());
            if (existing != null) {
                existing.flags.add("DUPLICATE_TASK_INPUT");
                if (!sameTask(existing.task, task)) existing.flags.add("TASK_ID_COLLISION");
                continue;
            }
            TaskState state = new TaskState(task, Boolean.TRUE.equals(taskCompleteness.get(task.taskRef())));
            if (states.putIfAbsent(task.taskRef(), state) != null) {
                quality.add("TASK_REF_COLLISION");
                states.get(task.taskRef()).flags.add("TASK_REF_COLLISION");
                continue;
            }
            taskIds.put(task.taskId(), state);
            if (!state.complete) state.flags.add("INCOMPLETE_INPUT");
            if (task.finalAttempt() == null) state.flags.add("MISSING_FINAL_ATTEMPT");
            if (task.associationScope() == AssociationScope.TASK_ONLY) state.flags.addAll(TASK_ONLY_FLAGS);
        }

        List<ParsedEvent> parsed = normalize(events, quality);
        for (ParsedEvent event : parsed) associate(event, states.values(), namespace, quality);
        appendMultiline(parsed);
        for (ParsedEvent event : parsed) {
            if (event.owner == null || event.multilineChild) continue;
            TaskState state = event.owner;
            state.events.add(event);
            state.flags.addAll(event.flags);
        }

        ObjectNode report = JSON.objectNode();
        report.put("selected_count", states.size());
        report.put("rule_version", RULE_VERSION);
        report.put("signature_is_root_cause", false);
        ArrayNode taskNodes = report.putArray("tasks");
        ArrayNode evidenceNodes = report.putArray("evidence");
        Map<String, List<String>> clusters = new TreeMap<>();
        for (TaskState state : states.values()) {
            String signature = signature(state);
            clusters.computeIfAbsent(signature, ignored -> new ArrayList<>()).add(state.task.taskRef());
            taskNodes.add(taskNode(state, signature, evidenceNodes));
            quality.addAll(state.flags);
        }
        ArrayNode clusterNodes = report.putArray("clusters");
        for (Map.Entry<String, List<String>> cluster : clusters.entrySet()) {
            ObjectNode node = clusterNodes.addObject();
            node.put("signature", cluster.getKey());
            node.put("task_count", cluster.getValue().size());
            node.put("ratio", states.isEmpty() ? 0d : (double) cluster.getValue().size() / states.size());
            strings(node.putArray("task_refs"), cluster.getValue());
        }
        strings(report.putArray("quality_flags"), quality);
        ArrayNode limitationNodes = report.putArray("limitations");
        if (limitations != null) limitations.stream().distinct().limit(64)
                .forEach(value -> limitationNodes.add(excerpt(redact(value == null ? "" : value), EXCERPT_LIMIT)));
        report.put("observed_error_event_count", states.values().stream()
                .flatMap(state -> state.events.stream()).filter(event -> !event.historical && classify(event) != null).count());
        return report;
    }

    private static boolean sameTask(Task left, Task right) {
        return java.util.Objects.equals(left.finalAttemptId(), right.finalAttemptId())
                && left.attempts().equals(right.attempts()) && java.util.Objects.equals(left.statusVersion(), right.statusVersion())
                && left.associationScope() == right.associationScope();
    }

    private static List<ParsedEvent> normalize(List<LogEvent> events, Set<String> quality) {
        List<ParsedEvent> result = new ArrayList<>();
        Map<String, ParsedEvent> stableIds = new LinkedHashMap<>();
        Map<String, Integer> duplicates = new LinkedHashMap<>();
        for (LogEvent event : events.stream().sorted(Comparator.comparing(LogEvent::time)
                .thenComparing(value -> stream(value.labels())).thenComparing(LogEvent::message)
                .thenComparing(LogEvent::eventId)).toList()) {
            ParsedEvent value = new ParsedEvent(event);
            if (!event.eventId().isBlank()) {
                ParsedEvent previous = stableIds.putIfAbsent(value.stream + "\u0000" + event.eventId(), value);
                if (previous != null) {
                    if (previous.event.time().equals(event.time()) && previous.event.message().equals(event.message())) continue;
                    previous.flags.add("SOURCE_EVENT_ID_CONFLICT");
                    value.flags.add("SOURCE_EVENT_ID_CONFLICT");
                    quality.add("SOURCE_EVENT_ID_CONFLICT");
                }
            }
            String sameInstant = value.stream + "\u0000" + event.time() + "\u0000" + event.message();
            value.ordinal = duplicates.merge(sameInstant, 1, Integer::sum);
            if (event.eventId().isBlank() && value.ordinal > 1) value.flags.add("DUPLICATE_AMBIGUITY");
            parsePayload(value);
            result.add(value);
            quality.addAll(value.flags);
        }
        return result;
    }

    private static void parsePayload(ParsedEvent event) {
        String text = event.event.message();
        if (text.indexOf('\r') >= 0) {
            text = text.replace('\r', '\n');
            event.flags.add("CARRIAGE_RETURN_NORMALIZED");
        }
        event.text = text;
        collectLabel(event, "task_id", event.taskIds);
        collectLabel(event, "prompt_id", event.promptIds);
        collectLabel(event, "attempt_id", event.attemptIds);
        collectLabel(event, "instance_id", event.instances);
        parseObject(text, event, true);
        ObjectNode labelDetails = JSON.objectNode();
        for (String key : List.of("event", "event_type", "level", "severity", "component", "service",
                "exception_type", "exception_class", "error_type", "exception_message", "error_message",
                "error", "reason", "failure_reason", "http_status", "http_status_code", "status_code", "error_code")) {
            if (event.event.labels().containsKey(key)) labelDetails.put(key, event.event.labels().get(key));
        }
        if (!labelDetails.isEmpty()) {
            event.errorScopes.add(labelDetails);
            event.structuredFields = labelDetails.toString();
        }
        for (JsonNode scope : List.copyOf(event.scopes)) {
            collectId(scope, "task_id", event.taskIds, event);
            collectId(scope, "prompt_id", event.promptIds, event);
            collectId(scope, "attempt_id", event.attemptIds, event);
            collectId(scope, "instance_id", event.instances, event);
        }
        event.eventName = field(event.errorScopes, "event", "event_type");
        event.level = firstNonempty(field(event.errorScopes, "level", "severity"), label(event.event.labels(), "level", "severity"));
        event.component = firstNonempty(label(event.event.labels(), "component", "app", "application", "app_kubernetes_io_name", "service_name"),
                field(event.errorScopes, "component", "service"), "unknown");
    }

    private static void collectLabel(ParsedEvent event, String name, Set<String> target) {
        String value = event.event.labels().get(name);
        if (value != null && !value.isBlank()) target.add(value);
    }

    private static void collectId(JsonNode node, String key, Set<String> target, ParsedEvent event) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) return;
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 512) {
            event.flags.add("INVALID_ID_FIELD");
            return;
        }
        target.add(value.textValue());
    }

    private static void parseObject(String text, ParsedEvent event, boolean innerMessage) {
        String candidate = text.strip();
        boolean whole = candidate.startsWith("{");
        if (!whole) {
            Matcher marker = PAYLOAD.matcher(candidate);
            if (!marker.find()) return;
            int start = marker.end();
            if (start >= candidate.length() || candidate.charAt(start) != '{') { event.flags.add("PARSE_ERROR"); return; }
            candidate = objectSlice(candidate, start);
            if (candidate == null) { event.flags.add("PARSE_ERROR"); return; }
        }
        try {
            JsonNode object = LiteralPayloadParser.parse(candidate);
            if (!object.isObject()) { event.flags.add("PARSE_ERROR"); return; }
            addScopes(object, event);
            if (innerMessage && object.path("message").isTextual()) parseObject(object.path("message").textValue(), event, false);
            if (object.path("payload").isTextual()) parseObject(object.path("payload").textValue(), event, false);
        } catch (IllegalArgumentException exception) { event.flags.add("PARSE_ERROR"); }
    }

    private static void addScopes(JsonNode object, ParsedEvent event) {
        addScope(object, event);
        if (object.path("payload").isObject()) addScope(object.path("payload"), event);
    }

    private static void addScope(JsonNode object, ParsedEvent event) {
        event.scopes.add(object);
        event.errorScopes.add(object);
        if (object.path("data").isObject()) {
            JsonNode data = object.path("data");
            event.scopes.add(data);
            event.errorScopes.add(data);
            if (data.path("original_data").isObject()) event.scopes.add(data.path("original_data"));
        }
    }

    private static String objectSlice(String text, int start) {
        char quote = 0;
        boolean escaped = false;
        int depth = 0;
        int limit = Math.min(text.length(), start + LiteralPayloadParser.MAX_LENGTH + 1);
        for (int i = start; i < limit; i++) {
            char value = text.charAt(i);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (value == '\\') escaped = true;
                else if (value == quote) quote = 0;
            } else if (value == '\'' || value == '"') quote = value;
            else if (value == '{' || value == '[') depth++;
            else if (value == '}' || value == ']') {
                if (--depth == 0) return text.substring(start, i + 1);
                if (depth < 0) return null;
            }
        }
        return null;
    }

    private static void associate(ParsedEvent event, Collection<TaskState> states, String namespace, Set<String> quality) {
        if (event.taskIds.isEmpty() && event.promptIds.isEmpty()) return;
        Set<TaskState> mentioned = new LinkedHashSet<>();
        Set<TaskState> candidates = null;
        boolean unknownId = false;
        TaskState explicitTaskOnly = null;
        if (event.taskIds.size() == 1) {
            String explicitId = event.taskIds.iterator().next();
            for (TaskState state : states) if (state.task.associationScope() == AssociationScope.TASK_ONLY
                    && explicitId.equals(state.task.taskId())) explicitTaskOnly = state;
        }
        for (String taskId : event.taskIds) {
            Set<TaskState> matches = new LinkedHashSet<>();
            for (TaskState state : states) if (taskId.equals(state.task.taskId())) matches.add(state);
            mentioned.addAll(matches);
            if (matches.isEmpty()) unknownId = true;
            candidates = intersect(candidates, matches);
        }
        for (String promptId : event.promptIds) {
            Set<TaskState> matches = new LinkedHashSet<>();
            for (TaskState state : states) if (promptId.equals(state.task.taskId())
                    || state.task.associationScope() == AssociationScope.VERIFIED_ATTEMPTS
                    && state.task.attempts().stream().anyMatch(attempt -> promptId.equals(attempt.promptId()))) matches.add(state);
            mentioned.addAll(matches);
            if (matches.isEmpty() && explicitTaskOnly != null) {
                // 精确逻辑 task_id 已成立；未知 prompt 只能作为观察元数据，不能建立重试映射。
                event.flags.add("UNVERIFIED_PROMPT_ID");
                continue;
            }
            if (matches.isEmpty()) unknownId = true;
            if (explicitTaskOnly != null) for (TaskState match : matches) if (match != explicitTaskOnly) unknownId = true;
            candidates = intersect(candidates, matches);
        }
        if (mentioned.isEmpty()) return;
        Set<String> namespaces = new TreeSet<>();
        for (String key : List.of("namespace", "namespace_name", "kubernetes.pod_namespace", "kubernetes.namespace_name", "k8s.namespace.name")) {
            String value = event.event.labels().get(key);
            if (value != null && !value.isBlank()) namespaces.add(value);
        }
        for (JsonNode scope : event.errorScopes) for (String key : List.of("namespace", "namespace_name")) {
            if (scope.path(key).isTextual() && !scope.path(key).asText().isBlank()) namespaces.add(scope.path(key).asText());
        }
        if (!namespaces.isEmpty() && (namespaces.size() != 1 || !namespaces.contains(namespace))) {
            mark(mentioned, "CROSS_NAMESPACE_REJECTED");
            quality.add("CROSS_NAMESPACE_REJECTED");
            return;
        }
        if (unknownId || candidates == null || candidates.size() != 1 || event.flags.contains("INVALID_ID_FIELD")
                || event.taskIds.size() > 1 || event.promptIds.size() > 1) {
            mark(mentioned, "ID_CONFLICT");
            quality.add("ID_CONFLICT");
            return;
        }
        TaskState owner = candidates.iterator().next();
        if (owner.flags.contains("TASK_ID_COLLISION") || owner.flags.contains("TASK_REF_COLLISION")) return;
        if (event.flags.contains("PARSE_ERROR")) {
            owner.flags.add("PARSE_ERROR");
            // 错误 payload 中可能藏有第二个冲突 ID，不能仅凭标签绕过解析失败。
            return;
        }
        if (event.attemptIds.size() > 1) { owner.flags.add("AMBIGUOUS_ATTEMPT"); return; }
        if (owner.task.associationScope() == AssociationScope.TASK_ONLY) {
            if (event.instances.size() > 1) { owner.flags.add("AMBIGUOUS_INSTANCE"); return; }
            // 只保留精确逻辑任务 ID 的候选证据。已观察到的 attempt/instance 不能冒充已验证映射。
            event.owner = owner;
            event.attempt = null;
            event.basis = "TASK_ID_ONLY_ATTEMPT_UNVERIFIED";
            event.flags.addAll(TASK_ONLY_FLAGS);
            markStreamQuality(event, namespaces);
            return;
        }
        List<Attempt> attempts = owner.task.attempts().stream()
                .filter(attempt -> event.attemptIds.isEmpty() || event.attemptIds.size() == 1 && event.attemptIds.contains(attempt.id()))
                .filter(attempt -> event.promptIds.isEmpty() || event.promptIds.stream().allMatch(prompt ->
                        prompt.equals(attempt.promptId()) || prompt.equals(owner.task.taskId()) && (attempt.promptId() == null || attempt.promptId().isBlank() || prompt.equals(attempt.promptId()))))
                .toList();
        if (event.instances.size() > 1) { owner.flags.add("AMBIGUOUS_INSTANCE"); return; }
        if (!event.instances.isEmpty()) {
            List<Attempt> matching = attempts.stream().filter(attempt -> event.instances.contains(attempt.instanceId())).toList();
            if (matching.isEmpty()) { owner.flags.add("INSTANCE_MISMATCH"); return; }
            attempts = matching;
        }
        List<Attempt> inWindow = attempts.stream().filter(attempt -> inWindow(event.event.time(), attempt)).toList();
        if (inWindow.isEmpty()) { owner.flags.add("OUTSIDE_ATTEMPT_WINDOW"); return; }
        if (inWindow.size() != 1) { owner.flags.add("AMBIGUOUS_ATTEMPT"); return; }
        Attempt attempt = inWindow.getFirst();
        event.owner = owner;
        event.attempt = attempt;
        event.historical = !attempt.id().equals(owner.task.finalAttemptId());
        event.basis = !event.attemptIds.isEmpty() ? "EXACT_ID_AND_ATTEMPT" : !event.promptIds.isEmpty()
                ? "EXACT_PROMPT_ID_AND_WINDOW" : !event.instances.isEmpty() ? "EXACT_TASK_ID_INSTANCE_AND_WINDOW" : "EXACT_TASK_ID_AND_WINDOW";
        if (event.taskIds.size() == 1 && event.promptIds.size() == 1
                && !event.taskIds.iterator().next().equals(event.promptIds.iterator().next())) event.basis = "VERIFIED_TASK_ATTEMPT_PROMPT_MAPPING";
        if (attempt.startedAt() == null || attempt.endedAt() == null) event.flags.add("INCOMPLETE_ATTEMPT_WINDOW");
        if (attempt.startedAt() != null && event.event.time().isBefore(attempt.startedAt())
                || attempt.endedAt() != null && event.event.time().isAfter(attempt.endedAt())) event.flags.add("CLOCK_SKEW_MARGIN");
        markStreamQuality(event, namespaces);
    }

    private static void markStreamQuality(ParsedEvent event, Set<String> namespaces) {
        if (namespaces.isEmpty()) event.flags.add("QUERY_SCOPED_NAMESPACE");
        if (label(event.event.labels(), "pod_uid", "kubernetes.pod_uid", "k8s.pod.uid").isBlank()) event.flags.add("MISSING_POD_UID");
        if (label(event.event.labels(), "restart", "restart_count", "container_restart_count", "kubernetes.container_restart_count").isBlank()) event.flags.add("MISSING_RESTART");
        if (event.container.isBlank()) event.flags.add("MISSING_CONTAINER");
    }

    private static Set<TaskState> intersect(Set<TaskState> current, Set<TaskState> next) {
        if (current == null) return new LinkedHashSet<>(next);
        current.retainAll(next);
        return current;
    }

    private static void mark(Collection<TaskState> states, String flag) { for (TaskState state : states) state.flags.add(flag); }

    private static boolean inWindow(Instant time, Attempt attempt) {
        return (attempt.startedAt() == null || !time.isBefore(attempt.startedAt().minus(CLOCK_MARGIN)))
                && (attempt.endedAt() == null || !time.isAfter(attempt.endedAt().plus(CLOCK_MARGIN)));
    }

    private static void appendMultiline(List<ParsedEvent> events) {
        Map<String, ParsedEvent> previous = new LinkedHashMap<>();
        for (ParsedEvent event : events) {
            if (event.owner != null) { previous.put(event.stream, event); continue; }
            if (!event.taskIds.isEmpty() || !event.promptIds.isEmpty()) { previous.remove(event.stream); continue; }
            ParsedEvent head = previous.get(event.stream);
            if (head == null || head.flags.contains("MISSING_RESTART") || head.flags.contains("MISSING_POD_UID")
                    || head.flags.contains("MISSING_CONTAINER")) continue;
            if (Duration.between(head.event.time(), event.event.time()).compareTo(Duration.ofSeconds(2)) > 0
                    || head.text.length() + event.text.length() > 12_000 || head.multilineCount >= 32) {
                previous.remove(event.stream);
                continue;
            }
            // 普通无 ID 的 ERROR 只属于候选上下文，不凭同 Pod 自动归给任务。
            if (STACK_LINE.matcher(event.text).matches() && (head.multilineCount > 0
                    || head.eventName.matches("(?i).*(?:execution_error|task_failed|exception|error).*?")
                    || EXCEPTION.matcher(head.text).find() || head.text.contains("Traceback"))) {
                head.text += "\n" + event.text;
                head.continuationText += "\n" + event.text;
                head.multilineCount++;
                head.flags.add("MULTILINE_SAME_STREAM");
                event.multilineChild = true;
            } else previous.remove(event.stream);
        }
    }

    private static String signature(TaskState state) {
        if (state.task.associationScope() == AssociationScope.TASK_ONLY) return "INCOMPLETE";
        if (state.flags.contains("TASK_ID_COLLISION") || state.flags.contains("TASK_REF_COLLISION")) return "AMBIGUOUS";
        if (state.task.finalAttempt() == null) return "INCOMPLETE";
        Set<String> signatures = new TreeSet<>();
        for (ParsedEvent event : state.events) if (!event.historical) {
            String value = classify(event);
            if (value != null) signatures.add(value);
        }
        String reason = classifyText(state.task.failureReason(), "scheduler", List.of(), state.task, true);
        if (!state.complete || state.flags.contains("INCOMPLETE_ATTEMPT_WINDOW")) {
            // 部分日志不能支持确定的共同原因；权威终态原因仍可报告为失败签名。
            if (reason != null) { state.flags.add("SIGNATURE_FROM_TASK_FAILURE_REASON"); return reason; }
            return "INCOMPLETE";
        }
        if (signatures.size() > 1) { state.flags.add("MULTIPLE_FAILURE_SIGNATURES"); return "AMBIGUOUS"; }
        if (signatures.size() == 1) {
            String value = signatures.iterator().next();
            if (reason != null && !compatibleReason(reason, value)) { state.flags.add("CONFLICTING_FAILURE_REASON"); return "AMBIGUOUS"; }
            return value;
        }
        if (reason != null) { state.flags.add("SIGNATURE_FROM_TASK_FAILURE_REASON"); return reason; }
        if (state.flags.contains("AMBIGUOUS_ATTEMPT") || state.flags.contains("ID_CONFLICT")
                || state.flags.contains("AMBIGUOUS_INSTANCE")) return "AMBIGUOUS";
        if (state.flags.contains("PARSE_ERROR")) return "INCOMPLETE";
        return "UNKNOWN";
    }

    private static boolean compatibleReason(String reason, String observed) {
        if (reason.equals(observed)) return true;
        // 终态原因常缺少组件和栈帧，不用较短摘要覆盖更具体的同类型异常指纹。
        return reason.startsWith("EXCEPTION:") && observed.startsWith("EXCEPTION:")
                && reason.substring(0, reason.lastIndexOf(':')).equals(observed.substring(0, observed.lastIndexOf(':')));
    }

    private static String classify(ParsedEvent event) {
        if (!event.classified) {
            event.failureSignature = computeSignature(event);
            event.classified = true;
        }
        return event.failureSignature;
    }

    private static String computeSignature(ParsedEvent event) {
        List<JsonNode> details = new ArrayList<>(event.errorScopes);
        for (JsonNode scope : event.errorScopes) {
            if (scope.path("error").isObject()) details.add(scope.path("error"));
            if (scope.path("exception").isObject()) details.add(scope.path("exception"));
        }
        String exception = field(details, "exception_type", "exception_class", "error_type");
        if (exception.isBlank()) for (JsonNode scope : event.errorScopes) {
            for (String nested : List.of("error", "exception")) if (scope.path(nested).isObject()) {
                exception = field(List.of(scope.path(nested)), "type", "class", "name");
                if (!exception.isBlank()) break;
            }
            if (!exception.isBlank()) break;
        }
        String code = field(details, "http_status", "http_status_code", "status_code", "error_code");
        String errorMessage = field(details, "exception_message", "error_message", "error", "reason");
        // original_data 只提供关联 ID；工作流、用户 prompt 和 metrics 不能伪造异常。
        StringBuilder semantic = new StringBuilder();
        if (event.scopes.isEmpty()) semantic.append(event.text);
        else for (JsonNode scope : details) for (String name : List.of("message", "exception_message", "error_message", "error", "reason", "failure_reason", "stack_trace", "traceback", "exception")) {
            JsonNode value = scope.get(name);
            if (value != null && value.isTextual()) {
                String text = value.textValue();
                if (name.equals("message") && (text.strip().startsWith("{") || PAYLOAD.matcher(text).find())) continue;
                semantic.append('\n').append(text);
            }
        }
        String fatalText = semantic + "\n" + exception + "\n" + errorMessage + event.continuationText;
        boolean fatal = !exception.isBlank() || !errorMessage.isBlank() || !code.isBlank()
                || event.level.matches("(?i)error|fatal|critical")
                || event.eventName.matches("(?i).*(?:execution_error|task_failed|task_failure|execution_failed|exception|oom|timeout).*")
                || EXCEPTION.matcher(fatalText).find()
                || fatalText.matches("(?is).*(?:CUDA out of memory|OOMKilled|\\bout of memory\\b|timed out).*?");
        if (!fatal) return null;
        String known = classifyText(fatalText, event.component, details, event.owner.task, false);
        if (known != null && !known.startsWith("EXCEPTION:")) return known;
        if (!exception.isBlank()) return fingerprint(exception, code, firstNonempty(errorMessage, semantic.toString()), event.component, event.owner.task);
        return known;
    }

    private static String classifyText(String value, String component, List<JsonNode> details, Task task, boolean authoritativeReason) {
        String code = field(details, "http_status", "http_status_code", "status_code");
        if (value == null || value.isBlank()) return code.matches("[45]\\d\\d") ? "HTTP_" + code : null;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("cuda out of memory") || lower.contains("cuda_oom") || lower.contains("torch.outofmemoryerror")
                || lower.contains("gpu out of memory")) return "CUDA_OOM";
        if (lower.contains("oomkilled") || lower.matches("(?s).*\\bout of memory\\b.*") || lower.matches("(?s).*\\boom\\b.*")) return "OOM";
        if (lower.contains("timed out") || lower.contains("timeouterror") || lower.contains("readtimeout")
                || lower.contains("connecttimeout") || lower.matches("(?s).*\\b(?:task|request|execution|connection|operation) timeout\\b.*")
                || authoritativeReason && lower.matches("(?s).*\\btimeout\\b.*")) return "TIMEOUT";
        if (code.matches("[45]\\d\\d")) return "HTTP_" + code;
        Matcher http = HTTP.matcher(value);
        if (http.find()) return "HTTP_" + http.group(1);
        if (authoritativeReason && value.matches("(?i)HTTP_[45]\\d\\d")) return value.toUpperCase(Locale.ROOT);
        Matcher exception = EXCEPTION.matcher(value);
        if (exception.find()) {
            String message = value.substring(exception.end()).strip().replaceFirst("^:\\s*", "");
            return fingerprint(exception.group(1), field(details, "error_code"), message, component, task);
        }
        return null;
    }

    private static String fingerprint(String exception, String code, String message, String component, Task task) {
        String normalized = redact(message);
        for (String id : allIds(task)) if (!id.isBlank()) normalized = normalized.replaceAll(
                "(?<![A-Za-z0-9_-])" + Pattern.quote(id) + "(?![A-Za-z0-9_-])", "<id>");
        normalized = normalized.replaceAll("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b", "<uuid>")
                .replaceAll("\\b\\d{4}-\\d{2}-\\d{2}[T ][0-9:.+Z-]+", "<time>")
                .replaceAll("(?i)(?:/tmp|/var/tmp)/[^\\s\\\"']+", "<temp>")
                .replaceAll("\\b\\d+(?:\\.\\d+)?\\b", "<n>").replaceAll("\\s+", " ").strip();
        normalized = excerpt(normalized, 800);
        String safeException = exception.replaceAll("[^A-Za-z0-9_.$]", "");
        return "EXCEPTION:" + safeException + ":" + sha256(component + "|" + exception + "|" + code + "|" + normalized).substring(0, 16);
    }

    private static List<String> allIds(Task task) {
        List<String> values = new ArrayList<>();
        values.add(task.taskId());
        for (Attempt attempt : task.attempts()) { values.add(attempt.id()); if (attempt.promptId() != null) values.add(attempt.promptId()); }
        // 先替换长 ID，避免短 ID 吃掉另一个完整 ID 的一部分。
        values.sort(Comparator.comparingInt(String::length).reversed());
        return values;
    }

    private static ObjectNode taskNode(TaskState state, String signature, ArrayNode evidenceNodes) {
        ObjectNode node = JSON.objectNode();
        node.put("task_ref", state.task.taskRef());
        node.put("task_id", state.task.taskId());
        node.put("association_scope", state.task.associationScope().name());
        node.put("final_attempt_id", state.task.finalAttemptId());
        node.put("signature", signature);
        if (state.task.associationScope() == AssociationScope.TASK_ONLY) {
            Set<String> observed = new TreeSet<>();
            for (ParsedEvent event : state.events) {
                String value = classify(event);
                if (value != null) observed.add(value);
            }
            strings(node.putArray("observed_signatures"), observed);
        }
        ArrayNode evidenceIds = node.putArray("evidence_ids");
        List<ParsedEvent> evidence = state.events.stream().sorted(Comparator
                .comparingInt((ParsedEvent event) -> event.historical ? 3 : classify(event) != null ? 0 : isTerminal(event) ? 1 : 2)
                .thenComparing(event -> event.event.time()).thenComparing(event -> event.stream).thenComparingInt(event -> event.ordinal))
                .limit(EVIDENCE_LIMIT).toList();
        for (ParsedEvent event : evidence) {
            String redacted = redact(evidenceText(event));
            String saved = excerpt(redacted, EXCERPT_LIMIT);
            if (saved.length() < redacted.length()) state.flags.add("EVIDENCE_EXCERPT_TRUNCATED");
            String digest = sha256(saved);
            String id = "ev_" + sha256(state.task.taskRef() + "|" + event.stream + "|" + event.event.time() + "|"
                    + digest + "|" + event.ordinal + "|" + (event.attempt == null ? "TASK_ONLY" : event.attempt.id())).substring(0, 24);
            event.evidenceId = id;
            evidenceIds.add(id);
            ObjectNode detail = evidenceNodes.addObject();
            detail.put("evidence_id", id);
            detail.put("task_ref", state.task.taskRef());
            putAssociation(detail, event);
            detail.put("component", safeMeta(event.component));
            detail.put("pod", safeMeta(event.pod));
            detail.put("container", safeMeta(event.container));
            detail.put("event_time", event.event.time().toString());
            detail.put("excerpt", saved);
            detail.put("content_sha256", digest);
            detail.put("association_basis", event.basis);
            strings(detail.putArray("quality_flags"), event.flags);
        }
        if (state.events.size() > EVIDENCE_LIMIT) state.flags.add("REPRESENTATIVE_EVIDENCE_LIMIT");
        ArrayNode timeline = node.putArray("timeline");
        List<ParsedEvent> ordered = state.events.stream().sorted(Comparator.comparing((ParsedEvent event) -> event.event.time())
                .thenComparing(event -> event.stream).thenComparingInt(event -> event.ordinal)).toList();
        // 同时保留任务的开始与结尾，避免长时间线裁剪掉终态。
        List<ParsedEvent> retained = ordered.size() <= TIMELINE_LIMIT ? ordered :
                java.util.stream.Stream.concat(ordered.stream().limit(5), ordered.stream().skip(ordered.size() - 15)).toList();
        if (ordered.size() > TIMELINE_LIMIT) state.flags.add("TIMELINE_TRUNCATED");
        for (ParsedEvent event : retained) {
            ObjectNode item = timeline.addObject();
            item.put("event_time", event.event.time().toString());
            putAssociation(item, event);
            item.put("component", safeMeta(event.component));
            item.put("pod", safeMeta(event.pod));
            item.put("container", safeMeta(event.container));
            item.put("event", safeMeta(event.eventName));
            item.put("association_basis", event.basis);
            item.put("context_only", false);
            item.put("excerpt", excerpt(redact(evidenceText(event)), 600));
            if (event.evidenceId != null) item.put("evidence_id", event.evidenceId);
        }
        strings(node.putArray("quality_flags"), state.flags);
        return node;
    }

    private static boolean isTerminal(ParsedEvent event) { return event.eventName.matches("(?i).*(?:failed|error|finished|completed|cancelled).*"); }

    private static void putAssociation(ObjectNode node, ParsedEvent event) {
        node.put("association_scope", event.owner.task.associationScope().name());
        if (event.attempt == null) {
            node.putNull("attempt_id");
            node.putNull("historical_attempt");
            node.put("candidate_only", true);
            if (!event.promptIds.isEmpty()) node.put("observed_prompt_id", safeMeta(event.promptIds.iterator().next()));
            if (!event.attemptIds.isEmpty()) node.put("observed_attempt_id", safeMeta(event.attemptIds.iterator().next()));
            if (!event.instances.isEmpty()) node.put("observed_instance_id", safeMeta(event.instances.iterator().next()));
        } else {
            node.put("attempt_id", event.attempt.id());
            node.put("historical_attempt", event.historical);
            node.put("candidate_only", false);
        }
    }

    private static String evidenceText(ParsedEvent event) {
        return event.text + (event.structuredFields.isBlank() ? "" : "\nstructured_fields: " + event.structuredFields);
    }

    /** 归档组件也可复用同一脱敏规则；哈希必须针对脱敏后的实际内容计算。 */
    public static String redact(String value) {
        if (value == null) return "";
        String result = PRIVATE_KEY.matcher(value).replaceAll("[REDACTED PRIVATE KEY]");
        result = URL_CREDENTIALS.matcher(result).replaceAll("$1[REDACTED]@");
        result = COOKIE_HEADER.matcher(result).replaceAll("$1[REDACTED]");
        result = BEARER.matcher(result).replaceAll("$1[REDACTED]");
        result = SECRET.matcher(result).replaceAll("$1[REDACTED]");
        return QUERY_SECRET.matcher(result).replaceAll("$1[REDACTED]");
    }

    private static String safeMeta(String value) { return excerpt(redact(value), 256); }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static String excerpt(String value, int max) {
        if (value.length() <= max) return value;
        int end = max - 1;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end) + "…";
    }

    private static String field(List<JsonNode> scopes, String... names) {
        for (String name : names) for (JsonNode scope : scopes) {
            JsonNode value = scope.get(name);
            if (value != null && (value.isTextual() || value.isNumber())) return value.asText();
        }
        return "";
    }

    private static String label(Map<String, String> labels, String... names) {
        for (String name : names) if (labels.containsKey(name) && !labels.get(name).isBlank()) return labels.get(name);
        return "";
    }

    private static String firstNonempty(String... values) { for (String value : values) if (value != null && !value.isBlank()) return value; return ""; }

    private static String stream(Map<String, String> labels) {
        return label(labels, "namespace", "namespace_name", "kubernetes.pod_namespace", "kubernetes.namespace_name", "k8s.namespace.name") + "|"
                + label(labels, "pod_uid", "kubernetes.pod_uid", "k8s.pod.uid") + "|"
                + label(labels, "pod", "pod_name", "kubernetes.pod_name", "k8s.pod.name") + "|"
                + label(labels, "container", "container_name", "kubernetes.container_name", "k8s.container.name") + "|"
                + label(labels, "restart", "restart_count", "container_restart_count", "kubernetes.container_restart_count");
    }

    private static void strings(ArrayNode node, Collection<String> values) { for (String value : values) node.add(value); }

    private static final class TaskState {
        final Task task;
        final boolean complete;
        final Set<String> flags = new TreeSet<>();
        final List<ParsedEvent> events = new ArrayList<>();
        TaskState(Task task, boolean complete) { this.task = task; this.complete = complete; }
    }

    private static final class ParsedEvent {
        final LogEvent event;
        final String stream;
        final String pod;
        final String container;
        final List<JsonNode> scopes = new ArrayList<>();
        final List<JsonNode> errorScopes = new ArrayList<>();
        final Set<String> taskIds = new LinkedHashSet<>();
        final Set<String> promptIds = new LinkedHashSet<>();
        final Set<String> attemptIds = new LinkedHashSet<>();
        final Set<String> instances = new LinkedHashSet<>();
        final Set<String> flags = new TreeSet<>();
        String text;
        String continuationText = "";
        String structuredFields = "";
        String component = "unknown";
        String eventName = "";
        String level = "";
        String basis;
        String evidenceId;
        String failureSignature;
        TaskState owner;
        Attempt attempt;
        boolean historical;
        boolean multilineChild;
        boolean classified;
        int multilineCount;
        int ordinal;
        ParsedEvent(LogEvent event) {
            this.event = event;
            this.stream = stream(event.labels());
            this.pod = label(event.labels(), "pod", "pod_name", "kubernetes.pod_name", "k8s.pod.name");
            this.container = label(event.labels(), "container", "container_name", "kubernetes.container_name", "k8s.container.name");
        }
    }
}
