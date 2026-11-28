package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        return UndoLogic.restore(snapshot, records, completed, progress, presetState, Set.of(),
                1024, Rec::ticks, Map.of());
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
                currentTargets, 4, Rec::ticks, Map.of());

        assertEquals(List.of("t2", "x", "t1", "t3"), List.copyOf(result.targets()));
        assertEquals(2, result.restoredTargets());
        assertEquals(1, result.skippedTargetsLimit());
        assertEquals(50, result.progressNotified().get("t1"));
        assertEquals(25, result.progressNotified().get("t4"));
        assertEquals(0, result.restoredRecords());
    }

    @Test
    void bulkRemovalRestoresRemovedTargetMetaWithoutOverwritingFreshValues() {
        // SF-2: a pinned/labelled/priority entry removed with its target must come back, but an entry
        // set after the removal (e.g. by re-adding the target) wins (putIfAbsent).
        Map<String, TargetMeta> removedMeta = new LinkedHashMap<>();
        removedMeta.put("t1", new TargetMeta(9, "main", true));
        removedMeta.put("t2", new TargetMeta(3, "old", false));
        UndoLogic.Snapshot<String, Rec> snapshot = UndoLogic.Snapshot.bulkRemoval(
                List.of("t1", "t2"), Map.of(), 0L, removedMeta);
        Map<String, TargetMeta> currentMeta = new LinkedHashMap<>();
        currentMeta.put("t1", new TargetMeta(1, "fresh", true));

        UndoLogic.Result<String, Rec> result = UndoLogic.restore(snapshot, Map.of(), Set.of(), Map.of(),
                Map.of(), Set.of(), 1024, Rec::ticks, currentMeta);

        assertEquals(new TargetMeta(1, "fresh", true), result.targetMeta().get("t1"));
        assertEquals(new TargetMeta(3, "old", false), result.targetMeta().get("t2"));
        assertEquals(2, result.restoredTargets());
        assertEquals(new TargetMeta(1, "fresh", true), currentMeta.get("t1"), "inputs are not mutated");
        assertThrows(UnsupportedOperationException.class, () -> snapshot.removedTargetMeta().put("x", TargetMeta.DEFAULT));
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
    void consumeInvalidatesTheGivenGenerationAndAllNewerOnes() {
        UndoLogic.Store<String, Rec> store = new UndoLogic.Store<>();
        UndoLogic.Snapshot<String, Rec> first = resetSnapshot(Map.of("a", new Rec(1, "s")), Set.of(), Map.of(), 0L);
        UndoLogic.Snapshot<String, Rec> second = resetSnapshot(Map.of("b", new Rec(2, "s")), Set.of(), Map.of(), 1L);
        UndoLogic.Snapshot<String, Rec> third = resetSnapshot(Map.of("c", new Rec(3, "s")), Set.of(), Map.of(), 2L);
        store.offer(PLAYER_A, first);
        store.offer(PLAYER_A, second);
        store.offer(PLAYER_A, third);

        store.consume(PLAYER_A, second); // middle generation: second and the newer third are invalid
        assertSame(first, store.peekValid(PLAYER_A, 3L));
        assertEquals(1, store.size());

        store.offer(PLAYER_A, second);
        store.offer(PLAYER_A, third);
        store.consume(PLAYER_A, third); // newest generation: only it is removed
        assertSame(second, store.peekValid(PLAYER_A, 3L));
        assertEquals(List.of(second, first), store.list(PLAYER_A, 3L));

        store.consume(PLAYER_A, second);
        assertSame(first, store.peekValid(PLAYER_A, 3L));
        store.consume(PLAYER_A, first);
        assertNull(store.peekValid(PLAYER_A, 3L));
        assertEquals(0, store.size());

        store.offer(PLAYER_B, first);
        store.clear();
        assertNull(store.peekValid(PLAYER_B, 0L));
        assertEquals(0, store.size());
    }

    @Test
    void historyRetainsFiveGenerationsNewestFirst() {
        assertEquals(5, UndoLogic.UNDO_HISTORY_DEPTH);
        UndoLogic.Store<String, Rec> store = new UndoLogic.Store<>();
        List<UndoLogic.Snapshot<String, Rec>> offered = new ArrayList<>();
        List<UndoLogic.Snapshot<String, Rec>> expectedNewestFirst = new ArrayList<>();
        for (int i = 1; i <= UndoLogic.UNDO_HISTORY_DEPTH; i++) {
            UndoLogic.Snapshot<String, Rec> snapshot =
                    resetSnapshot(Map.of("k" + i, new Rec(i, "s")), Set.of(), Map.of(), i);
            assertTrue(store.offer(PLAYER_A, snapshot));
            offered.add(snapshot);
        }
        for (int i = offered.size() - 1; i >= 0; i--) {
            expectedNewestFirst.add(offered.get(i));
        }

        assertEquals(5, store.size());
        assertEquals(expectedNewestFirst, store.list(PLAYER_A, 6L));
        assertSame(offered.get(0), store.get(PLAYER_A, 5, 6L));
    }

    @Test
    void sixthOfferDropsOldestTailWhileNewestStays() {
        UndoLogic.Store<String, Rec> store = new UndoLogic.Store<>();
        List<UndoLogic.Snapshot<String, Rec>> offered = new ArrayList<>();
        for (int i = 1; i <= UndoLogic.UNDO_HISTORY_DEPTH + 1; i++) {
            UndoLogic.Snapshot<String, Rec> snapshot =
                    resetSnapshot(Map.of("k" + i, new Rec(i, "s")), Set.of(), Map.of(), i);
            assertTrue(store.offer(PLAYER_A, snapshot));
            offered.add(snapshot);
        }

        assertEquals(UndoLogic.UNDO_HISTORY_DEPTH, store.size());
        assertSame(offered.get(5), store.peekValid(PLAYER_A, 7L));
        assertSame(offered.get(5), store.get(PLAYER_A, 1, 7L));
        assertSame(offered.get(1), store.get(PLAYER_A, 5, 7L));
        assertFalse(store.list(PLAYER_A, 7L).contains(offered.get(0)));
    }

    @Test
    void listIsNewestFirstAndPrunesExpiredSnapshotsAtEveryPosition() {
        long now = UndoLogic.VALIDITY_NANOS + 10L;
        UndoLogic.Snapshot<String, Rec> oldest = resetSnapshot(Map.of("oldest", new Rec(1, "s")), Set.of(), Map.of(), 10L);
        UndoLogic.Snapshot<String, Rec> expiredMiddle = resetSnapshot(Map.of("middle", new Rec(2, "s")), Set.of(), Map.of(), 9L);
        UndoLogic.Snapshot<String, Rec> newest = resetSnapshot(Map.of("newest", new Rec(3, "s")), Set.of(), Map.of(), now);
        UndoLogic.Store<String, Rec> store = new UndoLogic.Store<>();
        store.offer(PLAYER_A, oldest);
        store.offer(PLAYER_A, expiredMiddle);
        store.offer(PLAYER_A, newest);

        List<UndoLogic.Snapshot<String, Rec>> listed = store.list(PLAYER_A, now);
        assertEquals(List.of(newest, oldest), listed);
        assertEquals(2, store.size());
        assertThrows(UnsupportedOperationException.class, () -> listed.add(newest));
        assertSame(newest, store.peekValid(PLAYER_A, now));
        assertSame(newest, store.get(PLAYER_A, 1, now));
        assertSame(oldest, store.get(PLAYER_A, 2, now));

        long later = now + UndoLogic.VALIDITY_NANOS + 2L;
        assertTrue(store.list(PLAYER_A, later).isEmpty());
        assertNull(store.peekValid(PLAYER_A, later));
        assertEquals(0, store.size());
    }

    @Test
    void getSupportsFirstAndFifthGenerationAndRejectsOutOfRange() {
        UndoLogic.Store<String, Rec> store = new UndoLogic.Store<>();
        List<UndoLogic.Snapshot<String, Rec>> offered = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            UndoLogic.Snapshot<String, Rec> snapshot =
                    resetSnapshot(Map.of("k" + i, new Rec(i, "s")), Set.of(), Map.of(), i);
            store.offer(PLAYER_A, snapshot);
            offered.add(snapshot);
        }

        assertSame(offered.get(4), store.get(PLAYER_A, 1, 6L));
        assertSame(offered.get(0), store.get(PLAYER_A, 5, 6L));
        assertNull(store.get(PLAYER_A, 0, 6L));
        assertNull(store.get(PLAYER_A, 6, 6L));
        assertNull(store.get(PLAYER_B, 1, 6L));

        long later = UndoLogic.VALIDITY_NANOS + 2L;
        assertNull(store.get(PLAYER_A, 5, later));
        assertEquals(4, store.size());
        assertSame(offered.get(1), store.get(PLAYER_A, 4, later));
    }

    @Test
    void consumingMiddleGenerationDropsItAndNewerWhileOlderSurvives() {
        UndoLogic.Store<String, Rec> store = new UndoLogic.Store<>();
        UndoLogic.Snapshot<String, Rec> oldest = resetSnapshot(Map.of("a", new Rec(1, "s")), Set.of(), Map.of(), 0L);
        UndoLogic.Snapshot<String, Rec> middle = resetSnapshot(Map.of("b", new Rec(2, "s")), Set.of(), Map.of(), 1L);
        UndoLogic.Snapshot<String, Rec> newest = resetSnapshot(Map.of("c", new Rec(3, "s")), Set.of(), Map.of(), 2L);
        store.offer(PLAYER_A, oldest);
        store.offer(PLAYER_A, middle);
        store.offer(PLAYER_A, newest);

        store.consume(PLAYER_A, middle);

        assertEquals(List.of(oldest), store.list(PLAYER_A, 3L));
        assertSame(oldest, store.peekValid(PLAYER_A, 3L));
        assertEquals(1, store.size());
        store.consume(PLAYER_A, newest); // already invalidated: no-op
        assertSame(oldest, store.peekValid(PLAYER_A, 3L));
    }

    @Test
    void clearAndEmptyOrNullOffersLeaveANonEmptyStackUntouched() {
        UndoLogic.Store<String, Rec> store = new UndoLogic.Store<>();
        UndoLogic.Snapshot<String, Rec> first = resetSnapshot(Map.of("a", new Rec(1, "s")), Set.of(), Map.of(), 0L);
        UndoLogic.Snapshot<String, Rec> second = resetSnapshot(Map.of("b", new Rec(2, "s")), Set.of(), Map.of(), 1L);
        store.offer(PLAYER_A, first);
        store.offer(PLAYER_A, second);

        assertFalse(store.offer(PLAYER_A, resetSnapshot(Map.of(), Set.of("p"), Map.of(), 5L)));
        assertFalse(store.offer(PLAYER_A, UndoLogic.Snapshot.bulkRemoval(List.of(), Map.of(), 5L)));
        assertFalse(store.offer(PLAYER_A, null));
        store.consume(PLAYER_A, null);
        assertEquals(2, store.size());
        assertEquals(List.of(second, first), store.list(PLAYER_A, 6L));

        store.clear();
        assertEquals(0, store.size());
        assertTrue(store.list(PLAYER_A, 6L).isEmpty());
        assertNull(store.peekValid(PLAYER_A, 6L));
    }
}
