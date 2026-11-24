package com.pengin0503.itemmilestonelog;

import com.mojang.logging.LogUtils;
import com.pengin0503.itemmilestonelog.gametest.MilestoneGameTestRegistration;
import com.pengin0503.itemmilestonelog.network.ModNetworking;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
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

        ModTriggers.register(modEventBus);
        modEventBus.addListener(ModNetworking::registerPayloads);
        modEventBus.addListener(MilestoneGameTestRegistration::register);
        NeoForge.EVENT_BUS.register(Tracker.class);
        NeoForge.EVENT_BUS.register(MilestoneCommands.class);
        NeoForge.EVENT_BUS.addListener(ItemMilestoneLog::onServerStarting);
        NeoForge.EVENT_BUS.addListener(ItemMilestoneLog::onAddServerReloadListeners);
        NeoForge.EVENT_BUS.addListener(ItemMilestoneLog::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ItemMilestoneLog::onServerTick);
        NeoForge.EVENT_BUS.addListener(ItemMilestoneLog::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ItemMilestoneLog::onServerStopped);
        NeoForge.EVENT_BUS.addListener(MilestoneBackup::onLevelSave);

        LOGGER.info("Item Milestone Log loaded");
    }

    private static void onServerStarting(ServerStartingEvent event) {
        int custom = Presets.reload();
        LOGGER.info("[ItemMilestoneLog] Loaded {} preset(s) ({} custom)", Presets.names().size(), custom);
        MilestoneService.warnUnknownFirstJoinPresets();
        MilestoneBackup.initForServer(event.getServer());
    }

    /**
     * F21: data pack presets reload with the server's data packs (initial load and {@code /reload}),
     * not with {@code /milestonelog preset reload}, which only re-reads the config folder.
     */
    private static void onAddServerReloadListeners(AddServerReloadListenersEvent event) {
        event.addListener(Identifier.fromNamespaceAndPath(MOD_ID, "presets"), new DatapackPresetReloadListener());
    }

    private static void onServerStarted(ServerStartedEvent event) {
        if (!event.getServer().isDedicatedServer()) {
            DiscordNotifier.warnIfWebhookConfiguredInSingleplayer();
        }
        ScoreboardSync.refreshAll(event.getServer());
        DiscordNotifier.checkDigestOnServerStart(event.getServer());
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        DiscordNotifier.tickDigest(event.getServer());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        MilestoneBackup.onServerStopping(event.getServer());
        Tracker.clearPendingNotifications();
        MilestoneCommandRunner.clearPending();
        MilestoneService.UNDO_SNAPSHOTS.clear();
        ScoreboardSync.resetWarningState();
        DiscordNotifier.shutdown();
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        MilestoneBackup.resetState();
        DiscordNotifier.resetDigestThrottler();
        // F21: data pack presets belong to this world; do not carry them into the next one.
        Presets.clearDatapackPresets();
    }
}
