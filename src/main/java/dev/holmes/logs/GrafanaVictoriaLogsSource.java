package dev.holmes.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.holmes.Json;
import dev.holmes.logs.LogModels.FetchResult;
import dev.holmes.logs.LogModels.LogEvent;
import dev.holmes.logs.LogModels.LogQuery;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 通过 Grafana 数据源代理查询 VictoriaLogs；业务请求不能指定 URL 或原始 LogsQL。 */
public final class GrafanaVictoriaLogsSource implements LogSource {
    private static final String DATASOURCE_TYPE = "victoriametrics-logs-datasource";
    private static final int MAX_REQUESTS = 128;
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_MESSAGE_BYTES = 256 * 1024;
    private static final Pattern STREAM_LABEL = Pattern.compile("\\s*([A-Za-z_][A-Za-z0-9_.]*)\\s*=\\s*(\"(?:[^\"\\\\]|\\\\.)*\")\\s*(?:,|$)");
    private static final Map<String, String> FILTER_NAMES = Map.ofEntries(
            Map.entry("pod", "kubernetes.pod_name"), Map.entry("pod_name", "kubernetes.pod_name"),
            Map.entry("container", "kubernetes.container_name"), Map.entry("container_name", "kubernetes.container_name"),
            Map.entry("pod_uid", "kubernetes.pod_uid"), Map.entry("container_id", "kubernetes.container_id"),
            Map.entry("restart", "kubernetes.container_restart_count"),
            Map.entry("kubernetes.pod_name", "kubernetes.pod_name"),
            Map.entry("kubernetes.container_name", "kubernetes.container_name"),
            Map.entry("kubernetes.pod_uid", "kubernetes.pod_uid"),
            Map.entry("kubernetes.container_id", "kubernetes.container_id"),
            Map.entry("kubernetes.container_restart_count", "kubernetes.container_restart_count"),
            Map.entry("kubernetes.node_name", "kubernetes.node_name"));

    private final URI endpoint;
    private final String token;
    private final String datasourceUid;
    private final int pageSize;
    private final Duration requestTimeout;
    private final HttpClient http;
    // 同一个适配器的并发批次共享请求槽位，避免每个批次各自放大并发。
    private final Semaphore requests = new Semaphore(4, true);

    public GrafanaVictoriaLogsSource(URI baseUrl, String token, String datasourceUid,
                                    int pageSize, Duration requestTimeout) {
        if (baseUrl == null || !Set.of("http", "https").contains(baseUrl.getScheme())
                || baseUrl.getHost() == null || baseUrl.getUserInfo() != null
                || baseUrl.getQuery() != null || baseUrl.getFragment() != null) {
            throw new IllegalArgumentException("Grafana 地址必须是无凭据、查询参数与 fragment 的 HTTP(S) 基址");
        }
        if (datasourceUid == null || !datasourceUid.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Grafana datasource UID 无效");
        }
        if (pageSize < 1 || pageSize > 1000) {
            throw new IllegalArgumentException("Grafana pageSize 必须在 1 到 1000 之间");
        }
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("Grafana 请求超时必须为正数");
        }
        this.token = token == null ? "" : token;
        if ((!this.token.isBlank() && !this.token.matches("[A-Za-z0-9._~+/-]+=*"))
                || this.token.contains("\r") || this.token.contains("\n")) throw new IllegalArgumentException("Grafana token 无效");
        String base = baseUrl.toASCIIString().replaceAll("/+$", "");
        this.endpoint = URI.create(base + "/api/ds/query?ds_type=" + DATASOURCE_TYPE);
        this.datasourceUid = datasourceUid;
        this.pageSize = pageSize;
        this.requestTimeout = requestTimeout;
        this.http = HttpClient.newBuilder().connectTimeout(requestTimeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public FetchResult fetch(LogQuery query, int maxEvents, long maxBytes, Instant deadline)
            throws IOException, InterruptedException {
        validateQuery(query);
        if (deadline == null || maxEvents < 0 || maxBytes < 0) throw new IllegalArgumentException("日志预算无效");
        State state = new State(maxEvents, maxBytes, deadline);
        Instant from = query.from().truncatedTo(ChronoUnit.MILLIS);
        Instant to = query.to().truncatedTo(ChronoUnit.MILLIS);
        if (!to.equals(query.to())) to = to.plusMillis(1);
        if (!from.equals(query.from()) || !to.equals(query.to())) {
            // Grafana 的 Time 数值可能已截掉子毫秒；边界候选不能被当成确定的完整结果。
            state.limitations.add("请求边界含子毫秒，已外扩到完整毫秒保留候选；Grafana 时间精度无法证明完整性");
        }
        fetchWindow(query, from, to, state);
        state.events.sort(Comparator.comparing(LogEvent::time));
        if (!Instant.now().isBefore(deadline)) state.limitations.add("批次截止时间已到，日志不完整");
        return new FetchResult(state.events, state.limitations.isEmpty(), List.copyOf(state.limitations), state.bytes);
    }

    private void fetchWindow(LogQuery query, Instant from, Instant to, State state)
            throws IOException, InterruptedException {
        if (!from.isBefore(to)) return;
        if (!state.canRequest()) return;
        Page page;
        try {
            byte[] response = request(query, from, to, state);
            state.bytes += response.length;
            page = parsePage(response, query, from, to, state);
        } catch (ResponseLimitException e) {
            state.bytes += e.bytes;
            state.limitations.add("Grafana 响应达到读取字节上限，日志不完整");
            state.stopped = true;
            return;
        } catch (HttpTimeoutException e) {
            state.limitations.add("Grafana 查询超时或超过批次截止时间，日志不完整");
            state.stopped = true;
            return;
        }
        if (page.saturated) {
            if (Duration.between(from, to).compareTo(Duration.ofMillis(1)) <= 0) {
                state.limitations.add("同一毫秒内日志仍达到数据源返回上限，无法证明日志完整");
                state.accept(page.events);
                return;
            }
            if (!state.canRequest()) {
                state.accept(page.events);
                return;
            }
            // 父页只是探测，不能与子窗累加；保留相同正文/时间的真实重复日志。
            // LogsQL 可按纳秒过滤，但 Grafana Time 常只保留毫秒；拆窗边界必须对齐毫秒。
            // 例如 1.8 ms 的日志返回 Time=1 ms，不能用 1.5 ms 边界再次把它过滤掉。
            Instant middle = from.plus(Duration.between(from, to).dividedBy(2)).truncatedTo(ChronoUnit.MILLIS);
            fetchWindow(query, middle, to, state);
            fetchWindow(query, from, middle, state);
        } else {
            state.accept(page.events);
        }
    }

    private byte[] request(LogQuery query, Instant from, Instant to, State state)
            throws IOException, InterruptedException {
        long availableNanos = Duration.between(Instant.now(), state.deadline).toNanos();
        if (availableNanos <= 0 || !requests.tryAcquire(availableNanos, TimeUnit.NANOSECONDS)) {
            throw new HttpTimeoutException("Grafana 请求截止时间已到");
        }
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        BoundedSubscriber subscriber = new BoundedSubscriber(Math.min(MAX_RESPONSE_BYTES, state.maxBytes - state.bytes));
        try {
            Duration remaining = Duration.between(Instant.now(), state.deadline);
            if (remaining.isNegative() || remaining.isZero()) throw new HttpTimeoutException("Grafana 请求截止时间已到");
            Duration timeout = remaining.compareTo(requestTimeout) < 0 ? remaining : requestTimeout;
            ObjectNode body = Json.MAPPER.createObjectNode();
            body.put("from", Long.toString(from.toEpochMilli()));
            // Grafana 的毫秒范围需覆盖子毫秒边界，精确的半开区间由 LogsQL 再约束。
            long upperMillis = to.toEpochMilli();
            if (to.getNano() % 1_000_000 != 0) upperMillis++;
            body.put("to", Long.toString(upperMillis));
            ObjectNode definition = body.putArray("queries").addObject();
            definition.putObject("datasource").put("type", DATASOURCE_TYPE).put("uid", datasourceUid);
            definition.put("editorMode", "code").put("queryType", "range").put("refId", "A")
                    .put("maxLines", pageSize).put("expr", expression(query, from, to));
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(Json.MAPPER.writeValueAsBytes(body)));
            if (!token.isBlank()) request.header("Authorization", "Bearer " + token);
            state.requestCount++;
            Instant requestDeadline = Instant.now().plus(timeout);
            if (requestDeadline.isAfter(state.deadline)) requestDeadline = state.deadline;
            pending = http.sendAsync(request.build(), info -> info.statusCode() == 200 ? subscriber : new HttpStatusSubscriber(info.statusCode()));
            HttpResponse<byte[]> response = pending.get(Math.max(1, Duration.between(Instant.now(), requestDeadline).toNanos()), TimeUnit.NANOSECONDS);
            if (response.statusCode() != 200) throw new IOException("Grafana 查询失败，HTTP " + response.statusCode());
            return response.body();
        } catch (TimeoutException e) {
            if (pending != null) pending.cancel(true);
            state.bytes += subscriber.bytesRead();
            throw new HttpTimeoutException("Grafana 查询超时");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            while (cause != null && !(cause instanceof ResponseLimitException) && !(cause instanceof HttpTimeoutException)
                    && !(cause instanceof HttpStatusException)
                    && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof HttpStatusException status) throw new IOException("Grafana 查询失败，HTTP " + status.statusCode);
            if (cause instanceof ResponseLimitException limited) throw limited;
            if (cause instanceof HttpTimeoutException timedOut) {
                state.bytes += subscriber.bytesRead();
                throw timedOut;
            }
            // 不保留远端错误正文、URL 或 token 的异常链。
            throw new IOException("无法连接 Grafana 或读取其响应");
        } catch (InterruptedException e) {
            if (pending != null) pending.cancel(true);
            throw e;
        } finally {
            requests.release();
        }
    }

    private String expression(LogQuery query, Instant from, Instant to) throws IOException {
        TreeMap<String, String> filters = canonicalFilters(query.streamFilters());
        StringBuilder expr = new StringBuilder("_stream: {kubernetes.pod_namespace=").append(quote(query.namespace()));
        for (var entry : filters.entrySet()) expr.append(", ").append(entry.getKey()).append('=').append(quote(entry.getValue()));
        expr.append("} AND _time:[").append(from).append(',').append(to).append(')');
        if (!query.identifiers().isEmpty()) {
            expr.append(" AND (");
            for (int i = 0; i < query.identifiers().size(); i++) {
                if (i != 0) expr.append(" OR ");
                expr.append("*:").append(quote(query.identifiers().get(i)));
            }
            expr.append(')');
        }
        return expr.append(" | sort desc | limit ").append(pageSize).toString();
    }

    private Page parsePage(byte[] body, LogQuery query, Instant from, Instant to, State state) throws IOException {
        JsonNode response;
        try { response = Json.MAPPER.readTree(body); }
        catch (IOException | RuntimeException e) { throw new IOException("Grafana 返回无法解析的 JSON 响应"); }
        if (response == null || !response.isObject() || meaningful(response.get("error")) || meaningful(response.get("errors"))) {
            throw new IOException("Grafana 返回查询错误");
        }
        JsonNode result = response.path("results").path("A");
        if (!result.isObject()) throw new IOException("Grafana 响应缺少 refId A 查询结果");
        if (meaningful(result.get("error")) || meaningful(result.get("errors"))
                || (result.has("status") && result.path("status").asInt(-1) != 200)) {
            throw new IOException("Grafana 数据源返回查询错误");
        }
        JsonNode frames = result.get("frames");
        if (frames == null || !frames.isArray()) throw new IOException("Grafana 查询结果缺少 frames，无法确认日志为空");
        List<LogEvent> events = new ArrayList<>();
        Map<String, String> streamFilters = canonicalFilters(query.streamFilters());
        int rawRows = 0;
        for (JsonNode frame : frames) {
            JsonNode fields = frame.path("schema").path("fields");
            JsonNode values = frame.path("data").path("values");
            if (!fields.isArray() || fields.isEmpty() || !values.isArray() || values.size() != fields.size()) {
                throw new IOException("Grafana 日志帧结构无效，无法确认日志完整");
            }
            int timeIndex = -1, messageIndex = -1, labelsIndex = -1;
            Map<String, String> commonLabels = new LinkedHashMap<>();
            for (int i = 0; i < fields.size(); i++) {
                String name = fields.get(i).path("name").asText("").toLowerCase(Locale.ROOT);
                if (Set.of("time", "_time", "timestamp").contains(name)) timeIndex = i;
                if (Set.of("line", "_msg", "message", "log").contains(name)) messageIndex = i;
                if (name.equals("labels")) labelsIndex = i;
                addLabels(fields.get(i).get("labels"), commonLabels);
                if (!values.get(i).isArray()) throw new IOException("Grafana 日志列结构无效");
            }
            if (timeIndex < 0 || messageIndex < 0) throw new IOException("Grafana 日志帧缺少时间或正文列");
            int rows = values.get(timeIndex).size();
            for (JsonNode column : values) if (column.size() != rows) throw new IOException("Grafana 日志列长度不一致");
            rawRows += rows;
            for (int row = 0; row < rows; row++) {
                Instant time = parseTime(values.get(timeIndex).get(row));
                JsonNode message = values.get(messageIndex).get(row);
                if (!message.isTextual()) throw new IOException("Grafana 日志正文不是文本");
                String text = message.asText();
                // 单条异常日志不能占满内存；多字节文本必须按 UTF-8 字节而非字符计量。
                if (text.length() > MAX_MESSAGE_BYTES || text.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
                    state.limitations.add("单条 Grafana 日志正文超过 256 KiB 上限，已跳过，日志不完整");
                    continue;
                }
                Map<String, String> labels = new LinkedHashMap<>(commonLabels);
                if (labelsIndex >= 0) addLabels(values.get(labelsIndex).get(row), labels);
                for (int column = 0; column < fields.size(); column++) {
                    if (column != timeIndex && column != messageIndex && column != labelsIndex) {
                        addScalar(fields.get(column).path("name").asText(""), values.get(column).get(row), labels);
                    }
                }
                normalizeLabels(labels);
                String namespace = labels.get("namespace");
                if ((namespace != null && !query.namespace().equals(namespace))
                        || (labels.containsKey("kubernetes.pod_namespace") && !query.namespace().equals(labels.get("kubernetes.pod_namespace")))) {
                    state.limitations.add("Grafana 返回与请求 namespace 不一致的日志，已排除");
                    continue;
                }
                labels.putIfAbsent("namespace", query.namespace());
                labels.putIfAbsent("kubernetes.pod_namespace", query.namespace());
                if (streamFilters.entrySet().stream().anyMatch(filter -> !filter.getValue().equals(labels.get(filter.getKey())))) {
                    state.limitations.add("Grafana 返回缺少明确日志流标签或与请求日志流不一致的记录，已排除");
                    continue;
                }
                if (!time.isBefore(from) && time.isBefore(to)) {
                    events.add(new LogEvent(time, text, labels, eventId(labels)));
                }
            }
        }
        return new Page(events, rawRows >= pageSize || hasTruncation(result, rawRows));
    }

    private static boolean meaningful(JsonNode node) {
        return node != null && !node.isNull() && !(node.isTextual() && node.asText().isBlank())
                && !(node.isContainerNode() && node.isEmpty()) && !(node.isBoolean() && !node.asBoolean());
    }

    private static boolean hasTruncation(JsonNode node, int rows) {
        if (node.isObject()) {
            for (var field : node.properties()) {
                String key = field.getKey().toLowerCase(Locale.ROOT);
                JsonNode value = field.getValue();
                // 仅检查返回元数据，日志自身的 limit/truncated 字段不是分页信号。
                if (Set.of("data", "values", "fields").contains(key)) continue;
                if (Set.of("truncated", "partial", "hasmore", "limited").contains(key)
                        && (value.asBoolean(false) || value.asText("").equalsIgnoreCase("true"))) return true;
                if (Set.of("limit", "maxlines", "rowlimit").contains(key) && value.canConvertToInt()
                        && value.asInt() > 0 && rows >= value.asInt()) return true;
                if (key.equals("notices") && value.isArray()) {
                    for (JsonNode notice : value) {
                        String text = notice.path("text").asText("").toLowerCase(Locale.ROOT);
                        if (text.contains("truncat") || text.contains("limit") || text.contains("partial")) return true;
                    }
                }
                if ((value.isObject() || value.isArray()) && hasTruncation(value, rows)) return true;
            }
        } else if (node.isArray()) {
            for (JsonNode value : node) if (hasTruncation(value, rows)) return true;
        }
        return false;
    }

    static void validateQuery(LogQuery query) {
        if (query == null || query.namespace() == null || !query.namespace().matches("[a-z0-9](?:[-a-z0-9]{0,61}[a-z0-9])?")
                || query.from() == null || query.to() == null || !query.from().isBefore(query.to())) {
            throw new IllegalArgumentException("日志 namespace 或时间范围无效");
        }
        if (query.identifiers().size() > 64) throw new IllegalArgumentException("日志标识数量超过上限");
        for (String id : query.identifiers()) {
            if (id == null || id.isBlank() || id.length() > 256) throw new IllegalArgumentException("日志标识无效");
        }
        canonicalFilters(query.streamFilters());
        if (query.identifiers().isEmpty() && query.streamFilters().isEmpty()) {
            throw new IllegalArgumentException("日志查询必须包含任务标识或明确的日志流范围");
        }
    }

    static TreeMap<String, String> canonicalFilters(Map<String, String> filters) {
        TreeMap<String, String> result = new TreeMap<>();
        for (var filter : filters.entrySet()) {
            String key = FILTER_NAMES.get(filter.getKey());
            if (key == null || filter.getValue() == null || filter.getValue().isBlank() || filter.getValue().length() > 512) {
                throw new IllegalArgumentException("日志流筛选字段或值无效");
            }
            String previous = result.put(key, filter.getValue());
            if (previous != null && !previous.equals(filter.getValue())) throw new IllegalArgumentException("日志流筛选别名冲突");
        }
        return result;
    }

    static Instant parseTime(JsonNode value) throws IOException {
        try {
            if (value != null && value.isNumber()) {
                BigDecimal seconds = value.decimalValue().movePointLeft(3);
                long wholeSeconds = seconds.setScale(0, RoundingMode.FLOOR).longValueExact();
                int nanos = seconds.subtract(BigDecimal.valueOf(wholeSeconds)).movePointRight(9).intValue();
                return Instant.ofEpochSecond(wholeSeconds, nanos);
            }
            if (value != null && value.isTextual()) return Instant.parse(value.asText());
        } catch (DateTimeException | ArithmeticException e) {
            throw new IOException("日志时间格式无效");
        }
        throw new IOException("日志缺少有效时间");
    }

    static void addLabels(JsonNode input, Map<String, String> labels) throws IOException {
        if (input == null || input.isNull()) return;
        JsonNode value = input;
        if (input.isTextual()) {
            String text = input.asText().strip();
            if (text.isEmpty()) return;
            try { value = Json.MAPPER.readTree(text); }
            catch (IOException e) {
                // 兼容少数插件版本返回的 Prometheus 风格 labels 字符串。
                if (!text.startsWith("{") || !text.endsWith("}")) throw new IOException("日志 labels 字符串格式无效");
                String content = text.substring(1, text.length() - 1);
                Matcher matcher = STREAM_LABEL.matcher(content);
                int consumed = 0;
                while (matcher.find() && matcher.start() == consumed) {
                    try { labels.put(matcher.group(1), Json.MAPPER.readTree(matcher.group(2)).asText()); }
                    catch (IOException e2) { throw new IOException("日志 labels 字符串格式无效"); }
                    consumed = matcher.end();
                }
                if (!content.substring(consumed).isBlank()) throw new IOException("日志 labels 字符串格式无效");
                return;
            }
        }
        if (value == null || !value.isObject()) throw new IOException("日志 labels 必须是对象");
        flatten("", value, labels);
    }

    private static void flatten(String prefix, JsonNode value, Map<String, String> labels) {
        for (var field : value.properties()) {
            String key = prefix + field.getKey();
            if (field.getValue().isObject()) flatten(key + ".", field.getValue(), labels);
            else addScalar(key, field.getValue(), labels);
        }
    }

    static void addScalar(String key, JsonNode value, Map<String, String> labels) {
        if (!key.isEmpty() && value != null && !value.isNull() && value.isValueNode()) labels.put(key, value.asText());
    }

    static void normalizeLabels(Map<String, String> labels) {
        alias(labels, "namespace", "kubernetes.pod_namespace");
        alias(labels, "pod", "kubernetes.pod_name");
        alias(labels, "container", "kubernetes.container_name");
        alias(labels, "pod_uid", "kubernetes.pod_uid");
        alias(labels, "container_id", "kubernetes.container_id");
        alias(labels, "restart", "kubernetes.container_restart_count");
    }

    private static void alias(Map<String, String> labels, String alias, String canonical) {
        if (labels.containsKey(canonical)) labels.putIfAbsent(alias, labels.get(canonical));
        if (labels.containsKey(alias)) labels.putIfAbsent(canonical, labels.get(alias));
    }

    static String eventId(Map<String, String> labels) {
        for (String name : List.of("event_id", "_id", "eventId")) {
            String value = labels.get(name);
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static String quote(String text) throws IOException { return Json.MAPPER.writeValueAsString(text); }
    private record Page(List<LogEvent> events, boolean saturated) { }

    private static final class State {
        final int maxEvents;
        final long maxBytes;
        final Instant deadline;
        final List<LogEvent> events = new ArrayList<>();
        final LinkedHashSet<String> limitations = new LinkedHashSet<>();
        long bytes;
        int requestCount;
        boolean stopped;
        State(int maxEvents, long maxBytes, Instant deadline) {
            this.maxEvents = maxEvents; this.maxBytes = maxBytes; this.deadline = deadline;
        }
        boolean canRequest() {
            if (stopped) return false;
            if (events.size() >= maxEvents) limitations.add("日志记录预算已耗尽，日志不完整");
            else if (bytes >= maxBytes) limitations.add("日志响应字节预算已耗尽，日志不完整");
            else if (!Instant.now().isBefore(deadline)) limitations.add("批次截止时间已到，日志不完整");
            else if (requestCount >= MAX_REQUESTS) limitations.add("Grafana 拆窗请求次数达到上限，日志不完整");
            else return true;
            stopped = true;
            return false;
        }
        void accept(List<LogEvent> page) {
            int keep = Math.min(page.size(), maxEvents - events.size());
            events.addAll(page.subList(0, keep));
            if (keep < page.size()) {
                limitations.add("日志记录预算已耗尽，日志不完整");
                stopped = true;
            }
        }
    }

    private static final class ResponseLimitException extends IOException {
        final long bytes;
        ResponseLimitException(long bytes) { super("Grafana 响应达到读取上限"); this.bytes = bytes; }
    }

    private static final class HttpStatusException extends IOException {
        final int statusCode;
        HttpStatusException(int statusCode) { super("Grafana HTTP " + statusCode); this.statusCode = statusCode; }
    }

    /** 非成功状态在响应头阶段终止订阅，不读取可能含密钥的远端错误正文。 */
    private static final class HttpStatusSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result;
        HttpStatusSubscriber(int statusCode) { result = CompletableFuture.failedFuture(new HttpStatusException(statusCode)); }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) { subscription.cancel(); }
        @Override public void onNext(List<ByteBuffer> items) { }
        @Override public void onError(Throwable error) { }
        @Override public void onComplete() { }
    }

    /** 在 HTTP 订阅层限定内存，慢速响应也受 sendAsync 的整体截止时间控制。 */
    private static final class BoundedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final long limit;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private volatile int bytesRead;
        BoundedSubscriber(long limit) { this.limit = limit; }
        int bytesRead() { return bytesRead; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (limit == 0) { subscription.cancel(); result.complete(new byte[0]); }
            else subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer item : items) {
                if (item.remaining() > limit - buffer.size()) {
                    int remaining = (int) (limit - buffer.size());
                    byte[] chunk = new byte[remaining];
                    item.get(chunk);
                    buffer.writeBytes(chunk);
                    bytesRead = buffer.size();
                    subscription.cancel();
                    result.completeExceptionally(new ResponseLimitException(bytesRead));
                    return;
                }
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                buffer.writeBytes(chunk);
                bytesRead = buffer.size();
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(buffer.toByteArray()); }
    }
}
