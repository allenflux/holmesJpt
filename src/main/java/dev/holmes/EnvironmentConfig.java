package dev.holmes;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** 从启动目录的 .env 读取配置，再由系统环境覆盖；不执行 shell 或展开变量。 */
public final class EnvironmentConfig {
    private static final int MAX_BYTES = 64 * 1024;
    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private EnvironmentConfig() { }

    public static Map<String, String> load() throws IOException {
        return load(Path.of(".env"), System.getenv());
    }

    public static Map<String, String> load(Path envFile, Map<String, String> systemEnv) throws IOException {
        return load(envFile, systemEnv, false);
    }

    /** 显式 --env-file 选择的文件必须存在；不能退回当前目录的 .env。 */
    public static Map<String, String> loadRequired(Path envFile, Map<String, String> systemEnv) throws IOException {
        return load(envFile, systemEnv, true);
    }

    private static Map<String, String> load(Path envFile, Map<String, String> systemEnv, boolean required) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(envFile)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        } catch (NoSuchFileException e) {
            if (required) throw new IOException("--env-file 指定的配置文件不存在");
            return Map.copyOf(systemEnv);
        } catch (IOException e) {
            throw new IOException(required ? "无法读取 --env-file 指定的配置文件" : "无法读取 .env 文件");
        }
        if (bytes.length > MAX_BYTES) throw new IOException(".env 文件超过 64 KiB");
        String contents;
        try {
            contents = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException(".env 文件必须使用有效 UTF-8 编码");
        }
        if (contents.startsWith("\uFEFF")) contents = contents.substring(1);
        Map<String, String> values = new HashMap<>();
        String[] lines = contents.split("\\r\\n|\\n|\\r", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].stripLeading();
            if (line.isBlank() || line.startsWith("#")) continue;
            if (line.startsWith("export") && line.length() > 6 && Character.isWhitespace(line.charAt(6))) {
                line = line.substring(6).stripLeading();
            }
            int equals = line.indexOf('=');
            if (equals < 1) throw syntax(i + 1);
            String key = line.substring(0, equals).strip();
            if (!KEY.matcher(key).matches()) throw syntax(i + 1);
            values.put(key, value(line.substring(equals + 1), i + 1));
        }
        // 包括空串在内的显式系统环境值都优先，例如用空 Token 选择匿名查询。
        values.putAll(systemEnv);
        return Map.copyOf(values);
    }

    private static String value(String original, int lineNumber) throws IOException {
        String raw = original.stripLeading();
        if (raw.isEmpty()) return "";
        char quote = raw.charAt(0);
        if (quote != '\'' && quote != '"') {
            for (int i = 1; i < original.length(); i++) {
                if (original.charAt(i) == '#' && Character.isWhitespace(original.charAt(i - 1))) {
                    return original.substring(0, i).strip();
                }
            }
            return original.strip();
        }
        StringBuilder value = new StringBuilder();
        for (int i = 1; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == quote) {
                String tail = raw.substring(i + 1).stripLeading();
                if (!tail.isEmpty() && !tail.startsWith("#")) throw syntax(lineNumber);
                return value.toString();
            }
            if (quote == '"' && c == '\\' && i + 1 < raw.length()) {
                char escaped = raw.charAt(++i);
                switch (escaped) {
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case '"' -> value.append('"');
                    case '\\' -> value.append('\\');
                    default -> value.append('\\').append(escaped);
                }
            } else {
                value.append(c);
            }
        }
        throw syntax(lineNumber);
    }

    private static IOException syntax(int lineNumber) {
        // 不回显原始行或解析异常，避免 Key 出现在终端与日志中。
        return new IOException(".env 第 " + lineNumber + " 行格式无效，请使用 KEY=value 或单行引号值");
    }
}
