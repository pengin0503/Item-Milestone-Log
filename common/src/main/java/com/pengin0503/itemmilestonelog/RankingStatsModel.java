package com.pengin0503.itemmilestonelog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Pure-Java aggregation for the 1.5.0 ranking queries (D-150-34): per-preset rankings (N03),
 * world-first holder counts (N04), reachable unclaimed world firsts (N05), item completion-time
 * statistics (N49), visible-ranking gaps (N91) and recorded/targeting player lists (N92).
 *
 * <p>Minecraft-free and Java 17 compatible, like {@link TargetFilterModel}: the version trees
 * convert their own {@code TargetKey} / {@code MilestoneRecord} / {@code WorldFirstRecord}
 * objects into the value records below, and render the returned rows with {@code ServerLang.tr}.
 * Ranks use competition ranking (1, 2, 2, 4) on the primary axis only; the remaining sort keys
 * only establish display order. An epoch of {@code 0} means "unknown" and sorts last (never
 * removes a row). Nothing is cached; callers run the model per command execution.
 */
public final class RankingStatsModel {
    /** Maximum number of unclaimed rows returned before {@link UnclaimedResult#moreCount()} takes over. */
    public static final int UNCLAIMED_RESULT_LIMIT = 200;

    private static final Comparator<TargetRef> TARGET_ORDER = Comparator
            .comparing(TargetRef::itemId)
            .thenComparingInt(TargetRef::count);

    private RankingStatsModel() {
    }

    // ------------------------------------------------------------- shared value types

    /** {@code (itemId, count)} pair; the tree side's {@code TargetKey} without Minecraft dependencies. */
    public record TargetRef(String itemId, int count) {
        public TargetRef {
            Objects.requireNonNull(itemId, "itemId");
        }
    }

    /** One saved record from a player, reduced to the fields the ranking axes use. */
    public record RecordValue(String itemId, int count, int playTicks, long epochMillis) {
        public RecordValue {
            Objects.requireNonNull(itemId, "itemId");
        }
    }

    /** One candidate player for a preset ranking; {@code records} may include keys outside the preset. */
    public record PresetPlayer(UUID uuid, String name, Collection<RecordValue> records, boolean completed) {
        public PresetPlayer {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(name, "name");
            records = List.copyOf(records);
        }
    }

    /** A ranked preset row (D-150-15: no completion time is stored, so {@code completed} is display-only). */
    public record PresetCandidate(String presetId, UUID uuid, String name, int recorded, int total,
                                  int lastPlayTicks, long lastEpoch, boolean completed) {
    }

    /** Competition rank plus its preset row. */
    public record PresetRankEntry(int rank, PresetCandidate candidate) {
    }

    /** One world-first record as held by a player, used for N04's per-holder grouping. */
    public record WorldFirstValue(UUID uuid, String name, int playTicks, long epochMillis) {
        public WorldFirstValue {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(name, "name");
        }
    }

    /** One world-first holder row: count of distinct keys, earliest epoch and its earliest playTicks. */
    public record FirstCountEntry(int rank, UUID uuid, String name, int count,
                                  long earliestEpoch, int earliestTicks) {
    }

    /** Explicit source of an unclaimed candidate: never inferred from a key's item ID. */
    public enum UnclaimedScope {
        TARGETS,
        PRESETS,
        ALL;

        /** Case-insensitive command value; returns {@code null} for unknown/blank input. */
        public static UnclaimedScope parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            for (UnclaimedScope scope : values()) {
                if (scope.name().equalsIgnoreCase(raw)) {
                    return scope;
                }
            }
            return null;
        }

        /** Lower-case value written to {@code /milestonelog firsts unclaimed scope <value>}. */
        public String commandValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One preset and its includes-expanded item keys. */
    public record PresetRef(String id, Collection<TargetRef> items) {
        public PresetRef {
            Objects.requireNonNull(id, "id");
            items = List.copyOf(items);
        }
    }

    /** An unclaimed candidate; {@code presetId} is only set for {@code source == "preset"}. */
    public record UnclaimedEntry(String source, String itemId, int count, String presetId) {
        public UnclaimedEntry {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(itemId, "itemId");
        }

        public boolean fromPreset() {
            return "preset".equals(source);
        }
    }

    /** The first {@link #UNCLAIMED_RESULT_LIMIT} candidates plus the exact omitted count. */
    public record UnclaimedResult(List<UnclaimedEntry> entries, int moreCount) {
        public UnclaimedResult {
            entries = List.copyOf(entries);
        }
    }

    // ------------------------------------------------------------- N49 item time statistics

    /**
     * Completion-time aggregate for one target key. An empty population yields {@code count == 0}
     * and zeroed values so the tree can select its own empty message (D-150-22/24).
     */
    public record ItemTimeStats(int count, long meanTicks, long medianTicks, long minTicks, long maxTicks) {
    }

    /**
     * Statistics over the played-time ticks of every visible holder of a key. Unknown epochs do not
     * remove a record: only {@code playTicks} is used here. All intermediate maths is {@code long}.
     */
    public static ItemTimeStats itemTimes(List<Integer> playTicks) {
        Objects.requireNonNull(playTicks, "playTicks");
        List<Integer> sorted = new ArrayList<>(playTicks.size());
        for (Integer ticks : playTicks) {
            if (ticks != null) {
                sorted.add(ticks);
            }
        }
        if (sorted.isEmpty()) {
            return new ItemTimeStats(0, 0L, 0L, 0L, 0L);
        }
        sorted.sort(Integer::compareTo);
        long sum = 0L;
        for (int ticks : sorted) {
            sum += ticks;
        }
        int middle = sorted.size() / 2;
        long median = sorted.size() % 2 == 1
                ? sorted.get(middle).longValue()
                : (sorted.get(middle - 1).longValue() + sorted.get(middle).longValue()) / 2L;
        return new ItemTimeStats(sorted.size(), sum / sorted.size(), median,
                sorted.get(0).longValue(), sorted.get(sorted.size() - 1).longValue());
    }

    // ------------------------------------------------------------- N91 visible-ranking gaps

    /** One visible item-ranking row reduced to the fields used for gap calculation (D-150-26). */
    public record GapRow(int rank, UUID uuid, String name, int playTicks) {
    }

    /**
     * Gaps for one row: ticks to the preceding visible row and to the first visible row.
     * {@code -1} means "unavailable" (first row, or no self row) and is rendered as {@code gap_none}.
     * Epoch milliseconds are deliberately absent so unknown epochs cannot skew a gap.
     */
    public record TimeGap(long aboveTicks, long firstTicks) {
        /** The sentinel returned for the first row and for an unranked viewer. */
        public static TimeGap none() {
            return new TimeGap(-1L, -1L);
        }

        public boolean hasAbove() {
            return aboveTicks >= 0L;
        }

        public boolean hasFirst() {
            return firstTicks >= 0L;
        }
    }

    /** One gap per supplied row plus the gap for the viewer's own row. */
    public record GapResult(List<TimeGap> rows, TimeGap self) {
        public GapResult {
            rows = List.copyOf(rows);
            self = Objects.requireNonNull(self, "self");
        }
    }

    /**
     * Computes non-negative gaps over the <em>already visibility-filtered</em> ranking passed in by
     * the tree; a hidden player's time never enters this calculation. {@code selfIndex} is the
     * viewer's position in that full ranking (or {@code -1} when the viewer has no row) so a viewer
     * outside the displayed top rows still gets the gap to their real predecessor.
     */
    public static GapResult gapsFor(List<GapRow> rows, int selfIndex) {
        Objects.requireNonNull(rows, "rows");
        List<TimeGap> gaps = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            if (i == 0) {
                gaps.add(TimeGap.none());
            } else {
                int ticks = rows.get(i).playTicks();
                gaps.add(new TimeGap(nonNegativeGap(ticks, rows.get(i - 1).playTicks()),
                        nonNegativeGap(ticks, rows.get(0).playTicks())));
            }
        }
        TimeGap self = selfIndex >= 0 && selfIndex < rows.size() ? gaps.get(selfIndex) : TimeGap.none();
        return new GapResult(gaps, self);
    }

    private static long nonNegativeGap(int ticks, int baseTicks) {
        return Math.max(0L, (long) ticks - (long) baseTicks);
    }

    // ------------------------------------------------------------- N92 whohas

    /** One visibility-filtered player, with only the records and effective targets needed for N92. */
    public record WhoHasPlayer(UUID uuid, String name, Collection<RecordValue> records,
                               Collection<TargetRef> targets) {
        public WhoHasPlayer {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(name, "name");
            records = List.copyOf(records);
            targets = List.copyOf(targets);
        }
    }

    /** A recorded row (with competition rank) or a targeting row ({@code rank == 0}). */
    public record WhoHasEntry(boolean recorded, int rank, UUID uuid, String name, int playTicks) {
    }

    /**
     * Recorded rows first, then targeting rows, capped at {@link #UNCLAIMED_RESULT_LIMIT}.
     * The counts describe the uncapped population so the chat header stays accurate.
     */
    public record WhoHasResult(List<WhoHasEntry> entries, int recordedCount, int targetingCount,
                               int moreCount) {
        public WhoHasResult {
            entries = List.copyOf(entries);
        }
    }

    /**
     * Derives the flat "recorded then targeting" list for one key from already visibility-filtered
     * players. Recorded rows reuse the {@code topByItem} order (ticks asc, unknown epoch last,
     * name/UUID) and competition ranks; targeting rows are name-ordered. Raw possession counts and
     * notification thresholds are never read (D-150-27).
     */
    public static WhoHasResult whoHas(Collection<WhoHasPlayer> players, TargetRef key) {
        Objects.requireNonNull(players, "players");
        Objects.requireNonNull(key, "key");
        record RecordedRow(UUID uuid, String name, int playTicks, long epoch) {
        }
        List<RecordedRow> recordedRows = new ArrayList<>();
        List<WhoHasPlayer> targetingPlayers = new ArrayList<>();
        for (WhoHasPlayer player : players) {
            if (player == null) {
                continue;
            }
            RecordedRow recordedRow = null;
            for (RecordValue record : player.records()) {
                if (record.itemId().equals(key.itemId()) && record.count() == key.count()) {
                    recordedRow = new RecordedRow(player.uuid(), player.name(),
                            record.playTicks(), record.epochMillis());
                    break;
                }
            }
            if (recordedRow != null) {
                recordedRows.add(recordedRow);
            } else if (player.targets().contains(key)) {
                targetingPlayers.add(player);
            }
        }
        recordedRows.sort((a, b) -> {
            int c = Integer.compare(a.playTicks(), b.playTicks());
            if (c != 0) {
                return c;
            }
            c = compareEpochUnknownLast(a.epoch(), b.epoch());
            return c != 0 ? c : compareNames(a.name(), a.uuid(), b.name(), b.uuid());
        });
        List<WhoHasEntry> all = new ArrayList<>(recordedRows.size() + targetingPlayers.size());
        int rank = 0;
        for (int i = 0; i < recordedRows.size(); i++) {
            RecordedRow row = recordedRows.get(i);
            if (i == 0 || row.playTicks() != recordedRows.get(i - 1).playTicks()) {
                rank = i + 1;
            }
            all.add(new WhoHasEntry(true, rank, row.uuid(), row.name(), row.playTicks()));
        }
        targetingPlayers.sort((a, b) -> compareNames(a.name(), a.uuid(), b.name(), b.uuid()));
        for (WhoHasPlayer player : targetingPlayers) {
            all.add(new WhoHasEntry(false, 0, player.uuid(), player.name(), 0));
        }
        int recordedCount = recordedRows.size();
        int kept = Math.min(all.size(), UNCLAIMED_RESULT_LIMIT);
        return new WhoHasResult(all.subList(0, kept), recordedCount,
                all.size() - recordedCount, all.size() - kept);
    }

    // ------------------------------------------------------------- shared ordering

    /** Case-insensitive dictionary order, then case-sensitive, then UUID (as in {@code MilestoneRankings}). */
    public static int compareNames(String a, UUID ua, String b, UUID ub) {
        int c = String.CASE_INSENSITIVE_ORDER.compare(a, b);
        if (c != 0) {
            return c;
        }
        c = a.compareTo(b);
        return c != 0 ? c : ua.compareTo(ub);
    }

    /** Epoch {@code 0} means "unknown" and always sorts after a known timestamp. */
    public static int compareEpochUnknownLast(long a, long b) {
        boolean knownA = a > 0L;
        boolean knownB = b > 0L;
        if (knownA != knownB) {
            return knownA ? -1 : 1;
        }
        return knownA ? Long.compare(a, b) : 0;
    }

    /** Same order as {@code TargetKey.compareTo}: item ID, then count. */
    public static int compareTargets(TargetRef a, TargetRef b) {
        return TARGET_ORDER.compare(a, b);
    }

    // ------------------------------------------------------------- N03 preset ranking

    /**
     * Ranks players with at least one recorded key from {@code presetTargets}.
     * Axis order: recorded count desc -&gt; max playTicks asc -&gt; max epoch asc (0 unknown last)
     * -&gt; name -&gt; UUID. Ranks are assigned on the recorded count only (competition ranking).
     */
    public static List<PresetRankEntry> presetRanking(String presetId, Collection<TargetRef> presetTargets,
                                                      Collection<PresetPlayer> players) {
        Objects.requireNonNull(presetId, "presetId");
        Objects.requireNonNull(presetTargets, "presetTargets");
        Objects.requireNonNull(players, "players");
        Set<TargetRef> keys = new HashSet<>(presetTargets);
        List<PresetCandidate> rows = new ArrayList<>();
        for (PresetPlayer player : players) {
            if (player == null) {
                continue;
            }
            int recorded = 0;
            int lastPlayTicks = 0;
            long lastEpoch = 0L;
            for (RecordValue record : player.records()) {
                if (!keys.contains(new TargetRef(record.itemId(), record.count()))) {
                    continue;
                }
                recorded++;
                lastPlayTicks = Math.max(lastPlayTicks, record.playTicks());
                lastEpoch = Math.max(lastEpoch, record.epochMillis());
            }
            if (recorded == 0) {
                continue;
            }
            rows.add(new PresetCandidate(presetId, player.uuid(), player.name(), recorded, keys.size(),
                    lastPlayTicks, lastEpoch, player.completed()));
        }
        rows.sort((a, b) -> {
            int c = Integer.compare(b.recorded(), a.recorded());
            if (c != 0) {
                return c;
            }
            c = Integer.compare(a.lastPlayTicks(), b.lastPlayTicks());
            if (c != 0) {
                return c;
            }
            c = compareEpochUnknownLast(a.lastEpoch(), b.lastEpoch());
            if (c != 0) {
                return c;
            }
            return compareNames(a.name(), a.uuid(), b.name(), b.uuid());
        });
        List<PresetRankEntry> entries = new ArrayList<>(rows.size());
        int rank = 0;
        for (int i = 0; i < rows.size(); i++) {
            PresetCandidate candidate = rows.get(i);
            if (i == 0 || candidate.recorded() != rows.get(i - 1).recorded()) {
                rank = i + 1;
            }
            entries.add(new PresetRankEntry(rank, candidate));
        }
        return List.copyOf(entries);
    }

    // ------------------------------------------------------------- N04 world-first counts

    /** Returns world-first holders ranked by key count, with empty input yielding an empty list. */
    public static List<FirstCountEntry> firstsTopByCount(Collection<WorldFirstValue> worldFirsts) {
        Objects.requireNonNull(worldFirsts, "worldFirsts");
        Map<UUID, List<WorldFirstValue>> byHolder = new LinkedHashMap<>();
        for (WorldFirstValue value : worldFirsts) {
            byHolder.computeIfAbsent(value.uuid(), key -> new ArrayList<>()).add(value);
        }
        record Row(UUID uuid, String name, int count, long earliestEpoch, int earliestTicks) {
        }
        List<Row> rows = new ArrayList<>(byHolder.size());
        for (Map.Entry<UUID, List<WorldFirstValue>> holder : byHolder.entrySet()) {
            List<WorldFirstValue> values = holder.getValue();
            long earliestEpoch = 0L;
            for (WorldFirstValue value : values) {
                if (value.epochMillis() > 0L && (earliestEpoch == 0L || value.epochMillis() < earliestEpoch)) {
                    earliestEpoch = value.epochMillis();
                }
            }
            int earliestTicks = Integer.MAX_VALUE;
            for (WorldFirstValue value : values) {
                if (value.epochMillis() == earliestEpoch) {
                    earliestTicks = Math.min(earliestTicks, value.playTicks());
                }
            }
            String name = null;
            for (WorldFirstValue value : values) {
                if (value.name() != null && !value.name().isBlank()) {
                    name = value.name();
                    break;
                }
            }
            rows.add(new Row(holder.getKey(), name == null ? holder.getKey().toString() : name,
                    values.size(), earliestEpoch, earliestTicks));
        }
        rows.sort((a, b) -> {
            int c = Integer.compare(b.count(), a.count());
            if (c != 0) {
                return c;
            }
            c = compareEpochUnknownLast(a.earliestEpoch(), b.earliestEpoch());
            if (c != 0) {
                return c;
            }
            c = Integer.compare(a.earliestTicks(), b.earliestTicks());
            if (c != 0) {
                return c;
            }
            return compareNames(a.name(), a.uuid(), b.name(), b.uuid());
        });
        List<FirstCountEntry> entries = new ArrayList<>(rows.size());
        int rank = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (i == 0 || row.count() != rows.get(i - 1).count()) {
                rank = i + 1;
            }
            entries.add(new FirstCountEntry(rank, row.uuid(), row.name(), row.count(),
                    row.earliestEpoch(), row.earliestTicks()));
        }
        return List.copyOf(entries);
    }

    // ------------------------------------------------------------- N05 unclaimed world firsts

    /**
     * Collects keys without a world-first record, preserving scope order (targets, then presets)
     * and {@code TargetKey::compareTo} inside each scope. A key claimed in both sources is kept
     * twice, once per source, so the row can show its provenance. The result is capped at
     * {@link #UNCLAIMED_RESULT_LIMIT} rows with the exact omitted count reported separately.
     */
    public static UnclaimedResult unclaimed(UnclaimedScope scope, Collection<TargetRef> targets,
                                            Collection<PresetRef> presets, Collection<TargetRef> claimed) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(targets, "targets");
        Objects.requireNonNull(presets, "presets");
        Objects.requireNonNull(claimed, "claimed");
        Set<TargetRef> claimedKeys = new HashSet<>(claimed);
        List<UnclaimedEntry> all = new ArrayList<>();
        if (scope == UnclaimedScope.TARGETS || scope == UnclaimedScope.ALL) {
            List<TargetRef> sorted = new ArrayList<>(targets);
            sorted.sort(TARGET_ORDER);
            for (TargetRef key : sorted) {
                if (!claimedKeys.contains(key)) {
                    all.add(new UnclaimedEntry("target", key.itemId(), key.count(), null));
                }
            }
        }
        if (scope == UnclaimedScope.PRESETS || scope == UnclaimedScope.ALL) {
            List<Map.Entry<String, TargetRef>> flat = new ArrayList<>();
            for (PresetRef preset : presets) {
                for (TargetRef key : preset.items()) {
                    flat.add(Map.entry(preset.id(), key));
                }
            }
            // Stable sort keeps the given preset order for equal keys from different presets.
            flat.sort(Map.Entry.comparingByValue(TARGET_ORDER));
            for (Map.Entry<String, TargetRef> entry : flat) {
                TargetRef key = entry.getValue();
                if (!claimedKeys.contains(key)) {
                    all.add(new UnclaimedEntry("preset", key.itemId(), key.count(), entry.getKey()));
                }
            }
        }
        int total = all.size();
        int kept = Math.min(total, UNCLAIMED_RESULT_LIMIT);
        return new UnclaimedResult(all.subList(0, kept), total - kept);
    }
}
