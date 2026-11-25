package com.pengin0503.itemmilestonelog;

import java.util.function.Consumer;

/**
 * Lifecycle facade for the {@code itemmilestonelog-io} executor (F-140-3 / F-140-4).
 *
 * <p>A fresh {@link MilestoneIoQueue} is created for every server start; a stopped executor is
 * never reused (integrated servers may leave and re-enter worlds in the same JVM). The queue is
 * shut down after the stop-time drain and cleared again on {@code ServerStopped}.
 *
 * <p>Must remain Minecraft-free and Java 17 compatible.
 */
public final class MilestoneIo {
    private static final Object LOCK = new Object();
    private static volatile MilestoneIoQueue queue;

    private MilestoneIo() {
    }

    /**
     * Creates a new daemon executor for this server session, replacing and abruptly stopping any
     * leftover queue from a previous session.
     */
    public static void start(Consumer<String> warningLogger) {
        synchronized (LOCK) {
            MilestoneIoQueue previous = queue;
            if (previous != null) {
                previous.shutdownNow();
            }
            queue = new MilestoneIoQueue(warningLogger);
        }
    }

    /** Returns the current queue, or {@code null} before start / after reset. */
    public static MilestoneIoQueue current() {
        return queue;
    }

    /** Returns whether normal tasks may be submitted right now. */
    public static boolean isAccepting() {
        MilestoneIoQueue current = queue;
        return current != null && current.isAccepting();
    }

    /** Returns whether an {@code importall} is in flight. */
    public static boolean isImportInFlight() {
        MilestoneIoQueue current = queue;
        return current != null && current.isImportInFlight();
    }

    /** Reserves the single {@code importall} slot on the current queue. */
    public static boolean tryBeginImport() {
        MilestoneIoQueue current = queue;
        return current != null && current.tryBeginImport();
    }

    /** Releases the {@code importall} slot on the current queue (if any). */
    public static void endImport() {
        MilestoneIoQueue current = queue;
        if (current != null) {
            current.endImport();
        }
    }

    /** Submits a normal task; {@code false} means the caller must use its synchronous fallback. */
    public static boolean submit(MilestoneIoLogic.TaskKind kind, Runnable task) {
        MilestoneIoQueue current = queue;
        return current != null && current.submit(kind, task);
    }

    /** Submits the stop-time final backup (allowed after {@link #beginStopping()}). */
    public static boolean submitFinal(Runnable task) {
        MilestoneIoQueue current = queue;
        return current != null && current.submitFinal(task);
    }

    /** Stops accepting new normal tasks while letting queued work finish. */
    public static void beginStopping() {
        MilestoneIoQueue current = queue;
        if (current != null) {
            current.beginDrain();
        }
    }

    /** Gracefully shuts the executor down after the drain (idempotent). */
    public static void shutdown() {
        MilestoneIoQueue current = queue;
        if (current != null) {
            current.shutdown();
        }
    }

    /** Clears executor state after {@code ServerStopped}; the next start creates a new queue. */
    public static void reset() {
        synchronized (LOCK) {
            MilestoneIoQueue current = queue;
            queue = null;
            if (current != null) {
                current.shutdownNow();
            }
        }
    }
}
