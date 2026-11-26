package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-Java tests for the PR-1 command analysis model (F-150-19/21/22/23/27/32/35/36). */
class CommandAnalysisModelTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final long NOW = Instant.parse("2026-01-10T12:00:00Z").toEpochMilli();

    private static CommandAnalysisModel.RecordValue rec(int n, int ticks, long day, long epoch) {
        return new CommandAnalysisModel.RecordValue("minecraft:item_" + n, 1, ticks, day, epoch);
    }

    private static CommandAnalysisModel.TargetRef key(String id, int count) {
        return new CommandAnalysisModel.TargetRef(id, count);
    }

    // ------------------------------------------------------------- F-150-21

    @Test
    void sortRecordsRecentReproducesTheExistingPlayTicksThenKeyOrder() {
        List<CommandAnalysisModel.RecordValue> input = List.of(
                rec(2, 50, 3L, 100), rec(1, 50, 2L, 200), rec(3, 10, 1L, 0));
        List<CommandAnalysisModel.RecordValue> sorted =
                CommandAnalysisModel.sortRecords(input, CommandAnalysisModel.RecordSort.RECENT);
        assertEquals(List.of("minecraft:item_3", "minecraft:item_1", "minecraft:item_2"),
                sorted.stream().map(CommandAnalysisModel.RecordValue::itemId).toList());
        assertEquals(sorted, CommandAnalysisModel.sortRecords(input, null),
                "null sort must not change the legacy order");
    }

    @Test
    void sortRecordsNameUsesCaseInsensitiveThenCaseSensitiveThenKey() {
        List<CommandAnalysisModel.RecordValue> input = List.of(
                new CommandAnalysisModel.RecordValue("minecraft:beta", 2, 1, 0L, 0L),
                new CommandAnalysisModel.RecordValue("minecraft:Alpha", 1, 2, 0L, 0L),
                new CommandAnalysisModel.RecordValue("minecraft:alpha", 1, 3, 0L, 0L));
        List<CommandAnalysisModel.RecordValue> sorted =
                CommandAnalysisModel.sortRecords(input, CommandAnalysisModel.RecordSort.NAME);
        assertEquals(List.of("minecraft:Alpha", "minecraft:alpha", "minecraft:beta"),
                sorted.stream().map(CommandAnalysisModel.RecordValue::itemId).toList());
    }

    @Test
    void sortRecordsDayUsesDayThenPlayTicksThenKey() {
        List<CommandAnalysisModel.RecordValue> input = List.of(
                rec(3, 5, 2L, 0L), rec(1, 30, 1L, 10L), rec(2, 10, 1L, 0L));
        List<CommandAnalysisModel.RecordValue> sorted =
                CommandAnalysisModel.sortRecords(input, CommandAnalysisModel.RecordSort.DAY);
        assertEquals(List.of("minecraft:item_2", "minecraft:item_1", "minecraft:item_3"),
                sorted.stream().map(CommandAnalysisModel.RecordValue::itemId).toList());
        assertTrue(CommandAnalysisModel.sortRecords(List.of(), CommandAnalysisModel.RecordSort.DAY).isEmpty());
    }

    @Test
    void sortParsingAcceptsOnlyThreeValuesAndFilteringIsAppliedBeforeTheSort() {
        assertEquals(CommandAnalysisModel.RecordSort.DAY, CommandAnalysisModel.RecordSort.parse("DAY"));
        assertNull(CommandAnalysisModel.RecordSort.parse("count"));
        List<CommandAnalysisModel.RecordValue> filtered = List.of(rec(2, 1, 0L, 0L), rec(1, 99, 0L, 0L));
        assertEquals(List.of("minecraft:item_2", "minecraft:item_1"),
                CommandAnalysisModel.sortRecords(filtered, CommandAnalysisModel.RecordSort.RECENT)
                        .stream().map(CommandAnalysisModel.RecordValue::itemId).toList());
    }

    // ------------------------------------------------------------- F-150-27

    @Test
    void compactKeysDeduplicatesAndKeepsTheStableTargetOrder() {
        List<CommandAnalysisModel.TargetRef> input = List.of(
                key("minecraft:b", 1), key("minecraft:a", 3), key("minecraft:b", 1), key("minecraft:a", 1));
        assertEquals(List.of(key("minecraft:a", 1), key("minecraft:a", 3), key("minecraft:b", 1)),
                CommandAnalysisModel.compactKeys(input));
        assertTrue(CommandAnalysisModel.compactKeys(List.of()).isEmpty());
    }

    // ------------------------------------------------------------- F-150-22

    @Test
    void settingsSnapshotListsTheFourLegacyFlagsInOrder() {
        List<CommandAnalysisModel.SettingLine> lines = CommandAnalysisModel.settingsSnapshot(true, false, true, false);
        assertEquals(List.of("broadcast", "progress", "discord", "privacy"),
                lines.stream().map(CommandAnalysisModel.SettingLine::name).toList());
        assertEquals(List.of(true, false, true, false),
                lines.stream().map(CommandAnalysisModel.SettingLine::enabled).toList());
        assertEquals(List.of(false, false, false, false),
                CommandAnalysisModel.settingsSnapshot(false, false, false, false)
                        .stream().map(CommandAnalysisModel.SettingLine::enabled).toList());
    }

    @Test
    void settingsToggleParsingAndDiscordGate() {
        assertEquals(CommandAnalysisModel.Toggle.DISCORD, CommandAnalysisModel.Toggle.parse("discord"));
        assertNull(CommandAnalysisModel.Toggle.parse("unknown"));
        assertEquals(Boolean.TRUE, CommandAnalysisModel.parseSettingValue("on"));
        assertEquals(Boolean.FALSE, CommandAnalysisModel.parseSettingValue("OFF"));
        assertNull(CommandAnalysisModel.parseSettingValue("maybe"));
        assertFalse(CommandAnalysisModel.settingsToggleAllowed(CommandAnalysisModel.Toggle.DISCORD, false));
        assertTrue(CommandAnalysisModel.settingsToggleAllowed(CommandAnalysisModel.Toggle.DISCORD, true));
        assertTrue(CommandAnalysisModel.settingsToggleAllowed(CommandAnalysisModel.Toggle.PRIVACY, false));
    }

    // ------------------------------------------------------------- F-150-19

    @Test
    void nearTargetsRespectsThresholdBoundariesAndProgressOrder() {
        List<CommandAnalysisModel.NearCandidate> candidates = List.of(
                new CommandAnalysisModel.NearCandidate("minecraft:a", 100, 49, false),
                new CommandAnalysisModel.NearCandidate("minecraft:b", 100, 50, false),
                new CommandAnalysisModel.NearCandidate("#minecraft:tag", 50, 25, true),
                new CommandAnalysisModel.NearCandidate("minecraft:d", 1, 1, false));
        CommandAnalysisModel.NearResult result = CommandAnalysisModel.nearTargets(candidates, 50);
        assertEquals(List.of("#minecraft:tag", "minecraft:b"),
                result.entries().stream().map(CommandAnalysisModel.NearEntry::itemId).toList());
        assertEquals(List.of(50, 50), result.entries().stream()
                .map(CommandAnalysisModel.NearEntry::progressPercent).toList());
        assertTrue(result.entries().get(0).isTag());
        assertEquals(0, result.moreCount());
        assertEquals(0, CommandAnalysisModel.nearTargets(candidates, 51).entries().size(),
                "both 50%-complete targets fall below a threshold of 51");
        assertTrue(CommandAnalysisModel.nearTargets(candidates, 99).entries().isEmpty());
    }

    @Test
    void nearTargetsRejectsInvalidThresholdsAndCapsAtTwoHundred() {
        assertFalse(CommandAnalysisModel.isValidNearThreshold(0));
        assertFalse(CommandAnalysisModel.isValidNearThreshold(100));
        assertThrows(IllegalArgumentException.class, () -> CommandAnalysisModel.nearTargets(List.of(), 100));
        List<CommandAnalysisModel.NearCandidate> many = new ArrayList<>();
        for (int i = 0; i < 205; i++) {
            many.add(new CommandAnalysisModel.NearCandidate("minecraft:i" + i, 100, 100, false));
        }
        CommandAnalysisModel.NearResult result = CommandAnalysisModel.nearTargets(many, 50);
        assertEquals(CommandAnalysisModel.NEAR_LIMIT, result.entries().size());
        assertEquals(5, result.moreCount());
        assertTrue(CommandAnalysisModel.nearTargets(List.of(), 50).entries().isEmpty());
    }

    // ------------------------------------------------------------- F-150-23

    @Test
    void randomTargetCandidatesFollowKindAndCreativeCarriedRules() {
        List<CommandAnalysisModel.TargetRef> targets = List.of(
                key("minecraft:a", 1), key("minecraft:b", 1), key("#minecraft:tag", 1));
        assertEquals(List.of(key("minecraft:a", 1), key("minecraft:b", 1)),
                CommandAnalysisModel.randomTargetCandidates(targets, List.of(), List.of(),
                        CommandAnalysisModel.TargetKind.ITEM));
        assertEquals(List.of(key("#minecraft:tag", 1)),
                CommandAnalysisModel.randomTargetCandidates(targets, List.of(), List.of(),
                        CommandAnalysisModel.TargetKind.TAG));
        assertEquals(List.of(key("#minecraft:tag", 1), key("minecraft:b", 1)),
                CommandAnalysisModel.randomTargetCandidates(targets, List.of(key("minecraft:a", 1)),
                        List.of("minecraft:a"), CommandAnalysisModel.TargetKind.BOTH));
        assertTrue(CommandAnalysisModel.randomTargetCandidates(targets, targets, List.of(),
                CommandAnalysisModel.TargetKind.BOTH).isEmpty());
        assertNull(CommandAnalysisModel.TargetKind.parse("count"));
    }

    @Test
    void pickRandomTargetCanReachEveryCandidateWithAFixedSeed() {
        List<CommandAnalysisModel.TargetRef> candidates = List.of(
                key("minecraft:a", 1), key("minecraft:b", 1), key("minecraft:c", 1),
                key("minecraft:d", 1), key("minecraft:e", 1));
        Random fixed = new Random(7L);
        List<CommandAnalysisModel.TargetRef> seen = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            CommandAnalysisModel.TargetRef picked = CommandAnalysisModel.pickRandomTarget(candidates, fixed);
            assertNotNull(picked);
            if (!seen.contains(picked)) {
                seen.add(picked);
            }
        }
        assertEquals(new java.util.HashSet<>(candidates), new java.util.HashSet<>(seen),
                "a uniform pick must be able to reach every candidate");
        assertEquals(candidates.size(), seen.size(), "every candidate must be reached");
        assertNull(CommandAnalysisModel.pickRandomTarget(List.of(), fixed));
    }

    // ------------------------------------------------------------- F-150-32

    @Test
    void infoPreviewKeepsOrderRemovesDuplicatesAndCapsAtTen() {
        List<CommandAnalysisModel.TargetRef> tags = new ArrayList<>();
        Map<CommandAnalysisModel.TargetRef, CommandAnalysisModel.PreviewState> states = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 12; i++) {
            CommandAnalysisModel.TargetRef key = key("#tag:" + i, 1);
            tags.add(key);
            if (i == 3) {
                tags.add(key);
                states.put(key, new CommandAnalysisModel.PreviewState("recorded", 0, 0, 5L));
            } else {
                states.put(key, new CommandAnalysisModel.PreviewState("none", 0, 0, 0L));
            }
        }
        CommandAnalysisModel.PreviewResult preview = CommandAnalysisModel.infoPreview(tags, states);
        assertEquals(CommandAnalysisModel.INFO_PREVIEW_LIMIT, preview.entries().size());
        assertEquals(2, preview.moreCount());
        assertEquals("#tag:0", preview.entries().get(0).key().itemId());
        assertEquals("recorded", preview.entries().get(3).statusKey());
        assertTrue(CommandAnalysisModel.infoPreview(List.of(), Map.of()).entries().isEmpty());
    }

    // ------------------------------------------------------------- F-150-35

    @Test
    void trendsIsolatesUnknownDatesAndHonoursTheWindow() {
        List<CommandAnalysisModel.RecordValue> records = List.of(
                rec(1, 100, 0L, NOW - 2L * 86_400_000L),
                rec(2, 200, 0L, NOW - 86_400_000L),
                rec(3, 300, 0L, NOW),
                rec(4, 400, 0L, 0L),
                rec(5, 500, 0L, NOW + 86_400_000L));
        CommandAnalysisModel.TrendsResult result =
                CommandAnalysisModel.trends(records, UTC, 7, NOW);
        assertEquals(7, result.days().size());
        assertEquals(3, result.datedInWindow());
        assertEquals(1, result.unknownDateCount());
        assertEquals(3, result.streak());
        assertEquals(0, result.days().get(0).count());
        assertEquals(1, result.days().get(6).count());
        assertEquals(LocalDate.of(2026, 1, 10), result.days().get(6).date());
    }

    @Test
    void trendsClosesStreakAtYesterdayAndBuildsFourChartLevels() {
        List<CommandAnalysisModel.RecordValue> records = new ArrayList<>();
        for (int i = 4; i >= 2; i--) {
            records.add(rec(i, i * 10, 0L, NOW - i * 86_400_000L));
        }
        records.add(rec(20, 200, 0L, NOW - 86_400_000L));
        records.add(rec(21, 210, 0L, NOW - 86_400_000L));
        records.add(rec(22, 220, 0L, NOW - 86_400_000L));
        records.add(rec(23, 230, 0L, NOW - 86_400_000L));
        CommandAnalysisModel.TrendsResult result = CommandAnalysisModel.trends(records, UTC, 7, NOW);
        assertEquals(4, result.streak(), "no records today must close the streak at yesterday");
        assertEquals(4, result.days().get(result.days().size() - 2).level());
        assertEquals(1, result.days().get(result.days().size() - 3).level());
        assertEquals(0, result.days().get(result.days().size() - 1).level());
        assertEquals(30, CommandAnalysisModel.trends(List.of(), UTC, 30, NOW).days().size());
        assertEquals(90, CommandAnalysisModel.trends(List.of(), UTC, 90, NOW).days().size());
        assertThrows(IllegalArgumentException.class, () -> CommandAnalysisModel.trends(List.of(), UTC, 8, NOW));
    }

    @Test
    void trendsRespectsTimeZoneBoundary() {
        long boundary = Instant.parse("2026-01-10T23:30:00Z").toEpochMilli();
        CommandAnalysisModel.TrendsResult utc = CommandAnalysisModel.trends(
                List.of(rec(1, 1, 0L, boundary)), UTC, 7, boundary);
        CommandAnalysisModel.TrendsResult plusTwo = CommandAnalysisModel.trends(
                List.of(rec(1, 1, 0L, boundary)), ZoneId.of("+02:00"), 7, boundary);
        assertEquals(LocalDate.of(2026, 1, 10), utc.days().get(6).date());
        assertEquals(1, utc.days().get(6).count());
        assertEquals(1, plusTwo.days().get(6).count());
    }

    // ------------------------------------------------------------- F-150-36

    @Test
    void recapReturnSortsNewestFirstAndCapsAtTen() {
        List<CommandAnalysisModel.ReturnCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            candidates.add(new CommandAnalysisModel.ReturnCandidate("minecraft:r" + i, 1, "Player" + i,
                    NOW - i * 1000L));
        }
        CommandAnalysisModel.RecapResult result = CommandAnalysisModel.recapReturn(candidates);
        assertEquals(CommandAnalysisModel.RECAP_LIMIT, result.entries().size());
        assertEquals(2, result.moreCount());
        assertEquals("minecraft:r0", result.entries().get(0).itemId());
        assertEquals(NOW, result.entries().get(0).epochMillis());
    }

    @Test
    void recapDailyUsesTheTwentyFourHourWindowAndLegacyNeverGuessesDates() {
        CommandAnalysisModel.RecapResult daily = CommandAnalysisModel.recapDaily(List.of(
                rec(1, 1, 0L, NOW - 23L * 3_600_000L),
                rec(2, 1, 0L, NOW - 25L * 3_600_000L),
                rec(3, 1, 0L, 0L)), NOW);
        assertEquals(List.of("minecraft:item_1"), daily.entries().stream()
                .map(CommandAnalysisModel.RecapEntry::itemId).toList());
        CommandAnalysisModel.RecapResult legacy = CommandAnalysisModel.recapLegacy(5, 2);
        assertTrue(legacy.entries().isEmpty());
        assertEquals(5, legacy.unknownDateCount());
        assertEquals(2, legacy.stillTrackedCount());
        assertTrue(CommandAnalysisModel.recapDaily(List.of(), NOW).entries().isEmpty());
        assertEquals(CommandAnalysisModel.RecapKind.LEGACY, CommandAnalysisModel.RecapKind.parse("legacy"));
        assertNull(CommandAnalysisModel.RecapKind.parse("weekly"));
    }
}
