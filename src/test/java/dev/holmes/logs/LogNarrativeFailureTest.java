package dev.holmes.logs;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.holmes.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LogNarrativeFailureTest {
    @Test void acceptsOnlyFixedCodesAndInfersSafeStage() {
        for (String code : List.of("NARRATIVE_INPUT_INVALID", "NARRATIVE_INPUT_BUDGET", "NARRATIVE_PROVIDER_HTTP_100", "NARRATIVE_PROVIDER_HTTP_599",
                "NARRATIVE_PROVIDER_TIMEOUT", "NARRATIVE_PROVIDER_NETWORK", "NARRATIVE_PROVIDER_RESPONSE", "NARRATIVE_TOOL_PROTOCOL",
                "NARRATIVE_TOOL_NO_EVIDENCE", "NARRATIVE_TOOL_CONTEXT_LIMIT", "NARRATIVE_TOOL_BUDGET", "NARRATIVE_OUTPUT_JSON",
                "NARRATIVE_OUTPUT_SCHEMA", "NARRATIVE_OUTPUT_CITATION", "NARRATIVE_OUTPUT_INCOMPLETE", "NARRATIVE_GENERATION_FAILED")) {
            LogNarrativeFailure failure = new LogNarrativeFailure(code);
            assertEquals(code, failure.code()); assertNull(failure.getCause()); assertEquals(code, failure.diagnostic().path("code").asText());
        }
        assertEquals("INPUT", new LogNarrativeFailure("NARRATIVE_INPUT_INVALID").diagnostic().path("stage").asText());
        assertEquals("PROVIDER", new LogNarrativeFailure("NARRATIVE_PROVIDER_HTTP_429").diagnostic().path("stage").asText());
        assertEquals("TOOLS", new LogNarrativeFailure("NARRATIVE_TOOL_PROTOCOL").diagnostic().path("stage").asText());
        assertEquals("OUTPUT", new LogNarrativeFailure("NARRATIVE_OUTPUT_JSON").diagnostic().path("stage").asText());
        assertEquals("UNKNOWN", new LogNarrativeFailure("NARRATIVE_GENERATION_FAILED").diagnostic().path("stage").asText());
        for (String invalid : List.of("NARRATIVE_PROVIDER_HTTP_099", "NARRATIVE_PROVIDER_HTTP_600", "NARRATIVE_PROVIDER_HTTP_429 sensitive-secret", "sensitive-secret", "")) {
            LogNarrativeFailure failure = new LogNarrativeFailure(invalid);
            assertEquals("NARRATIVE_GENERATION_FAILED", failure.code()); assertFalse(failure.getMessage().contains("sensitive-secret"));
        }
    }

    @Test void diagnosticDropsUntrustedFieldsAndBoundsEveryTraceDimension() {
        ArrayNode steps = Json.MAPPER.createArrayNode();
        for (int i = 0; i < 10; i++) steps.addObject().put("tool", i == 0 ? "read_evidence" : "unsafe-tool-sensitive-secret")
                .put("status", i == 0 ? "OK" : "unsafe-status-sensitive-secret").put("evidence_count", 1_000)
                .put("arguments", "sensitive-secret").put("result", "sensitive-secret");
        LogNarrativeFailure failure = new LogNarrativeFailure("NARRATIVE_TOOL_PROTOCOL", 99, 99, steps);

        ObjectNode diagnostic = failure.diagnostic();

        assertEquals(4, diagnostic.path("model_rounds").asInt()); assertEquals(6, diagnostic.path("tool_calls").asInt());
        assertEquals(6, diagnostic.path("steps").size()); assertEquals(3, diagnostic.path("steps").get(0).size());
        assertEquals("UNKNOWN", diagnostic.path("steps").get(1).path("tool").asText());
        assertEquals("REJECTED", diagnostic.path("steps").get(1).path("status").asText());
        assertEquals(128, diagnostic.path("steps").get(0).path("evidence_count").asInt());
        assertFalse(diagnostic.toString().contains("sensitive-secret")); assertNull(failure.getCause());
        assertThrows(IllegalStateException.class, () -> failure.initCause(new RuntimeException("sensitive-secret")));
        diagnostic.put("code", "mutated"); steps.removeAll();
        assertEquals("NARRATIVE_TOOL_PROTOCOL", failure.diagnostic().path("code").asText()); assertEquals(6, failure.diagnostic().path("steps").size());
    }

    @Test void distinguishesUnknownCountersFromConfirmedNoCalls() {
        ObjectNode unknown = new LogNarrativeFailure("NARRATIVE_PROVIDER_NETWORK").diagnostic();
        assertTrue(unknown.path("model_rounds").isNull()); assertTrue(unknown.path("tool_calls").isNull());
        ObjectNode known = new LogNarrativeFailure("NARRATIVE_INPUT_INVALID", 0, 0, null).diagnostic();
        assertEquals(0, known.path("model_rounds").asInt()); assertEquals(0, known.path("tool_calls").asInt());
    }
}
