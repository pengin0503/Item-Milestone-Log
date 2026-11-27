package com.pengin0503.itemmilestonelog;

/**
 * F-150-31 (N155): per-player metadata attached to an encoded {@code TargetKey}
 * ({@link #priority()}, {@link #label()} and server-side {@link #pinned()}).
 *
 * <p>Stored as an optional value of {@code PlayerData.targetMeta}; a missing entry is
 * equivalent to {@link #DEFAULT}. {@link #INVALID} is a load-time sentinel: the lenient
 * SavedData readers skip it (with one {@link MilestoneLoadReport#recordElementSkip()} and a
 * warning) instead of failing the whole player record.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public record TargetMeta(int priority, String label, boolean pinned) {
    /** The metadata used when a target has no stored entry. */
    public static final TargetMeta DEFAULT = new TargetMeta(0, "", false);

    /** Skip sentinel produced by {@link #of(int, String, boolean)} for invalid input. */
    public static final TargetMeta INVALID = new TargetMeta(-1, "", false);

    /** Maximum label length in Unicode code points (not UTF-16 code units). */
    public static final int MAX_LABEL_LENGTH = 32;

    public TargetMeta {
        label = label == null ? "" : label;
    }

    /** Whether {@code priority} is within the accepted 0..9 (inclusive) range. */
    public static boolean isValidPriority(int priority) {
        return priority >= 0 && priority <= 9;
    }

    /** Removes line breaks and formatting codes and trims surrounding whitespace. */
    private static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder stripped = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\n' && c != '\r' && c != '\u00a7') {
                stripped.append(c);
            }
        }
        return stripped.toString().trim();
    }

    /**
     * Removes line breaks and formatting codes, trims surrounding whitespace and then
     * truncates to {@link #MAX_LABEL_LENGTH} code points. Never returns {@code null}.
     */
    public static String sanitizeLabel(String raw) {
        String cleaned = clean(raw);
        if (cleaned.codePointCount(0, cleaned.length()) <= MAX_LABEL_LENGTH) {
            return cleaned;
        }
        return cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_LABEL_LENGTH));
    }

    /**
     * Builds sanitized metadata, or {@link #INVALID} when the priority is outside 0..9 or
     * the cleaned label still exceeds {@link #MAX_LABEL_LENGTH} code points.
     */
    public static TargetMeta of(int priority, String label, boolean pinned) {
        if (!isValidPriority(priority)) {
            return INVALID;
        }
        String cleaned = clean(label);
        if (cleaned.codePointCount(0, cleaned.length()) > MAX_LABEL_LENGTH) {
            return INVALID;
        }
        return new TargetMeta(priority, cleaned, pinned);
    }
}
