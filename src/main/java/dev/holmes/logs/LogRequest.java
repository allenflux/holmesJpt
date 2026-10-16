package dev.holmes.logs;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

/** 批次参数独立于模型问题，拒绝任意查询文本和任意数据源地址。 */
public record LogRequest(String source, String namespace, String mode, List<String> taskIds,
                         int requestedCount, Instant from, Instant to, boolean archiveRequired) {
    public static LogRequest parse(JsonNode node, LogConfig config) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("请求必须是 JSON 对象");
        SetValidator.fields(node, java.util.Set.of("source", "namespace", "selection", "attempt_scope", "raw_archive"));
        String source = text(node, "source", config.source());
        if (!source.equals(config.source())) throw new IllegalArgumentException("未知日志来源");
        String namespace = text(node, "namespace", "prod");
        if (!config.namespaces().contains(namespace)) throw new IllegalArgumentException("namespace 不在授权配置中");
        JsonNode selection = node.path("selection");
        if (!selection.isObject()) throw new IllegalArgumentException("缺少 selection 对象");
        SetValidator.fields(selection, java.util.Set.of("mode", "task_ids", "requested_count", "from", "to"));
        String mode = text(selection, "mode", "");
        if (!List.of("latest_failed", "explicit_task_ids").contains(mode)) throw new IllegalArgumentException("无效 selection.mode");
        Instant from = instant(selection, "from"), to = instant(selection, "to");
        if (from.getNano() % 1_000_000 != 0 || to.getNano() % 1_000_000 != 0)
            throw new IllegalArgumentException("首版查询边界支持毫秒精度，请勿提供子毫秒时间");
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofHours(24)) > 0)
            throw new IllegalArgumentException("时间范围必须递增且不超过 24 小时");
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        int count = 20;
        if (mode.equals("explicit_task_ids")) {
            JsonNode input = selection.path("task_ids");
            if (!input.isArray() || input.isEmpty() || input.size() > 50 || selection.has("requested_count"))
                throw new IllegalArgumentException("显式模式需要 1–50 个 task_ids，不能同时提供 requested_count");
            for (JsonNode value : input) {
                if (!value.isTextual() || !validId(value.asText())) throw new IllegalArgumentException("task_id 格式无效");
                ids.add(value.asText());
            }
            count = ids.size();
        } else {
            if (selection.has("task_ids")) throw new IllegalArgumentException("两种任务选择模式不能混用");
            if (selection.has("requested_count")) {
                if (!selection.get("requested_count").isIntegralNumber() || !selection.get("requested_count").canConvertToInt())
                    throw new IllegalArgumentException("requested_count 必须是整数");
                count = selection.get("requested_count").asInt();
            }
            if (count < 1 || count > 50) throw new IllegalArgumentException("requested_count 必须介于 1 与 50");
        }
        if (!text(node, "attempt_scope", "final_failed").equals("final_failed"))
            throw new IllegalArgumentException("首版仅支持 final_failed");
        String archive = text(node, "raw_archive", "none");
        if (!archive.equals("none") && !archive.equals("required")) throw new IllegalArgumentException("raw_archive 必须是 none 或 required");
        return new LogRequest(source, namespace, mode, List.copyOf(ids), count, from, to, archive.equals("required"));
    }

    static boolean validId(String value) { return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"); }
    static String text(JsonNode node, String field, String fallback) {
        if (!node.has(field)) return fallback;
        if (!node.get(field).isTextual() || node.get(field).asText().isBlank()) throw new IllegalArgumentException(field + " 必须是非空字符串");
        return node.get(field).asText();
    }
    static Instant instant(JsonNode node, String field) {
        try { return Instant.parse(text(node, field, "")); }
        catch (RuntimeException e) { throw new IllegalArgumentException(field + " 必须是带时区的 ISO 8601 时间"); }
    }
    private static final class SetValidator {
        static void fields(JsonNode node, java.util.Set<String> allowed) {
            var names = node.fieldNames();
            while (names.hasNext()) if (!allowed.contains(names.next())) throw new IllegalArgumentException("请求包含不支持的字段");
        }
    }
}
