package com.pengin0503.itemmilestonelog;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
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
 * data. {@link Store} keeps at most {@link #UNDO_HISTORY_DEPTH} snapshots per player UUID, newest
 * first, in memory only, each valid for {@link #VALIDITY_NANOS} measured with the server's monotonic
 * clock ({@code System.nanoTime}). Operations that removed nothing never add a snapshot.
 */
public final class UndoLogic {
    /** How long a snapshot can be undone: 10 minutes. */
    public static final long VALIDITY_NANOS = TimeUnit.MINUTES.toNanos(10);

    /** Maximum number of undo generations kept per player (F-150-25, D-150-65). */
    public static final int UNDO_HISTORY_DEPTH = 5;

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
     * @param removedPresetState        {@code presetState} entries the operation deleted (preset ID -> state)
     * @param removedTargets            personal targets the operation removed (F28; empty for resets)
     * @param capturedAtNanos           {@code System.nanoTime()} when the snapshot was taken
     */
    public record Snapshot<K, R>(Kind kind,
                                 Map<K, R> removedRecords,
                                 Set<String> removedCompletedPresets,
                                 Map<K, Integer> removedProgressNotified,
                                 Map<String, PresetState> removedPresetState,
                                 List<K> removedTargets,
                                 long capturedAtNanos) {
        public Snapshot {
            Objects.requireNonNull(kind, "kind");
            removedRecords = Collections.unmodifiableMap(new LinkedHashMap<>(removedRecords));
            removedCompletedPresets = Collections.unmodifiableSet(new LinkedHashSet<>(removedCompletedPresets));
            removedProgressNotified = Collections.unmodifiableMap(new LinkedHashMap<>(removedProgressNotified));
            removedPresetState = Collections.unmodifiableMap(new LinkedHashMap<>(removedPresetState));
            removedTargets = List.copyOf(removedTargets);
        }

        /** Snapshot for a reset: records plus the completedPresets / progressNotified / presetState entries removed with them. */
        public static <K, R> Snapshot<K, R> reset(Map<K, R> removedRecords,
                                                  Collection<String> removedCompletedPresets,
                                                  Map<K, Integer> removedProgressNotified,
                                                  Map<String, PresetState> removedPresetState,
                                                  long capturedAtNanos) {
            return new Snapshot<>(Kind.RESET, removedRecords, new LinkedHashSet<>(removedCompletedPresets),
                    removedProgressNotified, removedPresetState, List.of(), capturedAtNanos);
        }

        /** Snapshot for an F28 bulk removal: removed personal targets plus the progressNotified entries removed with them. */
        public static <K, R> Snapshot<K, R> bulkRemoval(Collection<K> removedTargets,
                                                        Map<K, Integer> removedProgressNotified,
                                                        long capturedAtNanos) {
            return new Snapshot<>(Kind.BULK_REMOVAL, Map.of(), Set.of(), removedProgressNotified, Map.of(),
                    List.copyOf(removedTargets), capturedAtNanos);
        }

        /**
         * Whether the operation removed nothing worth undoing: a reset that removed no record
         * and no presetState (failed {@code reset}, 0-record {@code resetall}) or a bulk removal
         * that removed no target. Such snapshots never overwrite the stored one.
         */
        public boolean isEmpty() {
            return kind == Kind.RESET
                    ? removedRecords.isEmpty() && removedPresetState.isEmpty()
                    : removedTargets.isEmpty();
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
                               Map<String, PresetState> presetState,
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
     *   <li>presetState (F-140-9): the removed entries are merged back with the F-140-12 rule
     *       ({@code autoApplied} OR-ed, {@code lastSeenVersion} max), so state recorded after the
     *       reset is kept while the pre-reset values are restored.</li>
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
                                              Map<String, PresetState> currentPresetState,
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

        Map<String, PresetState> presetState = new LinkedHashMap<>(currentPresetState);
        PresetStateLogic.mergeInto(presetState, snapshot.removedPresetState());

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

        return new Result<>(records, completed, progress, presetState, targets, restoredRecords, restoredTargets, skippedLimit);
    }

    /**
     * In-memory, per-player store holding a stack of at most {@link #UNDO_HISTORY_DEPTH} snapshots
     * per UUID (head = newest generation). Not persisted: lost when the server stops ({@link #clear()}
     * is called on server stop so an integrated server does not carry snapshots into the next world).
     * Logging out does not clear a player's snapshots.
     */
    public static final class Store<K, R> {
        private final Map<UUID, Deque<Snapshot<K, R>>> snapshots = new ConcurrentHashMap<>();

        /**
         * Pushes {@code snapshot} as the newest generation for {@code playerId}, dropping the
         * oldest (tail) when more than {@link #UNDO_HISTORY_DEPTH} generations are stored, unless
         * the snapshot is empty (an operation that removed nothing keeps the existing history).
         *
         * @return whether the snapshot was stored (i.e. the operation can be undone)
         */
        public boolean offer(UUID playerId, Snapshot<K, R> snapshot) {
            if (snapshot == null || snapshot.isEmpty()) {
                return false;
            }
            snapshots.compute(playerId, (id, deque) -> {
                Deque<Snapshot<K, R>> history = deque == null ? new ArrayDeque<>() : deque;
                history.addFirst(snapshot);
                while (history.size() > UNDO_HISTORY_DEPTH) {
                    history.removeLast();
                }
                return history;
            });
            return true;
        }

        /**
         * Returns the newest still-valid snapshot (generation 1) for {@code playerId}, pruning
         * expired snapshots from every position; returns null and drops the map entry if none is valid.
         */
        public Snapshot<K, R> peekValid(UUID playerId, long nowNanos) {
            Deque<Snapshot<K, R>> history = pruneExpired(playerId, nowNanos);
            return history == null ? null : history.peekFirst();
        }

        /**
         * Returns the valid generations for {@code playerId}, newest first (index 0 = generation 1),
         * pruning expired snapshots and dropping the map entry if none remains. The returned list is
         * immutable and never exposes mutable snapshot state.
         */
        public List<Snapshot<K, R>> list(UUID playerId, long nowNanos) {
            Deque<Snapshot<K, R>> history = pruneExpired(playerId, nowNanos);
            return history == null ? List.of() : List.copyOf(history);
        }

        /**
         * Returns generation {@code generation} (1-based, 1 = newest) if it is still valid at
         * {@code nowNanos}, after pruning expired snapshots; null when out of range or expired.
         */
        public Snapshot<K, R> get(UUID playerId, int generation, long nowNanos) {
            if (generation < 1) {
                return null;
            }
            Deque<Snapshot<K, R>> history = pruneExpired(playerId, nowNanos);
            if (history == null || generation > history.size()) {
                return null;
            }
            int index = 1;
            for (Snapshot<K, R> snapshot : history) {
                if (index++ == generation) {
                    return snapshot;
                }
            }
            return null;
        }

        /**
         * Removes {@code snapshot} after a successful undo, together with every newer generation
         * (closer to the head): undoing a middle generation invalidates the later ones (D-150-66).
         * Consuming the newest generation therefore removes only it. No-op if no equal snapshot is
         * stored (e.g. it was replaced meanwhile).
         */
        public void consume(UUID playerId, Snapshot<K, R> snapshot) {
            if (snapshot == null) {
                return;
            }
            snapshots.computeIfPresent(playerId, (id, deque) -> {
                if (!deque.contains(snapshot)) {
                    return deque;
                }
                Iterator<Snapshot<K, R>> iterator = deque.iterator();
                while (iterator.hasNext()) {
                    Snapshot<K, R> current = iterator.next();
                    iterator.remove();
                    if (current.equals(snapshot)) {
                        break;
                    }
                }
                return deque.isEmpty() ? null : deque;
            });
        }

        /** Drops every snapshot (server stop). */
        public void clear() {
            snapshots.clear();
        }

        /** Total number of stored snapshots across all players, including expired ones not yet dropped (for tests). */
        public int size() {
            int total = 0;
            for (Deque<Snapshot<K, R>> history : snapshots.values()) {
                total += history.size();
            }
            return total;
        }

        /** Drops expired snapshots from every position; null (with the map entry removed) if none is left. */
        private Deque<Snapshot<K, R>> pruneExpired(UUID playerId, long nowNanos) {
            Deque<Snapshot<K, R>> history = snapshots.get(playerId);
            if (history == null) {
                return null;
            }
            history.removeIf(snapshot -> snapshot.isExpired(nowNanos));
            if (history.isEmpty()) {
                snapshots.remove(playerId, history);
                return null;
            }
            return history;
        }
    }
}
