package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #150: pins the stop-time ordering in {@link MilestoneServerStop}. There is no unit harness for a
 * live {@code MinecraftServer} shutdown, so the fixed call sequence is pinned against the source
 * of the class under test (the same source Gradle's test task exposes to the other source-reading
 * tests via {@code itemmilestonelog.mainSourceDir}).
 */
class MilestoneServerStopOrderTest {
    private static final String STOP_SOURCE_REL =
            "com/pengin0503/itemmilestonelog/MilestoneServerStop.java";

    private static Path resolveStopSource() {
        String mainSourceDir = System.getProperty("itemmilestonelog.mainSourceDir");
        if (mainSourceDir != null && !mainSourceDir.isBlank()) {
            Path cur = Path.of(mainSourceDir).toAbsolutePath().normalize();
            for (int i = 0; i < 6 && cur != null; i++) {
                Path candidate = cur.resolve(STOP_SOURCE_REL);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
                cur = cur.getParent();
            }
        }
        Path fromVersionDir = Path.of("src", "main", "java").resolve(STOP_SOURCE_REL);
        if (Files.isRegularFile(fromVersionDir)) {
            return fromVersionDir;
        }
        Path fromRunDir = Path.of("..", "src", "main", "java").resolve(STOP_SOURCE_REL);
        if (Files.isRegularFile(fromRunDir)) {
            return fromRunDir;
        }
        throw new AssertionError("MilestoneServerStop.java source not found; "
                + "set -Ditemmilestonelog.mainSourceDir=<version>/src/main/java");
    }

    private static int firstOf(String source, String... needles) {
        int first = -1;
        for (String needle : needles) {
            int at = source.indexOf(needle);
            if (at >= 0 && (first < 0 || at < first)) {
                first = at;
            }
        }
        return first;
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    @Test
    void rewardCommandsRunBeforeTheFinalBackupAndNotificationsAfterIt() throws IOException {
        String src = Files.readString(resolveStopSource());

        int guard = src.indexOf("MilestoneBackup.beginStopping()");
        int drain = src.indexOf("queue.awaitDrain(");
        int rewards = src.indexOf("MilestoneCommandRunner.runAllOnStop(server)");
        int applyPending = src.indexOf("MilestoneImportAll.applyPendingOnStop(server)");
        int finalBackup = firstOf(src,
                "MilestoneBackup.writeFinalBackupNow(server)",
                "MilestoneBackup.prepareFinalBackup(server)",
                "MilestoneBackup.writePreparedFinalBackupNow(finalBackup)");
        int notifications = src.indexOf("Tracker.flushPendingOnStop(server)");
        int clearPending = src.indexOf("MilestoneCommandRunner.clearPending()");

        assertTrue(guard >= 0, "the #106 duplicate-backup guard must still be first");
        assertTrue(drain >= 0, "the bounded I/O drain (F-140-4) must still run");
        assertTrue(rewards >= 0, "#150 reward commands must still be flushed on stop");
        assertTrue(applyPending >= 0, "a parsed importall must still be applied before the backup");
        assertTrue(finalBackup >= 0, "a final backup must still be written");
        assertTrue(notifications >= 0, "pending notifications must still be delivered while online");
        assertTrue(clearPending >= 0, "pending reward commands must still be cleared");

        assertTrue(guard < drain, "the #106 guard must precede the drain");
        assertTrue(drain < rewards, "the first drain must not consume the reward-command budget");
        assertTrue(rewards < applyPending, "reward commands must run before the final-backup apply");
        assertTrue(applyPending < finalBackup, "the importall result must be in the final backup");
        assertTrue(finalBackup < notifications, "notifications stay in the tail block (#150)");
        assertTrue(rewards < clearPending, "reward commands must be flushed before they are cleared");

        assertEquals(1, occurrences(src, "MilestoneCommandRunner.runAllOnStop(server)"),
                "reward commands must be flushed exactly once, before the snapshot");
    }
}
