package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Pure aggregation for {@code /milestonelog top}, {@code top <item> [<count>]} and {@code recent [<n>]} (F13),
 * and {@code stats} (F38).
 *
 * <p>Works only on the saved {@link MilestoneData.PlayerData} map plus viewer info, so it covers offline
 * players and is unit-testable without a server. Nothing is cached; callers run it per command execution.
 *
 * <p>Visibility (F14): players with {@code hideFromLookup} are excluded unless the viewer is privileged
 * ({@code MilestoneService.isPrivileged}) or is that player. Access gating by {@code allowLookup} is the
 * caller's job (same rule as F6 via {@link LookupPolicy#canLookUpOthers}).
 *
 * <p>Ranks use competition ranking (1, 2, 2, 4): equal values share a rank and the next distinct value
 * gets {@code 1 + number of entries ranked before it}.
 */
public final class MilestoneRankings {
    /** Number of rows shown by {@code top} and {@code top <item>} before the viewer's own line. */
    public static final int TOP_LIMIT = 10;
    public static final int RECENT_DEFAULT = 10;
    public static final int RECENT_MIN = 1;
    public static final int RECENT_MAX = 20;

    private MilestoneRankings() {
    }

    /**
     * Who is looking.
     *
     * @param uuid       the executing player's UUID, or {@code null} for console / RCON / command blocks
     * @param privileged whether the source satisfies {@code MilestoneService.isPrivileged}
     */
    public record Viewer(@Nullable UUID uuid, boolean privileged) {
        boolean isSelf(UUID other) {
            return uuid != null && uuid.equals(other);
        }
    }

    /** One row of {@code top}. */
    public record CountEntry(int rank, UUID uuid, String name, int count) {
    }

    /** One row of {@code top <item>}. */
    public record ItemEntry(int rank, UUID uuid, String name, MilestoneRecord record) {
    }

    /**
     * Full ranking (all visible players, not truncated) and the viewer's own row.
     *
     * @param entries ranked rows in display order; callers show the first {@link #TOP_LIMIT}
     * @param self    the viewer's row, or {@code null} when the viewer is not a player or has no entry
     */
    public record Ranking<E>(List<E> entries, @Nullable E self) {
    }

    /** One row of {@code recent}. */
    public record RecentEntry(UUID uuid, String name, TargetKey key, MilestoneRecord record, boolean worldFirst) {
    }

    /** Last known name, or the UUID string when none is stored. */
    public static String displayName(UUID uuid, MilestoneData.PlayerData pd) {
        return (pd.lastKnownName != null && !pd.lastKnownName.isBlank()) ? pd.lastKnownName : uuid.toString();
    }

    /** Whether {@code viewer} may see this player's records in top / recent / suggestions. */
    public static boolean isVisible(Viewer viewer, UUID uuid, MilestoneData.PlayerData pd) {
        return viewer.isSelf(uuid) || viewer.privileged() || !pd.isHiddenFromLookup();
    }

    /** Case-insensitive dictionary order, then case-sensitive, then UUID for a stable total order. */
    private static int compareNames(String a, UUID ua, String b, UUID ub) {
        int c = String.CASE_INSENSITIVE_ORDER.compare(a, b);
        if (c != 0) {
            return c;
        }
        c = a.compareTo(b);
        return c != 0 ? c : ua.compareTo(ub);
    }

    /** {@code top}: record count descending; ties share a rank and are ordered by name. Players with 0 records are excluded. */
    public static Ranking<CountEntry> topByCount(Map<UUID, MilestoneData.PlayerData> players, Viewer viewer) {
        record Row(UUID uuid, String name, int count) {
        }
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<UUID, MilestoneData.PlayerData> e : players.entrySet()) {
            MilestoneData.PlayerData pd = e.getValue();
            if (pd == null || pd.records.isEmpty() || !isVisible(viewer, e.getKey(), pd)) {
                continue;
            }
            rows.add(new Row(e.getKey(), displayName(e.getKey(), pd), pd.records.size()));
        }
        rows.sort((a, b) -> {
            int c = Integer.compare(b.count(), a.count());
            return c != 0 ? c : compareNames(a.name(), a.uuid(), b.name(), b.uuid());
        });
        List<CountEntry> entries = new ArrayList<>(rows.size());
        CountEntry self = null;
        int rank = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (i == 0 || r.count() != rows.get(i - 1).count()) {
                rank = i + 1;
            }
            CountEntry entry = new CountEntry(rank, r.uuid(), r.name(), r.count());
            entries.add(entry);
            if (viewer.isSelf(r.uuid())) {
                self = entry;
            }
        }
        return new Ranking<>(List.copyOf(entries), self);
    }

    /**
     * {@code top <item> [<count>]}: holders of {@code key} by recorded playTicks ascending; equal playTicks
     * share a rank and are ordered by epochMillis ascending (0 = unknown last), then by name.
     */
    public static Ranking<ItemEntry> topByItem(Map<UUID, MilestoneData.PlayerData> players, TargetKey key, Viewer viewer) {
        record Row(UUID uuid, String name, MilestoneRecord record) {
        }
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<UUID, MilestoneData.PlayerData> e : players.entrySet()) {
            MilestoneData.PlayerData pd = e.getValue();
            if (pd == null || !isVisible(viewer, e.getKey(), pd)) {
                continue;
            }
            MilestoneRecord rec = pd.records.get(key);
            if (rec != null) {
                rows.add(new Row(e.getKey(), displayName(e.getKey(), pd), rec));
            }
        }
        rows.sort((a, b) -> {
            int c = Integer.compare(a.record().playTicks(), b.record().playTicks());
            if (c != 0) {
                return c;
            }
            c = compareEpochUnknownLast(a.record(), b.record());
            return c != 0 ? c : compareNames(a.name(), a.uuid(), b.name(), b.uuid());
        });
        List<ItemEntry> entries = new ArrayList<>(rows.size());
        ItemEntry self = null;
        int rank = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (i == 0 || r.record().playTicks() != rows.get(i - 1).record().playTicks()) {
                rank = i + 1;
            }
            ItemEntry entry = new ItemEntry(rank, r.uuid(), r.name(), r.record());
            entries.add(entry);
            if (viewer.isSelf(r.uuid())) {
                self = entry;
            }
        }
        return new Ranking<>(List.copyOf(entries), self);
    }

    private static int compareEpochUnknownLast(MilestoneRecord a, MilestoneRecord b) {
        boolean ka = a.hasEpoch();
        boolean kb = b.hasEpoch();
        if (ka != kb) {
            return ka ? -1 : 1;
        }
        return ka ? Long.compare(a.epochMillis(), b.epochMillis()) : 0;
    }

    /**
     * {@code recent [<n>]}: the world's newest records by epochMillis descending (ties: name, then key),
     * at most {@code limit} rows. Records with an unknown epoch (0, from 1.0.0) are excluded.
     *
     * @param worldFirstHolders target key -> UUID of that key's world-first holder; a row is marked
     *                          {@code worldFirst} when its holder matches
     */
    public static List<RecentEntry> recent(
            Map<UUID, MilestoneData.PlayerData> players,
            Map<TargetKey, UUID> worldFirstHolders,
            Viewer viewer,
            int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<RecentEntry> rows = new ArrayList<>();
        for (Map.Entry<UUID, MilestoneData.PlayerData> e : players.entrySet()) {
            MilestoneData.PlayerData pd = e.getValue();
            if (pd == null || !isVisible(viewer, e.getKey(), pd)) {
                continue;
            }
            String name = displayName(e.getKey(), pd);
            for (Map.Entry<TargetKey, MilestoneRecord> r : pd.records.entrySet()) {
                if (!r.getValue().hasEpoch()) {
                    continue;
                }
                boolean worldFirst = e.getKey().equals(worldFirstHolders.get(r.getKey()));
                rows.add(new RecentEntry(e.getKey(), name, r.getKey(), r.getValue(), worldFirst));
            }
        }
        rows.sort(Comparator.comparingLong((RecentEntry r) -> r.record().epochMillis()).reversed()
                .thenComparing((a, b) -> compareNames(a.name(), a.uuid(), b.name(), b.uuid()))
                .thenComparing(RecentEntry::key));
        return List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
    }

    /** One row of {@code stats}' most-recorded list: {@code players} distinct visible players recorded {@code key}. */
    public record KeyCountEntry(int rank, TargetKey key, int players) {
    }

    /**
     * Result of {@code stats} (F38).
     *
     * @param totalRecords records of players visible to the viewer
     * @param players      visible players with at least one record
     * @param worldFirsts  number of world firsts (always global; public like {@code firsts})
     * @param topKeys      at most {@link #STATS_TOP_LIMIT} most-recorded keys in display order
     */
    public record ServerStats(int totalRecords, int players, int worldFirsts, List<KeyCountEntry> topKeys) {
        /** Nobody visible to the viewer has any record: callers show {@code top_empty}. */
        public boolean isEmpty() {
            return totalRecords == 0;
        }
    }

    /** Number of keys listed by {@code stats}. */
    public static final int STATS_TOP_LIMIT = 5;

    /**
     * {@code stats} (F38): totals and the most-recorded keys among players visible to {@code viewer}
     * (same F14 rule as {@code top}). Keys are ranked by distinct player count descending with competition
     * ranking; ties are ordered by {@link TargetKey} order. The list is truncated to {@link #STATS_TOP_LIMIT}.
     *
     * @param worldFirstCount global number of world firsts; not affected by visibility
     */
    public static ServerStats stats(Map<UUID, MilestoneData.PlayerData> players, int worldFirstCount, Viewer viewer) {
        int totalRecords = 0;
        int playerCount = 0;
        Map<TargetKey, Integer> perKey = new TreeMap<>();
        for (Map.Entry<UUID, MilestoneData.PlayerData> e : players.entrySet()) {
            MilestoneData.PlayerData pd = e.getValue();
            if (pd == null || pd.records.isEmpty() || !isVisible(viewer, e.getKey(), pd)) {
                continue;
            }
            totalRecords += pd.records.size();
            playerCount++;
            for (TargetKey key : pd.records.keySet()) {
                perKey.merge(key, 1, Integer::sum);
            }
        }
        List<Map.Entry<TargetKey, Integer>> rows = new ArrayList<>(perKey.entrySet());
        // Stable sort over the TreeMap's key order keeps ties in TargetKey order.
        rows.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<KeyCountEntry> top = new ArrayList<>(Math.min(STATS_TOP_LIMIT, rows.size()));
        int rank = 0;
        for (int i = 0; i < rows.size() && i < STATS_TOP_LIMIT; i++) {
            Map.Entry<TargetKey, Integer> r = rows.get(i);
            if (i == 0 || !r.getValue().equals(rows.get(i - 1).getValue())) {
                rank = i + 1;
            }
            top.add(new KeyCountEntry(rank, r.getKey(), r.getValue()));
        }
        return new ServerStats(totalRecords, playerCount, worldFirstCount, List.copyOf(top));
    }

    /** Item IDs recorded by any player visible to {@code viewer} (suggestions for {@code top <item>}). */
    public static Set<String> recordedItemIds(Map<UUID, MilestoneData.PlayerData> players, Viewer viewer) {
        Set<String> ids = new TreeSet<>();
        for (Map.Entry<UUID, MilestoneData.PlayerData> e : players.entrySet()) {
            if (e.getValue() != null && isVisible(viewer, e.getKey(), e.getValue())) {
                for (TargetKey key : e.getValue().records.keySet()) {
                    ids.add(key.itemId());
                }
            }
        }
        return ids;
    }

    /** Recorded counts for {@code itemId} among players visible to {@code viewer} (suggestions for {@code <count>}). */
    public static Set<Integer> recordedCounts(Map<UUID, MilestoneData.PlayerData> players, Viewer viewer, String itemId) {
        Set<Integer> counts = new TreeSet<>();
        for (Map.Entry<UUID, MilestoneData.PlayerData> e : players.entrySet()) {
            if (e.getValue() != null && isVisible(viewer, e.getKey(), e.getValue())) {
                for (TargetKey key : e.getValue().records.keySet()) {
                    if (key.itemId().equals(itemId)) {
                        counts.add(key.count());
                    }
                }
            }
        }
        return counts;
    }
}
