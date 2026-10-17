package dev.holmes.logs;

import dev.holmes.Json;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LogNarrativesTest {
    @Test void missingKeyKeepsRuleModeAvailable() {
        assertNull(LogNarratives.from(Map.of(), false));
        assertNull(LogNarratives.from(Map.of("LLM_API_KEY", " "), false));
    }

    @Test void factoryDoesNotRequireToolsOrFixedWebPort() {
        var generator = LogNarratives.from(Map.of("LLM_API_KEY", "synthetic-test-key",
                "LLM_BASE_URL", "https://model.example/v1", "LLM_MODEL", "test-model",
                "HOLMES_TOOLS_FILE", "does-not-exist.json", "HOLMES_PORT", "0"), false);
        assertEquals("test-model", generator.model());
        assertEquals("https://model.example/v1", generator.destination());
        assertTrue(generator.toolDriven());
    }

    @Test void refusesCredentialBearingModelDestination() {
        assertThrows(IllegalArgumentException.class, () -> LogNarratives.from(Map.of(
                "LLM_API_KEY", "synthetic-test-key", "LLM_BASE_URL", "https://user:secret@model.example/v1"), false));
    }

    @Test void demoUsesOnlyActualEvidenceReferencesWithoutNetwork() throws Exception {
        var report = Json.MAPPER.readTree("""
                {"retrieved_event_count":1,"tasks":[{"task_ref":"ref-a","association_scope":"TASK_ONLY",
                "signature":"INCOMPLETE","evidence_ids":["ev-demo"],"collection_status":"COLLECTED"}],
                "evidence":[{"evidence_id":"ev-demo","task_ref":"ref-a","event":"execution_error",
                "failure_signature":"HTTP_404","error_summary":"HTTP 404"}]}
                """);
        var generator = LogNarratives.from(Map.of(), true);
        var narrative = generator.generate(report);
        assertEquals("demo-log-narrative", narrative.path("model").asText());
        assertEquals("ev-demo", narrative.path("findings").get(0).path("evidence_ids").get(0).asText());
        assertTrue(narrative.path("limitations").toString().contains("没有调用外部模型"));
        assertEquals("TOOL_DRIVEN", narrative.path("analysis").path("mode").asText());
        assertTrue(narrative.path("analysis").path("tool_calls").asInt() > 0);
    }
}
