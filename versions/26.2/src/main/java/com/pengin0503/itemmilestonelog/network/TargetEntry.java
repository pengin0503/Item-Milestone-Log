package com.pengin0503.itemmilestonelog.network;

/**
 * One target item entry sent to the client GUI, distinguishing personal
 * targets from server-configured global default targets.
 */
public record TargetEntry(String itemId, boolean personal, boolean defaultTarget) {
}
