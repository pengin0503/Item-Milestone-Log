package com.pengin0503.itemmilestonelog;

import java.util.Optional;
import java.util.UUID;

/**
 * Pure-Java condition-matching and retroactive world-first logic for F2 (the
 * {@code itemmilestonelog:milestone_recorded} advancement criterion trigger), shared across
 * all Minecraft versions via {@code common/src/main/java}.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class MilestoneTriggerLogic {
    private MilestoneTriggerLogic() {
    }

    /**
     * Evaluates whether a milestone recording matches a trigger instance's {@link Optional}
     * conditions, delegating to
     * {@link #matches(String, Integer, Integer, Boolean, String, int, boolean, boolean)}.
     *
     * <ul>
     *   <li>{@code requiredItem}: exact string match against {@code actualItemId} (tag targets pass
     *       their {@code "#..."} ID as-is; no tag-membership matching). Empty matches any item.</li>
     *   <li>{@code minCount} / {@code maxCount}: inclusive bounds on {@code actualCount}. Empty
     *       bounds are unrestricted; equal min and max represent an exact count match.</li>
     *   <li>{@code requiredWorldFirst}: matched against {@code worldFirstEnabled && actualWorldFirst}.
     *       When {@code worldFirstEnabled} is {@code false}, {@code world_first: true} never matches.
     *       Empty matches regardless of world-first status.</li>
     * </ul>
     */
    public static boolean matchesOptional(
            Optional<String> requiredItem,
            Optional<Integer> minCount,
            Optional<Integer> maxCount,
            Optional<Boolean> requiredWorldFirst,
            String actualItemId,
            int actualCount,
            boolean actualWorldFirst,
            boolean worldFirstEnabled) {
        return matches(
                requiredItem != null ? requiredItem.orElse(null) : null,
                minCount != null ? minCount.orElse(null) : null,
                maxCount != null ? maxCount.orElse(null) : null,
                requiredWorldFirst != null ? requiredWorldFirst.orElse(null) : null,
                actualItemId,
                actualCount,
                actualWorldFirst,
                worldFirstEnabled);
    }

    /**
     * Nullable-parameter variant of
     * {@link #matchesOptional(Optional, Optional, Optional, Optional, String, int, boolean, boolean)}.
     */
    public static boolean matches(
            String requiredItem,
            Integer minCount,
            Integer maxCount,
            Boolean requiredWorldFirst,
            String actualItemId,
            int actualCount,
            boolean actualWorldFirst,
            boolean worldFirstEnabled) {
        if (requiredItem != null && !requiredItem.equals(actualItemId)) {
            return false;
        }
        if (minCount != null && actualCount < minCount) {
            return false;
        }
        if (maxCount != null && actualCount > maxCount) {
            return false;
        }
        if (requiredWorldFirst != null) {
            boolean effectiveWorldFirst = worldFirstEnabled && actualWorldFirst;
            if (requiredWorldFirst.booleanValue() != effectiveWorldFirst) {
                return false;
            }
        }
        return true;
    }

    /**
     * Determines the {@code world_first} flag for retroactive trigger evaluation (player login or
     * {@code /reload}): {@code true} only when world-first tracking is enabled and the player
     * currently holds the world-first record for that target key.
     */
    public static boolean isRetroactiveWorldFirst(
            UUID playerId,
            UUID worldFirstHolderId,
            boolean worldFirstEnabled) {
        return worldFirstEnabled && playerId != null && playerId.equals(worldFirstHolderId);
    }
}
