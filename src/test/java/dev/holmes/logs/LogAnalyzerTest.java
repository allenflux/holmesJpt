package dev.holmes.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.holmes.logs.LogModels.Attempt;
import dev.holmes.logs.LogModels.AssociationScope;
import dev.holmes.logs.LogModels.LogEvent;
import dev.holmes.logs.LogModels.Task;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

class LogAnalyzerTest {
    private static final Instant START = Instant.parse("2026-10-15T05:00:00Z");
    private final LogAnalyzer analyzer = new LogAnalyzer();

    @Test void sixTaskFixtureCountsOneFinalFailurePerUniqueTask() {
        Task a = new Task("ref-a", "task-a", "failed", List.of(
                new Attempt("a-1", "a-old", START.minusSeconds(600), START.minusSeconds(500), "old-instance"),
                new Attempt("a-2", "task-a", START, START.plusSeconds(60), "gpu-a")), "a-2", null, START.plusSeconds(60), "v1");
        List<Task> tasks = new ArrayList<>(List.of(a, task("b"), task("c"), task("d"), task("e"), task("f")));
        tasks.add(a);
        LogEvent duplicate = event(10, "{\"prompt_id\":\"task-b\",\"error_message\":\"CUDA out of memory\"}", "comfy-b", "source-b");
        List<LogEvent> events = List.of(
                event(-550, "{\"prompt_id\":\"a-old\",\"error_message\":\"request timed out\"}", "old-pod", "source-old"),
                event(15, "{\"task_id\":\"task-a\",\"event\":\"task_failed\",\"error_message\":\"CUDA out of memory\"}", "scheduler-1", "source-a"),
                duplicate, duplicate,
                event(12, "Scheduler Monitor payload: {'task_id': 'task-c', 'data': {'original_data': {'prompt_id': 'task-c'}}, 'event':'execution_error', 'error_message':'request timed out', 'ok':False, 'optional':None}", "scheduler-2", "source-c"),
                event(13, "{\"prompt_id\":\"task-d\",\"level\":\"ERROR\",\"message\":\"HTTP 403 forbidden\"}", "worker-d", "source-d"),
                new LogEvent(START.plusSeconds(13), "{\"prompt_id\":\"task-d\",\"error_message\":\"HTTP 404\"}", labels("worker-other", "staging", "0"), "other-scope"),
                event(14, "{\"prompt_id\":\"task-d-similar\",\"error_message\":\"HTTP 404\"}", "worker-d", "similar-id"),
                event(16, "CUDA out of memory unrelated concurrent work", "worker-e", "no-id"),
                event(17, "{\"prompt_id\":\"task-f\",\"error_message\":\"CUDA out of memory\"}", "worker-f", "partial"));
        Map<String, Boolean> complete = new HashMap<>();
        tasks.forEach(task -> complete.put(task.taskRef(), !task.taskId().equals("task-f")));
        ObjectNode report = analyzer.analyze(tasks, events, "prod", complete, List.of("task-f query truncated"));
        assertEquals(6, report.path("selected_count").asInt());
        assertEquals(Map.of("CUDA_OOM", 2, "TIMEOUT", 1, "HTTP_403", 1, "UNKNOWN", 1, "INCOMPLETE", 1), counts(report));
        assertEquals(6, counts(report).values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(2d / 6, find(report.path("clusters"), "signature", "CUDA_OOM").path("ratio").asDouble());
        assertEquals("CUDA_OOM", resultTask(report, "task-a").path("signature").asText());
        assertTrue(StreamSupport.stream(resultTask(report, "task-a").path("timeline").spliterator(), false)
                .anyMatch(value -> value.path("historical_attempt").asBoolean()));
        assertEquals(1, resultTask(report, "task-b").path("timeline").size());
        assertTrue(hasFlag(resultTask(report, "task-d"), "CROSS_NAMESPACE_REJECTED"));
    }

    @Test void matchesCompleteFieldsAndIgnoresRunningListsOrArbitraryNestedIds() {
        Task task = task("a");
        List<LogEvent> events = List.of(
                event(5, "{\"prompt_id\":\"task-a-suffix\",\"error_message\":\"CUDA out of memory\"}", "same-pod", "1"),
                event(6, "{\"metrics\":{\"running_task_ids\":[\"task-a\"]},\"error_message\":\"CUDA out of memory\"}", "same-pod", "2"),
                event(7, "{\"unrelated\":{\"task_id\":\"task-a\"},\"error_message\":\"HTTP 403\"}", "same-pod", "3"),
                event(8, "task_id=task-a RuntimeError: unauthenticated free text", "same-pod", "4"));
        ObjectNode report = analyzer.analyze(List.of(task), events, "prod", true, List.of());
        assertEquals("UNKNOWN", resultTask(report, "task-a").path("signature").asText());
        assertEquals(0, report.path("evidence").size());
    }

    @Test void refusesConflictingIdsAndDoesNotMergeTwoTasks() {
        ObjectNode report = analyzer.analyze(List.of(task("a"), task("b")), List.of(
                event(5, "payload: {'task_id':'task-a','data':{'original_data':{'prompt_id':'task-b'}},'error_message':'CUDA out of memory'}", "scheduler", "x")), "prod", true, List.of());
        assertEquals(Map.of("AMBIGUOUS", 2), counts(report));
        assertEquals(0, report.path("evidence").size());
        assertTrue(hasFlag(report, "ID_CONFLICT"));
    }

    @Test void acceptsExplicitRetryPromptMappingAcrossPods() {
        Task task = new Task("ref-a", "task-a", "failed", List.of(
                new Attempt("try-1", "prompt-first", START.minusSeconds(600), START.minusSeconds(500), "instance-1"),
                new Attempt("try-2", "prompt-last", START, START.plusSeconds(60), "instance-2")), "try-2", null, START.plusSeconds(60), "1");
        ObjectNode report = analyzer.analyze(List.of(task), List.of(
                event(-550, "{\"prompt_id\":\"prompt-first\",\"error_message\":\"task timeout\"}", "gpu-old", "old"),
                event(5, "{\"task_id\":\"task-a\",\"data\":{\"original_data\":{\"prompt_id\":\"prompt-last\"}},\"error_message\":\"CUDA out of memory\"}", "scheduler", "new"),
                event(8, "{\"prompt_id\":\"prompt-last\",\"event\":\"callback_failed\"}", "gpu-new", "callback")), "prod", true, List.of());
        assertEquals(Map.of("CUDA_OOM", 1), counts(report));
        assertEquals(3, report.path("evidence").size());
        assertTrue(StreamSupport.stream(report.path("evidence").spliterator(), false)
                .anyMatch(value -> value.path("association_basis").asText().equals("VERIFIED_TASK_ATTEMPT_PROMPT_MAPPING")));
    }

    @Test void refusesOverlappingAttemptsUnlessExplicitAttemptOrInstanceResolvesThem() {
        Task task = new Task("ref-a", "task-a", "failed", List.of(
                new Attempt("try-1", "task-a", START, START.plusSeconds(60), "instance-1"),
                new Attempt("try-2", "task-a", START, START.plusSeconds(60), "instance-2")), "try-2", null, START.plusSeconds(60), "1");
        var ambiguous = analyzer.analyze(List.of(task), List.of(event(5, "{\"task_id\":\"task-a\",\"error_message\":\"CUDA out of memory\"}", "pod", "1")), "prod", true, List.of());
        assertEquals("AMBIGUOUS", resultTask(ambiguous, "task-a").path("signature").asText());
        assertTrue(hasFlag(ambiguous, "AMBIGUOUS_ATTEMPT"));
        var exact = analyzer.analyze(List.of(task), List.of(event(5, "{\"task_id\":\"task-a\",\"attempt_id\":\"try-2\",\"error_message\":\"CUDA out of memory\"}", "pod", "2")), "prod", true, List.of());
        assertEquals("CUDA_OOM", resultTask(exact, "task-a").path("signature").asText());
        var instance = analyzer.analyze(List.of(task), List.of(event(5, "{\"task_id\":\"task-a\",\"instance_id\":\"instance-2\",\"error_message\":\"CUDA out of memory\"}", "pod", "3")), "prod", true, List.of());
        assertEquals("CUDA_OOM", resultTask(instance, "task-a").path("signature").asText());
    }

    @Test void allowsTwoMinuteClockMarginAndRejectsOutOfWindowAndWrongInstances() {
        var margin = analyzer.analyze(List.of(task("a")), List.of(event(-119, "{\"task_id\":\"task-a\",\"error_message\":\"CUDA out of memory\"}", "pod", "1")), "prod", true, List.of());
        assertEquals("CUDA_OOM", resultTask(margin, "task-a").path("signature").asText());
        assertTrue(hasFlag(margin, "CLOCK_SKEW_MARGIN"));
        var outside = analyzer.analyze(List.of(task("a")), List.of(
                event(-121, "{\"task_id\":\"task-a\",\"error_message\":\"CUDA out of memory\"}", "pod", "2"),
                event(5, "{\"task_id\":\"task-a\",\"instance_id\":\"other\",\"error_message\":\"HTTP 403\"}", "pod", "3")), "prod", true, List.of());
        assertEquals(0, outside.path("evidence").size());
        assertTrue(hasFlag(outside, "OUTSIDE_ATTEMPT_WINDOW"));
        assertTrue(hasFlag(outside, "INSTANCE_MISMATCH"));
    }

    @Test void parsesQuotedPythonPayloadAndRejectsExecutableOne() {
        var quoted = analyzer.analyze(List.of(task("a")), List.of(event(5,
                "Scheduler payload: {'task_id': 'task-a', 'data': {'original_data': {'prompt_id': 'task-a'}}, 'message': \"can't change 'quotes' or {braces}\", 'enabled': True, 'empty': None, 'error_message': 'CUDA out of memory'} after payload",
                "scheduler", "1")), "prod", true, List.of());
        assertEquals(Map.of("CUDA_OOM", 1), counts(quoted));
        Map<String, String> labels = new HashMap<>(labels("pod", "prod", "0"));
        labels.put("task_id", "task-a");
        var malicious = analyzer.analyze(List.of(task("a")), List.of(new LogEvent(START.plusSeconds(5),
                "payload: {'task_id': 'task-a', 'message': __import__('os').system('touch /tmp/holmes-payload-executed')}", labels, "2")), "prod", true, List.of());
        assertEquals(0, malicious.path("evidence").size());
        assertEquals(Map.of("INCOMPLETE", 1), counts(malicious));
        assertTrue(hasFlag(malicious, "PARSE_ERROR"));
    }

    @Test void preservesRealRepeatedMessagesIncludingUnidentifiableSameMillisecondDuplicates() {
        String message = "{\"task_id\":\"task-a\",\"error_message\":\"HTTP 403\"}";
        var report = analyzer.analyze(List.of(task("a")), List.of(
                event(5, message, "pod", ""), event(6, message, "pod", ""), event(6, message, "pod", "")), "prod", true, List.of());
        assertEquals(Map.of("HTTP_403", 1), counts(report));
        assertEquals(3, resultTask(report, "task-a").path("timeline").size());
        assertEquals(3, report.path("evidence").size());
        assertEquals(3, StreamSupport.stream(report.path("evidence").spliterator(), false)
                .map(value -> value.path("evidence_id").asText()).distinct().count());
        assertTrue(hasFlag(report, "DUPLICATE_AMBIGUITY"));
    }

    @Test void doesNotMerge403And404OrDistinctStructuredExceptions() {
        var report = analyzer.analyze(List.of(task("a"), task("b"), task("c"), task("d")), List.of(
                event(5, "{\"task_id\":\"task-a\",\"status_code\":403}", "pod", "1"),
                event(5, "{\"task_id\":\"task-b\",\"status_code\":404}", "pod", "2"),
                event(5, "{\"task_id\":\"task-c\",\"exception_type\":\"ValueError\",\"exception_message\":\"missing model alpha\"}", "pod", "3"),
                event(5, "{\"task_id\":\"task-d\",\"exception_type\":\"ValueError\",\"exception_message\":\"missing model beta\"}", "pod", "4")), "prod", true, List.of());
        assertEquals("HTTP_403", resultTask(report, "task-a").path("signature").asText());
        assertEquals("HTTP_404", resultTask(report, "task-b").path("signature").asText());
        assertTrue(resultTask(report, "task-c").path("signature").asText().startsWith("EXCEPTION:ValueError:"));
        assertNotEquals(resultTask(report, "task-c").path("signature"), resultTask(report, "task-d").path("signature"));
    }

    @Test void multipleFatalSignaturesRemainAmbiguous() {
        var report = analyzer.analyze(List.of(task("a")), List.of(
                event(5, "{\"task_id\":\"task-a\",\"error_message\":\"CUDA out of memory\"}", "gpu", "1"),
                event(6, "{\"task_id\":\"task-a\",\"error_message\":\"request timed out\"}", "scheduler", "2")), "prod", true, List.of());
        assertEquals(Map.of("AMBIGUOUS", 1), counts(report));
        assertTrue(hasFlag(report, "MULTIPLE_FAILURE_SIGNATURES"));
    }

    @Test void incompleteInputCanUseAuthorityReasonButCannotInferFromPartialLog() {
        Task base = task("a");
        Task authoritative = new Task(base.taskRef(), base.taskId(), base.status(), base.attempts(), base.finalAttemptId(), "CUDA_OOM", base.failedAt(), "v1");
        var report = analyzer.analyze(List.of(authoritative, task("b")), List.of(event(5, "{\"task_id\":\"task-b\",\"error_message\":\"CUDA out of memory\"}", "gpu", "1")), "prod", false, List.of("truncated"));
        assertEquals(Map.of("CUDA_OOM", 1, "INCOMPLETE", 1), counts(report));
        assertTrue(hasFlag(resultTask(report, "task-a"), "SIGNATURE_FROM_TASK_FAILURE_REASON"));
        assertTrue(hasFlag(resultTask(report, "task-a"), "INCOMPLETE_INPUT"));
        assertFalse(report.path("signature_is_root_cause").asBoolean());
    }

    @Test void joinsOnlyRecognizableStackContinuationFromSameCompleteStream() {
        String head = "{\"task_id\":\"task-a\",\"event\":\"execution_error\"}";
        var joined = analyzer.analyze(List.of(task("a")), List.of(
                event(5, head, "pod", "1"),
                event(6, "Traceback (most recent call last):", "pod", "2"),
                event(6, "  File \"worker.py\", line 33, in run", "pod", "3"),
                event(7, "RuntimeError: model alpha failed", "pod", "4")), "prod", true, List.of());
        assertTrue(resultTask(joined, "task-a").path("signature").asText().startsWith("EXCEPTION:RuntimeError:"));
        assertEquals(1, joined.path("evidence").size());
        assertTrue(joined.path("evidence").get(0).path("excerpt").asText().contains("RuntimeError"));
        var otherRestart = analyzer.analyze(List.of(task("a")), List.of(
                event(5, head, "pod", "1"),
                new LogEvent(START.plusSeconds(6), "RuntimeError: model alpha failed", labels("pod", "prod", "1"), "2")), "prod", true, List.of());
        assertEquals(Map.of("UNKNOWN", 1), counts(otherRestart));
        assertFalse(otherRestart.path("evidence").get(0).path("excerpt").asText().contains("RuntimeError"));
        Map<String, String> missing = new HashMap<>(labels("pod", "prod", "0"));
        missing.remove("restart");
        var missingRestart = analyzer.analyze(List.of(task("a")), List.of(
                new LogEvent(START.plusSeconds(5), head, missing, "1"),
                new LogEvent(START.plusSeconds(6), "RuntimeError: model alpha failed", missing, "2")), "prod", true, List.of());
        assertEquals(Map.of("UNKNOWN", 1), counts(missingRestart));
        assertTrue(hasFlag(missingRestart, "MISSING_RESTART"));
    }

    @Test void redactsBeforeHashingAndBoundsEvidenceAndTimeline() throws Exception {
        List<LogEvent> events = new ArrayList<>();
        String secret = "http://user:password@example.test/path?token=topsecret&ok=1 Authorization: Bearer abcdef123456 api_key=key-secret";
        for (int i = 0; i < 30; i++) events.add(event(i + 1, "{\"task_id\":\"task-a\",\"error_message\":\"CUDA out of memory " + secret + " " + "x".repeat(2_000) + "\"}", "gpu", "id-" + i));
        var report = analyzer.analyze(List.of(task("a")), events, "prod", true, List.of());
        assertEquals(8, report.path("evidence").size());
        assertEquals(20, resultTask(report, "task-a").path("timeline").size());
        String serialized = report.toString();
        assertFalse(serialized.contains("topsecret"));
        assertFalse(serialized.contains("abcdef123456"));
        assertFalse(serialized.contains("key-secret"));
        assertFalse(serialized.contains("user:password"));
        for (JsonNode evidence : report.path("evidence")) {
            String saved = evidence.path("excerpt").asText();
            assertTrue(saved.length() <= 1_800);
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(saved.getBytes(StandardCharsets.UTF_8))), evidence.path("content_sha256").asText());
        }
        assertTrue(hasFlag(report, "TIMELINE_TRUNCATED"));
        assertTrue(hasFlag(report, "EVIDENCE_EXCERPT_TRUNCATED"));
    }

    @Test void orderingAndStableIdsMakeReportDeterministic() {
        List<Task> tasks = List.of(task("b"), task("a"));
        List<LogEvent> events = List.of(event(2, "{\"task_id\":\"task-b\",\"error_message\":\"HTTP 403\"}", "pod-b", "2"),
                event(1, "{\"task_id\":\"task-a\",\"error_message\":\"CUDA out of memory\"}", "pod-a", "1"));
        ObjectNode left = analyzer.analyze(tasks, events, "prod", true, List.of());
        ObjectNode right = analyzer.analyze(tasks.reversed(), events.reversed(), "prod", true, List.of());
        assertEquals(left, right);
    }

    @Test void workflowPromptsAndRunningMetricsCannotFabricateFailureSignatures() {
        var report = analyzer.analyze(List.of(task("a")), List.of(event(5,
                "{\"task_id\":\"task-a\",\"event\":\"task_started\",\"data\":{\"original_data\":{\"prompt_id\":\"task-a\",\"message\":\"CUDA out of memory HTTP 403 RuntimeError: fake failure\"},\"metrics\":{\"error\":\"CUDA out of memory\"}}}",
                "scheduler", "1")), "prod", true, List.of());
        assertEquals(Map.of("UNKNOWN", 1), counts(report));
        assertEquals(1, report.path("evidence").size());
    }

    @Test void structuredExceptionFingerprintsIgnoreJsonWrappingAndDynamicIds() {
        var report = analyzer.analyze(List.of(task("a"), task("b")), List.of(
                event(5, "{\"task_id\":\"task-a\",\"exception_type\":\"ValueError\",\"exception_message\":\"model alpha rejected task-a at line 123\"}", "pod", "1"),
                event(6, "payload: {'task_id':'task-b','data':{'exception_type':'ValueError','exception_message':'model alpha rejected task-b at line 456'}}", "pod", "2")), "prod", true, List.of());
        assertEquals(resultTask(report, "task-a").path("signature"), resultTask(report, "task-b").path("signature"));
        assertEquals(1, report.path("clusters").size());
    }

    @Test void conflictingStableEventIdsAreVisibleRatherThanDiscarded() {
        var report = analyzer.analyze(List.of(task("a")), List.of(
                event(5, "{\"task_id\":\"task-a\",\"error_message\":\"CUDA out of memory\"}", "pod", "same-id"),
                event(6, "{\"task_id\":\"task-a\",\"error_message\":\"HTTP 403\"}", "pod", "same-id")), "prod", true, List.of());
        assertEquals(Map.of("AMBIGUOUS", 1), counts(report));
        assertEquals(2, report.path("evidence").size());
        assertTrue(hasFlag(report, "SOURCE_EVENT_ID_CONFLICT"));
    }

    @Test void noIdExceptionCannotAttachToOrdinaryTaskStart() {
        var report = analyzer.analyze(List.of(task("a")), List.of(
                event(5, "{\"task_id\":\"task-a\",\"event\":\"task_started\"}", "pod", "1"),
                event(6, "RuntimeError: concurrent other task failed", "pod", "2")), "prod", true, List.of());
        assertEquals(Map.of("UNKNOWN", 1), counts(report));
        assertEquals(1, resultTask(report, "task-a").path("timeline").size());
    }

    @Test void readsFlattenedStructuredFieldsFromDatasourceLabels() {
        Map<String, String> labels = new HashMap<>(labels("pod", "prod", "0"));
        labels.put("task_id", "task-a");
        labels.put("event", "execution_error");
        labels.put("exception_type", "ValueError");
        labels.put("error_message", "model alpha missing");
        var report = analyzer.analyze(List.of(task("a")), List.of(new LogEvent(START.plusSeconds(5), "worker failed", labels, "1")), "prod", true, List.of());
        assertTrue(resultTask(report, "task-a").path("signature").asText().startsWith("EXCEPTION:ValueError:"));
        assertEquals("execution_error", resultTask(report, "task-a").path("timeline").get(0).path("event").asText());
        assertTrue(report.path("evidence").get(0).path("excerpt").asText().contains("ValueError"));
        assertTrue(report.path("evidence").get(0).path("excerpt").asText().contains("model alpha missing"));
    }

    @Test void readsTypedErrorObject() {
        var report = analyzer.analyze(List.of(task("a")), List.of(event(5,
                "{\"task_id\":\"task-a\",\"error\":{\"type\":\"ValueError\",\"message\":\"model alpha missing\"}}", "pod", "1")), "prod", true, List.of());
        assertTrue(resultTask(report, "task-a").path("signature").asText().startsWith("EXCEPTION:ValueError:"));
    }

    @Test void redactsSignedUrlsCredentialsAndWholeCookieHeadersWhileKeepingBusinessIds() {
        String original = "https://user:password@example.test/log?task_id=task-a&X-Amz-Signature=aws-signature"
                + "&X-Amz-Credential=aws-credential&X-Amz-Security-Token=aws-session&prompt_id=task-a"
                + "&client_secret=client-value&credential=credential-value&token=token-value&node_id=node-7\n"
                + "Cookie: session=cookie-one; preferences=cookie-two; account=cookie-three\n"
                + "Set-Cookie: access=cookie-four; domain=private-domain; path=/\n"
                + "credential=credential-plain private_key=key-plain clientSecret=client-plain\n"
                + "https://username-only@example.test/resource?task_id=task-a";
        String safe = LogAnalyzer.redact(original);
        for (String secret : List.of("user:password", "username-only", "aws-signature", "aws-credential", "aws-session",
                "client-value", "credential-value", "token-value", "cookie-one", "cookie-two", "cookie-three", "cookie-four",
                "private-domain", "credential-plain", "key-plain", "client-plain")) assertFalse(safe.contains(secret), secret);
        assertTrue(safe.contains("task_id=task-a"));
        assertTrue(safe.contains("prompt_id=task-a"));
        assertTrue(safe.contains("node_id=node-7"));
        assertTrue(safe.contains("X-Amz-Signature="));
        assertTrue(safe.contains("Cookie: [REDACTED]"));
    }

    @Test void redactsQuotedEscapedSecretsAndPrivateKeyBlocks() {
        String original = "{\"client_secret\":\"secret with \\\"quoted\\\" content\",\"credential\":'single \\'quoted\\' credential',"
                + "\"Cookie\":\"session=cookie-alpha; other=cookie-beta\",\"task_id\":\"task-a\"}\n"
                + "-----BEGIN RSA PRIVATE KEY-----\nprivate-key-material\n-----END RSA PRIVATE KEY-----\n"
                + "prompt_id=task-a";
        String safe = LogAnalyzer.redact(original);
        for (String secret : List.of("secret with", "quoted", "credential'", "cookie-alpha", "cookie-beta", "private-key-material"))
            assertFalse(safe.contains(secret), secret);
        assertTrue(safe.contains("task-a"));
        assertTrue(safe.contains("prompt_id=task-a"));
        assertTrue(safe.contains("[REDACTED PRIVATE KEY]"));
    }

    @Test void reportSignedUrlRedactionHappensAfterExactTaskAssociation() {
        String message = "{\"task_id\":\"task-a\",\"error_message\":\"HTTP 403 https://example.test/log?token=private-token&task_id=task-a&X-Amz-Signature=private-signature&prompt_id=task-a\"}";
        var report = analyzer.analyze(List.of(task("a")), List.of(event(5, message, "pod", "1")), "prod", true, List.of());
        assertEquals(Map.of("HTTP_403", 1), counts(report));
        assertEquals(1, report.path("evidence").size());
        assertFalse(report.toString().contains("private-token"));
        assertFalse(report.toString().contains("private-signature"));
        assertTrue(report.path("evidence").get(0).path("excerpt").asText().contains("task_id=task-a"));
        assertTrue(report.path("evidence").get(0).path("excerpt").asText().contains("prompt_id=task-a"));
    }

    @Test void explicitTaskOnlyScopeRetainsExactEvidenceWithoutInventingFinalAttempt() {
        var report = analyzer.analyze(List.of(taskOnly("a")), List.of(
                event(5, "{\"task_id\":\"task-a\",\"error_message\":\"CUDA out of memory\"}", "gpu", "1"),
                event(6, "{\"task_id\":\"task-a\",\"error_message\":\"request timed out\"}", "scheduler", "2")), "prod", true, List.of());
        JsonNode task = resultTask(report, "task-a");
        assertEquals(Map.of("INCOMPLETE", 1), counts(report));
        assertEquals("TASK_ONLY", task.path("association_scope").asText());
        assertEquals(List.of("CUDA_OOM", "TIMEOUT"), StreamSupport.stream(task.path("observed_signatures").spliterator(), false).map(JsonNode::asText).toList());
        assertEquals(2, report.path("evidence").size());
        assertEquals(2, task.path("timeline").size());
        assertTrue(hasFlag(task, "TASK_ATTEMPT_UNVERIFIED"));
        assertTrue(hasFlag(task, "EXECUTION_WINDOW_UNVERIFIED"));
        assertTrue(hasFlag(task, "TASK_FAILURE_FINALITY_UNVERIFIED"));
        assertFalse(hasFlag(task, "OUTSIDE_ATTEMPT_WINDOW"));
        assertFalse(report.path("signature_is_root_cause").asBoolean());
        for (JsonNode evidence : report.path("evidence")) {
            assertTrue(evidence.path("attempt_id").isNull());
            assertTrue(evidence.path("historical_attempt").isNull());
            assertTrue(evidence.path("candidate_only").asBoolean());
            assertEquals("TASK_ID_ONLY_ATTEMPT_UNVERIFIED", evidence.path("association_basis").asText());
            assertTrue(hasFlag(evidence, "RETRY_MEMBERSHIP_UNVERIFIED"));
        }
        for (JsonNode timeline : task.path("timeline")) assertTrue(timeline.path("attempt_id").isNull());
    }

    @Test void taskOnlyAcceptsExactLabelIdAndSameUuidPromptWithoutAssumingOtherMappings() {
        Map<String, String> labels = new HashMap<>(labels("pod", "prod", "0"));
        labels.put("task_id", "task-a");
        var labelReport = analyzer.analyze(List.of(taskOnly("a")), List.of(new LogEvent(START.plusSeconds(5), "CUDA out of memory", labels, "1")), "prod", true, List.of());
        assertEquals("CUDA_OOM", resultTask(labelReport, "task-a").path("observed_signatures").get(0).asText());
        var sameUuid = analyzer.analyze(List.of(taskOnly("a")), List.of(event(5,
                "{\"prompt_id\":\"task-a\",\"error_message\":\"HTTP 403\"}", "pod", "1")), "prod", true, List.of());
        assertEquals(1, sameUuid.path("evidence").size());
        assertEquals("HTTP_403", resultTask(sameUuid, "task-a").path("observed_signatures").get(0).asText());
        var unknownPrompt = analyzer.analyze(List.of(taskOnly("a")), List.of(event(5,
                "{\"task_id\":\"task-a\",\"prompt_id\":\"unverified-retry-prompt\",\"error_message\":\"CUDA out of memory\"}", "pod", "1")), "prod", true, List.of());
        assertEquals(1, unknownPrompt.path("evidence").size());
        assertTrue(hasFlag(unknownPrompt, "UNVERIFIED_PROMPT_ID"));
        assertEquals("unverified-retry-prompt", unknownPrompt.path("evidence").get(0).path("observed_prompt_id").asText());
        assertEquals("CUDA_OOM", resultTask(unknownPrompt, "task-a").path("observed_signatures").get(0).asText());
        assertEquals("INCOMPLETE", resultTask(unknownPrompt, "task-a").path("signature").asText());
    }

    @Test void taskOnlyStillRejectsSubstringOtherNamespaceAndUnidentifiedPodNoise() {
        var report = analyzer.analyze(List.of(taskOnly("a")), List.of(
                event(5, "{\"task_id\":\"task-a-suffix\",\"error_message\":\"CUDA out of memory\"}", "pod", "1"),
                new LogEvent(START.plusSeconds(6), "{\"task_id\":\"task-a\",\"error_message\":\"HTTP 403\"}", labels("pod", "other", "0"), "2"),
                event(7, "CUDA out of memory", "pod", "3"),
                event(8, "{\"metrics\":{\"running_task_ids\":[\"task-a\"]},\"error_message\":\"HTTP 404\"}", "pod", "4")), "prod", true, List.of());
        assertEquals(0, report.path("evidence").size());
        assertEquals(0, resultTask(report, "task-a").path("observed_signatures").size());
        assertTrue(hasFlag(report, "CROSS_NAMESPACE_REJECTED"));
    }

    @Test void taskOnlyDoesNotMergeConflictingTaskAndPromptIdsAcrossTasks() {
        var report = analyzer.analyze(List.of(taskOnly("a"), taskOnly("b")), List.of(event(5,
                "payload: {'task_id':'task-a','data':{'original_data':{'prompt_id':'task-b'}},'error_message':'CUDA out of memory'}", "pod", "1")), "prod", true, List.of());
        assertEquals(Map.of("INCOMPLETE", 2), counts(report));
        assertEquals(0, report.path("evidence").size());
        assertTrue(hasFlag(resultTask(report, "task-a"), "ID_CONFLICT"));
        assertTrue(hasFlag(resultTask(report, "task-b"), "ID_CONFLICT"));
    }

    @Test void taskOnlyKeepsObservedAttemptAndInstanceAsUnverifiedMetadata() {
        var report = analyzer.analyze(List.of(taskOnly("a")), List.of(event(5,
                "{\"task_id\":\"task-a\",\"attempt_id\":\"observed-retry\",\"instance_id\":\"observed-instance\",\"error_message\":\"HTTP 404\"}", "pod", "1")), "prod", true, List.of());
        JsonNode evidence = report.path("evidence").get(0);
        assertTrue(evidence.path("attempt_id").isNull());
        assertEquals("observed-retry", evidence.path("observed_attempt_id").asText());
        assertEquals("observed-instance", evidence.path("observed_instance_id").asText());
        assertEquals("TASK_ID_ONLY_ATTEMPT_UNVERIFIED", evidence.path("association_basis").asText());
        assertFalse(hasFlag(report, "INSTANCE_MISMATCH"));
        assertEquals("INCOMPLETE", resultTask(report, "task-a").path("signature").asText());
    }

    @Test void missingLegacyAttemptNeverImplicitlyEnablesTaskOnlyFallback() {
        Task legacy = new Task("ref-a", "task-a", "failed", List.of(), "", "", START.plusSeconds(60), "legacy");
        assertEquals(AssociationScope.VERIFIED_ATTEMPTS, legacy.associationScope());
        var report = analyzer.analyze(List.of(legacy), List.of(event(5,
                "{\"task_id\":\"task-a\",\"error_message\":\"CUDA out of memory\"}", "pod", "1")), "prod", true, List.of());
        assertEquals(Map.of("INCOMPLETE", 1), counts(report));
        assertEquals(0, report.path("evidence").size());
        assertFalse(resultTask(report, "task-a").has("observed_signatures"));
        assertFalse(hasFlag(report, "TASK_ATTEMPT_UNVERIFIED"));
    }

    @Test void taskOnlyCannotTurnUnverifiedFailureReasonIntoFinalFailureSignature() {
        Task task = new Task("ref-a", "task-a", "failed", List.of(), "", "CUDA_OOM", null, "record-7-updated-observed", AssociationScope.TASK_ONLY);
        var report = analyzer.analyze(List.of(task), List.of(), "prod", true, List.of());
        assertEquals(Map.of("INCOMPLETE", 1), counts(report));
        assertEquals(0, resultTask(report, "task-a").path("observed_signatures").size());
        assertFalse(hasFlag(report, "SIGNATURE_FROM_TASK_FAILURE_REASON"));
        assertNull(task.failedAt());
        assertNull(task.finalAttempt());
    }

    @Test void taskOnlyDoesNotTreatPartialAttemptMetadataAsVerifiedPromptMapping() {
        Task task = new Task("ref-a", "task-a", "failed", List.of(new Attempt("unverified", "other-prompt", null, null, "")),
                "", "", null, "record-observation", AssociationScope.TASK_ONLY);
        var report = analyzer.analyze(List.of(task), List.of(event(5,
                "{\"task_id\":\"task-a\",\"prompt_id\":\"other-prompt\",\"error_message\":\"CUDA out of memory\"}", "pod", "1")), "prod", true, List.of());
        assertEquals(1, report.path("evidence").size());
        assertTrue(hasFlag(report, "UNVERIFIED_PROMPT_ID"));
        assertTrue(report.path("evidence").get(0).path("attempt_id").isNull());
        assertEquals("other-prompt", report.path("evidence").get(0).path("observed_prompt_id").asText());
        assertEquals("TASK_ID_ONLY_ATTEMPT_UNVERIFIED", report.path("evidence").get(0).path("association_basis").asText());
    }

    @Test void exactTaskOnlyIdKeepsUnknownPromptAsCandidateMetadataWithoutVerifiedMapping() {
        var report = analyzer.analyze(List.of(taskOnly("a")), List.of(event(5,
                "payload: {'task_id':'task-a','data':{'original_data':{'prompt_id':'new-comfy-prompt'}},'error_message':'HTTP 403'}", "pod", "1")), "prod", true, List.of());
        JsonNode evidence = report.path("evidence").get(0);
        assertEquals("new-comfy-prompt", evidence.path("observed_prompt_id").asText());
        assertTrue(evidence.path("candidate_only").asBoolean());
        assertTrue(evidence.path("attempt_id").isNull());
        assertTrue(hasFlag(evidence, "UNVERIFIED_PROMPT_ID"));
        assertEquals("INCOMPLETE", resultTask(report, "task-a").path("signature").asText());
        assertEquals("HTTP_403", resultTask(report, "task-a").path("observed_signatures").get(0).asText());
    }

    @Test void taskOnlyStillRejectsPromptMappedToAnotherVerifiedLogicalTask() {
        Task verified = new Task("ref-b", "task-b", "failed", List.of(new Attempt("try-b", "known-b-prompt", START, START.plusSeconds(60), "gpu-b")),
                "try-b", "", START.plusSeconds(60), "v1");
        var report = analyzer.analyze(List.of(taskOnly("a"), verified), List.of(event(5,
                "{\"task_id\":\"task-a\",\"prompt_id\":\"known-b-prompt\",\"error_message\":\"CUDA out of memory\"}", "pod", "1")), "prod", true, List.of());
        assertEquals(0, report.path("evidence").size());
        assertTrue(hasFlag(resultTask(report, "task-a"), "ID_CONFLICT"));
        assertTrue(hasFlag(resultTask(report, "task-b"), "ID_CONFLICT"));
    }

    @Test void verifiedAttemptScopeStillRejectsUnknownPromptBesideExactTaskId() {
        var report = analyzer.analyze(List.of(task("a")), List.of(event(5,
                "{\"task_id\":\"task-a\",\"prompt_id\":\"new-comfy-prompt\",\"error_message\":\"CUDA out of memory\"}", "pod", "1")), "prod", true, List.of());
        assertEquals(0, report.path("evidence").size());
        assertTrue(hasFlag(report, "ID_CONFLICT"));
        assertFalse(hasFlag(report, "UNVERIFIED_PROMPT_ID"));
    }

    @Test void taskOnlyUnknownPromptAloneCannotAssignEvidenceAndConflictingPromptFieldsAreRejected() {
        var noTaskId = analyzer.analyze(List.of(taskOnly("a")), List.of(event(5,
                "{\"prompt_id\":\"unmapped-prompt\",\"error_message\":\"CUDA out of memory\"}", "pod", "1")), "prod", true, List.of());
        assertEquals(0, noTaskId.path("evidence").size());
        var conflicting = analyzer.analyze(List.of(taskOnly("a")), List.of(event(5,
                "{\"task_id\":\"task-a\",\"prompt_id\":\"first-prompt\",\"data\":{\"original_data\":{\"prompt_id\":\"second-prompt\"}},\"error_message\":\"CUDA out of memory\"}", "pod", "2")), "prod", true, List.of());
        assertEquals(0, conflicting.path("evidence").size());
        assertTrue(hasFlag(conflicting, "ID_CONFLICT"));
    }

    @Test void taskOnlyMultilineAndPartialInputRemainCandidateEvidenceWithoutNullAttemptFailures() {
        var report = analyzer.analyze(List.of(taskOnly("a")), List.of(
                event(5, "{\"task_id\":\"task-a\",\"event\":\"execution_error\"}", "pod", "1"),
                event(6, "Traceback (most recent call last):", "pod", "2"),
                event(7, "RuntimeError: model alpha failed", "pod", "3")), "prod", false, List.of("SOURCE_LIMIT"));
        assertEquals(1, report.path("evidence").size());
        assertTrue(report.path("evidence").get(0).path("excerpt").asText().contains("RuntimeError"));
        assertTrue(resultTask(report, "task-a").path("observed_signatures").get(0).asText().startsWith("EXCEPTION:RuntimeError:"));
        assertEquals(Map.of("INCOMPLETE", 1), counts(report));
        assertTrue(hasFlag(report, "INCOMPLETE_INPUT"));
        assertTrue(hasFlag(report, "MULTILINE_SAME_STREAM"));
    }

    private static Task task(String name) {
        return new Task("ref-" + name, "task-" + name, "failed", List.of(
                new Attempt("attempt-" + name, "task-" + name, START, START.plusSeconds(60), "instance-" + name)),
                "attempt-" + name, null, START.plusSeconds(60), "v1");
    }

    private static Task taskOnly(String name) {
        return new Task("ref-" + name, "task-" + name, "failed", List.of(), "", "", null,
                "record-7-updated-2026-10-15T05:01:00Z", AssociationScope.TASK_ONLY);
    }

    private static LogEvent event(long seconds, String message, String pod, String id) {
        return new LogEvent(START.plusSeconds(seconds), message, labels(pod, "prod", "0"), id);
    }

    private static Map<String, String> labels(String pod, String namespace, String restart) {
        return Map.of("namespace", namespace, "pod", pod, "pod_uid", "uid-" + pod, "container", "worker", "restart", restart, "component", pod.startsWith("scheduler") ? "scheduler" : "comfyui");
    }

    private static Map<String, Integer> counts(JsonNode report) {
        Map<String, Integer> counts = new HashMap<>();
        for (JsonNode cluster : report.path("clusters")) counts.put(cluster.path("signature").asText(), cluster.path("task_count").asInt());
        return counts;
    }

    private static JsonNode resultTask(JsonNode report, String id) { return find(report.path("tasks"), "task_id", id); }

    private static JsonNode find(JsonNode values, String field, String value) {
        for (JsonNode node : values) if (node.path(field).asText().equals(value)) return node;
        throw new AssertionError("未找到 " + field + "=" + value);
    }

    private static boolean hasFlag(JsonNode node, String flag) {
        for (JsonNode value : node.path("quality_flags")) if (value.asText().equals(flag)) return true;
        return false;
    }
}
