package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-150-40 (N178): pure preset edit / protection rules shared by all five trees. */
class PresetEditLogicTest {
    private static final String DIAMOND = "minecraft:diamond";
    private static final String EMERALD = "minecraft:emerald";

    @Test
    void onlyUnprotectedFilePresetsAreEditable() {
        assertTrue(PresetEditLogic.canEdit(PresetEditLogic.Kind.FILE, false));
        assertFalse(PresetEditLogic.canEdit(PresetEditLogic.Kind.FILE, true));
        assertFalse(PresetEditLogic.canEdit(PresetEditLogic.Kind.BUILTIN, false));
        assertFalse(PresetEditLogic.canEdit(PresetEditLogic.Kind.DATAPACK, false));
    }

    @Test
    void addKeepsInsertionOrderAndDeduplicates() {
        List<String> items = List.of(DIAMOND, EMERALD);
        assertEquals(List.of(DIAMOND, EMERALD, "minecraft:elytra"),
                PresetEditLogic.addEncoded(items, "minecraft:elytra"));
        assertEquals(List.of(DIAMOND, EMERALD), PresetEditLogic.addEncoded(items, DIAMOND));
        assertNotSame(items, PresetEditLogic.addEncoded(items, DIAMOND));
    }

    @Test
    void addRejectsBlankInputWithoutMutating() {
        assertEquals(List.of(DIAMOND), PresetEditLogic.addEncoded(List.of(DIAMOND), "  "));
        assertTrue(PresetEditLogic.addEncoded(null, null).isEmpty());
    }

    @Test
    void removeEncodedRemovesOneExactKey() {
        List<String> items = List.of(DIAMOND, DIAMOND + "#3", EMERALD);
        assertEquals(List.of(DIAMOND + "#3", EMERALD), PresetEditLogic.removeEncoded(items, DIAMOND));
        assertEquals(List.of(DIAMOND, EMERALD), PresetEditLogic.removeEncoded(items, DIAMOND + "#3"));
    }

    @Test
    void removeItemIdDropsEveryCountOfThatItem() {
        List<String> items = List.of(DIAMOND, DIAMOND + "#3", EMERALD);
        assertEquals(List.of(EMERALD), PresetEditLogic.removeItemId(items, DIAMOND));
        assertNotSame(items, PresetEditLogic.removeItemId(items, DIAMOND));
    }

    @Test
    void removeIsANoOpForMissingOrBlankKeys() {
        List<String> items = List.of(DIAMOND);
        assertEquals(List.of(DIAMOND), PresetEditLogic.removeEncoded(items, "minecraft:air"));
        assertEquals(List.of(DIAMOND), PresetEditLogic.removeItemId(items, ""));
    }
}
