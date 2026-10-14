package dev.holmes.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.holmes.Json;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/** 本地只读文件工具与管理员配置的固定命令工具，不接受模型生成的 shell。 */
public final class ToolRegistry {
    private static final int MAX_ENTRIES = 2_000;
    private static final int MAX_DEPTH = 12;
    private static final int MAX_FILE_BYTES = 256 * 1_024;
    private static final long MAX_SEARCH_BYTES = 8L * 1_024 * 1_024;
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)}");
    private static final Set<String> MODEL_KEYS = Set.of("API_KEY", "DEEPSEEK_API_KEY",
            "ANTHROPIC_API_KEY", "GEMINI_API_KEY", "GOOGLE_API_KEY", "AZURE_OPENAI_API_KEY");

    private final Path fileRoot;
    private final int timeoutSeconds;
    private final int maxOutputChars;
    private final Map<String, Command> commands;
    private final ArrayNode definitions;

    private ToolRegistry(Path fileRoot, int timeoutSeconds, int maxOutputChars,
                         Map<String, Command> commands, ArrayNode definitions) {
        this.fileRoot = fileRoot;
        this.timeoutSeconds = timeoutSeconds;
        this.maxOutputChars = maxOutputChars;
        this.commands = commands;
        this.definitions = definitions;
    }

    public static ToolRegistry load(Path configFile, int timeoutSeconds, int maxOutputChars) throws IOException {
        if (timeoutSeconds < 1 || maxOutputChars < 1) {
            throw new IOException("工具超时和输出上限必须为正整数");
        }
        Path configPath = configFile.toAbsolutePath().normalize();
        JsonNode config = Json.MAPPER.readTree(configPath.toFile());
        requireObject(config, "工具配置");
        onlyFields(config, Set.of("fileRoot", "commands"), "工具配置");
        String configuredRoot = text(config, "fileRoot", "工具配置");
        Path root = configPath.getParent().resolve(configuredRoot).normalize().toRealPath();
        if (!Files.isDirectory(root)) throw new IOException("fileRoot 必须指向现有目录");

        ArrayNode definitions = Json.MAPPER.createArrayNode();
        addDefinition(definitions, "list_files", "列出证据根目录中的文本文件，路径相对于根目录。",
                schema(null, null));
        addDefinition(definitions, "read_file", "读取证据目录内的 UTF-8 文本文件；使用相对路径。",
                schema("path", "要读取的相对路径"));
        addDefinition(definitions, "search_files", "在证据目录的 UTF-8 文本中搜索字面字符串，返回路径、行号和匹配行。",
                schema("query", "要查找的字面字符串（忽略大小写）"));

        Map<String, Command> commands = new LinkedHashMap<>();
        JsonNode configuredCommands = config.path("commands");
        if (!configuredCommands.isMissingNode() && !configuredCommands.isArray()) {
            throw new IOException("commands 必须为数组");
        }
        for (JsonNode command : configuredCommands) {
            Command parsed = parseCommand(command);
            if (Set.of("list_files", "read_file", "search_files").contains(parsed.name())
                    || commands.putIfAbsent(parsed.name(), parsed) != null) {
                throw new IOException("工具名称重复：" + parsed.name());
            }
            addDefinition(definitions, parsed.name(), parsed.description(), parsed.schema());
        }
        return new ToolRegistry(root, timeoutSeconds, maxOutputChars, commands, definitions);
    }

    /** 返回副本，避免调用方修改已校验的工具定义。 */
    public ArrayNode definitions() {
        return definitions.deepCopy();
    }

    public ToolResult execute(String name, JsonNode arguments) {
        try {
            requireObject(arguments, "工具参数");
            return switch (name == null ? "" : name) {
                case "list_files" -> {
                    validate(arguments, schema(null, null), false);
                    yield listFiles();
                }
                case "read_file" -> {
                    validate(arguments, schema("path", "相对路径"), false);
                    yield readFile(arguments.path("path").asText());
                }
                case "search_files" -> {
                    validate(arguments, schema("query", "搜索字符串"), false);
                    yield searchFiles(arguments.path("query").asText());
                }
                default -> {
                    Command command = commands.get(name);
                    if (command == null) yield result(false, "未知工具：" + name, false);
                    validate(arguments, command.schema(), true);
                    yield run(command, arguments);
                }
            };
        } catch (IOException | IllegalArgumentException e) {
            return result(false, "工具执行失败：" + e.getMessage(), false);
        } catch (SecurityException e) {
            return result(false, "工具执行失败：操作系统拒绝访问", false);
        }
    }

    private ToolResult listFiles() throws IOException {
        Scan scan = scan();
        StringBuilder output = new StringBuilder();
        boolean truncated = scan.truncated();
        ReadBudget budget = new ReadBudget();
        for (Path file : scan.files()) {
            try {
                readText(file, budget);
                output.append(relative(file)).append('\n');
                if (output.length() > maxOutputChars) { truncated = true; break; }
            } catch (IOException ignored) {
                // 二进制、无效 UTF-8 或不可读文件不会作为证据列出。
            }
            if (budget.exhausted()) { truncated = true; break; }
        }
        return result(true, output.isEmpty() ? "没有可读取的文本文件。" : output.toString(), truncated);
    }

    private ToolResult readFile(String requested) throws IOException {
        Path file = resolveFile(requested);
        Content content = readText(file);
        return result(true, content.text(), content.truncated());
    }

    private ToolResult searchFiles(String query) throws IOException {
        Scan scan = scan();
        String needle = query.toLowerCase(Locale.ROOT);
        StringBuilder output = new StringBuilder();
        boolean truncated = scan.truncated();
        ReadBudget budget = new ReadBudget();
        outer: for (Path file : scan.files()) {
            try {
                Content content = readText(file, budget);
                truncated |= content.truncated();
                int lineNumber = 0;
                for (String line : content.text().split("\\R", -1)) {
                    lineNumber++;
                    if (line.toLowerCase(Locale.ROOT).contains(needle)) {
                        output.append(relative(file)).append(':').append(lineNumber).append(": ")
                                .append(line).append('\n');
                        if (output.length() > maxOutputChars) { truncated = true; break outer; }
                    }
                }
            } catch (IOException ignored) {
                // 搜索跳过无法作为文本证据读取的文件。
            }
            if (budget.exhausted()) { truncated = true; break; }
        }
        return result(true, output.isEmpty() ? "未找到匹配文本。" : output.toString(), truncated);
    }

    private Scan scan() throws IOException {
        List<Path> files = new ArrayList<>();
        boolean truncated = false;
        try (Stream<Path> paths = Files.walk(fileRoot, MAX_DEPTH)) {
            Iterator<Path> iterator = paths.iterator();
            int seen = 0;
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (++seen > MAX_ENTRIES) { truncated = true; break; }
                if (Files.isSymbolicLink(path)) continue;
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) files.add(path);
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                        && fileRoot.relativize(path).getNameCount() >= MAX_DEPTH) truncated = true;
            }
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
        files.sort(Comparator.comparing(this::relative));
        return new Scan(files, truncated);
    }

    private Path resolveFile(String requested) throws IOException {
        Path relative;
        try { relative = Path.of(requested); }
        catch (java.nio.file.InvalidPathException e) { throw new IOException("文件路径无效"); }
        if (relative.isAbsolute()) throw new IOException("只允许相对路径");
        for (Path component : relative) {
            if (component.toString().equals("..")) throw new IOException("文件路径不能包含 ..");
        }
        Path resolved = fileRoot.resolve(relative).normalize();
        if (!resolved.startsWith(fileRoot)) throw new IOException("文件路径超出证据根目录");
        Path cursor = fileRoot;
        for (Path component : fileRoot.relativize(resolved)) {
            cursor = cursor.resolve(component);
            if (Files.isSymbolicLink(cursor)) throw new IOException("不允许读取符号链接");
        }
        Path real = resolved.toRealPath();
        if (!real.startsWith(fileRoot) || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("只能读取证据根目录内的普通文件");
        }
        return real;
    }

    private Content readText(Path file) throws IOException {
        return readText(file, null);
    }

    private Content readText(Path file, ReadBudget budget) throws IOException {
        // 再次检查实际路径，避免扫描到的文件在读取前被换成目录外的链接。
        Path real = resolveFile(relative(file));
        long fileSize = Files.size(real);
        byte[] bytes;
        try (var stream = Files.newInputStream(real, LinkOption.NOFOLLOW_LINKS)) {
            int limit = budget == null ? MAX_FILE_BYTES + 1 : budget.readLimit();
            bytes = stream.readNBytes(limit);
        }
        // 在解码前计费，二进制与无效 UTF-8 文件同样消耗扫描预算。
        if (budget != null) budget.consume(bytes.length);
        int length = Math.min(bytes.length, MAX_FILE_BYTES);
        boolean truncated = bytes.length > MAX_FILE_BYTES || fileSize > length;
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer characters = CharBuffer.allocate(length);
        var decoded = decoder.decode(ByteBuffer.wrap(bytes, 0, length), characters, !truncated);
        if (decoded.isError()) throw new IOException("文件不是有效的 UTF-8 文本");
        characters.flip();
        String content = characters.toString();
        if (content.chars().anyMatch(c -> c < 32 && c != '\n' && c != '\r' && c != '\t')) {
            throw new IOException("不支持读取二进制文件");
        }
        if (content.startsWith("\uFEFF")) content = content.substring(1);
        return new Content(content, truncated);
    }

    private String relative(Path file) {
        return fileRoot.relativize(file).toString().replace('\\', '/');
    }

    private ToolResult run(Command command, JsonNode arguments) throws IOException {
        List<String> argv = new ArrayList<>();
        for (String template : command.argv()) {
            Matcher matcher = PLACEHOLDER.matcher(template);
            StringBuilder token = new StringBuilder();
            while (matcher.find()) matcher.appendReplacement(token,
                    Matcher.quoteReplacement(arguments.path(matcher.group(1)).asText()));
            matcher.appendTail(token);
            argv.add(token.toString());
        }
        ProcessBuilder builder = new ProcessBuilder(argv).redirectErrorStream(true).directory(fileRoot.toFile());
        // 工具保留集群认证环境，但不继承模型与本服务的密钥。
        builder.environment().keySet().removeIf(key -> {
            String name = key.toUpperCase(Locale.ROOT);
            return name.startsWith("LLM_") || name.startsWith("OPENAI_") || name.startsWith("HOLMES_")
                    || name.startsWith("SERVER_") || MODEL_KEYS.contains(name);
        });
        Process process = builder.start();
        process.getOutputStream().close();
        BoundedOutput output = new BoundedOutput(maxOutputChars);
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var stream = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buffer = new char[2_048];
                int count;
                while ((count = stream.read(buffer)) != -1) output.append(buffer, count);
            } catch (IOException ignored) {
                // 超时清理会主动关闭流；仍保留关闭前已收集到的输出。
            }
        });
        Map<Long, ProcessHandle> descendants = new HashMap<>();
        boolean finished = false;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (System.nanoTime() < deadline) {
                process.descendants().forEach(child -> descendants.put(child.pid(), child));
                if (process.waitFor(50, TimeUnit.MILLISECONDS)) { finished = true; break; }
            }
            if (!finished) terminate(process, descendants);
            reader.join(1_000);
            if (reader.isAlive()) {
                // 已退出的父进程可能留下持有管道的子进程，不能无限等待读取线程。
                output.markTruncated();
                terminate(process, descendants);
                process.getInputStream().close();
                reader.join(500);
            }
            String prefix = !finished ? "命令执行超时（限制 " + timeoutSeconds + " 秒），已终止进程。\n"
                    : process.exitValue() == 0 ? "" : "命令退出码：" + process.exitValue() + "\n";
            return result(finished && process.exitValue() == 0, prefix + output.text(), output.truncated());
        } catch (InterruptedException e) {
            terminate(process, descendants);
            Thread.currentThread().interrupt();
            return result(false, "工具执行被中断", output.truncated());
        } finally {
            if (process.isAlive()) terminate(process, descendants);
            process.getInputStream().close();
        }
    }

    private static void terminate(Process process, Map<Long, ProcessHandle> descendants) {
        process.descendants().forEach(child -> descendants.put(child.pid(), child));
        descendants.values().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            process.waitFor(200, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        descendants.values().stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) process.destroyForcibly();
    }

    private ToolResult result(boolean success, String output, boolean truncated) {
        int end = Math.min(output.length(), maxOutputChars);
        if (end > 0 && end < output.length() && Character.isHighSurrogate(output.charAt(end - 1))) end--;
        return new ToolResult(success, output.substring(0, end), truncated || end < output.length());
    }

    private static ObjectNode schema(String property, String description) {
        ObjectNode schema = Json.MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        if (property != null) {
            ObjectNode value = properties.putObject(property);
            value.put("type", "string").put("description", description).put("minLength", 1)
                    .put("maxLength", property.equals("query") ? 200 : 512);
            required.add(property);
        }
        return schema;
    }

    private static void addDefinition(ArrayNode tools, String name, String description, JsonNode schema) {
        ObjectNode tool = tools.addObject();
        tool.put("type", "function");
        ObjectNode function = tool.putObject("function");
        function.put("name", name).put("description", description).set("parameters", schema);
    }

    private static Command parseCommand(JsonNode source) throws IOException {
        requireObject(source, "命令配置");
        onlyFields(source, Set.of("name", "description", "parameters", "argv"), "命令配置");
        String name = text(source, "name", "命令配置");
        if (!NAME.matcher(name).matches()) throw new IOException("工具名称格式无效");
        String description = text(source, "description", "命令配置");
        JsonNode parameters = source.path("parameters");
        requireObject(parameters, name + " 参数定义");
        onlyFields(parameters, Set.of("type", "properties", "required", "additionalProperties"), name + " 参数定义");
        if (!parameters.path("type").asText().equals("object")) throw new IOException("参数定义 type 必须为 object");
        requireObject(parameters.path("properties"), name + " properties");
        if (parameters.has("additionalProperties") && !parameters.path("additionalProperties").isBoolean()
                || parameters.path("additionalProperties").asBoolean(false)) {
            throw new IOException("additionalProperties 只能为 false");
        }
        ObjectNode normalized = parameters.deepCopy();
        normalized.put("additionalProperties", false);
        if (!normalized.has("required")) normalized.putArray("required");
        if (!normalized.path("required").isArray()) throw new IOException("required 必须为数组");
        Set<String> required = new HashSet<>();
        for (JsonNode field : normalized.path("required")) {
            if (!field.isTextual() || !normalized.path("properties").has(field.asText()) || !required.add(field.asText())) {
                throw new IOException("required 包含未知或重复参数");
            }
        }
        for (var fields = normalized.path("properties").fields(); fields.hasNext();) {
            var entry = fields.next();
            if (!NAME.matcher(entry.getKey()).matches()) throw new IOException("参数名称格式无效");
            JsonNode rule = entry.getValue();
            requireObject(rule, "参数规则");
            onlyFields(rule, Set.of("type", "description", "pattern", "enum", "minLength", "maxLength"), "参数规则");
            if (!rule.path("type").asText().equals("string")) throw new IOException("命令参数仅支持 string");
            if (!rule.has("pattern") && !rule.has("enum")) throw new IOException("命令参数必须配置 pattern 或 enum");
            if (rule.has("pattern")) {
                if (!rule.path("pattern").isTextual()) throw new IOException("pattern 必须为字符串");
                try { Pattern.compile(rule.path("pattern").asText()); }
                catch (PatternSyntaxException e) { throw new IOException("参数正则表达式无效"); }
            }
            if (rule.has("enum")) {
                if (!rule.path("enum").isArray() || rule.path("enum").isEmpty()) throw new IOException("enum 必须是非空字符串数组");
                for (JsonNode value : rule.path("enum")) if (!value.isTextual()) throw new IOException("enum 仅支持字符串");
            }
            int minimum = lengthRule(rule, "minLength", 1);
            int maximum = lengthRule(rule, "maxLength", 512);
            if (minimum < 1 || maximum < minimum || maximum > 1_024) throw new IOException("参数长度范围必须在 1 到 1024 之间");
            ((ObjectNode) rule).put("minLength", minimum).put("maxLength", maximum);
        }
        JsonNode configuredArgv = source.path("argv");
        if (!configuredArgv.isArray() || configuredArgv.isEmpty() || configuredArgv.size() > 64) throw new IOException("argv 必须为 1 到 64 个字符串");
        List<String> argv = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (JsonNode value : configuredArgv) {
            if (!value.isTextual() || value.asText().isBlank() || value.asText().indexOf('\0') >= 0) throw new IOException("argv 元素必须为非空字符串");
            String token = value.asText();
            Matcher matcher = PLACEHOLDER.matcher(token);
            while (matcher.find()) {
                String parameter = matcher.group(1);
                if (argv.isEmpty()) throw new IOException("可执行文件名不能包含参数占位符");
                if (!required.contains(parameter)) throw new IOException("argv 占位符必须是 required 中的参数：" + parameter);
                used.add(parameter);
            }
            if (PLACEHOLDER.matcher(token).replaceAll("").matches(".*[{}].*")) throw new IOException("argv 包含无效占位符");
            argv.add(token);
        }
        if (used.size() != normalized.path("properties").size()) throw new IOException("每个命令参数都必须用于 argv 且为必填参数");
        return new Command(name, description, normalized, List.copyOf(argv));
    }

    private static int lengthRule(JsonNode rule, String name, int fallback) throws IOException {
        if (!rule.has(name)) return fallback;
        if (!rule.path(name).isIntegralNumber() || !rule.path(name).canConvertToInt()) throw new IOException(name + " 必须为整数");
        return rule.path(name).asInt();
    }

    private static void validate(JsonNode arguments, JsonNode schema, boolean command) throws IOException {
        JsonNode properties = schema.path("properties");
        for (var names = arguments.fieldNames(); names.hasNext();) {
            String name = names.next();
            if (!properties.has(name)) throw new IOException("不允许额外参数：" + name);
        }
        for (JsonNode required : schema.path("required")) {
            if (!arguments.has(required.asText())) throw new IOException("缺少必填参数：" + required.asText());
        }
        for (var fields = arguments.fields(); fields.hasNext();) {
            var field = fields.next();
            if (!field.getValue().isTextual()) throw new IOException("参数必须为字符串：" + field.getKey());
            String value = field.getValue().asText();
            JsonNode rule = properties.path(field.getKey());
            if (value.length() < rule.path("minLength").asInt(1) || value.length() > rule.path("maxLength").asInt(512)) {
                throw new IOException("参数长度不符合规则：" + field.getKey());
            }
            if (value.chars().anyMatch(c -> c < 32 || c == 127) || command && value.startsWith("-")) {
                throw new IOException("参数包含不允许的控制字符或选项前缀：" + field.getKey());
            }
            if (rule.has("pattern") && !Pattern.compile(rule.path("pattern").asText()).matcher(value).matches()) {
                throw new IOException("参数格式不符合 pattern：" + field.getKey());
            }
            if (rule.has("enum")) {
                boolean found = false;
                for (JsonNode choice : rule.path("enum")) found |= choice.asText().equals(value);
                if (!found) throw new IOException("参数不在 enum 允许值内：" + field.getKey());
            }
        }
    }

    private static void requireObject(JsonNode value, String label) throws IOException {
        if (value == null || !value.isObject()) throw new IOException(label + "必须为 JSON 对象");
    }

    private static void onlyFields(JsonNode value, Set<String> fields, String label) throws IOException {
        for (var names = value.fieldNames(); names.hasNext();) {
            String name = names.next();
            if (!fields.contains(name)) throw new IOException(label + "包含不支持的字段：" + name);
        }
    }

    private static String text(JsonNode object, String field, String label) throws IOException {
        if (!object.path(field).isTextual() || object.path(field).asText().isBlank()) {
            throw new IOException(label + "中的 " + field + " 必须为非空字符串");
        }
        return object.path(field).asText();
    }

    private record Command(String name, String description, ObjectNode schema, List<String> argv) { }
    private record Scan(List<Path> files, boolean truncated) { }
    private record Content(String text, boolean truncated) { }

    private static final class ReadBudget {
        private long remaining = MAX_SEARCH_BYTES;
        private int readLimit() { return (int) Math.min(MAX_FILE_BYTES + 1L, remaining); }
        private void consume(int bytes) { remaining -= bytes; }
        private boolean exhausted() { return remaining <= 0; }
    }

    private static final class BoundedOutput {
        private final int limit;
        private final StringBuilder text = new StringBuilder();
        private boolean truncated;

        private BoundedOutput(int limit) { this.limit = limit; }
        private synchronized void append(char[] characters, int count) {
            int accepted = Math.min(count, limit - text.length());
            text.append(characters, 0, accepted);
            truncated |= accepted < count;
        }
        private synchronized String text() { return text.toString(); }
        private synchronized boolean truncated() { return truncated; }
        private synchronized void markTruncated() { truncated = true; }
    }
}
