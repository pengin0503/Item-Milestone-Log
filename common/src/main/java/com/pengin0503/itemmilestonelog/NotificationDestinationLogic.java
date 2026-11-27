package com.pengin0503.itemmilestonelog;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * F-150-43 / F-150-44 (N64 x N116): the single definition of progress-alert destination
 * precedence. The server default ({@link Channel}) and the optional personal choice
 * ({@link Choice}) are resolved here and nowhere else; both the queued (N63) sender and the
 * immediate {@code groupSimultaneousRecords=false} sender consult this class.
 *
 * <p>Pure Java 21, no Minecraft/NeoForge dependency. Missing or {@link Choice#FOLLOW_SERVER}
 * personal data falls back to the server default, so old SavedData keeps its current behaviour.
 */
public final class NotificationDestinationLogic {
    /** Server-side destination values. {@code CHAT} is the 1.4.0 behaviour and the default. */
    public enum Channel {
        CHAT,
        ACTION_BAR,
        BOTH,
        OFF
    }

    /** Personal choice values. {@code FOLLOW_SERVER} is the default for missing/invalid data. */
    public enum Choice {
        FOLLOW_SERVER,
        CHAT,
        ACTION_BAR,
        BOTH,
        OFF
    }

    private static final Logger LOGGER = Logger.getLogger(NotificationDestinationLogic.class.getName());
    private static final AtomicBoolean WARNED_CHANNEL = new AtomicBoolean(false);
    private static final AtomicBoolean WARNED_CHOICE = new AtomicBoolean(false);

    private NotificationDestinationLogic() {
    }

    /**
     * The one precedence rule (spec 1.2.1): a missing or {@code FOLLOW_SERVER} choice inherits the
     * server default; every other choice maps 1:1 onto a {@link Channel}.
     */
    public static Channel resolve(Channel serverDefault, Choice playerChoice) {
        Channel fallback = serverDefault != null ? serverDefault : Channel.CHAT;
        if (playerChoice == null || playerChoice == Choice.FOLLOW_SERVER) {
            return fallback;
        }
        return Channel.valueOf(playerChoice.name());
    }

    /** Chat delivery: {@code CHAT} and {@code BOTH} only; {@code OFF} sends nothing. */
    public static boolean sendsChat(Channel channel) {
        return channel == Channel.CHAT || channel == Channel.BOTH;
    }

    /** Action-bar (vanilla overlay) delivery: {@code ACTION_BAR} and {@code BOTH} only. */
    public static boolean sendsActionBar(Channel channel) {
        return channel == Channel.ACTION_BAR || channel == Channel.BOTH;
    }

    /**
     * Parses a config-file value ({@code chat}, {@code actionbar}, {@code both}, {@code off}).
     * {@code server} is personal-choice-only and is rejected here. Invalid input falls back to
     * {@link Channel#CHAT} and produces at most one warning per JVM.
     */
    public static Channel parseChannel(String raw) {
        Channel parsed = raw == null ? null : parseEnum(normalizeChannel(raw), Channel.class);
        if (parsed == null) {
            warnOnce(WARNED_CHANNEL,
                    "[ItemMilestoneLog] Invalid progressAlertDestination '" + raw + "'; using chat");
            return Channel.CHAT;
        }
        return parsed;
    }

    /**
     * Parses a SavedData enum name. Missing/blank input means {@link Choice#FOLLOW_SERVER}; invalid
     * names also fall back to {@code FOLLOW_SERVER} with at most one warning per JVM.
     */
    public static Choice parseChoice(String raw) {
        if (raw == null || raw.isBlank()) {
            return Choice.FOLLOW_SERVER;
        }
        Choice parsed = parseEnum(raw.trim(), Choice.class);
        if (parsed == null) {
            warnOnce(WARNED_CHOICE,
                    "[ItemMilestoneLog] Invalid progressAlertDestination '" + raw
                            + "' in SavedData; following the server default");
            return Choice.FOLLOW_SERVER;
        }
        return parsed;
    }

    /** Whether a config string names a real server destination (validation without warning). */
    public static boolean isValidChannel(String raw) {
        return raw != null && parseEnum(normalizeChannel(raw), Channel.class) != null;
    }

    /** Whether a stored string names a real personal choice (validation without warning). */
    public static boolean isValidChoice(String raw) {
        return raw != null && parseEnum(raw.trim(), Choice.class) != null;
    }

    /** Lowercase command/help label ({@code server} for {@code FOLLOW_SERVER}). */
    public static String label(Choice choice) {
        Choice value = choice != null ? choice : Choice.FOLLOW_SERVER;
        if (value == Choice.FOLLOW_SERVER) {
            return "server";
        }
        return value == Choice.ACTION_BAR ? "actionbar" : value.name().toLowerCase(Locale.ROOT);
    }

    /** Lowercase config/help label ({@code actionbar} for {@code ACTION_BAR}). */
    public static String label(Channel channel) {
        Channel value = channel != null ? channel : Channel.CHAT;
        return value == Channel.ACTION_BAR ? "actionbar" : value.name().toLowerCase(Locale.ROOT);
    }

    /** Test seam for the warn-once guarantee; production code never calls this. */
    static void resetWarningStateForTests() {
        WARNED_CHANNEL.set(false);
        WARNED_CHOICE.set(false);
    }

    /** The config literal is {@code actionbar}; the enum name is {@code ACTION_BAR}. */
    private static String normalizeChannel(String raw) {
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        return "ACTIONBAR".equals(upper) ? "ACTION_BAR" : upper;
    }

    private static <E extends Enum<E>> E parseEnum(String raw, Class<E> type) {
        Function<String, E> valueOf = value -> {
            try {
                return Enum.valueOf(type, value);
            } catch (IllegalArgumentException invalid) {
                return null;
            }
        };
        return valueOf.apply(raw);
    }

    private static void warnOnce(AtomicBoolean warned, String message) {
        if (warned.compareAndSet(false, true)) {
            LOGGER.warning(message);
        }
    }
}
