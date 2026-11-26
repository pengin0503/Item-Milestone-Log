package com.pengin0503.itemmilestonelog;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure-Java command analysis for the 1.5.0 individual-command PR-1 (F-150-19/21/22/23/27/32/35/36,
 * D-150-100). One class in {@code common/src/main/java}, compiled into every version tree, keeps the
 * value records and the ordering/capping rules identical across all five Minecraft versions.
 *
 * <p>Minecraft-free, like {@link TargetFilterModel} and {@link RankingStatsModel}: the version trees
 * convert their own {@code TargetKey} / {@code MilestoneRecord} objects into the value records below,
 * call one of the methods and render the result with {@code ServerLang.tr}. Nothing is stored, no
 * config is read and no client data is produced. Unknown epochs ({@code epochMillis == 0}) are kept
 * in the population but explicitly separated wherever a time axis is involved.
 */
public final class CommandAnalysisModel {
    /** Maximum rows for {@code targets near} without a page (D-150-38/53). */
    public static final int NEAR_LIMIT = 200;
    /** Default {@code targets near} threshold (D-150-53). */
    public static final int DEFAULT_NEAR_THRESHOLD = 50;
    /** Default {@code trends days} window (D-150-86). */
    public static final int DEFAULT_TREND_DAYS = 30;
    /** Rows added by {@code info preview} before {@code info_tag_more} takes over. */
    public static final int INFO_PREVIEW_LIMIT = 10;
    /** Maximum rows for {@code recap return} and {@code recap daily}. */
    public static final int RECAP_LIMIT = 10;
    /** Window for {@code recap daily}. */
    public static final long RECAP_DAILY_MILLIS = 24L * 60L * 60L * 1000L;
    /** Minecraft ticks per hour, used only for the optional daily pace value. */
    public static final long TICKS_PER_HOUR = 20L * 60L * 60L;

    private CommandAnalysisModel() {
    }

    // ------------------------------------------------------------- shared value types

    /** {@code (itemId, count)} pair; tags carry a leading {@code '#'} just like the tree's TargetKey. */
    public record TargetRef(String itemId, int count) {
        public TargetRef {
            Objects.requireNonNull(itemId, "itemId");
        }

        public boolean isTag() {
            return itemId.startsWith("#");
        }
    }

    /** One stored record, reduced to the fields the PR-1 analyses use. */
    public record RecordValue(String itemId, int count, int playTicks, long day, long epochMillis) {
        public RecordValue {
            Objects.requireNonNull(itemId, "itemId");
        }

        public TargetRef key() {
            return new TargetRef(itemId, count);
        }
    }

    /** Stable key order, identical to {@code TargetKey.compareTo} in every version tree. */
    public static int compareTargets(TargetRef a, TargetRef b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        int result = a.itemId().compareTo(b.itemId());
        return result != 0 ? result : Integer.compare(a.count(), b.count());
    }

    /** compareNames rule: case-insensitive, then case-sensitive, then a stable key tie-break. */
    private static int compareNames(String a, String b) {
        int result = a.compareToIgnoreCase(b);
        return result != 0 ? result : a.compareTo(b);
    }

    // ------------------------------------------------------------- F-150-21 list sort / F-150-27 compact

    /** The three opt-in {@code list sort} values (D-150-57). */
    public enum RecordSort {
        RECENT,
        NAME,
        DAY;

        /** Case-insensitive command value; {@code null} for unknown/blank input. */
        public static RecordSort parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            for (RecordSort sort : values()) {
                if (sort.name().equalsIgnoreCase(raw)) {
                    return sort;
                }
            }
            return null;
        }

        public String commandValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Re-sorts the already filtered records. {@link RecordSort#RECENT} reproduces the existing
     * {@code playTicks} then {@code TargetKey} order exactly, so an explicit {@code sort recent}
     * cannot change legacy output. Unknown {@code day} values sort first (the literal
     * {@code MilestoneRecord.day} order); only {@code epochMillis} uses the "unknown last" rule.
     */
    public static List<RecordValue> sortRecords(Collection<RecordValue> records, RecordSort sort) {
        Objects.requireNonNull(records, "records");
        RecordSort effective = sort == null ? RecordSort.RECENT : sort;
        List<RecordValue> sorted = new ArrayList<>(records);
        Comparator<RecordValue> comparator = switch (effective) {
            case RECENT -> (a, b) -> {
                int result = Integer.compare(a.playTicks(), b.playTicks());
                return result != 0 ? result : compareTargets(a.key(), b.key());
            };
            case NAME -> (a, b) -> {
                int result = compareNames(a.itemId(), b.itemId());
                return result != 0 ? result : compareTargets(a.key(), b.key());
            };
            case DAY -> (a, b) -> {
                int result = Long.compare(a.day(), b.day());
                if (result != 0) {
                    return result;
                }
                result = Integer.compare(a.playTicks(), b.playTicks());
                return result != 0 ? result : compareTargets(a.key(), b.key());
            };
        };
        sorted.sort(comparator);
        return List.copyOf(sorted);
    }

    /**
     * The only logic shared by {@code targets compact} and {@code list compact}: a deduplicated,
     * stably ordered key set. Row rendering stays in the version trees (D-150-70).
     */
    public static List<TargetRef> compactKeys(Collection<TargetRef> keys) {
        Objects.requireNonNull(keys, "keys");
        Set<TargetRef> unique = new TreeSet<>(CommandAnalysisModel::compareTargets);
        for (TargetRef key : keys) {
            if (key != null) {
                unique.add(key);
            }
        }
        return List.copyOf(unique);
    }

    // ------------------------------------------------------------- F-150-22 settings

    /** The four personal toggles shown by {@code settings}. */
    public enum Toggle {
        BROADCAST,
        PROGRESS,
        DISCORD,
        PRIVACY;

        public static Toggle parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            for (Toggle toggle : values()) {
                if (toggle.name().equalsIgnoreCase(raw)) {
                    return toggle;
                }
            }
            return null;
        }

        public String commandValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One rendered settings line; {@code enabled} is the underlying legacy flag's value. */
    public record SettingLine(String name, boolean enabled) {
        public SettingLine {
            Objects.requireNonNull(name, "name");
        }
    }

    /** Reads the four existing flags without touching them. */
    public static List<SettingLine> settingsSnapshot(boolean muteBroadcasts, boolean muteProgressAlerts,
                                                     boolean excludeFromDiscord, boolean hideFromLookup) {
        return List.of(
                new SettingLine(Toggle.BROADCAST.commandValue(), muteBroadcasts),
                new SettingLine(Toggle.PROGRESS.commandValue(), muteProgressAlerts),
                new SettingLine(Toggle.DISCORD.commandValue(), excludeFromDiscord),
                new SettingLine(Toggle.PRIVACY.commandValue(), hideFromLookup));
    }

    /** {@code on|off} as the new value of a legacy flag; {@code null} for unknown words. */
    public static Boolean parseSettingValue(String raw) {
        if ("on".equalsIgnoreCase(raw)) {
            return Boolean.TRUE;
        }
        if ("off".equalsIgnoreCase(raw)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** {@code discord} follows the existing {@code discordoptout} config gate; the others do not. */
    public static boolean settingsToggleAllowed(Toggle toggle, boolean allowDiscordOptOut) {
        Objects.requireNonNull(toggle, "toggle");
        return toggle != Toggle.DISCORD || allowDiscordOptOut;
    }

    // ------------------------------------------------------------- F-150-19 targets near

    /** One effective target plus the possession count queried once by the tree. */
    public record NearCandidate(String itemId, int count, int current, boolean isTag) {
        public NearCandidate {
            Objects.requireNonNull(itemId, "itemId");
        }

        public TargetRef key() {
            return new TargetRef(itemId, count);
        }
    }

    /** A displayed near-complete row; {@code progressPercent} is integer floored. */
    public record NearEntry(String itemId, int count, int current, int progressPercent, boolean isTag) {
        public TargetRef key() {
            return new TargetRef(itemId, count);
        }
    }

    /** The first {@link #NEAR_LIMIT} rows plus the exact number of omitted rows. */
    public record NearResult(List<NearEntry> entries, int moreCount) {
        public NearResult {
            entries = List.copyOf(entries);
        }
    }

    /** Whether the threshold is in the spec's 1..99 range. */
    public static boolean isValidNearThreshold(int threshold) {
        return threshold >= 1 && threshold <= 99;
    }

    /**
     * Keeps unrecorded, non-creative-carried quantity targets at or above the threshold. Sorting is
     * progress descending then the stable key order; the cap is applied after sorting.
     */
    public static NearResult nearTargets(Collection<NearCandidate> candidates, int threshold) {
        Objects.requireNonNull(candidates, "candidates");
        if (!isValidNearThreshold(threshold)) {
            throw new IllegalArgumentException("threshold must be between 1 and 99: " + threshold);
        }
        List<NearEntry> all = new ArrayList<>();
        for (NearCandidate candidate : candidates) {
            if (candidate == null || candidate.count() <= 1 || candidate.current() < 0) {
                continue;
            }
            long progress = (long) candidate.current() * 100L / candidate.count();
            if (progress < threshold || progress > 100L) {
                continue;
            }
            all.add(new NearEntry(candidate.itemId(), candidate.count(), candidate.current(),
                    (int) progress, candidate.isTag()));
        }
        all.sort((a, b) -> {
            int result = Integer.compare(b.progressPercent(), a.progressPercent());
            return result != 0 ? result : compareTargets(a.key(), b.key());
        });
        int kept = Math.min(all.size(), NEAR_LIMIT);
        return new NearResult(all.subList(0, kept), all.size() - kept);
    }

    // ------------------------------------------------------------- F-150-23 randomtarget

    /** Item / tag / both candidate selection. */
    public enum TargetKind {
        ITEM,
        TAG,
        BOTH;

        public static TargetKind parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            for (TargetKind kind : values()) {
                if (kind.name().equalsIgnoreCase(raw)) {
                    return kind;
                }
            }
            return null;
        }

        public String commandValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Unrecorded effective targets minus creative-carried items, narrowed by {@code kind}. Tag keys
     * are never excluded by the carried set (the existing tag aggregation already handles it).
     */
    public static List<TargetRef> randomTargetCandidates(Collection<TargetRef> targets,
                                                         Collection<TargetRef> recorded,
                                                         Collection<String> carried,
                                                         TargetKind kind) {
        Objects.requireNonNull(targets, "targets");
        Objects.requireNonNull(recorded, "recorded");
        Objects.requireNonNull(carried, "carried");
        TargetKind effective = kind == null ? TargetKind.BOTH : kind;
        Set<TargetRef> recordedSet = new LinkedHashSet<>(recorded);
        Set<String> carriedSet = new LinkedHashSet<>(carried);
        List<TargetRef> candidates = new ArrayList<>();
        for (TargetRef key : targets) {
            if (key == null || recordedSet.contains(key)) {
                continue;
            }
            if (!key.isTag() && carriedSet.contains(key.itemId())) {
                continue;
            }
            if ((effective == TargetKind.ITEM && key.isTag())
                    || (effective == TargetKind.TAG && !key.isTag())) {
                continue;
            }
            candidates.add(key);
        }
        candidates.sort(CommandAnalysisModel::compareTargets);
        return List.copyOf(candidates);
    }

    /** Uniform pick from the supplied candidates, or {@code null} when there are none. */
    public static TargetRef pickRandomTarget(List<TargetRef> candidates, Random random) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(random, "random");
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(random.nextInt(candidates.size()));
    }

    // ------------------------------------------------------------- F-150-32 info preview

    /** One tag's own status as a token the tree maps back to the existing {@code info_status_*} rows. */
    public record PreviewState(String statusKey, int current, int goal, long playTicks) {
        public PreviewState {
            Objects.requireNonNull(statusKey, "statusKey");
        }
    }

    /** A deduplicated preview row. */
    public record PreviewTag(TargetRef key, String statusKey, int current, int goal, long playTicks) {
        public PreviewTag {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(statusKey, "statusKey");
        }
    }

    /** The first {@link #INFO_PREVIEW_LIMIT} unique rows plus their overflow count. */
    public record PreviewResult(List<PreviewTag> entries, int moreCount) {
        public PreviewResult {
            entries = List.copyOf(entries);
        }
    }

    /**
     * Keeps the existing related-tag order, removes duplicate keys and caps at ten rows. The tree
     * renders every row with the same display names/status components as the existing info rows.
     */
    public static PreviewResult infoPreview(Collection<TargetRef> relatedTags,
                                            Map<TargetRef, PreviewState> states) {
        Objects.requireNonNull(relatedTags, "relatedTags");
        Objects.requireNonNull(states, "states");
        Map<TargetRef, PreviewTag> unique = new LinkedHashMap<>();
        for (TargetRef key : relatedTags) {
            if (key == null || unique.containsKey(key)) {
                continue;
            }
            PreviewState state = states.get(key);
            if (state == null) {
                continue;
            }
            unique.put(key, new PreviewTag(key, state.statusKey(), state.current(),
                    state.goal(), state.playTicks()));
        }
        List<PreviewTag> all = new ArrayList<>(unique.values());
        int kept = Math.min(all.size(), INFO_PREVIEW_LIMIT);
        return new PreviewResult(all.subList(0, kept), all.size() - kept);
    }

    // ------------------------------------------------------------- F-150-35 trends

    /** One day bucket; {@code level} is 0..4 and {@code milestonesPerHour} may be 0. */
    public record TrendDay(LocalDate date, int count, int level, double milestonesPerHour) {
        public TrendDay {
            Objects.requireNonNull(date, "date");
        }
    }

    /** Daily buckets, the current streak, and the isolated unknown-date count. */
    public record TrendsResult(List<TrendDay> days, int streak, int unknownDateCount, int datedInWindow) {
        public TrendsResult {
            days = List.copyOf(days);
        }
    }

    /**
     * Aggregates dated records into the requested window. {@code epochMillis == 0} rows are counted
     * separately and never placed on the time axis. A streak ending today still counts when today
     * has no records yet (it is closed at yesterday instead of being broken).
     */
    public static TrendsResult trends(Collection<RecordValue> records, ZoneId zone,
                                      int days, long nowMillis) {
        Objects.requireNonNull(records, "records");
        Objects.requireNonNull(zone, "zone");
        if (days != 7 && days != 30 && days != 90) {
            throw new IllegalArgumentException("days must be 7, 30 or 90: " + days);
        }
        LocalDate today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate();
        LocalDate first = today.minusDays(days - 1L);
        Map<LocalDate, List<RecordValue>> byDay = new LinkedHashMap<>();
        int unknown = 0;
        for (RecordValue record : records) {
            if (record == null) {
                continue;
            }
            if (record.epochMillis() <= 0L) {
                unknown++;
                continue;
            }
            LocalDate date = Instant.ofEpochMilli(record.epochMillis()).atZone(zone).toLocalDate();
            if (date.isBefore(first) || date.isAfter(today)) {
                continue;
            }
            byDay.computeIfAbsent(date, ignored -> new ArrayList<>()).add(record);
        }
        int maxCount = 0;
        for (List<RecordValue> bucket : byDay.values()) {
            maxCount = Math.max(maxCount, bucket.size());
        }
        List<TrendDay> result = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            LocalDate date = first.plusDays(i);
            List<RecordValue> bucket = byDay.getOrDefault(date, List.of());
            int count = bucket.size();
            int level = count == 0 || maxCount == 0
                    ? 0
                    : 1 + (int) ((3L * count) / maxCount);
            long minTicks = Long.MAX_VALUE;
            long maxTicks = Long.MIN_VALUE;
            for (RecordValue record : bucket) {
                minTicks = Math.min(minTicks, record.playTicks());
                maxTicks = Math.max(maxTicks, record.playTicks());
            }
            double rate = bucket.isEmpty() || maxTicks <= minTicks
                    ? 0.0
                    : (double) count * (double) TICKS_PER_HOUR / (double) (maxTicks - minTicks);
            result.add(new TrendDay(date, count, level, rate));
        }
        int cursor = today.equals(result.get(result.size() - 1).date())
                && result.get(result.size() - 1).count() > 0 ? days - 1 : days - 2;
        int streak = 0;
        while (cursor >= 0 && result.get(cursor).count() > 0) {
            streak++;
            cursor--;
        }
        int dated = 0;
        for (TrendDay day : result) {
            dated += day.count();
        }
        return new TrendsResult(result, streak, unknown, dated);
    }

    // ------------------------------------------------------------- F-150-36 recap

    /** One {@code recap} kind. */
    public enum RecapKind {
        RETURN,
        DAILY,
        LEGACY;

        public static RecapKind parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            for (RecapKind kind : values()) {
                if (kind.name().equalsIgnoreCase(raw)) {
                    return kind;
                }
            }
            return null;
        }

        public String commandValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One world first after {@code lastSeen}, converted from the existing login-summary result. */
    public record ReturnCandidate(String itemId, int count, String holderName, long epochMillis) {
        public ReturnCandidate {
            Objects.requireNonNull(itemId, "itemId");
            Objects.requireNonNull(holderName, "holderName");
        }

        public TargetRef key() {
            return new TargetRef(itemId, count);
        }
    }

    /** One rendered recap line; the tree supplies display names and time formatting. */
    public record RecapEntry(String itemId, int count, String holderName, long epochMillis) {
        public RecapEntry {
            Objects.requireNonNull(itemId, "itemId");
            Objects.requireNonNull(holderName, "holderName");
        }

        public TargetRef key() {
            return new TargetRef(itemId, count);
        }
    }

    /** Entries plus the omitted count; legacy additionally reports the two counts it displays. */
    public record RecapResult(List<RecapEntry> entries, int moreCount, int unknownDateCount,
                              int stillTrackedCount) {
        public RecapResult {
            entries = List.copyOf(entries);
        }
    }

    /** {@code recap return}: newest first, stable key order for ties, capped at ten rows. */
    public static RecapResult recapReturn(Collection<ReturnCandidate> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        List<ReturnCandidate> sorted = new ArrayList<>(candidates);
        sorted.removeIf(Objects::isNull);
        sorted.sort((a, b) -> {
            int result = Long.compare(b.epochMillis(), a.epochMillis());
            return result != 0 ? result : compareTargets(a.key(), b.key());
        });
        return capRecap(sorted.stream()
                .map(c -> new RecapEntry(c.itemId(), c.count(), c.holderName(), c.epochMillis()))
                .toList());
    }

    /** {@code recap daily}: records inside the last 24 hours; unknown dates are left to legacy. */
    public static RecapResult recapDaily(Collection<RecordValue> records, long nowMillis) {
        Objects.requireNonNull(records, "records");
        List<RecapEntry> entries = new ArrayList<>();
        for (RecordValue record : records) {
            if (record == null || record.epochMillis() <= 0L
                    || record.epochMillis() > nowMillis
                    || nowMillis - record.epochMillis() > RECAP_DAILY_MILLIS) {
                continue;
            }
            entries.add(new RecapEntry(record.itemId(), record.count(), "", record.epochMillis()));
        }
        entries.sort((a, b) -> {
            int result = Long.compare(b.epochMillis(), a.epochMillis());
            return result != 0 ? result : compareTargets(a.key(), b.key());
        });
        return capRecap(entries);
    }

    /** {@code recap legacy}: the two counts are displayed as-is; dates are never guessed. */
    public static RecapResult recapLegacy(int unknownDateCount, int stillTrackedCount) {
        return new RecapResult(List.of(), 0, Math.max(0, unknownDateCount),
                Math.max(0, stillTrackedCount));
    }

    private static RecapResult capRecap(List<RecapEntry> entries) {
        int kept = Math.min(entries.size(), RECAP_LIMIT);
        return new RecapResult(entries.subList(0, kept), entries.size() - kept, 0, 0);
    }
}
