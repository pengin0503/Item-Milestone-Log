package com.pengin0503.itemmilestonelog.compat.v120_1;

import com.pengin0503.itemmilestonelog.DatapackPresetReloadListener;
import com.pengin0503.itemmilestonelog.compat.ReloadCompat;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;

/**
 * 1.20.1-forge {@link ReloadCompat}: the legacy {@link AddReloadListenerEvent} on the
 * Forge game event bus.
 */
public final class ReloadCompatImpl implements ReloadCompat {
    @Override
    public void register() {
        MinecraftForge.EVENT_BUS.addListener(ReloadCompatImpl::onAddReloadListener);
    }

    /**
     * F21: data pack presets reload with the server's data packs (initial load and {@code /reload}),
     * not with {@code /milestonelog preset reload}, which only re-reads the config folder.
     */
    private static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new DatapackPresetReloadListener());
    }
}
