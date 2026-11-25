package com.pengin0503.itemmilestonelog.compat;

/**
 * Data pack preset reload listener registration. The event type differs between loader
 * generations, so registration goes through this interface (1.20.1-forge uses
 * {@code AddReloadListenerEvent}).
 */
public interface ReloadCompat {
    /**
     * Registers the datapack preset reload listener on the game event bus.
     */
    void register();
}
