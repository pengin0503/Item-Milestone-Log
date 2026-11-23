package com.pengin0503.itemmilestonelog;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.ToIntFunction;

/**
 * Pure-Java snapshot and restore logic for {@code /milestonelog undo} (F23).
 *
 * <p>Generic over the key type {@code K} (TargetKey in the mod) and the record type {@code R}
 * (MilestoneRecord) so that it stays free of Minecraft/NeoForge dependencies and Java 21 compatible.
 *
 * <p>A snapshot holds what one reset (F23) or bulk target removal (F28) removed from a player's
 * data. {@link Store} keeps at most one snapshot per player UUID, in memory only, valid for
 * {@link #VALIDITY_NANOS} measured with the server's monotonic clock ({@code System.nanoTime}).
 * Operations that removed nothing never overwrite an existing snapshot.
 */
public final class UndoLogic {
    /** How long a snapshot can be undone: 10 minutes. */
    public static final long VALIDITY_NANOS = TimeUnit.MINUTES.toNanos(10);

    private UndoLogic() {
    }

    /** What kind of operation produced a snapshot; decides which {@code undo_done*} message is shown. */
    public enum Kind {
        /** {@code reset <item> [count]} / {@code resetall} and the GUI reset actions (F23). */
        RESET,
        /** F28 {@code presetremove} / {@code removeall}. */
        BULK_REMOVAL
    }

    /**
     * Immutable content removed by one operation.
     *
     * @param kind                      operation kind
     * @param removedRecords            records the operation deleted (key -> record)
     * @param removedCompletedPresets   {@code completedPresets} entries the operation deleted
     * @param removedProgressNotified   {@code progressNotified} entries the operation deleted (key -> value)
     * @param removedTargets            personal targets the operation removed (F28; empty for resets)
     * @param capturedAtNanos           {@code System.nanoTime()} when the snapshot was taken
     */
    public record Snapshot<K, R>(Kind kind,
                                 Map<K, R> removedRecords,
                                 Set<String> removedCompletedPresets,
                                 Map<K, Integer> removedProgressNotified,
                                 List<K> removedTargets,
                                 long capturedAtNanos) {
        public Snapshot {
            Objects.requireNonNull(kind, "kind");
            removedRecords = Collections.unmodifiableMap(new LinkedHashMap<>(removedRecords));
            removedCompletedPresets = Collections.unmodifiableSet(new LinkedHashSet<>(removedCompletedPresets));
            removedProgressNotified = Collections.unmodifiableMap(new LinkedHashMap<>(removedProgressNotified));
            removedTargets = List.copyOf(removedTargets);
        }

        /** Snapshot for a reset: records plus the completedPresets / progressNotified entries removed with them. */
        public static <K, R> Snapshot<K, R> reset(Map<K, R> removedRecords,
                                                  Collection<String> removedCompletedPresets,
                                                  Map<K, Integer> removedProgressNotified,
                                                  long capturedAtNanos) {
            return new Snapshot<>(Kind.RESET, removedRecords, new LinkedHashSet<>(removedCompletedPresets),
                    removedProgressNotified, List.of(), capturedAtNanos);
        }

        /** Snapshot for an F28 bulk removal: removed personal targets plus the progressNotified entries removed with them. */
        public static <K, R> Snapshot<K, R> bulkRemoval(Collection<K> removedTargets,
                                                        Map<K, Integer> removedProgressNotified,
                                                        long capturedAtNanos) {
            return new Snapshot<>(Kind.BULK_REMOVAL, Map.of(), Set.of(), removedProgressNotified,
                    List.copyOf(removedTargets), capturedAtNanos);
        }

        /**
         * Whether the operation removed nothing worth undoing: a reset that removed no record
         * (failed {@code reset}, 0-record {@code resetall}) or a bulk removal that removed no target.
         * Such snapshots never overwrite the stored one.
         */
        public boolean isEmpty() {
            return kind == Kind.RESET ? removedRecords.isEmpty() : removedTargets.isEmpty();
        }

        /** Whether more than {@link #VALIDITY_NANOS} have passed since capture (overflow-safe nanoTime comparison). */
        public boolean isExpired(long nowNanos) {
            return nowNanos - capturedAtNanos > VALIDITY_NANOS;
        }
    }

    /**
     * Result of {@link #restore}: the new contents of the player's collections plus counts for the message.
     *
     * @param restoredRecords     records whose snapshot value was put back (absent now, or the snapshot's playTicks was smaller)
     * @param restoredTargets     personal targets added back (F28)
     * @param skippedTargetsLimit targets not added back because the personal target limit was reached (F28)
     */
    public record Result<K, R>(Map<K, R> records,
                               Set<String> completedPresets,
                               Map<K, Integer> progressNotified,
                               Set<K> targets,
                               int restoredRecords,
                               int restoredTargets,
                               int skippedTargetsLimit) {
    }

    /**
     * Computes the player's data after undoing {@code snapshot}, without modifying any argument.
     *
     * <ul>
     *   <li>Records: a key absent now gets the snapshot record back; a key re-recorded since keeps
     *       the record with the smaller playTicks (same rule as import; ties keep the current one).</li>
     *   <li>completedPresets: union with the removed entries.</li>
     *   <li>Targets (F28): removed targets are added back unless already present; additions beyond
     *       {@code maxTargets} are skipped and counted.</li>
     *   <li>progressNotified: keys recorded after the restore have no entry (§1.1); for keys that are
     *       still unrecorded, the pre-removal value is restored only if there is no entry now.</li>
     * </ul>
     * World firsts are not part of the snapshot and are never touched.
     */
    public static <K, R> Result<K, R> restore(Snapshot<K, R> snapshot,
                                              Map<K, R> currentRecords,
                                              Set<String> currentCompletedPresets,
                                              Map<K, Integer> currentProgressNotified,
                                              Set<K> currentTargets,
                                              int maxTargets,
                                              ToIntFunction<R> playTicks) {
        Map<K, R> records = new LinkedHashMap<>(currentRecords);
        int restoredRecords = 0;
        for (Map.Entry<K, R> e : snapshot.removedRecords().entrySet()) {
            R existing = records.get(e.getKey());
            if (existing == null || playTicks.applyAsInt(e.getValue()) < playTicks.applyAsInt(existing)) {
                records.put(e.getKey(), e.getValue());
                restoredRecords++;
            }
        }

        Set<String> completed = new LinkedHashSet<>(currentCompletedPresets);
        completed.addAll(snapshot.removedCompletedPresets());

        Set<K> targets = new LinkedHashSet<>(currentTargets);
        int restoredTargets = 0;
        int skippedLimit = 0;
        for (K key : snapshot.removedTargets()) {
            if (targets.contains(key)) {
                continue;
            }
            if (targets.size() >= maxTargets) {
                skippedLimit++;
                continue;
            }
            targets.add(key);
            restoredTargets++;
        }

        Map<K, Integer> progress = new LinkedHashMap<>(currentProgressNotified);
        for (K key : snapshot.removedRecords().keySet()) {
            progress.remove(key);
        }
        for (Map.Entry<K, Integer> e : snapshot.removedProgressNotified().entrySet()) {
            K key = e.getKey();
            if (!records.containsKey(key) && !progress.containsKey(key)) {
                progress.put(key, e.getValue());
            }
        }

        return new Result<>(records, completed, progress, targets, restoredRecords, restoredTargets, skippedLimit);
    }

    /**
     * In-memory, per-player store holding at most one snapshot per UUID. Not persisted: lost when
     * the server stops ({@link #clear()} is called on server stop so an integrated server does not
     * carry snapshots into the next world). Logging out does not clear a player's snapshot.
     */
    public static final class Store<K, R> {
        private final Map<UUID, Snapshot<K, R>> snapshots = new ConcurrentHashMap<>();

        /**
         * Stores {@code snapshot} for {@code playerId}, replacing the previous one, unless the
         * snapshot is empty (an operation that removed nothing keeps the previous snapshot).
         *
         * @return whether the snapshot was stored (i.e. the operation can be undone)
         */
        public boolean offer(UUID playerId, Snapshot<K, R> snapshot) {
            if (snapshot == null || snapshot.isEmpty()) {
                return false;
            }
            snapshots.put(playerId, snapshot);
            return true;
        }

        /** Returns the player's snapshot if it is still valid at {@code nowNanos}; drops and returns null if expired. */
        public Snapshot<K, R> peekValid(UUID playerId, long nowNanos) {
            Snapshot<K, R> snapshot = snapshots.get(playerId);
            if (snapshot == null) {
                return null;
            }
            if (snapshot.isExpired(nowNanos)) {
                snapshots.remove(playerId, snapshot);
                return null;
            }
            return snapshot;
        }

        /** Removes {@code snapshot} after a successful undo (no-op if it was replaced meanwhile). */
        public void consume(UUID playerId, Snapshot<K, R> snapshot) {
            snapshots.remove(playerId, snapshot);
        }

        /** Drops every snapshot (server stop). */
        public void clear() {
            snapshots.clear();
        }

        /** Number of stored snapshots, including expired ones not yet dropped (for tests). */
        public int size() {
            return snapshots.size();
        }
    }
}
