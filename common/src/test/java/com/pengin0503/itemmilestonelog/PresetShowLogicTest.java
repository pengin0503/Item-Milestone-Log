package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.TargetKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PresetShowLogicTest {
    private static TargetKey key(String itemId) {
        return TargetKey.of(itemId);
    }

    private static TargetKey key(String itemId, int count) {
        return TargetKey.of(itemId, count);
    }

    private static PresetShowLogic.ShowData dataWith(
            String description,
            String category,
            Integer version,
            int order,
            List<String> requiredMods,
            Double unlockAfterHours,
            List<String> nextPresets,
            List<PresetShowLogic.GroupSummary> groups,
            List<TargetKey> items) {
        return new PresetShowLogic.ShowData(description, category, version, order, requiredMods,
                unlockAfterHours, nextPresets, groups, items);
    }

    private static PresetShowLogic.ShowData bare() {
        return dataWith(null, null, null, 0, List.of(), null, List.of(), List.of(), List.of());
    }

    @Test
    void allMetadataGroupsAndItemsAreRenderedInOrder() {
        PresetShowLogic.Result result = PresetShowLogic.show(dataWith(
                "A test preset", "Endgame", 3, 7,
                List.of("allthetweaks", "curios"), 2.5d, List.of("next_a", "next_b"),
                List.of(new PresetShowLogic.GroupSummary("Ores", 2)),
                List.of(key("minecraft:diamond", 5), key("minecraft:iron_ingot"))), true, 50);

        assertEquals(PresetShowLogic.Status.OK, result.status());
        assertEquals(List.of(
                PresetShowLogic.Line.of(PresetShowLogic.LineKind.DESCRIPTION, "A test preset"),
                PresetShowLogic.Line.of(PresetShowLogic.LineKind.CATEGORY, "Endgame"),
                PresetShowLogic.Line.of(PresetShowLogic.LineKind.VERSION, "3"),
                PresetShowLogic.Line.of(PresetShowLogic.LineKind.ORDER, "7"),
                PresetShowLogic.Line.of(PresetShowLogic.LineKind.REQUIRED_MODS, "allthetweaks, curios"),
                PresetShowLogic.Line.of(PresetShowLogic.LineKind.UNLOCK_AFTER_HOURS, "2.5"),
                PresetShowLogic.Line.of(PresetShowLogic.LineKind.NEXT_PRESETS, "next_a, next_b"),
                PresetShowLogic.Line.of(PresetShowLogic.LineKind.GROUP, "Ores", "2"),
                PresetShowLogic.Line.of(PresetShowLogic.LineKind.ITEM, "minecraft:diamond", "5"),
                PresetShowLogic.Line.of(PresetShowLogic.LineKind.ITEM, "minecraft:iron_ingot", "1")
        ), result.lines());
    }

    @Test
    void absentMetadataLeavesOnlyTheAlwaysPresentOrderLine() {
        PresetShowLogic.Result result = PresetShowLogic.show(bare(), true, 50);

        assertEquals(PresetShowLogic.Status.OK, result.status());
        assertEquals(List.of(PresetShowLogic.Line.of(PresetShowLogic.LineKind.ORDER, "0")), result.lines());
    }

    @Test
    void blankDescriptionAndCategoryAreTreatedAsAbsent() {
        PresetShowLogic.Result result = PresetShowLogic.show(dataWith(
                "   ", "", null, 0, List.of(), null, List.of(), List.of(), List.of()), true, 50);

        assertEquals(List.of(PresetShowLogic.Line.of(PresetShowLogic.LineKind.ORDER, "0")), result.lines());
    }

    @Test
    void zeroAndNegativeUnlockHoursAreHiddenWhilePositiveValuesAreShown() {
        PresetShowLogic.Result zero = PresetShowLogic.show(dataWith(
                null, null, null, 0, List.of(), 0.0d, List.of(), List.of(), List.of()), true, 50);
        PresetShowLogic.Result negative = PresetShowLogic.show(dataWith(
                null, null, null, 0, List.of(), -4.0d, List.of(), List.of(), List.of()), true, 50);
        PresetShowLogic.Result whole = PresetShowLogic.show(dataWith(
                null, null, null, 0, List.of(), 24.0d, List.of(), List.of(), List.of()), true, 50);

        assertFalse(zero.lines().stream().anyMatch(l -> l.kind() == PresetShowLogic.LineKind.UNLOCK_AFTER_HOURS));
        assertFalse(negative.lines().stream().anyMatch(l -> l.kind() == PresetShowLogic.LineKind.UNLOCK_AFTER_HOURS));
        assertEquals(List.of(
                PresetShowLogic.Line.of(PresetShowLogic.LineKind.ORDER, "0"),
                PresetShowLogic.Line.of(PresetShowLogic.LineKind.UNLOCK_AFTER_HOURS, "24")
        ), whole.lines());
    }

    @Test
    void exactlyMaxRowsHasNoOmissionLine() {
        List<TargetKey> items = List.of(key("minecraft:diamond"), key("minecraft:emerald"), key("minecraft:gold_ingot"));
        PresetShowLogic.Result result = PresetShowLogic.show(
                dataWith(null, null, null, 0, List.of(), null, List.of(), List.of(), items), true, 3);

        assertEquals(PresetShowLogic.LineKind.ITEM, result.lines().get(1).kind());
        assertEquals(4, result.lines().size(), "order + 3 item lines, no omission");
        assertFalse(result.lines().stream().anyMatch(l -> l.kind() == PresetShowLogic.LineKind.ITEMS_MORE));
    }

    @Test
    void oneOverMaxRowsAddsAnOmissionLine() {
        List<TargetKey> items = List.of(key("minecraft:diamond"), key("minecraft:emerald"),
                key("minecraft:gold_ingot"), key("minecraft:iron_ingot"));
        PresetShowLogic.Result result = PresetShowLogic.show(
                dataWith(null, null, null, 0, List.of(), null, List.of(), List.of(), items), true, 3);

        assertEquals(5, result.lines().size());
        assertEquals(PresetShowLogic.Line.of(PresetShowLogic.LineKind.ITEMS_MORE, "1"),
                result.lines().get(4));
    }

    @Test
    void zeroRowCapShowsOnlyTheOmissionLine() {
        List<TargetKey> items = List.of(key("minecraft:diamond"), key("minecraft:emerald"));
        PresetShowLogic.Result result = PresetShowLogic.show(
                dataWith(null, null, null, 0, List.of(), null, List.of(), List.of(), items), true, 0);

        assertEquals(List.of(
                PresetShowLogic.Line.of(PresetShowLogic.LineKind.ORDER, "0"),
                PresetShowLogic.Line.of(PresetShowLogic.LineKind.ITEMS_MORE, "2")), result.lines());
    }

    @Test
    void groupSummariesKeepDefinitionOrderAndMemberCounts() {
        PresetShowLogic.Result result = PresetShowLogic.show(dataWith(
                null, null, null, 0, List.of(), null, List.of(),
                List.of(new PresetShowLogic.GroupSummary("Ores", 2),
                        new PresetShowLogic.GroupSummary("Blocks", 0)),
                List.of(key("minecraft:diamond"))), true, 50);

        assertEquals(List.of(
                PresetShowLogic.Line.of(PresetShowLogic.LineKind.ORDER, "0"),
                PresetShowLogic.Line.of(PresetShowLogic.LineKind.GROUP, "Ores", "2"),
                PresetShowLogic.Line.of(PresetShowLogic.LineKind.GROUP, "Blocks", "0"),
                PresetShowLogic.Line.of(PresetShowLogic.LineKind.ITEM, "minecraft:diamond", "1")
        ), result.lines());
    }

    @Test
    void disabledPresetsWinOverEverythingAndProduceNoLines() {
        PresetShowLogic.Result disabled = PresetShowLogic.show(bare(), false, 50);
        PresetShowLogic.Result disabledUnknown = PresetShowLogic.show(null, false, 50);

        assertEquals(PresetShowLogic.Status.DISABLED, disabled.status());
        assertTrueEmpty(disabled);
        assertEquals(PresetShowLogic.Status.DISABLED, disabledUnknown.status());
        assertTrueEmpty(disabledUnknown);
    }

    @Test
    void unknownPresetProducesNoLines() {
        PresetShowLogic.Result result = PresetShowLogic.show(null, true, 50);

        assertEquals(PresetShowLogic.Status.UNKNOWN, result.status());
        assertTrueEmpty(result);
    }

    @Test
    void fractionalUnlockHoursUseALocaleFreeDecimal() {
        PresetShowLogic.Result result = PresetShowLogic.show(dataWith(
                null, null, null, 0, List.of(), 0.25d, List.of(), List.of(), List.of()), true, 50);

        assertEquals(PresetShowLogic.Line.of(PresetShowLogic.LineKind.UNLOCK_AFTER_HOURS, "0.25"),
                result.lines().get(1));
    }

    @Test
    void emptyAndNullValueListsAreTolerated() {
        PresetShowLogic.ShowData data = new PresetShowLogic.ShowData(
                null, null, null, 0, null, null, null, null, null);
        PresetShowLogic.Result result = PresetShowLogic.show(data, true, 50);

        assertEquals(List.of(PresetShowLogic.Line.of(PresetShowLogic.LineKind.ORDER, "0")), result.lines());
    }

    private static void assertTrueEmpty(PresetShowLogic.Result result) {
        assertEquals(List.of(), result.lines());
    }
}
