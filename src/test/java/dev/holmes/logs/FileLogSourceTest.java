package dev.holmes.logs;

import dev.holmes.Json;
import dev.holmes.logs.LogModels.LogQuery;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FileLogSourceTest {
    private static final Instant FROM = Instant.parse("2026-10-15T00:00:00Z");
    @TempDir Path directory;

    @Test void filtersNamespaceTimeCandidatesAcrossAllPodsAndRetainsDuplicates() throws Exception {
        Path file = directory.resolve("logs.jsonl");
        String first = log(FROM, "task-1 error", "prod", "pod-a", Map.of());
        Files.writeString(file, first + "\n" + first + "\n"
                + log(FROM.plusSeconds(1), "structured ID", "prod", "pod-b", Map.of("prompt_id", "prompt-1", "event_id", "real-id")) + "\n"
                + log(FROM.plusSeconds(1), "task-1 wrong namespace", "other", "pod-b", Map.of()) + "\n"
                + log(FROM.plusSeconds(10), "task-1 upper boundary", "prod", "pod-b", Map.of()) + "\n"
                + log(FROM, "unrelated", "prod", "pod-b", Map.of()));

        var fetched = new FileLogSource(file).fetch(query(List.of("task-1", "prompt-1"), Map.of()), 100, 100_000, Instant.now().plusSeconds(5));

        assertTrue(fetched.complete(), fetched.limitations().toString());
        assertEquals(3, fetched.events().size());
        assertEquals(List.of("pod-a", "pod-a", "pod-b"), fetched.events().stream().map(event -> event.labels().get("pod")).toList());
        assertEquals("", fetched.events().getFirst().eventId());
        assertEquals("real-id", fetched.events().getLast().eventId());
        assertEquals(Files.size(file), fetched.responseBytes());
    }

    @Test void contextUsesExactPodAndContainerWithoutRequiringIdentifier() throws Exception {
        Path file = directory.resolve("logs.jsonl");
        Files.writeString(file, log(FROM, "stacktrace", "prod", "pod-a", Map.of("container", "worker")) + "\n"
                + log(FROM, "wrong container", "prod", "pod-a", Map.of("container", "sidecar")) + "\n"
                + log(FROM, "wrong pod", "prod", "pod-b", Map.of("container", "worker")));

        var fetched = new FileLogSource(file).fetch(query(List.of(), Map.of("pod", "pod-a", "container", "worker")),
                100, 100_000, Instant.now().plusSeconds(5));

        assertTrue(fetched.complete());
        assertEquals(List.of("stacktrace"), fetched.events().stream().map(event -> event.message()).toList());
    }

    @Test void boundedBytesRecordsAndDeadlineAreExplicitlyPartial() throws Exception {
        Path file = directory.resolve("logs.jsonl");
        Files.writeString(file, log(FROM, "task-1 error", "prod", "pod-a", Map.of()) + "\n"
                + log(FROM, "task-1 second", "prod", "pod-b", Map.of()));
        var source = new FileLogSource(file);

        var bytes = source.fetch(query(List.of("task-1"), Map.of()), 100, 20, Instant.now().plusSeconds(5));
        assertFalse(bytes.complete());
        assertEquals(20, bytes.responseBytes());
        assertTrue(bytes.events().isEmpty());
        var records = source.fetch(query(List.of("task-1"), Map.of()), 1, 100_000, Instant.now().plusSeconds(5));
        assertFalse(records.complete());
        assertEquals(1, records.events().size());
        var deadline = source.fetch(query(List.of("task-1"), Map.of()), 100, 100_000, Instant.now().minusMillis(1));
        assertFalse(deadline.complete());
        assertEquals(0, deadline.responseBytes());
    }

    @Test void malformedRowsAreReportedWithoutLeakingAndOtherRowsRemainUsable() throws Exception {
        Path file = directory.resolve("logs.jsonl");
        Files.writeString(file, "private-secret invalid-json\n"
                + log(FROM, "task-1 error", "prod", "pod-a", Map.of()));

        var fetched = new FileLogSource(file).fetch(query(List.of("task-1"), Map.of()), 100, 100_000, Instant.now().plusSeconds(5));

        assertFalse(fetched.complete());
        assertEquals(1, fetched.events().size());
        assertTrue(fetched.limitations().toString().contains("第 1 行"));
        assertFalse(fetched.limitations().toString().contains("private-secret"));
    }

    @Test void oversizedUtf8LineIsPartialAndFollowingSmallLineIsRetained() throws Exception {
        Path file = directory.resolve("logs.jsonl");
        String large = log(FROM, "task-1 " + "界".repeat(90_000), "prod", "pod-a", Map.of());
        assertTrue(large.length() < 256 * 1024);
        assertTrue(large.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 256 * 1024);
        Files.writeString(file, large + "\n" + log(FROM.plusMillis(1), "task-1 small row", "prod", "pod-a", Map.of()) + "\n");

        var fetched = new FileLogSource(file).fetch(query(List.of("task-1"), Map.of()), 100, 1_000_000, Instant.now().plusSeconds(5));

        assertFalse(fetched.complete());
        assertEquals(List.of("task-1 small row"), fetched.events().stream().map(LogModels.LogEvent::message).toList());
        assertTrue(fetched.limitations().toString().contains("256 KiB"));
    }

    private static LogQuery query(List<String> ids, Map<String, String> filters) {
        return new LogQuery("prod", ids, FROM, FROM.plusSeconds(10), filters);
    }

    private static String log(Instant time, String message, String namespace, String pod, Map<String, String> extra) {
        var object = Json.MAPPER.createObjectNode().put("_time", time.toString()).put("_msg", message)
                .put("kubernetes.pod_namespace", namespace).put("kubernetes.pod_name", pod);
        extra.forEach(object::put);
        return object.toString();
    }
}
