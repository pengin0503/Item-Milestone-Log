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
 *   <li>Apply an importall result that finished during the drain, then flush the reward commands
 *       (#150) so records they create are included in the final backup.</li>
 *   <li>Queue the final automatic backup as the last executor task and include it in the drain.</li>
 *   <li>On timeout, interrupt unfinished tasks, warn with their count/kinds, and write the final
 *       backup synchronously.</li>
 *   <li>Deliver pending notifications while players are still connected, clear the remaining stop
 *       state, then shut the executor down.</li>
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
        // step 7 PR-1 (F-150-90): in-memory schedule state only; no SavedData key is added.
        MilestoneExport.resetAutoExportSession();
        MilestoneIo.start(message -> ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] {}", message));
    }

    /** Runs the fixed N149 stop sequence. Must be called on the server thread. */
    public static void onServerStopping(MinecraftServer server) {
        long startNanos = System.nanoTime();
        // A LevelEvent.Save fires after this event during Minecraft's stop sequence; suppress the
        // duplicate automatic backup before any stop work or drain can trigger it (#106).
        MilestoneBackup.beginStopping();
        // F-150-90: enqueue the final automatic batch while the queue still accepts work, so it
        // joins the bounded drain below and is written exactly once (guarded in MilestoneExport).
        MilestoneExport.autoExportOnStop(server);
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

        // #150: reward commands can create further milestone records (#135). Run them before any
        // final-backup snapshot so those records are in the backup file, while players are still
        // connected. The first drain stays before this step so command work does not consume the
        // I/O drain budget; notifications stay in the tail block for the same reason.
        MilestoneCommandRunner.runAllOnStop(server);

        if (timedOut) {
            if (queue != null) {
                queue.shutdownNow();
            }
            // F-140-4 / review 2-2: a parse that already completed during the first drain must
            // still be applied before the final backup, even on the timeout path. Only work that
            // did not finish parsing is abandoned.
            MilestoneImportAll.applyPendingOnStop(server);
            MilestoneImportAll.cancelPending("server stopping (I/O drain timeout)");
            MilestoneBackup.writeFinalBackupNow(server);
        } else {
            // A parsed importall may still have its server.execute application queued behind this
            // event; apply it before the final backup snapshot so its data is included.
            MilestoneImportAll.applyPendingOnStop(server);
            long remaining = remainingMillis(startNanos);
            if (queue == null || MilestoneIoLogic.needsSynchronousFinalBackup(remaining)) {
                // F-140-4 / review 2-3: submitting with an exhausted (or near-exhausted) budget
                // would guarantee a spurious timeout + shutdownNow + double serialization. Skip
                // the preparation/submission entirely and serialize once in the sync fallback.
                if (queue != null) {
                    ItemMilestoneLog.LOGGER.info(
                            "[ItemMilestoneLog] Only {} ms of the I/O drain budget remains; writing the final backup synchronously",
                            remaining);
                }
                MilestoneBackup.writeFinalBackupNow(server);
            } else {
                Runnable finalBackup = MilestoneBackup.prepareFinalBackup(server);
                if (finalBackup != null) {
                    // Re-check after preparing the snapshot (serialization itself takes time) so
                    // the final drain cannot exceed the overall stop budget.
                    long finalBudget = remainingMillis(startNanos);
                    if (MilestoneIoLogic.needsSynchronousFinalBackup(finalBudget)
                            || !MilestoneIo.submitFinal(finalBackup)) {
                        if (finalBudget > MilestoneIoLogic.FINAL_BACKUP_MIN_BUDGET_MILLIS) {
                            ItemMilestoneLog.LOGGER.warn(
                                    "[ItemMilestoneLog] I/O executor unavailable for the final backup; writing synchronously");
                        } else {
                            ItemMilestoneLog.LOGGER.info(
                                    "[ItemMilestoneLog] Only {} ms of the I/O drain budget remains after preparing the final backup; writing it synchronously",
                                    finalBudget);
                        }
                        // The prepared snapshot is written with the bounded claim/lock path, so
                        // it is serialized only once and cannot block the stop indefinitely.
                        MilestoneBackup.writePreparedFinalBackupNow(finalBackup);
                    } else {
                        MilestoneIoLogic.DrainResult finalDrain = queue.awaitDrain(finalBudget,
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
        }

        if (timedOut) {
            ItemMilestoneLog.LOGGER.warn(
                    "[ItemMilestoneLog] I/O drain timed out; interrupted {} unfinished task(s): {}",
                    unfinishedTasks, MilestoneIoLogic.describeKinds(unfinishedKinds));
        }

        // Existing stop work after the final backup: deliver the pending notifications (chat +
        // Discord) while players are still connected, then clear leftovers defensively (#135).
        // Reward commands already ran before the backup snapshot (#150).
        Tracker.flushPendingOnStop(server);
        MilestoneCommandRunner.clearPending();
        Tracker.clearPendingNotifications();
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
