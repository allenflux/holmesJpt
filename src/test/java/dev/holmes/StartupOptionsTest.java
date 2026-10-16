package dev.holmes;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StartupOptionsTest {
    @Test void allModesAcceptPrefixOrSuffixAndPathsContainingSpaces() {
        String file = "/tmp/synthetic config/selected.env";
        for (String mode : List.of("web", "ask", "logs", "tools", "demo", "serve")) {
            var before = StartupOptions.parse(new String[]{"--env-file", file, mode});
            var after = StartupOptions.parse(new String[]{mode, "--env-file", file});
            assertEquals(List.of(mode), before.arguments());
            assertEquals(before, after);
            assertTrue(after.explicitEnvFile());
            assertEquals(Path.of(file), after.envFile());
        }
    }

    @Test void knownOptionValuesAndQuestionTextAreNotScannedAsGlobalFlags() {
        for (String flag : List.of("--tools", "--runbooks", "--max-steps", "--port", "--host",
                "--tasks", "--log-file", "--data-dir", "--request", "--output")) {
            var parsed = StartupOptions.parse(new String[]{"web", flag, "--env-file", "--env-file", "selected.env"});
            assertEquals(List.of("web", flag, "--env-file"), parsed.arguments());
            assertEquals(Path.of("selected.env"), parsed.envFile());
        }
        String question = "为什么 --env-file config.env 没有生效？";
        var normal = StartupOptions.parse(new String[]{"ask", question, "--env-file", "selected.env"});
        assertEquals(List.of("ask", question), normal.arguments());
        var literal = StartupOptions.parse(new String[]{"--env-file", "selected.env", "ask", "--", "--env-file"});
        assertEquals(List.of("ask", "--", "--env-file"), literal.arguments());
        assertEquals(Path.of("selected.env"), literal.envFile());
        assertFalse(StartupOptions.parse(new String[]{"web", "--tasks", "--help"}).helpRequested());
    }

    @Test void defaultSelectionAndRepeatedExplicitSelectionAreDeterministic() {
        var defaults = StartupOptions.parse(new String[]{"tools"});
        assertEquals(Path.of(".env"), defaults.envFile());
        assertFalse(defaults.explicitEnvFile());
        var repeated = StartupOptions.parse(new String[]{"--env-file", "first.env", "web", "--env-file", "last.env"});
        assertEquals(Path.of("last.env"), repeated.envFile());
        assertEquals(List.of("web"), repeated.arguments());
    }

    @Test void invalidOrMissingPathsAreReportedWithoutEchoingTheirValue() {
        for (String[] input : List.of(new String[]{"web", "--env-file"}, new String[]{"--env-file", ""},
                new String[]{"--env-file", "  "}, new String[]{"web", "--env-file", "--port", "8080"},
                new String[]{"--env-file", "private-secret\u0000.env", "web"})) {
            var error = assertThrows(IllegalArgumentException.class, () -> StartupOptions.parse(input));
            assertTrue(error.getMessage().contains("--env-file"));
            assertFalse(error.toString().contains("private-secret"));
            assertNull(error.getCause());
        }
    }
}
