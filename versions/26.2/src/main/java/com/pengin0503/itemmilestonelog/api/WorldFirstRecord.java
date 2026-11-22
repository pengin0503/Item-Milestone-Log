package com.pengin0503.itemmilestonelog.api;

import java.util.UUID;

/**
 * Immutable record of the first player to obtain an item in the world.
 */
public record WorldFirstRecord(
        String itemId,
        UUID playerUuid,
        String playerName,
        int playTicks,
        long day,
        long epochMillis
) {
    public MilestoneRecord toMilestoneRecord() {
        return new MilestoneRecord(playTicks, day, epochMillis);
    }
}
