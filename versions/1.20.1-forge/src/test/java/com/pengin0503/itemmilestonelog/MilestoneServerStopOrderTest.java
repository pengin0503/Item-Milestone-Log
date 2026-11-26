package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void startingClearsImportStateBeforeCreatingTheFreshExecutor() throws IOException {
        String src = Files.readString(resolveStopSource());
        int reset = src.indexOf("MilestoneImportAll.resetState()");
        int start = src.indexOf("MilestoneIo.start(");
        assertTrue(reset >= 0, "a stale importall must be cleared at server start");
        assertTrue(start > reset, "the fresh executor must be created after clearing stale state");
        int stopped = src.indexOf("public static void onServerStopped()");
        assertTrue(stopped > start, "ServerStopped must reset the session state again");
    }

    @Test
    void timeoutPathShutsDownBeforeApplyingAndCancellingThenWritesTheBackupSync() throws IOException {
        String src = Files.readString(resolveStopSource());
        int timeout = src.indexOf("if (timedOut) {");
        assertTrue(timeout >= 0, "the timeout branch must exist");
        int branchEnd = src.indexOf("} else {", timeout);
        assertTrue(branchEnd > timeout);
        String branch = src.substring(timeout, branchEnd);
        assertTrue(branch.contains("queue.shutdownNow()"), "timeout must interrupt unfinished I/O work");
        assertTrue(branch.contains("MilestoneImportAll.applyPendingOnStop(server)"),
                "a parsed importall must be applied even on the timeout path");
        assertTrue(branch.contains("MilestoneImportAll.cancelPending("),
                "an unfinished importall must be abandoned on timeout");
        assertTrue(branch.contains("MilestoneBackup.writeFinalBackupNow(server)"),
                "the final backup must be written synchronously on timeout");
        assertFalse(branch.contains("MilestoneIo.submitFinal"),
                "no task may be submitted to an executor that was just shut down");
        assertTrue(branch.indexOf("queue.shutdownNow()") < branch.indexOf("MilestoneImportAll.applyPendingOnStop(server)"));
        assertTrue(branch.indexOf("MilestoneImportAll.applyPendingOnStop(server)")
                < branch.indexOf("MilestoneImportAll.cancelPending("));
        assertTrue(branch.indexOf("MilestoneImportAll.cancelPending(")
                < branch.indexOf("MilestoneBackup.writeFinalBackupNow(server)"));
    }

    @Test
    void insufficientBudgetSkipsSubmissionAndSerializesOnceSynchronously() throws IOException {
        String src = Files.readString(resolveStopSource());
        int guard = src.indexOf("MilestoneIoLogic.needsSynchronousFinalBackup(remaining)");
        int sync = src.indexOf("MilestoneBackup.writeFinalBackupNow(server)", guard);
        int prepare = src.indexOf("MilestoneBackup.prepareFinalBackup(server)");
        int submit = src.indexOf("MilestoneIo.submitFinal(finalBackup)");
        assertTrue(guard >= 0 && sync > guard, "the remaining-budget check must guard the sync fallback");
        assertTrue(sync < prepare && sync < submit,
                "an exhausted budget must skip snapshot preparation and submission entirely");
        assertTrue(submit > prepare, "the async path still prepares the snapshot before submitting");
    }

    @Test
    void finalDrainTimeoutShutsDownAndFallsBackToASyncBackup() throws IOException {
        String src = Files.readString(resolveStopSource());
        int finalDrain = src.indexOf("queue.awaitDrain(finalBudget");
        assertTrue(finalDrain >= 0, "the prepared final backup must be included in a bounded final drain");
        int recheck = src.indexOf("MilestoneIoLogic.needsSynchronousFinalBackup(finalBudget)");
        assertTrue(recheck > 0 && recheck < finalDrain, "the budget must be re-checked after preparing the snapshot");
        int shutdown = src.indexOf("queue.shutdownNow()", finalDrain);
        int sync = src.indexOf("MilestoneBackup.writeFinalBackupNow(server)", finalDrain);
        assertTrue(shutdown > finalDrain && sync > shutdown,
                "a final-drain timeout must stop the executor before the synchronous backup");
        int prepared = src.indexOf("MilestoneBackup.writePreparedFinalBackupNow(finalBackup)");
        int submit = src.indexOf("MilestoneIo.submitFinal(finalBackup)");
        assertTrue(prepared > submit && prepared < finalDrain,
                "the prepared snapshot must be serialized exactly once, before the final drain");
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
