package com.pengin0503.itemmilestonelog;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure-Java progress alert evaluation logic for quantity targets (F1).
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class ProgressAlertLogic {
    public static final int MIN_THRESHOLD = 1;
    public static final int MAX_THRESHOLD = 99;
    public static final int MIN_STORED_THRESHOLD = 0;
    public static final int MIN_ALERT_COUNT_LOWER = 2;
    public static final int DEFAULT_MIN_COUNT = 10;

    /**
     * Result of evaluating a single unrecorded quantity target.
     *
     * @param newStoredThreshold value to store in {@code progressNotified}, or {@code null} if unchanged
     * @param notifyThreshold    threshold to alert the player about, or {@code null} for no notification
     * @param percent            floored integer percentage {@code floor(current * 100 / targetCount)}
     */
    public record Evaluation(Integer newStoredThreshold, Integer notifyThreshold, int percent) {
        public static final Evaluation NO_CHANGE = new Evaluation(null, null, 0);

        public boolean shouldStore() {
            return newStoredThreshold != null;
        }

        public boolean shouldNotify() {
            return notifyThreshold != null;
        }
    }

    private ProgressAlertLogic() {
    }

    /** True when {@code threshold} is a valid configured alert threshold (1..99). */
    public static boolean isValidThreshold(int threshold) {
        return threshold >= MIN_THRESHOLD && threshold <= MAX_THRESHOLD;
    }

    /** True when {@code threshold} is a valid persisted {@code progressNotified} value (0..99). */
    public static boolean isValidStoredThreshold(int threshold) {
        return threshold >= MIN_STORED_THRESHOLD && threshold <= MAX_THRESHOLD;
    }

    /** Filters to 1..99, deduplicates, and sorts ascending. */
    public static List<Integer> normalizeThresholds(List<? extends Integer> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<Integer> sorted = new TreeSet<>();
        for (Integer value : raw) {
            if (value != null && isValidThreshold(value)) {
                sorted.add(value);
            }
        }
        return sorted.isEmpty() ? List.of() : List.copyOf(sorted);
    }

    /** Computes {@code floor(currentCount * 100 / targetCount)} safely without int overflow. */
    public static int computePercent(int currentCount, int targetCount) {
        if (targetCount <= 0 || currentCount <= 0) {
            return 0;
        }
        long pct = ((long) currentCount * 100L) / (long) targetCount;
        return pct >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) pct;
    }

    /**
     * Evaluates whether an unrecorded quantity target should update {@code progressNotified}
     * and/or emit a progress alert.
     *
     * <ul>
     *   <li>Targets with {@code targetCount < Math.max(2, minCount)} are ineligible.</li>
     *   <li>Targets at 100% or above ({@code currentCount >= targetCount}) emit no alert
     *       (record confirmation handles completion).</li>
     *   <li>Baseline: when {@code alreadyNotifiedMax == null}, stores the highest crossed
     *       threshold (or {@code 0} if none) without notifying.</li>
     *   <li>Otherwise, if the highest crossed threshold exceeds {@code alreadyNotifiedMax},
     *       notifies once for that largest threshold and stores it.</li>
     * </ul>
     */
    public static Evaluation evaluate(
            int currentCount,
            int targetCount,
            List<Integer> thresholds,
            Integer alreadyNotifiedMax,
            int minCount) {
        int effectiveMinCount = Math.max(MIN_ALERT_COUNT_LOWER, minCount);
        if (targetCount < effectiveMinCount || thresholds == null || thresholds.isEmpty()) {
            return Evaluation.NO_CHANGE;
        }
        int clampedCurrent = Math.max(0, currentCount);
        if (clampedCurrent >= targetCount) {
            return Evaluation.NO_CHANGE;
        }
        int percent = computePercent(clampedCurrent, targetCount);
        boolean hasValidThreshold = false;
        int highestCrossed = 0;
        for (Integer threshold : thresholds) {
            if (threshold == null || !isValidThreshold(threshold)) {
                continue;
            }
            hasValidThreshold = true;
            if (threshold <= percent && threshold > highestCrossed) {
                highestCrossed = threshold;
            }
        }
        if (!hasValidThreshold) {
            return Evaluation.NO_CHANGE;
        }
        if (alreadyNotifiedMax == null) {
            return new Evaluation(highestCrossed, null, percent);
        }
        int previous = Math.max(MIN_STORED_THRESHOLD, alreadyNotifiedMax);
        if (highestCrossed > previous) {
            return new Evaluation(highestCrossed, highestCrossed, percent);
        }
        return Evaluation.NO_CHANGE;
    }
}
