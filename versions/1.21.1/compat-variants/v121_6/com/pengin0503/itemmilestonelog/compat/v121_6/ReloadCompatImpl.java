package com.pengin0503.itemmilestonelog.compat.v121_6;

import com.pengin0503.itemmilestonelog.DatapackPresetReloadListener;
import com.pengin0503.itemmilestonelog.ItemMilestoneLog;
import com.pengin0503.itemmilestonelog.compat.ReloadCompat;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;

/**
 * NeoForge 21.4+ {@link ReloadCompat}: {@code AddReloadListenerEvent} was removed in 21.4;
 * listeners are registered by id through {@link AddServerReloadListenersEvent}.
 */
public final class ReloadCompatImpl implements ReloadCompat {
    @Override
    public void register() {
        NeoForge.EVENT_BUS.addListener(ReloadCompatImpl::onAddServerReloadListeners);
    }

    /**
     * F21: data pack presets reload with the server's data packs (initial load and {@code /reload}),
     * not with {@code /milestonelog preset reload}, which only re-reads the config folder.
     */
    private static void onAddServerReloadListeners(AddServerReloadListenersEvent event) {
        event.addListener(ResourceLocation.fromNamespaceAndPath(ItemMilestoneLog.MOD_ID, "presets"),
                new DatapackPresetReloadListener());
    }
}
