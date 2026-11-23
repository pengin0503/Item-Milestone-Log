package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MilestoneRankingsTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID D = UUID.fromString("00000000-0000-0000-0000-00000000000d");
    private static final UUID E = UUID.fromString("00000000-0000-0000-0000-00000000000e");

    private static final TargetKey DIAMOND = TargetKey.of("minecraft:diamond");
    private static final TargetKey IRON = TargetKey.of("minecraft:iron_ingot");
    private static final TargetKey GOLD = TargetKey.of("minecraft:gold_ingot");
    private static final TargetKey EMERALD = TargetKey.of("minecraft:emerald");
    private static final TargetKey DIAMOND_64 = TargetKey.of("minecraft:diamond", 64);

    private static final MilestoneRankings.Viewer CONSOLE = new MilestoneRankings.Viewer(null, true);

    private static MilestoneData.PlayerData player(String name, boolean hidden, Object... keyAndRecord) {
        MilestoneData.PlayerData pd = new MilestoneData.PlayerData();
        pd.lastKnownName = name;
        pd.hideFromLookup = hidden;
        for (int i = 0; i < keyAndRecord.length; i += 2) {
            pd.records.put((TargetKey) keyAndRecord[i], (MilestoneRecord) keyAndRecord[i + 1]);
        }
        return pd;
    }

    private static MilestoneRecord rec(int ticks, long epoch) {
        return new MilestoneRecord(ticks, 1L, epoch);
    }

    private static MilestoneRankings.Viewer player(UUID uuid) {
        return new MilestoneRankings.Viewer(uuid, false);
    }

    // ---------------------------------------------------------------- top

    @Test
    void topUsesCompetitionRankingAndOrdersTiesByNameCaseInsensitively() {
        Map<UUID, MilestoneData.PlayerData> players = new LinkedHashMap<>();
        players.put(A, player("zed", false, DIAMOND, rec(1, 1), IRON, rec(1, 1), GOLD, rec(1, 1)));
        players.put(B, player("bob", false, DIAMOND, rec(1, 1), IRON, rec(1, 1)));
        players.put(C, player("Alice", false, DIAMOND, rec(1, 1), IRON, rec(1, 1)));
        players.put(D, player("carol", false, DIAMOND, rec(1, 1)));
        players.put(E, player("empty", false));

        MilestoneRankings.Ranking<MilestoneRankings.CountEntry> r = MilestoneRankings.topByCount(players, CONSOLE);

        assertEquals(List.of("zed", "Alice", "bob", "carol"), r.entries().stream().map(MilestoneRankings.CountEntry::name).toList());
        assertEquals(List.of(1, 2, 2, 4), r.entries().stream().map(MilestoneRankings.CountEntry::rank).toList());
        assertEquals(List.of(3, 2, 2, 1), r.entries().stream().map(MilestoneRankings.CountEntry::count).toList());
        assertNull(r.self(), "console has no own row");
    }

    @Test
    void topExcludesPlayersWithoutRecordsAndReportsNoSelfRowForThem() {
        Map<UUID, MilestoneData.PlayerData> players = new LinkedHashMap<>();
        players.put(A, player("a", false, DIAMOND, rec(1, 1)));
        players.put(B, player("b", false));

        MilestoneRankings.Ranking<MilestoneRankings.CountEntry> r = MilestoneRankings.topByCount(players, player(B));

        assertEquals(1, r.entries().size());
        assertNull(r.self());
    }

    @Test
    void topHidesHiddenPlayersFromOthersButAlwaysIncludesViewerAndPrivileged() {
        Map<UUID, MilestoneData.PlayerData> players = new LinkedHashMap<>();
        players.put(A, player("hiddenTop", true, DIAMOND, rec(1, 1), IRON, rec(1, 1), GOLD, rec(1, 1)));
        players.put(B, player("visible", false, DIAMOND, rec(1, 1), IRON, rec(1, 1)));
        players.put(C, player("me", true, DIAMOND, rec(1, 1)));

        MilestoneRankings.Ranking<MilestoneRankings.CountEntry> asC = MilestoneRankings.topByCount(players, player(C));
        assertEquals(List.of("visible", "me"), asC.entries().stream().map(MilestoneRankings.CountEntry::name).toList());
        assertNotNull(asC.self());
        assertEquals(2, asC.self().rank(), "rank is computed among players visible to the viewer");
        assertEquals(1, asC.self().count());

        MilestoneRankings.Ranking<MilestoneRankings.CountEntry> asA = MilestoneRankings.topByCount(players, player(A));
        assertEquals(List.of("hiddenTop", "visible"), asA.entries().stream().map(MilestoneRankings.CountEntry::name).toList());
        assertEquals(1, asA.self().rank());

        MilestoneRankings.Ranking<MilestoneRankings.CountEntry> asOp =
                MilestoneRankings.topByCount(players, new MilestoneRankings.Viewer(B, true));
        assertEquals(3, asOp.entries().size());
        assertEquals(2, asOp.self().rank());
    }

    @Test
    void topFallsBackToUuidWhenNameIsEmpty() {
        Map<UUID, MilestoneData.PlayerData> players = new LinkedHashMap<>();
        players.put(A, player("", false, DIAMOND, rec(1, 1)));

        assertEquals(A.toString(), MilestoneRankings.topByCount(players, CONSOLE).entries().get(0).name());
    }

    // ---------------------------------------------------------------- top <item>

    @Test
    void topItemOrdersByPlayTicksThenEpochUnknownLastThenName() {
        Map<UUID, MilestoneData.PlayerData> players = new LinkedHashMap<>();
        players.put(A, player("a-unknown", false, DIAMOND, rec(100, 0)));
        players.put(B, player("b-late", false, DIAMOND, rec(100, 2000)));
        players.put(C, player("c-early", false, DIAMOND, rec(100, 1000)));
        players.put(D, player("Aaron", false, DIAMOND, rec(100, 1000)));
        players.put(E, player("fast", false, DIAMOND, rec(50, 5000), IRON, rec(1, 1)));

        MilestoneRankings.Ranking<MilestoneRankings.ItemEntry> r = MilestoneRankings.topByItem(players, DIAMOND, player(A));

        assertEquals(List.of("fast", "Aaron", "c-early", "b-late", "a-unknown"),
                r.entries().stream().map(MilestoneRankings.ItemEntry::name).toList());
        assertEquals(List.of(1, 2, 2, 2, 2), r.entries().stream().map(MilestoneRankings.ItemEntry::rank).toList());
        assertNotNull(r.self());
        assertEquals(2, r.self().rank());
        assertEquals(rec(100, 0), r.self().record());
    }

    @Test
    void topItemMatchesExactTargetKeyIncludingCount() {
        Map<UUID, MilestoneData.PlayerData> players = new LinkedHashMap<>();
        players.put(A, player("a", false, DIAMOND, rec(10, 1)));
        players.put(B, player("b", false, DIAMOND_64, rec(20, 1)));

        MilestoneRankings.Ranking<MilestoneRankings.ItemEntry> r = MilestoneRankings.topByItem(players, DIAMOND_64, player(A));

        assertEquals(List.of("b"), r.entries().stream().map(MilestoneRankings.ItemEntry::name).toList());
        assertNull(r.self(), "viewer has not recorded diamond x64");
    }

    @Test
    void topItemExcludesHiddenHoldersButKeepsViewer() {
        Map<UUID, MilestoneData.PlayerData> players = new LinkedHashMap<>();
        players.put(A, player("hidden", true, DIAMOND, rec(1, 1)));
        players.put(B, player("me", true, DIAMOND, rec(5, 1)));
        players.put(C, player("other", false, DIAMOND, rec(3, 1)));

        MilestoneRankings.Ranking<MilestoneRankings.ItemEntry> r = MilestoneRankings.topByItem(players, DIAMOND, player(B));

        assertEquals(List.of("other", "me"), r.entries().stream().map(MilestoneRankings.ItemEntry::name).toList());
        assertEquals(2, r.self().rank());
    }

    // ---------------------------------------------------------------- recent

    @Test
    void recentSortsNewestFirstExcludesUnknownEpochAndMarksWorldFirst() {
        Map<UUID, MilestoneData.PlayerData> players = new LinkedHashMap<>();
        players.put(A, player("a", false, DIAMOND, rec(1, 3000), IRON, rec(1, 0), GOLD, rec(1, 1000)));
        players.put(B, player("b", false, DIAMOND, rec(1, 4000), EMERALD, rec(1, 2000)));
        Map<TargetKey, UUID> firsts = Map.of(DIAMOND, A, EMERALD, B);

        List<MilestoneRankings.RecentEntry> rows = MilestoneRankings.recent(players, firsts, player(A), 10);

        assertEquals(List.of(4000L, 3000L, 2000L, 1000L), rows.stream().map(r -> r.record().epochMillis()).toList());
        assertEquals(List.of("b", "a", "b", "a"), rows.stream().map(MilestoneRankings.RecentEntry::name).toList());
        assertEquals(List.of(false, true, true, false), rows.stream().map(MilestoneRankings.RecentEntry::worldFirst).toList());
        assertTrue(rows.stream().noneMatch(r -> r.key().equals(IRON)), "epochMillis 0 records are excluded");
    }

    @Test
    void recentHonoursLimitAndIsEmptyWhenOnlyUndatedRecordsExist() {
        Map<UUID, MilestoneData.PlayerData> players = new LinkedHashMap<>();
        players.put(A, player("a", false, DIAMOND, rec(1, 3000), IRON, rec(1, 2000), GOLD, rec(1, 1000)));
        assertEquals(List.of(DIAMOND, IRON),
                MilestoneRankings.recent(players, Map.of(), CONSOLE, 2).stream().map(MilestoneRankings.RecentEntry::key).toList());

        Map<UUID, MilestoneData.PlayerData> legacy = new LinkedHashMap<>();
        legacy.put(A, player("a", false, DIAMOND, MilestoneRecord.legacy(10)));
        assertTrue(MilestoneRankings.recent(legacy, Map.of(), CONSOLE, 10).isEmpty());
    }

    @Test
    void recentExcludesHiddenPlayersForOthersButIncludesViewerAndPrivileged() {
        Map<UUID, MilestoneData.PlayerData> players = new LinkedHashMap<>();
        players.put(A, player("hidden", true, DIAMOND, rec(1, 3000)));
        players.put(B, player("visible", false, IRON, rec(1, 2000)));

        assertEquals(List.of("visible"),
                MilestoneRankings.recent(players, Map.of(), player(B), 10).stream().map(MilestoneRankings.RecentEntry::name).toList());
        assertEquals(List.of("hidden", "visible"),
                MilestoneRankings.recent(players, Map.of(), player(A), 10).stream().map(MilestoneRankings.RecentEntry::name).toList());
        assertEquals(2, MilestoneRankings.recent(players, Map.of(), CONSOLE, 10).size());
    }

    @Test
    void recentTiesOnEpochAreOrderedByName() {
        Map<UUID, MilestoneData.PlayerData> players = new LinkedHashMap<>();
        players.put(A, player("zoe", false, DIAMOND, rec(1, 1000)));
        players.put(B, player("Amy", false, DIAMOND, rec(1, 1000)));

        List<MilestoneRankings.RecentEntry> rows = MilestoneRankings.recent(players, Map.of(DIAMOND, A), CONSOLE, 10);
        assertEquals(List.of("Amy", "zoe"), rows.stream().map(MilestoneRankings.RecentEntry::name).toList());
        assertFalse(rows.get(0).worldFirst());
        assertTrue(rows.get(1).worldFirst());
    }

    // ---------------------------------------------------------------- stats (F38)

    @Test
    void statsCountsRecordsPlayersAndRanksKeysByDistinctPlayersWithTiesInTargetKeyOrder() {
        Map<UUID, MilestoneData.PlayerData> players = new LinkedHashMap<>();
        players.put(A, player("a", false, DIAMOND, rec(1, 1), IRON, rec(1, 1), GOLD, rec(1, 1)));
        players.put(B, player("b", false, DIAMOND, rec(1, 1), IRON, rec(1, 1), EMERALD, rec(1, 1)));
        players.put(C, player("c", false, DIAMOND, rec(1, 1), DIAMOND_64, rec(1, 1)));
        players.put(D, player("empty", false));

        MilestoneRankings.ServerStats s = MilestoneRankings.stats(players, 4, player(D));

        assertFalse(s.isEmpty());
        assertEquals(8, s.totalRecords());
        assertEquals(3, s.players(), "players without records are not counted");
        assertEquals(4, s.worldFirsts());
        // diamond 3; iron 2; then emerald / diamond x64 / gold at 1 each, in TargetKey order
        assertEquals(List.of(DIAMOND, IRON, DIAMOND_64, EMERALD, GOLD),
                s.topKeys().stream().map(MilestoneRankings.KeyCountEntry::key).toList());
        assertEquals(List.of(1, 2, 3, 3, 3), s.topKeys().stream().map(MilestoneRankings.KeyCountEntry::rank).toList());
        assertEquals(List.of(3, 2, 1, 1, 1), s.topKeys().stream().map(MilestoneRankings.KeyCountEntry::players).toList());
    }

    @Test
    void statsTruncatesToTopFive() {
        TargetKey extra = TargetKey.of("minecraft:stick");
        Map<UUID, MilestoneData.PlayerData> players = new LinkedHashMap<>();
        players.put(A, player("a", false, DIAMOND, rec(1, 1), IRON, rec(1, 1), GOLD, rec(1, 1),
                EMERALD, rec(1, 1), DIAMOND_64, rec(1, 1), extra, rec(1, 1)));

        MilestoneRankings.ServerStats s = MilestoneRankings.stats(players, 0, CONSOLE);

        assertEquals(6, s.totalRecords());
        assertEquals(MilestoneRankings.STATS_TOP_LIMIT, s.topKeys().size());
        assertFalse(s.topKeys().stream().anyMatch(e -> e.key().equals(extra)), "stick sorts last in TargetKey order");
    }

    @Test
    void statsExcludesHiddenPlayersForOthersButIncludesViewerAndPrivilegedWhileWorldFirstsStayGlobal() {
        Map<UUID, MilestoneData.PlayerData> players = new LinkedHashMap<>();
        players.put(A, player("hidden", true, DIAMOND, rec(1, 1), EMERALD, rec(1, 1)));
        players.put(B, player("visible", false, DIAMOND, rec(1, 1)));
        players.put(C, player("me", true, IRON, rec(1, 1)));

        MilestoneRankings.ServerStats asOther = MilestoneRankings.stats(players, 3, player(D));
        assertEquals(1, asOther.totalRecords());
        assertEquals(1, asOther.players());
        assertEquals(3, asOther.worldFirsts(), "world-first count is not affected by hiding");
        assertEquals(List.of(DIAMOND), asOther.topKeys().stream().map(MilestoneRankings.KeyCountEntry::key).toList());
        assertEquals(1, asOther.topKeys().get(0).players());

        MilestoneRankings.ServerStats asSelf = MilestoneRankings.stats(players, 3, player(C));
        assertEquals(2, asSelf.totalRecords(), "viewer's own hidden records are included");
        assertEquals(2, asSelf.players());
        assertEquals(3, asSelf.worldFirsts());
        assertEquals(List.of(DIAMOND, IRON), asSelf.topKeys().stream().map(MilestoneRankings.KeyCountEntry::key).toList());

        MilestoneRankings.ServerStats asOp = MilestoneRankings.stats(players, 3, new MilestoneRankings.Viewer(D, true));
        assertEquals(4, asOp.totalRecords());
        assertEquals(3, asOp.players());
        assertEquals(2, asOp.topKeys().get(0).players(), "diamond recorded by hidden and visible");
    }

    @Test
    void statsIsEmptyWhenNoVisibleRecordsEvenWithWorldFirsts() {
        Map<UUID, MilestoneData.PlayerData> players = new LinkedHashMap<>();
        players.put(A, player("hidden", true, DIAMOND, rec(1, 1)));
        players.put(B, player("empty", false));

        MilestoneRankings.ServerStats s = MilestoneRankings.stats(players, 1, player(B));
        assertTrue(s.isEmpty());
        assertEquals(0, s.players());
        assertTrue(s.topKeys().isEmpty());
        assertEquals(1, s.worldFirsts());

        assertTrue(MilestoneRankings.stats(Map.of(), 0, CONSOLE).isEmpty());
    }

    // ---------------------------------------------------------------- suggestions

    @Test
    void suggestionsListItemsRecordedByVisiblePlayersOnly() {
        Map<UUID, MilestoneData.PlayerData> players = new LinkedHashMap<>();
        players.put(A, player("hidden", true, EMERALD, rec(1, 1)));
        players.put(B, player("visible", false, DIAMOND, rec(1, 1), DIAMOND_64, rec(1, 1)));

        assertEquals(Set.of("minecraft:diamond"), MilestoneRankings.recordedItemIds(players, player(C)));
        assertEquals(Set.of("minecraft:diamond", "minecraft:emerald"), MilestoneRankings.recordedItemIds(players, player(A)));
        assertEquals(Set.of(1, 64), MilestoneRankings.recordedCounts(players, player(C), "minecraft:diamond"));
    }
}
