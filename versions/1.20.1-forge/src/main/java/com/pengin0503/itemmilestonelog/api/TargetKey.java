package com.pengin0503.itemmilestonelog.api;

import javax.annotation.Nullable;

/**
 * Identifies a milestone target as an {@code (itemId, count)} pair, where {@code itemId} is either
 * an item ID ({@code namespace:path}, e.g. {@code minecraft:diamond}) or an item tag ID prefixed
 * with {@code '#'} ({@code #namespace:path}, e.g. {@code #c:ingots/iron}).
 *
 * <p>Callers should check {@link #isTag()} before treating {@link #itemId()} as an item
 * {@code Identifier} / {@code ResourceLocation}, and use {@link #tagId()} to obtain the tag
 * identifier without the leading {@code '#'}.
 *
 * <p>Encoded form used in NBT target lists and config entries:
 * <ul>
 *   <li>{@code count == 1}: {@code itemId} (e.g. {@code minecraft:diamond}, {@code #c:ingots/iron})</li>
 *   <li>{@code count > 1}: {@code itemId#count} (e.g. {@code minecraft:diamond#100}, {@code #c:ingots/iron#100})</li>
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

    /** Returns {@code true} if {@code itemId} starts with the tag prefix {@code '#'}. */
    public static boolean isTagId(@Nullable String itemId) {
        return itemId != null && !itemId.isEmpty() && itemId.charAt(0) == '#';
    }

    /** Returns {@code true} if this target refers to an item tag ({@link #itemId()} starts with {@code '#'}). */
    public boolean isTag() {
        return isTagId(itemId);
    }

    /**
     * Returns the tag identifier without the leading {@code '#'} (e.g. {@code "c:ingots/iron"} for
     * {@code "#c:ingots/iron"}), or {@code null} if {@link #isTag()} is {@code false}.
     */
    @Nullable
    public String tagId() {
        return isTag() ? itemId.substring(1) : null;
    }

    /**
     * Returns {@code true} if {@code itemId} is a valid target ID: either an item ID or a tag ID
     * ({@code '#' + valid namespace:path identifier}).
     */
    public static boolean isValidItemId(@Nullable String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return false;
        }
        if (itemId.charAt(0) == '#') {
            return normalizeItemId(itemId, true) != null;
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
     * Validates and normalizes a target item or tag ID according to Minecraft's resource location rules.
     *
     * <p>Tag IDs start with {@code '#'} and must have an explicit {@code namespace:path} identifier
     * after the leading {@code '#'}. For item IDs, when {@code requireNamespace} is {@code false},
     * an ID without {@code ':'} is normalized to the {@code minecraft:} namespace. Returns {@code null}
     * if {@code raw} is null, empty, contains invalid characters, or lacks a required namespace.
     */
    @Nullable
    public static String normalizeItemId(@Nullable String raw, boolean requireNamespace) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        boolean tag = raw.charAt(0) == '#';
        String body = tag ? raw.substring(1) : raw;
        if (body.isEmpty()) {
            return null;
        }
        int colon = body.indexOf(':');
        String namespace;
        String path;
        if (colon < 0) {
            if (tag || requireNamespace) {
                return null;
            }
            namespace = "minecraft";
            path = body;
        } else {
            if (colon == 0 || colon == body.length() - 1) {
                return null;
            }
            namespace = body.substring(0, colon);
            path = body.substring(colon + 1);
        }
        if (!isValidNamespace(namespace) || !isValidPath(path)) {
            return null;
        }
        return (tag ? "#" : "") + namespace + ":" + path;
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
     * Parses an encoded target string:
     * <ul>
     *   <li>Item target: {@code itemId} (count = 1) or {@code itemId#count} (count in 1..1,000,000)</li>
     *   <li>Tag target: {@code #namespace:path} (count = 1) or {@code #namespace:path#count} (count in 1..1,000,000)</li>
     * </ul>
     *
     * <p>A leading {@code '#'} marks a tag target. The count separator is the last {@code '#'} at
     * index {@code >= 1}; the suffix after it must satisfy {@link #tryParseCount(String)}.
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
        int lastHash = trimmed.lastIndexOf('#');
        if (lastHash < 1) {
            return isValidItemId(trimmed) ? new TargetKey(trimmed, MIN_COUNT) : null;
        }
        String id = trimmed.substring(0, lastHash);
        if (!isValidItemId(id)) {
            return null;
        }
        int parsedCount = tryParseCount(trimmed.substring(lastHash + 1));
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
