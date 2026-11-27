package com.pengin0503.itemmilestonelog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure-Java data shaping for F-150-63 (N25): turns the local pin order plus the existing sync
 * snapshots into at most {@code pinHudMaxRows} one-line rows. No new S2C channel and no Minecraft
 * type is used; the version-specific overlay only renders the already built rows.
 *
 * <p>Pins whose id is absent from the current snapshot are skipped instead of showing stale data;
 * old pins remain in the file so they can reappear on reconnect (F-150-62-3). An empty pin list
 * always yields no rows. Cached/offline data is labelled through {@link #OFFLINE_KEY} rather than
 * by guessing server state.
 */
public final class HudRowPlan {
    /** Prefix shown on every row when the data came from the local cache. */
    public static final String OFFLINE_KEY = "gui.itemmilestonelog.hud.offline";
    public static final int MAX_ROWS = 10;
    public static final int DEFAULT_MAX_ROWS = 5;

    private HudRowPlan() {
    }

    public enum Status {
        TARGET,
        ACHIEVED
    }

    /** One precomputed HUD line; the overlay never rebuilds text while rendering. */
    public record HudRow(String encodedId, String displayName, Status status, int progress, boolean offline) {
        public HudRow {
            displayName = displayName == null || displayName.isEmpty() ? encodedId : displayName;
            progress = Math.max(0, progress);
        }
    }

    public static int clampMaxRows(int value) {
        return Math.max(1, Math.min(MAX_ROWS, value));
    }

    /**
     * @param pins          local pins in their stored order
     * @param availableIds  encoded ids present in the live sync or offline cache; {@code null} means
     *                      no snapshot at all, which produces no rows
     * @param progress      encoded id to progress value (from the sync payload)
     * @param recordedIds   encoded ids the player has already recorded
     * @param displayNames  optional id to precomputed display name (no per-frame translation)
     * @param offline       true when the rows come from {@code LocalSyncCache}
     * @param maxRows       configured row limit, clamped to 1..10
     */
    public static List<HudRow> plan(List<PinStore.Pin> pins,
                                    Set<String> availableIds,
                                    Map<String, Integer> progress,
                                    Set<String> recordedIds,
                                    Map<String, String> displayNames,
                                    boolean offline,
                                    int maxRows) {
        if (pins == null || pins.isEmpty() || availableIds == null || availableIds.isEmpty()) {
            return List.of();
        }
        int limit = clampMaxRows(maxRows);
        List<HudRow> rows = new ArrayList<>(Math.min(limit, pins.size()));
        for (PinStore.Pin pin : pins) {
            if (rows.size() >= limit) {
                break;
            }
            String encoded = pin.encoded();
            if (!availableIds.contains(encoded)) {
                continue; // missing item: keep the pin, draw no stale row
            }
            int value = progress == null ? 0 : progress.getOrDefault(encoded, 0);
            boolean achieved = recordedIds != null && recordedIds.contains(encoded);
            String name = displayNames == null ? null : displayNames.get(encoded);
            rows.add(new HudRow(encoded, name,
                    achieved ? Status.ACHIEVED : Status.TARGET, value, offline));
        }
        return List.copyOf(rows);
    }

    /** Convenience overload with no localisation data and no offline prefix. */
    public static List<HudRow> plan(List<PinStore.Pin> pins, Set<String> availableIds) {
        return plan(pins, availableIds, Map.of(), Set.of(), Map.of(), false, DEFAULT_MAX_ROWS);
    }
}
