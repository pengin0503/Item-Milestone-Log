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

    /** Server-thread only. */
    private static final MilestoneCommandLogic.CommandQueue<QueuedCommand> QUEUE =
            new MilestoneCommandLogic.CommandQueue<QueuedCommand>(
                    Comparator.comparing(QueuedCommand::key),
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

    /** Drains up to {@value MilestoneCommandLogic#MAX_COMMANDS_PER_TICK} queued entries. */
    static List<QueuedCommand> drainForTick() {
        return QUEUE.drainForTick();
    }

    static int pendingSize() {
        return QUEUE.pendingSize();
    }

    /** Discards all queued commands without executing them (called on server stop / test cleanup). */
    public static void clearPending() {
        QUEUE.clear();
    }

    /**
     * Drains up to {@value MilestoneCommandLogic#MAX_COMMANDS_PER_TICK} queued milestone commands
     * and executes them against {@code server}. Called from {@code Tracker.onServerTick} after
     * F27 notification flushing.
     */
    static void drainAndRun(@Nullable MinecraftServer server) {
        if (server == null || QUEUE.isEmpty()) {
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
