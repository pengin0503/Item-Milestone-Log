package com.pengin0503.itemmilestonelog.network;

/**
 * Preset summary entry sent to the client GUI in {@link SyncMilestonesPayload}.
 *
 * @param id          preset identifier (built-in ID or file stem)
 * @param displayName custom display name (empty for built-in presets, resolved via lang key on client)
 * @param itemCount   number of item IDs in the preset
 * @param builtin     whether this is a built-in preset
 */
public record PresetEntry(String id, String displayName, int itemCount, boolean builtin) {
}
