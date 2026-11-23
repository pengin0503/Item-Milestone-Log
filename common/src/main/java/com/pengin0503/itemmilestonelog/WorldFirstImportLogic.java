package com.pengin0503.itemmilestonelog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * Pure-Java merge logic for world-first records imported via {@code /milestonelog importall} (F31).
 *
 * <p>Generic over key type {@code K} ({@code TargetKey}) and record type {@code R}
 * ({@code WorldFirstRecord}) so that it stays free of Minecraft/NeoForge dependencies
 * and Java 21 compatible.
 */
public final class WorldFirstImportLogic {
    private WorldFirstImportLogic() {
    }

    /**
     * Result of {@link #merge}: the merged world-first map plus counts for {@code importall_done}.
     *
     * @param worldFirsts  unmodifiable view of the resulting world-first map (input maps are not modified)
     * @param updatedCount number of keys added or replaced compared to {@code current}
     * @param skippedCount number of unparseable/null elements skipped (including {@code preSkippedCount})
     */
    public record MergeResult<K, R>(
            Map<K, R> worldFirsts,
            int updatedCount,
            int skippedCount) {
        public MergeResult {
            worldFirsts = Collections.unmodifiableMap(new LinkedHashMap<>(worldFirsts));
        }
    }

    /**
     * Returns whether an incoming world-first record should replace an existing one for the same key.
     *
     * <p>Rule (F31):
     * <ul>
     *   <li>If both {@code existingEpochMillis} and {@code incomingEpochMillis} are known ({@code > 0}),
     *       keep the earlier one (replace iff {@code incomingEpochMillis < existingEpochMillis}).</li>
     *   <li>If either is unknown ({@code <= 0}), keep the existing one (return {@code false}).</li>
     * </ul>
     */
    public static boolean shouldReplace(long existingEpochMillis, long incomingEpochMillis) {
        return existingEpochMillis > 0L
                && incomingEpochMillis > 0L
                && incomingEpochMillis < existingEpochMillis;
    }

    /**
     * Merges {@code incomingEntries} into {@code current} without modifying either input.
     *
     * <p>Null elements in {@code incomingEntries} (or entries whose extracted key is {@code null})
     * represent broken/unparseable entries and are skipped and counted alongside {@code preSkippedCount}.
     *
     * @param current         current saved world firsts (may be {@code null} or empty)
     * @param incomingEntries parsed world-first records (may be {@code null} or contain {@code null} elements)
     * @param preSkippedCount entries already skipped while parsing before calling {@code merge}
     * @param keyExtractor    extracts the target key from a non-null record
     * @param epochExtractor  extracts {@code epochMillis} ({@code > 0} if known, {@code <= 0} if unknown)
     * @return the merged map and counts
     */
    public static <K, R> MergeResult<K, R> merge(
            Map<K, R> current,
            List<R> incomingEntries,
            int preSkippedCount,
            Function<R, K> keyExtractor,
            ToLongFunction<R> epochExtractor) {
        Objects.requireNonNull(keyExtractor, "keyExtractor");
        Objects.requireNonNull(epochExtractor, "epochExtractor");

        Map<K, R> merged = current != null ? new LinkedHashMap<>(current) : new LinkedHashMap<>();
        Set<K> modifiedKeys = new LinkedHashSet<>();
        int skipped = Math.max(0, preSkippedCount);

        if (incomingEntries != null) {
            for (R incoming : incomingEntries) {
                if (incoming == null) {
                    skipped++;
                    continue;
                }
                K key = keyExtractor.apply(incoming);
                if (key == null) {
                    skipped++;
                    continue;
                }
                R existing = merged.get(key);
                if (existing == null) {
                    merged.put(key, incoming);
                    modifiedKeys.add(key);
                } else {
                    long existingEpoch = epochExtractor.applyAsLong(existing);
                    long incomingEpoch = epochExtractor.applyAsLong(incoming);
                    if (shouldReplace(existingEpoch, incomingEpoch)) {
                        merged.put(key, incoming);
                        modifiedKeys.add(key);
                    }
                }
            }
        }

        return new MergeResult<>(merged, modifiedKeys.size(), skipped);
    }
}
