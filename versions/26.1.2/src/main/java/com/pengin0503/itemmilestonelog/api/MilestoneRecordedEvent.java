package com.pengin0503.itemmilestonelog.api;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;

/**
 * Posted on {@link net.neoforged.neoforge.common.NeoForge#EVENT_BUS} on the server
 * whenever a player's milestone record is confirmed during normal gameplay.
 *
 * <p>This event is not cancellable and is not fired for imports or resets.
 */
public class MilestoneRecordedEvent extends Event {
    private final ServerPlayer player;
    private final String itemId;
    private final int count;
    private final int playTicks;
    private final long day;
    private final long epochMillis;

    public MilestoneRecordedEvent(ServerPlayer player, String itemId, int playTicks, long day, long epochMillis) {
        this(player, itemId, TargetKey.MIN_COUNT, playTicks, day, epochMillis);
    }

    public MilestoneRecordedEvent(ServerPlayer player, String itemId, int count, int playTicks, long day, long epochMillis) {
        this.player = player;
        this.itemId = itemId;
        this.count = count;
        this.playTicks = playTicks;
        this.day = day;
        this.epochMillis = epochMillis;
    }

    public ServerPlayer getPlayer() {
        return player;
    }

    public String getItemId() {
        return itemId;
    }

    public int getCount() {
        return count;
    }

    public TargetKey getTargetKey() {
        return new TargetKey(itemId, count);
    }

    public int getPlayTicks() {
        return playTicks;
    }

    public long getDay() {
        return day;
    }

    public long getEpochMillis() {
        return epochMillis;
    }
}
