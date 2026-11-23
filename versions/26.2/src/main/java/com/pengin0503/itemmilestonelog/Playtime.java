package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Helpers around the vanilla cumulative playtime statistic and record formatting. */
public final class Playtime {
    private static final long TICKS_PER_DAY = 24000L;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private Playtime() {
    }

    public static int currentTicks(ServerPlayer player) {
        return player.getStats().getValue(Stats.CUSTOM, Stats.PLAY_TIME);
    }

    public static long currentDay(MinecraftServer server) {
        return Math.floorDiv(server.overworld().getOverworldClockTime(), TICKS_PER_DAY);
    }

    public static String format(int ticks) {
        long totalSeconds = Math.max(0, ticks) / 20L;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.ROOT, "%dh %02dm %02ds", hours, minutes, seconds);
    }

    public static String formatDate(long epochMillis) {
        return DATE_FORMATTER.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMillis));
    }

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
