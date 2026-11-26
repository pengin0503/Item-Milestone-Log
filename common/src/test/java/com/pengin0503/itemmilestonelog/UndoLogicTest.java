package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F23 undo: restore rules (re-record conflict, progressNotified, completedPresets) and the snapshot store. */
class UndoLogicTest {
    private record Rec(int ticks, String label) {
    }

    private static final UUID PLAYER_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID PLAYER_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static UndoLogic.Result<String, Rec> restore(UndoLogic.Snapshot<String, Rec> snapshot,
                                                         Map<String, Rec> records,
                                                         Set<String> completed,
                                                         Map<String, Integer> progress) {
        return restore(snapshot, records, completed, progress, Map.of());
    }

    private static UndoLogic.Result<String, Rec> restore(UndoLogic.Snapshot<String, Rec> snapshot,
                                                         Map<String, Rec> records,
                                                         Set<String> completed,
                                                         Map<String, Integer> progress,
                                                         Map<String, PresetState> presetState) {
        return UndoLogic.restore(snapshot, records, completed, progress, presetState, Set.of(), 1024, Rec::ticks);
    }

    private static UndoLogic.Snapshot<String, Rec> resetSnapshot(Map<String, Rec> records, Set<String> completed,
                                                                 Map<String, Integer> progress, long at) {
        return resetSnapshot(records, completed, progress, Map.of(), at);
    }

    private static UndoLogic.Snapshot<String, Rec> resetSnapshot(Map<String, Rec> records, Set<String> completed,
                                                                 Map<String, Integer> progress,
                                                                 Map<String, PresetState> presetState, long at) {
        return UndoLogic.Snapshot.reset(records, completed, progress, presetState, at);
    }

    @Test
    void restoresRemovedRecordsIntoEmptyData() {
        Map<String, Rec> removed = new LinkedHashMap<>();
        removed.put("a", new Rec(100, "old-a"));
        removed.put("b", new Rec(200, "old-b"));
        UndoLogic.Result<String, Rec> result = restore(resetSnapshot(removed, Set.of(), Map.of(), 0L),
                Map.of(), Set.of(), Map.of());

        assertEquals(removed, result.records());
        assertEquals(2, result.restoredRecords());
        assertEquals(0, result.restoredTargets());
    }

    @Test
    void reRecordedKeyKeepsSmallerPlayTicks() {
        Map<String, Rec> removed = new LinkedHashMap<>();
        removed.put("earlier", new Rec(100, "snapshot"));
        removed.put("later", new Rec(900, "snapshot"));
        removed.put("tie", new Rec(500, "snapshot"));
        Map<String, Rec> current = new LinkedHashMap<>();
        current.put("earlier", new Rec(300, "current"));
        current.put("later", new Rec(400, "current"));
        current.put("tie", new Rec(500, "current"));
        current.put("other", new Rec(50, "current"));

        UndoLogic.Result<String, Rec> result = restore(resetSnapshot(removed, Set.of(), Map.of(), 0L),
                current, Set.of(), Map.of());

        assertEquals(new Rec(100, "snapshot"), result.records().get("earlier"));
        assertEquals(new Rec(400, "current"), result.records().get("later"));
        assertEquals(new Rec(500, "current"), result.records().get("tie"));
        assertEquals(new Rec(50, "current"), result.records().get("other"));
        assertEquals(1, result.restoredRecords());
    }

    @Test
    void doesNotModifyInputs() {
        Map<String, Rec> current = new LinkedHashMap<>(Map.of("x", new Rec(1, "c")));
        Set<String> completed = new LinkedHashSet<>(Set.of("p1"));
        Map<String, Integer> progress = new LinkedHashMap<>(Map.of("y", 50));
        restore(resetSnapshot(Map.of("a", new Rec(1, "s")), Set.of("p2"), Map.of("z", 25), 0L),
                current, completed, progress);

        assertEquals(Map.of("x", new Rec(1, "c")), current);
        assertEquals(Set.of("p1"), completed);
        assertEquals(Map.of("y", 50), progress);
    }

    @Test
    void completedPresetsAreUnionedBack() {
        UndoLogic.Result<String, Rec> result = restore(
                resetSnapshot(Map.of("a", new Rec(1, "s")), new LinkedHashSet<>(List.of("p1", "p2")), Map.of(), 0L),
                Map.of(), new LinkedHashSet<>(List.of("p2", "p3")), Map.of());

        assertEquals(Set.of("p1", "p2", "p3"), result.completedPresets());
    }

    @Test
    void presetStateIsMergedBackWithOrAndMaxSemantics() {
        Map<String, PresetState> removed = new LinkedHashMap<>();
        removed.put("old_only", new PresetState(true, 3));
        removed.put("both", new PresetState(true, 2));
        removed.put("newer_current", new PresetState(false, 1));
        Map<String, PresetState> current = new LinkedHashMap<>();
        current.put("both", new PresetState(false, 5));
        current.put("newer_current", new PresetState(true, 4));
        current.put("added_after", new PresetState(true, 1));

        UndoLogic.Result<String, Rec> result = restore(
                resetSnapshot(Map.of(), Set.of(), Map.of(), removed, 0L),
                Map.of(), Set.of(), Map.of(), current);

        assertEquals(Map.of(
                "old_only", new PresetState(true, 3),
                "both", new PresetState(true, 5),
                "newer_current", new PresetState(true, 4),
                "added_after", new PresetState(true, 1)
        ), result.presetState());
    }

    @Test
    void progressNotifiedRestoredOnlyForStillUnrecordedKeysWithoutEntry() {
        Map<String, Integer> removedProgress = new LinkedHashMap<>();
        removedProgress.put("unrecorded-free", 50);
        removedProgress.put("unrecorded-has-entry", 75);
        removedProgress.put("recorded-by-other-means", 25);
        Map<String, Integer> currentProgress = new LinkedHashMap<>();
        currentProgress.put("unrecorded-has-entry", 10);
        Map<String, Rec> current = new LinkedHashMap<>();
        current.put("recorded-by-other-means", new Rec(10, "c"));

        UndoLogic.Result<String, Rec> result = restore(
                resetSnapshot(Map.of("a", new Rec(1, "s")), Set.of(), removedProgress, 0L),
                current, Set.of(), currentProgress);

        assertEquals(50, result.progressNotified().get("unrecorded-free"));
        assertEquals(10, result.progressNotified().get("unrecorded-has-entry"));
        assertFalse(result.progressNotified().containsKey("recorded-by-other-means"));
    }

    @Test
    void restoredRecordKeysHaveNoProgressNotifiedEntry() {
        // An alert after the reset created an entry for "a"; once "a"'s record is back, the entry must go (§1.1).
        Map<String, Integer> currentProgress = new LinkedHashMap<>();
        currentProgress.put("a", 50);
        currentProgress.put("b", 25);
        UndoLogic.Result<String, Rec> result = restore(
                resetSnapshot(Map.of("a", new Rec(1, "s")), Set.of(), Map.of("a", 75), 0L),
                Map.of(), Set.of(), currentProgress);

        assertFalse(result.progressNotified().containsKey("a"));
        assertEquals(25, result.progressNotified().get("b"));
    }

    @Test
    void bulkRemovalRestoresTargetsWithoutDuplicatesAndRespectsLimit() {
        UndoLogic.Snapshot<String, Rec> snapshot = UndoLogic.Snapshot.bulkRemoval(
                List.of("t1", "t2", "t3", "t4"), Map.of("t1", 50, "t4", 25), 0L);
        Set<String> currentTargets = new LinkedHashSet<>(List.of("t2", "x"));

        UndoLogic.Result<String, Rec> result = UndoLogic.restore(snapshot, Map.of(), Set.of(), Map.of(), Map.of(),
                currentTargets, 4, Rec::ticks);

        assertEquals(List.of("t2", "x", "t1", "t3"), List.copyOf(result.targets()));
        assertEquals(2, result.restoredTargets());
        assertEquals(1, result.skippedTargetsLimit());
        assertEquals(50, result.progressNotified().get("t1"));
        assertEquals(25, result.progressNotified().get("t4"));
        assertEquals(0, result.restoredRecords());
    }

    @Test
    void emptyOperationsAreEmptySnapshots() {
        assertTrue(resetSnapshot(Map.of(), Set.of("p1"), Map.of("a", 50), 0L).isEmpty());
        assertTrue(resetSnapshot(Map.of(), Set.of(), Map.of(), 0L).isEmpty());
        assertFalse(resetSnapshot(Map.of("a", new Rec(1, "s")), Set.of(), Map.of(), 0L).isEmpty());
        assertFalse(resetSnapshot(Map.of(), Set.of(), Map.of(),
                Map.of("p", new PresetState(true, 1)), 0L).isEmpty());
        assertTrue(UndoLogic.Snapshot.<String, Rec>bulkRemoval(List.of(), Map.of(), 0L).isEmpty());
        assertFalse(UndoLogic.Snapshot.<String, Rec>bulkRemoval(List.of("t"), Map.of(), 0L).isEmpty());
    }

    @Test
    void storeDoesNotOverwriteWithEmptyOperation() {
        UndoLogic.Store<String, Rec> store = new UndoLogic.Store<>();
        UndoLogic.Snapshot<String, Rec> first = resetSnapshot(Map.of("a", new Rec(1, "s")), Set.of(), Map.of(), 0L);
        assertTrue(store.offer(PLAYER_A, first));
        assertFalse(store.offer(PLAYER_A, resetSnapshot(Map.of(), Set.of("p"), Map.of(), 10L)));
        assertFalse(store.offer(PLAYER_A, UndoLogic.Snapshot.bulkRemoval(List.of(), Map.of(), 10L)));
        assertSame(first, store.peekValid(PLAYER_A, 20L));
    }

    @Test
    void storeReplacesWithNewNonEmptyOperationAndIsPerPlayer() {
        UndoLogic.Store<String, Rec> store = new UndoLogic.Store<>();
        UndoLogic.Snapshot<String, Rec> first = resetSnapshot(Map.of("a", new Rec(1, "s")), Set.of(), Map.of(), 0L);
        UndoLogic.Snapshot<String, Rec> second = UndoLogic.Snapshot.bulkRemoval(List.of("t"), Map.of(), 5L);
        UndoLogic.Snapshot<String, Rec> other = resetSnapshot(Map.of("b", new Rec(2, "s")), Set.of(), Map.of(), 0L);
        store.offer(PLAYER_A, first);
        store.offer(PLAYER_B, other);
        store.offer(PLAYER_A, second);

        assertSame(second, store.peekValid(PLAYER_A, 6L));
        assertSame(other, store.peekValid(PLAYER_B, 6L));
    }

    @Test
    void snapshotExpiresAfterTenMinutes() {
        long start = 1_000L;
        UndoLogic.Snapshot<String, Rec> snapshot = resetSnapshot(Map.of("a", new Rec(1, "s")), Set.of(), Map.of(), start);
        assertEquals(600_000_000_000L, UndoLogic.VALIDITY_NANOS);
        assertFalse(snapshot.isExpired(start));
        assertFalse(snapshot.isExpired(start + UndoLogic.VALIDITY_NANOS));
        assertTrue(snapshot.isExpired(start + UndoLogic.VALIDITY_NANOS + 1));

        UndoLogic.Store<String, Rec> store = new UndoLogic.Store<>();
        store.offer(PLAYER_A, snapshot);
        assertSame(snapshot, store.peekValid(PLAYER_A, start + UndoLogic.VALIDITY_NANOS));
        assertNull(store.peekValid(PLAYER_A, start + UndoLogic.VALIDITY_NANOS + 1));
        assertEquals(0, store.size());
    }

    @Test
    void expiryIsSafeAcrossNanoTimeOverflow() {
        long start = Long.MAX_VALUE - 10L;
        UndoLogic.Snapshot<String, Rec> snapshot = resetSnapshot(Map.of("a", new Rec(1, "s")), Set.of(), Map.of(), start);
        assertFalse(snapshot.isExpired(start + 100L));
        assertTrue(snapshot.isExpired(start + UndoLogic.VALIDITY_NANOS + 1));
    }

    @Test
    void consumeRemovesOnlyTheGivenSnapshot() {
        UndoLogic.Store<String, Rec> store = new UndoLogic.Store<>();
        UndoLogic.Snapshot<String, Rec> first = resetSnapshot(Map.of("a", new Rec(1, "s")), Set.of(), Map.of(), 0L);
        UndoLogic.Snapshot<String, Rec> second = resetSnapshot(Map.of("b", new Rec(2, "s")), Set.of(), Map.of(), 1L);
        store.offer(PLAYER_A, first);
        store.offer(PLAYER_A, second);
        store.consume(PLAYER_A, first);
        assertSame(second, store.peekValid(PLAYER_A, 2L));
        store.consume(PLAYER_A, second);
        assertNull(store.peekValid(PLAYER_A, 2L));

        store.offer(PLAYER_B, first);
        store.clear();
        assertNull(store.peekValid(PLAYER_B, 0L));
    }
}
