package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-Java unit tests for every F24 {@code renameitem} rewrite and collision rule. */
class RenameItemLogicTest {
    private static final String OLD = "oldmod:gem";
    private static final String NEW = "minecraft:diamond";
    private static final String OTHER = "minecraft:emerald";
    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private record Key(String itemId, int count) {
    }

    private record Rec(int playTicks, long day) {
    }

    private record Wf(String itemId, int count, UUID holder, long epochMillis) {
        Wf withItemId(String nextId) {
            return new Wf(nextId, count, holder, epochMillis);
        }
    }

    private static boolean isOld(Key k) {
        return OLD.equals(k.itemId());
    }

    private static boolean isNew(Key k) {
        return NEW.equals(k.itemId());
    }

    private static Key toNew(Key k) {
        return new Key(NEW, k.count());
    }

    private static Key newToOld(Key k) {
        return new Key(OLD, k.count());
    }

    @Test
    void targetsRewritePreservesCountsDeduplicatesCollisionsAndLeavesInputUnchanged() {
        Set<Key> input = new LinkedHashSet<>();
        input.add(new Key(OLD, 1));
        input.add(new Key(NEW, 1)); // collides with OLD#1
        input.add(new Key(OLD, 64)); // no collision
        input.add(new Key(OTHER, 16));
        input.add(new Key("#c:gems", 1)); // tag must never be touched

        RenameItemLogic.SetRewrite<Key> res = RenameItemLogic.rewriteTargets(input, RenameItemLogicTest::isOld, RenameItemLogicTest::toNew);

        assertEquals(2, res.changedCount());
        assertEquals(Set.of(
                new Key(NEW, 1),
                new Key(NEW, 64),
                new Key(OTHER, 16),
                new Key("#c:gems", 1)
        ), res.values());
        // Original input untouched
        assertTrue(input.contains(new Key(OLD, 1)));
        assertTrue(input.contains(new Key(OLD, 64)));
    }

    @Test
    void recordsCollisionKeepsSmallerPlayTicksRegardlessOfIterationOrder() {
        // Case 1: old before new — old has smaller playTicks -> old wins; tie -> dest wins; old larger -> dest wins
        Map<Key, Rec> oldFirst = new LinkedHashMap<>();
        oldFirst.put(new Key(OLD, 1), new Rec(100, 1L));
        oldFirst.put(new Key(NEW, 1), new Rec(300, 3L));
        oldFirst.put(new Key(OLD, 10), new Rec(500, 5L));
        oldFirst.put(new Key(NEW, 10), new Rec(200, 2L));
        oldFirst.put(new Key(OLD, 50), new Rec(400, 4L));
        oldFirst.put(new Key(NEW, 50), new Rec(400, 9L)); // tie -> keep destination (day 9)
        oldFirst.put(new Key(OLD, 64), new Rec(700, 7L)); // no collision
        oldFirst.put(new Key(OTHER, 1), new Rec(50, 1L));

        RenameItemLogic.MapRewrite<Key, Rec> res1 = RenameItemLogic.rewriteRecords(
                oldFirst, RenameItemLogicTest::isOld, RenameItemLogicTest::isNew, RenameItemLogicTest::toNew, Rec::playTicks);

        assertEquals(4, res1.changedCount());
        assertEquals(Map.of(
                new Key(NEW, 1), new Rec(100, 1L),
                new Key(NEW, 10), new Rec(200, 2L),
                new Key(NEW, 50), new Rec(400, 9L),
                new Key(NEW, 64), new Rec(700, 7L),
                new Key(OTHER, 1), new Rec(50, 1L)
        ), res1.values());

        // Case 2: new before old — identical winners regardless of iteration order
        Map<Key, Rec> newFirst = new LinkedHashMap<>();
        newFirst.put(new Key(NEW, 1), new Rec(300, 3L));
        newFirst.put(new Key(OLD, 1), new Rec(100, 1L));
        newFirst.put(new Key(NEW, 10), new Rec(200, 2L));
        newFirst.put(new Key(OLD, 10), new Rec(500, 5L));
        newFirst.put(new Key(NEW, 50), new Rec(400, 9L));
        newFirst.put(new Key(OLD, 50), new Rec(400, 4L));

        RenameItemLogic.MapRewrite<Key, Rec> res2 = RenameItemLogic.rewriteRecords(
                newFirst, RenameItemLogicTest::isOld, RenameItemLogicTest::isNew, RenameItemLogicTest::toNew, Rec::playTicks);

        assertEquals(3, res2.changedCount());
        assertEquals(Map.of(
                new Key(NEW, 1), new Rec(100, 1L),
                new Key(NEW, 10), new Rec(200, 2L),
                new Key(NEW, 50), new Rec(400, 9L)
        ), res2.values());
    }

    @Test
    void progressNotifiedCollisionKeepsLargerValueAndDropsKeysRecordedAfterMerge() {
        // Unrecorded collisions in both orders: larger wins
        Map<Key, Integer> prog1 = new LinkedHashMap<>();
        prog1.put(new Key(OLD, 100), 75);
        prog1.put(new Key(NEW, 100), 25); // old > new -> 75
        prog1.put(new Key(NEW, 200), 90);
        prog1.put(new Key(OLD, 200), 50); // new > old -> 90
        prog1.put(new Key(OLD, 300), 50); // no collision -> moved
        prog1.put(new Key(OLD, 400), 50); // dest already recorded -> dropped
        prog1.put(new Key(NEW, 500), 50); // old was recorded (so NEW#500 is recorded after merge) -> dropped
        prog1.put(new Key(OLD, 600), 25); // both progress entries, dest recorded -> dropped
        prog1.put(new Key(NEW, 600), 75);
        prog1.put(new Key(OTHER, 100), 50); // unrelated -> preserved

        Set<Key> originalRecords = Set.of(new Key(NEW, 400), new Key(OLD, 500), new Key(NEW, 600));
        Set<Key> mergedRecords = Set.of(new Key(NEW, 400), new Key(NEW, 500), new Key(NEW, 600));

        RenameItemLogic.ProgressRewrite<Key> res = RenameItemLogic.rewriteProgressNotified(
                prog1, originalRecords, mergedRecords,
                RenameItemLogicTest::isOld, RenameItemLogicTest::isNew,
                RenameItemLogicTest::toNew, RenameItemLogicTest::newToOld);

        assertTrue(res.changed());
        assertEquals(Map.of(
                new Key(NEW, 100), 75,
                new Key(NEW, 200), 90,
                new Key(NEW, 300), 50,
                new Key(OTHER, 100), 50
        ), res.values());
    }

    @Test
    void progressNotifiedUnchangedWhenOldItemIsAbsent() {
        Map<Key, Integer> prog = Map.of(new Key(NEW, 100), 50, new Key(OTHER, 100), 25);
        Set<Key> records = Set.of(new Key(NEW, 100)); // pre-existing unrelated entry

        RenameItemLogic.ProgressRewrite<Key> res = RenameItemLogic.rewriteProgressNotified(
                prog, records, records,
                RenameItemLogicTest::isOld, RenameItemLogicTest::isNew,
                RenameItemLogicTest::toNew, RenameItemLogicTest::newToOld);

        assertFalse(res.changed());
        assertEquals(prog, res.values());
    }

    @Test
    void carriedFromCreativeRewritesAndDeduplicates() {
        Set<String> input = new LinkedHashSet<>();
        input.add(OLD);
        input.add(NEW);
        input.add(OTHER);

        RenameItemLogic.SetRewrite<String> res = RenameItemLogic.rewriteCarriedFromCreative(input, OLD, NEW);

        assertEquals(1, res.changedCount());
        assertEquals(Set.of(NEW, OTHER), res.values());
        assertTrue(input.contains(OLD));
    }

    @Test
    void worldFirstsCollisionKeepsEarlierKnownEpochElseKeepsDestinationRegardlessOfOrder() {
        // 1. Both known, old earlier -> old wins (with updated itemId)
        // 2. Both known, dest earlier -> dest wins
        // 3. Both known, equal epoch -> dest wins
        // 4. Old known (>0), dest unknown (0) -> dest wins
        // 5. Old unknown (0), dest known (>0) -> dest wins
        // 6. Both unknown (0) -> dest wins
        // 7. No collision -> old moved with updated itemId
        Map<Key, Wf> oldFirst = new LinkedHashMap<>();
        oldFirst.put(new Key(OLD, 1), new Wf(OLD, 1, ALICE, 1_000L));
        oldFirst.put(new Key(NEW, 1), new Wf(NEW, 1, BOB, 2_000L));
        oldFirst.put(new Key(OLD, 2), new Wf(OLD, 2, ALICE, 3_000L));
        oldFirst.put(new Key(NEW, 2), new Wf(NEW, 2, BOB, 1_500L));
        oldFirst.put(new Key(OLD, 3), new Wf(OLD, 3, ALICE, 2_000L));
        oldFirst.put(new Key(NEW, 3), new Wf(NEW, 3, BOB, 2_000L));
        oldFirst.put(new Key(OLD, 4), new Wf(OLD, 4, ALICE, 1_000L));
        oldFirst.put(new Key(NEW, 4), new Wf(NEW, 4, BOB, 0L));
        oldFirst.put(new Key(OLD, 5), new Wf(OLD, 5, ALICE, 0L));
        oldFirst.put(new Key(NEW, 5), new Wf(NEW, 5, BOB, 5_000L));
        oldFirst.put(new Key(OLD, 6), new Wf(OLD, 6, ALICE, 0L));
        oldFirst.put(new Key(NEW, 6), new Wf(NEW, 6, BOB, 0L));
        oldFirst.put(new Key(OLD, 7), new Wf(OLD, 7, ALICE, 9_000L));
        oldFirst.put(new Key(OTHER, 1), new Wf(OTHER, 1, BOB, 500L));

        RenameItemLogic.WorldFirstRewrite<Key, Wf> res1 = RenameItemLogic.rewriteWorldFirsts(
                oldFirst,
                RenameItemLogicTest::isOld,
                RenameItemLogicTest::isNew,
                RenameItemLogicTest::toNew,
                wf -> wf.withItemId(NEW),
                Wf::epochMillis,
                Wf::holder);

        Map<Key, Wf> expected = Map.of(
                new Key(NEW, 1), new Wf(NEW, 1, ALICE, 1_000L),
                new Key(NEW, 2), new Wf(NEW, 2, BOB, 1_500L),
                new Key(NEW, 3), new Wf(NEW, 3, BOB, 2_000L),
                new Key(NEW, 4), new Wf(NEW, 4, BOB, 0L),
                new Key(NEW, 5), new Wf(NEW, 5, BOB, 5_000L),
                new Key(NEW, 6), new Wf(NEW, 6, BOB, 0L),
                new Key(NEW, 7), new Wf(NEW, 7, ALICE, 9_000L),
                new Key(OTHER, 1), new Wf(OTHER, 1, BOB, 500L)
        );
        assertEquals(7, res1.changedCount());
        assertEquals(expected, res1.values());
        assertEquals(Set.of(ALICE, BOB), res1.affectedPlayers());

        // Reverse order (dest before old) yields exact same result
        Map<Key, Wf> newFirst = new LinkedHashMap<>();
        newFirst.put(new Key(NEW, 1), new Wf(NEW, 1, BOB, 2_000L));
        newFirst.put(new Key(OLD, 1), new Wf(OLD, 1, ALICE, 1_000L));
        newFirst.put(new Key(NEW, 2), new Wf(NEW, 2, BOB, 1_500L));
        newFirst.put(new Key(OLD, 2), new Wf(OLD, 2, ALICE, 3_000L));
        newFirst.put(new Key(NEW, 3), new Wf(NEW, 3, BOB, 2_000L));
        newFirst.put(new Key(OLD, 3), new Wf(OLD, 3, ALICE, 2_000L));
        newFirst.put(new Key(NEW, 4), new Wf(NEW, 4, BOB, 0L));
        newFirst.put(new Key(OLD, 4), new Wf(OLD, 4, ALICE, 1_000L));
        newFirst.put(new Key(NEW, 5), new Wf(NEW, 5, BOB, 5_000L));
        newFirst.put(new Key(OLD, 5), new Wf(OLD, 5, ALICE, 0L));
        newFirst.put(new Key(NEW, 6), new Wf(NEW, 6, BOB, 0L));
        newFirst.put(new Key(OLD, 6), new Wf(OLD, 6, ALICE, 0L));
        newFirst.put(new Key(OLD, 7), new Wf(OLD, 7, ALICE, 9_000L));
        newFirst.put(new Key(OTHER, 1), new Wf(OTHER, 1, BOB, 500L));

        RenameItemLogic.WorldFirstRewrite<Key, Wf> res2 = RenameItemLogic.rewriteWorldFirsts(
                newFirst,
                RenameItemLogicTest::isOld,
                RenameItemLogicTest::isNew,
                RenameItemLogicTest::toNew,
                wf -> wf.withItemId(NEW),
                Wf::epochMillis,
                Wf::holder);

        assertEquals(7, res2.changedCount());
        assertEquals(expected, res2.values());
        assertEquals(Set.of(ALICE, BOB), res2.affectedPlayers());
    }
}
