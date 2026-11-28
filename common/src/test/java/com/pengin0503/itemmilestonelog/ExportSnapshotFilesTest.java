package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportSnapshotFilesTest {
    private static final Instant NOW = Instant.parse("2026-11-28T19:00:00Z");

    @Test
    void timestampUsesTheStrictServerPattern() {
        assertEquals("20261128-190000", ExportSnapshotFiles.formatTimestamp(NOW, ZoneOffset.UTC));
    }

    @Test
    void snapshotNamesUseTheDedicatedPrefixAndSecondSuffix() {
        String first = ExportSnapshotFiles.chooseFileName("all-players.json", "20261128-190000", List.of());
        assertEquals("snapshot-all-players-20261128-190000.json", first);
        assertEquals("snapshot-Bob-uuid-20261128-190000.html",
                ExportSnapshotFiles.chooseFileName("Bob-uuid.html", "20261128-190000", List.of()));
        assertEquals("snapshot-all-players-20261128-190000-1.json",
                ExportSnapshotFiles.chooseFileName("all-players.json", "20261128-190000", List.of(first)));
        assertEquals("snapshot-all-players-20261128-190000-2.json", ExportSnapshotFiles.chooseFileName(
                "all-players.json", "20261128-190000", List.of(first,
                        "snapshot-all-players-20261128-190000-1.json")));
    }

    @Test
    void onlyPatternMatchingFilesAreRecognised() {
        assertTrue(ExportSnapshotFiles.isSnapshotFileName("snapshot-all-players-20261128-190000.json"));
        assertTrue(ExportSnapshotFiles.isSnapshotFileName("snapshot-all-players-20261128-190000-3.csv"));
        assertFalse(ExportSnapshotFiles.isSnapshotFileName("all-players.json"));
        assertFalse(ExportSnapshotFiles.isSnapshotFileName("all-players.html"));
        assertFalse(ExportSnapshotFiles.isSnapshotFileName("Bob-uuid.json"));
        assertFalse(ExportSnapshotFiles.isSnapshotFileName("my-notes.txt"));
        assertFalse(ExportSnapshotFiles.isSnapshotFileName("snapshot-all-players-20261328-190000.json"));
    }

    @Test
    void pruneDeletesOnlyTheOldestPatternMatchingFiles() {
        List<String> names = List.of(
                "all-players.json",
                "all-players.html",
                "Bob-uuid.json",
                "my-notes-20260101-000000.txt",
                "snapshot-all-players-20261120-120000.json",
                "snapshot-all-players-20261121-120000.json",
                "snapshot-all-players-20261122-120000.json",
                "snapshot-all-players-20261123-120000.json",
                "snapshot-all-players-20261124-120000.json",
                "snapshot-all-players-20261125-120000.json",
                "snapshot-Bob-uuid-20261125-120000-1.html");
        List<String> pruned = ExportSnapshotFiles.selectFilesToPrune(names, 5);
        assertEquals(List.of("snapshot-all-players-20261120-120000.json",
                "snapshot-all-players-20261121-120000.json"), pruned);
        assertTrue(pruned.stream().allMatch(ExportSnapshotFiles::isSnapshotFileName));
    }

    @Test
    void pruneClampsGenerationsToTheConstantRange() {
        assertEquals(1, ExportSnapshotFiles.clampGenerations(0));
        assertEquals(20, ExportSnapshotFiles.clampGenerations(100));
        assertEquals(5, ExportSnapshotFiles.DEFAULT_GENERATIONS);
        assertEquals(List.of(), ExportSnapshotFiles.selectFilesToPrune(
                List.of("all-players.json", "snapshot-all-players-20261128-190000.json"), 5));
    }

    @Test
    void equalSecondsAreOrderedByNumericSuffix() {
        List<String> names = List.of(
                "snapshot-all-players-20261128-190000-10.json",
                "snapshot-all-players-20261128-190000-2.json",
                "snapshot-all-players-20261128-190000.json");
        assertEquals(List.of("snapshot-all-players-20261128-190000.json"),
                ExportSnapshotFiles.selectFilesToPrune(names, 2));
    }
}
