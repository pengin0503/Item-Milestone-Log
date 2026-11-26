package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.TargetKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresetGroupLogicTest {
    private static TargetKey key(String itemId) {
        return TargetKey.of(itemId);
    }

    @Test
    void validGroupsKeepOrderAndName() {
        PresetGroup group = new PresetGroup("ores", PresetName.ofPlain("Ores"),
                List.of(key("minecraft:iron_ingot"), key("minecraft:gold_ingot")));
        PresetGroupLogic.Result result = PresetGroupLogic.validate(List.of(group), List.of(
                key("minecraft:iron_ingot"), key("minecraft:gold_ingot"), key("minecraft:diamond")));

        assertEquals(List.of(group), result.groups());
        assertTrue(result.warnings().isEmpty(), () -> "unexpected warnings: " + result.warnings());
    }

    @Test
    void unknownItemKeysAreDroppedWithAWarning() {
        PresetGroup group = new PresetGroup("ores", List.of(
                key("minecraft:iron_ingot"), key("missingmod:absent_ore")));
        PresetGroupLogic.Result result = PresetGroupLogic.validate(List.of(group),
                List.of(key("minecraft:iron_ingot")));

        assertEquals(List.of(new PresetGroup("ores", List.of(key("minecraft:iron_ingot")))), result.groups());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("absent_ore")),
                () -> "expected an unknown-key warning, got: " + result.warnings());
    }

    @Test
    void firstGroupWinsWhenAnItemBelongsToSeveralGroups() {
        PresetGroup ores = new PresetGroup("ores", List.of(key("minecraft:iron_ingot")));
        PresetGroup metals = new PresetGroup("metals", List.of(
                key("minecraft:iron_ingot"), key("minecraft:gold_ingot")));
        PresetGroupLogic.Result result = PresetGroupLogic.validate(List.of(ores, metals),
                List.of(key("minecraft:iron_ingot"), key("minecraft:gold_ingot")));

        assertEquals(List.of(ores, new PresetGroup("metals", List.of(key("minecraft:gold_ingot")))),
                result.groups());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("already belongs")),
                () -> "expected a cross-group warning, got: " + result.warnings());
    }

    @Test
    void duplicateGroupIdDropsTheLaterGroupEntirely() {
        PresetGroup first = new PresetGroup("ores", List.of(key("minecraft:iron_ingot")));
        PresetGroup later = new PresetGroup("ores", List.of(key("minecraft:gold_ingot")));
        PresetGroupLogic.Result result = PresetGroupLogic.validate(List.of(first, later),
                List.of(key("minecraft:iron_ingot"), key("minecraft:gold_ingot")));

        assertEquals(List.of(first), result.groups());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("duplicate group id")),
                () -> "expected a duplicate-id warning, got: " + result.warnings());
    }

    @Test
    void emptyGroupAfterValidationIsDroppedWithAWarning() {
        PresetGroup empty = new PresetGroup("empty", List.of(key("missingmod:absent_ore")));
        PresetGroupLogic.Result result = PresetGroupLogic.validate(List.of(empty),
                List.of(key("minecraft:iron_ingot")));

        assertTrue(result.groups().isEmpty());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("no valid items")),
                () -> "expected an empty-group warning, got: " + result.warnings());
    }

    @Test
    void duplicateItemWithinOneGroupIsDedupedSilently() {
        PresetGroup group = new PresetGroup("ores", List.of(
                key("minecraft:iron_ingot"), key("minecraft:iron_ingot")));
        PresetGroupLogic.Result result = PresetGroupLogic.validate(List.of(group),
                List.of(key("minecraft:iron_ingot")));

        assertEquals(List.of(new PresetGroup("ores", List.of(key("minecraft:iron_ingot")))), result.groups());
        assertTrue(result.warnings().isEmpty(), () -> "unexpected warnings: " + result.warnings());
    }
}
