package com.pengin0503.itemmilestonelog;

/**
 * F-150-61 / F-150-78 (N117 / N191): modifier policy for the single existing hover key.
 *
 * <p>One {@code KeyMapping} provides four operations; no key and no {@code Action} ordinal is
 * added (D-150-217 / D-150-218). When several modifiers are held the priority is
 * SHIFT &gt; ALT &gt; CTRL &gt; none, so exactly one action is sent.
 *
 * <p>{@link Operation#actionOrdinal()} records the ordinal of the <em>existing</em> C2S action each
 * operation must reuse. A version-tree test resolves every operation to the real
 * {@code MilestoneActionPayload.Action} and checks that mapping; this class deliberately has no
 * Minecraft dependency.
 */
public final class HoverShortcutPolicy {
    private HoverShortcutPolicy() {
    }

    /** GLFW modifier bits as reported by the screen input events on every supported version. */
    public record Modifiers(boolean shift, boolean alt, boolean ctrl) {
        public static final Modifiers NONE = new Modifiers(false, false, false);
        private static final int GLFW_MOD_SHIFT = 0x0001;
        private static final int GLFW_MOD_CONTROL = 0x0002;
        private static final int GLFW_MOD_ALT = 0x0004;

        /** Decodes an {@code event.getModifiers()} bit mask (or 0 when the API does not expose it). */
        public static Modifiers fromGlfw(int bits) {
            return new Modifiers(
                    (bits & GLFW_MOD_SHIFT) != 0,
                    (bits & GLFW_MOD_ALT) != 0,
                    (bits & GLFW_MOD_CONTROL) != 0);
        }
    }

    /** The four operations of the one hover key, each mapped to an already-existing action. */
    public enum Operation {
        ADD_COUNT_ONE(0),
        ADD_STACK_COUNT(0),
        REMOVE_TARGET(2),
        ADD_HAND(1);

        private final int actionOrdinal;

        Operation(int actionOrdinal) {
            this.actionOrdinal = actionOrdinal;
        }

        /** Ordinal of the existing {@code MilestoneActionPayload.Action} this operation reuses. */
        public int actionOrdinal() {
            return actionOrdinal;
        }
    }

    /** Chooses exactly one operation, applying SHIFT &gt; ALT &gt; CTRL &gt; none. */
    public static Operation choose(Modifiers modifiers) {
        if (modifiers.shift()) {
            return Operation.ADD_STACK_COUNT;
        }
        if (modifiers.alt()) {
            return Operation.REMOVE_TARGET;
        }
        if (modifiers.ctrl()) {
            return Operation.ADD_HAND;
        }
        return Operation.ADD_COUNT_ONE;
    }
}
