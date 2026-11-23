package com.pengin0503.itemmilestonelog;

/**
 * Pure-Java access rules for {@code /milestonelog lookup <player>} (F6, with the F14 privacy flag),
 * shared across all Minecraft versions via {@code common/src/main/java}.
 *
 * <p>Rules (spec F6 / F14):
 * <ul>
 *   <li>Looking up yourself is always allowed.</li>
 *   <li>When {@code allowLookup} is false, only privileged sources ({@code MilestoneService.isPrivileged})
 *       may look up other players; everyone else gets the feature-disabled message.</li>
 *   <li>A player who hides their records ({@code hideFromLookup}) cannot be looked up by
 *       non-privileged other players ({@code lookup_hidden} message).</li>
 *   <li>Name suggestions are offered only for targets whose lookup would be {@link Decision#ALLOWED}.</li>
 * </ul>
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class LookupPolicy {
    /** Outcome of a lookup access check. */
    public enum Decision {
        /** Show the target's records. */
        ALLOWED,
        /** {@code allowLookup} is off and the viewer is not privileged: feature-disabled message. */
        DISABLED,
        /** The target hides their records from this viewer: {@code lookup_hidden} message. */
        HIDDEN
    }

    private LookupPolicy() {
    }

    /**
     * Whether the viewer may look up players other than themselves at all (ignoring per-target privacy).
     * Used to decide whether stored player names are suggested.
     */
    public static boolean canLookUpOthers(boolean allowLookup, boolean viewerPrivileged) {
        return allowLookup || viewerPrivileged;
    }

    /**
     * Decides whether a viewer may see a specific target's records.
     *
     * @param allowLookup      the {@code allowLookup} config value
     * @param viewerPrivileged whether the command source satisfies {@code MilestoneService.isPrivileged}
     * @param viewerIsTarget   whether the command source is the target player themself
     * @param targetHidden     whether the target has enabled {@code hideFromLookup}
     */
    public static Decision decide(boolean allowLookup, boolean viewerPrivileged, boolean viewerIsTarget, boolean targetHidden) {
        if (viewerIsTarget) {
            return Decision.ALLOWED;
        }
        if (!canLookUpOthers(allowLookup, viewerPrivileged)) {
            return Decision.DISABLED;
        }
        if (targetHidden && !viewerPrivileged) {
            return Decision.HIDDEN;
        }
        return Decision.ALLOWED;
    }

    /** Whether the target's name should appear in the viewer's {@code <player>} suggestions. */
    public static boolean shouldSuggest(boolean allowLookup, boolean viewerPrivileged, boolean viewerIsTarget, boolean targetHidden) {
        return decide(allowLookup, viewerPrivileged, viewerIsTarget, targetHidden) == Decision.ALLOWED;
    }
}
