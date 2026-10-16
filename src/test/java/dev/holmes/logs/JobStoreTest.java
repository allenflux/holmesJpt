package dev.holmes.logs;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.holmes.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class JobStoreTest {
    private static final String A = "00000000-0000-0000-0000-000000000001";
    private static final String B = "00000000-0000-0000-0000-000000000002";
    private static final String C = "00000000-0000-0000-0000-000000000003";
    private static final String D = "00000000-0000-0000-0000-000000000004";
    private static final String E = "00000000-0000-0000-0000-000000000005";
    @TempDir Path directory;

    @Test void enforcesSingleReportCapAndPreservesPreviousCheckpoint() throws Exception {
        JobStore store = new JobStore(directory, 2_000, 256);
        ObjectNode previous = job(A, "RUNNING", 20);
        store.save(A, previous);
        assertThrows(JobStore.StorageLimit.class, () -> store.save(A, job(A, "COMPLETED", 1_000)));
        assertEquals(previous, store.get(A));
        assertEquals(Set.of(A + ".json"), names(directory));
    }

    @Test void totalQuotaIncludesAtomicReplacementPeakAndLeavesNoTemporaryFiles() throws Exception {
        ObjectNode previous = job(A, "RUNNING", 20);
        long size = Json.MAPPER.writeValueAsBytes(previous).length;
        JobStore tight = new JobStore(directory, size * 2 - 1, size * 3);
        tight.save(A, previous);
        assertThrows(JobStore.StorageLimit.class, () -> tight.save(B, job(B, "RUNNING", 20)));
        assertThrows(JobStore.StorageLimit.class, () -> tight.save(A, previous));
        assertEquals(previous, tight.get(A));
        assertTrue(used(directory) <= size * 2 - 1);
        assertEquals(Set.of(A + ".json"), names(directory));
        JobStore room = new JobStore(directory, size * 3, size * 3);
        ObjectNode next = previous.deepCopy().put("stage", "DONE");
        room.save(A, next);
        assertEquals(next, room.get(A));
        assertEquals(Set.of(A + ".json"), names(directory));
    }

    @Test void newBatchCapacityReservesActiveReportsAndOneReplacementBuffer() throws Exception {
        JobStore store = new JobStore(directory, 1_024, 256);
        store.checkNewJobCapacity();
        store.save(A, job(A, "RUNNING", 0));
        store.checkNewJobCapacity();
        store.save(B, job(B, "QUEUED", 0));
        assertThrows(JobStore.StorageLimit.class, store::checkNewJobCapacity);
        store.save(B, job(B, "COMPLETED", 0));
        store.checkNewJobCapacity();
        // 完成批次仍占真实空间，但不再为未来检查点预留一整份报告。
        store.save(A, job(A, "COMPLETED", 0));
        store.checkNewJobCapacity();
        Path small = directory.resolve("small");
        JobStore cannotReserve = new JobStore(small, 511, 256);
        assertThrows(JobStore.StorageLimit.class, cannotReserve::checkNewJobCapacity);
    }

    @Test void ttlRemovesExpiredReportsAndOldSpoolButRetainsFreshAndCorruptFiles() throws Exception {
        JobStore store = new JobStore(directory, 4_096, 1_024);
        ObjectNode live = job(A, "RUNNING", 0);
        store.save(A, live);
        write(B, job(B, "COMPLETED", 0).put("expires_at", Instant.now().minusSeconds(60).toString()));
        write(C, job(C, "RUNNING", 0).put("expires_at", Instant.now().minusSeconds(60).toString()));
        Files.writeString(directory.resolve(D + ".json"), "{broken-json");
        Path oldTemporary = directory.resolve("holmes-report-old.tmp"), freshTemporary = directory.resolve("holmes-report-fresh.tmp");
        Files.writeString(oldTemporary, "old"); Files.writeString(freshTemporary, "fresh");
        Files.setLastModifiedTime(oldTemporary, FileTime.from(Instant.now().minusSeconds(7_200)));
        store.cleanup();
        assertEquals(live, store.get(A));
        assertNull(store.get(B)); assertNull(store.get(C));
        assertFalse(Files.exists(directory.resolve(B + ".json")));
        assertFalse(Files.exists(directory.resolve(C + ".json")));
        assertFalse(Files.exists(oldTemporary)); assertTrue(Files.exists(freshTemporary));
        assertTrue(Files.exists(directory.resolve(D + ".json")));
        assertThrows(IOException.class, () -> store.get(D));
    }

    @Test void corruptAndUnrelatedRegularFilesStillConsumeTotalQuota() throws Exception {
        JobStore store = new JobStore(directory, 1_024, 512);
        Files.writeString(directory.resolve("unrelated.bin"), "x".repeat(950));
        assertThrows(JobStore.StorageLimit.class, () -> store.save(A, job(A, "RUNNING", 0)));
        assertEquals(950, used(directory));
        assertThrows(JobStore.StorageLimit.class, store::checkNewJobCapacity);
    }

    @Test void unfinishedReturnsOnlyNonExpiredQueuedAndRunningCheckpoints() throws Exception {
        JobStore store = new JobStore(directory, 8_192, 1_024);
        store.save(A, job(A, "QUEUED", 0)); store.save(B, job(B, "RUNNING", 0));
        store.save(C, job(C, "COMPLETED", 0)); store.save(D, job(D, "FAILED", 0)); store.save(E, job(E, "PARTIAL", 0));
        Files.writeString(directory.resolve("00000000-0000-0000-0000-000000000006.json"), "[]");
        write("00000000-0000-0000-0000-000000000007", job("expired", "RUNNING", 0).put("expires_at", Instant.now().minusSeconds(60).toString()));
        assertEquals(Set.of(A, B), store.unfinished().stream().map(node -> node.path("batch_id").asText()).collect(Collectors.toSet()));
    }

    @Test void traversalAndNonUuidBatchIdsCannotAccessFiles() throws Exception {
        JobStore store = new JobStore(directory, 4_096, 1_024);
        for (String id : List.of("../outside", "/tmp/outside", "", A + "/../" + B, A + ".json", "00000000-0000-0000-0000-00000000000G")) {
            assertThrows(IllegalArgumentException.class, () -> store.get(id), id);
            assertThrows(IllegalArgumentException.class, () -> store.save(id, job(A, "RUNNING", 0)), id);
        }
        assertThrows(IllegalArgumentException.class, () -> store.get(null));
        assertEquals(Set.of(), names(directory));
    }

    @Test void rejectsSymlinkedReportAndDoesNotFollowItDuringRecoveryOrCleanup() throws Exception {
        Path reports = directory.resolve("reports"), outside = directory.resolve("outside.json");
        JobStore store = new JobStore(reports, 4_096, 1_024);
        String original = job(A, "RUNNING", 0).toString(); Files.writeString(outside, original);
        Files.createSymbolicLink(reports.resolve(A + ".json"), outside);
        assertThrows(IllegalArgumentException.class, () -> store.get(A));
        assertThrows(IllegalArgumentException.class, () -> store.save(A, job(A, "COMPLETED", 0)));
        assertTrue(store.unfinished().isEmpty()); store.cleanup();
        assertEquals(original, Files.readString(outside));
        assertTrue(Files.isSymbolicLink(reports.resolve(A + ".json")));
    }

    @Test void rejectsSymlinkedStoreDirectoryAndSymlinkedAncestors() throws Exception {
        Path physical = directory.resolve("physical"); Files.createDirectory(physical);
        Path alias = directory.resolve("alias"); Files.createSymbolicLink(alias, physical);
        assertThrows(IOException.class, () -> new JobStore(alias, 4_096, 1_024));
        assertThrows(IOException.class, () -> new JobStore(alias.resolve("reports"), 4_096, 1_024));
    }

    private void write(String id, ObjectNode value) throws Exception { Files.writeString(directory.resolve(id + ".json"), value.toString()); }
    private static ObjectNode job(String id, String state, int payloadSize) {
        return Json.MAPPER.createObjectNode().put("batch_id", id).put("state", state)
                .put("expires_at", Instant.parse("2099-01-01T00:00:00Z").toString()).put("payload", "x".repeat(payloadSize));
    }
    private static Set<String> names(Path directory) throws Exception {
        try (var files = Files.list(directory)) { return files.map(path -> path.getFileName().toString()).collect(Collectors.toSet()); }
    }
    private static long used(Path directory) throws Exception {
        try (var files = Files.list(directory)) { long total = 0; for (Path file : files.filter(Files::isRegularFile).toList()) total += Files.size(file); return total; }
    }
}
