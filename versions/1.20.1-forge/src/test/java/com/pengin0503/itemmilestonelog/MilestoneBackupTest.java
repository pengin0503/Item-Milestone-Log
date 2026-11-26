package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.compat.McCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MilestoneBackupTest {
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final UUID PLAYER_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAYER_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void resetBefore() {
        MilestoneBackup.resetState();
    }

    @AfterEach
    void resetAfter() {
        MilestoneBackup.resetState();
    }

    private static MilestoneData sampleData() {
        MilestoneData data = new MilestoneData();
        TargetKey diamond1 = TargetKey.of("minecraft:diamond");
        TargetKey diamond100 = TargetKey.of("minecraft:diamond", 100);
        TargetKey ironTag = TargetKey.of("#c:ingots/iron", 64);

        MilestoneData.PlayerData pdA = data.player(PLAYER_A);
        pdA.targets.add(diamond1);
        pdA.targets.add(diamond100);
        pdA.targets.add(ironTag);
        MilestoneRecord recA1 = new MilestoneRecord(12340, 11L, 1795600000000L);
        MilestoneRecord recA100 = new MilestoneRecord(99999, 25L, 1795650000000L);
        MilestoneRecord recTag = new MilestoneRecord(48000, 15L, 1795620000000L);
        pdA.records.put(diamond1, recA1);
        pdA.records.put(diamond100, recA100);
        pdA.records.put(ironTag, recTag);
        pdA.carriedFromCreative.add("minecraft:netherite_ingot");
        pdA.lastKnownName = "Alice";
        pdA.firstJoinHintShown = true;
        pdA.muteBroadcasts = true;
        pdA.muteProgressAlerts = true;
        pdA.excludeFromDiscord = true;
        pdA.hideFromLookup = true;
        pdA.lastSeen = 1795800000000L;
        pdA.progressNotified.put(TargetKey.of("minecraft:gold_ingot", 500), 50);
        pdA.completedPresets.add("vanilla_endgame");

        MilestoneData.PlayerData pdB = data.player(PLAYER_B);
        pdB.lastKnownName = "Bob";
        pdB.targets.add(TargetKey.of("minecraft:emerald"));

        data.recordWorldFirstIfAbsent(diamond1, PLAYER_A, "Alice", recA1);
        data.recordWorldFirstIfAbsent(ironTag, PLAYER_A, "Alice", recTag);
        return data;
    }

    private static void assertDataEquals(MilestoneData expected, MilestoneData actual) {
        assertNotNull(actual);
        assertEquals(expected.allPlayers().keySet(), actual.allPlayers().keySet());
        for (Map.Entry<UUID, MilestoneData.PlayerData> entry : expected.allPlayers().entrySet()) {
            MilestoneData.PlayerData exp = entry.getValue();
            MilestoneData.PlayerData act = actual.playerOrNull(entry.getKey());
            assertNotNull(act);
            assertEquals(List.copyOf(exp.targets), List.copyOf(act.targets));
            assertEquals(exp.records, act.records);
            assertEquals(List.copyOf(exp.carriedFromCreative), List.copyOf(act.carriedFromCreative));
            assertEquals(exp.lastKnownName, act.lastKnownName);
            assertEquals(exp.firstJoinHintShown, act.firstJoinHintShown);
            assertEquals(exp.muteBroadcasts, act.muteBroadcasts);
            assertEquals(exp.muteProgressAlerts, act.muteProgressAlerts);
            assertEquals(exp.excludeFromDiscord, act.excludeFromDiscord);
            assertEquals(exp.hideFromLookup, act.hideFromLookup);
            assertEquals(exp.lastSeen, act.lastSeen);
            assertEquals(exp.progressNotified, act.progressNotified);
            assertEquals(List.copyOf(exp.completedPresets), List.copyOf(act.completedPresets));
        }
        assertEquals(expected.worldFirsts(), actual.worldFirsts());
    }

    private static Set<String> listFileNames(Path dir) throws IOException {
        Set<String> names = new TreeSet<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.map(p -> p.getFileName().toString()).forEach(names::add);
        }
        return names;
    }

    @Test
    void writtenBackupLoadsBackThroughNormalLoadPathWithEqualContent(@TempDir Path tempDir) throws IOException {
        MilestoneData original = sampleData();
        assertTrue(original.isDirty());

        Path backupDir = tempDir.resolve("backups");
        Instant ts = ZonedDateTime.of(2026, 11, 24, 11, 15, 30, 0, UTC).toInstant();

        assertTrue(MilestoneBackup.writeBackup(backupDir, original, ts, UTC, 5));
        // Backing up in-memory data must not clear Minecraft's dirty flag
        assertTrue(original.isDirty());

        Path backupFile = backupDir.resolve("milestones-20261124-111530.dat");
        assertTrue(Files.isRegularFile(backupFile));
        assertEquals(Set.of("milestones-20261124-111530.dat"), listFileNames(backupDir));

        // Verify the compressed NBT wrapper structure matches vanilla SavedData
        CompoundTag rawRoot = NbtIo.readCompressed(backupFile.toFile());
        assertTrue(rawRoot.contains("data"));
        assertTrue(McCompat.nbt().getInt(rawRoot, "DataVersion") > 0);
        // F-140-1 (N88): toSavedDataFileTag uses the normal save path, so the F10 backup root
        // automatically carries schemaVersion.
        assertEquals(SchemaVersionLogic.CURRENT_SCHEMA_VERSION,
                McCompat.nbt().getInt(McCompat.nbt().getCompound(rawRoot, "data"), "schemaVersion"));

        // Load the backup content back through MilestoneData.load (the same entry the
        // DataCompat storage adapters use) and verify the round trip.
        MilestoneData loaded = MilestoneData.load(McCompat.nbt().getCompound(rawRoot, "data"));
        assertDataEquals(original, loaded);
    }

    @Test
    void skipsWritingWhenDataIsEmpty(@TempDir Path tempDir) throws IOException {
        Path backupDir = tempDir.resolve("backups");
        Instant ts = ZonedDateTime.of(2026, 11, 24, 11, 15, 30, 0, UTC).toInstant();

        MilestoneData empty = new MilestoneData();
        assertTrue(empty.isEmpty());
        assertFalse(MilestoneBackup.writeBackup(backupDir, empty, ts, UTC, 5));
        assertFalse(Files.exists(backupDir));
        assertEquals(MilestoneBackupLogic.NO_BACKUP_RECORDED, MilestoneBackup.lastBackupMillis());

        // World-first only (no players) is NOT empty and must be written
        MilestoneData worldFirstOnly = new MilestoneData();
        worldFirstOnly.recordWorldFirstIfAbsent(
                TargetKey.of("minecraft:diamond"), PLAYER_A, "Alice", new MilestoneRecord(100, 1L, 1795600000000L));
        assertFalse(worldFirstOnly.isEmpty());
        assertTrue(MilestoneBackup.writeBackup(backupDir, worldFirstOnly, ts, UTC, 5));
        assertEquals(Set.of("milestones-20261124-111530.dat"), listFileNames(backupDir));
    }

    @Test
    void sameSecondCollisionsAppendSequentialSuffixes(@TempDir Path tempDir) throws IOException {
        Path backupDir = tempDir.resolve("backups");
        MilestoneData data = sampleData();
        Instant ts = ZonedDateTime.of(2026, 11, 24, 11, 15, 30, 0, UTC).toInstant();

        assertTrue(MilestoneBackup.writeBackup(backupDir, data, ts, UTC, 5));
        assertTrue(MilestoneBackup.writeBackup(backupDir, data, ts, UTC, 5));
        assertTrue(MilestoneBackup.writeBackup(backupDir, data, ts, UTC, 5));

        assertEquals(Set.of(
                "milestones-20261124-111530.dat",
                "milestones-20261124-111530-1.dat",
                "milestones-20261124-111530-2.dat"
        ), listFileNames(backupDir));
    }

    @Test
    void pruningDeletesOnlyOldestMatchingFilesAndLeavesUnrelatedFilesUntouched(@TempDir Path tempDir) throws IOException {
        Path backupDir = tempDir.resolve("backups");
        Files.createDirectories(backupDir);
        Files.writeString(backupDir.resolve("notes.txt"), "user notes");
        Files.writeString(backupDir.resolve("keep-this.dat"), "manual backup");
        Files.writeString(backupDir.resolve("milestones-20261124-100000.dat.bak"), "copy");
        Files.createDirectories(backupDir.resolve("milestones-20200101-000000.dat")); // directory, not a file

        MilestoneData data = sampleData();
        Instant t1 = ZonedDateTime.of(2026, 11, 24, 10, 0, 0, 0, UTC).toInstant();
        Instant t2 = ZonedDateTime.of(2026, 11, 24, 11, 0, 0, 0, UTC).toInstant();
        Instant t3 = ZonedDateTime.of(2026, 11, 24, 12, 0, 0, 0, UTC).toInstant();

        assertTrue(MilestoneBackup.writeBackup(backupDir, data, t1, UTC, 3));
        assertTrue(MilestoneBackup.writeBackup(backupDir, data, t2, UTC, 3));
        assertTrue(MilestoneBackup.writeBackup(backupDir, data, t2, UTC, 3)); // -1
        assertTrue(MilestoneBackup.writeBackup(backupDir, data, t2, UTC, 3)); // -2 -> prunes t1
        assertTrue(MilestoneBackup.writeBackup(backupDir, data, t3, UTC, 3)); // prunes t2 base

        assertEquals(Set.of(
                "keep-this.dat",
                "milestones-20200101-000000.dat",
                "milestones-20261124-100000.dat.bak",
                "milestones-20261124-110000-1.dat",
                "milestones-20261124-110000-2.dat",
                "milestones-20261124-120000.dat",
                "notes.txt"
        ), listFileNames(backupDir));
        assertTrue(Files.isDirectory(backupDir.resolve("milestones-20200101-000000.dat")));
    }

    @Test
    void rateLimitUsesInMemoryTimestampInitializedAtStartAndStoppingIgnoresIt(@TempDir Path tempDir) throws IOException {
        Path backupDir = tempDir.resolve("backups");
        Files.createDirectories(backupDir);
        // Existing backup from 10:00:00 UTC + unrelated file with a future date
        Files.writeString(backupDir.resolve("milestones-20261124-100000.dat"), "existing");
        Files.writeString(backupDir.resolve("manual-20991231-235959.dat"), "ignored");

        MilestoneBackup.initFromDir(backupDir, UTC);
        Instant t0 = ZonedDateTime.of(2026, 11, 24, 10, 0, 0, 0, UTC).toInstant();
        assertEquals(t0.toEpochMilli(), MilestoneBackup.lastBackupMillis());

        // Delete the existing file to prove subsequent saves use in-memory state rather than rescanning
        Files.delete(backupDir.resolve("milestones-20261124-100000.dat"));

        MilestoneData data = sampleData();
        // 30 minutes later: blocked by in-memory rate limit
        assertFalse(MilestoneBackup.tryAutoBackupForTest(
                backupDir, data, true, false, t0.plusSeconds(1800), UTC, 5));
        assertEquals(Set.of("manual-20991231-235959.dat"), listFileNames(backupDir));

        // 60 minutes later: allowed
        Instant t1 = t0.plusSeconds(3600);
        assertTrue(MilestoneBackup.tryAutoBackupForTest(
                backupDir, data, true, false, t1, UTC, 5));
        assertTrue(Files.isRegularFile(backupDir.resolve("milestones-20261124-110000.dat")));

        // 1 minute later on server stopping (ignoreRateLimit = true): allowed
        Instant tStop = t1.plusSeconds(60);
        assertTrue(MilestoneBackup.tryAutoBackupForTest(
                backupDir, data, true, true, tStop, UTC, 5));
        assertTrue(Files.isRegularFile(backupDir.resolve("milestones-20261124-110100.dat")));

        // Disabled autoBackupEnabled skips even when ignoreRateLimit = true
        assertFalse(MilestoneBackup.tryAutoBackupForTest(
                backupDir, data, false, true, tStop.plusSeconds(60), UTC, 5));
        assertFalse(Files.exists(backupDir.resolve("milestones-20261124-110200.dat")));
    }

    @Test
    void writeFailureReturnsFalseWithoutThrowing(@TempDir Path tempDir) throws IOException {
        Path blockedPath = tempDir.resolve("not-a-directory");
        Files.writeString(blockedPath, "blocker");

        MilestoneData data = sampleData();
        Instant ts = ZonedDateTime.of(2026, 11, 24, 11, 15, 30, 0, UTC).toInstant();
        assertFalse(MilestoneBackup.writeBackup(blockedPath, data, ts, UTC, 5));
        assertEquals(MilestoneBackupLogic.NO_BACKUP_RECORDED, MilestoneBackup.lastBackupMillis());
    }

    @Test
    void snapshotWriteDoesNotOwnRateLimitTimestamp(@TempDir Path tempDir) throws IOException {
        Path backupDir = tempDir.resolve("backups");
        MilestoneData data = sampleData();
        Instant ts = ZonedDateTime.of(2026, 11, 24, 11, 15, 30, 0, UTC).toInstant();
        CompoundTag root = data.toSavedDataFileTag();

        assertEquals(MilestoneBackupLogic.NO_BACKUP_RECORDED, MilestoneBackup.lastBackupMillis());
        // The I/O-thread entry point must never mutate the rate-limit timestamp (review 1-1).
        assertTrue(MilestoneBackup.writeBackupSnapshot(backupDir, root, ts, UTC, 5));
        assertEquals(MilestoneBackupLogic.NO_BACKUP_RECORDED, MilestoneBackup.lastBackupMillis());

        // The synchronous wrapper owns the timestamp on its caller's (server) thread.
        Instant later = ts.plusSeconds(30);
        assertTrue(MilestoneBackup.writeBackup(backupDir, data, later, UTC, 5));
        assertEquals(later.toEpochMilli(), MilestoneBackup.lastBackupMillis());
    }

    @Test
    void concurrentSnapshotWritesAreSerializedAndNeverShareAFilename(@TempDir Path tempDir) throws Exception {
        Path backupDir = tempDir.resolve("backups");
        Instant ts = ZonedDateTime.of(2026, 11, 24, 11, 15, 30, 0, UTC).toInstant();
        CompoundTag root = sampleData().toSavedDataFileTag();
        int writers = 6;

        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < writers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return MilestoneBackup.writeBackupSnapshot(backupDir, root, ts, UTC, 50);
                }));
            }
            start.countDown();
            for (Future<Boolean> future : futures) {
                assertTrue(future.get(10, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        // The filename choice through prune is serialized, so the same-second writers must all get
        // a distinct generation instead of silently overwriting one another (review 1-2).
        assertEquals(writers, listFileNames(backupDir).size());
    }

    @Test
    void finalBackupIsExactlyOnceAndScopedToTheCurrentSession(@TempDir Path tempDir) throws IOException {
        Path backupDir = tempDir.resolve("backups");
        MilestoneData data = sampleData();
        CompoundTag root = data.toSavedDataFileTag();
        Instant previousWorld = ZonedDateTime.of(2026, 11, 24, 10, 0, 0, 0, UTC).toInstant();
        Instant currentWorld = ZonedDateTime.of(2026, 11, 24, 11, 0, 0, 0, UTC).toInstant();

        MilestoneBackup.resetState();
        long firstGeneration = MilestoneBackup.finalBackupGeneration();
        Runnable previousWorldTask = MilestoneBackup.prepareFinalBackupForTest(
                backupDir, root, previousWorld, UTC, 5);

        // A late write from the previous world must not mark the next world as already backed up
        // (review 3-2).
        MilestoneBackup.resetState();
        assertNotEquals(firstGeneration, MilestoneBackup.finalBackupGeneration());
        previousWorldTask.run();
        assertFalse(MilestoneBackup.isFinalBackupWritten());
        assertTrue(Files.isRegularFile(backupDir.resolve("milestones-20261124-100000.dat")));

        // The current session writes once; running the async task afterwards must be a no-op.
        Runnable currentTask = MilestoneBackup.prepareFinalBackupForTest(
                backupDir, root, currentWorld, UTC, 5);
        assertTrue(MilestoneBackup.writePreparedFinalBackupNow(currentTask));
        assertTrue(MilestoneBackup.isFinalBackupWritten());
        currentTask.run();
        assertEquals(Set.of(
                "milestones-20261124-100000.dat",
                "milestones-20261124-110000.dat"), listFileNames(backupDir));
    }

    @Test
    void finalBackupFallbackWaitsOnlyBoundedGraceForAnInFlightClaim(@TempDir Path tempDir) {
        Path backupDir = tempDir.resolve("backups");
        Instant ts = ZonedDateTime.of(2026, 11, 24, 11, 15, 30, 0, UTC).toInstant();
        CompoundTag root = sampleData().toSavedDataFileTag();

        MilestoneBackup.resetState();
        assertTrue(MilestoneBackup.claimFinalBackupForTest());
        long start = System.nanoTime();
        assertFalse(MilestoneBackup.writeFinalBackupNowForTest(backupDir, root, ts, UTC, 5, 150L));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        // The fallback must not block on the in-flight writer, but must give it the bounded grace.
        assertTrue(elapsedMillis >= 100L, "expected the bounded grace to elapse, got " + elapsedMillis + " ms");
        assertTrue(elapsedMillis < 5_000L, "fallback blocked too long: " + elapsedMillis + " ms");
        assertFalse(Files.exists(backupDir));
        assertFalse(MilestoneBackup.isFinalBackupWritten());
    }
}
