package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorruptionEvacuationIOTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final Instant INSTANT = Instant.parse("2026-11-25T12:00:00Z");

    @Test
    void copiesSourceAndWritesSummary(@TempDir Path dir) throws IOException {
        Path source = dir.resolve("itemmilestonelog_data.dat");
        byte[] bytes = {1, 2, 3, 4, 5};
        Files.write(source, bytes);
        Path corrupt = dir.resolve("itemmilestonelog/corrupt");

        CorruptionEvacuationIO.Result result = CorruptionEvacuationIO.evacuate(
                source,
                corrupt,
                new CorruptionEvacuationLogic.LoadOutcome(2, false, 1),
                INSTANT,
                UTC);

        assertTrue(result.summaryWritten());
        assertEquals(1, result.retainedCount());
        assertTrue(result.warnings().isEmpty());
        assertEquals("itemmilestonelog_data-20261125-120000.dat",
                result.dataFile().getFileName().toString());
        assertArrayEquals(bytes, Files.readAllBytes(result.dataFile()));
        assertTrue(Files.exists(source), "the source file must never be deleted or renamed");

        String summary = Files.readString(result.summaryFile());
        assertTrue(summary.contains("elementSkips: 2"));
        assertTrue(summary.contains("totalLoadFailure: false"));
        assertTrue(summary.contains("loadedSchemaVersion: 1"));
        assertTrue(summary.contains("sourceFile: itemmilestonelog_data.dat"));
        assertFalse(summary.contains("Alice"));
    }

    @Test
    void totalFailureSummaryHasNoElementSkipsAndUnknownVersion(@TempDir Path dir) throws IOException {
        Path source = dir.resolve("itemmilestonelog_data.dat");
        Files.writeString(source, "not really nbt");
        Path corrupt = dir.resolve("corrupt");

        CorruptionEvacuationIO.Result result = CorruptionEvacuationIO.evacuate(
                source,
                corrupt,
                new CorruptionEvacuationLogic.LoadOutcome(0, true,
                        CorruptionEvacuationLogic.UNKNOWN_SCHEMA_VERSION),
                INSTANT,
                UTC);

        String summary = Files.readString(result.summaryFile());
        assertTrue(summary.contains("elementSkips: 0"));
        assertTrue(summary.contains("totalLoadFailure: true"));
        assertTrue(summary.contains("loadedSchemaVersion: unknown"));
    }

    @Test
    void prunesOnlyOwnedOldEvacuationsAndTheirSummaries(@TempDir Path dir) throws IOException {
        Path source = dir.resolve("itemmilestonelog_data.dat");
        Files.writeString(source, "current");
        Path corrupt = dir.resolve("corrupt");
        Files.createDirectories(corrupt);

        for (int day = 1; day <= 7; day++) {
            String base = "itemmilestonelog_data-2026110" + day + "-120000";
            Files.writeString(corrupt.resolve(base + ".dat"), "old" + day);
            Files.writeString(corrupt.resolve(base + ".txt"), "summary" + day);
        }
        Files.writeString(corrupt.resolve("milestones-20261101-120000.dat"), "other mod data");
        Files.writeString(corrupt.resolve("notes.txt"), "keep me");
        Files.writeString(corrupt.resolve("itemmilestonelog_data-20261101-120000.tmp"), "temp");

        CorruptionEvacuationIO.Result result = CorruptionEvacuationIO.evacuate(
                source,
                corrupt,
                new CorruptionEvacuationLogic.LoadOutcome(1, false, 1),
                INSTANT,
                UTC);

        assertEquals(5, result.retainedCount());
        Set<String> names;
        try (Stream<Path> stream = Files.list(corrupt)) {
            names = stream.map(path -> path.getFileName().toString()).collect(Collectors.toSet());
        }
        // Newest 5 owned data files plus their summaries remain (new + days 4..7).
        for (int day = 4; day <= 7; day++) {
            String base = "itemmilestonelog_data-2026110" + day + "-120000";
            assertTrue(names.contains(base + ".dat"), base + ".dat");
            assertTrue(names.contains(base + ".txt"), base + ".txt");
        }
        assertTrue(names.contains("itemmilestonelog_data-20261125-120000.dat"));
        assertTrue(names.contains("itemmilestonelog_data-20261125-120000.txt"));
        for (int day = 1; day <= 3; day++) {
            String base = "itemmilestonelog_data-2026110" + day + "-120000";
            assertFalse(names.contains(base + ".dat"), base + ".dat");
            assertFalse(names.contains(base + ".txt"), base + ".txt");
        }
        // Non-matching files are never pruned, even if they look similar or are temp files.
        assertTrue(names.contains("milestones-20261101-120000.dat"));
        assertTrue(names.contains("notes.txt"));
        assertTrue(names.contains("itemmilestonelog_data-20261101-120000.tmp"));
        assertEquals(13, names.size());
    }

    @Test
    void collisionWithExistingDataAndSummaryUsesNextSuffix(@TempDir Path dir) throws IOException {
        Path source = dir.resolve("itemmilestonelog_data.dat");
        Files.writeString(source, "current");
        Path corrupt = dir.resolve("corrupt");
        Files.createDirectories(corrupt);
        Files.writeString(corrupt.resolve("itemmilestonelog_data-20261125-120000.dat"), "old");
        Files.writeString(corrupt.resolve("itemmilestonelog_data-20261125-120000.txt"), "old summary");

        CorruptionEvacuationIO.Result result = CorruptionEvacuationIO.evacuate(
                source,
                corrupt,
                new CorruptionEvacuationLogic.LoadOutcome(1, false, 1),
                INSTANT,
                UTC);

        assertEquals("itemmilestonelog_data-20261125-120000-1.dat",
                result.dataFile().getFileName().toString());
        assertEquals("itemmilestonelog_data-20261125-120000-1.txt",
                result.summaryFile().getFileName().toString());
        assertEquals("old", Files.readString(corrupt.resolve("itemmilestonelog_data-20261125-120000.dat")));
    }

    @Test
    void summaryContentIsPlainTextMetadataOnly(@TempDir Path dir) throws IOException {
        Path source = dir.resolve("itemmilestonelog_data.dat");
        Files.writeString(source, "current");
        CorruptionEvacuationIO.Result result = CorruptionEvacuationIO.evacuate(
                source,
                dir.resolve("corrupt"),
                new CorruptionEvacuationLogic.LoadOutcome(0, false, 0),
                INSTANT,
                UTC);

        List<String> lines = Files.readAllLines(result.summaryFile(), StandardCharsets.UTF_8);
        assertTrue(lines.get(0).startsWith("Item Milestone Log"));
        assertTrue(lines.stream().anyMatch(line -> line.equals("elementSkips: 0")));
        assertTrue(lines.stream().anyMatch(line -> line.equals("totalLoadFailure: false")));
        assertTrue(lines.stream().anyMatch(line -> line.equals("loadedSchemaVersion: 0")));
    }

    @Test
    void retainedCountIsUnknownWhenDirectoryCannotBeListed(@TempDir Path dir) throws IOException {
        Path regularFile = dir.resolve("not-a-directory");
        Files.writeString(regularFile, "x");
        java.util.List<String> warnings = new java.util.ArrayList<>();

        assertEquals(-1, CorruptionEvacuationIO.countRetained(regularFile, warnings));
        assertFalse(warnings.isEmpty());
    }

}
