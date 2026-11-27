package com.pengin0503.itemmilestonelog;

/**
 * F-150-60 (N69): quantity rule for the Shift-modified hovered-item shortcut.
 *
 * <p>No modifier adds one item exactly as before. Shift adds the count of the <em>hovered stack</em>
 * (not the inventory total, D-150-182) through the existing {@code ADD_TARGET} action, whose
 * payload already carries a varint count. Values are clamped to {@link #MAX_COUNT}, the same bound
 * as the version trees' {@code TargetKey.MAX_COUNT}; server validation is not changed.
 *
 * <p>Pure Java and shared by all five version trees.
 */
public final class HoverCountPolicy {
    /** Mirrors {@code TargetKey.MAX_COUNT} without depending on a Minecraft class. */
    public static final int MAX_COUNT = 1_000_000;

    private HoverCountPolicy() {
    }

    /** Count sent for {@code ADD_TARGET}: 1 without Shift, the clamped stack count with Shift. */
    public static int forStack(boolean shift, int stackCount) {
        return shift ? clamp(stackCount) : 1;
    }

    /** Clamps to the valid positive target-count range; invalid values fall back to one. */
    public static int clamp(int count) {
        if (count < 1) {
            return 1;
        }
        return Math.min(count, MAX_COUNT);
    }
}
