package com.pengin0503.itemmilestonelog;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * F-140-3 (N45): asynchronous {@code /milestonelog importall}.
 *
 * <p>File reading, JSON parsing and the immutable merge plan are built on the single-threaded
 * {@code itemmilestonelog-io} executor. Applying the plan to {@link MilestoneData}, refreshing
 * scoreboards/GUI state and sending the result feedback happen back on the server thread. Plain
 * single-player {@code import} stays synchronous in {@link MilestoneCommands}.
 *
 * <p>The command feedback is two-stage (D-140-5): an "accepted/started" message immediately, then
 * the existing result message or a failure message with a short reason. A second {@code importall}
 * while one is in flight is rejected. If the executor is unavailable the command runs the old
 * synchronous path for that invocation only and logs a warning.
 */
public final class MilestoneImportAll {
    private static final AtomicReference<Job> ACTIVE = new AtomicReference<>();

    private MilestoneImportAll() {
    }

    /**
     * Runs the command. Must be called on the server thread.
     *
     * @return Brigadier result ({@code 1} accepted/accepted-synchronously, {@code 0} rejected)
     */
    public static int run(CommandSourceStack source, String fileName) {
        if (!Config.EXPORT_IMPORT_ENABLED.get()) {
            return fail(source, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        if (!MilestoneExport.isSafeFileName(fileName)) {
            return fail(source, ServerLang.tr(source, "message.itemmilestonelog.import_invalid_name", fileName));
        }
        MinecraftServer server = source.getServer();
        Path dir = MilestoneExport.exportsDir(server).normalize();
        Path file = dir.resolve(fileName).normalize();
        if (!file.getParent().equals(dir)) {
            return fail(source, ServerLang.tr(source, "message.itemmilestonelog.import_invalid_name", fileName));
        }
        if (!Files.isRegularFile(file)) {
            return fail(source, ServerLang.tr(source, "message.itemmilestonelog.import_not_found", fileName));
        }

        if (MilestoneIo.isImportInFlight()) {
            return fail(source, ServerLang.tr(source, "message.itemmilestonelog.importall_busy"));
        }
        if (!MilestoneIo.isAccepting()) {
            ItemMilestoneLog.LOGGER.warn(
                    "[ItemMilestoneLog] I/O executor is not accepting tasks; running importall synchronously: {}",
                    fileName);
            return runSynchronously(source, file, fileName);
        }
        if (!MilestoneIo.tryBeginImport()) {
            return fail(source, ServerLang.tr(source, "message.itemmilestonelog.importall_busy"));
        }

        Job job = new Job(server, file, fileName, source);
        if (!submit(job)) {
            MilestoneIo.endImport();
            ItemMilestoneLog.LOGGER.warn(
                    "[ItemMilestoneLog] Could not submit importall to the I/O executor; "
                            + "falling back to a synchronous import: {}",
                    fileName);
            return runSynchronously(source, file, fileName);
        }
        source.sendSuccess(() -> ServerLang.tr(source,
                "message.itemmilestonelog.importall_started", fileName), false);
        return 1;
    }

    private static boolean submit(Job job) {
        return submit(job, MilestoneImportAll::parseJob, runnable -> job.server.execute(runnable));
    }

    private static boolean submit(Job job, ParseAction parseAction, Consumer<Runnable> scheduler) {
        if (!ACTIVE.compareAndSet(null, job)) {
            return false;
        }
        boolean accepted = MilestoneIo.submit(MilestoneIoLogic.TaskKind.IMPORT_ALL, () -> {
            try {
                parseAction.parse(job);
            } catch (Throwable t) {
                // Errors (StackOverflowError on deeply nested JSON, OutOfMemoryError) must be
                // recorded just like checked failures; otherwise the application step is never
                // scheduled and ACTIVE/importInFlight stay set forever (review 3-1).
                job.failure.set(t);
            } finally {
                job.parseDone.set(true);
            }
            try {
                scheduler.accept(() -> apply(job));
            } catch (Throwable t) {
                // During shutdown server.execute can reject. Release the in-flight state here and
                // attempt failure feedback so importall can never stay permanently busy.
                ItemMilestoneLog.LOGGER.warn(
                        "[ItemMilestoneLog] Could not schedule importall result application: {}", t.getMessage());
                releaseRejected(job, t);
            }
        });
        if (!accepted) {
            ACTIVE.compareAndSet(job, null);
        }
        return accepted;
    }

    private static void parseJob(Job job) throws IOException {
        MilestoneExport.AllPlayersImportPayload payload =
                MilestoneExport.parseAllPlayersExport(job.file);
        job.plan.set(MilestoneExport.buildMergePlan(payload));
    }

    /** Releases a job whose result could not be handed to the server thread, with feedback. */
    private static void releaseRejected(Job job, Throwable cause) {
        if (job.failure.get() == null) {
            job.failure.set(cause);
        }
        if (!job.applied.compareAndSet(false, true)) {
            return;
        }
        ACTIVE.compareAndSet(job, null);
        MilestoneIo.endImport();
        sendFailure(job, job.failure.get());
    }

    @FunctionalInterface
    interface ParseAction {
        void parse(Job job) throws Throwable;
    }

    /** Test hook: runs the parse/schedule path with an injected parser and scheduler. */
    static boolean submitForTest(Job job, ParseAction parseAction, Consumer<Runnable> scheduler) {
        return submit(job, parseAction, scheduler);
    }

    static Job newJobForTest(Path file, String fileName) {
        return new Job(null, file, fileName, null);
    }

    static Job activeForTest() {
        return ACTIVE.get();
    }

    /** Applies a fully parsed importall result; safe to call twice. */
    private static void apply(Job job) {
        if (!job.applied.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!job.parseDone.get()) {
                return;
            }
            Throwable failure = job.failure.get();
            if (failure != null) {
                sendFailure(job, failure);
                return;
            }
            MilestoneExport.MergePlan plan = job.plan.get();
            if (plan == null) {
                sendFailure(job, new IllegalStateException("missing importall merge plan"));
                return;
            }
            MilestoneData data = MilestoneData.get(job.server);
            MilestoneExport.ImportAllMergeStats stats = MilestoneExport.applyMergePlan(data, plan);
            ScoreboardSync.refreshAll(job.server);
            MilestoneService.syncAllOnlineGuiPlayers(job.server, null);
            job.source.sendSuccess(() -> ServerLang.tr(job.source,
                    "message.itemmilestonelog.importall_done",
                    job.fileName,
                    stats.importedPlayers(),
                    stats.addedTargets(),
                    stats.updatedRecords(),
                    stats.updatedWorldFirsts(),
                    stats.skippedEntries()), false);
        } catch (RuntimeException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Applying importall {} failed", job.fileName, e);
            sendFailure(job, e);
        } finally {
            ACTIVE.compareAndSet(job, null);
            MilestoneIo.endImport();
        }
    }

    /**
     * Stop-time hook (F-140-4): applies a result that finished parsing during the drain but whose
     * {@code server.execute} task has not run yet. Must run on the server thread before the final
     * backup snapshot.
     */
    public static void applyPendingOnStop(@Nullable MinecraftServer server) {
        Job job = ACTIVE.get();
        if (job == null || (server != null && job.server != server)) {
            return;
        }
        if (job.parseDone.get()) {
            apply(job);
        }
    }

    /** Abandons an importall that did not finish within the stop-time drain. */
    public static void cancelPending(String reason) {
        Job job = ACTIVE.getAndSet(null);
        if (job == null) {
            return;
        }
        if (job.applied.compareAndSet(false, true)) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] importall {} abandoned: {}", job.fileName, reason);
            MilestoneIo.endImport();
        }
    }

    /** Clears all importall state on {@code ServerStopped}. */
    public static void resetState() {
        Job job = ACTIVE.getAndSet(null);
        if (job != null && job.applied.compareAndSet(false, true)) {
            MilestoneIo.endImport();
        }
    }

    private static int runSynchronously(CommandSourceStack source, Path file, String fileName) {
        try {
            MilestoneExport.AllPlayersImportPayload payload = MilestoneExport.parseAllPlayersExport(file);
            MilestoneExport.MergePlan plan = MilestoneExport.buildMergePlan(payload);
            MinecraftServer server = source.getServer();
            MilestoneData data = MilestoneData.get(server);
            MilestoneExport.ImportAllMergeStats stats = MilestoneExport.applyMergePlan(data, plan);
            ScoreboardSync.refreshAll(server);
            MilestoneService.syncAllOnlineGuiPlayers(server, null);
            source.sendSuccess(() -> ServerLang.tr(source,
                    "message.itemmilestonelog.importall_done",
                    fileName,
                    stats.importedPlayers(),
                    stats.addedTargets(),
                    stats.updatedRecords(),
                    stats.updatedWorldFirsts(),
                    stats.skippedEntries()), false);
            return 1;
        } catch (IllegalArgumentException e) {
            return fail(source, ServerLang.tr(source, "message.itemmilestonelog.import_invalid_format", fileName));
        } catch (IOException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Import-all of {} failed", fileName, e);
            return fail(source, ServerLang.tr(source, "message.itemmilestonelog.import_failed", fileName));
        } catch (RuntimeException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Import-all of {} failed", fileName, e);
            return fail(source, failureMessage(source, fileName, e));
        }
    }

    private static void sendFailure(Job job, Throwable failure) {
        if (failure == null) {
            return;
        }
        try {
            job.source.sendFailure(failureMessage(job.source, job.fileName, failure));
        } catch (RuntimeException e) {
            ItemMilestoneLog.LOGGER.warn(
                    "[ItemMilestoneLog] Could not send importall failure feedback: {}", e.getMessage());
        }
    }

    private static Component failureMessage(CommandSourceStack source, String fileName, Throwable failure) {
        String reasonKey;
        if (failure instanceof IllegalArgumentException) {
            reasonKey = "message.itemmilestonelog.importall_reason_invalid_format";
        } else if (failure instanceof IOException) {
            reasonKey = "message.itemmilestonelog.importall_reason_io";
        } else {
            reasonKey = "message.itemmilestonelog.importall_reason_error";
        }
        return ServerLang.tr(source, "message.itemmilestonelog.importall_failed",
                fileName, ServerLang.tr(source, reasonKey));
    }

    private static int fail(CommandSourceStack source, Component message) {
        source.sendFailure(message);
        return 0;
    }

    static final class Job {
        final MinecraftServer server;
        final Path file;
        final String fileName;
        final CommandSourceStack source;
        final AtomicBoolean parseDone = new AtomicBoolean();
        final AtomicBoolean applied = new AtomicBoolean();
        final AtomicReference<MilestoneExport.MergePlan> plan = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        Job(MinecraftServer server, Path file, String fileName, CommandSourceStack source) {
            this.server = server;
            this.file = file;
            this.fileName = fileName;
            this.source = source;
        }
    }
}
