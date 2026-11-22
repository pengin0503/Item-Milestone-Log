package com.pengin0503.itemmilestonelog.api;

import java.util.UUID;

/**
 * Immutable record of the first player to obtain an item target (item + quantity) in the world.
 *
 * @param itemId      registry item ID
 * @param count       required quantity (1..1,000,000)
 * @param playerUuid  UUID of the player who achieved the world first
 * @param playerName  player name at the time of recording
 * @param playTicks   player's cumulative playtime ticks at recording
 * @param day         in-game overworld day index, or {@link MilestoneRecord#UNKNOWN_DAY} if unknown
 * @param epochMillis wall-clock timestamp in milliseconds since Unix epoch, or {@link MilestoneRecord#UNKNOWN_EPOCH} if unknown
 */
public record WorldFirstRecord(
        String itemId,
        int count,
        UUID playerUuid,
        String playerName,
        int playTicks,
        long day,
        long epochMillis
) {
    public WorldFirstRecord(String itemId, UUID playerUuid, String playerName, int playTicks, long day, long epochMillis) {
        this(itemId, TargetKey.MIN_COUNT, playerUuid, playerName, playTicks, day, epochMillis);
    }

    public TargetKey targetKey() {
        return new TargetKey(itemId, count);
    }

    public MilestoneRecord toMilestoneRecord() {
        return new MilestoneRecord(playTicks, day, epochMillis);
    }
}
