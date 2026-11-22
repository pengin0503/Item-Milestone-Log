package com.pengin0503.itemmilestonelog.network;

import com.pengin0503.itemmilestonelog.api.TargetKey;

/**
 * One target item entry (item + quantity) sent to the client GUI, distinguishing personal
 * targets from server-configured global default targets.
 */
public record TargetEntry(String itemId, int count, boolean personal, boolean defaultTarget) {
    public TargetKey key() {
        return new TargetKey(itemId, count);
    }
}
