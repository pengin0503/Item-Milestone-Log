package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for F37 {@link PruneDataLogic} eligibility and last-seen resolution rules. */
class PruneDataLogicTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final int DAYS = 30;
    private static final long OLD_LAST_SEEN = NOW - (DAYS * PruneDataLogic.MILLIS_PER_DAY) - 1L;

    private static PruneDataLogic.PlayerState emptyDefaultState() {
        return new PruneDataLogic.PlayerState(0, 0, true, false, false, false, false, true, true, true);
    }

    @Test
    void eligiblePlayerIsTargeted() {
        assertTrue(PruneDataLogic.isPruneTarget(emptyDefaultState(), false, OLD_LAST_SEEN, NOW, DAYS));
    }

    @Test
    void nonEmptyRecordsTargetsOrCarriedFromCreativeExcludePlayer() {
        assertFalse(PruneDataLogic.isPruneTarget(
                new PruneDataLogic.PlayerState(1, 0, true, false, false, false, false, true, true, true),
                false, OLD_LAST_SEEN, NOW, DAYS));
        assertFalse(PruneDataLogic.isPruneTarget(
                new PruneDataLogic.PlayerState(0, 1, true, false, false, false, false, true, true, true),
                false, OLD_LAST_SEEN, NOW, DAYS));
        assertFalse(PruneDataLogic.isPruneTarget(
                new PruneDataLogic.PlayerState(0, 0, false, false, false, false, false, true, true, true),
                false, OLD_LAST_SEEN, NOW, DAYS));
        assertFalse(PruneDataLogic.isPruneTarget(null, false, OLD_LAST_SEEN, NOW, DAYS));
    }

    @Test
    void persistedFunctionalStateExcludesPlayer() {
        // progressNotified, completedPresets and presetState are functional state: losing them to a
        // prune would silently drop progress thresholds and preset G-C state.
        assertFalse(PruneDataLogic.isPruneTarget(
                new PruneDataLogic.PlayerState(0, 0, true, false, false, false, false, false, true, true),
                false, OLD_LAST_SEEN, NOW, DAYS));
        assertFalse(PruneDataLogic.isPruneTarget(
                new PruneDataLogic.PlayerState(0, 0, true, false, false, false, false, true, false, true),
                false, OLD_LAST_SEEN, NOW, DAYS));
        assertFalse(PruneDataLogic.isPruneTarget(
                new PruneDataLogic.PlayerState(0, 0, true, false, false, false, false, true, true, false),
                false, OLD_LAST_SEEN, NOW, DAYS));
    }

    @Test
    void anyNonDefaultPersonalSettingExcludesPlayer() {
        assertFalse(PruneDataLogic.isPruneTarget(
                new PruneDataLogic.PlayerState(0, 0, true, true, false, false, false, true, true, true),
                false, OLD_LAST_SEEN, NOW, DAYS));
        assertFalse(PruneDataLogic.isPruneTarget(
                new PruneDataLogic.PlayerState(0, 0, true, false, true, false, false, true, true, true),
                false, OLD_LAST_SEEN, NOW, DAYS));
        assertFalse(PruneDataLogic.isPruneTarget(
                new PruneDataLogic.PlayerState(0, 0, true, false, false, true, false, true, true, true),
                false, OLD_LAST_SEEN, NOW, DAYS));
        assertFalse(PruneDataLogic.isPruneTarget(
                new PruneDataLogic.PlayerState(0, 0, true, false, false, false, true, true, true, true),
                false, OLD_LAST_SEEN, NOW, DAYS));
    }

    @Test
    void nonEmptyTargetMetaExcludesPlayerFromPruning() {
        // SF-8: pinned/labelled/priority metadata is functional state even when every other
        // collection is empty; the legacy 10-arg constructor keeps the old "empty" behaviour.
        PruneDataLogic.PlayerState metadataOnly = new PruneDataLogic.PlayerState(
                0, 0, true, false, false, false, false, true, true, true, false);
        assertFalse(PruneDataLogic.isEmptyWithDefaultSettings(metadataOnly));
        assertFalse(PruneDataLogic.isPruneTarget(metadataOnly, false, OLD_LAST_SEEN, NOW, DAYS));
        assertTrue(PruneDataLogic.isEmptyWithDefaultSettings(emptyDefaultState()));
    }

    @Test
    void onlinePlayerIsNeverTargeted() {
        assertFalse(PruneDataLogic.isPruneTarget(emptyDefaultState(), true, OLD_LAST_SEEN, NOW, DAYS));
    }

    @Test
    void lastSeenTimeThresholdBoundariesAndUnknownTimestamp() {
        PruneDataLogic.PlayerState state = emptyDefaultState();
        long exactBoundary = NOW - (DAYS * PruneDataLogic.MILLIS_PER_DAY);

        // Unknown (0), negative, or future timestamps are not targeted
        assertFalse(PruneDataLogic.isPruneTarget(state, false, 0L, NOW, DAYS));
        assertFalse(PruneDataLogic.isPruneTarget(state, false, -1000L, NOW, DAYS));
        assertFalse(PruneDataLogic.isPruneTarget(state, false, NOW, NOW, DAYS));
        assertFalse(PruneDataLogic.isPruneTarget(state, false, NOW + 60_000L, NOW, DAYS));

        // Strict inequality: must be MORE than <days> days ago
        assertFalse(PruneDataLogic.isPruneTarget(state, false, exactBoundary + 1L, NOW, DAYS));
        assertFalse(PruneDataLogic.isPruneTarget(state, false, exactBoundary, NOW, DAYS));
        assertTrue(PruneDataLogic.isPruneTarget(state, false, exactBoundary - 1L, NOW, DAYS));
    }

    @Test
    void respectsMinAndMaxDaysBounds() {
        PruneDataLogic.PlayerState state = emptyDefaultState();
        long oneDayPlusOne = NOW - PruneDataLogic.MILLIS_PER_DAY - 1L;
        long maxDaysPlusOne = NOW - ((long) PruneDataLogic.MAX_DAYS * PruneDataLogic.MILLIS_PER_DAY) - 1L;

        assertTrue(PruneDataLogic.isPruneTarget(state, false, oneDayPlusOne, NOW, PruneDataLogic.MIN_DAYS));
        assertTrue(PruneDataLogic.isPruneTarget(state, false, maxDaysPlusOne, NOW, PruneDataLogic.MAX_DAYS));
        assertFalse(PruneDataLogic.isPruneTarget(state, false, maxDaysPlusOne + 1L, NOW, PruneDataLogic.MAX_DAYS));

        // Out-of-range days defensively return false
        assertFalse(PruneDataLogic.isPruneTarget(state, false, OLD_LAST_SEEN, NOW, 0));
        assertFalse(PruneDataLogic.isPruneTarget(state, false, OLD_LAST_SEEN, NOW, -5));
        assertFalse(PruneDataLogic.isPruneTarget(state, false, 1L, NOW, PruneDataLogic.MAX_DAYS + 1));
    }

    @Test
    void resolveLastSeenPrefersStoredValueAndFallsBackToVanillaDatFile(@TempDir Path playerDataDir) throws IOException {
        UUID uuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
        Path datFile = playerDataDir.resolve(uuid + ".dat");
        Files.writeString(datFile, "dummy");
        long fileMtime = 1_700_000_000_000L;
        Files.setLastModifiedTime(datFile, FileTime.fromMillis(fileMtime));

        // Stored positive lastSeen takes precedence over the .dat file's mtime
        long stored = 1_750_000_000_000L;
        assertEquals(stored, PruneDataLogic.resolveLastSeen(stored, playerDataDir, uuid));

        // Stored 0 (pre-1.1.0 data) falls back to <uuid>.dat mtime
        assertEquals(fileMtime, PruneDataLogic.resolveLastSeen(0L, playerDataDir, uuid));

        // Missing .dat file -> unknown (0)
        UUID missingUuid = UUID.fromString("22222222-2222-2222-2222-222222222222");
        assertEquals(PruneDataLogic.UNKNOWN_LAST_SEEN, PruneDataLogic.resolveLastSeen(0L, playerDataDir, missingUuid));

        // Directory with the same name as <uuid>.dat is ignored (not a regular file)
        UUID dirUuid = UUID.fromString("33333333-3333-3333-3333-333333333333");
        Files.createDirectory(playerDataDir.resolve(dirUuid + ".dat"));
        assertEquals(PruneDataLogic.UNKNOWN_LAST_SEEN, PruneDataLogic.resolveLastSeen(0L, playerDataDir, dirUuid));

        // Null directory or UUID returns unknown (0) when storedLastSeen is 0
        assertEquals(PruneDataLogic.UNKNOWN_LAST_SEEN, PruneDataLogic.resolveLastSeen(0L, null, uuid));
        assertEquals(PruneDataLogic.UNKNOWN_LAST_SEEN, PruneDataLogic.resolveLastSeen(0L, playerDataDir, null));
    }
}
