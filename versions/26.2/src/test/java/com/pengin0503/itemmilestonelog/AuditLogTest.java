package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-150-57 / §1.3: rotation, failure isolation and file shape (all five trees). */
class AuditLogTest {
    private static final Instant TS = Instant.parse("2026-11-28T01:02:03Z");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @AfterEach
    void clearTestSink() {
        AuditLog.setSinkForTest(null);
    }

    private static AuditEvent event() {
        return AuditEvent.of(AuditEvent.Operation.ADMIN_PURGE, ACTOR, null,
                Map.of("playersAffected", 2), TS);
    }

    @Test
    void appendWritesExactlyOneUtf8JsonLine(@TempDir Path dir) throws Exception {
        AuditLog.append(dir, event());
        Path file = dir.resolve(AuditLog.FILE_NAME);
        assertTrue(Files.exists(file));
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        assertEquals(event().toJsonLine(), lines.get(0));
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).endsWith("\n"));
    }

    @Test
    void smallFilesAreNotRotated(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(AuditLog.FILE_NAME), "existing\n", StandardCharsets.UTF_8);
        AuditLog.append(dir, event());
        assertFalse(Files.exists(dir.resolve(AuditLog.FILE_NAME + ".1.log")));
        assertTrue(Files.readString(dir.resolve(AuditLog.FILE_NAME), StandardCharsets.UTF_8)
                .startsWith("existing\n"));
    }

    @Test
    void rotatesAtOneMiBAndKeepsFiveGenerations(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(AuditLog.FILE_NAME);
        Files.writeString(file, "CURRENT\n".repeat(150_000), StandardCharsets.UTF_8);
        for (int i = 1; i <= AuditLog.MAX_GENERATIONS; i++) {
            Files.writeString(dir.resolve(AuditLog.FILE_NAME + "." + i + ".log"),
                    "GEN" + i, StandardCharsets.UTF_8);
        }
        assertEquals(AuditLog.MAX_FILE_BYTES, 1024L * 1024L);

        AuditLog.append(dir, event());

        // Oldest generation is dropped, every survivor shifts by one, new line is in audit.log.
        assertFalse(Files.exists(dir.resolve(AuditLog.FILE_NAME + ".6.log")));
        assertEquals("GEN4", Files.readString(dir.resolve(AuditLog.FILE_NAME + ".5.log"),
                StandardCharsets.UTF_8));
        assertEquals("GEN3", Files.readString(dir.resolve(AuditLog.FILE_NAME + ".4.log"),
                StandardCharsets.UTF_8));
        assertEquals("GEN1", Files.readString(dir.resolve(AuditLog.FILE_NAME + ".2.log"),
                StandardCharsets.UTF_8));
        assertTrue(Files.readString(dir.resolve(AuditLog.FILE_NAME + ".1.log"),
                StandardCharsets.UTF_8).startsWith("CURRENT"));
        assertEquals(event().toJsonLine(), Files.readString(file, StandardCharsets.UTF_8).stripTrailing());
    }

    @Test
    void repeatedRotationsNeverKeepMoreThanFiveGenerations(@TempDir Path dir) throws Exception {
        for (int i = 0; i < 8; i++) {
            Files.writeString(dir.resolve(AuditLog.FILE_NAME), "x".repeat((int) AuditLog.MAX_FILE_BYTES),
                    StandardCharsets.UTF_8);
            AuditLog.append(dir, event());
        }
        for (int i = 1; i <= AuditLog.MAX_GENERATIONS; i++) {
            assertTrue(Files.exists(dir.resolve(AuditLog.FILE_NAME + "." + i + ".log")));
        }
        assertFalse(Files.exists(dir.resolve(AuditLog.FILE_NAME + ".6.log")));
        assertTrue(Files.size(dir.resolve(AuditLog.FILE_NAME)) < AuditLog.MAX_FILE_BYTES);
    }

    @Test
    void throwingSinkIsContainedAndDoesNotChangeTheOperationResult(@TempDir Path dir) {
        AuditLog.setSinkForTest(line -> {
            throw new IllegalStateException("secret sink detail " + line);
        });
        // Models a successful handler: the data change and the success result are already decided;
        // the audit append is additive and must never undo either one.
        boolean[] dataChanged = {false};
        String result = "success";
        try {
            dataChanged[0] = true;
            AuditLog.append(dir, event());
        } catch (RuntimeException e) {
            result = "broken";
        }
        assertEquals("success", result);
        assertTrue(dataChanged[0]);
        assertFalse(Files.exists(dir.resolve(AuditLog.FILE_NAME)));
    }

    @Test
    void appendDoesNotThrowWhenDirectoryCannotBeCreated(@TempDir Path dir) throws Exception {
        Path blocker = dir.resolve("not-a-dir");
        Files.writeString(blocker, "file", StandardCharsets.UTF_8);
        Path impossible = blocker.resolve("child");
        AuditLog.append(impossible, event()); // must swallow IOException
        assertFalse(Files.exists(impossible.resolve(AuditLog.FILE_NAME)));
    }

    @Test
    void writePropagatesSinkFailureToDirectCallers(@TempDir Path dir) {
        AuditLog.setSinkForTest(line -> {
            throw new IllegalStateException("boom");
        });
        assertThrows(IllegalStateException.class,
                () -> AuditLog.write(dir, event().toJsonLine() + "\n"));
    }

    @Test
    void everyShippedOperationIsHookedAndLegacyInfoLogsSurvive() throws Exception {
        String sourceDir = System.getProperty("itemmilestonelog.mainSourceDir");
        assertNotNull(sourceDir, "test harness must expose the version main source dir");
        Path root = Path.of(sourceDir).resolve("com/pengin0503/itemmilestonelog");
        String commands = Files.readString(root.resolve("MilestoneCommands.java"), StandardCharsets.UTF_8);
        String service = Files.readString(root.resolve("MilestoneService.java"), StandardCharsets.UTF_8);
        String tracker = Files.readString(root.resolve("Tracker.java"), StandardCharsets.UTF_8);
        String importAll = Files.readString(root.resolve("MilestoneImportAll.java"), StandardCharsets.UTF_8);
        String all = commands + service + tracker + importAll;

        for (AuditEvent.Operation op : AuditEvent.Operation.values()) {
            if (op == AuditEvent.Operation.ADJUST_RECORD) {
                continue; // designed but unshipped (D-150-136)
            }
            assertTrue(all.contains("AuditEvent.Operation." + op.name()),
                    op.name() + " has no hook in the main sources");
        }
        // The five pre-existing attribution INFO lines are preserved byte-for-byte.
        assertTrue(tracker.contains("[ItemMilestoneLog] Admin {} granted {} to {} ({}) at playtime {} ({} ticks)"));
        assertTrue(commands.contains("[ItemMilestoneLog] Admin {} purged all milestone data for {} ({})"));
        assertTrue(commands.contains("[ItemMilestoneLog] Admin {} pruned data for {} player(s) (days={})"));
        assertTrue(service.contains("[ItemMilestoneLog] Admin {} renamed item {} -> {}"));
        assertTrue(service.contains("[ItemMilestoneLog] Admin {} renamed tag {} -> {}"));
        // No read path is shipped in PR-5; AUDIT_VIEW is reserved for a later PR (D-150-137).
        assertFalse(commands.contains("MilestonePermissions.AUDIT_VIEW"));
        assertFalse(service.contains("MilestonePermissions.AUDIT_VIEW"));
        assertFalse(all.contains("TaskKind.AUDIT"));
    }
}
