package dev.holmes.logs;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.holmes.Json;
import dev.holmes.logs.LogModels.LogEvent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FluxDropArchiveSinkTest {
    @TempDir Path directory;
    private HttpServer server;
    private final List<Upload> uploads = new CopyOnWriteArrayList<>();

    @AfterEach void stopServer() { if (server != null) server.stop(0); }

    @Test void streamsGzippedJsonLinesWithClientHashAndSafeUniqueNames() throws Exception {
        start(exchange -> respond(exchange, 200, "{\"object\":{\"download_url\":\"/f/test/log.jsonl.gz\"}}"));
        var sink = sink(1024 * 1024, 4096);
        var first = sink.archive("../../业务/\\danger", List.of(event("task_id=t1 prompt_id=p1 failed")), deadline());
        var second = sink.archive("../../业务/\\danger", List.of(event("task_id=t1 prompt_id=p1 failed")), deadline());

        assertEquals("AVAILABLE", first.state());
        assertEquals("AVAILABLE", second.state());
        assertEquals(2, uploads.size());
        Upload upload = uploads.getFirst();
        assertEquals("PUT", upload.method());
        assertEquals("application/gzip", upload.contentType());
        assertEquals("Bearer upload-secret", upload.authorization());
        assertTrue(upload.path().matches("/upload/[A-Za-z0-9_-]+\\.jsonl\\.gz"), upload.path());
        assertNotEquals(upload.path(), uploads.getLast().path());
        var part = first.parts().getFirst();
        assertEquals(upload.bytes().length, part.bytes());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(upload.bytes())), part.sha256());
        assertEquals(origin().resolve("/f/test/log.jsonl.gz").toString(), part.downloadUrl());
        String jsonl = gunzip(upload.bytes());
        assertTrue(jsonl.endsWith("\n"));
        var line = Json.MAPPER.readTree(jsonl);
        assertEquals("2026-10-15T05:00:00Z", line.path("time").asText());
        assertEquals("task_id=t1 prompt_id=p1 failed", line.path("message").asText());
        assertEquals("pod-1", line.path("labels").path("pod").asText());
        assertFalse(sink.toString().contains("upload-secret"));
        assertSpoolEmpty();
    }

    @Test void redactsCredentialHeadersLabelsAndUrlQueryValuesBeforeCompression() throws Exception {
        start(exchange -> respond(exchange, 200, "/f/redacted/log.gz"));
        String message = "Authorization: Bearer bearerSecret\nProxy-Authorization=Basic basicSecret\n"
                + "Cookie: session=cookieSecret\npassword=passwordSecret api_key=apiSecret access_token=tokenSecret\n"
                + "{\"password\":\"quoted secret with spaces\",\"authorization\":\"Bearer quoted bearer secret\"}\n"
                + "https://user:userSecret@example.test/file?X-Amz-Signature=signSecret&X-Amz-Credential=credSecret&safe=valueSecret";
        var record = new LogEvent(Instant.parse("2026-10-15T05:00:00Z"), message,
                Map.of("pod", "pod-1", "authorization", "labelSecret", "source", "https://host/path?token=querySecret"), "id");

        assertEquals("AVAILABLE", sink(10000, 4000).archive("redaction", List.of(record), deadline()).state());

        String jsonl = gunzip(uploads.getFirst().bytes());
        for (String secret : List.of("bearerSecret", "basicSecret", "cookieSecret", "passwordSecret", "apiSecret", "tokenSecret",
                "userSecret", "signSecret", "credSecret", "valueSecret", "labelSecret", "querySecret")) {
            assertFalse(jsonl.contains(secret), secret + " 未脱敏");
        }
        assertTrue(jsonl.contains("[REDACTED]"));
        assertFalse(jsonl.contains("quoted secret"));
        assertFalse(jsonl.contains("quoted bearer secret"));
        assertSpoolEmpty();
    }

    @Test void splitsOnUncompressedBytesAndEveryPartContainsCompleteJsonLines() throws Exception {
        start(exchange -> respond(exchange, 200, "/f/chunk/log.gz"));
        List<LogEvent> events = List.of(event("failed " + "a".repeat(60)), event("failed " + "b".repeat(60)), event("failed " + "中".repeat(20)));

        var result = sink(10000, 250).archive("parts", events, deadline());

        assertEquals("AVAILABLE", result.state());
        assertEquals(3, result.parts().size());
        List<String> messages = new ArrayList<>();
        for (Upload upload : uploads) {
            String lines = gunzip(upload.bytes());
            assertTrue(lines.getBytes(StandardCharsets.UTF_8).length <= 250);
            for (String line : lines.lines().toList()) messages.add(Json.MAPPER.readTree(line).path("message").asText());
        }
        assertEquals(events.stream().map(LogEvent::message).toList(), messages);
        assertSpoolEmpty();
    }

    @Test void oversizeEventIsExplicitlyOmittedAndOtherEventsCanStillArchive() throws Exception {
        start(exchange -> respond(exchange, 200, "/f/chunk/log.gz"));

        var result = sink(10000, 160).archive("oversize", List.of(event("x".repeat(1000)), event("ok")), deadline());

        assertEquals("PARTIAL", result.state());
        assertEquals(1, result.parts().size());
        assertTrue(result.limitations().stream().anyMatch(s -> s.contains("单条日志")));
        assertEquals("ok", Json.MAPPER.readTree(gunzip(uploads.getFirst().bytes())).path("message").asText());
        assertSpoolEmpty();
    }

    @Test void jsonEscapingAndMetadataAlsoCountAgainstUncompressedLimit() throws Exception {
        start(exchange -> respond(exchange, 200, "/f/chunk/log.gz"));

        var result = sink(10000, 100).archive("oversize", List.of(event("\\".repeat(30))), deadline());

        assertEquals("UNAVAILABLE", result.state());
        assertTrue(result.limitations().stream().anyMatch(s -> s.contains("单条日志")));
        assertTrue(uploads.isEmpty());
        assertSpoolEmpty();
    }

    @Test void successWithoutActualDownloadReferenceIsUnverified() throws Exception {
        start(exchange -> respond(exchange, 201, "{\"success\":true,\"id\":\"do-not-guess\"}"));

        var result = sink(10000, 1000).archive("no-url", List.of(event("failed")), deadline());

        assertEquals("UNVERIFIED", result.state());
        assertEquals("UNVERIFIED", result.parts().getFirst().state());
        assertNull(result.parts().getFirst().downloadUrl());
        assertSpoolEmpty();
    }

    @Test void acceptsActualSameOriginLocationWithoutParsingResponseBody() throws Exception {
        start(exchange -> {
            exchange.getResponseHeaders().add("Location", origin().resolve("/f/location/log.gz").toString());
            respond(exchange, 201, "uploaded");
        });

        var result = sink(10000, 1000).archive("location", List.of(event("failed")), deadline());

        assertEquals("AVAILABLE", result.state());
        assertEquals(origin().resolve("/f/location/log.gz").toString(), result.parts().getFirst().downloadUrl());
        assertSpoolEmpty();
    }

    @Test void rejectsCrossOriginCredentialTraversalAndNonDownloadReferences() throws Exception {
        AtomicReference<String> response = new AtomicReference<>();
        start(exchange -> respond(exchange, 200, response.get()));
        for (String link : List.of("https://evil.test/f/log.gz", "http://127.0.0.1:1/f/log.gz",
                "file:///f/log.gz", "//evil.test/f/log.gz", "/f/../admin", "/f/%2e%2e/admin",
                "/f/path%5cadmin", "/f/%0asecret", "/admin", "http://user:secret@127.0.0.1:" + server.getAddress().getPort() + "/f/file")) {
            response.set(Json.MAPPER.createObjectNode().put("url", link).toString());
            var result = sink(10000, 1000).archive("unsafe", List.of(event("failed")), deadline());
            assertEquals("UNVERIFIED", result.state(), link);
            assertNull(result.parts().getFirst().downloadUrl(), link);
        }
        assertSpoolEmpty();
    }

    @Test void doesNotFollowRedirectsAndDoesNotLeakRemoteErrorBody() throws Exception {
        AtomicInteger redirects = new AtomicInteger();
        start(exchange -> {
            if (exchange.getRequestURI().getPath().startsWith("/upload/")) {
                exchange.getResponseHeaders().add("Location", origin().resolve("/f/redirect/log.gz").toString());
                respond(exchange, 302, "private-server-secret");
            } else {
                redirects.incrementAndGet();
                respond(exchange, 200, "/f/final/log.gz");
            }
        });

        var result = sink(10000, 1000).archive("redirect", List.of(event("failed")), deadline());

        assertEquals("UNAVAILABLE", result.state());
        assertEquals(0, redirects.get());
        assertFalse(result.toString().contains("private-server-secret"));
        assertTrue(result.parts().getFirst().message().contains("302"));
        assertSpoolEmpty();
    }

    @Test void keepsPreviouslyUploadedPartsWhenNextPartFailsAndDoesNotRetry() throws Exception {
        AtomicInteger count = new AtomicInteger();
        start(exchange -> respond(exchange, count.incrementAndGet() == 1 ? 200 : 503,
                count.get() == 1 ? "/f/first/log.gz" : "remote-private-secret"));

        var result = sink(10000, 250).archive("partial", List.of(event("a".repeat(80)), event("b".repeat(80)), event("c".repeat(80))), deadline());

        assertEquals("PARTIAL", result.state());
        assertEquals(2, count.get());
        assertEquals(List.of("AVAILABLE", "FAILED"), result.parts().stream().map(p -> p.state()).toList());
        assertTrue(result.limitations().stream().anyMatch(s -> s.contains("剩余日志")));
        assertFalse(result.toString().contains("remote-private-secret"));
        assertSpoolEmpty();
    }

    @Test void boundedResponseRejectsOversizedServerBody() throws Exception {
        start(exchange -> respond(exchange, 200, "x".repeat(65537)));

        var result = sink(10000, 1000).archive("large-response", List.of(event("failed")), deadline());

        assertEquals("UNAVAILABLE", result.state());
        assertNull(result.parts().getFirst().downloadUrl());
        assertSpoolEmpty();
    }

    @Test void unverifiedEarlierPartAndFailedFinalPartIsPartial() throws Exception {
        AtomicInteger count = new AtomicInteger();
        start(exchange -> respond(exchange, count.incrementAndGet() == 1 ? 200 : 500, "{}"));

        var result = sink(10000, 250).archive("partial", List.of(event("a".repeat(80)), event("b".repeat(80))), deadline());

        assertEquals("PARTIAL", result.state());
        assertEquals(List.of("UNVERIFIED", "FAILED"), result.parts().stream().map(p -> p.state()).toList());
        assertSpoolEmpty();
    }

    @Test void existingSpoolFilesCountTowardQuotaAndAreNeverDeleted() throws Exception {
        start(exchange -> respond(exchange, 200, "/f/quota/log.gz"));
        Path existing = Files.createDirectories(directory.resolve("spool")).resolve("other-owner.log");
        Files.write(existing, new byte[55]);

        var result = sink(60, 1000).archive("quota", List.of(event("failed")), deadline());

        assertEquals("UNAVAILABLE", result.state());
        assertTrue(result.limitations().stream().anyMatch(s -> s.contains("容量")));
        assertTrue(uploads.isEmpty());
        assertEquals(55, Files.size(existing));
        try (var files = Files.list(existing.getParent())) { assertEquals(List.of(existing), files.toList()); }
    }

    @Test void compressionFooterAlsoCannotExceedSpoolQuota() throws Exception {
        start(exchange -> respond(exchange, 200, "/f/quota/log.gz"));

        var result = sink(30, 1000).archive("small-quota", List.of(event("failed")), deadline());

        assertEquals("UNAVAILABLE", result.state());
        assertTrue(uploads.isEmpty());
        assertSpoolEmpty();
    }

    @Test void deadlineBeforeStartDoesNotCreateTemporaryFiles() throws Exception {
        start(exchange -> respond(exchange, 200, "/f/log.gz"));

        var result = sink(10000, 1000).archive("expired", List.of(event("failed")), Instant.now().minusSeconds(1));

        assertEquals("UNAVAILABLE", result.state());
        assertTrue(uploads.isEmpty());
        assertSpoolEmpty();
    }

    @Test void stalledResponseBodyIsBoundedByWholeArchiveDeadlineAndFilesAreRemoved() throws Exception {
        start(exchange -> {
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write('x');
            exchange.getResponseBody().flush();
            try { Thread.sleep(1000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        long start = System.nanoTime();

        var result = sink(10000, 1000).archive("timeout", List.of(event("failed")), Instant.now().plusMillis(200));

        assertEquals("UNAVAILABLE", result.state());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 900);
        assertSpoolEmpty();
    }

    @Test void iteratorExceptionCleansTemporaryFilesWithoutLeakingExceptionMessage() throws Exception {
        start(exchange -> respond(exchange, 200, "/f/log.gz"));
        Iterable<LogEvent> broken = () -> new Iterator<>() {
            int count;
            @Override public boolean hasNext() { if (count > 0) throw new IllegalStateException("private-input-secret"); return true; }
            @Override public LogEvent next() { count++; return event("failed"); }
        };

        var result = sink(10000, 1000).archive("broken", broken, deadline());

        assertEquals("UNAVAILABLE", result.state());
        assertFalse(result.toString().contains("private-input-secret"));
        assertTrue(uploads.isEmpty());
        assertSpoolEmpty();
    }

    @Test void interruptionCancelsUploadAndCleansItsTemporaryFiles() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        start(exchange -> {
            received.countDown();
            try { Thread.sleep(1000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        AtomicReference<Throwable> error = new AtomicReference<>();
        var sink = sink(10000, 1000);
        Thread worker = Thread.ofVirtual().start(() -> {
            try { sink.archive("interrupted", List.of(event("failed")), deadline()); }
            catch (Throwable ex) { error.set(ex); }
            finally { finished.countDown(); }
        });
        assertTrue(received.await(3, TimeUnit.SECONDS));

        worker.interrupt();

        assertTrue(finished.await(3, TimeUnit.SECONDS));
        assertInstanceOf(InterruptedException.class, error.get());
        assertSpoolEmpty();
    }

    @Test void sinkInstancesShareSpoolLockAndWaitingRespectsDeadline() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        start(exchange -> {
            received.countDown();
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            respond(exchange, 200, "/f/serialized/log.gz");
        });
        var first = sink(10000, 1000);
        var second = sink(10000, 1000);
        AtomicReference<String> firstState = new AtomicReference<>();
        Thread.ofVirtual().start(() -> {
            try { firstState.set(first.archive("first", List.of(event("failed")), deadline()).state()); }
            catch (InterruptedException ex) { firstState.set("INTERRUPTED"); }
            finally { finished.countDown(); }
        });
        assertTrue(received.await(3, TimeUnit.SECONDS));
        try {
            var result = second.archive("second", List.of(event("failed")), Instant.now().plusMillis(50));
            assertEquals("UNAVAILABLE", result.state());
            assertEquals(1, uploads.size());
        } finally {
            release.countDown();
        }
        assertTrue(finished.await(3, TimeUnit.SECONDS));
        assertEquals("AVAILABLE", firstState.get());
        assertSpoolEmpty();
    }

    @Test void recoveryDeletesOnlyMarkedAbandonedPartsAndTheirEmptyDirectory() throws Exception {
        Path spool = Files.createDirectories(directory.resolve("spool"));
        Path abandoned = ownedDirectory(spool.resolve("fluxdrop-abandoned"));
        Files.write(abandoned.resolve("part-1.jsonl.gz"), new byte[1024 * 1024]);
        Files.writeString(abandoned.resolve("part-2.jsonl.gz"), "interrupted partial gzip");
        Path foreign = Files.writeString(spool.resolve("foreign.log"), "keep foreign file");

        offlineSink(spool).cleanupAbandoned();

        assertFalse(Files.exists(abandoned));
        assertEquals("keep foreign file", Files.readString(foreign));
    }

    @Test void recoveryPreservesUnknownChildrenForeignDirectoriesAndAllSymbolicLinks() throws Exception {
        Path spool = Files.createDirectories(directory.resolve("spool"));
        Path marked = ownedDirectory(spool.resolve("fluxdrop-with-foreign-children"));
        Path ownPart = Files.writeString(marked.resolve("part-1.jsonl.gz"), "own abandoned gzip");
        Path unknown = Files.writeString(marked.resolve("user-notes.txt"), "keep unknown");
        Path nested = Files.createDirectory(marked.resolve("nested"));
        Path nestedPart = Files.writeString(nested.resolve("part-3.jsonl.gz"), "keep nested");
        Path target = Files.writeString(directory.resolve("external.log"), "keep symlink target");
        Path linkedPart = Files.createSymbolicLink(marked.resolve("part-2.jsonl.gz"), target);
        Path unmarked = Files.createDirectory(spool.resolve("fluxdrop-no-marker"));
        Path foreignPart = Files.writeString(unmarked.resolve("part-1.jsonl.gz"), "keep unmarked");
        Path invalid = ownedDirectory(spool.resolve("fluxdrop-invalid-marker"));
        Files.writeString(invalid.resolve(".holmes-fluxdrop-owner"), "another-tool\n");
        Path invalidPart = Files.writeString(invalid.resolve("part-1.jsonl.gz"), "keep invalid marker");
        Path external = ownedDirectory(directory.resolve("external-owned"));
        Path externalPart = Files.writeString(external.resolve("part-1.jsonl.gz"), "keep external directory");
        Path linkedDirectory = Files.createSymbolicLink(spool.resolve("fluxdrop-linked-directory"), external);
        Path linkedMarkerDir = Files.createDirectory(spool.resolve("fluxdrop-linked-marker"));
        Files.createSymbolicLink(linkedMarkerDir.resolve(".holmes-fluxdrop-owner"), external.resolve(".holmes-fluxdrop-owner"));
        Path linkedMarkerPart = Files.writeString(linkedMarkerDir.resolve("part-1.jsonl.gz"), "keep linked marker");

        offlineSink(spool).cleanupAbandoned();

        assertFalse(Files.exists(ownPart));
        for (Path preserved : List.of(unknown, nestedPart, target, foreignPart, invalidPart, externalPart, linkedMarkerPart))
            assertTrue(Files.isRegularFile(preserved), preserved.toString());
        assertTrue(Files.isSymbolicLink(linkedPart));
        assertTrue(Files.isSymbolicLink(linkedDirectory));
        assertTrue(Files.isSymbolicLink(linkedMarkerDir.resolve(".holmes-fluxdrop-owner")));
        assertEquals("keep symlink target", Files.readString(target));
        assertEquals("holmes-fluxdrop-spool-v1\n", Files.readString(marked.resolve(".holmes-fluxdrop-owner")));
    }

    @Test void activeArchiveCreatesValidOwnershipMarkerAndRecoveryWaitsForItsSpoolLock() throws Exception {
        CountDownLatch received = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch archiveFinished = new CountDownLatch(1), cleanupStarted = new CountDownLatch(1), cleanupFinished = new CountDownLatch(1);
        start(exchange -> {
            received.countDown();
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            respond(exchange, 200, "/f/active/log.gz");
        });
        var sink = sink(10000, 1000);
        AtomicReference<String> archiveState = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread.ofVirtual().start(() -> {
            try { archiveState.set(sink.archive("active", List.of(event("failed")), deadline()).state()); }
            catch (Throwable ex) { error.set(ex); }
            finally { archiveFinished.countDown(); }
        });
        assertTrue(received.await(3, TimeUnit.SECONDS));
        Path working;
        try (var files = Files.list(directory.resolve("spool"))) { working = files.findFirst().orElseThrow(); }
        assertEquals("holmes-fluxdrop-spool-v1\n", Files.readString(working.resolve(".holmes-fluxdrop-owner")));
        Thread.ofVirtual().start(() -> {
            cleanupStarted.countDown();
            try { sink.cleanupAbandoned(); }
            catch (Throwable ex) { error.set(ex); }
            finally { cleanupFinished.countDown(); }
        });
        try {
            assertTrue(cleanupStarted.await(1, TimeUnit.SECONDS));
            assertFalse(cleanupFinished.await(50, TimeUnit.MILLISECONDS));
            assertTrue(Files.exists(working.resolve("part-1.jsonl.gz")));
        } finally { release.countDown(); }
        assertTrue(archiveFinished.await(3, TimeUnit.SECONDS));
        assertTrue(cleanupFinished.await(3, TimeUnit.SECONDS));
        assertNull(error.get());
        assertEquals("AVAILABLE", archiveState.get());
        assertSpoolEmpty();
    }

    @Test void serviceRestartCleansMarkedAbandonedSpoolBeforeAcceptingNewJobs() throws Exception {
        var config = LogInvestigationServiceTest.fixtureConfig(directory.resolve("service"), Map.of());
        try (var original = new LogInvestigationService(config)) { /* 模拟已有服务退出后的重启。 */ }
        Path abandoned = ownedDirectory(config.dataDir().resolve("spool").resolve("fluxdrop-interrupted-jvm"));
        Files.writeString(abandoned.resolve("part-1.jsonl.gz"), "log left by abruptly terminated JVM");

        try (var resumed = new LogInvestigationService(config)) {
            assertFalse(Files.exists(abandoned));
            var report = LogInvestigationServiceTest.completed(resumed, resumed.submit(LogInvestigationServiceTest.explicit("none", "task-B")));
            assertEquals(1, report.path("processed_count").asInt());
        }
    }

    private static Path ownedDirectory(Path path) throws IOException {
        Files.createDirectories(path);
        Files.writeString(path.resolve(".holmes-fluxdrop-owner"), "holmes-fluxdrop-spool-v1\n");
        return path;
    }

    private static FluxDropArchiveSink offlineSink(Path spool) {
        return new FluxDropArchiveSink(URI.create("http://127.0.0.1:1"), "", spool, 10000, 1000, Duration.ofSeconds(3));
    }

    private void start(HttpHandler handler) throws IOException {
        server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            uploads.add(new Upload(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Content-Type"), exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestBody().readAllBytes()));
            handler.handle(exchange);
        });
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private FluxDropArchiveSink sink(long quota, long chunk) {
        return new FluxDropArchiveSink(origin(), "upload-secret", directory.resolve("spool"), quota, chunk, Duration.ofSeconds(4));
    }

    private URI origin() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
    private static Instant deadline() { return Instant.now().plusSeconds(5); }
    private static LogEvent event(String message) {
        return new LogEvent(Instant.parse("2026-10-15T05:00:00Z"), message, Map.of("pod", "pod-1", "namespace", "prod"), "event-1");
    }
    private static String gunzip(byte[] gzip) throws IOException {
        try (var input = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private void assertSpoolEmpty() throws IOException {
        Path spool = directory.resolve("spool");
        if (Files.exists(spool)) try (var files = Files.list(spool)) { assertEquals(0, files.count()); }
    }
    private record Upload(String method, String path, String contentType, String authorization, byte[] bytes) { }
}
