package com.pengin0503.itemmilestonelog;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;

/** Helpers around the vanilla cumulative playtime statistic. */
public final class Playtime {
    private Playtime() {
    }

    /**
     * The player's cumulative playtime in ticks, exactly as shown in the
     * vanilla Statistics screen ("Time Played" / minecraft:play_time).
     * Counts all time spent in this world/server, including AFK time,
     * and is not reset by sleeping (unlike world time).
     */
    public static int currentTicks(ServerPlayer player) {
        return player.getStats().getValue(Stats.CUSTOM, Stats.PLAY_TIME);
    }

    /** Formats playtime ticks as e.g. "27h 15m 30s" (hours are total hours, not wrapped at 24). */
    public static String format(int ticks) {
        long totalSeconds = ticks / 20L;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format("%dh %02dm %02ds", hours, minutes, seconds);
    }
}
