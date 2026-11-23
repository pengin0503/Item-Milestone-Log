package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;

import javax.annotation.Nullable;
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
        return formatDate(epochMillis, ZoneId.systemDefault());
    }

    public static String formatDate(long epochMillis, ZoneId zone) {
        ZoneId effectiveZone = zone != null ? zone : ZoneId.systemDefault();
        return DATE_FORMATTER.withZone(effectiveZone).format(Instant.ofEpochMilli(epochMillis));
    }

    public static String formatRecord(MilestoneRecord record) {
        return formatRecord(record, ZoneId.systemDefault());
    }

    public static String formatRecord(MilestoneRecord record, ZoneId zone) {
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
            sb.append(formatDate(record.epochMillis(), zone));
        }
        sb.append(')');
        return sb.toString();
    }

    // ---- F48: locale-aware Component variants (chat and GUI only) ----
    // The String variants above stay English-format for Discord, server logs, exports and the API.

    static final String KEY_PLAYTIME_FORMAT = "message.itemmilestonelog.playtime_format";
    static final String KEY_RECORD_DAY_DATE = "message.itemmilestonelog.record_day_date";
    static final String KEY_RECORD_DAY = "message.itemmilestonelog.record_day";
    static final String KEY_RECORD_DATE = "message.itemmilestonelog.record_date";

    /** Builds a translatable component from a key and arguments (server: ServerLang.tr, client: Component.translatable). */
    @FunctionalInterface
    public interface Translator {
        MutableComponent tr(String key, Object... args);
    }

    /** Client-side translator for GUI use (the client's own language via {@link Component#translatable}). */
    public static final Translator CLIENT = Component::translatable;

    /** Playtime as a component; minutes and seconds are zero-padded here and passed as strings. */
    public static MutableComponent formatComponent(int ticks, Translator translator) {
        long totalSeconds = Math.max(0, ticks) / 20L;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return translator.tr(KEY_PLAYTIME_FORMAT,
                Long.toString(hours),
                String.format(Locale.ROOT, "%02d", minutes),
                String.format(Locale.ROOT, "%02d", seconds));
    }

    /** Record formatting as a component; the en_us result equals {@link #formatRecord(MilestoneRecord, ZoneId)}. */
    public static MutableComponent formatRecordComponent(MilestoneRecord record, @Nullable ZoneId zone, Translator translator) {
        MutableComponent base = formatComponent(record.playTicks(), translator);
        boolean hasDay = record.hasDay();
        boolean hasEpoch = record.hasEpoch();
        if (hasDay && hasEpoch) {
            return translator.tr(KEY_RECORD_DAY_DATE, base,
                    Long.toString(record.day() + 1L), formatDate(record.epochMillis(), zone));
        }
        if (hasDay) {
            return translator.tr(KEY_RECORD_DAY, base, Long.toString(record.day() + 1L));
        }
        if (hasEpoch) {
            return translator.tr(KEY_RECORD_DATE, base, formatDate(record.epochMillis(), zone));
        }
        return base;
    }

    /** Server chat: playtime in the recipient's language. */
    public static MutableComponent formatComponent(@Nullable ServerPlayer recipient, int ticks) {
        return formatComponent(ticks, (key, args) -> ServerLang.tr(recipient, key, args));
    }

    /** Server chat: playtime in the command source's language. */
    public static MutableComponent formatComponent(@Nullable CommandSourceStack source, int ticks) {
        return formatComponent(ticks, (key, args) -> ServerLang.tr(source, key, args));
    }

    /** Server chat: record formatting in the recipient's language. */
    public static MutableComponent formatRecordComponent(@Nullable ServerPlayer recipient, MilestoneRecord record, @Nullable ZoneId zone) {
        return formatRecordComponent(record, zone, (key, args) -> ServerLang.tr(recipient, key, args));
    }

    /** Server chat: record formatting in the command source's language. */
    public static MutableComponent formatRecordComponent(@Nullable CommandSourceStack source, MilestoneRecord record, @Nullable ZoneId zone) {
        return formatRecordComponent(record, zone, (key, args) -> ServerLang.tr(source, key, args));
    }

    /** Playtime for an explicit language code (bundled lang text as fallback). */
    public static MutableComponent formatComponentForLang(@Nullable String language, int ticks) {
        return formatComponent(ticks, (key, args) -> ServerLang.trForLang(language, key, args));
    }

    /** Record formatting for an explicit language code (bundled lang text as fallback). */
    public static MutableComponent formatRecordComponentForLang(@Nullable String language, MilestoneRecord record, @Nullable ZoneId zone) {
        return formatRecordComponent(record, zone, (key, args) -> ServerLang.trForLang(language, key, args));
    }
}
