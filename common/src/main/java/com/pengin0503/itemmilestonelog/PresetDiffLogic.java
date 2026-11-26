package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.TargetKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure-Java diff computation for {@code /milestonelog preset diff} (spec 1.4.0 F-140-17 / N53),
 * shared across all Minecraft versions.
 *
 * <p>Both inputs are the presets' already includes-expanded {@link TargetKey} lists. Comparison
 * is by item ID: a key present only on one side is reported there, while an item present on both
 * sides with a different quantity is reported as a count difference. (Metadata is out of scope;
 * {@code preset show} displays it.) A preset that lists the same item ID more than once — the
 * parser normally blocks exact duplicates but can keep the same ID with different quantities —
 * contributes the sum of those quantities, i.e. the multiset total.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class PresetDiffLogic {
    /** Outcome of a diff request; the command maps each status to its own message. */
    public enum Status {
        /** {@code presetsEnabled == false}; the command reports the existing disabled message. */
        DISABLED,
        /** The first name does not resolve to a preset. */
        UNKNOWN_A,
        /** The second name does not resolve to a preset. */
        UNKNOWN_B,
        /** Both presets resolved and were compared. */
        OK
    }

    /** One item/quantity pair in a one-sided section. */
    public record ItemCount(String itemId, long count) {
        public ItemCount {
            Objects.requireNonNull(itemId, "itemId");
            if (count <= 0) {
                throw new IllegalArgumentException("count must be positive: " + count);
            }
        }
    }

    /** An item present on both sides with different quantities. */
    public record CountDifference(String itemId, long countA, long countB) {
        public CountDifference {
            Objects.requireNonNull(itemId, "itemId");
            if (countA <= 0 || countB <= 0) {
                throw new IllegalArgumentException("counts must be positive: " + countA + " / " + countB);
            }
        }
    }

    /**
     * Diff result. One-sided sections and the count-difference section are each capped
     * independently at the same row limit as {@code preset show}; {@code *Hidden} is the number
     * of omitted rows for that section.
     */
    public record Result(
            Status status,
            List<ItemCount> onlyA,
            int onlyAHidden,
            List<ItemCount> onlyB,
            int onlyBHidden,
            List<CountDifference> countDifferences,
            int countDifferencesHidden) {

        public Result {
            Objects.requireNonNull(status, "status");
            onlyA = onlyA == null ? List.of() : List.copyOf(onlyA);
            onlyB = onlyB == null ? List.of() : List.copyOf(onlyB);
            countDifferences = countDifferences == null ? List.of() : List.copyOf(countDifferences);
            onlyAHidden = Math.max(0, onlyAHidden);
            onlyBHidden = Math.max(0, onlyBHidden);
            countDifferencesHidden = Math.max(0, countDifferencesHidden);
        }

        /** Whether any section has a shown or omitted row. */
        public boolean hasDifferences() {
            return !onlyA.isEmpty() || onlyAHidden > 0
                    || !onlyB.isEmpty() || onlyBHidden > 0
                    || !countDifferences.isEmpty() || countDifferencesHidden > 0;
        }
    }

    private PresetDiffLogic() {
    }

    /**
     * Computes the diff. Either item list may be null to mean "unknown preset"; disabled wins
     * over unknown names, matching the other preset commands.
     */
    public static Result diff(List<TargetKey> itemsA, List<TargetKey> itemsB,
                              boolean presetsEnabled, int maxRows) {
        if (!presetsEnabled) {
            return empty(Status.DISABLED);
        }
        if (itemsA == null) {
            return empty(Status.UNKNOWN_A);
        }
        if (itemsB == null) {
            return empty(Status.UNKNOWN_B);
        }

        Map<String, Long> countsA = countsByItemId(itemsA);
        Map<String, Long> countsB = countsByItemId(itemsB);

        List<ItemCount> onlyA = new ArrayList<>();
        List<CountDifference> countDifferences = new ArrayList<>();
        for (Map.Entry<String, Long> entry : countsA.entrySet()) {
            Long other = countsB.get(entry.getKey());
            if (other == null) {
                onlyA.add(new ItemCount(entry.getKey(), entry.getValue()));
            } else if (!other.equals(entry.getValue())) {
                countDifferences.add(new CountDifference(entry.getKey(), entry.getValue(), other));
            }
        }
        List<ItemCount> onlyB = new ArrayList<>();
        for (Map.Entry<String, Long> entry : countsB.entrySet()) {
            if (!countsA.containsKey(entry.getKey())) {
                onlyB.add(new ItemCount(entry.getKey(), entry.getValue()));
            }
        }

        int limitA = limit(onlyA.size(), maxRows);
        int limitB = limit(onlyB.size(), maxRows);
        int limitDiff = limit(countDifferences.size(), maxRows);
        return new Result(
                Status.OK,
                List.copyOf(onlyA.subList(0, limitA)), onlyA.size() - limitA,
                List.copyOf(onlyB.subList(0, limitB)), onlyB.size() - limitB,
                List.copyOf(countDifferences.subList(0, limitDiff)), countDifferences.size() - limitDiff);
    }

    private static Result empty(Status status) {
        return new Result(status, List.of(), 0, List.of(), 0, List.of(), 0);
    }

    private static int limit(int size, int maxRows) {
        return Math.max(0, Math.min(maxRows, size));
    }

    /**
     * Multiset totals per item ID, preserving first-occurrence order. Long arithmetic keeps the
     * result well-defined for hand-written presets with repeated IDs.
     */
    private static Map<String, Long> countsByItemId(List<TargetKey> items) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (TargetKey key : items) {
            if (key == null) {
                continue;
            }
            long current = counts.getOrDefault(key.itemId(), 0L);
            long next;
            try {
                next = Math.addExact(current, key.count());
            } catch (ArithmeticException overflow) {
                next = Long.MAX_VALUE;
            }
            counts.put(key.itemId(), next);
        }
        return counts;
    }
}
