package dev.holmes;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {
    @Test void defaultsUseRequestedProviderAndModel() {
        var config = AppConfig.from(Map.of());
        assertEquals("https://llm.aiaipool.com/v1", config.baseUrl().toString());
        assertEquals("deepseek-v41-flash", config.model());
        assertEquals("", config.apiKey());
        assertEquals(8, config.maxSteps());
        assertEquals(Duration.ofSeconds(90), config.llmTimeout());
    }

    @Test void environmentOverridesAreTrimmedAndSecretIsRedacted() {
        var config = AppConfig.from(Map.of("LLM_API_KEY", " test-secret ", "LLM_MODEL", " model-name ",
                "LLM_TIMEOUT_SECONDS", "45", "HOLMES_MAX_STEPS", "12", "HOLMES_PORT", "9090"));
        assertEquals("test-secret", config.apiKey());
        assertEquals("model-name", config.model());
        assertEquals(Duration.ofSeconds(45), config.llmTimeout());
        assertEquals(12, config.maxSteps());
        assertEquals(9090, config.port());
        assertFalse(config.toString().contains("test-secret"));
    }

    @Test void baseUrlRejectsCredentialsQueryFragmentAndRemotePlaintext() {
        for (String url : new String[]{"https://user:password@example.com/v1", "https://example.com/v1?key=secret",
                "https://example.com/v1#secret", "http://example.com/v1", "file:///tmp/example"}) {
            assertThrows(IllegalArgumentException.class, () -> AppConfig.from(Map.of("LLM_BASE_URL", url)), url);
        }
        assertDoesNotThrow(() -> AppConfig.from(Map.of("LLM_BASE_URL", "http://localhost:8081/v1")));
        assertDoesNotThrow(() -> AppConfig.from(Map.of("LLM_BASE_URL", "http://127.0.0.1:8081/v1")));
    }

    @Test void invalidBudgetsAreRejectedInsteadOfSilentlyFallingBack() {
        for (Map<String, String> values : List.of(Map.of("HOLMES_MAX_STEPS", "1"),
                Map.of("HOLMES_MAX_TOOL_CALLS", "0"), Map.of("HOLMES_MAX_CONTEXT_CHARS", "4095"),
                Map.of("HOLMES_PORT", "65536"), Map.of("LLM_TIMEOUT_SECONDS", "not-a-number"))) {
            assertThrows(IllegalArgumentException.class, () -> AppConfig.from(values));
        }
    }
}
