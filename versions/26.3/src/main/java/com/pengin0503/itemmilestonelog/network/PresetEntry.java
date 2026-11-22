package com.pengin0503.itemmilestonelog.network;

/**
 * Preset summary entry sent to the client GUI in {@link SyncMilestonesPayload}.
 */
public record PresetEntry(String id, String displayName, int itemCount, boolean builtin) {
}
