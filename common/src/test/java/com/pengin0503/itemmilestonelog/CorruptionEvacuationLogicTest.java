package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorruptionEvacuationLogicTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final Instant INSTANT = Instant.parse("2026-11-25T12:00:00Z");

    @Test
    void cleanLoadDoesNotEvacuate() {
        assertFalse(CorruptionEvacuationLogic.shouldEvacuate(0, false));
        assertFalse(new CorruptionEvacuationLogic.LoadOutcome(0, false, 1).requiresEvacuation());
    }

    @Test
    void oneOrMoreElementSkipsEvacuate() {
        assertTrue(CorruptionEvacuationLogic.shouldEvacuate(1, false));
        assertTrue(CorruptionEvacuationLogic.shouldEvacuate(17, false));
        assertTrue(new CorruptionEvacuationLogic.LoadOutcome(1, false, 1).requiresEvacuation());
    }

    @Test
    void totalFailureEvacuatesEvenWithoutElementSkips() {
        assertTrue(CorruptionEvacuationLogic.shouldEvacuate(0, true));
        assertTrue(new CorruptionEvacuationLogic.LoadOutcome(0, true, 0).requiresEvacuation());
    }

    @Test
    void unreadableExistingFileIsATotalFailureEvenWhenLoadAppearsCached() {
        // An existing-but-unreadable file is a total failure by definition. This must hold even
        // when loadFailed is false because another listener already populated MilestoneData with a
        // cached empty instance (agy review 4-1) and no load report is available.
        assertTrue(CorruptionEvacuationLogic.isTotalLoadFailure(true, false, false));
        assertTrue(CorruptionEvacuationLogic.isTotalLoadFailure(true, true, false));
        assertTrue(CorruptionEvacuationLogic.isTotalLoadFailure(true, false, true));
        assertTrue(CorruptionEvacuationLogic.isTotalLoadFailure(false, true, false));
        assertTrue(CorruptionEvacuationLogic.isTotalLoadFailure(false, false, true));
        assertFalse(CorruptionEvacuationLogic.isTotalLoadFailure(false, false, false));
    }

    @Test
    void loadOutcomeRejectsInvalidCounts() {
        assertThrows(IllegalArgumentException.class,
                () -> new CorruptionEvacuationLogic.LoadOutcome(-1, false, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new CorruptionEvacuationLogic.LoadOutcome(0, false, -2));
    }

    @Test
    void fileNameUsesDisplayTimeZoneAndExpectedPrefix() {
        assertEquals("itemmilestonelog_data-20261125-120000",
                CorruptionEvacuationLogic.formatBaseName(INSTANT, UTC));
        assertEquals("itemmilestonelog_data-20261125-120000.dat",
                CorruptionEvacuationLogic.chooseDataFileName(INSTANT, UTC, name -> false));
    }

    @Test
    void sameSecondCollisionsUseNumericSuffixAndRespectSummaryFiles() {
        String base = "itemmilestonelog_data-20261125-120000";
        assertEquals(base + "-1.dat", CorruptionEvacuationLogic.chooseDataFileName(
                INSTANT, UTC, name -> name.equals(base + ".dat")));
        assertEquals(base + "-2.dat", CorruptionEvacuationLogic.chooseDataFileName(
                INSTANT, UTC, name -> name.equals(base + ".dat") || name.equals(base + "-1.dat")));
        // A leftover summary alone must also force a new suffix so it is not overwritten.
        assertEquals(base + "-1.dat", CorruptionEvacuationLogic.chooseDataFileName(
                INSTANT, UTC, name -> name.equals(base + ".txt")));
    }

    @Test
    void filePatternsAcceptOnlyOwnedFiles() {
        assertTrue(CorruptionEvacuationLogic.isEvacuationDataFileName(
                "itemmilestonelog_data-20261125-120000.dat"));
        assertTrue(CorruptionEvacuationLogic.isEvacuationDataFileName(
                "itemmilestonelog_data-20261125-120000-12.dat"));
        assertFalse(CorruptionEvacuationLogic.isEvacuationDataFileName(
                "milestones-20261125-120000.dat"));
        assertFalse(CorruptionEvacuationLogic.isEvacuationDataFileName(
                "itemmilestonelog_data-20261125-120000.txt"));
        assertTrue(CorruptionEvacuationLogic.isEvacuationSummaryFileName(
                "itemmilestonelog_data-20261125-120000.txt"));
        assertFalse(CorruptionEvacuationLogic.isEvacuationSummaryFileName("notes.txt"));
        assertEquals("itemmilestonelog_data-20261125-120000.txt",
                CorruptionEvacuationLogic.summaryFileName("itemmilestonelog_data-20261125-120000.dat"));
    }

    @Test
    void pruneSelectsOldestMatchingDataFilesOnly() {
        List<String> names = List.of(
                "itemmilestonelog_data-20261101-120000.dat",
                "itemmilestonelog_data-20261102-120000.dat",
                "itemmilestonelog_data-20261103-120000.dat",
                "itemmilestonelog_data-20261104-120000.dat",
                "itemmilestonelog_data-20261105-120000.dat",
                "itemmilestonelog_data-20261106-120000.dat",
                "itemmilestonelog_data-20261107-120000.dat",
                "itemmilestonelog_data-20261101-120000.txt",
                "milestones-20261101-120000.dat",
                "notes.txt");

        assertEquals(List.of(
                        "itemmilestonelog_data-20261101-120000.dat",
                        "itemmilestonelog_data-20261102-120000.dat"),
                CorruptionEvacuationLogic.selectDataFilesToPrune(names, 5));
        assertTrue(CorruptionEvacuationLogic.selectDataFilesToPrune(names, 7).isEmpty());
        assertTrue(CorruptionEvacuationLogic.selectDataFilesToPrune(names, 20).isEmpty());
    }

    @Test
    void parseTimestampUsesZoneAndRejectsNonMatchingNames() {
        assertEquals(INSTANT.toEpochMilli(),
                CorruptionEvacuationLogic.parseTimestampMillis(
                        "itemmilestonelog_data-20261125-120000.dat", UTC));
        assertEquals(-1L, CorruptionEvacuationLogic.parseTimestampMillis(
                "itemmilestonelog_data-20261325-120000.dat", UTC));
        assertEquals(-1L, CorruptionEvacuationLogic.parseTimestampMillis("notes.txt", UTC));
    }

    @Test
    void summaryContainsMetadataButNoPlayerOrRecordData() {
        String summary = CorruptionEvacuationLogic.formatSummary(
                new CorruptionEvacuationLogic.LoadOutcome(3, false, 1),
                "itemmilestonelog_data.dat",
                INSTANT,
                UTC);

        assertTrue(summary.contains("evacuatedAt: 2026-11-25 12:00:00 Z"));
        assertTrue(summary.contains("sourceFile: itemmilestonelog_data.dat"));
        assertTrue(summary.contains("elementSkips: 3"));
        assertTrue(summary.contains("totalLoadFailure: false"));
        assertTrue(summary.contains("loadedSchemaVersion: 1"));
        assertFalse(summary.contains("Alice"));
        assertFalse(summary.contains("minecraft:diamond"));
    }

    @Test
    void summaryRendersUnknownSchemaVersionForUnreadableFile() {
        String summary = CorruptionEvacuationLogic.formatSummary(
                new CorruptionEvacuationLogic.LoadOutcome(0, true,
                        CorruptionEvacuationLogic.UNKNOWN_SCHEMA_VERSION),
                "itemmilestonelog_data.dat",
                INSTANT,
                UTC);
        assertTrue(summary.contains("totalLoadFailure: true"));
        assertTrue(summary.contains("loadedSchemaVersion: unknown"));
    }

    @Test
    void comparatorHandlesSuffixedNamesNumerically() {
        List<String> names = List.of(
                "itemmilestonelog_data-20261125-120000-10.dat",
                "itemmilestonelog_data-20261125-120000-2.dat",
                "itemmilestonelog_data-20261125-120000.dat");
        List<String> sorted = names.stream()
                .sorted(CorruptionEvacuationLogic.EVACUATION_NAME_ORDER)
                .toList();
        assertEquals(List.of(
                "itemmilestonelog_data-20261125-120000.dat",
                "itemmilestonelog_data-20261125-120000-2.dat",
                "itemmilestonelog_data-20261125-120000-10.dat"), sorted);
    }

    @Test
    void maxEvacuationsIsFiveAndUnknownSchemaIsMinusOne() {
        assertEquals(5, CorruptionEvacuationLogic.MAX_EVACUATIONS);
        assertEquals(-1, CorruptionEvacuationLogic.UNKNOWN_SCHEMA_VERSION);
    }
}
