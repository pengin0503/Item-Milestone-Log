package com.pengin0503.itemmilestonelog.api;

/**
 * Immutable milestone record representing when a player first obtained a target item.
 *
 * @param playTicks   cumulative playtime ticks (vanilla {@code minecraft:play_time} stat)
 * @param day         in-game overworld day index ({@code floor(dayTime / 24000)}), or {@link #UNKNOWN_DAY} if unknown
 * @param epochMillis wall-clock timestamp in milliseconds since Unix epoch, or {@link #UNKNOWN_EPOCH} if unknown
 */
public record MilestoneRecord(int playTicks, long day, long epochMillis) {
    public static final long UNKNOWN_DAY = -1L;
    public static final long UNKNOWN_EPOCH = 0L;

    public static MilestoneRecord legacy(int playTicks) {
        return new MilestoneRecord(playTicks, UNKNOWN_DAY, UNKNOWN_EPOCH);
    }

    public boolean hasDay() {
        return day >= 0L;
    }

    public boolean hasEpoch() {
        return epochMillis > 0L;
    }
}
