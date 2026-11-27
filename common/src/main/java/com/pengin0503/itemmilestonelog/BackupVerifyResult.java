package com.pengin0503.itemmilestonelog;

import java.util.Locale;
import java.util.Objects;

/**
 * Pure-Java result of a read-only backup verification (F-150-55 / N39), shared across all Minecraft
 * versions via {@code common/src/main/java}.
 *
 * <p>The six statuses are the complete public outcome set: a verifier never returns exception
 * messages, stack traces, paths or webhook URLs. Counts and the schema version are reported only for
 * {@link Status#OK}; every other status carries neutral zeros. This object is produced by a version
 * side NBT wrapper that parses a disposable {@link #players()} / {@link #worldFirsts()} snapshot and
 * never loads into the live {@code MilestoneData}.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public record BackupVerifyResult(Status status, int players, int worldFirsts, int schemaVersion) {
    /** Complete set of verify outcomes; the language key suffix is the lower-case enum name. */
    public enum Status {
        OK,
        INVALID_NAME,
        NOT_FOUND,
        INVALID_NBT,
        INCOMPATIBLE_SCHEMA,
        IO_ERROR
    }

    private static final String KEY_PREFIX = "message.itemmilestonelog.backup_verify_";

    public BackupVerifyResult {
        Objects.requireNonNull(status, "status");
        if (players < 0 || worldFirsts < 0 || schemaVersion < 0) {
            throw new IllegalArgumentException("verify counters must not be negative");
        }
        if (status != Status.OK && (players != 0 || worldFirsts != 0 || schemaVersion != 0)) {
            throw new IllegalArgumentException("only OK may carry snapshot counters: " + status);
        }
    }

    /** Fixed failure outcome with no detail and no counters. */
    public static BackupVerifyResult failure(Status status) {
        Objects.requireNonNull(status, "status");
        if (status == Status.OK) {
            throw new IllegalArgumentException("use ok(...) for a successful verification");
        }
        return new BackupVerifyResult(status, 0, 0, 0);
    }

    /** Successful verification of a disposable snapshot. */
    public static BackupVerifyResult ok(int players, int worldFirsts, int schemaVersion) {
        return new BackupVerifyResult(Status.OK, players, worldFirsts, schemaVersion);
    }

    /** Translation key of the single fixed wording for this outcome. */
    public String messageKey() {
        return KEY_PREFIX + status.name().toLowerCase(Locale.ROOT);
    }

    /** Whether the backup parsed cleanly and is compatible with this release. */
    public boolean isOk() {
        return status == Status.OK;
    }
}
