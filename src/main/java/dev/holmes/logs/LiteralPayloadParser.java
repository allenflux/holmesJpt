package dev.holmes.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;

/** 只读取 JSON / Python 字面量的有限子集；任何函数、属性访问和表达式都不能执行。 */
public final class LiteralPayloadParser {
    static final int MAX_LENGTH = 65_536;
    private static final int MAX_DEPTH = 24;
    private static final int MAX_VALUES = 4_096;
    private static final int MAX_KEYS = 1_024;
    private final String input;
    private int position;
    private int values;
    private int keys;

    private LiteralPayloadParser(String input) { this.input = input; }

    public static JsonNode parse(String input) {
        if (input == null || input.length() > MAX_LENGTH) throw invalid();
        LiteralPayloadParser parser = new LiteralPayloadParser(input);
        JsonNode result = parser.value(0);
        parser.space();
        if (parser.position != input.length()) throw invalid();
        return result;
    }

    private JsonNode value(int depth) {
        if (depth > MAX_DEPTH || ++values > MAX_VALUES) throw invalid();
        space();
        if (position >= input.length()) throw invalid();
        char next = input.charAt(position);
        if (next == '{') return object(depth + 1);
        if (next == '[') return array(depth + 1);
        if (next == '\'' || next == '"') return JsonNodeFactory.instance.textNode(string());
        if (next == '-' || next >= '0' && next <= '9') return number();
        if (literal("true") || literal("True")) return JsonNodeFactory.instance.booleanNode(true);
        if (literal("false") || literal("False")) return JsonNodeFactory.instance.booleanNode(false);
        if (literal("null") || literal("None")) return JsonNodeFactory.instance.nullNode();
        throw invalid();
    }

    private ObjectNode object(int depth) {
        position++;
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        space();
        if (take('}')) return result;
        while (true) {
            space();
            if (++keys > MAX_KEYS || position >= input.length()
                    || input.charAt(position) != '\'' && input.charAt(position) != '"') throw invalid();
            String key = string();
            // 重复键不可悄悄覆盖 ID 或异常码。
            if (result.has(key)) throw invalid();
            space();
            require(':');
            result.set(key, value(depth));
            space();
            if (take('}')) return result;
            require(',');
            space();
            if (take('}')) return result;
        }
    }

    private ArrayNode array(int depth) {
        position++;
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        space();
        if (take(']')) return result;
        while (true) {
            result.add(value(depth));
            space();
            if (take(']')) return result;
            require(',');
            space();
            if (take(']')) return result;
        }
    }

    private String string() {
        char quote = input.charAt(position++);
        StringBuilder result = new StringBuilder();
        while (position < input.length()) {
            char next = input.charAt(position++);
            if (next == quote) return result.toString();
            if (next < 0x20) throw invalid();
            if (next != '\\') { result.append(next); continue; }
            if (position == input.length()) throw invalid();
            char escaped = input.charAt(position++);
            switch (escaped) {
                case '\\', '\'', '"', '/' -> result.append(escaped);
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'u' -> result.append(unicode(4));
                case 'x' -> result.append(unicode(2));
                default -> throw invalid();
            }
        }
        throw invalid();
    }

    private char unicode(int length) {
        if (position + length > input.length()) throw invalid();
        int result = 0;
        for (int i = 0; i < length; i++) {
            int digit = Character.digit(input.charAt(position++), 16);
            if (digit < 0) throw invalid();
            result = result * 16 + digit;
        }
        return (char) result;
    }

    private JsonNode number() {
        int start = position;
        take('-');
        if (!take('0')) digits();
        if (take('.')) digits();
        if (take('e') || take('E')) {
            if (!take('+')) take('-');
            digits();
        }
        String value = input.substring(start, position);
        // 防止超大指数在后续序列化中产生不受限的内存开销。
        if (value.length() > 128) throw invalid();
        try {
            BigDecimal number = new BigDecimal(value);
            if (Math.abs((long) number.scale()) > 1_000) throw invalid();
            return JsonNodeFactory.instance.numberNode(number);
        } catch (NumberFormatException exception) { throw invalid(); }
    }

    private void digits() {
        int start = position;
        while (position < input.length() && input.charAt(position) >= '0' && input.charAt(position) <= '9') position++;
        if (start == position) throw invalid();
    }

    private boolean literal(String literal) {
        if (!input.startsWith(literal, position)) return false;
        int end = position + literal.length();
        if (end < input.length() && Character.isJavaIdentifierPart(input.charAt(end))) return false;
        position = end;
        return true;
    }

    private void space() {
        while (position < input.length() && Character.isWhitespace(input.charAt(position))) position++;
    }
    private boolean take(char expected) {
        if (position < input.length() && input.charAt(position) == expected) { position++; return true; }
        return false;
    }
    private void require(char expected) { if (!take(expected)) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("日志 payload 不是受支持的有界字面量"); }
}
