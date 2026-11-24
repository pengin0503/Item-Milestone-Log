package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.api.WorldFirstRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure-Java selection logic for the F52 login world-first summary, shared across all
 * Minecraft versions via {@code common/src/main/java}.
 *
 * <p>The caller supplies the saved world-first map, the logging-in player's UUID, the
 * player's stored {@code lastSeen} value from before this login, and the current wall-clock
 * time. The selection keeps world firsts recorded in {@code (previousLastSeen, now]} that
 * are held by someone else, sorts them newest first (ties by {@link TargetKey}), and limits
 * the result to the newest {@value #MAX_ENTRIES} entries.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class LoginWorldFirstSummaryLogic {
    /** Maximum number of world-first entries shown in one login summary. */
    public static final int MAX_ENTRIES = 5;

    /**
     * Result of one login-summary selection.
     *
     * @param entries        the newest {@value #MAX_ENTRIES} qualifying world firsts, in display order
     * @param totalCount     all qualifying world firsts (the header count)
     * @param remainingCount qualifying world firsts omitted from {@link #entries}
     */
    public record Selection(List<WorldFirstRecord> entries, int totalCount, int remainingCount) {
        public Selection {
            entries = List.copyOf(entries);
        }

        /** Whether no world first qualified (nothing should be sent). */
        public boolean isEmpty() {
            return totalCount == 0;
        }
    }

    private static final Selection EMPTY = new Selection(List.of(), 0, 0);

    private LoginWorldFirstSummaryLogic() {
    }

    /**
     * Selects the world firsts to show when {@code viewerUuid} logs in.
     *
     * <p>A world first qualifies when all of the following hold:
     * <ul>
     *   <li>{@code previousLastSeen > 0} (the player has logged in before on a version that
     *       stored {@code lastSeen}); otherwise the selection is empty</li>
     *   <li>{@code epochMillis > previousLastSeen} and {@code epochMillis <= nowMillis}</li>
     *   <li>the holder UUID is not {@code viewerUuid}</li>
     * </ul>
     * Because {@code epochMillis == 0} is never greater than a positive {@code previousLastSeen},
     * legacy entries with an unknown timestamp never qualify.
     *
     * @param worldFirsts      saved world firsts (may be {@code null} or empty)
     * @param viewerUuid       the logging-in player's UUID
     * @param previousLastSeen the player's stored {@code lastSeen} before this login overwrote it
     * @param nowMillis        the current wall-clock time at login
     */
    public static Selection select(
            Map<TargetKey, WorldFirstRecord> worldFirsts,
            UUID viewerUuid,
            long previousLastSeen,
            long nowMillis) {
        if (previousLastSeen <= 0L || worldFirsts == null || worldFirsts.isEmpty()) {
            return EMPTY;
        }

        List<WorldFirstRecord> qualifying = new ArrayList<>();
        for (WorldFirstRecord worldFirst : worldFirsts.values()) {
            if (worldFirst == null) {
                continue;
            }
            long epochMillis = worldFirst.epochMillis();
            if (epochMillis <= previousLastSeen || epochMillis > nowMillis) {
                continue;
            }
            if (viewerUuid != null && Objects.equals(viewerUuid, worldFirst.playerUuid())) {
                continue;
            }
            qualifying.add(worldFirst);
        }
        if (qualifying.isEmpty()) {
            return EMPTY;
        }

        // Newest first; equal timestamps keep the stable TargetKey (item ID, then count) order.
        qualifying.sort(Comparator.comparingLong(WorldFirstRecord::epochMillis).reversed()
                .thenComparing(WorldFirstRecord::targetKey));

        int totalCount = qualifying.size();
        List<WorldFirstRecord> entries = totalCount <= MAX_ENTRIES
                ? List.copyOf(qualifying)
                : List.copyOf(qualifying.subList(0, MAX_ENTRIES));
        return new Selection(entries, totalCount, totalCount - entries.size());
    }
}
