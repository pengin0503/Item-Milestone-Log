package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.TargetKey;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * F7: queues server commands on milestone confirmation ({@code Tracker.record}) and drains them
 * on {@code ServerTickEvent.Post} (at most {@value MilestoneCommandLogic#MAX_COMMANDS_PER_TICK}
 * per tick, remainder next tick).
 *
 * <p>Commands run with the server's console command source (permission level 4 equivalent) and
 * suppressed output so OP chat is not spammed with {@code [Server: ...]} feedback. Failures
 * (syntax errors, runtime exceptions, non-success callbacks, result 0) are logged as warnings
 * and never affect milestone recording.
 */
public final class MilestoneCommandRunner {
    record QueuedCommand(
            UUID playerId,
            String playerName,
            TargetKey key,
            int playTicks,
            boolean worldFirst) {
    }

    /** F-150-45 (N13): one completed preset produces one queued hook command. */
    record QueuedPresetCommand(UUID playerId, String playerName, String presetId, boolean builtin) {
    }

    /** F-150-46 (N109): one world-first record produces one queued hook command. */
    record QueuedWorldFirstCommand(UUID playerId, String playerName, TargetKey key, int playTicks) {
    }

    /** Server-thread only. */
    private static final MilestoneCommandLogic.CommandQueue<QueuedCommand> QUEUE =
            new MilestoneCommandLogic.CommandQueue<QueuedCommand>(
                    Comparator.comparing(QueuedCommand::key),
                    MilestoneCommandLogic.MAX_COMMANDS_PER_TICK);

    /** F-150-45: separate queue; F7's drain order and stop flush are untouched. */
    private static final MilestoneCommandLogic.CommandQueue<QueuedPresetCommand> PRESET_QUEUE =
            new MilestoneCommandLogic.CommandQueue<QueuedPresetCommand>(
                    Comparator.comparing(QueuedPresetCommand::presetId),
                    MilestoneCommandLogic.MAX_COMMANDS_PER_TICK);

    /** F-150-46: third separate queue; drained after F7 and N13. */
    private static final MilestoneCommandLogic.CommandQueue<QueuedWorldFirstCommand> WORLD_FIRST_QUEUE =
            new MilestoneCommandLogic.CommandQueue<QueuedWorldFirstCommand>(
                    Comparator.comparing(QueuedWorldFirstCommand::key),
                    MilestoneCommandLogic.MAX_COMMANDS_PER_TICK);

    private MilestoneCommandRunner() {
    }

    /**
     * Enqueues one command execution for a newly confirmed milestone record when
     * {@link Config#MILESTONE_COMMAND} is configured. Never throws into the recording path.
     */
    static void enqueue(
            UUID playerId,
            @Nullable String playerName,
            TargetKey key,
            int playTicks,
            boolean worldFirst) {
        try {
            if (Config.milestoneCommand().isBlank()) {
                return;
            }
            enqueueUnchecked(playerId, playerName, key, playTicks, worldFirst);
        } catch (Throwable t) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Failed to enqueue milestone command for {}",
                    key != null ? key.formatDisplayId() : "null", t);
        }
    }

    /** Enqueues without reading {@link Config} (for unit tests). */
    static void enqueueUnchecked(
            UUID playerId,
            @Nullable String playerName,
            TargetKey key,
            int playTicks,
            boolean worldFirst) {
        QUEUE.enqueue(playerId, new QueuedCommand(
                playerId,
                playerName != null ? playerName : "",
                key,
                playTicks,
                worldFirst));
    }

    /**
     * F-150-45 (N13): enqueues one hook command for a completed preset. The F7 config and queue
     * are never touched; blank presetCompleteCommand is a no-op. Never throws into the record path.
     */
    static void enqueuePreset(UUID playerId, @Nullable String playerName, String presetId, boolean builtin) {
        try {
            if (Config.presetCompleteCommand().isBlank() || !Presets.isValidId(presetId)) {
                return;
            }
            enqueuePresetUnchecked(playerId, playerName, presetId, builtin);
        } catch (Throwable t) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Failed to enqueue preset complete command for {}",
                    presetId, t);
        }
    }

    /** Enqueues without reading {@link Config} (for unit tests). */
    static void enqueuePresetUnchecked(
            UUID playerId, @Nullable String playerName, String presetId, boolean builtin) {
        PRESET_QUEUE.enqueue(playerId, new QueuedPresetCommand(
                playerId, playerName != null ? playerName : "", presetId, builtin));
    }

    /**
     * F-150-46 (N109): enqueues one hook command only for a confirmed world-first record.
     * Blank worldFirstCommand is a no-op; grant / already-recorded paths never reach this method.
     */
    static void enqueueWorldFirst(UUID playerId, @Nullable String playerName, TargetKey key, int playTicks) {
        try {
            if (Config.worldFirstCommand().isBlank()) {
                return;
            }
            enqueueWorldFirstUnchecked(playerId, playerName, key, playTicks);
        } catch (Throwable t) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Failed to enqueue world first command for {}",
                    key != null ? key.formatDisplayId() : "null", t);
        }
    }

    /** Enqueues without reading {@link Config} (for unit tests). */
    static void enqueueWorldFirstUnchecked(
            UUID playerId, @Nullable String playerName, TargetKey key, int playTicks) {
        WORLD_FIRST_QUEUE.enqueue(playerId, new QueuedWorldFirstCommand(
                playerId, playerName != null ? playerName : "", key, playTicks));
    }

    /** Drains up to {@value MilestoneCommandLogic#MAX_COMMANDS_PER_TICK} queued entries. */
    static List<QueuedCommand> drainForTick() {
        return QUEUE.drainForTick();
    }

    /** Test hook: promotes and drains every queued entry without the per-tick cap. */
    static List<QueuedCommand> drainAllForStop() {
        return QUEUE.drainAll();
    }

    static int pendingSize() {
        return QUEUE.pendingSize();
    }

    /** Test hook for the N13 queue only. */
    static int pendingPresetSize() {
        return PRESET_QUEUE.pendingSize();
    }

    /** Test hook for the N109 queue only. */
    static int pendingWorldFirstSize() {
        return WORLD_FIRST_QUEUE.pendingSize();
    }

    /** Test hook: promotes and drains up to 20 preset hook commands. */
    static List<QueuedPresetCommand> drainPresetForTick() {
        return PRESET_QUEUE.drainForTick();
    }

    /** Test hook: promotes and drains up to 20 world-first hook commands. */
    static List<QueuedWorldFirstCommand> drainWorldFirstForTick() {
        return WORLD_FIRST_QUEUE.drainForTick();
    }

    /** Test hook: drains the entire N13 queue without the per-tick cap. */
    static List<QueuedPresetCommand> drainPresetAllForStop() {
        return PRESET_QUEUE.drainAll();
    }

    /** Test hook: drains the entire N109 queue without the per-tick cap. */
    static List<QueuedWorldFirstCommand> drainWorldFirstAllForStop() {
        return WORLD_FIRST_QUEUE.drainAll();
    }

    /** Discards all queued commands without executing them (called on server stop / test cleanup). */
    public static void clearPending() {
        QUEUE.clear();
        PRESET_QUEUE.clear();
        WORLD_FIRST_QUEUE.clear();
    }

    /**
     * Drains up to {@value MilestoneCommandLogic#MAX_COMMANDS_PER_TICK} queued milestone commands
     * and executes them against {@code server}. Called from {@code Tracker.onServerTick} after
     * F27 notification flushing.
     */
    static void drainAndRun(@Nullable MinecraftServer server) {
        if (server == null) {
            return;
        }
        drainAndRunF7(server);
        drainAndRunPreset(server);
        drainAndRunWorldFirst(server);
    }

    /** F7: unchanged queue/config/cap/execution order from 1.4.0. */
    private static void drainAndRunF7(@Nullable MinecraftServer server) {
        if (QUEUE.isEmpty()) {
            return;
        }
        String template;
        try {
            template = Config.milestoneCommand();
        } catch (Throwable t) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Failed to read milestoneCommand config", t);
            return;
        }
        if (template.isBlank()) {
            QUEUE.clear();
            return;
        }
        List<QueuedCommand> batch = QUEUE.drainForTick();
        executeBatch(batch, template, cmd -> runServerCommand(server, cmd));
    }

    /** F-150-45: up to 20 preset hook commands per tick from the separate queue. */
    private static void drainAndRunPreset(@Nullable MinecraftServer server) {
        if (PRESET_QUEUE.isEmpty()) {
            return;
        }
        String template;
        try {
            template = Config.presetCompleteCommand();
        } catch (Throwable t) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Failed to read presetCompleteCommand config", t);
            return;
        }
        if (template.isBlank()) {
            PRESET_QUEUE.clear();
            return;
        }
        executePresetBatch(PRESET_QUEUE.drainForTick(), template, cmd -> runServerCommand(server, cmd));
    }

    /** F-150-46: up to 20 world-first hook commands per tick from the third queue. */
    private static void drainAndRunWorldFirst(@Nullable MinecraftServer server) {
        if (WORLD_FIRST_QUEUE.isEmpty()) {
            return;
        }
        String template;
        try {
            template = Config.worldFirstCommand();
        } catch (Throwable t) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Failed to read worldFirstCommand config", t);
            return;
        }
        if (template.isBlank()) {
            WORLD_FIRST_QUEUE.clear();
            return;
        }
        executeWorldFirstBatch(WORLD_FIRST_QUEUE.drainForTick(), template, cmd -> runServerCommand(server, cmd));
    }

    /**
     * Server-stop flush: executes every queued milestone command, ignoring the per-tick cap.
     * {@code ServerStoppingEvent} still runs while players are connected, so reward commands
     * queued just before shutdown can be delivered before the server shuts them off (#135).
     */
    public static void runAllOnStop(@Nullable MinecraftServer server) {
        if (server == null) {
            return;
        }
        runAllF7OnStop(server);
        // F-150-45 / F-150-46: the existing F7 flush stays first; the two hook queues flush next,
        // independently of whether F7 had anything queued or its config was readable.
        runAllPresetOnStop(server);
        runAllWorldFirstOnStop(server);
    }

    /** F7 stop flush: unchanged from 1.4.0. */
    private static void runAllF7OnStop(@Nullable MinecraftServer server) {
        if (QUEUE.isEmpty()) {
            return;
        }
        String template;
        try {
            template = Config.milestoneCommand();
        } catch (Throwable t) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Failed to read milestoneCommand config", t);
            return;
        }
        if (template.isBlank()) {
            QUEUE.clear();
            return;
        }
        // Commands normally do not enqueue further reward commands, but loop defensively so
        // anything queued by a command handler is also delivered. Bound it so a pathological
        // feedback loop cannot extend the stop indefinitely.
        for (int batch = 0; batch < 1_000 && !QUEUE.isEmpty(); batch++) {
            executeBatch(QUEUE.drainAll(), template, cmd -> runServerCommand(server, cmd));
        }
        if (!QUEUE.isEmpty()) {
            ItemMilestoneLog.LOGGER.warn(
                    "[ItemMilestoneLog] Dropping {} milestone command(s) that were still queued after the stop flush",
                    QUEUE.pendingSize());
            QUEUE.clear();
        }
    }

    private static void runAllPresetOnStop(@Nullable MinecraftServer server) {
        if (PRESET_QUEUE.isEmpty()) {
            return;
        }
        String template;
        try {
            template = Config.presetCompleteCommand();
        } catch (Throwable t) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Failed to read presetCompleteCommand config", t);
            return;
        }
        if (template.isBlank()) {
            PRESET_QUEUE.clear();
            return;
        }
        for (int batch = 0; batch < 1_000 && !PRESET_QUEUE.isEmpty(); batch++) {
            executePresetBatch(PRESET_QUEUE.drainAll(), template, cmd -> runServerCommand(server, cmd));
        }
        if (!PRESET_QUEUE.isEmpty()) {
            ItemMilestoneLog.LOGGER.warn(
                    "[ItemMilestoneLog] Dropping {} preset command(s) still queued after the stop flush",
                    PRESET_QUEUE.pendingSize());
            PRESET_QUEUE.clear();
        }
    }

    private static void runAllWorldFirstOnStop(@Nullable MinecraftServer server) {
        if (WORLD_FIRST_QUEUE.isEmpty()) {
            return;
        }
        String template;
        try {
            template = Config.worldFirstCommand();
        } catch (Throwable t) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Failed to read worldFirstCommand config", t);
            return;
        }
        if (template.isBlank()) {
            WORLD_FIRST_QUEUE.clear();
            return;
        }
        for (int batch = 0; batch < 1_000 && !WORLD_FIRST_QUEUE.isEmpty(); batch++) {
            executeWorldFirstBatch(WORLD_FIRST_QUEUE.drainAll(), template, cmd -> runServerCommand(server, cmd));
        }
        if (!WORLD_FIRST_QUEUE.isEmpty()) {
            ItemMilestoneLog.LOGGER.warn(
                    "[ItemMilestoneLog] Dropping {} world first command(s) still queued after the stop flush",
                    WORLD_FIRST_QUEUE.pendingSize());
            WORLD_FIRST_QUEUE.clear();
        }
    }

    /** Renders and dispatches each entry in {@code batch}, isolating errors per entry. */
    static void executeBatch(List<QueuedCommand> batch, String template, Consumer<String> executor) {
        for (QueuedCommand entry : batch) {
            try {
                MilestoneCommandLogic.RenderResult rendered = MilestoneCommandLogic.render(
                        template,
                        entry.playerName(),
                        entry.playerId(),
                        entry.key().itemId(),
                        entry.key().count(),
                        Playtime.format(entry.playTicks()),
                        entry.worldFirst());
                if (rendered.status() == MilestoneCommandLogic.RenderStatus.INVALID_PLAYER_NAME) {
                    ItemMilestoneLog.LOGGER.warn(
                            "[ItemMilestoneLog] Skipping milestone command for player '{}' ({}) on {}: player name does not match {}",
                            entry.playerName(),
                            entry.playerId(),
                            entry.key().formatDisplayId(),
                            MilestoneCommandLogic.VALID_PLAYER_NAME.pattern());
                    continue;
                }
                if (!rendered.isReady()) {
                    continue;
                }
                executor.accept(rendered.command());
            } catch (Throwable t) {
                ItemMilestoneLog.LOGGER.warn(
                        "[ItemMilestoneLog] Unexpected error executing milestone command for {} ({})",
                        entry.key().formatDisplayId(), entry.playerId(), t);
            }
        }
    }

    /** Renders and dispatches a batch of N13 preset hook commands, isolating errors per entry. */
    static void executePresetBatch(
            List<QueuedPresetCommand> batch, String template, Consumer<String> executor) {
        for (QueuedPresetCommand entry : batch) {
            try {
                // Presets.isValidId is the version-layer trust boundary (reserved command literals).
                if (!Presets.isValidId(entry.presetId())) {
                    ItemMilestoneLog.LOGGER.warn(
                            "[ItemMilestoneLog] Skipping preset command with invalid preset id '{}'",
                            entry.presetId());
                    continue;
                }
                MilestoneCommandLogic.RenderResult rendered = MilestoneCommandLogic.renderForPreset(
                        template, entry.playerName(), entry.playerId(), entry.presetId(), entry.builtin());
                if (!dispatchHookRender(rendered, entry.playerName(), "preset " + entry.presetId(), executor)) {
                    continue;
                }
            } catch (Throwable t) {
                ItemMilestoneLog.LOGGER.warn(
                        "[ItemMilestoneLog] Unexpected error executing preset command for '{}'",
                        entry.presetId(), t);
            }
        }
    }

    /** Renders and dispatches a batch of N109 world-first hook commands, isolating errors per entry. */
    static void executeWorldFirstBatch(
            List<QueuedWorldFirstCommand> batch, String template, Consumer<String> executor) {
        for (QueuedWorldFirstCommand entry : batch) {
            try {
                MilestoneCommandLogic.RenderResult rendered = MilestoneCommandLogic.render(
                        template,
                        entry.playerName(),
                        entry.playerId(),
                        entry.key().itemId(),
                        entry.key().count(),
                        Playtime.format(entry.playTicks()),
                        true);
                dispatchHookRender(rendered, entry.playerName(), entry.key().formatDisplayId(), executor);
            } catch (Throwable t) {
                ItemMilestoneLog.LOGGER.warn(
                        "[ItemMilestoneLog] Unexpected error executing world first command for {} ({})",
                        entry.key().formatDisplayId(), entry.playerId(), t);
            }
        }
    }

    private static boolean dispatchHookRender(
            MilestoneCommandLogic.RenderResult rendered,
            String playerName,
            String context,
            Consumer<String> executor) {
        if (rendered.status() == MilestoneCommandLogic.RenderStatus.INVALID_PLAYER_NAME) {
            ItemMilestoneLog.LOGGER.warn(
                    "[ItemMilestoneLog] Skipping hook command for player '{}' on {}: player name does not match {}",
                    playerName, context, MilestoneCommandLogic.VALID_PLAYER_NAME.pattern());
            return false;
        }
        if (rendered.status() == MilestoneCommandLogic.RenderStatus.INVALID_PRESET_ID) {
            ItemMilestoneLog.LOGGER.warn(
                    "[ItemMilestoneLog] Skipping hook command on {}: invalid preset id", context);
            return false;
        }
        if (!rendered.isReady()) {
            return false;
        }
        executor.accept(rendered.command());
        return true;
    }

    private static void runServerCommand(MinecraftServer server, String command) {
        MilestoneCommandLogic.CallbackTracker tracker = new MilestoneCommandLogic.CallbackTracker();
        try {
            CommandSourceStack source = server.createCommandSourceStack()
                    .withSuppressedOutput()
                    .withCallback(tracker::onResult);
            server.getCommands().performPrefixedCommand(source, command);
        } catch (Throwable t) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Milestone command threw an exception: '{}'", command, t);
            return;
        }
        if (!tracker.succeeded()) {
            if (tracker.invoked()) {
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Milestone command failed (result={}): '{}'",
                        tracker.lastResult(), command);
            } else {
                ItemMilestoneLog.LOGGER.warn(
                        "[ItemMilestoneLog] Milestone command failed (syntax error or did not execute): '{}'",
                        command);
            }
        }
    }
}
