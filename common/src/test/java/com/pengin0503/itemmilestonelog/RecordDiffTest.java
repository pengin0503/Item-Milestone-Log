package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-150-70: new-record detection shared with the PR-3 HUD snapshot. */
class RecordDiffTest {
    @Test
    void detectsOnlyNewlyAppearedKeys() {
        Set<String> previous = Set.of("minecraft:diamond#1", "minecraft:iron#1");
        Set<String> current = Set.of("minecraft:diamond#1", "minecraft:iron#1", "minecraft:gold#2");
        assertEquals(Set.of("minecraft:gold#2"), RecordDiff.added(previous, current));
    }

    @Test
    void removedKeysNeverTriggerACapture() {
        Set<String> previous = Set.of("minecraft:diamond#1", "minecraft:iron#1");
        Set<String> current = Set.of("minecraft:diamond#1");
        assertTrue(RecordDiff.added(previous, current).isEmpty());
        assertFalse(RecordDiff.shouldCapture(previous, current, 0L, 5_000L));
    }

    @Test
    void firstSnapshotIsABaseline() {
        Set<String> current = Set.of("minecraft:diamond#1");
        assertEquals(current, RecordDiff.baseline(current));
        assertTrue(RecordDiff.added(null, current).contains("minecraft:diamond#1"),
                "pure diff treats an empty previous set as new; the caller seeds the baseline");
    }

    @Test
    void sameSecondArrivalsAreGroupedIntoOneCapture() {
        Set<String> empty = Set.of();
        Set<String> one = Set.of("minecraft:diamond#1");
        Set<String> two = Set.of("minecraft:diamond#1", "minecraft:gold#1");
        assertFalse(RecordDiff.captureDue(0L, 1_000L - 1));
        assertTrue(RecordDiff.captureDue(0L, 1_000L));
        assertTrue(RecordDiff.shouldCapture(empty, one, 1_000L, 2_000L));
        assertFalse(RecordDiff.shouldCapture(one, two, 2_000L, 2_500L),
                "a second arrival inside the same second must not capture again");
        assertTrue(RecordDiff.shouldCapture(one, two, 2_000L, 3_000L));
    }

    @Test
    void baselinePreservesEncounterOrderForTheHud() {
        assertEquals(List.of("a", "b", "c"),
                List.copyOf(RecordDiff.baseline(List.of("a", "b", "c", "a"))));
    }
}
