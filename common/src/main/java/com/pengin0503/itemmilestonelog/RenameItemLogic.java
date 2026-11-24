package com.pengin0503.itemmilestonelog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

/**
 * Pure-Java collision and rewrite logic for {@code /milestonelog renameitem} (F24),
 * shared across all Minecraft versions via {@code common/src/main/java}.
 *
 * <p>Generic over key type {@code K} ({@code TargetKey}), record type {@code R}
 * ({@code MilestoneRecord}), and world-first type {@code W} ({@code WorldFirstRecord}) so that it
 * stays Java 21 compatible and free of Minecraft/NeoForge dependencies. All operations return new
 * immutable collections without mutating their inputs.
 */
public final class RenameItemLogic {
    private RenameItemLogic() {
    }

    /** Result of rewriting a set ({@code targets} or {@code carriedFromCreative}). */
    public record SetRewrite<T>(Set<T> values, int changedCount) {
        public SetRewrite {
            values = Collections.unmodifiableSet(new LinkedHashSet<>(values));
        }
    }

    /** Result of rewriting a record map ({@code records}). */
    public record MapRewrite<K, V>(Map<K, V> values, int changedCount) {
        public MapRewrite {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }

    /** Result of rewriting a {@code progressNotified} map. */
    public record ProgressRewrite<K>(Map<K, Integer> values, boolean changed) {
        public ProgressRewrite {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }

    /**
     * Result of rewriting the world-first map.
     *
     * @param values          unmodifiable merged world-first map
     * @param changedCount    number of world-first entries whose key matched the old item ID
     * @param affectedPlayers UUIDs of players holding either a moved old world first or a colliding
     *                        destination world first (used to refresh F17 scoreboard counts)
     */
    public record WorldFirstRewrite<K, W>(
            Map<K, W> values,
            int changedCount,
            Set<UUID> affectedPlayers) {
        public WorldFirstRewrite {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            affectedPlayers = Collections.unmodifiableSet(new LinkedHashSet<>(affectedPlayers));
        }
    }

    /**
     * Record collision rule (F24): keep the record with the smaller {@code playTicks};
     * on a tie, keep the existing destination record.
     */
    public static boolean shouldKeepOldRecord(int oldPlayTicks, int existingDestPlayTicks) {
        return oldPlayTicks < existingDestPlayTicks;
    }

    /**
     * Progress-alert collision rule (F24): keep the larger notified threshold.
     */
    public static int mergeProgressThreshold(int oldThreshold, int existingDestThreshold) {
        return Math.max(oldThreshold, existingDestThreshold);
    }

    /**
     * World-first collision rule (F24): when both {@code epochMillis} are known ({@code > 0}),
     * keep the earlier (smaller) one; otherwise keep the existing destination record.
     */
    public static boolean shouldKeepOldWorldFirst(long existingDestEpochMillis, long oldEpochMillis) {
        return WorldFirstImportLogic.shouldReplace(existingDestEpochMillis, oldEpochMillis);
    }

    /**
     * Rewrites target keys matching {@code isOld} via {@code toNew}, deduplicating collisions.
     * Input set is not modified.
     */
    public static <K> SetRewrite<K> rewriteTargets(
            Set<K> targets,
            Predicate<K> isOld,
            Function<K, K> toNew) {
        Objects.requireNonNull(isOld, "isOld");
        Objects.requireNonNull(toNew, "toNew");
        if (targets == null || targets.isEmpty()) {
            return new SetRewrite<>(Set.of(), 0);
        }
        Set<K> next = new LinkedHashSet<>(targets.size());
        int changed = 0;
        for (K key : targets) {
            if (isOld.test(key)) {
                next.add(toNew.apply(key));
                changed++;
            } else {
                next.add(key);
            }
        }
        return new SetRewrite<>(next, changed);
    }

    /**
     * Rewrites record keys matching {@code isOld} via {@code toNew}. When the destination key
     * already exists in {@code records}, keeps the record with the smaller {@code playTicks}
     * (keeping the existing destination record on a tie), regardless of map iteration order.
     * Input map is not modified.
     */
    public static <K, R> MapRewrite<K, R> rewriteRecords(
            Map<K, R> records,
            Predicate<K> isOld,
            Predicate<K> isNew,
            Function<K, K> toNew,
            ToIntFunction<R> playTicks) {
        Objects.requireNonNull(isOld, "isOld");
        Objects.requireNonNull(isNew, "isNew");
        Objects.requireNonNull(toNew, "toNew");
        Objects.requireNonNull(playTicks, "playTicks");
        if (records == null || records.isEmpty()) {
            return new MapRewrite<>(Map.of(), 0);
        }
        Map<K, R> next = new LinkedHashMap<>(records.size());
        int changed = 0;
        for (Map.Entry<K, R> entry : records.entrySet()) {
            K key = entry.getKey();
            R rec = entry.getValue();
            if (isOld.test(key)) {
                changed++;
                K destKey = toNew.apply(key);
                R existingAtDest = records.get(destKey);
                if (existingAtDest == null) {
                    next.put(destKey, rec);
                } else {
                    R winner = shouldKeepOldRecord(
                            playTicks.applyAsInt(rec),
                            playTicks.applyAsInt(existingAtDest))
                            ? rec
                            : existingAtDest;
                    next.put(destKey, winner);
                }
            } else if (isNew.test(key)) {
                next.putIfAbsent(key, rec);
            } else {
                next.put(key, rec);
            }
        }
        return new MapRewrite<>(next, changed);
    }

    /**
     * Rewrites {@code progressNotified} keys matching {@code isOld} via {@code toNew}:
     * <ul>
     *   <li>When both old and destination keys exist and are unrecorded after the merge, keeps the
     *       larger threshold value.</li>
     *   <li>When a destination key is present in {@code mergedRecordKeys} after the merge (either
     *       because the destination was already recorded or because the old key's record moved onto
     *       it), removes the {@code progressNotified} entry for that key.</li>
     * </ul>
     * Input collections are not modified.
     */
    public static <K> ProgressRewrite<K> rewriteProgressNotified(
            Map<K, Integer> progressNotified,
            Set<K> originalRecordKeys,
            Set<K> mergedRecordKeys,
            Predicate<K> isOld,
            Predicate<K> isNew,
            Function<K, K> toNew,
            Function<K, K> newToOld) {
        Objects.requireNonNull(isOld, "isOld");
        Objects.requireNonNull(isNew, "isNew");
        Objects.requireNonNull(toNew, "toNew");
        Objects.requireNonNull(newToOld, "newToOld");
        if (progressNotified == null || progressNotified.isEmpty()) {
            return new ProgressRewrite<>(Map.of(), false);
        }
        Set<K> origRecs = originalRecordKeys != null ? originalRecordKeys : Set.of();
        Set<K> mergedRecs = mergedRecordKeys != null ? mergedRecordKeys : Set.of();

        Map<K, Integer> next = new LinkedHashMap<>(progressNotified.size());
        boolean changed = false;
        for (Map.Entry<K, Integer> entry : progressNotified.entrySet()) {
            K key = entry.getKey();
            int threshold = entry.getValue();
            if (isOld.test(key)) {
                changed = true;
                K destKey = toNew.apply(key);
                if (!mergedRecs.contains(destKey)) {
                    next.merge(destKey, threshold, RenameItemLogic::mergeProgressThreshold);
                }
            } else if (isNew.test(key)) {
                K oldKey = newToOld.apply(key);
                boolean touchedByRename = origRecs.contains(oldKey) || progressNotified.containsKey(oldKey);
                if (mergedRecs.contains(key) && touchedByRename) {
                    changed = true;
                } else {
                    next.merge(key, threshold, RenameItemLogic::mergeProgressThreshold);
                }
            } else {
                next.put(key, threshold);
            }
        }
        return new ProgressRewrite<>(next, changed);
    }

    /**
     * Rewrites {@code oldId} to {@code newId} in {@code carriedFromCreative}, deduplicating if
     * {@code newId} is already present. Input set is not modified.
     */
    public static SetRewrite<String> rewriteCarriedFromCreative(
            Set<String> carried,
            String oldId,
            String newId) {
        Objects.requireNonNull(oldId, "oldId");
        Objects.requireNonNull(newId, "newId");
        if (carried == null || carried.isEmpty()) {
            return new SetRewrite<>(Set.of(), 0);
        }
        Set<String> next = new LinkedHashSet<>(carried.size());
        int changed = 0;
        for (String id : carried) {
            if (oldId.equals(id)) {
                next.add(newId);
                changed++;
            } else {
                next.add(id);
            }
        }
        return new SetRewrite<>(next, changed);
    }

    /**
     * Rewrites world-first keys matching {@code isOld} via {@code toNew} (and updates the record's
     * embedded item ID via {@code renameRecord}). On collision with an existing destination key,
     * keeps the earlier {@code epochMillis} when both are known ({@code > 0}); otherwise keeps the
     * existing destination record, regardless of map iteration order. Input map is not modified.
     */
    public static <K, W> WorldFirstRewrite<K, W> rewriteWorldFirsts(
            Map<K, W> worldFirsts,
            Predicate<K> isOld,
            Predicate<K> isNew,
            Function<K, K> toNew,
            Function<W, W> renameRecord,
            ToLongFunction<W> epochMillis,
            Function<W, UUID> playerUuid) {
        Objects.requireNonNull(isOld, "isOld");
        Objects.requireNonNull(isNew, "isNew");
        Objects.requireNonNull(toNew, "toNew");
        Objects.requireNonNull(renameRecord, "renameRecord");
        Objects.requireNonNull(epochMillis, "epochMillis");
        Objects.requireNonNull(playerUuid, "playerUuid");
        if (worldFirsts == null || worldFirsts.isEmpty()) {
            return new WorldFirstRewrite<>(Map.of(), 0, Set.of());
        }
        Map<K, W> next = new LinkedHashMap<>(worldFirsts.size());
        Set<UUID> affectedPlayers = new LinkedHashSet<>();
        int changed = 0;
        for (Map.Entry<K, W> entry : worldFirsts.entrySet()) {
            K key = entry.getKey();
            W wf = entry.getValue();
            if (isOld.test(key)) {
                changed++;
                UUID oldHolder = playerUuid.apply(wf);
                if (oldHolder != null) {
                    affectedPlayers.add(oldHolder);
                }
                K destKey = toNew.apply(key);
                W existingAtDest = worldFirsts.get(destKey);
                if (existingAtDest == null) {
                    next.put(destKey, renameRecord.apply(wf));
                } else {
                    UUID destHolder = playerUuid.apply(existingAtDest);
                    if (destHolder != null) {
                        affectedPlayers.add(destHolder);
                    }
                    W winner = shouldKeepOldWorldFirst(
                            epochMillis.applyAsLong(existingAtDest),
                            epochMillis.applyAsLong(wf))
                            ? renameRecord.apply(wf)
                            : existingAtDest;
                    next.put(destKey, winner);
                }
            } else if (isNew.test(key)) {
                next.putIfAbsent(key, wf);
            } else {
                next.put(key, wf);
            }
        }
        return new WorldFirstRewrite<>(next, changed, affectedPlayers);
    }
}
