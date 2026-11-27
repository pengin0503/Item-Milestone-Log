package com.pengin0503.itemmilestonelog;

import java.util.UUID;

/**
 * F-150-38 (N167, adopted side): one recorded past world-first holder.
 *
 * <p>Stored as an element of {@code MilestoneData.firstsHolderHistory}, a per-target list of at
 * most {@code FirstsHolderHistory.HISTORY_LIMIT} entries (oldest first). {@link #INVALID} is a
 * load-time sentinel: the lenient SavedData readers skip it instead of failing the whole file
 * or its sibling entries.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public record HolderEntry(String uuid, String name, int playTicks, long epochMillis) {
    /** Skip sentinel produced by {@link #of} for an unknown UUID or a negative play time. */
    public static final HolderEntry INVALID = new HolderEntry("", "", -1, 0L);

    public HolderEntry {
        name = name == null ? "" : name;
    }

    /** Whether {@code value} is a parseable player UUID. */
    public static boolean isValidUuid(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /** Whether this entry can be persisted (valid UUID, non-negative ticks and epoch). */
    public boolean isValid() {
        return isValidUuid(uuid) && playTicks >= 0 && epochMillis >= 0;
    }

    /**
     * Builds a validated entry, or {@link #INVALID} when the UUID is unknown, {@code playTicks}
     * is negative or out of {@code int} range, or the epoch is negative.
     */
    public static HolderEntry of(String uuid, String name, long playTicks, long epochMillis) {
        if (!isValidUuid(uuid) || playTicks < 0 || playTicks > Integer.MAX_VALUE || epochMillis < 0) {
            return INVALID;
        }
        return new HolderEntry(uuid, name, (int) playTicks, epochMillis);
    }
}
