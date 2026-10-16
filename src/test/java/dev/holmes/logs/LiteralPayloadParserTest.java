package dev.holmes.logs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LiteralPayloadParserTest {
    @Test void readsJsonAndPythonLiteralsWithoutChangingQuotes() {
        var json = LiteralPayloadParser.parse("{\"task_id\":\"a\",\"ok\":true,\"n\":null}");
        assertEquals("a", json.path("task_id").asText());
        var python = LiteralPayloadParser.parse("{'task_id': 'a', 'data': {'original_data': {'prompt_id': 'a'}}, 'message': \"can't replace 'quotes'\", 'enabled': True, 'disabled': False, 'missing': None, 'list': [1, -2.5,],}");
        assertEquals("can't replace 'quotes'", python.path("message").asText());
        assertTrue(python.path("enabled").asBoolean());
        assertFalse(python.path("disabled").asBoolean());
        assertTrue(python.path("missing").isNull());
        assertEquals("a", python.path("data").path("original_data").path("prompt_id").asText());
        assertEquals(-2.5, python.path("list").get(1).asDouble());
    }

    @Test void readsEscapedQuotesBracesAndUnicode() {
        var result = LiteralPayloadParser.parse("{'message': 'it\\'s {safe}', 'unicode': '\\u4f60\\u597d', 'hex': '\\x41'}");
        assertEquals("it's {safe}", result.path("message").asText());
        assertEquals("你好", result.path("unicode").asText());
        assertEquals("A", result.path("hex").asText());
    }

    @Test void rejectsExecutablePayloadsAndDuplicateKeys() {
        for (String payload : new String[]{
                "{'task_id': __import__('os').system('touch /tmp/holmes-payload-executed')}",
                "{'task_id': str('a')}", "{'task_id': (lambda: 'a')()}",
                "{'task_id': 'a', 'task_id': 'b'}", "{'x': 1 + 2}", "{'x': object.attr}",
                "{'x': set([1])}", "{unquoted: 'a'}", "{}; __import__('os')", "{'x': TrueValue}"}) {
            assertThrows(IllegalArgumentException.class, () -> LiteralPayloadParser.parse(payload), payload);
        }
    }

    @Test void boundsLengthDepthKeysAndNumericExpansion() {
        assertThrows(IllegalArgumentException.class, () -> LiteralPayloadParser.parse(" ".repeat(65_537)));
        assertThrows(IllegalArgumentException.class, () -> LiteralPayloadParser.parse("[".repeat(26) + "0" + "]".repeat(26)));
        StringBuilder keys = new StringBuilder("{");
        for (int i = 0; i < 1_025; i++) keys.append("'k").append(i).append("':0,");
        keys.append("}");
        assertThrows(IllegalArgumentException.class, () -> LiteralPayloadParser.parse(keys.toString()));
        assertThrows(IllegalArgumentException.class, () -> LiteralPayloadParser.parse("1e999999999"));
        assertThrows(IllegalArgumentException.class, () -> LiteralPayloadParser.parse("01"));
        assertThrows(IllegalArgumentException.class, () -> LiteralPayloadParser.parse("NaN"));
    }
}
