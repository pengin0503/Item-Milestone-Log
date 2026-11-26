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
 * world-first holder counts (N04) and reachable unclaimed world firsts (N05).
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
