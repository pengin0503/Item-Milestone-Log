package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldFirstImportLogicTest {
    private record Entry(String key, String holder, long epochMillis) {
    }

    @Test
    void shouldReplaceFollowsEarlierWinsAndUnknownEpochRules() {
        // Both known (> 0): strictly earlier wins
        assertTrue(WorldFirstImportLogic.shouldReplace(2000L, 1000L));
        assertFalse(WorldFirstImportLogic.shouldReplace(1000L, 2000L));
        assertFalse(WorldFirstImportLogic.shouldReplace(1000L, 1000L));

        // Either unknown (0 or negative): keep existing
        assertFalse(WorldFirstImportLogic.shouldReplace(0L, 1000L));
        assertFalse(WorldFirstImportLogic.shouldReplace(1000L, 0L));
        assertFalse(WorldFirstImportLogic.shouldReplace(0L, 0L));
        assertFalse(WorldFirstImportLogic.shouldReplace(-1L, 1000L));
        assertFalse(WorldFirstImportLogic.shouldReplace(1000L, -1L));
    }

    @Test
    void mergeAddsMissingEntriesIncludingUnknownEpochWithoutMutatingInputs() {
        Entry existingDiamond = new Entry("minecraft:diamond", "Alice", 2000L);
        Map<String, Entry> current = new LinkedHashMap<>();
        current.put(existingDiamond.key(), existingDiamond);

        Entry newEmeraldKnown = new Entry("minecraft:emerald", "Bob", 3000L);
        Entry newIronUnknown = new Entry("minecraft:iron_ingot#64", "Charlie", 0L);
        List<Entry> incoming = List.of(newEmeraldKnown, newIronUnknown);

        WorldFirstImportLogic.MergeResult<String, Entry> result = WorldFirstImportLogic.merge(
                current, incoming, 0, Entry::key, Entry::epochMillis);

        assertEquals(2, result.updatedCount());
        assertEquals(0, result.skippedCount());
        assertEquals(3, result.worldFirsts().size());
        assertEquals(existingDiamond, result.worldFirsts().get("minecraft:diamond"));
        assertEquals(newEmeraldKnown, result.worldFirsts().get("minecraft:emerald"));
        assertEquals(newIronUnknown, result.worldFirsts().get("minecraft:iron_ingot#64"));

        // Input map was not modified, and result map is unmodifiable.
        assertEquals(1, current.size());
        assertThrows(UnsupportedOperationException.class, () -> result.worldFirsts().put("x", existingDiamond));
    }

    @Test
    void mergeResolvesConflictsWithEarlierWinsAndKeepsExistingWhenEitherEpochIsUnknown() {
        Entry existingEarlier = new Entry("minecraft:diamond", "Alice", 1000L);
        Entry existingLater = new Entry("minecraft:emerald", "Alice", 5000L);
        Entry existingTie = new Entry("minecraft:gold_ingot", "Alice", 3000L);
        Entry existingUnknown = new Entry("minecraft:iron_ingot", "Alice", 0L);
        Entry existingKnownForUnknownIncoming = new Entry("minecraft:coal", "Alice", 4000L);
        Entry existingUnknownBoth = new Entry("minecraft:redstone", "Alice", 0L);

        Map<String, Entry> current = new LinkedHashMap<>();
        for (Entry e : List.of(
                existingEarlier, existingLater, existingTie,
                existingUnknown, existingKnownForUnknownIncoming, existingUnknownBoth)) {
            current.put(e.key(), e);
        }

        Entry incomingLater = new Entry("minecraft:diamond", "Bob", 2000L);
        Entry incomingEarlier = new Entry("minecraft:emerald", "Bob", 2500L);
        Entry incomingTie = new Entry("minecraft:gold_ingot", "Bob", 3000L);
        Entry incomingKnownForUnknown = new Entry("minecraft:iron_ingot", "Bob", 1000L);
        Entry incomingUnknownForKnown = new Entry("minecraft:coal", "Bob", 0L);
        Entry incomingUnknownBoth = new Entry("minecraft:redstone", "Bob", 0L);

        WorldFirstImportLogic.MergeResult<String, Entry> result = WorldFirstImportLogic.merge(
                current,
                List.of(incomingLater, incomingEarlier, incomingTie,
                        incomingKnownForUnknown, incomingUnknownForKnown, incomingUnknownBoth),
                0,
                Entry::key,
                Entry::epochMillis);

        // Only minecraft:emerald was replaced (2500 < 5000).
        assertEquals(1, result.updatedCount());
        assertEquals(0, result.skippedCount());
        assertEquals(existingEarlier, result.worldFirsts().get("minecraft:diamond"));
        assertEquals(incomingEarlier, result.worldFirsts().get("minecraft:emerald"));
        assertEquals(existingTie, result.worldFirsts().get("minecraft:gold_ingot"));
        assertEquals(existingUnknown, result.worldFirsts().get("minecraft:iron_ingot"));
        assertEquals(existingKnownForUnknownIncoming, result.worldFirsts().get("minecraft:coal"));
        assertEquals(existingUnknownBoth, result.worldFirsts().get("minecraft:redstone"));
    }

    @Test
    void mergeSkipsNullEntriesAndNullKeysAndAccumulatesPreSkippedCount() {
        List<Entry> incoming = new ArrayList<>();
        incoming.add(null);
        incoming.add(new Entry(null, "BrokenKey", 1000L));
        incoming.add(new Entry("minecraft:diamond", "Alice", 1500L));
        incoming.add(null);

        WorldFirstImportLogic.MergeResult<String, Entry> result = WorldFirstImportLogic.merge(
                Map.of(), incoming, 3, Entry::key, Entry::epochMillis);

        assertEquals(1, result.updatedCount());
        assertEquals(6, result.skippedCount()); // 3 pre-skipped + 2 null elements + 1 null key
        assertEquals(Map.of("minecraft:diamond", new Entry("minecraft:diamond", "Alice", 1500L)), result.worldFirsts());
    }

    @Test
    void mergeHandlesDuplicateKeysInIncomingAndCountsEachChangedKeyOnce() {
        Entry first = new Entry("minecraft:diamond", "Alice", 3000L);
        Entry earlier = new Entry("minecraft:diamond", "Bob", 1000L);
        Entry later = new Entry("minecraft:diamond", "Charlie", 2000L);

        WorldFirstImportLogic.MergeResult<String, Entry> result = WorldFirstImportLogic.merge(
                Map.of(), List.of(first, earlier, later), 0, Entry::key, Entry::epochMillis);

        assertEquals(1, result.updatedCount());
        assertEquals(0, result.skippedCount());
        assertEquals(earlier, result.worldFirsts().get("minecraft:diamond"));
    }

    @Test
    void mergeHandlesNullCurrentAndNullIncoming() {
        WorldFirstImportLogic.MergeResult<String, Entry> result = WorldFirstImportLogic.merge(
                null, null, 2, Entry::key, Entry::epochMillis);

        assertEquals(0, result.updatedCount());
        assertEquals(2, result.skippedCount());
        assertTrue(result.worldFirsts().isEmpty());
    }
}
