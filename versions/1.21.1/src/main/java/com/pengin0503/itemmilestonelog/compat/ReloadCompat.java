package com.pengin0503.itemmilestonelog.compat;

/**
 * N252: data pack preset reload listener registration. NeoForge 21.4 removed
 * {@code AddReloadListenerEvent} in favour of {@code AddServerReloadListenersEvent}
 * (which does not exist in 21.0 / 21.1), so registration goes through this interface.
 */
public interface ReloadCompat {
    /**
     * Registers the datapack preset reload listener on the NeoForge event bus; the event type
     * differs per NeoForge generation.
     */
    void register();
}
