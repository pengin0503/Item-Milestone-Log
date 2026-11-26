package com.pengin0503.itemmilestonelog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Single-threaded, serial I/O queue used by F-140-3 (N45): auto backups and {@code importall}
 * parsing run on one daemon thread named {@value #THREAD_NAME}, so their ordering is preserved
 * and the server thread never waits for file I/O during normal gameplay.
 *
 * <p>The queue tracks accepted-but-unfinished tasks and exposes a drain wait for F-140-4 (N149).
 * Submission decisions themselves live in {@link MilestoneIoLogic}; this class only enforces the
 * counters and thread lifecycle with pure Java. It must stay Minecraft-free.
 */
public final class MilestoneIoQueue {
    /** Name of the daemon I/O thread created for each server session. */
    public static final String THREAD_NAME = "itemmilestonelog-io";

    private final ExecutorService executor;
    private final Consumer<String> warningLogger;
    private final Object monitor = new Object();
    private final AtomicInteger pendingTasks = new AtomicInteger();
    private final EnumMap<MilestoneIoLogic.TaskKind, AtomicInteger> pendingByKind =
            new EnumMap<>(MilestoneIoLogic.TaskKind.class);
    private final AtomicBoolean importInFlight = new AtomicBoolean();
    private volatile boolean accepting = true;
    private volatile boolean shutdown;
    private volatile Thread ioThread;

    /**
     * Creates and starts the daemon executor. Callers normally use {@link MilestoneIo#start} so a
     * previous executor can never be reused.
     */
    public MilestoneIoQueue(Consumer<String> warningLogger) {
        this.warningLogger = warningLogger != null ? warningLogger : message -> {
        };
        for (MilestoneIoLogic.TaskKind kind : MilestoneIoLogic.TaskKind.values()) {
            pendingByKind.put(kind, new AtomicInteger());
        }
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            ioThread = thread;
            return thread;
        };
        this.executor = Executors.newSingleThreadExecutor(factory);
    }

    /** Returns whether normal tasks are currently accepted. */
    public boolean isAccepting() {
        return MilestoneIoLogic.canSubmit(accepting, isShutdown());
    }

    /** Returns whether this queue has been shut down (normally or abruptly). */
    public boolean isShutdown() {
        return shutdown || executor.isShutdown();
    }

    /** Returns whether an {@code importall} is currently being parsed/applied. */
    public boolean isImportInFlight() {
        return importInFlight.get();
    }

    /**
     * Reserves the single {@code importall} slot. Returns {@code false} when a task is already
     * running or the queue is stopping. The state inspection and the CAS share the monitor with
     * the drain/shutdown transitions, so an import cannot be begun after a stop transition.
     */
    public boolean tryBeginImport() {
        synchronized (monitor) {
            if (!MilestoneIoLogic.canBeginImport(accepting, isShutdown(), importInFlight.get())) {
                return false;
            }
            return importInFlight.compareAndSet(false, true);
        }
    }

    /** Releases the {@code importall} slot (idempotent). */
    public void endImport() {
        importInFlight.set(false);
    }

    /**
     * Submits a normal task. Returns {@code false} when the queue is stopping or the executor
     * rejected the task; the caller must then run that single task synchronously.
     */
    public boolean submit(MilestoneIoLogic.TaskKind kind, Runnable task) {
        return submitInternal(kind, task, false);
    }

    /**
     * Submits the stop-time final backup. This is allowed after {@link #beginDrain()} but never
     * after shutdown.
     */
    public boolean submitFinal(Runnable task) {
        return submitInternal(MilestoneIoLogic.TaskKind.FINAL_BACKUP, task, true);
    }

    private boolean submitInternal(MilestoneIoLogic.TaskKind kind, Runnable task, boolean allowAfterDrain) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(task, "task");
        // Inspect the state and increment both counters under the same monitor used by the drain
        // and shutdown transitions. Otherwise a thread that had already passed the accepting
        // check could increment after awaitDrain() observed zero and completed the drain (#109).
        synchronized (monitor) {
            if (isShutdown()) {
                return false;
            }
            if (!allowAfterDrain && !accepting) {
                return false;
            }
            pendingTasks.incrementAndGet();
            pendingByKind.get(kind).incrementAndGet();
        }
        try {
            executor.execute(new QueuedTask(kind, task));
            return true;
        } catch (RejectedExecutionException e) {
            finishTask(kind);
            return false;
        }
    }

    /** Carries the task kind so {@link #shutdownNow()} can release the counters it discards. */
    private final class QueuedTask implements Runnable {
        private final MilestoneIoLogic.TaskKind kind;
        private final Runnable task;

        private QueuedTask(MilestoneIoLogic.TaskKind kind, Runnable task) {
            this.kind = kind;
            this.task = task;
        }

        @Override
        public void run() {
            runTask(kind, task);
        }
    }

    private void runTask(MilestoneIoLogic.TaskKind kind, Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            warningLogger.accept("I/O task '" + kind.label() + "' failed: " + t);
        } finally {
            finishTask(kind);
        }
    }

    private void finishTask(MilestoneIoLogic.TaskKind kind) {
        // Keep the per-kind and total counters consistent for drain progress snapshots: both are
        // updated under the monitor that awaitDrain also holds while reading them.
        synchronized (monitor) {
            pendingByKind.get(kind).decrementAndGet();
            pendingTasks.decrementAndGet();
            monitor.notifyAll();
        }
    }

    /** Stops accepting new normal tasks; already queued work still runs. */
    public void beginDrain() {
        // The transition shares the monitor with submitInternal()/tryBeginImport(), so once this
        // returns no new normal task can start after a completed drain (#109).
        synchronized (monitor) {
            accepting = false;
        }
    }

    /**
     * Waits up to {@code timeoutMillis} for every accepted task to finish. The progress callback
     * receives a snapshot at most every 5 seconds (see {@link MilestoneIoLogic#DRAIN_LOG_INTERVAL_MILLIS}).
     * Never throws; an interrupt returns an incomplete result and restores the interrupt flag.
     */
    public MilestoneIoLogic.DrainResult awaitDrain(long timeoutMillis, Consumer<MilestoneIoLogic.DrainResult> progress) {
        return awaitDrain(timeoutMillis, progress, MilestoneIoLogic.DRAIN_LOG_INTERVAL_MILLIS);
    }

    /**
     * #123 test seam: identical drain logic with an injectable progress-log interval, so the
     * callback can be exercised without waiting five real seconds. Production behaviour is
     * unchanged: the public entry point passes {@link MilestoneIoLogic#DRAIN_LOG_INTERVAL_MILLIS}.
     */
    MilestoneIoLogic.DrainResult awaitDrain(long timeoutMillis,
                                            Consumer<MilestoneIoLogic.DrainResult> progress,
                                            long progressIntervalMillis) {
        long start = System.nanoTime();
        long lastLogged = 0L;
        long effectiveTimeout = Math.max(0L, timeoutMillis);
        synchronized (monitor) {
            while (true) {
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                int remaining = pendingTasks.get();
                MilestoneIoLogic.DrainDecision decision =
                        MilestoneIoLogic.drainDecision(remaining, elapsed, effectiveTimeout);
                Map<MilestoneIoLogic.TaskKind, Integer> kinds = pendingByKindSnapshot();
                MilestoneIoLogic.DrainResult snapshot = new MilestoneIoLogic.DrainResult(
                        decision == MilestoneIoLogic.DrainDecision.COMPLETE, elapsed, remaining, kinds);
                if (decision != MilestoneIoLogic.DrainDecision.CONTINUE) {
                    return snapshot;
                }
                if (progress != null
                        && MilestoneIoLogic.shouldLogDrainProgress(
                                elapsed, lastLogged, progressIntervalMillis)) {
                    progress.accept(snapshot);
                    lastLogged = elapsed;
                }
                long wait = Math.min(MilestoneIoLogic.DRAIN_POLL_MILLIS,
                        Math.max(1L, effectiveTimeout - elapsed));
                try {
                    monitor.wait(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new MilestoneIoLogic.DrainResult(false, elapsed, pendingTasks.get(),
                            pendingByKindSnapshot());
                }
            }
        }
    }

    /** Returns a snapshot of unfinished task counts by kind. */
    public Map<MilestoneIoLogic.TaskKind, Integer> pendingByKindSnapshot() {
        // Share the monitor with finishTask()/submitInternal()/shutdownNow() so an external
        // caller never observes a per-kind count that disagrees with the total tasks.
        synchronized (monitor) {
            Map<MilestoneIoLogic.TaskKind, Integer> snapshot = new LinkedHashMap<>();
            for (Map.Entry<MilestoneIoLogic.TaskKind, AtomicInteger> entry : pendingByKind.entrySet()) {
                int value = entry.getValue().get();
                if (value > 0) {
                    snapshot.put(entry.getKey(), value);
                }
            }
            return Collections.unmodifiableMap(snapshot);
        }
    }

    /** Returns the number of accepted tasks that have not finished yet. */
    public int pendingTaskCount() {
        return pendingTasks.get();
    }

    /** Stops accepting tasks and interrupts the running task; queued tasks are discarded. */
    public void shutdownNow() {
        synchronized (monitor) {
            shutdown = true;
            accepting = false;
            // shutdownNow() returns exactly the queued runnables that will never run. Release
            // their acceptance counters and wake a waiting drain, otherwise pendingTasks can
            // never return to zero and awaitDrain() only ends by timing out (#109).
            for (Runnable discarded : executor.shutdownNow()) {
                if (discarded instanceof QueuedTask queued) {
                    pendingByKind.get(queued.kind).decrementAndGet();
                    pendingTasks.decrementAndGet();
                }
            }
            monitor.notifyAll();
        }
    }

    /** Stops accepting tasks after a completed drain. Idempotent. */
    public void shutdown() {
        synchronized (monitor) {
            shutdown = true;
            accepting = false;
            executor.shutdown();
        }
    }

    /** Returns the executor thread while it is alive (diagnostics/tests). */
    public List<String> threadNamesNow() {
        Thread thread = ioThread;
        if (thread == null || !thread.isAlive()) {
            return List.of();
        }
        List<String> names = new ArrayList<>(1);
        names.add(thread.getName());
        return names;
    }
}
