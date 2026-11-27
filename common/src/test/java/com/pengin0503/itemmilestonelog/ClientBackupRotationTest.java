package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-150-67 regression coverage: generations, same-second suffixes, pattern-only pruning, non-throw. */
class ClientBackupRotationTest {
    @TempDir
    Path configDir;

    private static final Instant NOW = Instant.parse("2026-11-28T12:34:56Z");

    @Test
    void fileNameUsesTimestampAndSameSecondSuffixes() {
        List<String> existing = new ArrayList<>();
        assertEquals("cache-20261128-123456.json",
                ClientBackupRotation.chooseFileName(NOW, ZoneOffset.UTC, existing::contains));
        existing.add("cache-20261128-123456.json");
        assertEquals("cache-20261128-123456-1.json",
                ClientBackupRotation.chooseFileName(NOW, ZoneOffset.UTC, existing::contains));
        existing.add("cache-20261128-123456-1.json");
        assertEquals("cache-20261128-123456-2.json",
                ClientBackupRotation.chooseFileName(NOW, ZoneOffset.UTC, existing::contains));
    }

    @Test
    void patternMatchingNeverTouchesForeignFiles() {
        assertTrue(ClientBackupRotation.isBackupFileName("cache-20261128-123456.json"));
        assertTrue(ClientBackupRotation.isBackupFileName("cache-20261128-123456-12.json"));
        assertFalse(ClientBackupRotation.isBackupFileName("cache-20261128-123456.json.tmp"));
        assertFalse(ClientBackupRotation.isBackupFileName("milestones-20261128-123456.dat"));
        assertFalse(ClientBackupRotation.isBackupFileName("other-mod-cache.json"));

        List<String> names = new ArrayList<>(List.of(
                "cache-20261120-010000.json", "cache-20261121-010000.json",
                "cache-20261122-010000.json", "cache-20261123-010000.json",
                "notes.txt", "milestones-20261128-123456.dat"));
        assertEquals(List.of("cache-20261120-010000.json", "cache-20261121-010000.json"),
                ClientBackupRotation.selectFilesToPrune(names, 2));
        assertEquals(1, ClientBackupRotation.clampGenerations(0));
        assertEquals(10, ClientBackupRotation.clampGenerations(99));
    }

    @Test
    void rotateWritesOneGenerationAndPrunesOnlyOldOnes() throws Exception {
        Path cache = LocalSyncCache.fileFor(configDir);
        Files.createDirectories(cache.getParent());
        Files.writeString(cache, "{\"v\":\"1\"}", StandardCharsets.UTF_8);
        Path backups = ClientBackupRotation.backupDir(configDir);
        Files.createDirectories(backups);
        for (int day = 1; day <= 3; day++) {
            Files.writeString(backups.resolve("cache-2026110" + day + "-000000.json"), "old");
        }
        Files.writeString(backups.resolve("notes-from-other-mod.txt"), "keep me");

        ClientBackupRotation.Result result = ClientBackupRotation.rotate(
                configDir, NOW, ZoneOffset.UTC, 2);
        assertTrue(result.written());
        assertTrue(result.warnings().isEmpty());
        assertEquals("cache-20261128-123456.json", result.backupFile().getFileName().toString());
        assertEquals(2, result.retained());
        assertTrue(Files.exists(backups.resolve("cache-20261128-123456.json")));
        assertFalse(Files.exists(backups.resolve("cache-20261101-000000.json")));
        assertTrue(Files.exists(backups.resolve("notes-from-other-mod.txt")),
                "rotation must never delete files it does not own");
    }

    @Test
    void rotateWithoutACacheIsASilentNoOp() {
        ClientBackupRotation.Result result = ClientBackupRotation.rotate(
                configDir, NOW, ZoneOffset.UTC, 3);
        assertFalse(result.written());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void ioFailureReturnsWarningsInsteadOfThrowing() throws Exception {
        Path cache = LocalSyncCache.fileFor(configDir);
        Files.createDirectories(cache.getParent());
        Files.writeString(cache, "{}", StandardCharsets.UTF_8);
        // Make the backup directory path a regular file so creating it must fail.
        Files.writeString(ClientBackupRotation.backupDir(configDir), "occupied");

        ClientBackupRotation.Result result = ClientBackupRotation.rotate(
                configDir, NOW, ZoneOffset.UTC, 3);
        assertFalse(result.written());
        assertFalse(result.warnings().isEmpty());
    }
}
