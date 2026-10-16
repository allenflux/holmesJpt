package dev.holmes;

import dev.holmes.logs.LogConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WebStatusTest {
    @TempDir Path directory;

    @Test void missingSnapshotKeepsWebUsableWithoutPublishingSecretsOrPaths() {
        var config = config(null, false);
        var status = new WebStatus(config, false, false).get();
        assertFalse(status.path("snapshot").path("ready").asBoolean());
        assertFalse(status.path("capabilities").path("ask").asBoolean());
        assertTrue(status.path("capabilities").path("logs").asBoolean());
        assertEquals("grafana", status.path("data_mode").asText());
        assertEquals(2, status.path("namespaces").size());
        assertEquals("dev", status.path("namespaces").get(0).asText());
        assertFalse(status.toString().contains("private-test-token"));
        assertFalse(status.toString().contains("private-grafana.example"));
        assertFalse(status.toString().contains(directory.toString()));
    }

    @Test void syntheticFixtureGivesFixedWindowAndCorrectFailedTaskCount() {
        var config = config(Path.of("examples/logs/tasks.json").toAbsolutePath(), true);
        var status = new WebStatus(config, true, true).get();
        assertTrue(status.path("snapshot").path("ready").asBoolean());
        assertEquals(6, status.path("snapshot").path("failed_count").asInt());
        assertEquals("prod", status.path("snapshot").path("namespace").asText());
        assertEquals(6, status.path("defaults").path("requested_count").asInt());
        assertTrue(status.path("capabilities").path("ask").asBoolean());
        Instant from = Instant.parse(status.path("defaults").path("from").asText());
        Instant to = Instant.parse(status.path("defaults").path("to").asText());
        assertEquals(Duration.ofHours(1), Duration.between(from, to));
        assertEquals(Instant.parse("2026-10-15T06:00:00.001Z"), to);
        assertEquals("file", status.path("data_mode").asText());
    }

    @Test void invalidAttemptMappingDoesNotDisplaySnapshotAsReady() throws Exception {
        var source = Json.MAPPER.readTree(Path.of("examples/logs/tasks.json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) source.path("tasks").get(0))
                .put("final_attempt_id", "invalid-private-test-token");
        Path file = directory.resolve("private-snapshot.json");
        Files.writeString(file, source.toString());
        var status = new WebStatus(config(file, false), false, false).get();
        assertFalse(status.path("snapshot").path("ready").asBoolean());
        assertTrue(status.path("snapshot").path("error").isTextual());
        assertFalse(status.toString().contains("private-test-token"));
        assertFalse(status.toString().contains(file.toString()));
    }

    @Test void snapshotReadinessReflectsFileReplacementWithoutRestart() throws Exception {
        Path file = directory.resolve("tasks.json");
        var status = new WebStatus(config(file, true), false, false);
        assertFalse(status.get().path("snapshot").path("ready").asBoolean());
        Files.copy(Path.of("examples/logs/tasks.json"), file);
        assertTrue(status.get().path("snapshot").path("ready").asBoolean());
        Files.writeString(file, "{\"secret\":\"private-test-token\"}");
        assertFalse(status.get().path("snapshot").path("ready").asBoolean());
        assertFalse(status.get().toString().contains("private-test-token"));
    }

    private LogConfig config(Path tasks, boolean offline) {
        Map<String, String> env = new HashMap<>(Map.of(
                "HOLMES_LOG_NAMESPACES", "prod,dev",
                "HOLMES_LOG_DATA_DIR", directory.toString(),
                "HOLMES_GRAFANA_URL", "https://private-grafana.example",
                "HOLMES_GRAFANA_TOKEN", "private-test-token",
                "HOLMES_FLUXDROP_TOKEN", "private-test-token"));
        if (tasks != null) env.put("HOLMES_LOG_TASKS_FILE", tasks.toString());
        if (offline) env.put("HOLMES_LOG_FILE", Path.of("examples/logs/events.jsonl").toAbsolutePath().toString());
        return LogConfig.from(env);
    }
}
