package com.pengin0503.itemmilestonelog;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure-Java rules for F-140-12 (D-140-8) {@code presetState}: the merge/restore rules, the
 * {@code unlockAfterHours} threshold comparison (F-140-14) and the version-notice decision
 * (F-140-15), shared by every Minecraft version through {@code common/src/main/java}.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class PresetStateLogic {
    /** Ticks per real-time hour (20 ticks/second * 3600 seconds). */
    public static final double TICKS_PER_HOUR = 20.0d * 3600.0d;

    private PresetStateLogic() {
    }

    /**
     * F-140-12 restore rule: merges two states for the same preset ID. {@code autoApplied} is
     * OR-ed and {@code lastSeenVersion} takes the larger value. A missing side is treated as
     * {@link PresetState#EMPTY}.
     */
    public static PresetState merge(PresetState current, PresetState incoming) {
        if (current == null) {
            return incoming != null ? incoming : PresetState.EMPTY;
        }
        if (incoming == null) {
            return current;
        }
        return new PresetState(
                current.autoApplied() || incoming.autoApplied(),
                Math.max(current.lastSeenVersion(), incoming.lastSeenVersion()));
    }

    /**
     * F-140-12 restore rule applied to whole maps: every incoming entry is merged into
     * {@code target} without removing IDs that only exist on one side. No-op when
     * {@code incoming} is null or empty.
     */
    public static void mergeInto(
            Map<String, PresetState> target,
            Map<String, PresetState> incoming) {
        if (target == null || incoming == null || incoming.isEmpty()) {
            return;
        }
        for (Map.Entry<String, PresetState> entry : incoming.entrySet()) {
            String presetId = entry.getKey();
            if (presetId == null) {
                continue;
            }
            PresetState merged = merge(target.get(presetId), entry.getValue());
            target.put(presetId, merged);
        }
    }

    /**
     * Copy of {@code states} suitable for encoding. Returns an empty immutable map for an
     * empty/null input and otherwise preserves iteration order.
     */
    public static Map<String, PresetState> copyOf(Map<String, PresetState> states) {
        if (states == null || states.isEmpty()) {
            return Map.of();
        }
        return new LinkedHashMap<>(states);
    }

    /** Converts cumulative playtime ticks ({@code Stats.PLAY_TIME}) to real-time hours. */
    public static double ticksToHours(int playTicks) {
        if (playTicks <= 0) {
            return 0.0d;
        }
        return playTicks / TICKS_PER_HOUR;
    }

    /**
     * F-140-14 threshold check: the player's cumulative playtime has reached
     * {@code unlockAfterHours}. A missing threshold ({@code null}) never unlocks; the boundary is
     * inclusive ({@code playtime >= threshold}).
     */
    public static boolean isUnlockDue(Double unlockAfterHours, int playTicks) {
        if (unlockAfterHours == null
                || unlockAfterHours.isNaN()
                || unlockAfterHours.isInfinite()
                || unlockAfterHours < 0.0d
                || playTicks < 0) {
            return false;
        }
        return ticksToHours(playTicks) >= unlockAfterHours;
    }

    /**
     * F-140-15 decision: whether a versioned preset's current version is newer than the version
     * the player has already seen. The first involvement ({@code state} missing or
     * {@code lastSeenVersion == 0}) is never a notice; it initializes the version instead.
     */
    public static boolean isVersionNoticeDue(
            Integer currentVersion,
            PresetState state) {
        return currentVersion != null
                && currentVersion > 0
                && state != null
                && state.lastSeenVersion() > 0
                && currentVersion > state.lastSeenVersion();
    }
}
