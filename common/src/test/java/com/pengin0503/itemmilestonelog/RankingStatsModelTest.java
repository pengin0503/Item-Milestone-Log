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
}
