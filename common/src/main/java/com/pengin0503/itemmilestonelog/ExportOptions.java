package com.pengin0503.itemmilestonelog;

/**
 * Shared server-side export options (step 7 PR-1, F-150-88 / D-150-245).
 *
 * <p>Exactly one options record exists for every export writer: JSON, HTML, CSV, Markdown and
 * matrix all read the same four fields in the same order. New fields are appended here only, never
 * as writer-specific arguments. Kept Minecraft-free and Java 17 compatible so all five trees read
 * the same class.
 *
 * @param sinceDays     number of days to look back; {@code 0} means no cut-off (the existing
 *                      export shape). Brigadier bounds the CLI value to 1..365.
 * @param anonymized    whether identities are replaced (plumbing in PR-1; applied in PR-2).
 * @param snapshot      whether a dated snapshot name with generation rotation is used.
 * @param formatVariant output variant; {@code pretty} is the unchanged existing byte shape.
 */
public record ExportOptions(int sinceDays, boolean anonymized, boolean snapshot, String formatVariant) {
    /** Maximum value of the {@code since <days>} child literal (F-150-88, D-150-291). */
    public static final int MAX_SINCE_DAYS = 365;
    /** The options used by every pre-existing export command invocation. */
    public static final ExportOptions DEFAULT = new ExportOptions(0, false, false, "pretty");
    /** Options for a dated snapshot with no other changes. */
    public static final ExportOptions SNAPSHOT = new ExportOptions(0, false, true, "pretty");

    public ExportOptions {
        sinceDays = Math.max(0, Math.min(MAX_SINCE_DAYS, sinceDays));
        if (formatVariant == null || formatVariant.isBlank()) {
            formatVariant = "pretty";
        }
    }

    public ExportOptions withSinceDays(int days) {
        return new ExportOptions(days, anonymized, snapshot, formatVariant);
    }

    public ExportOptions withAnonymized(boolean value) {
        return new ExportOptions(sinceDays, value, snapshot, formatVariant);
    }

    public ExportOptions withSnapshot(boolean value) {
        return new ExportOptions(sinceDays, anonymized, value, formatVariant);
    }
}
