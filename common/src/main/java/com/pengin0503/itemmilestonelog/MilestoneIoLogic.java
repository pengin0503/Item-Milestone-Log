package com.pengin0503.itemmilestonelog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Pure-Java decision logic for F-140-3 / F-140-4 (N45 + N149): whether a task may be submitted
 * to the single-threaded I/O executor, whether a second {@code importall} may begin, when the
 * stop-time drain is complete/timed out, and whether the final backup must fall back to a
 * synchronous write.
 *
 * <p>Kept free of Minecraft/NeoForge dependencies and Java 17 compatible so the same rules are
 * shared by every edition and can be unit-tested without threads.
 */
public final class MilestoneIoLogic {
    /** Maximum time {@code onServerStopping} waits for queued I/O work (D-140-3). */
    public static final long DRAIN_TIMEOUT_MILLIS = 30_000L;
    /** How often the stop-time drain logs the number of remaining tasks. */
    public static final long DRAIN_LOG_INTERVAL_MILLIS = 5_000L;
    /** Maximum time the drain waiter sleeps before re-checking the queue. */
    public static final long DRAIN_POLL_MILLIS = 250L;

    /** Kinds of work that may run on the {@code itemmilestonelog-io} executor. */
    public enum TaskKind {
        BACKUP("backup"),
        IMPORT_ALL("importall"),
        FINAL_BACKUP("final backup");

        private final String label;

        TaskKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Outcome of one drain-wait decision. */
    public enum DrainDecision {
        /** No work remains. */
        COMPLETE,
        /** Work remains and the timeout has not been reached yet. */
        CONTINUE,
        /** Work remains but the timeout has been reached. */
        TIMEOUT
    }

    /** How the stop-time final backup should run. */
    public enum FinalBackupMode {
        /** Queue it on the still-healthy executor and include it in the drain. */
        ASYNC,
        /** Write it on the server thread because the drain timed out or the executor is gone. */
        SYNC
    }

    /**
     * Immutable snapshot of a drain wait.
     *
     * @param completed       whether the queue became empty before the timeout
     * @param elapsedMillis   milliseconds spent waiting
     * @param remainingTasks  tasks accepted but not finished yet (running + queued)
     * @param remainingByKind remaining tasks grouped by kind
     */
    public record DrainResult(boolean completed, long elapsedMillis, int remainingTasks,
                              Map<TaskKind, Integer> remainingByKind) {
        public DrainResult {
            remainingByKind = Collections.unmodifiableMap(new LinkedHashMap<>(remainingByKind));
        }
    }

    private MilestoneIoLogic() {
    }

    /** Returns whether a normal (non-final) task may be submitted. */
    public static boolean canSubmit(boolean accepting, boolean shutdown) {
        return accepting && !shutdown;
    }

    /** Returns whether an {@code importall} may begin, given the stopping and in-flight flags. */
    public static boolean canBeginImport(boolean accepting, boolean shutdown, boolean importInFlight) {
        return accepting && !shutdown && !importInFlight;
    }

    /** Decides whether to keep waiting, or finish, for {@code remainingTasks}. */
    public static DrainDecision drainDecision(int remainingTasks, long elapsedMillis, long timeoutMillis) {
        if (remainingTasks <= 0) {
            return DrainDecision.COMPLETE;
        }
        if (elapsedMillis >= Math.max(0L, timeoutMillis)) {
            return DrainDecision.TIMEOUT;
        }
        return DrainDecision.CONTINUE;
    }

    /** Returns whether the 5-second drain progress log is due. */
    public static boolean shouldLogDrainProgress(long elapsedMillis, long lastLoggedMillis, long intervalMillis) {
        return elapsedMillis - lastLoggedMillis >= Math.max(1L, intervalMillis);
    }

    /** Returns the remaining drain budget, never negative. */
    public static long remainingMillis(long elapsedMillis, long timeoutMillis) {
        return Math.max(0L, timeoutMillis - Math.max(0L, elapsedMillis));
    }

    /** Chooses the final-backup path from the first drain outcome. */
    public static FinalBackupMode finalBackupMode(DrainDecision firstDrainDecision) {
        Objects.requireNonNull(firstDrainDecision, "firstDrainDecision");
        return firstDrainDecision == DrainDecision.TIMEOUT ? FinalBackupMode.SYNC : FinalBackupMode.ASYNC;
    }

    /** Formats a kind/count map for a warning log, e.g. {@code importall=1, backup=2}. */
    public static String describeKinds(Map<TaskKind, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<TaskKind, Integer> entry : counts.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(entry.getKey().label()).append('=').append(entry.getValue());
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }
}
