package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MilestoneBackupLogicTest {
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    @Test
    void backupFileNamePatternMatchesOnlyOwnedBackupFiles() {
        // Valid names
        assertTrue(MilestoneBackupLogic.isBackupFileName("milestones-20261124-111530.dat"));
        assertTrue(MilestoneBackupLogic.isBackupFileName("milestones-20261124-111530-1.dat"));
        assertTrue(MilestoneBackupLogic.isBackupFileName("milestones-20261124-111530-2.dat"));
        assertTrue(MilestoneBackupLogic.isBackupFileName("milestones-20261124-111530-123.dat"));
        assertTrue(MilestoneBackupLogic.isBackupFileName("milestones-19990101-000000.dat"));

        // Invalid / user-owned names that pruning must never touch
        assertFalse(MilestoneBackupLogic.isBackupFileName(null));
        assertFalse(MilestoneBackupLogic.isBackupFileName(""));
        assertFalse(MilestoneBackupLogic.isBackupFileName("notes.txt"));
        assertFalse(MilestoneBackupLogic.isBackupFileName("keep-this.dat"));
        assertFalse(MilestoneBackupLogic.isBackupFileName("milestones.dat"));
        assertFalse(MilestoneBackupLogic.isBackupFileName("milestones-20261124-111530.dat.tmp"));
        assertFalse(MilestoneBackupLogic.isBackupFileName("milestones-20261124-111530.dat.bak"));
        assertFalse(MilestoneBackupLogic.isBackupFileName(".milestones-20261124-111530.dat.tmp"));
        assertFalse(MilestoneBackupLogic.isBackupFileName("milestones-20261124-111530-.dat"));
        assertFalse(MilestoneBackupLogic.isBackupFileName("milestones-20261124-111530-abc.dat"));
        assertFalse(MilestoneBackupLogic.isBackupFileName("milestones-20261124-111530-1-2.dat"));
        assertFalse(MilestoneBackupLogic.isBackupFileName("milestones-2026112-111530.dat")); // 7 date digits
        assertFalse(MilestoneBackupLogic.isBackupFileName("milestones-202611240-111530.dat")); // 9 date digits
        assertFalse(MilestoneBackupLogic.isBackupFileName("milestones-20261124-11153.dat")); // 5 time digits
        assertFalse(MilestoneBackupLogic.isBackupFileName("milestones-20261124-1115300.dat")); // 7 time digits
        assertFalse(MilestoneBackupLogic.isBackupFileName("manual-milestones-20261124-111530.dat"));
        assertFalse(MilestoneBackupLogic.isBackupFileName("milestones-20261124-111530.DAT"));
        assertFalse(MilestoneBackupLogic.isBackupFileName("milestones-20261124_111530.dat"));
    }

    @Test
    void chooseFileNameHandlesSameSecondCollisionsWithSequentialSuffixes() {
        Instant instant = ZonedDateTime.of(2026, 11, 24, 11, 15, 30, 0, UTC).toInstant();
        Set<String> existing = new HashSet<>();

        String first = MilestoneBackupLogic.chooseFileName(instant, UTC, existing::contains);
        assertEquals("milestones-20261124-111530.dat", first);
        existing.add(first);

        String second = MilestoneBackupLogic.chooseFileName(instant, UTC, existing::contains);
        assertEquals("milestones-20261124-111530-1.dat", second);
        existing.add(second);

        String third = MilestoneBackupLogic.chooseFileName(instant, UTC, existing::contains);
        assertEquals("milestones-20261124-111530-2.dat", third);
        existing.add(third);

        // Respects timezone formatting
        String tokyoName = MilestoneBackupLogic.chooseFileName(instant, TOKYO, name -> false);
        assertEquals("milestones-20261124-201530.dat", tokyoName);
    }

    @Test
    void parsesTimestampsAndFindsNewestBackupAcrossMatchingFilesOnly() {
        long t1 = ZonedDateTime.of(2026, 11, 24, 10, 0, 0, 0, UTC).toInstant().toEpochMilli();
        long t2 = ZonedDateTime.of(2026, 11, 24, 11, 15, 30, 0, UTC).toInstant().toEpochMilli();

        assertEquals(t1, MilestoneBackupLogic.parseTimestampMillis("milestones-20261124-100000.dat", UTC));
        assertEquals(t2, MilestoneBackupLogic.parseTimestampMillis("milestones-20261124-111530-2.dat", UTC));
        assertEquals(MilestoneBackupLogic.NO_BACKUP_RECORDED,
                MilestoneBackupLogic.parseTimestampMillis("notes.txt", UTC));
        // Invalid calendar date (Feb 30) and invalid hour (25) are ignored safely
        assertEquals(MilestoneBackupLogic.NO_BACKUP_RECORDED,
                MilestoneBackupLogic.parseTimestampMillis("milestones-20260230-120000.dat", UTC));
        assertEquals(MilestoneBackupLogic.NO_BACKUP_RECORDED,
                MilestoneBackupLogic.parseTimestampMillis("milestones-20261124-250000.dat", UTC));

        // Empty or non-matching directory -> NO_BACKUP_RECORDED (first save allowed)
        assertEquals(MilestoneBackupLogic.NO_BACKUP_RECORDED,
                MilestoneBackupLogic.findNewestBackupTimeMillis(List.of(), UTC));
        assertEquals(MilestoneBackupLogic.NO_BACKUP_RECORDED,
                MilestoneBackupLogic.findNewestBackupTimeMillis(List.of("manual.dat", "readme.txt"), UTC));

        // Picks newest valid backup even when non-matching or invalid-date names exist alongside
        List<String> mixed = List.of(
                "milestones-20261124-100000.dat",
                "milestones-20999999-999999.dat",
                "milestones-20261124-111530-1.dat",
                "user-backup-20991231-235959.dat"
        );
        assertEquals(t2, MilestoneBackupLogic.findNewestBackupTimeMillis(mixed, UTC));
    }

    @Test
    void selectFilesToPruneDeletesOldestMatchingOnlyAndOrdersCollisionSuffixesNumerically() {
        List<String> files = List.of(
                "keep-me.dat",
                "milestones-20261124-100000.dat",
                "milestones-20261124-111530-10.dat",
                "milestones-20261124-111530-2.dat",
                "milestones-20261124-111530-1.dat",
                "milestones-20261124-111530.dat",
                "milestones-20261124-111530.dat.bak",
                "notes.txt",
                "milestones-20261124-120000.dat"
        );

        // 6 matching files; keep 3 newest: 111530-2, 111530-10, 120000
        List<String> toDelete = MilestoneBackupLogic.selectFilesToPrune(files, 3);
        assertEquals(List.of(
                "milestones-20261124-100000.dat",
                "milestones-20261124-111530.dat",
                "milestones-20261124-111530-1.dat"
        ), toDelete);

        // Keep 6 or more -> nothing pruned (non-matching files do not count toward generations)
        assertTrue(MilestoneBackupLogic.selectFilesToPrune(files, 6).isEmpty());
        assertTrue(MilestoneBackupLogic.selectFilesToPrune(files, 20).isEmpty());

        // Keep 1 -> deletes 5 oldest matching, keeps 120000 and all non-matching files
        List<String> keepOne = MilestoneBackupLogic.selectFilesToPrune(files, 1);
        assertEquals(List.of(
                "milestones-20261124-100000.dat",
                "milestones-20261124-111530.dat",
                "milestones-20261124-111530-1.dat",
                "milestones-20261124-111530-2.dat",
                "milestones-20261124-111530-10.dat"
        ), keepOne);
    }

    @Test
    void rateLimitAllowsFirstBackupAndRequiresOneFullHourBetweenAutomaticSaves() {
        long t0 = 1_795_600_000_000L;
        assertTrue(MilestoneBackupLogic.isRateLimitElapsed(MilestoneBackupLogic.NO_BACKUP_RECORDED, t0));
        assertFalse(MilestoneBackupLogic.isRateLimitElapsed(t0, t0));
        assertFalse(MilestoneBackupLogic.isRateLimitElapsed(t0, t0 + MilestoneBackupLogic.MIN_INTERVAL_MILLIS - 1L));
        assertTrue(MilestoneBackupLogic.isRateLimitElapsed(t0, t0 + MilestoneBackupLogic.MIN_INTERVAL_MILLIS));
        assertTrue(MilestoneBackupLogic.isRateLimitElapsed(t0, t0 + MilestoneBackupLogic.MIN_INTERVAL_MILLIS + 60_000L));
        // Clock moving backwards stays rate-limited
        assertFalse(MilestoneBackupLogic.isRateLimitElapsed(t0, t0 - 1_000L));
    }
}
