package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Helpers around the vanilla cumulative playtime statistic and record formatting. */
public final class Playtime {
    private static final long TICKS_PER_DAY = 24000L;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

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

    /** Returns the current in-game day index ({@code floor(overworld.getDayTime() / 24000)}). */
    public static long currentDay(MinecraftServer server) {
        return Math.floorDiv(server.overworld().getDayTime(), TICKS_PER_DAY);
    }

    /** Formats playtime ticks as e.g. "27h 15m 30s" (hours are total hours, not wrapped at 24). */
    public static String format(int ticks) {
        long totalSeconds = Math.max(0, ticks) / 20L;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format("%dh %02dm %02ds", hours, minutes, seconds);
    }

    /** Formats an epoch millisecond timestamp as {@code yyyy-MM-dd} in the system default timezone. */
    public static String formatDate(long epochMillis) {
        return DATE_FORMATTER.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMillis));
    }

    /**
     * Formats a milestone record with optional in-game Day and real-world date in parentheses,
     * e.g. {@code "27h 15m 30s (Day 12, 2026-11-22)"}. Unknown fields are omitted.
     */
    public static String formatRecord(MilestoneRecord record) {
        String base = format(record.playTicks());
        boolean hasDay = record.hasDay();
        boolean hasEpoch = record.hasEpoch();
        if (!hasDay && !hasEpoch) {
            return base;
        }
        StringBuilder sb = new StringBuilder(base).append(" (");
        if (hasDay) {
            sb.append("Day ").append(record.day() + 1L);
            if (hasEpoch) {
                sb.append(", ");
            }
        }
        if (hasEpoch) {
            sb.append(formatDate(record.epochMillis()));
        }
        sb.append(')');
        return sb.toString();
    }
}
