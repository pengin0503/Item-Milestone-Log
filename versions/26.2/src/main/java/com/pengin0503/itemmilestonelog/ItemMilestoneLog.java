package com.pengin0503.itemmilestonelog;

import com.mojang.logging.LogUtils;
import com.pengin0503.itemmilestonelog.gametest.MilestoneGameTestRegistration;
import com.pengin0503.itemmilestonelog.network.ModNetworking;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

/**
 * Item Milestone Log (Minecraft 26.2).
 */
@Mod(ItemMilestoneLog.MOD_ID)
public class ItemMilestoneLog {
    public static final String MOD_ID = "itemmilestonelog";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ItemMilestoneLog(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        modEventBus.addListener(ModNetworking::registerPayloads);
        modEventBus.addListener(MilestoneGameTestRegistration::register);
        NeoForge.EVENT_BUS.register(Tracker.class);
        NeoForge.EVENT_BUS.register(MilestoneCommands.class);
        NeoForge.EVENT_BUS.addListener(ItemMilestoneLog::onServerStarting);
        NeoForge.EVENT_BUS.addListener(ItemMilestoneLog::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ItemMilestoneLog::onServerStopping);

        LOGGER.info("Item Milestone Log loaded");
    }

    private static void onServerStarting(ServerStartingEvent event) {
        int custom = Presets.reload();
        LOGGER.info("[ItemMilestoneLog] Loaded {} preset(s) ({} custom)", Presets.names().size(), custom);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        if (!event.getServer().isDedicatedServer()) {
            DiscordNotifier.warnIfWebhookConfiguredInSingleplayer();
        }
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        Tracker.clearPendingNotifications();
        DiscordNotifier.shutdown();
    }
}
