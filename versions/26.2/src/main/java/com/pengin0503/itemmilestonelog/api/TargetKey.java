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
        if (!isValidItemId(itemId)) {
            throw new IllegalArgumentException("Invalid itemId: " + itemId);
        }
        if (!isValidCount(count)) {
            throw new IllegalArgumentException("Count out of range [" + MIN_COUNT + ", " + MAX_COUNT + "]: " + count);
        }
    }

    public static boolean isValidItemId(@Nullable String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return false;
        }
        for (int i = 0; i < itemId.length(); i++) {
            char c = itemId.charAt(i);
            if (c == '#' || Character.isWhitespace(c) || Character.isISOControl(c)) {
                return false;
            }
        }
        int colon = itemId.indexOf(':');
        if (colon >= 0 && (colon == 0 || colon == itemId.length() - 1)) {
            return false; // empty namespace or empty path
        }
        return true;
    }

    public static boolean isValidCount(int count) {
        return count >= MIN_COUNT && count <= MAX_COUNT;
    }

    /**
     * Validates and normalizes a target item ID according to Minecraft's resource location rules.
     *
     * <p>In 1.1.0, only item IDs are accepted ({@code #}-prefixed tag IDs return {@code null}).
     * When {@code requireNamespace} is {@code false}, an ID without {@code ':'} is normalized to
     * the {@code minecraft:} namespace. Returns {@code null} if {@code raw} is null, empty,
     * contains invalid characters, or lacks a required namespace.
     */
    @Nullable
    public static String normalizeItemId(@Nullable String raw, boolean requireNamespace) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        int colon = raw.indexOf(':');
        String namespace;
        String path;
        if (colon < 0) {
            if (requireNamespace) {
                return null;
            }
            namespace = "minecraft";
            path = raw;
        } else {
            if (colon == 0 || colon == raw.length() - 1) {
                return null;
            }
            namespace = raw.substring(0, colon);
            path = raw.substring(colon + 1);
        }
        if (!isValidNamespace(namespace) || !isValidPath(path)) {
            return null;
        }
        return namespace + ":" + path;
    }

    /** Convenience overload allowing the implicit {@code minecraft:} namespace. */
    @Nullable
    public static String normalizeItemId(@Nullable String raw) {
        return normalizeItemId(raw, false);
    }

    /**
     * Normalizes an {@code (itemId, count)} pair into a {@link TargetKey}, or returns {@code null}
     * if {@code rawItemId} is invalid, {@code count} is out of range, or {@code rejectAir} is
     * {@code true} and the normalized ID is {@code minecraft:air}.
     */
    @Nullable
    public static TargetKey normalize(@Nullable String rawItemId, int count, boolean requireNamespace, boolean rejectAir) {
        if (!isValidCount(count)) {
            return null;
        }
        String normalizedId = normalizeItemId(rawItemId, requireNamespace);
        if (normalizedId == null || (rejectAir && "minecraft:air".equals(normalizedId))) {
            return null;
        }
        return new TargetKey(normalizedId, count);
    }

    /** Convenience overload allowing implicit {@code minecraft:} namespace and permitting {@code minecraft:air}. */
    @Nullable
    public static TargetKey normalize(@Nullable String rawItemId, int count) {
        return normalize(rawItemId, count, false, false);
    }

    /**
     * Parses and normalizes an encoded target string ({@code itemId} or {@code itemId#count}).
     * Leading/trailing whitespace is trimmed. Returns {@code null} if malformed, invalid, missing
     * a required namespace, or {@code minecraft:air} when {@code rejectAir} is {@code true}.
     */
    @Nullable
    public static TargetKey tryParseNormalized(@Nullable String raw, boolean requireNamespace, boolean rejectAir) {
        TargetKey parsed = tryParseEncoded(raw);
        if (parsed == null) {
            return null;
        }
        return normalize(parsed.itemId(), parsed.count(), requireNamespace, rejectAir);
    }

    /** Convenience overload allowing implicit {@code minecraft:} namespace and rejecting {@code minecraft:air}. */
    @Nullable
    public static TargetKey tryParseNormalized(@Nullable String raw) {
        return tryParseNormalized(raw, false, true);
    }

    private static boolean isValidNamespace(String namespace) {
        if (namespace.isEmpty()) {
            return false;
        }
        for (int i = 0; i < namespace.length(); i++) {
            char c = namespace.charAt(i);
            if (!(c == '_' || c == '-' || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidPath(String path) {
        if (path.isEmpty()) {
            return false;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (!(c == '_' || c == '-' || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '/' || c == '.')) {
                return false;
            }
        }
        return true;
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
            return isValidItemId(trimmed) ? new TargetKey(trimmed, MIN_COUNT) : null;
        }
        String id = trimmed.substring(0, hash);
        if (!isValidItemId(id)) {
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
