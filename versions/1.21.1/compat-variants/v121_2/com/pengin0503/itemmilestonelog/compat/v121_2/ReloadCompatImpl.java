package com.pengin0503.itemmilestonelog.compat.v121_2;

import com.pengin0503.itemmilestonelog.DatapackPresetReloadListener;
import com.pengin0503.itemmilestonelog.compat.ReloadCompat;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

/**
 * 1.21 / 1.21.1 {@link ReloadCompat}: the legacy {@link AddReloadListenerEvent}.
 */
public final class ReloadCompatImpl implements ReloadCompat {
    @Override
    public void register() {
        NeoForge.EVENT_BUS.addListener(ReloadCompatImpl::onAddReloadListener);
    }

    /**
     * F21: data pack presets reload with the server's data packs (initial load and {@code /reload}),
     * not with {@code /milestonelog preset reload}, which only re-reads the config folder.
     */
    private static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new DatapackPresetReloadListener());
    }
}
