package com.pengin0503.itemmilestonelog;

/**
 * F-150-59 (N29): short-lived memory of the last {@code ItemStack} handed to an item tooltip.
 *
 * <p>Some mod GUIs draw their own tooltips and never expose a vanilla {@code AbstractContainerScreen}
 * slot. Watching the existing {@code ItemTooltipEvent} lets the F41 hover key work there without
 * referencing JEI / EMI / REI (no new dependency, spec D-150-179). The version-specific listener
 * passes a <em>copy</em> of the stack as immutable primitive fields through {@link StackView}, so
 * later changes to the original stack cannot change what the key will add.
 *
 * <p>Pure Java (Java 17-compatible) so all five version trees compile the same bytes. The cache is
 * memory-only and is cleared on logout and when a server without the mod is detected.
 */
public final class HoverItemCache {
    /** How long a captured stack stays usable; older captures are dropped (F-150-59). */
    public static final long TTL_MILLIS = 500L;

    /** Immutable view of a copied stack; implemented by the version-specific tooltip listener. */
    public interface StackView {
        String itemId();

        int count();
    }

    /** Snapshot returned to the shortcut handler; never holds a live Minecraft object. */
    public record Entry(String itemId, int count) {
        public boolean isEmpty() {
            return itemId == null || itemId.isBlank() || count <= 0;
        }
    }

    private volatile Entry entry;
    private volatile long capturedAtMillis = Long.MIN_VALUE;

    /** Remembers {@code stack} at {@code nowMillis}, or clears the cache for an empty stack. */
    public void capture(StackView stack, long nowMillis) {
        if (stack == null) {
            clear();
            return;
        }
        String itemId = stack.itemId();
        int count = stack.count();
        if (itemId == null || itemId.isBlank() || count <= 0) {
            clear();
            return;
        }
        entry = new Entry(itemId, count);
        capturedAtMillis = nowMillis;
    }

    /**
     * Returns the captured stack, or {@code null} when nothing was captured or the 500 ms window
     * has elapsed. A clock moving backwards is treated as expired so a stale item is never added.
     */
    public Entry get(long nowMillis) {
        Entry current = entry;
        if (current == null) {
            return null;
        }
        long elapsed = nowMillis - capturedAtMillis;
        if (elapsed < 0L || elapsed > TTL_MILLIS) {
            entry = null;
            return null;
        }
        return current;
    }

    /** Discards the cached stack, for example on logout or on a vanilla-only server. */
    public void clear() {
        entry = null;
        capturedAtMillis = Long.MIN_VALUE;
    }
}
