package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-Java tests for the 1.5.0 ranking axes, competition ranks and unclaimed derivation. */
class RankingStatsModelTest {
    private static final UUID U1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID U2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID U3 = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID U4 = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID U5 = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID U6 = UUID.fromString("00000000-0000-0000-0000-000000000006");

    private static RankingStatsModel.TargetRef key(int n) {
        return new RankingStatsModel.TargetRef("minecraft:item_" + n, 1);
    }

    private static RankingStatsModel.RecordValue rec(int n, int ticks, long epoch) {
        return new RankingStatsModel.RecordValue("minecraft:item_" + n, 1, ticks, epoch);
    }

    private static RankingStatsModel.PresetPlayer player(UUID uuid, String name,
                                                          List<RankingStatsModel.RecordValue> records) {
        return new RankingStatsModel.PresetPlayer(uuid, name, records, false);
    }

    @Test
    void presetRankingUsesRecordedCountDescendingWithCompetitionRanks() {
        List<RankingStatsModel.TargetRef> preset = List.of(key(1), key(2), key(3));
        List<RankingStatsModel.PresetRankEntry> ranking = RankingStatsModel.presetRanking("demo", preset, List.of(
                player(U1, "Alice", List.of(rec(1, 90, 10), rec(2, 90, 10), rec(3, 90, 10))),
                player(U2, "Bob", List.of(rec(1, 20, 10), rec(2, 20, 10))),
                player(U3, "Carol", List.of(rec(1, 30, 10), rec(2, 30, 10))),
                player(U4, "Dave", List.of(rec(1, 40, 10)))));

        assertEquals(List.of(1, 2, 2, 4), ranking.stream().map(RankingStatsModel.PresetRankEntry::rank).toList());
        assertEquals(List.of(U1, U2, U3, U4),
                ranking.stream().map(e -> e.candidate().uuid()).toList());
        assertEquals("demo", ranking.get(0).candidate().presetId());
        assertEquals(3, ranking.get(0).candidate().recorded());
        assertEquals(3, ranking.get(0).candidate().total());
        assertEquals(90, ranking.get(0).candidate().lastPlayTicks());
    }

    @Test
    void presetRankingBreaksTiesByPlayTicksThenEpochUnknownLastNameThenUuid() {
        List<RankingStatsModel.TargetRef> preset = List.of(key(1), key(2));
        // Same count: lower max playTicks first; then known epoch before unknown (0); then name
        // case-insensitively, then case-sensitively, then UUID.
        List<RankingStatsModel.PresetRankEntry> ranking = RankingStatsModel.presetRanking("demo", preset, List.of(
                player(U1, "alice", List.of(rec(1, 50, 200), rec(2, 50, 200))),
                player(U2, "Alice", List.of(rec(1, 50, 0), rec(2, 50, 0))),
                player(U3, "Bob", List.of(rec(1, 40, 999), rec(2, 40, 999))),
                player(U4, "bob", List.of(rec(1, 40, 999), rec(2, 40, 999))),
                player(U5, "Carol", List.of(rec(1, 30, 0), rec(2, 30, 0)))));

        assertEquals(List.of(U5, U3, U4, U1, U2),
                ranking.stream().map(e -> e.candidate().uuid()).toList());
        assertEquals(List.of(1, 1, 1, 1, 1),
                ranking.stream().map(RankingStatsModel.PresetRankEntry::rank).toList());
        assertEquals(0L, ranking.get(0).candidate().lastEpoch());
    }

    @Test
    void presetRankingExcludesEmptyPlayersAndDoesNotRankCompletionMarker() {
        List<RankingStatsModel.TargetRef> preset = List.of(key(1), key(2));
        List<RankingStatsModel.PresetRankEntry> ranking = RankingStatsModel.presetRanking("demo", preset, List.of(
                player(U1, "Alice", List.of(rec(1, 10, 10), rec(2, 10, 10))),
                new RankingStatsModel.PresetPlayer(U2, "Bob",
                        List.of(rec(1, 5, 5), rec(2, 5, 5)), true),
                player(U3, "Carol", List.of(rec(9, 1, 1)))));

        assertEquals(List.of(U2, U1), ranking.stream().map(e -> e.candidate().uuid()).toList());
        assertEquals(List.of(1, 1), ranking.stream().map(RankingStatsModel.PresetRankEntry::rank).toList());
        assertTrue(ranking.get(0).candidate().completed());
        assertFalse(ranking.get(1).candidate().completed());
        assertTrue(RankingStatsModel.presetRanking("empty", List.of(), List.of(player(U1, "Alice",
                List.of(rec(1, 1, 1))))).isEmpty());
    }

    @Test
    void firstsTopGroupsByHolderAndRanksByCount() {
        List<RankingStatsModel.FirstCountEntry> ranking = RankingStatsModel.firstsTopByCount(List.of(
                new RankingStatsModel.WorldFirstValue(U1, "Alice", 100, 1_000),
                new RankingStatsModel.WorldFirstValue(U1, "Alice", 200, 2_000),
                new RankingStatsModel.WorldFirstValue(U1, "Alice", 300, 3_000),
                new RankingStatsModel.WorldFirstValue(U2, "Bob", 10, 10),
                new RankingStatsModel.WorldFirstValue(U2, "Bob", 11, 11),
                new RankingStatsModel.WorldFirstValue(U3, "Carol", 20, 20),
                new RankingStatsModel.WorldFirstValue(U3, "Carol", 21, 21),
                new RankingStatsModel.WorldFirstValue(U4, "Dave", 30, 30),
                new RankingStatsModel.WorldFirstValue(U5, "Eve", 40, 40)));

        assertEquals(List.of(1, 2, 2, 4, 4),
                ranking.stream().map(RankingStatsModel.FirstCountEntry::rank).toList());
        assertEquals(List.of(U1, U2, U3, U4, U5),
                ranking.stream().map(RankingStatsModel.FirstCountEntry::uuid).toList());
        assertEquals(3, ranking.get(0).count());
        assertEquals(1_000L, ranking.get(0).earliestEpoch());
        assertEquals(100, ranking.get(0).earliestTicks());
    }

    @Test
    void firstsTopBreaksCountTiesByEarliestEpochThenTicksThenNameThenUuid() {
        List<RankingStatsModel.FirstCountEntry> ranking = RankingStatsModel.firstsTopByCount(List.of(
                // U1: earliest epoch 100, earliest ticks at that epoch 42 (a later record has 1 tick).
                new RankingStatsModel.WorldFirstValue(U1, "Zed", 42, 100),
                new RankingStatsModel.WorldFirstValue(U1, "Zed", 1, 900),
                // U2: all epochs unknown (0) => last among the two-count holders.
                new RankingStatsModel.WorldFirstValue(U2, "alice", 2, 0),
                new RankingStatsModel.WorldFirstValue(U2, "alice", 3, 0),
                // U3/U4/U5: one count each, same epoch/ticks, name order decides.
                new RankingStatsModel.WorldFirstValue(U3, "Alice", 5, 10),
                new RankingStatsModel.WorldFirstValue(U4, "alice", 5, 10),
                new RankingStatsModel.WorldFirstValue(U5, "Bob", 5, 10)));

        assertEquals(List.of(U1, U2, U3, U4, U5),
                ranking.stream().map(RankingStatsModel.FirstCountEntry::uuid).toList());
        assertEquals(0L, ranking.get(1).earliestEpoch());
        assertEquals(2, ranking.get(1).earliestTicks());
        assertTrue(RankingStatsModel.firstsTopByCount(List.of()).isEmpty());
    }

    @Test
    void unclaimedScopeTargetsSortsAndExcludesAlreadyClaimedKeys() {
        RankingStatsModel.UnclaimedResult result = RankingStatsModel.unclaimed(
                RankingStatsModel.UnclaimedScope.TARGETS,
                List.of(key(3), key(1), key(2)), List.of(), List.of(key(2)));

        assertEquals(List.of("target", "target"), result.entries().stream()
                .map(RankingStatsModel.UnclaimedEntry::source).toList());
        assertEquals(List.of("minecraft:item_1", "minecraft:item_3"), result.entries().stream()
                .map(RankingStatsModel.UnclaimedEntry::itemId).toList());
        assertTrue(result.entries().stream().allMatch(e -> e.presetId() == null));
        assertEquals(0, result.moreCount());
    }

    @Test
    void unclaimedScopePresetsKeepsPresetIdsAndDuplicateKeysFromDifferentScopes() {
        RankingStatsModel.PresetRef first = new RankingStatsModel.PresetRef("alpha", List.of(key(2), key(1)));
        RankingStatsModel.PresetRef second = new RankingStatsModel.PresetRef("beta", List.of(key(1)));
        RankingStatsModel.UnclaimedResult presets = RankingStatsModel.unclaimed(
                RankingStatsModel.UnclaimedScope.PRESETS, List.of(), List.of(first, second), List.of());

        assertEquals(3, presets.entries().size());
        assertEquals(List.of("minecraft:item_1", "minecraft:item_1", "minecraft:item_2"),
                presets.entries().stream().map(RankingStatsModel.UnclaimedEntry::itemId).toList());
        assertEquals(List.of("alpha", "beta", "alpha"), presets.entries().stream()
                .map(RankingStatsModel.UnclaimedEntry::presetId).toList());
        assertTrue(presets.entries().stream().allMatch(RankingStatsModel.UnclaimedEntry::fromPreset));

        RankingStatsModel.UnclaimedResult all = RankingStatsModel.unclaimed(
                RankingStatsModel.UnclaimedScope.ALL, List.of(key(1)), List.of(first), List.of());
        assertEquals(3, all.entries().size());
        assertEquals(List.of("target", "preset", "preset"), all.entries().stream()
                .map(RankingStatsModel.UnclaimedEntry::source).toList());
    }

    @Test
    void unclaimedCapsAt200AndReportsExactMoreCount() {
        List<RankingStatsModel.TargetRef> targets = new ArrayList<>();
        for (int i = 0; i < 210; i++) {
            targets.add(new RankingStatsModel.TargetRef(String.format("minecraft:item_%03d", i), 1));
        }
        RankingStatsModel.UnclaimedResult result = RankingStatsModel.unclaimed(
                RankingStatsModel.UnclaimedScope.TARGETS, targets, List.of(),
                List.of(new RankingStatsModel.TargetRef("minecraft:item_003", 1),
                        new RankingStatsModel.TargetRef("minecraft:item_007", 1)));

        assertEquals(200, result.entries().size());
        assertEquals(8, result.moreCount()); // 210 targets - 2 claimed - 200 kept
        assertEquals(RankingStatsModel.UNCLAIMED_RESULT_LIMIT, result.entries().size());
        assertTrue(RankingStatsModel.unclaimed(RankingStatsModel.UnclaimedScope.ALL,
                List.of(), List.of(), List.of()).entries().isEmpty());
    }

    @Test
    void unclaimedScopeParsingAndCommandValuesAreCaseInsensitive() {
        assertEquals(RankingStatsModel.UnclaimedScope.TARGETS, RankingStatsModel.UnclaimedScope.parse("TARGETS"));
        assertEquals(RankingStatsModel.UnclaimedScope.PRESETS, RankingStatsModel.UnclaimedScope.parse("Presets"));
        assertEquals(RankingStatsModel.UnclaimedScope.ALL, RankingStatsModel.UnclaimedScope.parse("all"));
        assertNull(RankingStatsModel.UnclaimedScope.parse("records"));
        assertNull(RankingStatsModel.UnclaimedScope.parse(""));
        assertNull(RankingStatsModel.UnclaimedScope.parse(null));
        assertEquals("targets", RankingStatsModel.UnclaimedScope.TARGETS.commandValue());
        assertEquals("presets", RankingStatsModel.UnclaimedScope.PRESETS.commandValue());
        assertEquals("all", RankingStatsModel.UnclaimedScope.ALL.commandValue());
    }

    @Test
    void sharedComparatorsKeepUnknownEpochLastAndNameOrderStable() {
        assertEquals(-1, Integer.signum(RankingStatsModel.compareEpochUnknownLast(10L, 0L)));
        assertEquals(1, Integer.signum(RankingStatsModel.compareEpochUnknownLast(0L, 10L)));
        assertEquals(0, RankingStatsModel.compareEpochUnknownLast(0L, 0L));
        assertTrue(RankingStatsModel.compareNames("alice", U1, "Bob", U2) < 0);
        assertTrue(RankingStatsModel.compareNames("Bob", U1, "bob", U2) < 0);
        assertTrue(RankingStatsModel.compareNames("Bob", U1, "Bob", U2) < 0);
        assertEquals(0, RankingStatsModel.compareNames("Bob", U1, "Bob", U1));
        assertTrue(RankingStatsModel.compareTargets(key(1), key(2)) < 0);
    }

    @Test
    void itemTimesCoversSingleOddEvenEmptyAndLongSafeValues() {
        RankingStatsModel.ItemTimeStats empty = RankingStatsModel.itemTimes(List.of());
        assertEquals(new RankingStatsModel.ItemTimeStats(0, 0L, 0L, 0L, 0L), empty);

        assertEquals(new RankingStatsModel.ItemTimeStats(1, 42L, 42L, 42L, 42L),
                RankingStatsModel.itemTimes(List.of(42)));
        assertEquals(new RankingStatsModel.ItemTimeStats(3, 20L, 20L, 10L, 30L),
                RankingStatsModel.itemTimes(List.of(30, 10, 20)));
        assertEquals(new RankingStatsModel.ItemTimeStats(4, 25L, 25L, 10L, 40L),
                RankingStatsModel.itemTimes(List.of(40, 10, 30, 20)));
        // Even count: floor-average of the two middle values (D-150-24).
        assertEquals(1L, RankingStatsModel.itemTimes(List.of(2, 1)).medianTicks());
        assertEquals(2L, RankingStatsModel.itemTimes(List.of(1, 3)).medianTicks());

        // Two ints near MAX_VALUE must not overflow the long sum or the long median average.
        int max = Integer.MAX_VALUE;
        assertEquals(new RankingStatsModel.ItemTimeStats(2, max - 1L, max - 1L, max - 1L, max),
                RankingStatsModel.itemTimes(List.of(max, max - 1)));
        assertEquals(RankingStatsModel.itemTimes(List.of(5, 1, 3)),
                RankingStatsModel.itemTimes(List.of(3, 5, 1)));
    }

    @Test
    void gapsAreRelativeToVisibleRowsWithTiesAndUnrankedSelf() {
        List<RankingStatsModel.GapRow> rows = List.of(
                new RankingStatsModel.GapRow(1, U1, "Alice", 100),
                new RankingStatsModel.GapRow(1, U2, "Bob", 100),
                new RankingStatsModel.GapRow(3, U3, "Carol", 250));
        RankingStatsModel.GapResult result = RankingStatsModel.gapsFor(rows, 2);

        assertEquals(new RankingStatsModel.TimeGap(-1L, -1L), result.rows().get(0));
        assertFalse(result.rows().get(0).hasAbove());
        assertEquals(new RankingStatsModel.TimeGap(0L, 0L), result.rows().get(1));
        assertEquals(new RankingStatsModel.TimeGap(150L, 150L), result.rows().get(2));
        assertEquals(new RankingStatsModel.TimeGap(150L, 150L), result.self());
        assertEquals(new RankingStatsModel.TimeGap(-1L, -1L), RankingStatsModel.gapsFor(rows, -1).self());
        // The first row's -1 is "unavailable", not a negative gap; every real gap is non-negative.
        assertTrue(result.rows().stream().skip(1).allMatch(g -> g.aboveTicks() >= 0L && g.firstTicks() >= 0L));

        // A viewer ranked outside the displayed top 10 still gets their real predecessor's gap.
        List<RankingStatsModel.GapRow> twelve = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            twelve.add(new RankingStatsModel.GapRow(i + 1, new UUID(0, i + 1), "P" + i, 100 + i * 10));
        }
        RankingStatsModel.GapResult outer = RankingStatsModel.gapsFor(twelve, 11);
        assertEquals(10L, outer.self().aboveTicks());
        assertEquals(110L, outer.self().firstTicks());
        assertEquals(new RankingStatsModel.TimeGap(10L, 90L), outer.rows().get(9));

        // A hidden player in between must never become a gap baseline: the tree passes only the
        // visibility-filtered ranking, so the second visible row's "above" is 150, not 100.
        List<RankingStatsModel.GapRow> visible = List.of(
                new RankingStatsModel.GapRow(1, U1, "Alice", 100),
                new RankingStatsModel.GapRow(2, U3, "Carol", 250));
        assertEquals(new RankingStatsModel.TimeGap(150L, 150L),
                RankingStatsModel.gapsFor(visible, 1).self());

        // Defensive clamp keeps gaps non-negative even for out-of-order input.
        RankingStatsModel.GapResult descending = RankingStatsModel.gapsFor(List.of(
                new RankingStatsModel.GapRow(1, U1, "Alice", 300),
                new RankingStatsModel.GapRow(2, U2, "Bob", 100)), 1);
        assertEquals(0L, descending.self().aboveTicks());
    }

    @Test
    void whoHasOrdersRecordedThenTargetingWithCompetitionRanks() {
        RankingStatsModel.TargetRef target = key(1);
        List<RankingStatsModel.WhoHasPlayer> players = List.of(
                new RankingStatsModel.WhoHasPlayer(U1, "Alice", List.of(rec(1, 100, 1_000)), List.of()),
                new RankingStatsModel.WhoHasPlayer(U2, "Bob", List.of(rec(1, 100, 500)), List.of()),
                new RankingStatsModel.WhoHasPlayer(U3, "Carol", List.of(rec(1, 250, 0)), List.of()),
                // Recorded wins when the key is both recorded and targeted.
                new RankingStatsModel.WhoHasPlayer(U5, "Eve", List.of(rec(1, 30, 30)), List.of(target)),
                new RankingStatsModel.WhoHasPlayer(U4, "Dave", List.of(rec(2, 5, 5)), List.of(target)),
                new RankingStatsModel.WhoHasPlayer(U6, "Aaron", List.of(), List.of(target)));

        RankingStatsModel.WhoHasResult result = RankingStatsModel.whoHas(players, target);
        assertEquals(List.of(U5, U2, U1, U3, U6, U4), result.entries().stream()
                .map(RankingStatsModel.WhoHasEntry::uuid).toList());
        assertEquals(List.of(1, 2, 2, 4, 0, 0), result.entries().stream()
                .map(RankingStatsModel.WhoHasEntry::rank).toList());
        assertEquals(List.of(true, true, true, true, false, false), result.entries().stream()
                .map(RankingStatsModel.WhoHasEntry::recorded).toList());
        assertEquals(30, result.entries().get(0).playTicks());
        assertEquals(0, result.entries().get(4).playTicks());
        assertEquals(4, result.recordedCount());
        assertEquals(2, result.targetingCount());
        assertEquals(0, result.moreCount());

        // Visibility is applied by the caller (as in PR-1): a player not handed to the model —
        // e.g. one with hideFromLookup — appears in neither section.
        assertTrue(RankingStatsModel.whoHas(players, key(9)).entries().isEmpty());
        assertEquals(0, RankingStatsModel.whoHas(List.of(), target).moreCount());
    }

    @Test
    void whoHasCapsFlatListAt200AndKeepsRanksBeyondTheDisplayedTop() {
        List<RankingStatsModel.WhoHasPlayer> players = new ArrayList<>();
        players.add(new RankingStatsModel.WhoHasPlayer(U1, "Ace", List.of(rec(1, 10, 10)), List.of()));
        for (int i = 0; i < 201; i++) {
            players.add(new RankingStatsModel.WhoHasPlayer(new UUID(0, i + 100), "Z" + i,
                    List.of(), List.of(key(1))));
        }
        RankingStatsModel.WhoHasResult result = RankingStatsModel.whoHas(players, key(1));
        assertEquals(RankingStatsModel.UNCLAIMED_RESULT_LIMIT, result.entries().size());
        assertEquals(2, result.moreCount());
        assertEquals(1, result.recordedCount());
        assertEquals(201, result.targetingCount());
        assertTrue(result.entries().get(0).recorded());
        assertTrue(result.entries().stream().skip(1).noneMatch(RankingStatsModel.WhoHasEntry::recorded));

        List<RankingStatsModel.WhoHasPlayer> twelve = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            twelve.add(new RankingStatsModel.WhoHasPlayer(new UUID(0, i + 1), "P" + i,
                    List.of(rec(1, 100 + i, 100 + i)), List.of()));
        }
        List<RankingStatsModel.WhoHasEntry> ranked = RankingStatsModel.whoHas(twelve, key(1)).entries();
        assertEquals(12, ranked.get(11).rank());
        assertTrue(ranked.subList(0, 10).stream().noneMatch(e -> e.uuid().equals(ranked.get(11).uuid())));
    }

    private static RankingStatsModel.CloseWorldFirst first(int n, UUID holder, String winner, int ticks) {
        return new RankingStatsModel.CloseWorldFirst(key(n), holder, winner, ticks);
    }

    private static RankingStatsModel.WhoHasPlayer visible(UUID uuid, String name,
                                                          RankingStatsModel.RecordValue... records) {
        return new RankingStatsModel.WhoHasPlayer(uuid, name, List.of(records), List.of());
    }

    @Test
    void firstsClosePicksOneBestRunnerUpPerKeyAndOmitsHolderAndKeylessRows() {
        List<RankingStatsModel.CloseWorldFirst> worldFirsts = List.of(
                first(1, U1, "Alice", 100),
                first(2, U2, "Bob", 300),
                // No other visible player recorded key 3: the key must not produce a row.
                first(3, U3, "Carol", 50));
        List<RankingStatsModel.WhoHasPlayer> players = List.of(
                // The holder's own record is never a runner-up, even when it would be fastest.
                visible(U1, "Alice", rec(1, 101, 1)),
                visible(U2, "Bob", rec(1, 110, 500), rec(2, 320, 600)),
                visible(U4, "Dave", rec(1, 130, 100)),
                visible(U3, "Carol", rec(1, 120, 900)),
                visible(U5, "Eve", rec(2, 320, 700)));

        List<RankingStatsModel.CloseEntry> entries = RankingStatsModel.firstsClose(worldFirsts, players);
        assertEquals(2, entries.size());
        assertEquals(new RankingStatsModel.CloseEntry("minecraft:item_1", 1, "Alice", "Bob", 10L),
                entries.get(0));
        assertEquals(new RankingStatsModel.CloseEntry("minecraft:item_2", 1, "Bob", "Eve", 20L),
                entries.get(1));
        assertTrue(entries.stream().noneMatch(e -> e.runnerName().equals(e.winnerName())),
                "the holder can never be its own runner-up");
        assertEquals(List.of(), RankingStatsModel.firstsClose(worldFirsts, List.of()));
    }

    @Test
    void firstsCloseBreaksRunnerTiesByKnownEpochThenNameThenUuid() {
        List<RankingStatsModel.CloseWorldFirst> worldFirsts = List.of(first(1, U1, "Alice", 100));
        List<RankingStatsModel.WhoHasPlayer> players = List.of(
                visible(U2, "Bob", rec(1, 120, 0)),       // unknown epoch sorts last
                visible(U3, "Carol", rec(1, 120, 500)),
                visible(U6, "Aaron", rec(1, 120, 500)),   // name wins over Carol
                visible(U5, "Aaron", rec(1, 120, 500)));  // equal name: UUID breaks the tie

        List<RankingStatsModel.CloseEntry> entries = RankingStatsModel.firstsClose(worldFirsts, players);
        assertEquals(1, entries.size());
        assertEquals("Aaron", entries.get(0).runnerName());
        assertEquals(20L, entries.get(0).gapTicks());
        assertEquals(entries, RankingStatsModel.firstsClose(worldFirsts, List.of(
                visible(U5, "Aaron", rec(1, 120, 500)),
                visible(U6, "Aaron", rec(1, 120, 500)),
                visible(U3, "Carol", rec(1, 120, 500)),
                visible(U2, "Bob", rec(1, 120, 0)))),
                "the runner-up choice must not depend on iteration order");
    }

    @Test
    void firstsCloseDropsHiddenRunnersNoRunnerKeysAndNegativeGaps() {
        List<RankingStatsModel.CloseWorldFirst> worldFirsts = List.of(
                first(1, U1, "Alice", 100),
                first(2, U2, "Bob", 500));
        // U6 has a faster key-1 record but is hidden, so the tree never passes it to the model:
        // neither the chosen runner-up nor the reported gap may leak that record.
        List<RankingStatsModel.WhoHasPlayer> visiblePlayers = List.of(
                visible(U3, "Carol", rec(1, 150, 10), rec(2, 400, 20)),
                visible(U6, "Aaron", rec(1, 110, 30)));
        List<RankingStatsModel.CloseEntry> entries =
                RankingStatsModel.firstsClose(worldFirsts, visiblePlayers.subList(0, 1));
        assertEquals(List.of(new RankingStatsModel.CloseEntry(
                "minecraft:item_1", 1, "Alice", "Carol", 50L)), entries);
        // Visibility is the caller's responsibility (as in PR-1/PR-2): only the filtered list
        // reaches the model, so a hidden player's ticks cannot become a gap baseline.
        assertEquals(50L, RankingStatsModel.firstsClose(worldFirsts, visiblePlayers.subList(0, 1))
                .get(0).gapTicks());
        assertEquals(List.of(), RankingStatsModel.firstsClose(List.of(first(2, U2, "Bob", 500)),
                List.of(visible(U3, "Carol", rec(2, 400, 20)))));
    }

    @Test
    void firstsCloseSortsByGapThenTargetThenRunnerName() {
        List<RankingStatsModel.CloseWorldFirst> worldFirsts = List.of(
                first(2, U2, "Bob", 100),
                first(1, U1, "Alice", 100),
                first(3, U3, "Carol", 10));
        List<RankingStatsModel.WhoHasPlayer> players = List.of(
                visible(U4, "Zed", rec(2, 110, 20)),
                visible(U5, "Yan", rec(1, 105, 20)),
                visible(U6, "Aaron", rec(3, 20, 20)));

        assertEquals(List.of("minecraft:item_1", "minecraft:item_2", "minecraft:item_3"),
                RankingStatsModel.firstsClose(worldFirsts, players).stream()
                        .map(RankingStatsModel.CloseEntry::itemId).toList());
    }

    @Test
    void firstsCloseCapsTheDefaultViewAtTenRows() {
        List<RankingStatsModel.CloseWorldFirst> worldFirsts = new ArrayList<>();
        List<RankingStatsModel.WhoHasPlayer> players = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            worldFirsts.add(first(i + 1, U1, "Alice", 100));
            players.add(visible(new UUID(0, i + 100), "P" + i, rec(i + 1, 100 + i, i)));
        }
        List<RankingStatsModel.CloseEntry> all = RankingStatsModel.firstsClose(worldFirsts, players);
        assertEquals(12, all.size());
        List<RankingStatsModel.CloseEntry> capped = RankingStatsModel.firstsClose(
                worldFirsts, players, RankingStatsModel.CLOSE_LIMIT);
        assertEquals(10, capped.size());
        assertEquals(0L, capped.get(0).gapTicks());
        assertEquals(9L, capped.get(9).gapTicks());
        assertTrue(RankingStatsModel.firstsClose(worldFirsts, players, 0).isEmpty());
    }
}
