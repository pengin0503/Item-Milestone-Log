package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/** F-150-61 / F-150-78: one key, four operations, SHIFT &gt; ALT &gt; CTRL &gt; none, no new ordinal. */
class HoverShortcutPolicyTest {
    @Test
    void fourModifierStatesChooseTheFourExistingOperations() {
        assertEquals(HoverShortcutPolicy.Operation.ADD_COUNT_ONE,
                HoverShortcutPolicy.choose(HoverShortcutPolicy.Modifiers.NONE));
        assertEquals(HoverShortcutPolicy.Operation.ADD_STACK_COUNT,
                HoverShortcutPolicy.choose(new HoverShortcutPolicy.Modifiers(true, false, false)));
        assertEquals(HoverShortcutPolicy.Operation.REMOVE_TARGET,
                HoverShortcutPolicy.choose(new HoverShortcutPolicy.Modifiers(false, true, false)));
        assertEquals(HoverShortcutPolicy.Operation.ADD_HAND,
                HoverShortcutPolicy.choose(new HoverShortcutPolicy.Modifiers(false, false, true)));
    }

    @Test
    void simultaneousModifiersExecuteOnlyTheHighestPriorityOperation() {
        assertEquals(HoverShortcutPolicy.Operation.ADD_STACK_COUNT,
                HoverShortcutPolicy.choose(new HoverShortcutPolicy.Modifiers(true, true, false)));
        assertEquals(HoverShortcutPolicy.Operation.ADD_STACK_COUNT,
                HoverShortcutPolicy.choose(new HoverShortcutPolicy.Modifiers(true, true, true)));
        assertEquals(HoverShortcutPolicy.Operation.REMOVE_TARGET,
                HoverShortcutPolicy.choose(new HoverShortcutPolicy.Modifiers(false, true, true)));
        assertNotSame(HoverShortcutPolicy.Operation.ADD_HAND,
                HoverShortcutPolicy.choose(new HoverShortcutPolicy.Modifiers(false, true, true)));
    }

    @Test
    void glfwModifierBitsDecodeToTheExpectedFlags() {
        assertEquals(HoverShortcutPolicy.Modifiers.NONE, HoverShortcutPolicy.Modifiers.fromGlfw(0));
        assertEquals(new HoverShortcutPolicy.Modifiers(true, false, false),
                HoverShortcutPolicy.Modifiers.fromGlfw(0x0001));
        assertEquals(new HoverShortcutPolicy.Modifiers(false, false, true),
                HoverShortcutPolicy.Modifiers.fromGlfw(0x0002));
        assertEquals(new HoverShortcutPolicy.Modifiers(false, true, false),
                HoverShortcutPolicy.Modifiers.fromGlfw(0x0004));
        assertEquals(HoverShortcutPolicy.Operation.REMOVE_TARGET,
                HoverShortcutPolicy.choose(HoverShortcutPolicy.Modifiers.fromGlfw(0x0004)));
    }

    @Test
    void everyOperationPointsAtAnExistingOrdinalAndNoOrdinalNineIsUsed() {
        assertEquals(0, HoverShortcutPolicy.Operation.ADD_COUNT_ONE.actionOrdinal());
        assertEquals(0, HoverShortcutPolicy.Operation.ADD_STACK_COUNT.actionOrdinal());
        assertEquals(2, HoverShortcutPolicy.Operation.REMOVE_TARGET.actionOrdinal());
        assertEquals(1, HoverShortcutPolicy.Operation.ADD_HAND.actionOrdinal());
        for (HoverShortcutPolicy.Operation operation : HoverShortcutPolicy.Operation.values()) {
            assertEquals(true, operation.actionOrdinal() <= 2, "no new C2S ordinal may be consumed");
        }
    }
}
