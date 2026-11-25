package com.pengin0503.itemmilestonelog;

import net.minecraft.server.MinecraftServer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * F-140-4 (N149): fixed stop-time ordering for the {@code itemmilestonelog-io} executor.
 *
 * <ol>
 *   <li>Stop accepting new normal tasks (later submissions use their sync fallback).</li>
 *   <li>Drain the queue for at most 30 seconds, info-logging remaining work every 5 seconds.</li>
 *   <li>Apply an importall result that finished during the drain, then queue the final automatic
 *       backup as the last executor task and include it in the drain.</li>
 *   <li>On timeout, interrupt unfinished tasks, warn with their count/kinds, and write the final
 *       backup synchronously.</li>
 *   <li>Run the existing stop work, then shut the executor down.</li>
 * </ol>
 *
 * <p>{@code ServerStopped} clears the executor state so the next world start creates a new queue.
 */
public final class MilestoneServerStop {
    private MilestoneServerStop() {
    }

    /** Creates a fresh daemon I/O executor for this server session. */
    public static void onServerStarting() {
        // Clear any importall state left over from a session that did not stop cleanly, then create
        // a brand-new executor; a stopped executor is never reused (F-140-3).
        MilestoneImportAll.resetState();
        MilestoneIo.start(message -> ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] {}", message));
    }

    /** Runs the fixed N149 stop sequence. Must be called on the server thread. */
    public static void onServerStopping(MinecraftServer server) {
        long startNanos = System.nanoTime();
        MilestoneIo.beginStopping();
        MilestoneIoQueue queue = MilestoneIo.current();

        MilestoneIoLogic.DrainResult firstDrain;
        if (queue != null) {
            firstDrain = queue.awaitDrain(remainingMillis(startNanos),
                    snapshot -> logWaiting("queued work", snapshot));
        } else {
            firstDrain = new MilestoneIoLogic.DrainResult(true, 0L, 0, Map.of());
        }

        boolean timedOut = !firstDrain.completed();
        int unfinishedTasks = firstDrain.remainingTasks();
        Map<MilestoneIoLogic.TaskKind, Integer> unfinishedKinds = firstDrain.remainingByKind();

        if (timedOut) {
            if (queue != null) {
                queue.shutdownNow();
            }
            MilestoneImportAll.cancelPending("server stopping (I/O drain timeout)");
            MilestoneBackup.writeFinalBackupNow(server);
        } else {
            // A parsed importall may still have its server.execute application queued behind this
            // event; apply it before the final backup snapshot so its data is included.
            MilestoneImportAll.applyPendingOnStop(server);
            Runnable finalBackup = MilestoneBackup.prepareFinalBackup(server);
            if (finalBackup != null) {
                boolean submitted = queue != null && MilestoneIo.submitFinal(finalBackup);
                if (!submitted) {
                    if (queue != null) {
                        ItemMilestoneLog.LOGGER.warn(
                                "[ItemMilestoneLog] I/O executor unavailable for the final backup; writing synchronously");
                    }
                    finalBackup.run();
                } else {
                    MilestoneIoLogic.DrainResult finalDrain = queue.awaitDrain(remainingMillis(startNanos),
                            snapshot -> logWaiting("final backup", snapshot));
                    if (!finalDrain.completed()) {
                        timedOut = true;
                        unfinishedTasks += finalDrain.remainingTasks();
                        unfinishedKinds = mergeKinds(unfinishedKinds, finalDrain.remainingByKind());
                        queue.shutdownNow();
                        MilestoneBackup.writeFinalBackupNow(server);
                    }
                }
            }
        }

        if (timedOut) {
            ItemMilestoneLog.LOGGER.warn(
                    "[ItemMilestoneLog] I/O drain timed out; interrupted {} unfinished task(s): {}",
                    unfinishedTasks, MilestoneIoLogic.describeKinds(unfinishedKinds));
        }

        // Existing stop work, after the drain/final backup as required by F-140-4.
        Tracker.clearPendingNotifications();
        MilestoneCommandRunner.clearPending();
        MilestoneService.UNDO_SNAPSHOTS.clear();
        ScoreboardSync.resetWarningState();
        DiscordNotifier.shutdown();

        MilestoneIo.shutdown();
    }

    /** Clears executor/import state so the next world can create a fresh executor. */
    public static void onServerStopped() {
        MilestoneImportAll.resetState();
        MilestoneIo.reset();
    }

    private static void logWaiting(String phase, MilestoneIoLogic.DrainResult snapshot) {
        ItemMilestoneLog.LOGGER.info(
                "[ItemMilestoneLog] Waiting for I/O {} to drain: {} task(s) remaining ({})",
                phase,
                snapshot.remainingTasks(),
                MilestoneIoLogic.describeKinds(snapshot.remainingByKind()));
    }

    private static long remainingMillis(long startNanos) {
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        return MilestoneIoLogic.remainingMillis(elapsed, MilestoneIoLogic.DRAIN_TIMEOUT_MILLIS);
    }

    private static Map<MilestoneIoLogic.TaskKind, Integer> mergeKinds(
            Map<MilestoneIoLogic.TaskKind, Integer> left,
            Map<MilestoneIoLogic.TaskKind, Integer> right) {
        Map<MilestoneIoLogic.TaskKind, Integer> merged = new LinkedHashMap<>(left);
        for (Map.Entry<MilestoneIoLogic.TaskKind, Integer> entry : right.entrySet()) {
            merged.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
        return merged;
    }
}
