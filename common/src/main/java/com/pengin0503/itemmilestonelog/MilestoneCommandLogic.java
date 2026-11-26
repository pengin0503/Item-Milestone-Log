package com.pengin0503.itemmilestonelog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Pure-Java logic for F7 (running a server command on milestone confirmation), shared across
 * all Minecraft versions via {@code common/src/main/java}: player-name validation, placeholder
 * substitution, per-tick queue draining, and command-callback outcome tracking.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class MilestoneCommandLogic {
    /** Maximum number of queued milestone commands executed per server tick (F7). */
    public static final int MAX_COMMANDS_PER_TICK = 20;

    /**
     * Player names allowed for {@code %player%} substitution: ASCII alphanumerics plus
     * {@code _ . * + -}, length 1..20 (supports Geyser/Floodgate prefixes {@code .}/{@code *}
     * and names over 16 chars while rejecting {@code @} selectors and whitespace).
     */
    public static final Pattern VALID_PLAYER_NAME = Pattern.compile("^[A-Za-z0-9_.*+-]{1,20}$");

    public static final String PLACEHOLDER_PLAYER = "%player%";
    public static final String PLACEHOLDER_UUID = "%uuid%";
    public static final String PLACEHOLDER_ITEM = "%item%";
    public static final String PLACEHOLDER_COUNT = "%count%";
    public static final String PLACEHOLDER_PLAYTIME = "%playtime%";
    public static final String PLACEHOLDER_WORLD_FIRST = "%worldfirst%";

    private MilestoneCommandLogic() {
    }

    /**
     * Returns whether {@code playerName} matches {@link #VALID_PLAYER_NAME} and is safe to
     * substitute for {@code %player%}.
     */
    public static boolean isValidPlayerName(String playerName) {
        return playerName != null && VALID_PLAYER_NAME.matcher(playerName).matches();
    }

    /** Outcome status of {@link #render}. */
    public enum RenderStatus {
        /** Config template is empty/blank (feature disabled). */
        DISABLED,
        /** Template references {@code %player%} but the player name failed validation. */
        INVALID_PLAYER_NAME,
        /** Command was rendered and is ready to execute. */
        READY
    }

    /** Result of rendering a milestone command template. */
    public record RenderResult(RenderStatus status, String command) {
        public static final RenderResult DISABLED = new RenderResult(RenderStatus.DISABLED, null);
        public static final RenderResult INVALID_PLAYER_NAME = new RenderResult(RenderStatus.INVALID_PLAYER_NAME, null);

        public static RenderResult ready(String command) {
            return new RenderResult(RenderStatus.READY, Objects.requireNonNull(command, "command"));
        }

        public boolean isReady() {
            return status == RenderStatus.READY;
        }
    }

    /**
     * Renders {@code template} by replacing milestone placeholders in a single pass.
     *
     * <p>When {@code template} is null or blank, returns {@link RenderResult#DISABLED}. When
     * {@code template} contains {@code %player%} and {@code playerName} does not match
     * {@link #VALID_PLAYER_NAME}, returns {@link RenderResult#INVALID_PLAYER_NAME} so the caller
     * skips execution and logs a warning.
     */
    public static RenderResult render(
            String template,
            String playerName,
            UUID playerUuid,
            String itemId,
            int count,
            String formattedPlaytime,
            boolean worldFirst) {
        if (template == null) {
            return RenderResult.DISABLED;
        }
        String trimmed = template.trim();
        if (trimmed.isEmpty()) {
            return RenderResult.DISABLED;
        }
        if (trimmed.contains(PLACEHOLDER_PLAYER) && !isValidPlayerName(playerName)) {
            return RenderResult.INVALID_PLAYER_NAME;
        }

        String uuidStr = playerUuid != null ? playerUuid.toString() : "";
        String itemStr = itemId != null ? itemId : "";
        String countStr = Integer.toString(count);
        String playtimeStr = formattedPlaytime != null ? formattedPlaytime : "";
        String worldFirstStr = Boolean.toString(worldFirst);

        StringBuilder out = new StringBuilder(trimmed.length() + 32);
        int len = trimmed.length();
        int i = 0;
        while (i < len) {
            if (trimmed.charAt(i) == '%') {
                if (trimmed.startsWith(PLACEHOLDER_PLAYER, i)) {
                    out.append(playerName);
                    i += PLACEHOLDER_PLAYER.length();
                    continue;
                }
                if (trimmed.startsWith(PLACEHOLDER_UUID, i)) {
                    out.append(uuidStr);
                    i += PLACEHOLDER_UUID.length();
                    continue;
                }
                if (trimmed.startsWith(PLACEHOLDER_ITEM, i)) {
                    out.append(itemStr);
                    i += PLACEHOLDER_ITEM.length();
                    continue;
                }
                if (trimmed.startsWith(PLACEHOLDER_COUNT, i)) {
                    out.append(countStr);
                    i += PLACEHOLDER_COUNT.length();
                    continue;
                }
                if (trimmed.startsWith(PLACEHOLDER_PLAYTIME, i)) {
                    out.append(playtimeStr);
                    i += PLACEHOLDER_PLAYTIME.length();
                    continue;
                }
                if (trimmed.startsWith(PLACEHOLDER_WORLD_FIRST, i)) {
                    out.append(worldFirstStr);
                    i += PLACEHOLDER_WORLD_FIRST.length();
                    continue;
                }
            }
            out.append(trimmed.charAt(i));
            i++;
        }
        return RenderResult.ready(out.toString());
    }

    /**
     * Tracks invocations of Minecraft's {@code CommandResultCallback} during a single command
     * dispatch. A command is considered successful if at least one callback reports
     * {@code success == true} and {@code result != 0}; syntax errors (callback never invoked),
     * failures ({@code success == false}), and result {@code 0} all yield {@code succeeded() == false}.
     */
    public static final class CallbackTracker {
        private boolean invoked;
        private boolean succeeded;
        private int lastResult;

        public void onResult(boolean success, int result) {
            this.invoked = true;
            this.lastResult = result;
            if (success && result != 0) {
                this.succeeded = true;
            }
        }

        public boolean invoked() {
            return invoked;
        }

        public boolean succeeded() {
            return succeeded;
        }

        public int lastResult() {
            return lastResult;
        }
    }

    /**
     * Server-thread command queue: groups entries enqueued within the same tick by player
     * (insertion order), sorts each player's simultaneous records by {@code perPlayerOrder}
     * (TargetKey order) when promoted at tick end, preserves FIFO order across ticks, and drains
     * at most {@code maxPerTick} entries per tick.
     */
    public static final class CommandQueue<E> {
        private final Map<UUID, List<E>> currentTickByPlayer = new LinkedHashMap<>();
        private final Deque<E> ready = new ArrayDeque<>();
        private final Comparator<E> perPlayerOrder;
        private final int maxPerTick;

        public CommandQueue(Comparator<E> perPlayerOrder, int maxPerTick) {
            if (maxPerTick < 1) {
                throw new IllegalArgumentException("maxPerTick must be >= 1");
            }
            this.perPlayerOrder = Objects.requireNonNull(perPlayerOrder, "perPlayerOrder");
            this.maxPerTick = maxPerTick;
        }

        public void enqueue(UUID playerId, E entry) {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(entry, "entry");
            currentTickByPlayer.computeIfAbsent(playerId, k -> new ArrayList<>()).add(entry);
        }

        public boolean isEmpty() {
            return currentTickByPlayer.isEmpty() && ready.isEmpty();
        }

        public int pendingSize() {
            int total = ready.size();
            for (List<E> list : currentTickByPlayer.values()) {
                total += list.size();
            }
            return total;
        }

        /**
         * Promotes the current tick's per-player batches into the FIFO ready queue (sorting each
         * player's simultaneous entries by {@code perPlayerOrder}) and drains up to
         * {@code maxPerTick} entries. Entries enqueued while the caller runs the returned batch
         * stay in {@code currentTickByPlayer} until the next tick.
         */
        public List<E> drainForTick() {
            promoteCurrentTick();
            if (ready.isEmpty()) {
                return List.of();
            }
            int count = Math.min(maxPerTick, ready.size());
            List<E> batch = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                batch.add(ready.removeFirst());
            }
            return batch;
        }

        /**
         * Promotes the current tick's entries and drains every ready entry, ignoring the per-tick
         * cap. Used by the server-stop flush so no reward command queued just before shutdown is
         * silently dropped.
         */
        public List<E> drainAll() {
            promoteCurrentTick();
            if (ready.isEmpty()) {
                return List.of();
            }
            List<E> batch = new ArrayList<>(ready);
            ready.clear();
            return batch;
        }

        private void promoteCurrentTick() {
            if (!currentTickByPlayer.isEmpty()) {
                for (List<E> entries : currentTickByPlayer.values()) {
                    if (entries.size() > 1) {
                        entries.sort(perPlayerOrder);
                    }
                    ready.addAll(entries);
                }
                currentTickByPlayer.clear();
            }
        }

        public void clear() {
            currentTickByPlayer.clear();
            ready.clear();
        }
    }
}
