package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MilestoneIoLogicTest {
    @Test
    void submitEligibilityRequiresAcceptingAndRunning() {
        assertTrue(MilestoneIoLogic.canSubmit(true, false));
        assertFalse(MilestoneIoLogic.canSubmit(false, false));
        assertFalse(MilestoneIoLogic.canSubmit(true, true));
        assertFalse(MilestoneIoLogic.canSubmit(false, true));
    }

    @Test
    void importAdmissionRejectsInFlightAndStopping() {
        assertTrue(MilestoneIoLogic.canBeginImport(true, false, false));
        assertFalse(MilestoneIoLogic.canBeginImport(true, false, true));
        assertFalse(MilestoneIoLogic.canBeginImport(false, false, false));
        assertFalse(MilestoneIoLogic.canBeginImport(true, true, false));
    }

    @Test
    void drainDecisionCoversEmptyInTimeAndTimeout() {
        assertEquals(MilestoneIoLogic.DrainDecision.COMPLETE,
                MilestoneIoLogic.drainDecision(0, 0L, 30_000L));
        assertEquals(MilestoneIoLogic.DrainDecision.COMPLETE,
                MilestoneIoLogic.drainDecision(0, 99_999L, 30_000L));
        assertEquals(MilestoneIoLogic.DrainDecision.CONTINUE,
                MilestoneIoLogic.drainDecision(1, 0L, 30_000L));
        assertEquals(MilestoneIoLogic.DrainDecision.CONTINUE,
                MilestoneIoLogic.drainDecision(3, 29_999L, 30_000L));
        assertEquals(MilestoneIoLogic.DrainDecision.TIMEOUT,
                MilestoneIoLogic.drainDecision(1, 30_000L, 30_000L));
        assertEquals(MilestoneIoLogic.DrainDecision.TIMEOUT,
                MilestoneIoLogic.drainDecision(1, 30_001L, 30_000L));
    }

    @Test
    void drainProgressLogsEveryFiveSeconds() {
        assertFalse(MilestoneIoLogic.shouldLogDrainProgress(4_999L, 0L, 5_000L));
        assertTrue(MilestoneIoLogic.shouldLogDrainProgress(5_000L, 0L, 5_000L));
        assertFalse(MilestoneIoLogic.shouldLogDrainProgress(9_999L, 5_000L, 5_000L));
        assertTrue(MilestoneIoLogic.shouldLogDrainProgress(10_000L, 5_000L, 5_000L));
    }

    @Test
    void remainingBudgetNeverNegative() {
        assertEquals(20_000L, MilestoneIoLogic.remainingMillis(10_000L, 30_000L));
        assertEquals(0L, MilestoneIoLogic.remainingMillis(30_000L, 30_000L));
        assertEquals(0L, MilestoneIoLogic.remainingMillis(31_000L, 30_000L));
    }

    @Test
    void finalBackupFallsBackToSyncOnlyAfterTimeout() {
        assertEquals(MilestoneIoLogic.FinalBackupMode.ASYNC,
                MilestoneIoLogic.finalBackupMode(MilestoneIoLogic.DrainDecision.COMPLETE));
        assertEquals(MilestoneIoLogic.FinalBackupMode.ASYNC,
                MilestoneIoLogic.finalBackupMode(MilestoneIoLogic.DrainDecision.CONTINUE));
        assertEquals(MilestoneIoLogic.FinalBackupMode.SYNC,
                MilestoneIoLogic.finalBackupMode(MilestoneIoLogic.DrainDecision.TIMEOUT));
    }

    @Test
    void finalBackupRunsSynchronouslyWhenBudgetIsExhaustedOrTooSmall() {
        // Zero/near-zero remaining budget always means a spurious timeout if submitted (review 2-3).
        assertTrue(MilestoneIoLogic.needsSynchronousFinalBackup(0L));
        assertTrue(MilestoneIoLogic.needsSynchronousFinalBackup(-1L));
        assertTrue(MilestoneIoLogic.needsSynchronousFinalBackup(MilestoneIoLogic.FINAL_BACKUP_MIN_BUDGET_MILLIS));
        assertFalse(MilestoneIoLogic.needsSynchronousFinalBackup(
                MilestoneIoLogic.FINAL_BACKUP_MIN_BUDGET_MILLIS + 1L));
        assertFalse(MilestoneIoLogic.needsSynchronousFinalBackup(30_000L));
    }

    @Test
    void describesTaskKindsForTimeoutWarnings() {
        assertEquals("none", MilestoneIoLogic.describeKinds(Map.of()));
        Map<MilestoneIoLogic.TaskKind, Integer> counts = new LinkedHashMap<>();
        counts.put(MilestoneIoLogic.TaskKind.IMPORT_ALL, 2);
        counts.put(MilestoneIoLogic.TaskKind.BACKUP, 1);
        counts.put(MilestoneIoLogic.TaskKind.EXPORT, 3);
        assertEquals("importall=2, backup=1, export=3", MilestoneIoLogic.describeKinds(counts));
    }
}
