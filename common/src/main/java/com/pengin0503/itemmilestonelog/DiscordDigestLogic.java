package com.pengin0503.itemmilestonelog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntFunction;

/**
 * Pure-Java logic for F9 periodic Discord digest notifications, shared across all Minecraft
 * versions via {@code common/src/main/java}: interval/first-run scheduling decisions, window
 * extraction with scope/opt-out filtering and F47 name resolution, Markdown-escaped line
 * formatting, and line/character truncation.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class DiscordDigestLogic {
    /** Default interval in days for {@code discordDigestIntervalDays}. */
    public static final int DEFAULT_INTERVAL_DAYS = 7;
    /** Minimum allowed interval in days for {@code discordDigestIntervalDays}. */
    public static final int MIN_INTERVAL_DAYS = 1;
    /** Maximum allowed interval in days for {@code discordDigestIntervalDays}. */
    public static final int MAX_INTERVAL_DAYS = 30;
    /** Milliseconds in one day (24 hours). */
    public static final long MILLIS_PER_DAY = 86_400_000L;
    /** Server ticks between periodic digest checks while running (~1 hour at 20 TPS). */
    public static final int CHECK_INTERVAL_TICKS = 72_000;
    /** Maximum number of digest lines shown before appending the trailing "more" line. */
    public static final int MAX_LINES = NotificationGrouping.DISCORD_MAX_LINES;
    /** Prefix prepended to world-first rows in a digest. */
    public static final String WORLD_FIRST_PREFIX = "★ ";

    static final String DEFAULT_LINE_TEMPLATE = "%s — %s (%s)";
    static final String DEFAULT_MORE_TEMPLATE = "...and %s more";

    /** Scheduling decision for a digest check tick/startup pass. */
    public enum CheckAction {
        /** Do nothing (disabled, no webhook, or interval not yet elapsed). */
        SKIP,
        /** First run ({@code lastDigestEpoch == 0}): record current timestamp and send nothing. */
        INIT_TIMESTAMP_ONLY,
        /** Interval elapsed: collect qualifying records in {@code (lastDigestEpoch, now]} and send if non-empty. */
        COLLECT_AND_SEND
    }

    /** Pure snapshot of one milestone record owned by a player. */
    public record RecordSnapshot(String itemId, int count, int playTicks, long day, long epochMillis) {
    }

    /** Pure snapshot of one player's persisted state relevant to digest extraction. */
    public record PlayerSnapshot(
            UUID playerUuid,
            String lastKnownName,
            boolean excludeFromDiscord,
            List<RecordSnapshot> records
    ) {
    }

    /** Pure snapshot of one persisted world-first record. */
    public record WorldFirstSnapshot(
            String itemId,
            int count,
            UUID playerUuid,
            String recordedName,
            int playTicks,
            long day,
            long epochMillis
    ) {
    }

    /** Extracted digest row ready for formatting, sorted by {@code epochMillis} ascending. */
    public record DigestEntry(
            UUID playerUuid,
            String playerName,
            String itemId,
            int count,
            int playTicks,
            long day,
            long epochMillis,
            boolean worldFirst
    ) {
    }

    /** Simple server-thread tick counter for thinning periodic digest checks. */
    public static final class TickThrottler {
        private final int intervalTicks;
        private int ticks;

        public TickThrottler() {
            this(CHECK_INTERVAL_TICKS);
        }

        public TickThrottler(int intervalTicks) {
            this.intervalTicks = Math.max(1, intervalTicks);
        }

        /** Advances by one server tick; returns {@code true} once every {@code intervalTicks} ticks. */
        public boolean tick() {
            ticks++;
            if (ticks >= intervalTicks) {
                ticks = 0;
                return true;
            }
            return false;
        }

        /** Resets the elapsed tick count to zero. */
        public void reset() {
            ticks = 0;
        }

        int currentTicks() {
            return ticks;
        }
    }

    private record TargetIdCount(String itemId, int count) {
    }

    private record PlayerTargetKey(UUID playerUuid, String itemId, int count) {
    }

    private DiscordDigestLogic() {
    }

    /** Clamps {@code intervalDays} to [{@value #MIN_INTERVAL_DAYS}, {@value #MAX_INTERVAL_DAYS}]. */
    public static int clampIntervalDays(int intervalDays) {
        return Math.max(MIN_INTERVAL_DAYS, Math.min(MAX_INTERVAL_DAYS, intervalDays));
    }

    /** Converts {@code intervalDays} (clamped to 1..30) into milliseconds. */
    public static long intervalMillis(int intervalDays) {
        return clampIntervalDays(intervalDays) * MILLIS_PER_DAY;
    }

    /**
     * Decides what action a digest check should take given the configuration, persisted
     * {@code lastDigestEpoch}, and current wall-clock time {@code nowMillis}.
     *
     * <ul>
     *   <li>Disabled, unconfigured webhook, or non-positive {@code nowMillis} -> {@link CheckAction#SKIP}.</li>
     *   <li>First run ({@code lastDigestEpoch <= 0}) -> {@link CheckAction#INIT_TIMESTAMP_ONLY}
     *       (records {@code nowMillis} and sends nothing).</li>
     *   <li>Elapsed ({@code nowMillis - lastDigestEpoch >= intervalMillis}) -> {@link CheckAction#COLLECT_AND_SEND}.</li>
     *   <li>Otherwise (including clock skew {@code nowMillis <= lastDigestEpoch}) -> {@link CheckAction#SKIP}.</li>
     * </ul>
     */
    public static CheckAction decideAction(
            boolean enabled,
            boolean webhookConfigured,
            long lastDigestEpoch,
            long nowMillis,
            int intervalDays) {
        if (!enabled || !webhookConfigured || nowMillis <= 0L) {
            return CheckAction.SKIP;
        }
        if (lastDigestEpoch <= 0L) {
            return CheckAction.INIT_TIMESTAMP_ONLY;
        }
        if (nowMillis > lastDigestEpoch && (nowMillis - lastDigestEpoch) >= intervalMillis(intervalDays)) {
            return CheckAction.COLLECT_AND_SEND;
        }
        return CheckAction.SKIP;
    }

    /**
     * F-150-54 (N32): scheduling decision for an explicitly requested digest. The caller's
     * {@code enabled} flag and the interval are deliberately ignored, but the webhook
     * configuration, the non-positive clock guard and the first-run behaviour remain exactly
     * as in {@link #decideAction}. Opt-out and scope filtering happen later in
     * {@link #extractEntries}, so they are still applied to a manual run.
     */
    public static CheckAction decideActionForManual(
            boolean webhookConfigured,
            long lastDigestEpoch,
            long nowMillis) {
        if (!webhookConfigured || nowMillis <= 0L) {
            return CheckAction.SKIP;
        }
        if (lastDigestEpoch <= 0L) {
            return CheckAction.INIT_TIMESTAMP_ONLY;
        }
        return CheckAction.COLLECT_AND_SEND;
    }

    /**
     * Returns {@code true} if {@code recordEpochMillis} is known ({@code > 0}) and falls in
     * the half-open digest window {@code (lastDigestEpoch, nowMillis]}.
     */
    public static boolean isInWindow(long recordEpochMillis, long lastDigestEpoch, long nowMillis) {
        return recordEpochMillis > 0L
                && recordEpochMillis > lastDigestEpoch
                && recordEpochMillis <= nowMillis;
    }

    /**
     * Resolves a player's display name for a Discord digest line per F47 rules 1 and 3
     * (without the {@code "(was ...)"} renamed suffix):
     * <ol>
     *   <li>If {@code PlayerData} exists and {@code lastKnownName} is non-empty, use {@code lastKnownName}.</li>
     *   <li>Otherwise, if {@code recordedName} is non-empty, use {@code recordedName}.</li>
     *   <li>Otherwise, fall back to {@code playerUuid.toString()}.</li>
     * </ol>
     */
    public static String resolvePlayerName(
            UUID playerUuid,
            String recordedName,
            boolean hasPlayerData,
            String lastKnownName) {
        if (hasPlayerData && lastKnownName != null && !lastKnownName.isEmpty()) {
            return lastKnownName;
        }
        if (recordedName != null && !recordedName.isEmpty()) {
            return recordedName;
        }
        return playerUuid != null ? playerUuid.toString() : "";
    }

    /**
     * Extracts qualifying digest entries in {@code (lastDigestEpoch, nowMillis]}, excluding
     * unknown-epoch ({@code 0}) records and F45-opted-out players, honoring {@code notifyScope}
     * ({@code "all"} or {@code "world_first"}), resolving names via F47 rules 1 &amp; 3, and
     * sorting by {@code epochMillis} ascending with deterministic tie-breakers.
     */
    public static List<DigestEntry> extractEntries(
            Collection<PlayerSnapshot> players,
            Collection<WorldFirstSnapshot> worldFirsts,
            long lastDigestEpoch,
            long nowMillis,
            String notifyScope,
            boolean allowDiscordOptOut) {
        if (lastDigestEpoch <= 0L || nowMillis <= lastDigestEpoch) {
            return List.of();
        }
        Map<UUID, PlayerSnapshot> playerByUuid = new HashMap<>();
        if (players != null) {
            for (PlayerSnapshot ps : players) {
                if (ps != null && ps.playerUuid() != null) {
                    playerByUuid.put(ps.playerUuid(), ps);
                }
            }
        }
        Map<TargetIdCount, WorldFirstSnapshot> worldFirstByTarget = new HashMap<>();
        if (worldFirsts != null) {
            for (WorldFirstSnapshot wf : worldFirsts) {
                if (wf != null && wf.itemId() != null && wf.playerUuid() != null) {
                    worldFirstByTarget.put(new TargetIdCount(wf.itemId(), wf.count()), wf);
                }
            }
        }

        List<DigestEntry> entries = new ArrayList<>();
        if ("world_first".equals(notifyScope)) {
            if (worldFirsts != null) {
                for (WorldFirstSnapshot wf : worldFirsts) {
                    if (wf == null || wf.itemId() == null || wf.playerUuid() == null) {
                        continue;
                    }
                    if (!isInWindow(wf.epochMillis(), lastDigestEpoch, nowMillis)) {
                        continue;
                    }
                    PlayerSnapshot holder = playerByUuid.get(wf.playerUuid());
                    if (!DiscordDeliveryPolicy.shouldPostToDiscord(
                            allowDiscordOptOut, holder != null && holder.excludeFromDiscord())) {
                        continue;
                    }
                    String name = resolvePlayerName(
                            wf.playerUuid(),
                            wf.recordedName(),
                            holder != null,
                            holder != null ? holder.lastKnownName() : null);
                    entries.add(new DigestEntry(
                            wf.playerUuid(),
                            name,
                            wf.itemId(),
                            wf.count(),
                            wf.playTicks(),
                            wf.day(),
                            wf.epochMillis(),
                            true));
                }
            }
        } else {
            Set<PlayerTargetKey> emitted = new HashSet<>();
            if (players != null) {
                for (PlayerSnapshot ps : players) {
                    if (ps == null || ps.playerUuid() == null) {
                        continue;
                    }
                    if (!DiscordDeliveryPolicy.shouldPostToDiscord(allowDiscordOptOut, ps.excludeFromDiscord())) {
                        continue;
                    }
                    if (ps.records() == null || ps.records().isEmpty()) {
                        continue;
                    }
                    for (RecordSnapshot rec : ps.records()) {
                        if (rec == null || rec.itemId() == null) {
                            continue;
                        }
                        if (!isInWindow(rec.epochMillis(), lastDigestEpoch, nowMillis)) {
                            continue;
                        }
                        WorldFirstSnapshot wf = worldFirstByTarget.get(new TargetIdCount(rec.itemId(), rec.count()));
                        boolean isWorldFirst = wf != null && ps.playerUuid().equals(wf.playerUuid());
                        String name = resolvePlayerName(
                                ps.playerUuid(),
                                isWorldFirst ? wf.recordedName() : null,
                                true,
                                ps.lastKnownName());
                        entries.add(new DigestEntry(
                                ps.playerUuid(),
                                name,
                                rec.itemId(),
                                rec.count(),
                                rec.playTicks(),
                                rec.day(),
                                rec.epochMillis(),
                                isWorldFirst));
                        emitted.add(new PlayerTargetKey(ps.playerUuid(), rec.itemId(), rec.count()));
                    }
                }
            }
            // Include world-firsts whose holder no longer has a matching player record (e.g. purged or reset)
            // so "all" scope remains a superset of "world_first" while still honoring F45 opt-out.
            if (worldFirsts != null) {
                for (WorldFirstSnapshot wf : worldFirsts) {
                    if (wf == null || wf.itemId() == null || wf.playerUuid() == null) {
                        continue;
                    }
                    if (!isInWindow(wf.epochMillis(), lastDigestEpoch, nowMillis)) {
                        continue;
                    }
                    if (emitted.contains(new PlayerTargetKey(wf.playerUuid(), wf.itemId(), wf.count()))) {
                        continue;
                    }
                    PlayerSnapshot holder = playerByUuid.get(wf.playerUuid());
                    if (!DiscordDeliveryPolicy.shouldPostToDiscord(
                            allowDiscordOptOut, holder != null && holder.excludeFromDiscord())) {
                        continue;
                    }
                    String name = resolvePlayerName(
                            wf.playerUuid(),
                            wf.recordedName(),
                            holder != null,
                            holder != null ? holder.lastKnownName() : null);
                    entries.add(new DigestEntry(
                            wf.playerUuid(),
                            name,
                            wf.itemId(),
                            wf.count(),
                            wf.playTicks(),
                            wf.day(),
                            wf.epochMillis(),
                            true));
                }
            }
        }

        entries.sort(DiscordDigestLogic::compareEntries);
        return List.copyOf(entries);
    }

    private static int compareEntries(DigestEntry a, DigestEntry b) {
        int c = Long.compare(a.epochMillis(), b.epochMillis());
        if (c != 0) {
            return c;
        }
        c = String.CASE_INSENSITIVE_ORDER.compare(a.playerName(), b.playerName());
        if (c != 0) {
            return c;
        }
        c = a.playerName().compareTo(b.playerName());
        if (c != 0) {
            return c;
        }
        c = a.playerUuid().compareTo(b.playerUuid());
        if (c != 0) {
            return c;
        }
        c = a.itemId().compareTo(b.itemId());
        return c != 0 ? c : Integer.compare(a.count(), b.count());
    }

    /**
     * Formats one digest line using {@code lineTemplate} ({@code "%s — %s (%s)"} or localized
     * equivalent), Markdown-escaping {@code playerName} and {@code itemLabel} like F4 while
     * keeping {@code formattedPlaytime} unescaped in English format, and prepending {@code "★ "}
     * when {@code worldFirst} is {@code true}.
     */
    public static String formatLine(
            String lineTemplate,
            String playerName,
            String itemLabel,
            String formattedPlaytime,
            boolean worldFirst) {
        String escapedName = TextEscaping.escapeDiscordMarkdown(playerName != null ? playerName : "");
        String escapedItem = TextEscaping.escapeDiscordMarkdown(itemLabel != null ? itemLabel : "");
        String safePlaytime = formattedPlaytime != null ? formattedPlaytime : "";
        String body = formatSafe(lineTemplate, DEFAULT_LINE_TEMPLATE, escapedName, escapedItem, safePlaytime);
        return worldFirst ? WORLD_FIRST_PREFIX + body : body;
    }

    /**
     * Assembles the plain-text Discord digest message (title line + up to {@value #MAX_LINES}
     * formatted lines + optional "more" line) within Discord's 2000-character limit.
     */
    public static String assemblePlainText(
            String title,
            List<String> formattedLines,
            IntFunction<String> moreLineFormatter) {
        return assemblePlainText(
                title, formattedLines, moreLineFormatter, MAX_LINES, NotificationGrouping.DISCORD_PLAIN_MAX_CHARS);
    }

    /** Configurable-limit overload of {@link #assemblePlainText(String, List, IntFunction)}. */
    public static String assemblePlainText(
            String title,
            List<String> formattedLines,
            IntFunction<String> moreLineFormatter,
            int maxLines,
            int maxChars) {
        return NotificationGrouping.assembleDiscordBody(
                title, formattedLines, moreLineFormatter, maxLines, maxChars);
    }

    /**
     * Assembles the Discord embed {@code description} (up to {@value #MAX_LINES} formatted lines
     * + optional "more" line) within Discord's 4096-character limit.
     */
    public static String assembleEmbedDescription(
            List<String> formattedLines,
            IntFunction<String> moreLineFormatter) {
        return assembleEmbedDescription(
                formattedLines, moreLineFormatter, MAX_LINES, NotificationGrouping.DISCORD_EMBED_DESCRIPTION_MAX_CHARS);
    }

    /** Configurable-limit overload of {@link #assembleEmbedDescription(List, IntFunction)}. */
    public static String assembleEmbedDescription(
            List<String> formattedLines,
            IntFunction<String> moreLineFormatter,
            int maxLines,
            int maxChars) {
        return NotificationGrouping.assembleDiscordBody(
                null, formattedLines, moreLineFormatter, maxLines, maxChars);
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
