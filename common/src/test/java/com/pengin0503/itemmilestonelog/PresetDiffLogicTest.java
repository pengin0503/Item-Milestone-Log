package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.TargetKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresetDiffLogicTest {
    private static TargetKey key(String itemId) {
        return TargetKey.of(itemId);
    }

    private static TargetKey key(String itemId, int count) {
        return TargetKey.of(itemId, count);
    }

    private static PresetDiffLogic.ItemCount item(String itemId, long count) {
        return new PresetDiffLogic.ItemCount(itemId, count);
    }

    private static PresetDiffLogic.CountDifference diff(String itemId, long countA, long countB) {
        return new PresetDiffLogic.CountDifference(itemId, countA, countB);
    }

    @Test
    void identicalPresetsHaveNoDifferences() {
        PresetDiffLogic.Result result = PresetDiffLogic.diff(
                List.of(key("minecraft:diamond", 5), key("minecraft:emerald")),
                List.of(key("minecraft:diamond", 5), key("minecraft:emerald")), true, 50);

        assertEquals(PresetDiffLogic.Status.OK, result.status());
        assertFalse(result.hasDifferences());
        assertEquals(List.of(), result.onlyA());
        assertEquals(List.of(), result.onlyB());
        assertEquals(List.of(), result.countDifferences());
    }

    @Test
    void itemsOnlyInAAndOnlyInBAreSeparated() {
        PresetDiffLogic.Result result = PresetDiffLogic.diff(
                List.of(key("minecraft:diamond", 5), key("minecraft:emerald")),
                List.of(key("minecraft:emerald"), key("minecraft:gold_ingot", 3)), true, 50);

        assertEquals(List.of(item("minecraft:diamond", 5)), result.onlyA());
        assertEquals(List.of(item("minecraft:gold_ingot", 3)), result.onlyB());
        assertEquals(List.of(), result.countDifferences());
        assertTrue(result.hasDifferences());
    }

    @Test
    void sameItemWithDifferentCountsIsACountDifferenceNotTwoOneSidedRows() {
        PresetDiffLogic.Result result = PresetDiffLogic.diff(
                List.of(key("minecraft:diamond", 5)),
                List.of(key("minecraft:diamond", 10)), true, 50);

        assertEquals(List.of(), result.onlyA());
        assertEquals(List.of(), result.onlyB());
        assertEquals(List.of(diff("minecraft:diamond", 5, 10)), result.countDifferences());
    }

    @Test
    void countDifferenceKeepsAAndBOrder() {
        PresetDiffLogic.Result result = PresetDiffLogic.diff(
                List.of(key("minecraft:diamond", 5), key("minecraft:emerald", 2)),
                List.of(key("minecraft:emerald", 3), key("minecraft:diamond", 10)), true, 50);

        assertEquals(List.of(
                diff("minecraft:diamond", 5, 10),
                diff("minecraft:emerald", 2, 3)), result.countDifferences());
    }

    @Test
    void emptyPresetsHaveNoDifferences() {
        PresetDiffLogic.Result bothEmpty = PresetDiffLogic.diff(List.of(), List.of(), true, 50);
        PresetDiffLogic.Result aEmpty = PresetDiffLogic.diff(List.of(), List.of(key("minecraft:diamond")), true, 50);
        PresetDiffLogic.Result bEmpty = PresetDiffLogic.diff(List.of(key("minecraft:diamond")), List.of(), true, 50);

        assertFalse(bothEmpty.hasDifferences());
        assertEquals(List.of(item("minecraft:diamond", 1)), aEmpty.onlyB());
        assertEquals(List.of(), aEmpty.onlyA());
        assertEquals(List.of(item("minecraft:diamond", 1)), bEmpty.onlyA());
        assertEquals(List.of(), bEmpty.onlyB());
    }

    @Test
    void nullInputsReportTheUnknownSide() {
        PresetDiffLogic.Result unknownA = PresetDiffLogic.diff(null, List.of(), true, 50);
        PresetDiffLogic.Result unknownB = PresetDiffLogic.diff(List.of(), null, true, 50);

        assertEquals(PresetDiffLogic.Status.UNKNOWN_A, unknownA.status());
        assertEquals(PresetDiffLogic.Status.UNKNOWN_B, unknownB.status());
        assertFalse(unknownA.hasDifferences());
        assertFalse(unknownB.hasDifferences());
    }

    @Test
    void disabledWinsOverUnknownNames() {
        PresetDiffLogic.Result result = PresetDiffLogic.diff(null, null, false, 50);

        assertEquals(PresetDiffLogic.Status.DISABLED, result.status());
        assertFalse(result.hasDifferences());
    }

    @Test
    void eachSectionIsCappedIndependentlyAtMaxRows() {
        PresetDiffLogic.Result result = PresetDiffLogic.diff(
                List.of(key("minecraft:a", 5), key("minecraft:b", 5), key("minecraft:c", 5),
                        key("minecraft:d", 5), key("minecraft:only_a", 2)),
                List.of(key("minecraft:a", 6), key("minecraft:b", 6), key("minecraft:c", 6),
                        key("minecraft:d", 6), key("minecraft:only_b", 3)), true, 2);

        assertEquals(List.of(item("minecraft:only_a", 2)), result.onlyA());
        assertEquals(0, result.onlyAHidden());
        assertEquals(List.of(item("minecraft:only_b", 3)), result.onlyB());
        assertEquals(0, result.onlyBHidden());
        assertEquals(List.of(diff("minecraft:a", 5, 6), diff("minecraft:b", 5, 6)),
                result.countDifferences());
        assertEquals(2, result.countDifferencesHidden());
    }

    @Test
    void zeroMaxRowsHidesWholeSectionsButStillCountsThem() {
        PresetDiffLogic.Result result = PresetDiffLogic.diff(
                List.of(key("minecraft:diamond", 5), key("minecraft:emerald", 2)),
                List.of(key("minecraft:gold_ingot", 7)), true, 0);

        assertEquals(List.of(), result.onlyA());
        assertEquals(2, result.onlyAHidden());
        assertEquals(List.of(), result.onlyB());
        assertEquals(1, result.onlyBHidden());
        assertTrue(result.hasDifferences());
    }

    @Test
    void repeatedItemIdContributesItsMultisetTotal() {
        PresetDiffLogic.Result sameTotal = PresetDiffLogic.diff(
                List.of(key("minecraft:diamond", 5), key("minecraft:diamond", 10)),
                List.of(key("minecraft:diamond", 15)), true, 50);
        PresetDiffLogic.Result differentTotal = PresetDiffLogic.diff(
                List.of(key("minecraft:diamond", 5), key("minecraft:diamond", 10)),
                List.of(key("minecraft:diamond", 20)), true, 50);

        assertFalse(sameTotal.hasDifferences());
        assertEquals(List.of(diff("minecraft:diamond", 15, 20)), differentTotal.countDifferences());
    }

    @Test
    void tagIdsAreComparedAsOrdinaryItemIds() {
        PresetDiffLogic.Result result = PresetDiffLogic.diff(
                List.of(key("#c:ingots/iron", 4)),
                List.of(key("#c:ingots/iron", 5)), true, 50);

        assertEquals(List.of(diff("#c:ingots/iron", 4, 5)), result.countDifferences());
    }

    @Test
    void nullEntriesInsideListsAreIgnored() {
        PresetDiffLogic.Result result = PresetDiffLogic.diff(
                java.util.Arrays.asList(key("minecraft:diamond"), null),
                List.of(key("minecraft:diamond")), true, 50);

        assertFalse(result.hasDifferences());
    }
}
