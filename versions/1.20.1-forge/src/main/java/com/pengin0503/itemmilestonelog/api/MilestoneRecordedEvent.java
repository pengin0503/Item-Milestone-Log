package com.pengin0503.itemmilestonelog.api;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.Event;

/**
 * Posted on {@link net.minecraftforge.common.MinecraftForge#EVENT_BUS} on the server
 * whenever a player's milestone record is confirmed during normal gameplay, via
 * {@link ItemMilestoneLogApi#recordNow}, or via an administrator {@code grant} command
 * (distinguished by {@link #getCause()}).
 *
 * <p>This event is not cancellable and is not fired for imports or resets. When a single
 * milestone record also completes one or more presets, this event is posted before any
 * resulting {@link PresetCompletedEvent}.
 *
 * <p>Example KubeJS server script granting a reward on world-first gameplay milestones:
 * <pre>{@code
 * ForgeEvents.onEvent('com.pengin0503.itemmilestonelog.api.MilestoneRecordedEvent', event => {
 *   if (event.isWorldFirst() && event.getCause().name() == 'GAMEPLAY') {
 *     event.getPlayer().give('minecraft:emerald')
 *   }
 * })
 * }</pre>
 */
public class MilestoneRecordedEvent extends Event {
    private final ServerPlayer player;
    private final String itemId;
    private final int count;
    private final int playTicks;
    private final long day;
    private final long epochMillis;
    private final boolean worldFirst;
    private final RecordCause cause;

    /**
     * Creates an event for a quantity-1 milestone record with {@code worldFirst = false}
     * and {@code cause = RecordCause.GAMEPLAY}.
     * Retained for API compatibility with earlier versions.
     */
    public MilestoneRecordedEvent(ServerPlayer player, String itemId, int playTicks, long day, long epochMillis) {
        this(player, itemId, TargetKey.MIN_COUNT, playTicks, day, epochMillis, false, RecordCause.GAMEPLAY);
    }

    /**
     * Creates an event for a milestone record with {@code worldFirst = false}
     * and {@code cause = RecordCause.GAMEPLAY}.
     * Retained for API compatibility with earlier versions.
     */
    public MilestoneRecordedEvent(ServerPlayer player, String itemId, int count, int playTicks, long day, long epochMillis) {
        this(player, itemId, count, playTicks, day, epochMillis, false, RecordCause.GAMEPLAY);
    }

    /**
     * Creates an event for a confirmed milestone record with {@code cause = RecordCause.GAMEPLAY}.
     *
     * @param player      the player whose milestone was recorded
     * @param itemId      normalized target item ID, or {@code '#'}-prefixed tag ID (e.g. {@code "#c:ingots/iron"})
     * @param count       target quantity ({@code 1..1,000,000})
     * @param playTicks   player's cumulative playtime ticks at recording
     * @param day         in-game overworld day index, or {@link MilestoneRecord#UNKNOWN_DAY} if unknown
     * @param epochMillis wall-clock timestamp in milliseconds since Unix epoch, or {@link MilestoneRecord#UNKNOWN_EPOCH} if unknown
     * @param worldFirst  {@code true} if this record established the server's world-first for the target
     */
    public MilestoneRecordedEvent(
            ServerPlayer player,
            String itemId,
            int count,
            int playTicks,
            long day,
            long epochMillis,
            boolean worldFirst) {
        this(player, itemId, count, playTicks, day, epochMillis, worldFirst, RecordCause.GAMEPLAY);
    }

    /**
     * Creates an event for a confirmed milestone record with an explicit {@link RecordCause}.
     *
     * @param player      the player whose milestone was recorded
     * @param itemId      normalized target item ID, or {@code '#'}-prefixed tag ID (e.g. {@code "#c:ingots/iron"})
     * @param count       target quantity ({@code 1..1,000,000})
     * @param playTicks   player's cumulative playtime ticks at recording
     * @param day         in-game overworld day index, or {@link MilestoneRecord#UNKNOWN_DAY} if unknown
     * @param epochMillis wall-clock timestamp in milliseconds since Unix epoch, or {@link MilestoneRecord#UNKNOWN_EPOCH} if unknown
     * @param worldFirst  {@code true} if this record established the server's world-first for the target
     * @param cause       how the milestone record was confirmed (defaults to {@link RecordCause#GAMEPLAY} if {@code null})
     */
    public MilestoneRecordedEvent(
            ServerPlayer player,
            String itemId,
            int count,
            int playTicks,
            long day,
            long epochMillis,
            boolean worldFirst,
            RecordCause cause) {
        this.player = player;
        this.itemId = itemId;
        this.count = count;
        this.playTicks = playTicks;
        this.day = day;
        this.epochMillis = epochMillis;
        this.worldFirst = worldFirst;
        this.cause = cause != null ? cause : RecordCause.GAMEPLAY;
    }

    /** Returns the player whose milestone was recorded. */
    public ServerPlayer getPlayer() {
        return player;
    }

    /**
     * Returns the normalized target ID.
     *
     * <p>This value may start with {@code '#'} for item tag targets (e.g. {@code "#c:ingots/iron"}).
     * Callers should check {@link TargetKey#isTag()} (via {@link #getTargetKey()}) or
     * {@link TargetKey#isTagId(String)} before treating the returned string as a Minecraft
     * {@code Identifier} / {@code ResourceLocation}.
     */
    public String getItemId() {
        return itemId;
    }

    /** Returns the required target quantity ({@code 1..1,000,000}). */
    public int getCount() {
        return count;
    }

    /** Returns the {@link TargetKey} combining {@link #getItemId()} and {@link #getCount()}. */
    public TargetKey getTargetKey() {
        return new TargetKey(itemId, count);
    }

    /** Returns the player's cumulative playtime ticks at the moment of recording. */
    public int getPlayTicks() {
        return playTicks;
    }

    /** Returns the in-game overworld day index at recording, or {@link MilestoneRecord#UNKNOWN_DAY} if unknown. */
    public long getDay() {
        return day;
    }

    /** Returns the wall-clock timestamp in milliseconds since Unix epoch, or {@link MilestoneRecord#UNKNOWN_EPOCH} if unknown. */
    public long getEpochMillis() {
        return epochMillis;
    }

    /**
     * Returns {@code true} if this record established the world-first record for its
     * {@linkplain #getTargetKey() target}. Always {@code false} when {@code worldFirstEnabled}
     * is disabled in the server configuration or when a world-first already existed.
     */
    public boolean isWorldFirst() {
        return worldFirst;
    }

    /**
     * Returns how this milestone record was confirmed ({@link RecordCause#GAMEPLAY},
     * {@link RecordCause#API}, or {@link RecordCause#ADMIN_GRANT}).
     */
    public RecordCause getCause() {
        return cause;
    }
}
