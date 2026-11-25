package com.pengin0503.itemmilestonelog;

import java.util.Objects;

/**
 * Per-load accumulator for F-140-2 (N141): counts element-level skips and records the loaded
 * {@code schemaVersion} so the server-start evacuation hook can decide whether to copy the
 * on-disk SavedData file.
 *
 * <p>The 1.21.1/Forge NBT loaders and the 26.x Codec decoders all call
 * {@link #recordElementSkip()} at their existing warn-log sites. A Codec decode runs nested
 * decoders before its final factory method, so the active report is held in a {@link ThreadLocal}
 * for the duration of one decode rather than passed through every lambda.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class MilestoneLoadReport {
    private static final ThreadLocal<MilestoneLoadReport> ACTIVE = new ThreadLocal<>();

    private final MilestoneLoadReport previous;
    private int elementSkipCount;
    private boolean totalFailure;
    private int loadedSchemaVersion = CorruptionEvacuationLogic.UNKNOWN_SCHEMA_VERSION;

    private MilestoneLoadReport(MilestoneLoadReport previous) {
        this.previous = previous;
    }

    /** Starts a report on the current thread and makes it the active accumulator. */
    public static MilestoneLoadReport begin() {
        MilestoneLoadReport report = new MilestoneLoadReport(ACTIVE.get());
        ACTIVE.set(report);
        return report;
    }

    /** Ends {@code report}, restoring the previous active report (if any). */
    public static void end(MilestoneLoadReport report) {
        Objects.requireNonNull(report, "report");
        if (report.previous == null) {
            ACTIVE.remove();
        } else {
            ACTIVE.set(report.previous);
        }
    }

    /** Records one element/field skipped by the lenient loader. No-op outside a load. */
    public static void recordElementSkip() {
        MilestoneLoadReport report = ACTIVE.get();
        if (report != null) {
            report.elementSkipCount++;
        }
    }

    /** Marks the whole load as failed (root structure unusable / decoder failure). */
    public static void markTotalFailure() {
        MilestoneLoadReport report = ACTIVE.get();
        if (report != null) {
            report.totalFailure = true;
        }
    }

    /** Records the schemaVersion value interpreted from the stored data. */
    public static void recordLoadedSchemaVersion(int schemaVersion) {
        MilestoneLoadReport report = ACTIVE.get();
        if (report != null) {
            report.loadedSchemaVersion = Math.max(
                    CorruptionEvacuationLogic.UNKNOWN_SCHEMA_VERSION, schemaVersion);
        }
    }

    /** Number of element-level skips seen by this load. */
    public int elementSkipCount() {
        return elementSkipCount;
    }

    /** Whether this load was marked as a total failure. */
    public boolean totalFailure() {
        return totalFailure;
    }

    /** Loaded schemaVersion, or {@link CorruptionEvacuationLogic#UNKNOWN_SCHEMA_VERSION}. */
    public int loadedSchemaVersion() {
        return loadedSchemaVersion;
    }

    /** Snapshot consumed by the evacuation decision. */
    public CorruptionEvacuationLogic.LoadOutcome toOutcome() {
        return new CorruptionEvacuationLogic.LoadOutcome(
                elementSkipCount, totalFailure, loadedSchemaVersion);
    }
}
