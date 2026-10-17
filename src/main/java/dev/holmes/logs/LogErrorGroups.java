package dev.holmes.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.holmes.Json;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从批次既有错误证据生成工程师处理视图，不改变规则结果或执行归属。 */
public final class LogErrorGroups {
    private static final int MAX_GROUPS = 40;
    private static final int MAX_VIEW_BYTES = 64 * 1024;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final Pattern URL = Pattern.compile("(?i)[a-z][a-z0-9+.-]{1,20}://[^\\s<>\\\"'\\\\]+");
    private static final Pattern RESOURCE_PATH = Pattern.compile("(?<![A-Za-z0-9_:])/(?!tmp(?:/|\\b)|var/tmp(?:/|\\b))[^\\s<>\\\"'\\\\;]+");
    private static final Set<String> FAILURE_EVENTS = Set.of("execution_error", "execution_failed", "execution_failure", "task_failed", "task_failure");
    private static final Pattern UUID = Pattern.compile("(?i)\\b[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\b");
    private static final Pattern TIME = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}[T ][0-9:.+-]+(?:Z)?");
    private static final Set<String> VOLATILE_QUERY = Set.of("key", "apikey", "authkey", "token", "accesstoken", "refreshtoken",
            "authorization", "password", "secret", "clientsecret", "credential", "signature", "sig", "expires", "expiry", "exp",
            "expiration", "timestamp", "ts", "nonce", "session", "sessionid", "taskid", "promptid", "attemptid", "instanceid", "nodeid", "cachebuster");

    private LogErrorGroups() { }

    public static ObjectNode build(JsonNode report) {
        Map<String, JsonNode> tasks = new LinkedHashMap<>();
        Set<String> noise = new HashSet<>();
        for (JsonNode task : report.path("tasks")) {
            String ref = text(task, "task_ref");
            if (ID.matcher(ref).matches()) tasks.putIfAbsent(ref, task);
            addNoise(noise, task, "task_id", "task_ref", "final_attempt_id");
        }
        Map<String, JsonNode> evidence = new LinkedHashMap<>();
        Set<String> conflicting = new HashSet<>();
        for (JsonNode item : report.path("evidence")) {
            String id = text(item, "evidence_id");
            if (!ID.matcher(id).matches()) continue;
            JsonNode previous = evidence.putIfAbsent(id, item);
            if (previous != null && !previous.equals(item)) conflicting.add(id);
            addNoise(noise, item, "observed_prompt_id", "observed_instance_id", "observed_attempt_id", "attempt_id", "pod");
        }
        conflicting.forEach(evidence::remove);
        List<Pattern> identifiers = noise.stream().sorted(Comparator.comparingInt(String::length).reversed().thenComparing(String::compareTo))
                .map(value -> Pattern.compile("(?<![A-Za-z0-9_-])" + Pattern.quote(value) + "(?![A-Za-z0-9_-])")).toList();
        Map<String, Group> groups = new HashMap<>();
        Set<String> errorTasks = new HashSet<>();
        Set<String> structuredTasks = new HashSet<>();
        for (JsonNode item : evidence.values()) {
            String ref = text(item, "task_ref"), id = text(item, "evidence_id");
            JsonNode task = tasks.get(ref);
            if (task == null || !contains(task.path("evidence_ids"), id)) continue;
            if (item.has("failure_signature") || !text(item, "event").isBlank()) structuredTasks.add(ref);
            if (item.path("historical_attempt").asBoolean() || item.path("context_only").asBoolean()) continue;
            String signature = signature(item);
            if (signature == null) continue;
            String component = safe(first(text(item, "component"), text(item, "container"), "unknown"), 128);
            String node = safe(text(item, "node_type"), 128), exception = safe(text(item, "exception_type"), 256);
            String message = normalized(first(text(item, "error_summary"), text(item, "exception_message"),
                    text(item, "event"), signature), identifiers);
            String nodeIdentity = first(node, component);
            String key = signature + "\n" + nodeIdentity + "\n" + exception + "\n" + message;
            Group group = groups.computeIfAbsent(key, ignored -> new Group(key, signature, component, node, exception, message));
            group.components.add(component);
            group.tasks.add(ref);
            group.evidence.add(item);
            group.candidate |= task.path("association_scope").asText().equals("TASK_ONLY") || item.path("candidate_only").asBoolean()
                    || !item.path("historical_attempt").isBoolean();
            errorTasks.add(ref);
        }
        List<Group> ordered = groups.values().stream().sorted(Comparator.comparingInt((Group group) -> group.tasks.size()).reversed()
                .thenComparing(group -> group.id)).toList();
        ObjectNode view = Json.MAPPER.createObjectNode().put("version", "engineer-v1")
                .put("truncated", ordered.size() > MAX_GROUPS || ordered.stream().anyMatch(group -> group.message.length() > 400));
        ObjectNode summary = view.putObject("summary").put("selected_task_count", Math.max(0, report.path("selected_count").asInt()))
                .put("processed_task_count", Math.max(0, report.path("processed_count").asInt())).put("error_task_count", errorTasks.size())
                .put("pending_task_count", tasks.size() - errorTasks.size()).put("group_count", groups.size());
        ArrayNode cards = view.putArray("groups");
        ordered.stream().limit(MAX_GROUPS).forEach(group -> cards.add(group.card()));
        ArrayNode pending = view.putArray("pending_tasks");
        tasks.entrySet().stream().sorted(Map.Entry.comparingByKey()).filter(entry -> !errorTasks.contains(entry.getKey())).forEach(entry -> {
            JsonNode task = entry.getValue();
            String status = text(task, "collection_status");
            String reason = switch (status) {
                case "NO_MATCHES" -> "NO_LOG_MATCH";
                case "FAILED" -> "QUERY_FAILED";
                case "SKIPPED" -> "QUERY_SKIPPED";
                default -> status.isBlank() && !structuredTasks.contains(entry.getKey()) ? "LEGACY_NOT_RECORDED" : "NO_CLEAR_ERROR";
            };
            pending.addObject().put("task_ref", safe(entry.getKey(), 128)).put("task_id", safe(text(task, "task_id"), 128))
                    .put("reason_code", reason).put("collection_status", safe(status, 32));
        });
        summary.put("displayed_group_count", cards.size());
        bound(view, cards, pending, summary);
        return view;
    }

    private static String signature(JsonNode item) {
        String value = text(item, "failure_signature");
        if (value.isBlank() && FAILURE_EVENTS.contains(text(item, "event").toLowerCase(Locale.ROOT))) return "EXECUTION_ERROR";
        if (Set.of("CUDA_OOM", "OOM", "TIMEOUT", "EXECUTION_ERROR").contains(value) || value.matches("HTTP_[45]\\d\\d")) return value;
        if (value.length() <= 300 && value.matches("EXCEPTION:[A-Za-z_$][A-Za-z0-9_.$]*:[0-9a-f]{16}")) return value;
        return null;
    }

    private static String normalized(String raw, List<Pattern> identifiers) {
        String value = LogAnalyzer.redact(raw);
        List<String> resources = new ArrayList<>();
        Matcher urls = URL.matcher(value);
        StringBuffer protectedText = new StringBuffer();
        while (urls.find()) {
            resources.add(resource(urls.group()));
            urls.appendReplacement(protectedText, Matcher.quoteReplacement("\uE000" + (resources.size() - 1) + "\uE001"));
        }
        urls.appendTail(protectedText); value = protectedText.toString();
        Matcher paths = RESOURCE_PATH.matcher(value);
        StringBuffer protectedPaths = new StringBuffer();
        while (paths.find()) {
            resources.add(LogAnalyzer.redact(paths.group()));
            paths.appendReplacement(protectedPaths, Matcher.quoteReplacement("\uE000" + (resources.size() - 1) + "\uE001"));
        }
        paths.appendTail(protectedPaths); value = protectedPaths.toString();
        // 先保护资源身份，再清理执行噪声，资源路径中的 UUID 和数字不能被删掉。
        for (Pattern identifier : identifiers) value = identifier.matcher(value).replaceAll("<id>");
        value = UUID.matcher(value).replaceAll("<uuid>");
        value = TIME.matcher(value).replaceAll("<time>")
                .replaceAll("(?i)\\b(?:node_id|task_id|prompt_id|attempt_id|instance_id|pod)\\s*[:=]\\s*[^;\\s]+", "")
                .replaceAll("(?i)(?:/tmp|/var/tmp)/[^\\s;]+", "<temp>").replaceAll("\\s+", " ").strip();
        for (int i = 0; i < resources.size(); i++) value = value.replace("\uE000" + i + "\uE001", resources.get(i));
        return LogAnalyzer.redact(value.replaceAll("(?:;\\s*){2,}", "; "));
    }

    private static String resource(String raw) {
        String value = raw.replaceAll("[.,;\\)\\]]+$", "").replaceFirst("(?i)([a-z][a-z0-9+.-]{1,20}://)[^/\\s@]+@", "$1");
        try {
            URI uri = URI.create(value);
            if (uri.getRawAuthority() == null) return LogAnalyzer.redact(value);
            List<String> stable = new ArrayList<>();
            if (uri.getRawQuery() != null) for (String parameter : uri.getRawQuery().split("&")) {
                String key = URLDecoder.decode(parameter.split("=", 2)[0], StandardCharsets.UTF_8).toLowerCase(Locale.ROOT).replaceAll("[_ -]", "");
                if (VOLATILE_QUERY.contains(key) || key.startsWith("xamz") || key.startsWith("xgoog")
                        || key.contains("token") || key.contains("secret") || key.contains("signature") || key.contains("credential")) continue;
                stable.add(parameter);
            }
            stable.sort(String::compareTo);
            return LogAnalyzer.redact(uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority().toLowerCase(Locale.ROOT)
                    + first(uri.getRawPath(), "/") + (stable.isEmpty() ? "" : "?" + String.join("&", stable)));
        } catch (IllegalArgumentException invalid) { return LogAnalyzer.redact(value.replaceFirst("[?#].*$", "")); }
    }

    private static void bound(ObjectNode view, ArrayNode groups, ArrayNode pending, ObjectNode summary) {
        if (bytes(view) > MAX_VIEW_BYTES) {
            view.put("truncated", true);
            for (JsonNode group : groups) ((ObjectNode) group).put("normalized_message", safe(text(group, "normalized_message"), 160));
        }
        while (bytes(view) > MAX_VIEW_BYTES && !groups.isEmpty()) groups.remove(groups.size() - 1);
        while (bytes(view) > MAX_VIEW_BYTES && !pending.isEmpty()) pending.remove(pending.size() - 1);
        summary.put("displayed_group_count", groups.size());
    }

    private static int bytes(JsonNode node) { return node.toString().getBytes(StandardCharsets.UTF_8).length; }
    private static String text(JsonNode node, String name) { return node.path(name).isValueNode() && !node.path(name).isNull() ? node.path(name).asText() : ""; }
    private static String first(String... values) { for (String value : values) if (!value.isBlank() && !value.equals("unknown")) return value; return "unknown"; }
    private static String safe(String raw, int limit) {
        String value = LogAnalyzer.redact(raw);
        if (value.length() <= limit) return value;
        int end = limit - 1;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end) + "…";
    }
    private static void addNoise(Set<String> noise, JsonNode node, String... names) {
        for (String name : names) { String value = text(node, name); if (!value.isBlank() && !value.matches("\\d+")) noise.add(value); }
    }
    private static boolean contains(JsonNode array, String value) { for (JsonNode node : array) if (node.isTextual() && node.asText().equals(value)) return true; return false; }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 24); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static final class Group {
        final String id, signature, node, exception, message;
        final Set<String> components = new TreeSet<>();
        final Set<String> tasks = new TreeSet<>();
        final List<JsonNode> evidence = new ArrayList<>();
        boolean candidate;
        Group(String key, String signature, String component, String node, String exception, String message) {
            id = "err_" + hash(key); this.signature = signature; components.add(component);
            this.node = node; this.exception = exception; this.message = message;
        }
        ObjectNode card() {
            Advice advice = advice(signature, node, exception);
            String component = safe(String.join(" / ", components.stream().filter(value -> !value.equals("unknown") || components.size() == 1).toList()), 128);
            ObjectNode card = Json.MAPPER.createObjectNode().put("group_id", id).put("signature", signature).put("title", advice.title)
                    .put("component", component).put("node_type", node).put("exception_type", exception).put("normalized_message", safe(message, 400))
                    .put("task_count", tasks.size()).put("evidence_count", evidence.size()).put("suggested_owner_role", advice.role)
                    .put("assignment_status", "UNASSIGNED").put("candidate_only", candidate)
                    .put("quality_hint", candidate ? "任务级候选错误，最终执行和重试归属未验证。" : "基于已关联错误证据；同类表现不等于共同根因。");
            ArrayNode taskRefs = card.putArray("task_refs"); tasks.forEach(taskRefs::add);
            ArrayNode representatives = card.putArray("representative_evidence_ids");
            List<JsonNode> ranked = evidence.stream().sorted(Comparator.comparingInt(LogErrorGroups::detailScore).reversed()
                    .thenComparing((JsonNode item) -> text(item, "event_time"), Comparator.reverseOrder()).thenComparing(item -> text(item, "evidence_id"))).toList();
            Set<String> chosenTasks = new HashSet<>(), chosenIds = new HashSet<>();
            for (JsonNode item : ranked) {
                if (!chosenTasks.add(text(item, "task_ref"))) continue;
                String id = text(item, "evidence_id"); representatives.add(id); chosenIds.add(id);
                if (representatives.size() == 2) break;
            }
            if (representatives.size() < 2) for (JsonNode item : ranked) {
                String id = text(item, "evidence_id");
                if (chosenIds.add(id)) representatives.add(id);
                if (representatives.size() == 2) break;
            }
            ArrayNode recommendations = card.putArray("recommendations"); advice.actions.forEach(recommendations::add);
            return card;
        }
    }
    private static int detailScore(JsonNode item) {
        int score = text(item, "exception_message").isBlank() ? 0 : 4;
        if (!text(item, "exception_type").isBlank()) score += 2;
        if (!text(item, "node_type").isBlank()) score++;
        if (item.path("historical_attempt").isBoolean() && !item.path("historical_attempt").asBoolean()) score++;
        return score;
    }
    private record Advice(String title, String role, List<String> actions) { }
    private static Advice advice(String signature, String node, String exception) {
        if (signature.equals("HTTP_404")) return new Advice(node.toLowerCase(Locale.ROOT).contains("video") ? "视频资源下载返回 404" : "请求的资源返回 404",
                "工作流/节点维护工程师（协同资源服务维护工程师）", List.of("核对资源路径是否存在、链接是否仍有效。", "检查节点资源参数与上游返回状态，必要时替换为可访问的地址。", "修复资源或参数后重试一项任务，确认不再返回 404。"));
        if (signature.matches("HTTP_5\\d\\d")) return new Advice("上游服务返回 " + signature.substring(5), "上游服务/平台维护工程师",
                List.of("检查上游服务健康、网关状态及同期错误。", "按已有重试策略处理临时故障，避免连续扩大并发。", "服务恢复后单项复测，确认请求成功。"));
        if (signature.equals("HTTP_401") || signature.equals("HTTP_403")) return new Advice("资源访问未通过认证或授权", "资源服务/权限配置维护工程师",
                List.of("核对服务端访问身份与目标资源权限。", "检查链接有效期及权限配置变更。", "权限修正后单项复测。"));
        if (signature.equals("CUDA_OOM") || signature.equals("OOM")) return new Advice(signature.equals("CUDA_OOM") ? "GPU 显存不足" : "运行内存不足", "GPU/运行平台与节点维护工程师",
                List.of("核对出错节点的输入规模、批量与资源占用。", "减少输入规模或并发，检查资源是否及时释放。", "调整后单项复测并观察内存占用。"));
        if (signature.equals("TIMEOUT")) return new Advice("执行或请求超时", "服务/节点维护工程师",
                List.of("定位耗时阶段及其上游连接状态。", "核对超时配置与实际耗时，先排除依赖故障。", "修正后单项复测并观察耗时。"));
        if (signature.equals("EXECUTION_ERROR")) return new Advice("执行报错，异常详情待补充", "工作流/节点维护工程师",
                List.of("补采该任务报错事件的异常类别与消息。", "核对执行时间窗及重试归属，再确定修复范围。"));
        if (signature.matches("HTTP_4\\d\\d")) return new Advice("请求返回 " + signature.substring(5), "接口/节点维护工程师",
                List.of("结合返回状态核对请求参数与接口约定。", "修正请求或目标资源配置后单项复测。"));
        return new Advice(exception.isBlank() ? "节点执行发生异常" : "节点执行异常（" + safe(exception, 80) + "）", "应用/节点维护工程师",
                List.of("结合代表证据检查对应节点的异常消息与输入参数。", "修正节点配置或依赖问题后单项复测。"));
    }
}
