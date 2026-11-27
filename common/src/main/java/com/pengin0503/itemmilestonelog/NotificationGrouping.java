package com.pengin0503.itemmilestonelog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/**
 * Pure-Java grouping and formatting logic for simultaneous milestone record notifications (F27).
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class NotificationGrouping {
    /** Minimum number of queued notifications in the same channel in a tick to send a grouped message. */
    public static final int GROUP_THRESHOLD = 3;
    /** Maximum number of target names listed explicitly in a grouped chat message. */
    public static final int CHAT_LIST_MAX_ITEMS = 5;
    /** Maximum number of item lines shown in a grouped Discord message before collapsing the remainder. */
    public static final int DISCORD_MAX_LINES = 20;
    /** Maximum plain-text character count for a Discord webhook {@code content} payload. */
    public static final int DISCORD_PLAIN_MAX_CHARS = 2000;
    /** Maximum character count for a Discord embed {@code description} (for F4 reuse). */
    public static final int DISCORD_EMBED_DESCRIPTION_MAX_CHARS = 4096;
    /** Shortest supported shared notification window; 1 keeps the original per-tick F27 behaviour. */
    public static final int MIN_WINDOW_TICKS = 1;
    /** Longest supported shared notification window (in-game and Discord). */
    public static final int MAX_WINDOW_TICKS = 20;

    public enum SendMode {
        NONE,
        INDIVIDUAL,
        GROUPED
    }

    /** Per-channel dispatch decision for one player's queued records in a single tick. */
    public record ChannelPlan<T>(SendMode mode, List<T> items) {
        public static <T> ChannelPlan<T> none() {
            return new ChannelPlan<>(SendMode.NONE, List.of());
        }
    }

    /** Full per-channel dispatch plan for one player's queued records in a single tick. */
    public record FlushPlan<T>(
            ChannelPlan<T> playerChat,
            ChannelPlan<T> broadcastChat,
            ChannelPlan<T> worldFirstChat,
            boolean playSound,
            ChannelPlan<T> discord,
            ChannelPlan<T> discordWorldFirst
    ) {
    }

    /** Slice of explicitly listed items plus the count of omitted trailing items. */
    public record ListSummary<T>(List<T> shown, int remainingCount) {
        public boolean hasMore() {
            return remainingCount > 0;
        }
    }

    /** Single item entry for building a grouped Discord notification payload. */
    public record DiscordItem(String label, boolean worldFirst) {
    }

    /** Language templates used when formatting a grouped Discord notification. */
    public record DiscordTemplates(
            String groupHeader,
            String groupLine,
            String groupLineWorldFirst,
            String moreLine
    ) {
        public static final DiscordTemplates ENGLISH_DEFAULTS = new DiscordTemplates(
                "★ Milestones recorded: %s obtained %s targets at %s",
                "- %s",
                "- ★ [World First] %s",
                "...and %s more"
        );
    }

    private NotificationGrouping() {
    }

    /**
     * Clamps a configured window length into the shared 1..20 range. A missing or invalid
     * configuration value is expected to arrive here as {@link #MIN_WINDOW_TICKS}.
     */
    public static int normalizeWindowTicks(int windowTicks) {
        return Math.max(MIN_WINDOW_TICKS, Math.min(MAX_WINDOW_TICKS, windowTicks));
    }

    /**
     * The single window definition shared by in-game and Discord notification aggregation (N63/N125):
     * {@code windowIndex = floorDiv(serverTick, windowTicks)}. Queues are still one per player; the
     * index is stored on that queue, not used as a map key.
     */
    public static long windowIndex(long serverTick, int windowTicks) {
        return Math.floorDiv(serverTick, (long) normalizeWindowTicks(windowTicks));
    }

    /**
     * True once the {@code ServerTickEvent.Post} being handled has moved past the window in which
     * a queue was filled. The window closes on the first Post whose index is greater, so a 1-tick
     * window still flushes on the very next Post (byte-for-byte current behaviour).
     */
    public static boolean windowClosed(long pendingWindowIndex, long currentTick, int windowTicks) {
        return windowIndex(currentTick, windowTicks) > pendingWindowIndex;
    }

    /**
     * Dispatch decision for the progress-alert channel (N63). Progress alerts are a separate
     * channel: they reuse the F27 {@value #GROUP_THRESHOLD} threshold and chat-list caps but never
     * enter the record or Discord plans. 1-2 alerts are sent individually, 3+ become one message.
     */
    public static <T> ChannelPlan<T> planProgressChannel(List<T> alerts) {
        if (alerts == null || alerts.isEmpty()) {
            return ChannelPlan.none();
        }
        if (alerts.size() >= GROUP_THRESHOLD) {
            return new ChannelPlan<>(SendMode.GROUPED, List.copyOf(alerts));
        }
        return new ChannelPlan<>(SendMode.INDIVIDUAL, List.copyOf(alerts));
    }

    /**
     * Decides per-channel delivery (none, individual, or grouped) for a single player's
     * records queued during the same server tick.
     *
     * <ul>
     *   <li>Player and broadcast channels count all records.</li>
     *   <li>World-first channel counts only world-first records.</li>
     *   <li>Discord counts records selected by {@code discordNotifyScope} ({@code "world_first"} or {@code "all"}).</li>
     *   <li>Each channel groups into one message when its eligible count is {@code >= GROUP_THRESHOLD} (3),
     *       sorting grouped items by {@code groupSortOrder}; counts of 1 or 2 send individual messages in order.</li>
     *   <li>Sound plays at most once per player per tick whenever at least one record was queued
     *       and {@code milestoneSoundEnabled} is true.</li>
     * </ul>
     */
    public static <T> FlushPlan<T> planFlush(
            List<T> records,
            Predicate<T> isWorldFirst,
            Comparator<T> groupSortOrder,
            boolean notifyPlayer,
            boolean broadcastMilestones,
            boolean announceWorldFirst,
            boolean milestoneSoundEnabled,
            String discordNotifyScope) {
        return planFlush(
                records,
                records,
                isWorldFirst,
                groupSortOrder,
                notifyPlayer,
                broadcastMilestones,
                announceWorldFirst,
                milestoneSoundEnabled,
                true,
                discordNotifyScope);
    }

    /**
     * Decides per-channel delivery for a single player's queued records in a single tick,
     * with an independent Discord queue and opt-out gate (F27 + F45), assuming only the
     * primary Discord webhook is configured.
     */
    public static <T> FlushPlan<T> planFlush(
            List<T> records,
            List<T> discordRecords,
            Predicate<T> isWorldFirst,
            Comparator<T> groupSortOrder,
            boolean notifyPlayer,
            boolean broadcastMilestones,
            boolean announceWorldFirst,
            boolean milestoneSoundEnabled,
            boolean discordEnabled,
            String discordNotifyScope) {
        return planFlush(
                records,
                discordRecords,
                isWorldFirst,
                groupSortOrder,
                notifyPlayer,
                broadcastMilestones,
                announceWorldFirst,
                milestoneSoundEnabled,
                discordEnabled,
                discordNotifyScope,
                true,
                false);
    }

    /**
     * Decides per-channel delivery for a single player's queued records in a single tick,
     * splitting Discord records by destination ({@code discordWebhookUrl} vs
     * {@code discordWorldFirstWebhookUrl}) BEFORE applying the F27 grouping threshold
     * independently per destination (F27 + F45 + F46).
     */
    public static <T> FlushPlan<T> planFlush(
            List<T> records,
            List<T> discordRecords,
            Predicate<T> isWorldFirst,
            Comparator<T> groupSortOrder,
            boolean notifyPlayer,
            boolean broadcastMilestones,
            boolean announceWorldFirst,
            boolean milestoneSoundEnabled,
            boolean discordEnabled,
            String discordNotifyScope,
            boolean mainWebhookSet,
            boolean worldFirstWebhookSet) {
        if (records == null || records.isEmpty()) {
            return new FlushPlan<>(
                    ChannelPlan.none(),
                    ChannelPlan.none(),
                    ChannelPlan.none(),
                    false,
                    ChannelPlan.none(),
                    ChannelPlan.none());
        }

        List<T> worldFirstRecords = new ArrayList<>();
        for (T rec : records) {
            if (isWorldFirst != null && isWorldFirst.test(rec)) {
                worldFirstRecords.add(rec);
            }
        }

        ChannelPlan<T> playerChat = planChannel(records, notifyPlayer, groupSortOrder);
        ChannelPlan<T> broadcastChat = planChannel(records, broadcastMilestones, groupSortOrder);
        ChannelPlan<T> worldFirstChat = planChannel(worldFirstRecords, announceWorldFirst, groupSortOrder);

        ChannelPlan<T> discord;
        ChannelPlan<T> discordWorldFirst;
        if (!discordEnabled || discordRecords == null || discordRecords.isEmpty()) {
            discord = ChannelPlan.none();
            discordWorldFirst = ChannelPlan.none();
        } else {
            DiscordRouting.RecordBatchSplit<T> split = DiscordRouting.splitRecordsByDestination(
                    discordRecords, isWorldFirst, mainWebhookSet, worldFirstWebhookSet, discordNotifyScope);
            discord = planChannel(split.mainRecords(), true, groupSortOrder);
            discordWorldFirst = planChannel(split.worldFirstRecords(), true, groupSortOrder);
        }

        return new FlushPlan<>(
                playerChat, broadcastChat, worldFirstChat, milestoneSoundEnabled, discord, discordWorldFirst);
    }

    private static <T> ChannelPlan<T> planChannel(List<T> eligible, boolean enabled, Comparator<T> groupSortOrder) {
        if (!enabled || eligible.isEmpty()) {
            return ChannelPlan.none();
        }
        if (eligible.size() >= GROUP_THRESHOLD) {
            List<T> sorted = new ArrayList<>(eligible);
            if (groupSortOrder != null) {
                sorted.sort(groupSortOrder);
            }
            return new ChannelPlan<>(SendMode.GROUPED, List.copyOf(sorted));
        }
        return new ChannelPlan<>(SendMode.INDIVIDUAL, List.copyOf(eligible));
    }

    /** Takes the first {@value #CHAT_LIST_MAX_ITEMS} items and counts the remaining ones. */
    public static <T> ListSummary<T> summarizeChatList(List<T> sortedItems) {
        return summarizeList(sortedItems, CHAT_LIST_MAX_ITEMS);
    }

    /** Takes at most {@code maxShown} items and counts the remaining ones. */
    public static <T> ListSummary<T> summarizeList(List<T> items, int maxShown) {
        if (items == null || items.isEmpty()) {
            return new ListSummary<>(List.of(), 0);
        }
        int cap = Math.max(0, maxShown);
        if (items.size() <= cap) {
            return new ListSummary<>(List.copyOf(items), 0);
        }
        return new ListSummary<>(List.copyOf(items.subList(0, cap)), items.size() - cap);
    }

    /**
     * Pure string formatter mirroring grouped chat list assembly: joins the first 5 names with
     * {@code separator} and appends {@code moreTemplate} formatted with the remaining count when needed.
     */
    public static String formatChatListText(List<String> sortedDisplayNames, String separator, String moreTemplate) {
        ListSummary<String> summary = summarizeChatList(sortedDisplayNames);
        if (summary.shown().isEmpty() && !summary.hasMore()) {
            return "";
        }
        String sep = separator != null ? separator : ", ";
        StringBuilder sb = new StringBuilder();
        List<String> shown = summary.shown();
        for (int i = 0; i < shown.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(shown.get(i));
        }
        if (summary.hasMore()) {
            sb.append(formatSafe(moreTemplate, ", and %s more", summary.remainingCount()));
        }
        return sb.toString();
    }

    /**
     * Builds the plain-text body for a grouped Discord notification, enforcing the
     * {@value #DISCORD_MAX_LINES}-line cap and {@value #DISCORD_PLAIN_MAX_CHARS}-character limit.
     */
    public static String buildDiscordGroupPlainText(
            String playerName,
            String formattedRecord,
            List<DiscordItem> items,
            DiscordTemplates templates) {
        return buildDiscordGroupPlainText(
                playerName, formattedRecord, items, templates, DISCORD_MAX_LINES, DISCORD_PLAIN_MAX_CHARS);
    }

    /**
     * Builds the plain-text body for a grouped Discord notification with configurable line and character limits.
     */
    public static String buildDiscordGroupPlainText(
            String playerName,
            String formattedRecord,
            List<DiscordItem> items,
            DiscordTemplates templates,
            int maxLines,
            int maxChars) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        List<DiscordItem> safeItems = nonNullItems(items);
        if (safeItems.isEmpty()) {
            return "";
        }
        DiscordTemplates t = templates != null ? templates : DiscordTemplates.ENGLISH_DEFAULTS;
        String safeName = playerName != null ? playerName : "";
        String safeTime = formattedRecord != null ? formattedRecord : "";
        String header = formatSafe(
                t.groupHeader(),
                DiscordTemplates.ENGLISH_DEFAULTS.groupHeader(),
                safeName,
                safeItems.size(),
                safeTime);
        return assembleDiscordBody(header, formatDiscordItemLines(safeItems, t), t.moreLine(), maxLines, maxChars);
    }

    /**
     * Builds only the item lines (with optional trailing "more" line) for a grouped Discord payload,
     * capped at {@code maxLines} and {@code maxChars}. Designed to be reused for F4 embed descriptions.
     */
    public static String buildDiscordGroupLines(
            List<DiscordItem> items,
            DiscordTemplates templates,
            int maxLines,
            int maxChars) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        List<DiscordItem> safeItems = nonNullItems(items);
        if (safeItems.isEmpty()) {
            return "";
        }
        DiscordTemplates t = templates != null ? templates : DiscordTemplates.ENGLISH_DEFAULTS;
        return assembleDiscordBody(null, formatDiscordItemLines(safeItems, t), t.moreLine(), maxLines, maxChars);
    }

    /** Drops null entries so header counts and rendered lines always agree. */
    private static List<DiscordItem> nonNullItems(List<DiscordItem> items) {
        List<DiscordItem> safe = new ArrayList<>(items.size());
        for (DiscordItem item : items) {
            if (item != null) {
                safe.add(item);
            }
        }
        return safe;
    }

    private static List<String> formatDiscordItemLines(List<DiscordItem> items, DiscordTemplates templates) {
        List<String> lines = new ArrayList<>(items.size());
        for (DiscordItem item : items) {
            if (item == null) {
                continue;
            }
            String label = item.label() != null ? item.label() : "";
            if (item.worldFirst()) {
                lines.add(formatSafe(
                        templates.groupLineWorldFirst(),
                        DiscordTemplates.ENGLISH_DEFAULTS.groupLineWorldFirst(),
                        label));
            } else {
                lines.add(formatSafe(
                        templates.groupLine(),
                        DiscordTemplates.ENGLISH_DEFAULTS.groupLine(),
                        label));
            }
        }
        return lines;
    }

    /**
     * Assembles an optional header line, up to {@code maxLines} formatted item lines, and a
     * trailing "more" line produced by {@code moreLineFormatter} when lines are omitted,
     * keeping the result within {@code maxChars} UTF-16 code units.
     */
    public static String assembleDiscordBody(
            String header,
            List<String> itemLines,
            IntFunction<String> moreLineFormatter,
            int maxLines,
            int maxChars) {
        if (itemLines == null || itemLines.isEmpty()) {
            return "";
        }
        IntFunction<String> formatter = moreLineFormatter != null
                ? moreLineFormatter
                : remaining -> formatSafe(null, DiscordTemplates.ENGLISH_DEFAULTS.moreLine(), remaining);
        int total = itemLines.size();
        int shown = Math.min(total, Math.max(0, maxLines));
        int limit = Math.max(0, maxChars);

        String content = renderDiscordBody(header, itemLines, shown, total, formatter);
        while (content.length() > limit && shown > 0) {
            shown--;
            content = renderDiscordBody(header, itemLines, shown, total, formatter);
        }
        if (content.length() > limit) {
            return TextEscaping.truncateUtf16(content, limit);
        }
        return content;
    }

    private static String assembleDiscordBody(
            String header,
            List<String> itemLines,
            String moreTemplate,
            int maxLines,
            int maxChars) {
        return assembleDiscordBody(
                header,
                itemLines,
                remaining -> formatSafe(moreTemplate, DiscordTemplates.ENGLISH_DEFAULTS.moreLine(), remaining),
                maxLines,
                maxChars);
    }

    private static String renderDiscordBody(
            String header,
            List<String> itemLines,
            int shown,
            int total,
            IntFunction<String> moreLineFormatter) {
        StringBuilder sb = new StringBuilder();
        if (header != null && !header.isEmpty()) {
            sb.append(header);
        }
        for (int i = 0; i < shown; i++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(itemLines.get(i));
        }
        int remaining = total - shown;
        if (remaining > 0) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            String moreLine = moreLineFormatter.apply(remaining);
            if (moreLine != null) {
                sb.append(moreLine);
            }
        }
        return sb.toString();
    }

    private static String formatSafe(String template, String fallbackTemplate, Object... args) {
        String effective = template != null ? template : fallbackTemplate;
        try {
            return String.format(Locale.ROOT, effective, args);
        } catch (IllegalFormatException e) {
            return String.format(Locale.ROOT, fallbackTemplate, args);
        }
    }
}
