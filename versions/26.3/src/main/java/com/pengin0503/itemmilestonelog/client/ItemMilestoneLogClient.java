package com.pengin0503.itemmilestonelog.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.pengin0503.itemmilestonelog.ItemMilestoneLog;
import com.pengin0503.itemmilestonelog.network.ModNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-only entrypoint for Item Milestone Log (Minecraft 26.3).
 */
@Mod(value = ItemMilestoneLog.MOD_ID, dist = Dist.CLIENT)
public final class ItemMilestoneLogClient {
    public static final KeyMapping.Category KEY_CATEGORY = new KeyMapping.Category(
            Identifier.fromNamespaceAndPath(ItemMilestoneLog.MOD_ID, "general"));
    public static final String KEY_OPEN_GUI = "key." + ItemMilestoneLog.MOD_ID + ".open_gui";

    public static final KeyMapping OPEN_GUI_MAPPING = new KeyMapping(
            KEY_OPEN_GUI,
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_M,
            KEY_CATEGORY
    );

    public ItemMilestoneLogClient(IEventBus modEventBus) {
        ModNetworking.setClientSyncHandler(ClientMilestoneHandler::onSyncReceived);
        modEventBus.addListener(ItemMilestoneLogClient::onRegisterKeyMappings);
        NeoForge.EVENT_BUS.addListener(ItemMilestoneLogClient::onClientTick);
    }

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.registerCategory(KEY_CATEGORY);
        event.register(OPEN_GUI_MAPPING);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.screen() != null) {
            return;
        }
        while (OPEN_GUI_MAPPING.consumeClick()) {
            ClientMilestoneHandler.requestOpenGui();
        }
    }
}
