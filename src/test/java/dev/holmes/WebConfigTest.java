package dev.holmes;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WebConfigTest {
    @Test void defaultsBindToLoopbackAndAllowOptionalPassword() {
        var config = WebConfig.from(Map.of());
        assertEquals("127.0.0.1", config.host());
        assertEquals(8080, config.port());
        assertFalse(config.secureCookie());
        assertEquals("", config.password());
        assertEquals("", WebConfig.from(Map.of("HOLMES_WEB_PASSWORD", "")).password());
        assertFalse(WebConfig.from(Map.of("HOLMES_WEB_PASSWORD", "synthetic-web-password")).toString().contains("synthetic-web-password"));
    }

    @Test void explicitDeploymentSettingsAndQuotedPasswordWhitespaceArePreserved() {
        var config = WebConfig.from(Map.of("HOLMES_BIND_HOST", "0.0.0.0", "HOLMES_PORT", "9090",
                "HOLMES_WEB_PASSWORD", "  synthetic-password  ", "HOLMES_WEB_SECURE_COOKIE", "true"));
        assertEquals("0.0.0.0", config.host());
        assertEquals(9090, config.port());
        assertEquals("  synthetic-password  ", config.password());
        assertTrue(config.secureCookie());
    }

    @Test void invalidSettingsNeverEchoPassword() {
        for (String password : new String[]{"too-short", " ".repeat(20), "x".repeat(257), "synthetic-password\n"}) {
            var error = assertThrows(IllegalArgumentException.class, () -> WebConfig.from(Map.of("HOLMES_WEB_PASSWORD", password)));
            assertFalse(error.toString().contains(password));
        }
        for (var extra : java.util.List.of(Map.of("HOLMES_BIND_HOST", "http://example.com"), Map.of("HOLMES_PORT", "65536"),
                Map.of("HOLMES_PORT", "invalid"), Map.of("HOLMES_WEB_SECURE_COOKIE", "yes"))) {
            var values = new java.util.HashMap<>(extra); values.put("HOLMES_WEB_PASSWORD", "synthetic-web-password");
            assertThrows(IllegalArgumentException.class, () -> WebConfig.from(values));
        }
    }
}
