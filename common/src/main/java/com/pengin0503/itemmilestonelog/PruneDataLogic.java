package com.pengin0503.itemmilestonelog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Pure-Java eligibility and timestamp-resolution logic for {@code /milestonelog prunedata} (F37),
 * shared across all Minecraft versions via {@code common/src/main/java}.
 *
 * <p>A player's data is eligible for pruning iff ALL of the following hold:
 * <ul>
 *   <li>0 recorded milestones, 0 personal targets, and empty {@code carriedFromCreative},
 *       {@code progressNotified}, {@code completedPresets} and {@code presetState} collections</li>
 *   <li>all personal settings are at their defaults ({@code muteBroadcasts}, {@code hideFromLookup},
 *       {@code muteProgressAlerts}, and {@code excludeFromDiscord} are all {@code false})</li>
 *   <li>the player is not currently online</li>
 *   <li>a last-seen timestamp is known ({@code > 0}) and is strictly more than {@code days} days
 *       before {@code nowMillis}</li>
 * </ul>
 *
 * <p>Cosmetic-only data ({@code lastKnownName}, {@code firstJoinHintShown}) never blocks pruning.
 * {@code lastSeen} is the inactivity timestamp, not functional state.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class PruneDataLogic {
    /** Minimum allowed value for {@code <days>} in {@code /milestonelog prunedata}. */
    public static final int MIN_DAYS = 1;
    /** Maximum allowed value for {@code <days>} in {@code /milestonelog prunedata}. */
    public static final int MAX_DAYS = 3650;
    /** Number of milliseconds in one 24-hour day. */
    public static final long MILLIS_PER_DAY = 86_400_000L;
    /** Sentinel value indicating that the player's last-seen time is unknown. */
    public static final long UNKNOWN_LAST_SEEN = 0L;

    /** Snapshot of the per-player fields inspected by {@link #isPruneTarget}. */
    public record PlayerState(
            int recordCount,
            int personalTargetCount,
            boolean carriedFromCreativeEmpty,
            boolean muteBroadcasts,
            boolean hideFromLookup,
            boolean muteProgressAlerts,
            boolean excludeFromDiscord,
            boolean progressNotifiedEmpty,
            boolean completedPresetsEmpty,
            boolean presetStateEmpty) {
    }

    private PruneDataLogic() {
    }

    /**
     * Returns whether the player's stored data has no records, no personal targets, no creative-carry
     * markers, no progress-notification thresholds, no completed-preset entries and no per-preset
     * state, with all personal settings at their defaults. Cosmetic fields are deliberately ignored.
     */
    public static boolean isEmptyWithDefaultSettings(PlayerState state) {
        if (state == null) {
            return false;
        }
        return state.recordCount() == 0
                && state.personalTargetCount() == 0
                && state.carriedFromCreativeEmpty()
                && !state.muteBroadcasts()
                && !state.hideFromLookup()
                && !state.muteProgressAlerts()
                && !state.excludeFromDiscord()
                && state.progressNotifiedEmpty()
                && state.completedPresetsEmpty()
                && state.presetStateEmpty();
    }

    /**
     * Pure target-eligibility judgment for {@code /milestonelog prunedata <days>}.
     *
     * @param state          snapshot of the player's stored data and personal settings
     * @param online         whether the player is currently online
     * @param lastSeenMillis resolved last-seen epoch millis, or {@code <= 0} if unknown
     * @param nowMillis      current wall-clock epoch millis
     * @param days           inactivity threshold in days ({@link #MIN_DAYS}..{@link #MAX_DAYS})
     * @return {@code true} iff this player's data should be pruned
     */
    public static boolean isPruneTarget(
            PlayerState state,
            boolean online,
            long lastSeenMillis,
            long nowMillis,
            int days) {
        if (days < MIN_DAYS || days > MAX_DAYS) {
            return false;
        }
        if (online || !isEmptyWithDefaultSettings(state)) {
            return false;
        }
        if (lastSeenMillis <= UNKNOWN_LAST_SEEN || nowMillis <= lastSeenMillis) {
            return false;
        }
        long thresholdMillis = (long) days * MILLIS_PER_DAY;
        return (nowMillis - lastSeenMillis) > thresholdMillis;
    }

    /**
     * Resolves a player's effective last-seen timestamp in epoch millis:
     * uses {@code storedLastSeen} when {@code > 0}; otherwise falls back to the last-modified time
     * of the vanilla player data file {@code <playerDataDir>/<uuid>.dat}. Returns
     * {@link #UNKNOWN_LAST_SEEN} ({@code 0}) if neither is available.
     *
     * @param storedLastSeen {@code PlayerData.lastSeen} (0 when absent / pre-1.1.0)
     * @param playerDataDir  world player-data directory from {@code LevelResource.PLAYER_DATA_DIR}
     * @param uuid           player UUID
     */
    public static long resolveLastSeen(long storedLastSeen, Path playerDataDir, UUID uuid) {
        if (storedLastSeen > UNKNOWN_LAST_SEEN) {
            return storedLastSeen;
        }
        if (playerDataDir == null || uuid == null) {
            return UNKNOWN_LAST_SEEN;
        }
        Path datFile = playerDataDir.resolve(uuid + ".dat");
        if (!Files.isRegularFile(datFile)) {
            return UNKNOWN_LAST_SEEN;
        }
        try {
            long mtime = Files.getLastModifiedTime(datFile).toMillis();
            return mtime > UNKNOWN_LAST_SEEN ? mtime : UNKNOWN_LAST_SEEN;
        } catch (IOException | SecurityException ignored) {
            return UNKNOWN_LAST_SEEN;
        }
    }
}
