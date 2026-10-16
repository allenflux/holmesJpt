package dev.holmes.logs;

import com.fasterxml.jackson.databind.JsonNode;
import dev.holmes.Json;
import dev.holmes.logs.LogModels.FetchResult;
import dev.holmes.logs.LogModels.LogEvent;
import dev.holmes.logs.LogModels.LogQuery;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 供离线验证与重放使用的有界 JSONL 来源，与线上日志使用相同事件格式。 */
public final class FileLogSource implements LogSource {
    private static final int MAX_LINE_BYTES = 256 * 1024;
    private static final Set<String> PAYLOAD_FIELDS = Set.of("_time", "time", "timestamp", "Time", "_msg", "message", "log", "Line", "labels");
    private final Path file;

    public FileLogSource(Path file) {
        if (file == null) throw new IllegalArgumentException("本地日志路径不能为空");
        this.file = file.toAbsolutePath().normalize();
    }

    @Override public FetchResult fetch(LogQuery query, int maxEvents, long maxBytes, Instant deadline)
            throws IOException, InterruptedException {
        GrafanaVictoriaLogsSource.validateQuery(query);
        if (deadline == null || maxEvents < 0 || maxBytes < 0) throw new IllegalArgumentException("日志预算无效");
        List<LogEvent> events = new ArrayList<>();
        LinkedHashSet<String> limitations = new LinkedHashSet<>();
        Map<String, String> filters = GrafanaVictoriaLogsSource.canonicalFilters(query.streamFilters());
        if (maxEvents == 0) return new FetchResult(List.of(), false, List.of("日志记录预算已耗尽，日志不完整"), 0);
        long bytes = 0;
        int lineNumber = 1;
        boolean oversized = false;
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            while (true) {
                if ((bytes & 4095) == 0) {
                    if (Thread.interrupted()) throw new InterruptedException("本地日志读取被中断");
                    if (!Instant.now().isBefore(deadline)) { limitations.add("批次截止时间已到，日志不完整"); break; }
                }
                if (bytes >= maxBytes) {
                    if (Files.size(file) > bytes) limitations.add("本地日志读取字节预算已耗尽，日志不完整");
                    break;
                }
                int next = input.read();
                if (next < 0) break;
                bytes++;
                if (next == '\n') {
                    if (!oversized) acceptLine(line.toByteArray(), lineNumber, query, filters, events, limitations, maxEvents);
                    line.reset(); oversized = false; lineNumber++;
                } else if (!oversized) {
                    if (line.size() >= MAX_LINE_BYTES) {
                        limitations.add("本地日志包含超过单行 256 KiB 上限的记录，已跳过，日志不完整");
                        line.reset(); oversized = true;
                    } else line.write(next);
                }
                if (events.size() >= maxEvents) {
                    if (Files.size(file) > bytes || line.size() > 0) limitations.add("日志记录预算已耗尽，日志不完整");
                    break;
                }
            }
            if (!oversized && line.size() > 0 && !limitations.contains("本地日志读取字节预算已耗尽，日志不完整")
                    && Instant.now().isBefore(deadline)) {
                acceptLine(line.toByteArray(), lineNumber, query, filters, events, limitations, maxEvents);
            }
        }
        if (!Instant.now().isBefore(deadline)) limitations.add("批次截止时间已到，日志不完整");
        events.sort(Comparator.comparing(LogEvent::time));
        return new FetchResult(events, limitations.isEmpty(), List.copyOf(limitations), bytes);
    }

    private static void acceptLine(byte[] line, int lineNumber, LogQuery query, Map<String, String> filters,
                                   List<LogEvent> events, LinkedHashSet<String> limitations, int maxEvents) {
        if (line.length == 0 || new String(line, java.nio.charset.StandardCharsets.UTF_8).isBlank()) return;
        try {
            JsonNode object = Json.MAPPER.readTree(line);
            if (object == null || !object.isObject()) throw new IOException("JSONL 必须为对象");
            JsonNode timeValue = first(object, "_time", "time", "timestamp", "Time");
            JsonNode messageValue = first(object, "_msg", "message", "log", "Line");
            Instant time = GrafanaVictoriaLogsSource.parseTime(timeValue);
            if (messageValue == null || !messageValue.isTextual()) throw new IOException("日志缺少文本正文");
            Map<String, String> labels = new LinkedHashMap<>();
            GrafanaVictoriaLogsSource.addLabels(object.get("labels"), labels);
            for (var field : object.properties()) {
                if (!PAYLOAD_FIELDS.contains(field.getKey())) {
                    if (field.getValue().isObject()) {
                        var nested = Json.MAPPER.createObjectNode();
                        nested.set(field.getKey(), field.getValue());
                        GrafanaVictoriaLogsSource.addLabels(nested, labels);
                    } else GrafanaVictoriaLogsSource.addScalar(field.getKey(), field.getValue(), labels);
                }
            }
            GrafanaVictoriaLogsSource.normalizeLabels(labels);
            if (!labels.containsKey("namespace")) throw new IOException("日志缺少 namespace");
            if (!labels.get("namespace").equals(labels.get("kubernetes.pod_namespace"))) throw new IOException("日志 namespace 别名冲突");
            if (!query.namespace().equals(labels.get("namespace")) || time.isBefore(query.from()) || !time.isBefore(query.to())) return;
            for (var filter : filters.entrySet()) if (!filter.getValue().equals(labels.get(filter.getKey()))) return;
            String message = messageValue.asText();
            if (!query.identifiers().isEmpty() && query.identifiers().stream().noneMatch(id -> message.contains(id)
                    || labels.values().stream().anyMatch(value -> value.contains(id)))) return;
            if (events.size() >= maxEvents) { limitations.add("日志记录预算已耗尽，日志不完整"); return; }
            events.add(new LogEvent(time, message, labels, GrafanaVictoriaLogsSource.eventId(labels)));
        } catch (IOException | RuntimeException e) {
            // 错误消息只包含位置，不将可能包含密钥的原始日志写入异常或报告。
            limitations.add("本地日志第 " + lineNumber + " 行格式无效，已跳过");
        }
    }

    private static JsonNode first(JsonNode object, String... names) {
        for (String name : names) if (object.hasNonNull(name)) return object.get(name);
        return null;
    }
}
