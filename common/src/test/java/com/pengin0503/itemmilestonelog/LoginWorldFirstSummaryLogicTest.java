package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.api.WorldFirstRecord;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginWorldFirstSummaryLogicTest {
    private static final UUID VIEWER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final long PREVIOUS = 1_000L;
    private static final long NOW = 2_000L;

    private static WorldFirstRecord worldFirst(String itemId, int count, UUID holder, long epochMillis) {
        return new WorldFirstRecord(itemId, count, holder, "Holder", 1_200, 3L, epochMillis);
    }

    private static Map<TargetKey, WorldFirstRecord> mapOf(WorldFirstRecord... records) {
        Map<TargetKey, WorldFirstRecord> map = new LinkedHashMap<>();
        for (WorldFirstRecord record : records) {
            map.put(record.targetKey(), record);
        }
        return map;
    }

    @Test
    void previousLastSeenZeroSelectsNothing() {
        Map<TargetKey, WorldFirstRecord> worldFirsts = mapOf(
                worldFirst("minecraft:diamond", 1, OTHER, 1_500L),
                worldFirst("minecraft:emerald", 1, VIEWER, 1_500L));

        for (long previous : new long[]{0L, -1L}) {
            LoginWorldFirstSummaryLogic.Selection selection =
                    LoginWorldFirstSummaryLogic.select(worldFirsts, VIEWER, previous, NOW);

            assertTrue(selection.isEmpty());
            assertEquals(0, selection.totalCount());
            assertTrue(selection.entries().isEmpty());
            assertEquals(0, selection.remainingCount());
        }
    }

    @Test
    void excludesOwnWorldFirstsAndEntriesOutsideTheWindow() {
        Map<TargetKey, WorldFirstRecord> worldFirsts = mapOf(
                worldFirst("minecraft:own_new", 1, VIEWER, 1_800L),
                worldFirst("minecraft:unknown_epoch", 1, OTHER, 0L),
                worldFirst("minecraft:exactly_previous", 1, OTHER, PREVIOUS),
                worldFirst("minecraft:just_after_previous", 1, OTHER, PREVIOUS + 1L),
                worldFirst("minecraft:exactly_now", 1, OTHER, NOW),
                worldFirst("minecraft:after_now", 1, OTHER, NOW + 1L));

        LoginWorldFirstSummaryLogic.Selection selection =
                LoginWorldFirstSummaryLogic.select(worldFirsts, VIEWER, PREVIOUS, NOW);

        assertEquals(2, selection.totalCount());
        assertEquals(0, selection.remainingCount());
        assertEquals(List.of(
                        TargetKey.of("minecraft:exactly_now"),
                        TargetKey.of("minecraft:just_after_previous")),
                selection.entries().stream().map(WorldFirstRecord::targetKey).toList());
    }

    @Test
    void includesBothWindowBoundariesAndExcludesThePreviousLoginTime() {
        Map<TargetKey, WorldFirstRecord> worldFirsts = mapOf(
                worldFirst("minecraft:before", 1, OTHER, PREVIOUS - 1L),
                worldFirst("minecraft:previous", 1, OTHER, PREVIOUS),
                worldFirst("minecraft:after", 1, OTHER, PREVIOUS + 1L),
                worldFirst("minecraft:last", 1, OTHER, NOW),
                worldFirst("minecraft:future", 1, OTHER, NOW + 1L));

        LoginWorldFirstSummaryLogic.Selection selection =
                LoginWorldFirstSummaryLogic.select(worldFirsts, VIEWER, PREVIOUS, NOW);

        assertEquals(List.of(
                        TargetKey.of("minecraft:last"),
                        TargetKey.of("minecraft:after")),
                selection.entries().stream().map(WorldFirstRecord::targetKey).toList());
        assertEquals(2, selection.totalCount());
    }

    @Test
    void sortsNewestFirstWithTargetKeyOrderForTiesAndLimitsToFive() {
        Map<TargetKey, WorldFirstRecord> worldFirsts = mapOf(
                worldFirst("minecraft:gold_ingot", 1, OTHER, 600L),
                worldFirst("minecraft:cherry", 1, OTHER, 900L),
                worldFirst("minecraft:diamond", 2, OTHER, 800L),
                worldFirst("minecraft:apple", 1, OTHER, 1_000L),
                worldFirst("minecraft:emerald", 1, OTHER, 800L),
                worldFirst("minecraft:banana", 1, OTHER, 900L),
                worldFirst("minecraft:iron_ingot", 1, OTHER, 700L));

        LoginWorldFirstSummaryLogic.Selection selection =
                LoginWorldFirstSummaryLogic.select(worldFirsts, VIEWER, 500L, 2_000L);

        assertEquals(List.of(
                        TargetKey.of("minecraft:apple"),
                        TargetKey.of("minecraft:banana"),
                        TargetKey.of("minecraft:cherry"),
                        TargetKey.of("minecraft:diamond", 2),
                        TargetKey.of("minecraft:emerald")),
                selection.entries().stream().map(WorldFirstRecord::targetKey).toList());
        assertEquals(7, selection.totalCount());
        assertEquals(2, selection.remainingCount());
        assertFalse(selection.isEmpty());
    }

    @Test
    void fiveOrFewerEntriesAreAllShownWithoutARemainingCount() {
        Map<TargetKey, WorldFirstRecord> worldFirsts = mapOf(
                worldFirst("minecraft:a", 1, OTHER, 1_100L),
                worldFirst("minecraft:b", 1, OTHER, 1_200L),
                worldFirst("minecraft:c", 1, OTHER, 1_300L),
                worldFirst("minecraft:d", 1, OTHER, 1_400L),
                worldFirst("minecraft:e", 1, OTHER, 1_500L));

        LoginWorldFirstSummaryLogic.Selection selection =
                LoginWorldFirstSummaryLogic.select(worldFirsts, VIEWER, PREVIOUS, NOW);

        assertEquals(5, selection.entries().size());
        assertEquals(5, selection.totalCount());
        assertEquals(0, selection.remainingCount());
        assertEquals(
                List.of("minecraft:e", "minecraft:d", "minecraft:c", "minecraft:b", "minecraft:a"),
                selection.entries().stream().map(WorldFirstRecord::itemId).toList());
    }

    @Test
    void tieAtTheSameTimestampUsesTargetKeyOrderIncludingCount() {
        Map<TargetKey, WorldFirstRecord> worldFirsts = mapOf(
                worldFirst("minecraft:diamond", 2, OTHER, 1_500L),
                worldFirst("minecraft:apple", 1, OTHER, 1_500L),
                worldFirst("minecraft:diamond", 1, OTHER, 1_500L));

        LoginWorldFirstSummaryLogic.Selection selection =
                LoginWorldFirstSummaryLogic.select(worldFirsts, VIEWER, PREVIOUS, NOW);

        assertEquals(List.of(
                        TargetKey.of("minecraft:apple"),
                        TargetKey.of("minecraft:diamond", 1),
                        TargetKey.of("minecraft:diamond", 2)),
                selection.entries().stream().map(WorldFirstRecord::targetKey).toList());
    }

    @Test
    void nullOrEmptyMapSelectsNothing() {
        assertTrue(LoginWorldFirstSummaryLogic.select(null, VIEWER, PREVIOUS, NOW).isEmpty());
        assertTrue(LoginWorldFirstSummaryLogic.select(Map.of(), VIEWER, PREVIOUS, NOW).isEmpty());
    }
}
