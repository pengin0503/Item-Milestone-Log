package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

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

    // ================================================================= PR-2: read-only analytics

    private static final UUID U1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID U2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID U3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private static CommandAnalysisModel.RecordValue rv(String id, int count, int ticks, long epoch) {
        return new CommandAnalysisModel.RecordValue(id, count, ticks, 0L, epoch);
    }

    private static CommandAnalysisModel.TargetRef k(String id, int count) {
        return new CommandAnalysisModel.TargetRef(id, count);
    }

    @Test
    void compareSplitsThreeSetsNormalizesDiffAndIgnoresUnknownEpochs() {
        List<CommandAnalysisModel.RecordValue> a = List.of(
                rv("minecraft:a", 1, 100, 0L), rv("minecraft:b", 1, 300, 500L),
                rv("minecraft:only_self", 1, 50, 0L));
        List<CommandAnalysisModel.RecordValue> b = List.of(
                rv("minecraft:a", 1, 150, 0L), rv("minecraft:b", 1, 250, 900L),
                rv("minecraft:only_them", 1, 75, 0L));
        CommandAnalysisModel.CompareResult result = CommandAnalysisModel.compare(a, b);
        assertEquals(List.of(k("minecraft:a", 1), k("minecraft:b", 1)),
                result.common().stream().map(CommandAnalysisModel.CompareRow::key).toList());
        assertEquals(50L, result.common().get(0).diffTicks());
        assertFalse(result.common().get(0).inverted());
        assertEquals(50L, result.common().get(1).diffTicks());
        assertTrue(result.common().get(1).inverted());
        assertEquals(List.of(k("minecraft:only_them", 1)),
                result.onlyThem().stream().map(CommandAnalysisModel.CompareRow::key).toList());
        assertEquals(List.of(k("minecraft:only_self", 1)),
                result.onlyYou().stream().map(CommandAnalysisModel.CompareRow::key).toList());
        assertEquals(0, result.moreCount());
        // epochMillis never enters the difference: only playTicks does.
        assertEquals(result, CommandAnalysisModel.compare(List.of(
                        rv("minecraft:a", 1, 100, 12345L), rv("minecraft:b", 1, 300, 9999L),
                        rv("minecraft:only_self", 1, 50, 7777L)),
                List.of(rv("minecraft:a", 1, 150, 1111L), rv("minecraft:b", 1, 250, 2222L),
                        rv("minecraft:only_them", 1, 75, 3333L))));
        // Console comparison (A = empty): everything is "them only", in playTicks order.
        CommandAnalysisModel.CompareResult console = CommandAnalysisModel.compare(List.of(), b);
        assertTrue(console.common().isEmpty());
        assertTrue(console.onlyYou().isEmpty());
        assertEquals(3, console.onlyThem().size());
        assertTrue(CommandAnalysisModel.compare(List.of(), List.of()).common().isEmpty());
    }

    @Test
    void compareCapsAtTwoHundredRowsInDisplayOrder() {
        List<CommandAnalysisModel.RecordValue> a = new ArrayList<>();
        List<CommandAnalysisModel.RecordValue> b = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            a.add(rv("minecraft:shared" + i, 1, i, 0L));
            b.add(rv("minecraft:shared" + i, 1, i + 1, 0L));
        }
        CommandAnalysisModel.CompareResult result = CommandAnalysisModel.compare(a, b);
        assertEquals(CommandAnalysisModel.COMPARE_LIMIT, result.common().size());
        assertEquals(50, result.moreCount());
        assertEquals("minecraft:shared0", result.common().get(0).key().itemId());
    }

    @Test
    void compareBranchesCoverMissingRouteAndRivals() {
        List<CommandAnalysisModel.RecordValue> a = List.of(
                rv("minecraft:shared", 1, 100, 0L), rv("minecraft:self", 1, 30, 0L));
        List<CommandAnalysisModel.RecordValue> b = List.of(
                rv("minecraft:shared", 1, 400, 0L), rv("minecraft:them", 1, 10, 0L));
        CommandAnalysisModel.CompareBranchResult branches = CommandAnalysisModel.compareBranches(
                CommandAnalysisModel.CompareBranch.MISSING, a, b, List.of(), U2, U1, 2);
        assertEquals(List.of(k("minecraft:them", 1)),
                branches.missing().entries().stream()
                        .map(CommandAnalysisModel.CompareRow::key).toList());
        assertTrue(branches.route().entries().isEmpty());

        CommandAnalysisModel.RouteResult route = CommandAnalysisModel.compareRoute(a, b);
        assertEquals(1, route.entries().size());
        assertEquals(300L, route.entries().get(0).diffTicks());
        assertFalse(route.entries().get(0).inverted());
        assertTrue(CommandAnalysisModel.compareRoute(
                List.of(rv("minecraft:shared", 1, 400, 0L)),
                List.of(rv("minecraft:shared", 1, 100, 0L))).entries().get(0).inverted());
        assertEquals(100, route.entries().get(0).selfTicks());
        assertEquals(400, route.entries().get(0).otherTicks());

        List<CommandAnalysisModel.RivalPlayer> players = List.of(
                new CommandAnalysisModel.RivalPlayer(U2, "Them", 100),
                new CommandAnalysisModel.RivalPlayer(U1, "Self", 95),
                new CommandAnalysisModel.RivalPlayer(U3, "Near", 90),
                new CommandAnalysisModel.RivalPlayer(UUID.randomUUID(), "Far", 89));
        CommandAnalysisModel.RivalResult rivals = CommandAnalysisModel.compareRivals(U2, U1, 100, players);
        assertEquals(List.of("Near"), rivals.entries().stream()
                .map(CommandAnalysisModel.RivalEntry::name).toList());
        assertEquals(10, rivals.entries().get(0).gap());
        assertTrue(CommandAnalysisModel.compareRivals(U2, U1, 100, List.of()).entries().isEmpty());
        assertEquals(CommandAnalysisModel.CompareBranch.RIVAL,
                CommandAnalysisModel.CompareBranch.parse("rival"));
        assertNull(CommandAnalysisModel.CompareBranch.parse("other"));
    }

    @Test
    void timelineComputesConsecutiveGapsKeepsWorldFirstSeparateAndIgnoresInputOrder() {
        List<CommandAnalysisModel.TimelineHolder> holders = List.of(
                new CommandAnalysisModel.TimelineHolder(U3, "C", 250, 0L),
                new CommandAnalysisModel.TimelineHolder(U1, "A", 100, 10L),
                new CommandAnalysisModel.TimelineHolder(U2, "B", 100, 20L));
        CommandAnalysisModel.TimelineResult result = CommandAnalysisModel.timeline(holders,
                new CommandAnalysisModel.TimelineHolder(U1, "A", 50, 0L), 100);
        assertEquals(List.of(0L, 0L, 150L), result.entries().stream()
                .map(CommandAnalysisModel.TimelineEntry::gapTicks).toList());
        assertEquals(List.of(1, 2, 3), result.entries().stream()
                .map(CommandAnalysisModel.TimelineEntry::index).toList());
        assertNotNull(result.worldFirst());
        assertTrue(result.worldFirst().worldFirst());
        assertEquals(50, result.worldFirst().playTicks());
        assertEquals(result, CommandAnalysisModel.timeline(List.of(holders.get(1), holders.get(0),
                holders.get(2)), holders.get(1) == null ? null
                : new CommandAnalysisModel.TimelineHolder(U1, "A", 50, 0L), 100));
        assertEquals(0, CommandAnalysisModel.timeline(List.of(), null, 100).entries().size());
        List<CommandAnalysisModel.TimelineHolder> many = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            many.add(new CommandAnalysisModel.TimelineHolder(U1, "P" + i, i, 0L));
        }
        CommandAnalysisModel.TimelineResult capped = CommandAnalysisModel.timeline(many, null,
                CommandAnalysisModel.TIMELINE_LIMIT);
        assertEquals(CommandAnalysisModel.TIMELINE_LIMIT, capped.entries().size());
        assertEquals(50, capped.moreCount());
    }

    @Test
    void sessionWindowHonoursBoundaryUnknownEpochsFourWindowsAndOrder() {
        List<CommandAnalysisModel.RecordValue> records = List.of(
                rv("minecraft:recent", 1, 1, NOW - 24L * 3_600_000L),
                rv("minecraft:older", 1, 2, NOW - 25L * 3_600_000L),
                rv("minecraft:unknown", 1, 3, 0L),
                rv("minecraft:newest", 1, 4, NOW - 1000L));
        assertEquals(List.of("minecraft:newest", "minecraft:recent"),
                CommandAnalysisModel.sessionWindow(records, NOW, 24).entries().stream()
                        .map(CommandAnalysisModel.RecordValue::itemId).toList());
        assertEquals(1, CommandAnalysisModel.sessionWindow(records, NOW, 23).entries().size());
        assertEquals(3, CommandAnalysisModel.sessionWindow(records, NOW, 72).entries().size());
        assertEquals(1, CommandAnalysisModel.sessionWindow(records, NOW, 1).entries().size());
        assertEquals(6, CommandAnalysisModel.parseSessionHours("6"));
        assertNull(CommandAnalysisModel.parseSessionHours("12"));
        assertTrue(CommandAnalysisModel.sessionWindow(List.of(), NOW, 24).entries().isEmpty());
        List<CommandAnalysisModel.RecordValue> many = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            many.add(rv("minecraft:r" + i, 1, 1, NOW - 1000L));
        }
        assertEquals(CommandAnalysisModel.SESSION_LIMIT,
                CommandAnalysisModel.sessionWindow(many, NOW, 24).entries().size());
        assertEquals(50, CommandAnalysisModel.sessionWindow(many, NOW, 24).moreCount());
    }

    @Test
    void paceHandlesZeroDivisionCompetitionRanksScopesAndCap() {
        List<CommandAnalysisModel.PacePlayer> players = List.of(
                new CommandAnalysisModel.PacePlayer(U1, "Zero", 10, 0L),
                new CommandAnalysisModel.PacePlayer(U2, "Fast", 20, 72_000L),
                new CommandAnalysisModel.PacePlayer(U3, "Tied", 10, 36_000L));
        CommandAnalysisModel.PaceResult result = CommandAnalysisModel.pace(players,
                CommandAnalysisModel.PACE_LIMIT);
        assertEquals(List.of("Fast", "Tied", "Zero"), result.entries().stream()
                .map(CommandAnalysisModel.PaceEntry::name).toList());
        assertEquals(20L, result.entries().get(0).milestonesPerHour());
        assertEquals(20L, result.entries().get(1).milestonesPerHour());
        assertEquals(-1L, result.entries().get(2).milestonesPerHour());
        assertEquals(List.of(1, 1, 3), result.entries().stream()
                .map(CommandAnalysisModel.PaceEntry::rank).toList());
        assertEquals(3, result.playerCount());
        assertEquals(CommandAnalysisModel.PaceScope.PRESETS,
                CommandAnalysisModel.PaceScope.parse("presets"));
        assertNull(CommandAnalysisModel.PaceScope.parse("bogus"));
        assertEquals("targets", CommandAnalysisModel.PaceScope.TARGETS.commandValue());
        assertEquals(new CommandAnalysisModel.PaceSummary(2, 5, 1, 3),
                new CommandAnalysisModel.PaceSummary(2, 5, 1, 3));
        List<CommandAnalysisModel.PacePlayer> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(new CommandAnalysisModel.PacePlayer(U1, "P" + i, i + 1, 72_000L));
        }
        assertEquals(CommandAnalysisModel.PACE_LIMIT,
                CommandAnalysisModel.pace(many, CommandAnalysisModel.PACE_LIMIT).entries().size());
    }

    @Test
    void modStatsExtractsNamespacesDeduplicatesUnionsAndKeepsFirstsGlobal() {
        assertEquals("minecraft", CommandAnalysisModel.namespaceOf("minecraft:diamond"));
        assertEquals("c", CommandAnalysisModel.namespaceOf("#c:ingots/iron"));
        assertEquals("a", CommandAnalysisModel.namespaceOf("a:b:c"));
        assertNull(CommandAnalysisModel.namespaceOf(""));
        assertNull(CommandAnalysisModel.namespaceOf(null));
        CommandAnalysisModel.NamespaceResult result = CommandAnalysisModel.namespaceStats(
                List.of(k("minecraft:a", 1), k("mod:b", 1), k("mod:b", 1)),
                List.of(k("minecraft:a", 1), k("mod:b", 2), k("mod:b", 2), k("unknown", 1)),
                List.of(k("hidden:c", 1)), CommandAnalysisModel.MODSTATS_LIMIT);
        assertEquals(List.of("minecraft", "mod", "unknown", "hidden"),
                result.entries().stream().map(CommandAnalysisModel.NamespaceStats::namespace).toList());
        CommandAnalysisModel.NamespaceStats mod = result.entries().get(1);
        assertEquals(1, mod.targets());
        assertEquals(1, mod.records());
        assertEquals(0, mod.firsts());
        assertEquals(1, result.entries().get(3).firsts()); // world firsts stay global
        assertTrue(CommandAnalysisModel.namespaceStats(List.of(), List.of(), List.of(),
                CommandAnalysisModel.MODSTATS_LIMIT).entries().isEmpty());
    }

    @Test
    void rankingAxisCoversAllAxesPeriodSkipsUnknownAndTeamUsesFastest() {
        List<CommandAnalysisModel.AxisPlayer> players = List.of(
                new CommandAnalysisModel.AxisPlayer(U1, "A", List.of(
                        rv("minecraft:a", 1, 100, NOW - 1000L), rv("minecraft:b", 1, 200, 0L)),
                        true, "red"),
                new CommandAnalysisModel.AxisPlayer(U2, "B", List.of(
                        rv("minecraft:a", 1, 50, NOW - 2000L)), false, "red"),
                new CommandAnalysisModel.AxisPlayer(U3, "C", List.of(
                        rv("minecraft:a", 1, 30, NOW - 1000L)), true, null));
        CommandAnalysisModel.AxisResult all = CommandAnalysisModel.rankingAxis(players,
                CommandAnalysisModel.RankingAxis.ALL, 7, NOW);
        assertEquals(List.of("A", "B", "C"), all.entries().stream()
                .map(CommandAnalysisModel.AxisCountEntry::name).toList());
        CommandAnalysisModel.AxisResult online = CommandAnalysisModel.rankingAxis(players,
                CommandAnalysisModel.RankingAxis.ONLINE, 7, NOW);
        assertEquals(List.of("A", "C"), online.entries().stream()
                .map(CommandAnalysisModel.AxisCountEntry::name).toList());
        CommandAnalysisModel.AxisResult period = CommandAnalysisModel.rankingAxis(players,
                CommandAnalysisModel.RankingAxis.PERIOD, 7, NOW);
        assertEquals(1, period.skippedUnknown());
        assertEquals(1, period.entries().get(0).count());
        CommandAnalysisModel.AxisResult team = CommandAnalysisModel.rankingAxis(players,
                CommandAnalysisModel.RankingAxis.TEAM, 7, NOW);
        assertEquals(30, team.teams().get(0).playTicks());
        assertNull(team.teams().get(0).team());
        assertEquals(50, team.teams().get(1).playTicks());
        assertEquals("red", team.teams().get(1).team());
        assertEquals(CommandAnalysisModel.RankingAxis.TEAM,
                CommandAnalysisModel.RankingAxis.parse("team"));
        assertNull(CommandAnalysisModel.RankingAxis.parse("nope"));
    }

    @Test
    void presetStatsUsesVisibleDenominatorEvenMedianAndTwentyCap() {
        List<CommandAnalysisModel.PresetStatsPlayer> players = List.of(
                new CommandAnalysisModel.PresetStatsPlayer(U1, "A", Set.of("p1"), List.of(
                        rv("minecraft:a", 1, 100, 0L), rv("minecraft:b", 1, 200, 0L))),
                new CommandAnalysisModel.PresetStatsPlayer(U2, "B", Set.of(), List.of(
                        rv("minecraft:a", 1, 300, 0L), rv("minecraft:b", 1, 400, 0L))),
                new CommandAnalysisModel.PresetStatsPlayer(U3, "C", Set.of("p1"), List.of(
                        rv("minecraft:a", 1, 500, 0L))));
        CommandAnalysisModel.PresetStatsResult stats = CommandAnalysisModel.presetStats(players,
                List.of("p1", "p2"), CommandAnalysisModel.PRESETSTATS_LIMIT);
        assertEquals(2, stats.entries().get(0).completed());
        assertEquals(3, stats.entries().get(0).totalPlayers());
        CommandAnalysisModel.PresetEstimate estimate = CommandAnalysisModel.presetPredict(players,
                List.of(k("minecraft:a", 1), k("minecraft:b", 1)));
        assertEquals(2, estimate.finishers());
        assertEquals(300L, estimate.medianTicks());
        assertEquals(0, CommandAnalysisModel.presetPredict(players,
                List.of(k("minecraft:missing", 1))).finishers());
        assertEquals(0, CommandAnalysisModel.presetStats(List.of(), List.of("p1"),
                CommandAnalysisModel.PRESETSTATS_LIMIT).entries().get(0).totalPlayers());
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add("p" + i);
        }
        assertEquals(CommandAnalysisModel.PRESETSTATS_LIMIT,
                CommandAnalysisModel.presetStats(players, many,
                        CommandAnalysisModel.PRESETSTATS_LIMIT).entries().size());
    }

    @Test
    void presetMatrixCapsColumnsIgnoresCompletionMarkersAndKeepsSuppliedTotals() {
        List<String> presets = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            presets.add("p" + i);
        }
        Map<String, CommandAnalysisModel.MatrixValue> progress = new java.util.HashMap<>();
        progress.put("p0", new CommandAnalysisModel.MatrixValue(3, 5, true));
        progress.put("p1", new CommandAnalysisModel.MatrixValue(2, 4, false));
        CommandAnalysisModel.PresetMatrixResult result = CommandAnalysisModel.presetMatrix(
                List.of(new CommandAnalysisModel.MatrixPlayer(U1, "B", progress),
                        new CommandAnalysisModel.MatrixPlayer(U2, "A", Map.of())),
                presets, CommandAnalysisModel.PRESETMATRIX_COLUMNS,
                CommandAnalysisModel.PRESETMATRIX_LIMIT);
        assertEquals(List.of("A", "B"), result.rows().stream()
                .map(CommandAnalysisModel.MatrixRow::name).toList());
        assertEquals(CommandAnalysisModel.PRESETMATRIX_COLUMNS, result.rows().get(0).cells().size());
        assertEquals(2, result.omittedPresets());
        assertEquals(new CommandAnalysisModel.MatrixValue(3, 5, true), result.rows().get(1).cells().get(0));
        assertEquals(new CommandAnalysisModel.MatrixValue(2, 4, false), result.rows().get(1).cells().get(1));
        assertEquals(new CommandAnalysisModel.MatrixValue(0, 0, false), result.rows().get(0).cells().get(0));
        assertTrue(CommandAnalysisModel.presetMatrix(List.of(), presets,
                CommandAnalysisModel.PRESETMATRIX_COLUMNS,
                CommandAnalysisModel.PRESETMATRIX_LIMIT).rows().isEmpty());
    }
}
