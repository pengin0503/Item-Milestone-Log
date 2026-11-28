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
import java.util.UUID;

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
    /** Lower bound of a quantity target, matching the trees' {@code TargetKey.MIN_COUNT}. */
    public static final int MIN_COUNT = 1;
    /** Upper bound of a quantity target, matching the trees' {@code TargetKey.MAX_COUNT}. */
    public static final int MAX_COUNT = 1_000_000;
    /** Maximum number of comma-separated counts accepted by {@code addsteps} (F-150-17). */
    public static final int MAX_STEPS = 20;

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

    // ==========================================================================================
    // PR-2: read-only analytics (F-150-11/33/12/18/15/24/29/39/20)
    //
    // Everything below is Minecraft-free and purely derived from values handed in by each version
    // tree: no player data is written, no config is read and no time zone / wall clock is involved
    // unless the caller supplies an explicit nowMillis. Unknown epochs (0) are kept except where a
    // time window is explicitly being measured, in which case they are reported as excluded.
    // ==========================================================================================

    /** Maximum rows of the default compare view (D-150-38). */
    public static final int COMPARE_LIMIT = 200;
    /** Maximum rows of the compare route branch (F-150-33). */
    public static final int ROUTE_LIMIT = 50;
    /** Maximum rows of the compare rival branch (F-150-33). */
    public static final int RIVAL_LIMIT = 10;
    /** Milliseconds in an hour / day, for the analysis windows only. */
    public static final long HOUR_MILLIS = 3_600_000L;
    public static final long DAY_MILLIS = 86_400_000L;
    /** Maximum rows of the timeline without a page (F-150-12). */
    public static final int TIMELINE_LIMIT = 100;
    /** Maximum rows of the session window without a page (F-150-18). */
    public static final int SESSION_LIMIT = 200;
    /** Maximum rows of the pace view (F-150-15). */
    public static final int PACE_LIMIT = 15;
    /** Maximum namespaces shown by modstats (F-150-24). */
    public static final int MODSTATS_LIMIT = 100;
    /** Maximum presets shown by presetstats (F-150-39). */
    public static final int PRESETSTATS_LIMIT = 20;
    /** Maximum preset columns / rows of the preset matrix (F-150-20). */
    public static final int PRESETMATRIX_COLUMNS = 10;
    public static final int PRESETMATRIX_LIMIT = 200;

    // ---------------------------------------------------------- F-150-11 / F-150-33: compare

    /**
     * One comparison row. {@code diffTicks} is always non-negative; {@code inverted} is true when the
     * other player reached the key earlier (self took more playTicks) and needs an explicit label.
     */
    public record CompareRow(TargetRef key, int selfTicks, int otherTicks, long diffTicks, boolean inverted) {
    }

    /** Common / other-only / self-only rows in display order, with the single overall cap count. */
    public record CompareResult(List<CompareRow> common, List<CompareRow> onlyThem,
                                List<CompareRow> onlyYou, int moreCount) {
        public CompareResult {
            common = List.copyOf(common);
            onlyThem = List.copyOf(onlyThem);
            onlyYou = List.copyOf(onlyYou);
        }
    }

    /** Latest-by-key map: duplicate keys keep the smallest playTicks so results are input-order free. */
    private static Map<TargetRef, RecordValue> firstByKey(Collection<RecordValue> records) {
        Map<TargetRef, RecordValue> byKey = new LinkedHashMap<>();
        if (records == null) {
            return byKey;
        }
        for (RecordValue record : records) {
            if (record == null) {
                continue;
            }
            RecordValue existing = byKey.get(record.key());
            if (existing == null || record.playTicks() < existing.playTicks()) {
                byKey.put(record.key(), record);
            }
        }
        return byKey;
    }

    /** playTicks ascending, unknown epoch last (RankingStatsModel rule), then stable key order. */
    private static final Comparator<RecordValue> TICK_ORDER = (a, b) -> {
        int result = Integer.compare(a.playTicks(), b.playTicks());
        if (result != 0) {
            return result;
        }
        boolean knownA = a.epochMillis() > 0L;
        boolean knownB = b.epochMillis() > 0L;
        if (knownA != knownB) {
            return knownA ? -1 : 1;
        }
        if (knownA) {
            result = Long.compare(a.epochMillis(), b.epochMillis());
            if (result != 0) {
                return result;
            }
        }
        return compareTargets(a.key(), b.key());
    };

    private static List<RecordValue> sortedByTicks(Collection<RecordValue> records) {
        List<RecordValue> sorted = new ArrayList<>(records);
        sorted.sort(TICK_ORDER);
        return sorted;
    }

    /** compareNames rule without a UUID tie-break, used for namespaces and scoreboard team labels. */
    private static int compareLabels(String a, String b) {
        int result = String.CASE_INSENSITIVE_ORDER.compare(a, b);
        return result != 0 ? result : a.compareTo(b);
    }

    /** F-150-11: split two players' records into common / other-only / self-only, capped at 200. */
    public static CompareResult compare(Collection<RecordValue> a, Collection<RecordValue> b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        Map<TargetRef, RecordValue> self = firstByKey(a);
        Map<TargetRef, RecordValue> other = firstByKey(b);
        List<CompareRow> common = new ArrayList<>();
        for (RecordValue record : sortedByTicks(self.values())) {
            RecordValue theirs = other.get(record.key());
            if (theirs == null) {
                continue;
            }
            long diff = Math.abs((long) theirs.playTicks() - (long) record.playTicks());
            common.add(new CompareRow(record.key(), record.playTicks(), theirs.playTicks(), diff,
                    record.playTicks() > theirs.playTicks()));
        }
        List<CompareRow> onlyThem = new ArrayList<>();
        for (RecordValue record : sortedByTicks(other.values())) {
            if (!self.containsKey(record.key())) {
                onlyThem.add(new CompareRow(record.key(), 0, record.playTicks(), 0L, false));
            }
        }
        List<CompareRow> onlyYou = new ArrayList<>();
        for (RecordValue record : sortedByTicks(self.values())) {
            if (!other.containsKey(record.key())) {
                onlyYou.add(new CompareRow(record.key(), record.playTicks(), 0, 0L, false));
            }
        }
        List<CompareRow> ordered = new ArrayList<>(common.size() + onlyThem.size() + onlyYou.size());
        ordered.addAll(common);
        ordered.addAll(onlyThem);
        ordered.addAll(onlyYou);
        int kept = Math.min(ordered.size(), COMPARE_LIMIT);
        int commonKept = Math.min(common.size(), kept);
        int themKept = Math.min(onlyThem.size(), kept - commonKept);
        int youKept = kept - commonKept - themKept;
        return new CompareResult(ordered.subList(0, commonKept),
                ordered.subList(commonKept, commonKept + themKept),
                ordered.subList(commonKept + themKept, kept), ordered.size() - kept);
    }

    /** F-150-33 optional branch literal. */
    public enum CompareBranch {
        MISSING, ROUTE, RIVAL;

        /** Case-insensitive literal parse; {@code null} for unknown values. */
        public static CompareBranch parse(String raw) {
            if (raw == null) {
                return null;
            }
            for (CompareBranch branch : values()) {
                if (branch.name().equalsIgnoreCase(raw)) {
                    return branch;
                }
            }
            return null;
        }

        /** Command literal spelling (lower case). */
        public String commandValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One row of {@code compare route}; self/other ticks are always in that display order. */
    public record RouteEntry(TargetRef key, int selfTicks, int otherTicks, long diffTicks, boolean inverted) {
    }

    public record RouteResult(List<RouteEntry> entries, int moreCount) {
        public RouteResult {
            entries = List.copyOf(entries);
        }
    }

    /** One visible player considered by {@code compare rival}. */
    public record RivalPlayer(UUID uuid, String name, int recordCount) {
        public RivalPlayer {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(name, "name");
        }
    }

    public record RivalEntry(UUID uuid, String name, int recordCount, int gap) {
    }

    public record RivalResult(List<RivalEntry> entries, int moreCount) {
        public RivalResult {
            entries = List.copyOf(entries);
        }
    }

    public record MissingResult(List<CompareRow> entries, int moreCount) {
        public MissingResult {
            entries = List.copyOf(entries);
        }
    }

    /** Only the requested branch is filled; the other two are empty. */
    public record CompareBranchResult(MissingResult missing, RouteResult route, RivalResult rivals) {
    }

    /** F-150-33 {@code missing}: other-only keys, playTicks ascending, capped at 200. */
    public static MissingResult compareMissing(Collection<RecordValue> self, Collection<RecordValue> other) {
        Map<TargetRef, RecordValue> ours = firstByKey(self);
        List<CompareRow> rows = new ArrayList<>();
        for (RecordValue record : sortedByTicks(firstByKey(other).values())) {
            if (!ours.containsKey(record.key())) {
                rows.add(new CompareRow(record.key(), 0, record.playTicks(), 0L, false));
            }
        }
        int kept = Math.min(rows.size(), COMPARE_LIMIT);
        return new MissingResult(rows.subList(0, kept), rows.size() - kept);
    }

    /** F-150-33 {@code route}: common keys ordered by absolute playTicks difference, capped at 50. */
    public static RouteResult compareRoute(Collection<RecordValue> self, Collection<RecordValue> other) {
        List<RouteEntry> rows = new ArrayList<>();
        for (CompareRow row : compare(self, other).common()) {
            rows.add(new RouteEntry(row.key(), row.selfTicks(), row.otherTicks(),
                    row.diffTicks(), row.inverted()));
        }
        rows.sort((a, b) -> {
            int result = Long.compare(b.diffTicks(), a.diffTicks());
            return result != 0 ? result : compareTargets(a.key(), b.key());
        });
        int kept = Math.min(rows.size(), ROUTE_LIMIT);
        return new RouteResult(rows.subList(0, kept), rows.size() - kept);
    }

    /**
     * F-150-33 {@code rival}: visible players within +/-10 records of the compared player, capped at 10.
     * Both the compared player and the viewer are excluded so the compared player's own row is never
     * repeated.
     */
    public static RivalResult compareRivals(UUID otherUuid, UUID selfUuid, int otherCount,
                                            Collection<RivalPlayer> players) {
        List<RivalEntry> rows = new ArrayList<>();
        for (RivalPlayer player : players) {
            if (player.uuid().equals(otherUuid) || player.uuid().equals(selfUuid)) {
                continue;
            }
            int gap = player.recordCount() - otherCount;
            if (Math.abs(gap) > 10) {
                continue;
            }
            rows.add(new RivalEntry(player.uuid(), player.name(), player.recordCount(), Math.abs(gap)));
        }
        rows.sort((a, b) -> {
            int result = Integer.compare(a.gap(), b.gap());
            return result != 0 ? result : RankingStatsModel.compareNames(
                    a.name(), a.uuid(), b.name(), b.uuid());
        });
        int kept = Math.min(rows.size(), RIVAL_LIMIT);
        return new RivalResult(rows.subList(0, kept), rows.size() - kept);
    }

    /** Single entry point used by the tree: computes only the selected branch. */
    public static CompareBranchResult compareBranches(CompareBranch branch,
                                                      Collection<RecordValue> self,
                                                      Collection<RecordValue> other,
                                                      Collection<RivalPlayer> players,
                                                      UUID otherUuid, UUID selfUuid,
                                                      int otherRecordCount) {
        Objects.requireNonNull(branch, "branch");
        return switch (branch) {
            case MISSING -> new CompareBranchResult(compareMissing(self, other),
                    new RouteResult(List.of(), 0), new RivalResult(List.of(), 0));
            case ROUTE -> new CompareBranchResult(new MissingResult(List.of(), 0), compareRoute(self, other),
                    new RivalResult(List.of(), 0));
            case RIVAL -> new CompareBranchResult(new MissingResult(List.of(), 0),
                    new RouteResult(List.of(), 0),
                    compareRivals(otherUuid, selfUuid, otherRecordCount, players));
        };
    }

    // ---------------------------------------------------------------- F-150-12: timeline

    /** One timeline candidate supplied by the tree (already visibility-filtered). */
    public record TimelineHolder(UUID uuid, String name, int playTicks, long epochMillis) {
        public TimelineHolder {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(name, "name");
        }
    }

    public record TimelineEntry(int index, UUID uuid, String name, int playTicks,
                                long gapTicks, boolean worldFirst) {
    }

    /**
     * @param entries    visible players' rows with consecutive gaps, capped at {@link #TIMELINE_LIMIT}
     * @param worldFirst separate, explicitly labelled World First row (never mixed into the gaps)
     */
    public record TimelineResult(List<TimelineEntry> entries, int moreCount,
                                 TimelineEntry worldFirst) {
        public TimelineResult {
            entries = List.copyOf(entries);
        }
    }

    /** F-150-12: consecutive playTicks gaps; equal ticks give gap 0; unknown epochs sort last. */
    public static TimelineResult timeline(Collection<TimelineHolder> holders,
                                          TimelineHolder worldFirst, int limit) {
        Objects.requireNonNull(holders, "holders");
        List<TimelineHolder> sorted = new ArrayList<>(holders);
        sorted.sort((a, b) -> {
            int result = Integer.compare(a.playTicks(), b.playTicks());
            if (result != 0) {
                return result;
            }
            boolean knownA = a.epochMillis() > 0L;
            boolean knownB = b.epochMillis() > 0L;
            if (knownA != knownB) {
                return knownA ? -1 : 1;
            }
            if (knownA) {
                result = Long.compare(a.epochMillis(), b.epochMillis());
                if (result != 0) {
                    return result;
                }
            }
            return RankingStatsModel.compareNames(a.name(), a.uuid(), b.name(), b.uuid());
        });
        List<TimelineEntry> entries = new ArrayList<>();
        int previous = 0;
        for (TimelineHolder holder : sorted) {
            int index = entries.size() + 1;
            long gap = entries.isEmpty() ? 0L : Math.max(0L, (long) holder.playTicks() - previous);
            entries.add(new TimelineEntry(index, holder.uuid(), holder.name(), holder.playTicks(), gap, false));
            previous = holder.playTicks();
        }
        int kept = Math.min(entries.size(), Math.max(0, limit));
        TimelineEntry first = worldFirst == null ? null
                : new TimelineEntry(0, worldFirst.uuid(), worldFirst.name(), worldFirst.playTicks(), 0L, true);
        return new TimelineResult(entries.subList(0, kept), entries.size() - kept, first);
    }

    // ----------------------------------------------------------------- F-150-18: session

    /** Accepted session windows (D-150-51); {@code null} for anything else. */
    public static Integer parseSessionHours(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw) {
            case "1" -> 1;
            case "6" -> 6;
            case "24" -> 24;
            case "72" -> 72;
            default -> null;
        };
    }

    public record SessionResult(List<RecordValue> entries, int moreCount) {
        public SessionResult {
            entries = List.copyOf(entries);
        }
    }

    /**
     * F-150-18: records with a known epoch in {@code [now - hours, now]}; unknown epochs are excluded
     * because "recent" cannot be defined for them. Newest first, then stable key order.
     */
    public static SessionResult sessionWindow(Collection<RecordValue> records, long nowMillis, int hours) {
        Objects.requireNonNull(records, "records");
        long window = hours * HOUR_MILLIS;
        List<RecordValue> matched = new ArrayList<>();
        for (RecordValue record : records) {
            if (record.epochMillis() > 0L && record.epochMillis() <= nowMillis
                    && nowMillis - record.epochMillis() <= window) {
                matched.add(record);
            }
        }
        matched.sort((a, b) -> {
            int result = Long.compare(b.epochMillis(), a.epochMillis());
            return result != 0 ? result : compareTargets(a.key(), b.key());
        });
        int kept = Math.min(matched.size(), SESSION_LIMIT);
        return new SessionResult(matched.subList(0, kept), matched.size() - kept);
    }

    // -------------------------------------------------------------------- F-150-15: pace

    /** Opt-in {@code stats pace scope} values. */
    public enum PaceScope {
        ALL, TARGETS, PRESETS;

        public static PaceScope parse(String raw) {
            if (raw == null) {
                return null;
            }
            for (PaceScope scope : values()) {
                if (scope.name().equalsIgnoreCase(raw)) {
                    return scope;
                }
            }
            return null;
        }

        public String commandValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One player's already-counted record total and current playtime (no wall clock involved). */
    public record PacePlayer(UUID uuid, String name, int recordCount, long playTicks) {
        public PacePlayer {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * Competition rank on milestones/hour (floor); {@code milestonesPerHour < 0} means zero playtime
     * and is rendered as {@code pace_rate_none} instead of a number.
     */
    public record PaceEntry(int rank, UUID uuid, String name, int recordCount,
                            long playTicks, long milestonesPerHour) {
    }

    public record PaceResult(List<PaceEntry> entries, int moreCount, int playerCount) {
        public PaceResult {
            entries = List.copyOf(entries);
        }
    }

    /** F-150-15: records / elapsed playtime, competition rank, then compareNames tie-break. */
    public static PaceResult pace(Collection<PacePlayer> players, int limit) {
        Objects.requireNonNull(players, "players");
        List<PaceEntry> entries = new ArrayList<>();
        for (PacePlayer player : players) {
            long rate = player.playTicks() > 0L
                    ? (long) player.recordCount() * TICKS_PER_HOUR / player.playTicks()
                    : -1L;
            entries.add(new PaceEntry(0, player.uuid(), player.name(), player.recordCount(),
                    player.playTicks(), rate));
        }
        entries.sort((a, b) -> {
            boolean aNone = a.milestonesPerHour() < 0L;
            boolean bNone = b.milestonesPerHour() < 0L;
            if (aNone != bNone) {
                return aNone ? 1 : -1;
            }
            int result = Long.compare(b.milestonesPerHour(), a.milestonesPerHour());
            return result != 0 ? result : RankingStatsModel.compareNames(
                    a.name(), a.uuid(), b.name(), b.uuid());
        });
        int rank = 0;
        long previousRate = Long.MIN_VALUE;
        for (int i = 0; i < entries.size(); i++) {
            PaceEntry entry = entries.get(i);
            if (i == 0 || entry.milestonesPerHour() != previousRate) {
                rank = i + 1;
                previousRate = entry.milestonesPerHour();
            }
            entries.set(i, new PaceEntry(rank, entry.uuid(), entry.name(), entry.recordCount(),
                    entry.playTicks(), entry.milestonesPerHour()));
        }
        int kept = Math.min(entries.size(), Math.max(0, limit));
        return new PaceResult(entries.subList(0, kept), entries.size() - kept, entries.size());
    }

    /** The two counters of {@code stats pace scope targets/presets}. */
    public record PaceSummary(int recordedTargets, int totalTargets,
                              int completedPresets, int totalPresets) {
    }

    // ----------------------------------------------------------------- F-150-24: modstats

    /**
     * Namespace of an item/tag id: strip a leading {@code '#'}, then take the segment before the
     * first {@code ':'} (3-segment ids keep the first segment). Blank ids have no namespace.
     */
    public static String namespaceOf(String itemId) {
        if (itemId == null) {
            return null;
        }
        String id = itemId.startsWith("#") ? itemId.substring(1) : itemId;
        int colon = id.indexOf(':');
        String namespace = (colon >= 0 ? id.substring(0, colon) : id).trim();
        return namespace.isEmpty() ? null : namespace;
    }

    /** Distinct key counts per namespace; world-firsts are global and never visibility-filtered here. */
    public record NamespaceStats(String namespace, int targets, int records, int firsts) {
    }

    public record NamespaceResult(List<NamespaceStats> entries, int moreCount) {
        public NamespaceResult {
            entries = List.copyOf(entries);
        }
    }

    /** F-150-24: record count descending, then compareNames, capped at 100 namespaces. */
    public static NamespaceResult namespaceStats(Collection<TargetRef> targets,
                                                 Collection<TargetRef> records,
                                                 Collection<TargetRef> worldFirsts, int limit) {
        Map<String, Set<TargetRef>> targetSets = new LinkedHashMap<>();
        Map<String, Set<TargetRef>> recordSets = new LinkedHashMap<>();
        Map<String, Set<TargetRef>> firstSets = new LinkedHashMap<>();
        collectByNamespace(targets, targetSets);
        collectByNamespace(records, recordSets);
        collectByNamespace(worldFirsts, firstSets);
        Set<String> namespaces = new LinkedHashSet<>();
        namespaces.addAll(targetSets.keySet());
        namespaces.addAll(recordSets.keySet());
        namespaces.addAll(firstSets.keySet());
        List<NamespaceStats> rows = new ArrayList<>();
        for (String namespace : namespaces) {
            rows.add(new NamespaceStats(namespace,
                    targetSets.getOrDefault(namespace, Set.of()).size(),
                    recordSets.getOrDefault(namespace, Set.of()).size(),
                    firstSets.getOrDefault(namespace, Set.of()).size()));
        }
        rows.sort((a, b) -> {
            int result = Integer.compare(b.records(), a.records());
            return result != 0 ? result : compareLabels(a.namespace(), b.namespace());
        });
        int kept = Math.min(rows.size(), Math.max(0, limit));
        return new NamespaceResult(rows.subList(0, kept), rows.size() - kept);
    }

    private static void collectByNamespace(Collection<TargetRef> keys,
                                           Map<String, Set<TargetRef>> into) {
        if (keys == null) {
            return;
        }
        for (TargetRef key : keys) {
            String namespace = namespaceOf(key.itemId());
            if (namespace != null) {
                into.computeIfAbsent(namespace, ignored -> new LinkedHashSet<>()).add(key);
            }
        }
    }

    // ------------------------------------------------------------------ F-150-29: axis

    /** Opt-in ranking axis values; default {@code ALL} keeps the existing output byte-identical. */
    public enum RankingAxis {
        ALL, PERIOD, ONLINE, TEAM;

        public static RankingAxis parse(String raw) {
            if (raw == null) {
                return null;
            }
            for (RankingAxis axis : values()) {
                if (axis.name().equalsIgnoreCase(raw)) {
                    return axis;
                }
            }
            return null;
        }

        public String commandValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One visible player and the inputs needed by the four axes. */
    public record AxisPlayer(UUID uuid, String name, Collection<RecordValue> records,
                             boolean online, String team) {
        public AxisPlayer {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(name, "name");
            records = List.copyOf(records);
        }
    }

    public record AxisCountEntry(int rank, UUID uuid, String name, int count) {
    }

    /** Team representative = fastest playTicks in the team; unaffiliated players form one group. */
    public record AxisTeamEntry(int rank, String team, int playTicks, int playerCount) {
    }

    public record AxisResult(List<AxisCountEntry> entries, List<AxisTeamEntry> teams,
                             int skippedUnknown) {
        public AxisResult {
            entries = List.copyOf(entries);
            teams = List.copyOf(teams);
        }
    }

    /**
     * F-150-29: ALL/ONLINE/PERIOD use the existing competition-ranked record counts; TEAM groups by
     * scoreboard team and uses each team's fastest playTicks. Only PERIOD drops unknown epochs and
     * reports how many were excluded.
     */
    public static AxisResult rankingAxis(Collection<AxisPlayer> players, RankingAxis axis,
                                         int days, long nowMillis) {
        Objects.requireNonNull(players, "players");
        RankingAxis selected = axis == null ? RankingAxis.ALL : axis;
        List<AxisPlayer> population = new ArrayList<>();
        int skipped = 0;
        for (AxisPlayer player : players) {
            Collection<RecordValue> records = player.records();
            if (selected == RankingAxis.ONLINE && !player.online()) {
                continue;
            }
            if (selected == RankingAxis.PERIOD) {
                long cutoff = nowMillis - (long) days * DAY_MILLIS;
                List<RecordValue> inWindow = new ArrayList<>();
                for (RecordValue record : records) {
                    if (record.epochMillis() == 0L) {
                        skipped++;
                    } else if (record.epochMillis() >= cutoff) {
                        inWindow.add(record);
                    }
                }
                population.add(new AxisPlayer(player.uuid(), player.name(), inWindow,
                        player.online(), player.team()));
            } else {
                population.add(player);
            }
        }
        if (selected == RankingAxis.TEAM) {
            Map<String, List<AxisPlayer>> groups = new LinkedHashMap<>();
            for (AxisPlayer player : population) {
                String team = player.team() == null || player.team().isBlank() ? "" : player.team();
                groups.computeIfAbsent(team, ignored -> new ArrayList<>()).add(player);
            }
            List<AxisTeamEntry> teams = new ArrayList<>();
            for (Map.Entry<String, List<AxisPlayer>> group : groups.entrySet()) {
                int fastest = Integer.MAX_VALUE;
                for (AxisPlayer player : group.getValue()) {
                    for (RecordValue record : player.records()) {
                        fastest = Math.min(fastest, record.playTicks());
                    }
                }
                teams.add(new AxisTeamEntry(0, group.getKey().isEmpty() ? null : group.getKey(),
                        fastest == Integer.MAX_VALUE ? 0 : fastest, group.getValue().size()));
            }
            teams.sort((a, b) -> {
                int result = Integer.compare(a.playTicks(), b.playTicks());
                if (result != 0) {
                    return result;
                }
                String aName = a.team() == null ? "" : a.team();
                String bName = b.team() == null ? "" : b.team();
                return compareLabels(aName, bName);
            });
            for (int i = 0; i < teams.size(); i++) {
                AxisTeamEntry team = teams.get(i);
                int rank = i == 0 || team.playTicks() != teams.get(i - 1).playTicks() ? i + 1
                        : teams.get(i - 1).rank();
                teams.set(i, new AxisTeamEntry(rank, team.team(), team.playTicks(), team.playerCount()));
            }
            return new AxisResult(List.of(), teams, skipped);
        }
        List<AxisCountEntry> entries = new ArrayList<>();
        for (AxisPlayer player : population) {
            if (!player.records().isEmpty()) {
                entries.add(new AxisCountEntry(0, player.uuid(), player.name(), player.records().size()));
            }
        }
        entries.sort((a, b) -> {
            int result = Integer.compare(b.count(), a.count());
            return result != 0 ? result : RankingStatsModel.compareNames(
                    a.name(), a.uuid(), b.name(), b.uuid());
        });
        for (int i = 0; i < entries.size(); i++) {
            AxisCountEntry entry = entries.get(i);
            int rank = i == 0 || entry.count() != entries.get(i - 1).count() ? i + 1
                    : entries.get(i - 1).rank();
            entries.set(i, new AxisCountEntry(rank, entry.uuid(), entry.name(), entry.count()));
        }
        return new AxisResult(entries, List.of(), skipped);
    }

    // ------------------------------------------------------------- F-150-39: presetstats

    /** One visible player's completed preset IDs and records, supplied by the tree. */
    public record PresetStatsPlayer(UUID uuid, String name, Set<String> completedPresets,
                                    Collection<RecordValue> records) {
        public PresetStatsPlayer {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(name, "name");
            completedPresets = Set.copyOf(completedPresets);
            records = List.copyOf(records);
        }
    }

    /** Completion count over the visible population, in the caller's Presets.LIST_ORDER. */
    public record PresetPopularity(String presetId, int completed, int totalPlayers) {
    }

    public record PresetStatsResult(List<PresetPopularity> entries, int moreCount) {
        public PresetStatsResult {
            entries = List.copyOf(entries);
        }
    }

    /** Estimated completion time: median proxy ticks plus the number of finishers (0 -> render —). */
    public record PresetEstimate(int finishers, long medianTicks) {
    }

    /** F-150-39 popularity: completed count descending, then the caller-provided LIST_ORDER. */
    public static PresetStatsResult presetStats(Collection<PresetStatsPlayer> players,
                                                List<String> presetIdsInOrder, int limit) {
        Objects.requireNonNull(players, "players");
        Objects.requireNonNull(presetIdsInOrder, "presetIdsInOrder");
        List<PresetPopularity> rows = new ArrayList<>();
        for (String presetId : presetIdsInOrder) {
            int completed = 0;
            for (PresetStatsPlayer player : players) {
                if (player.completedPresets().contains(presetId)) {
                    completed++;
                }
            }
            rows.add(new PresetPopularity(presetId, completed, players.size()));
        }
        Map<String, Integer> order = new LinkedHashMap<>();
        for (int i = 0; i < presetIdsInOrder.size(); i++) {
            order.put(presetIdsInOrder.get(i), i);
        }
        rows.sort((a, b) -> {
            int result = Integer.compare(b.completed(), a.completed());
            return result != 0 ? result : Integer.compare(
                    order.getOrDefault(a.presetId(), Integer.MAX_VALUE),
                    order.getOrDefault(b.presetId(), Integer.MAX_VALUE));
        });
        int kept = Math.min(rows.size(), Math.max(0, limit));
        return new PresetStatsResult(rows.subList(0, kept), rows.size() - kept);
    }

    /**
     * F-150-39 estimate: completion time is not stored, so a finisher is a player who recorded every
     * preset key and their proxy time is the largest playTicks of those keys. The estimate is the
     * median of those proxies (even count: mean of the two middle values, as in itemTimes).
     */
    public static PresetEstimate presetPredict(Collection<PresetStatsPlayer> players,
                                                Collection<TargetRef> presetItems) {
        Objects.requireNonNull(players, "players");
        Objects.requireNonNull(presetItems, "presetItems");
        List<TargetRef> required = List.copyOf(presetItems);
        List<Long> finishes = new ArrayList<>();
        for (PresetStatsPlayer player : players) {
            Map<TargetRef, RecordValue> byKey = firstByKey(player.records());
            long last = -1L;
            boolean all = !required.isEmpty();
            for (TargetRef key : required) {
                RecordValue record = byKey.get(key);
                if (record == null) {
                    all = false;
                    break;
                }
                last = Math.max(last, record.playTicks());
            }
            if (all) {
                finishes.add(last);
            }
        }
        if (finishes.isEmpty()) {
            return new PresetEstimate(0, 0L);
        }
        finishes.sort(Long::compare);
        int middle = finishes.size() / 2;
        long median = finishes.size() % 2 == 1 ? finishes.get(middle)
                : (finishes.get(middle - 1) + finishes.get(middle)) / 2L;
        return new PresetEstimate(finishes.size(), median);
    }

    // ------------------------------------------------------------- F-150-20: presetmatrix

    /** One already-computed cell: recorded/total after includes expansion, plus completion flag. */
    public record MatrixValue(int recorded, int total, boolean completed) {
    }

    public record MatrixPlayer(UUID uuid, String name, Map<String, MatrixValue> progress) {
        public MatrixPlayer {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(name, "name");
            progress = Map.copyOf(progress);
        }
    }

    public record MatrixRow(UUID uuid, String name, List<MatrixValue> cells) {
    }

    public record PresetMatrixResult(List<MatrixRow> rows, int moreCount, int omittedPresets) {
        public PresetMatrixResult {
            rows = List.copyOf(rows);
        }
    }

    /**
     * F-150-20: rows are players (name order), columns are presets in the caller's LIST_ORDER and
     * truncated to 10. Completion is display-only: no completion timestamp exists or is returned.
     */
    public static PresetMatrixResult presetMatrix(Collection<MatrixPlayer> players,
                                                   List<String> presetIdsInOrder,
                                                   int maxColumns, int maxRows) {
        Objects.requireNonNull(players, "players");
        Objects.requireNonNull(presetIdsInOrder, "presetIdsInOrder");
        List<String> columns = presetIdsInOrder.subList(0, Math.min(
                Math.max(0, maxColumns), presetIdsInOrder.size()));
        List<MatrixPlayer> sorted = new ArrayList<>(players);
        sorted.sort((a, b) -> RankingStatsModel.compareNames(a.name(), a.uuid(), b.name(), b.uuid()));
        List<MatrixRow> rows = new ArrayList<>();
        MatrixValue empty = new MatrixValue(0, 0, false);
        for (MatrixPlayer player : sorted) {
            List<MatrixValue> cells = new ArrayList<>();
            for (String presetId : columns) {
                cells.add(player.progress().getOrDefault(presetId, empty));
            }
            rows.add(new MatrixRow(player.uuid(), player.name(), cells));
        }
        int kept = Math.min(rows.size(), Math.max(0, maxRows));
        return new PresetMatrixResult(rows.subList(0, kept), rows.size() - kept,
                presetIdsInOrder.size() - columns.size());
    }

    // ==========================================================================================
    // PR-3: shared set/cap math for the write commands
    // (F-150-13/14/37 copy+share, F-150-17 steps, F-150-30 bulk, F-150-26 import dry-run)
    //
    // Everything below is Minecraft-free: the caller supplies already-resolved keys and applies the
    // returned plan through its own MilestoneService calls. Nothing is stored, no config is read and
    // no registry lookup happens here. Item id validity is only "non-null and non-blank".
    // ==========================================================================================

    private static boolean isValidRef(TargetRef key) {
        return key != null && key.itemId() != null && !key.itemId().isBlank();
    }

    /** Non-null, blank-free keys, in iteration order (used for read-only membership checks). */
    private static Set<TargetRef> validKeySet(Collection<TargetRef> keys) {
        Set<TargetRef> unique = new LinkedHashSet<>();
        if (keys != null) {
            for (TargetRef key : keys) {
                if (isValidRef(key)) {
                    unique.add(key);
                }
            }
        }
        return unique;
    }

    /** The missing keys of a copy, the number of skipped source keys and the number to copy. */
    public record CopyPlan(List<TargetRef> missing, int skipped, int copied) {
        public CopyPlan {
            missing = List.copyOf(missing);
        }
    }

    /**
     * F-150-13/14/37: keys in {@code source} absent from {@code existing}, in source iteration order,
     * capped by the remaining capacity ({@code maxTargets - existing.size()}, never negative). The
     * existing keys, duplicate keys and capacity overflow are counted as skipped; nothing is mutated
     * and no record data is involved. The caller decides which keys belong in {@code source} (for
     * example, config defaults are excluded by the caller because everybody already has them).
     */
    public static CopyPlan copyTargets(Collection<TargetRef> existing, Collection<TargetRef> source,
                                       int maxTargets) {
        Set<TargetRef> known = validKeySet(existing);
        int existingSize = existing == null ? 0 : existing.size();
        int capacity = Math.max(0, maxTargets - existingSize);
        Set<TargetRef> added = new LinkedHashSet<>();
        List<TargetRef> missing = new ArrayList<>();
        int skipped = 0;
        if (source != null) {
            for (TargetRef key : source) {
                if (!isValidRef(key) || known.contains(key) || !added.add(key)
                        || missing.size() >= capacity) {
                    skipped++;
                } else {
                    missing.add(key);
                }
            }
        }
        return new CopyPlan(missing, skipped, missing.size());
    }

    /** F-150-37: share acceptance is exactly the F-150-13 copy calculation, with no duplicate logic. */
    public static CopyPlan sharePlan(Collection<TargetRef> existing, Collection<TargetRef> shared,
                                     int maxTargets) {
        return copyTargets(existing, shared, maxTargets);
    }

    /**
     * Strict comma-separated {@code addsteps} parse: 1..{@link #MAX_STEPS} counts, each an unsigned
     * decimal integer in {@link #MIN_COUNT}..{@link #MAX_COUNT}, strictly ascending. Signs, spaces,
     * decimals, empty entries and overflow are rejected with {@code null} rather than an exception.
     */
    public static List<Integer> parseSteps(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String[] parts = raw.split(",", -1);
        if (parts.length > MAX_STEPS) {
            return null;
        }
        List<Integer> values = new ArrayList<>(parts.length);
        int previous = Integer.MIN_VALUE;
        for (String part : parts) {
            if (part.isEmpty()) {
                return null;
            }
            long value = 0L;
            for (int i = 0; i < part.length(); i++) {
                char digit = part.charAt(i);
                if (digit < '0' || digit > '9') {
                    return null;
                }
                value = value * 10L + (digit - '0');
                if (value > MAX_COUNT) {
                    return null;
                }
            }
            int count = (int) value;
            if (count < MIN_COUNT || count > MAX_COUNT || count <= previous) {
                return null;
            }
            values.add(count);
            previous = count;
        }
        return List.copyOf(values);
    }

    /** New step keys for one caller-supplied item id (tags arrive as {@code "#tag"}). */
    public record StepsPlan(List<TargetRef> keys, int skipped, int invalid) {
        public StepsPlan {
            keys = List.copyOf(keys);
        }
    }

    /**
     * F-150-17: builds {@code TargetRef(itemId, count)} keys from already parsed counts, filters the
     * keys already present and applies exactly the {@link #copyTargets} capacity rule. Counts that
     * are null or outside the quantity range are reported as invalid (normally zero because
     * {@link #parseSteps} has already validated the input).
     */
    public static StepsPlan stepsFor(String itemId, Collection<Integer> counts,
                                     Collection<TargetRef> existing, int maxTargets) {
        boolean validId = itemId != null && !itemId.isBlank();
        List<TargetRef> candidates = new ArrayList<>();
        int invalid = 0;
        if (counts != null) {
            for (Integer count : counts) {
                if (!validId || count == null || count < MIN_COUNT || count > MAX_COUNT) {
                    invalid++;
                } else {
                    candidates.add(new TargetRef(itemId, count));
                }
            }
        }
        CopyPlan plan = copyTargets(existing, candidates, maxTargets);
        return new StepsPlan(plan.missing(), plan.skipped(), invalid);
    }

    /** F-150-30 {@code bulk preview}: the three actionable counts plus the tag capacity overflow. */
    public record BulkPlan(int stepTargets, int taggedItems, int completedTargets, int skipped) {
    }

    /**
     * Read-only preview over personal targets. {@code stepTargets} are quantity goals,
     * {@code completedTargets} are personal goals already present in {@code recordedKeys}, and
     * {@code taggedItems} are the new keys {@code expandtag} would add, capped by the same capacity
     * rule as copy/steps. Records and world firsts are never touched by this calculation.
     */
    public static BulkPlan bulkPlan(Collection<TargetRef> targets, Set<TargetRef> recordedKeys,
                                    Collection<TargetRef> tagItems, int maxTargets) {
        Set<TargetRef> recorded = recordedKeys == null ? Set.of() : recordedKeys;
        int stepTargets = 0;
        int completedTargets = 0;
        if (targets != null) {
            for (TargetRef key : targets) {
                if (!isValidRef(key)) {
                    continue;
                }
                if (key.count() > MIN_COUNT) {
                    stepTargets++;
                }
                if (recorded.contains(key)) {
                    completedTargets++;
                }
            }
        }
        CopyPlan tags = copyTargets(targets, tagItems, maxTargets);
        return new BulkPlan(stepTargets, tags.copied(), completedTargets, tags.skipped());
    }

    /** F-150-26 dry run: the four displayed counts, with no writes and no detail lines. */
    public record ImportPreview(int targetsToAdd, int recordsToOverwrite, int newWorldFirsts,
                                int skipped) {
    }

    /**
     * Pure four-number diff for {@code import} / {@code importall dryrun}. Target adds reuse the
     * copy capacity rule; a record is written when its key is absent locally or the imported
     * playTicks is strictly smaller than the stored one (SF-3, matching the real import); world
     * firsts are deduplicated. Invalid/null keys are skipped and included in {@code skipped}.
     * Every argument is read-only. The displayed label stays {@code recordsToOverwrite} and covers
     * both writes and adds.
     */
    public static ImportPreview importPreview(Collection<TargetRef> currentTargets,
                                              Collection<TargetRef> importedTargets,
                                              Map<TargetRef, Integer> currentRecords,
                                              Map<TargetRef, Integer> importedRecords,
                                              Collection<TargetRef> currentFirsts,
                                              Collection<TargetRef> importedFirsts,
                                              int maxTargets) {
        CopyPlan targets = copyTargets(currentTargets, importedTargets, maxTargets);
        int skipped = targets.skipped();
        int recordsToOverwrite = 0;
        if (importedRecords != null) {
            for (Map.Entry<TargetRef, Integer> entry : importedRecords.entrySet()) {
                TargetRef key = entry.getKey();
                if (!isValidRef(key)) {
                    skipped++;
                    continue;
                }
                Integer importedTicks = entry.getValue();
                if (importedTicks == null) {
                    continue;
                }
                // The real import writes a record when it is absent locally as well as when the
                // incoming playTicks is strictly smaller; the dry run must count both (SF-3).
                Integer currentTicks = currentRecords == null ? null : currentRecords.get(key);
                if (currentTicks == null || importedTicks < currentTicks) {
                    recordsToOverwrite++;
                }
            }
        }
        Set<TargetRef> knownFirsts = validKeySet(currentFirsts);
        Set<TargetRef> seenNew = new LinkedHashSet<>();
        int newWorldFirsts = 0;
        if (importedFirsts != null) {
            for (TargetRef key : importedFirsts) {
                if (!isValidRef(key)) {
                    skipped++;
                    continue;
                }
                if (knownFirsts.contains(key) || !seenNew.add(key)) {
                    continue;
                }
                newWorldFirsts++;
            }
        }
        return new ImportPreview(targets.copied(), recordsToOverwrite, newWorldFirsts, skipped);
    }
}
