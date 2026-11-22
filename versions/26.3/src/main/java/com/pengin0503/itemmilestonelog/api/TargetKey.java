package com.pengin0503.itemmilestonelog.api;

import javax.annotation.Nullable;

/**
 * Identifies a milestone target as an {@code (itemId, count)} pair.
 *
 * <p>Encoded form used in NBT target lists and config entries:
 * <ul>
 *   <li>{@code count == 1}: {@code itemId} (e.g. {@code minecraft:diamond})</li>
 *   <li>{@code count > 1}: {@code itemId#count} (e.g. {@code minecraft:diamond#100})</li>
 * </ul>
 */
public record TargetKey(String itemId, int count) implements Comparable<TargetKey> {
    public static final int MIN_COUNT = 1;
    public static final int MAX_COUNT = 1_000_000;

    public TargetKey {
        if (itemId == null || itemId.isEmpty() || itemId.indexOf('#') >= 0) {
            throw new IllegalArgumentException("Invalid itemId: " + itemId);
        }
        if (!isValidCount(count)) {
            throw new IllegalArgumentException("Count out of range [" + MIN_COUNT + ", " + MAX_COUNT + "]: " + count);
        }
    }

    public static boolean isValidCount(int count) {
        return count >= MIN_COUNT && count <= MAX_COUNT;
    }

    public static TargetKey of(String itemId) {
        return new TargetKey(itemId, MIN_COUNT);
    }

    public static TargetKey of(String itemId, int count) {
        return new TargetKey(itemId, count);
    }

    /**
     * Parses a strictly positive integer count string in [{@value #MIN_COUNT}, {@value #MAX_COUNT}].
     * Returns {@code -1} if invalid.
     */
    public static int tryParseCount(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
        }
        try {
            int value = Integer.parseInt(raw);
            return isValidCount(value) ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Parses {@code itemId} (count = 1) or {@code itemId#count} (count in 1..1,000,000).
     * Returns {@code null} if the string is blank or malformed.
     */
    @Nullable
    public static TargetKey tryParseEncoded(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        int hash = trimmed.indexOf('#');
        if (hash < 0) {
            return new TargetKey(trimmed, MIN_COUNT);
        }
        String id = trimmed.substring(0, hash);
        if (id.isEmpty()) {
            return null;
        }
        int parsedCount = tryParseCount(trimmed.substring(hash + 1));
        if (parsedCount < MIN_COUNT) {
            return null;
        }
        return new TargetKey(id, parsedCount);
    }

    /** Encodes as {@code itemId} when count == 1, or {@code itemId#count} when count > 1. */
    public String toEncodedString() {
        return count == MIN_COUNT ? itemId : itemId + "#" + count;
    }

    /** Display suffix: empty for count == 1, or {@code " ×<count>"} for count > 1. */
    public String formatSuffix() {
        return count > MIN_COUNT ? " ×" + count : "";
    }

    /** Display ID with quantity suffix when count > 1 (e.g. {@code minecraft:diamond ×100}). */
    public String formatDisplayId() {
        return count > MIN_COUNT ? itemId + " ×" + count : itemId;
    }

    @Override
    public int compareTo(TargetKey other) {
        int c = this.itemId.compareTo(other.itemId);
        return c != 0 ? c : Integer.compare(this.count, other.count);
    }
}
