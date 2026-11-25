package com.pengin0503.itemmilestonelog;

import java.util.Objects;

/**
 * Pure-Java logic for F-140-1 (N88): the explicit {@code schemaVersion} of {@code MilestoneData},
 * shared across all Minecraft versions via {@code common/src/main/java}.
 *
 * <p>Load rules (D-140-1):
 * <ul>
 *   <li>field absent &rarr; treated as {@code 0} (data written up to 1.3.1), no warning;</li>
 *   <li>value in {@code [0, CURRENT_SCHEMA_VERSION]} &rarr; used as-is;</li>
 *   <li>value greater than {@code CURRENT_SCHEMA_VERSION} &rarr; loaded best-effort and the greater
 *       value is retained so a save from this older version does not downgrade it;</li>
 *   <li>malformed (non-numeric) or negative value &rarr; warned about and treated as {@code 0}.</li>
 * </ul>
 * The value written on save is always {@code max(CURRENT_SCHEMA_VERSION, retainedValue)}.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class SchemaVersionLogic {
    /** Schema version written by this release (1.4.0). */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * Sentinel meaning "the {@code schemaVersion} field is absent from the stored data". Distinct
     * from {@link #MALFORMED_SCHEMA_VERSION} so that missing legacy data does not log a warning.
     */
    public static final int ABSENT_SCHEMA_VERSION = Integer.MIN_VALUE;

    /** Sentinel meaning "the {@code schemaVersion} field is present but not a number". */
    public static final int MALFORMED_SCHEMA_VERSION = Integer.MIN_VALUE + 1;

    /** How a stored {@code schemaVersion} was interpreted. */
    public enum Status {
        /** Field absent: legacy data written before 1.4.0. */
        MISSING,
        /** Field present and within {@code [0, CURRENT_SCHEMA_VERSION]}. */
        VALID,
        /** Field present and greater than {@link #CURRENT_SCHEMA_VERSION}: newer data. */
        FUTURE,
        /** Field present but malformed or negative. */
        INVALID
    }

    /**
     * Result of interpreting a stored {@code schemaVersion}.
     *
     * @param retainedVersion value kept in memory; {@code 0} for missing/invalid fields and the
     *                        stored value otherwise
     * @param status          how the stored value was interpreted
     */
    public record LoadResult(int retainedVersion, Status status) {
        public LoadResult {
            Objects.requireNonNull(status, "status");
            if (retainedVersion < 0) {
                throw new IllegalArgumentException("retainedVersion must not be negative: " + retainedVersion);
            }
        }

        /** Value to write on save: {@code max(CURRENT_SCHEMA_VERSION, retainedVersion)}. */
        public int versionToSave() {
            return SchemaVersionLogic.versionToSave(retainedVersion);
        }

        /** Whether the load should log one warning about the stored value. */
        public boolean shouldWarn() {
            return status == Status.FUTURE || status == Status.INVALID;
        }

        /** Whether the stored data comes from a newer release than this one. */
        public boolean isFuture() {
            return status == Status.FUTURE;
        }
    }

    private SchemaVersionLogic() {
    }

    /** Interprets a raw stored {@code schemaVersion} (may be {@link #ABSENT_SCHEMA_VERSION} or {@link #MALFORMED_SCHEMA_VERSION}). */
    public static LoadResult fromStoredVersion(int rawVersion) {
        if (rawVersion == ABSENT_SCHEMA_VERSION) {
            return new LoadResult(0, Status.MISSING);
        }
        if (rawVersion == MALFORMED_SCHEMA_VERSION || rawVersion < 0) {
            return new LoadResult(0, Status.INVALID);
        }
        if (rawVersion > CURRENT_SCHEMA_VERSION) {
            return new LoadResult(rawVersion, Status.FUTURE);
        }
        return new LoadResult(rawVersion, Status.VALID);
    }

    /** Returns the value to write on save for a retained (loaded) schema version. */
    public static int versionToSave(int retainedVersion) {
        return Math.max(CURRENT_SCHEMA_VERSION, retainedVersion);
    }

    /**
     * Builds the single warning to log for a stored value, or returns {@code null} when no warning
     * is needed (valid value or absent field). Kept here so every loader logs identical wording.
     */
    public static String warningMessage(int rawVersion, LoadResult result) {
        Objects.requireNonNull(result, "result");
        if (!result.shouldWarn()) {
            return null;
        }
        if (result.isFuture()) {
            return "[ItemMilestoneLog] SavedData has newer schemaVersion " + rawVersion
                    + " (current " + CURRENT_SCHEMA_VERSION
                    + "); loading best-effort and preserving it on save";
        }
        String shown = rawVersion == MALFORMED_SCHEMA_VERSION ? "malformed" : String.valueOf(rawVersion);
        return "[ItemMilestoneLog] Ignoring invalid or negative schemaVersion " + shown
                + " in SavedData; treating it as 0";
    }
}
