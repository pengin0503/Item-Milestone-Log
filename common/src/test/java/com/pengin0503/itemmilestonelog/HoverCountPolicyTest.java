package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** F-150-60: Shift uses the hovered stack count, clamped to TargetKey.MAX_COUNT; no new action. */
class HoverCountPolicyTest {
    @Test
    void noModifierAlwaysAddsExactlyOne() {
        assertEquals(1, HoverCountPolicy.forStack(false, 1));
        assertEquals(1, HoverCountPolicy.forStack(false, 64));
        assertEquals(1, HoverCountPolicy.forStack(false, HoverCountPolicy.MAX_COUNT + 100));
    }

    @Test
    void shiftUsesTheHoveredStackCountAndIsIdenticalToOneAtOne() {
        assertEquals(1, HoverCountPolicy.forStack(true, 1));
        assertEquals(7, HoverCountPolicy.forStack(true, 7));
        assertEquals(64, HoverCountPolicy.forStack(true, 64));
        assertEquals(HoverCountPolicy.forStack(false, 1), HoverCountPolicy.forStack(true, 1));
    }

    @Test
    void shiftCountIsClampedToTheExistingTargetMaximum() {
        assertEquals(HoverCountPolicy.MAX_COUNT,
                HoverCountPolicy.forStack(true, HoverCountPolicy.MAX_COUNT));
        assertEquals(HoverCountPolicy.MAX_COUNT,
                HoverCountPolicy.forStack(true, HoverCountPolicy.MAX_COUNT + 1));
        assertEquals(HoverCountPolicy.MAX_COUNT, HoverCountPolicy.clamp(Integer.MAX_VALUE));
        assertEquals(1_000_000, HoverCountPolicy.MAX_COUNT, "must match TargetKey.MAX_COUNT");
    }

    @Test
    void invalidStackCountsFallBackToOne() {
        assertEquals(1, HoverCountPolicy.clamp(0));
        assertEquals(1, HoverCountPolicy.clamp(-5));
        assertEquals(1, HoverCountPolicy.forStack(true, 0));
    }
}
