package com.pengin0503.itemmilestonelog;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Pure-Java new-record detection for F-150-70 (N67), shared with the PR-3 HUD snapshot so the
 * screenshot path does not keep a second record history (D-150-201). A "new record" is a key
 * present in the current recorded-id set but absent from the previous one. Removed keys are
 * ignored, and the first snapshot of a connection is a baseline (no screenshot on login).
 *
 * <p>Must stay Java 17 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class RecordDiff {
    /** Multiple arrivals in the same second are captured at most once. */
    public static final long CAPTURE_DEBOUNCE_MILLIS = 1000L;

    private RecordDiff() {
    }

    /** Copy of {@code current}, used to seed the baseline when a connection starts. */
    public static Set<String> baseline(Collection<String> current) {
        return current == null ? new LinkedHashSet<>() : new LinkedHashSet<>(current);
    }

    /** Returns keys newly present in {@code current}; keys that disappeared never trigger. */
    public static Set<String> added(Collection<String> previous, Collection<String> current) {
        Set<String> result = new LinkedHashSet<>();
        if (current == null) {
            return result;
        }
        Set<String> before = previous == null ? Set.of() : new LinkedHashSet<>(previous);
        for (String key : current) {
            if (key != null && !before.contains(key)) {
                result.add(key);
            }
        }
        return result;
    }

    /** True when one capture is due; a backwards clock is treated as due. */
    public static boolean captureDue(long lastCaptureMillis, long nowMillis) {
        return nowMillis < lastCaptureMillis
                || nowMillis - lastCaptureMillis >= CAPTURE_DEBOUNCE_MILLIS;
    }

    /** Merges any number of same-second arrivals into a single capture decision. */
    public static boolean shouldCapture(Collection<String> previous, Collection<String> current,
                                        long lastCaptureMillis, long nowMillis) {
        return !added(previous, current).isEmpty() && captureDue(lastCaptureMillis, nowMillis);
    }
}
