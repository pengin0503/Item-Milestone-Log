package com.pengin0503.itemmilestonelog;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
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
                // server.execute() does not reject during shutdown: BlockableEventLoop runs the
                // task inline when scheduling is disabled. This catch is only a defensive guard
                // for unexpected Throwables (e.g. OutOfMemoryError); it must release the in-flight
                // state without touching the CommandSourceStack from this I/O thread (#107/#133).
                releaseFailedHandOff(job, t);
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

    /**
     * Releases a job whose application could not be handed to the server thread. Feedback is not
     * sent here: this runs on the I/O thread, and a CommandSourceStack (and the player behind it)
     * must only be touched on the server thread (#107).
     */
    private static void releaseFailedHandOff(Job job, Throwable cause) {
        if (job.failure.get() == null) {
            job.failure.set(cause);
        }
        if (!job.applied.compareAndSet(false, true)) {
            return;
        }
        ACTIVE.compareAndSet(job, null);
        MilestoneIo.endImport();
        ItemMilestoneLog.LOGGER.warn(
                "[ItemMilestoneLog] importall {} could not be handed to the server thread: {}",
                job.fileName, cause.getMessage());
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

    static Job newJobForTest(Path file, String fileName, @Nullable CommandSourceStack source) {
        return new Job(null, file, fileName, source);
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
            ServerPlayer onlineExecutor = resolveOnlineExecutor(job);
            Component done = onlineExecutor != null
                    ? ServerLang.tr(onlineExecutor,
                            "message.itemmilestonelog.importall_done",
                            job.fileName,
                            stats.importedPlayers(),
                            stats.addedTargets(),
                            stats.updatedRecords(),
                            stats.updatedWorldFirsts(),
                            stats.skippedEntries())
                    : ServerLang.tr(job.source,
                            "message.itemmilestonelog.importall_done",
                            job.fileName,
                            stats.importedPlayers(),
                            stats.addedTargets(),
                            stats.updatedRecords(),
                            stats.updatedWorldFirsts(),
                            stats.skippedEntries());
            sendSuccess(job, onlineExecutor, done);
        } catch (RuntimeException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Applying importall {} failed", job.fileName, e);
            sendFailure(job, e);
        } finally {
            ACTIVE.compareAndSet(job, null);
            MilestoneIo.endImport();
        }
    }

    /** Re-resolves a player executor by UUID so a relogin during a long parse still gets feedback. */
    @Nullable
    private static ServerPlayer resolveOnlineExecutor(Job job) {
        if (job.executorUuid == null || job.server == null) {
            return null;
        }
        return job.server.getPlayerList().getPlayer(job.executorUuid);
    }

    private static void sendSuccess(Job job, @Nullable ServerPlayer onlineExecutor, Component message) {
        if (job.executorUuid != null) {
            if (onlineExecutor == null) {
                ItemMilestoneLog.LOGGER.info(
                        "[ItemMilestoneLog] importall {} finished, but executor {} is no longer online",
                        job.fileName, job.executorUuid);
                return;
            }
            if (job.source == null || onlineExecutor != job.source.getPlayer()) {
                onlineExecutor.sendSystemMessage(message);
                return;
            }
        }
        if (job.source != null) {
            job.source.sendSuccess(() -> message, false);
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
            ServerPlayer onlineExecutor = resolveOnlineExecutor(job);
            if (job.executorUuid != null && onlineExecutor == null) {
                ItemMilestoneLog.LOGGER.warn(
                        "[ItemMilestoneLog] importall {} failed, but executor {} is no longer online: {}",
                        job.fileName, job.executorUuid, failure.getMessage());
                return;
            }
            Component message = failureMessage(onlineExecutor, job.source, job.fileName, failure);
            if (onlineExecutor != null && (job.source == null || onlineExecutor != job.source.getPlayer())) {
                onlineExecutor.sendSystemMessage(message);
            } else if (job.source != null) {
                job.source.sendFailure(message);
            }
        } catch (RuntimeException e) {
            ItemMilestoneLog.LOGGER.warn(
                    "[ItemMilestoneLog] Could not send importall failure feedback: {}", e.getMessage());
        }
    }

    private static Component failureMessage(
            @Nullable ServerPlayer player,
            @Nullable CommandSourceStack source,
            String fileName,
            Throwable failure) {
        String reasonKey;
        if (failure instanceof IllegalArgumentException) {
            reasonKey = "message.itemmilestonelog.importall_reason_invalid_format";
        } else if (failure instanceof IOException) {
            reasonKey = "message.itemmilestonelog.importall_reason_io";
        } else {
            reasonKey = "message.itemmilestonelog.importall_reason_error";
        }
        if (player != null) {
            return ServerLang.tr(player, "message.itemmilestonelog.importall_failed",
                    fileName, ServerLang.tr(player, reasonKey));
        }
        return ServerLang.tr(source, "message.itemmilestonelog.importall_failed",
                fileName, ServerLang.tr(source, reasonKey));
    }

    private static Component failureMessage(CommandSourceStack source, String fileName, Throwable failure) {
        return failureMessage(null, source, fileName, failure);
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
        @Nullable
        final UUID executorUuid;
        final AtomicBoolean parseDone = new AtomicBoolean();
        final AtomicBoolean applied = new AtomicBoolean();
        final AtomicReference<MilestoneExport.MergePlan> plan = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        Job(MinecraftServer server, Path file, String fileName, CommandSourceStack source) {
            this(server, file, fileName, source,
                    source != null && source.getPlayer() != null ? source.getPlayer().getUUID() : null);
        }

        Job(MinecraftServer server, Path file, String fileName, CommandSourceStack source,
                @Nullable UUID executorUuid) {
            this.server = server;
            this.file = file;
            this.fileName = fileName;
            this.source = source;
            this.executorUuid = executorUuid;
        }
    }
}
