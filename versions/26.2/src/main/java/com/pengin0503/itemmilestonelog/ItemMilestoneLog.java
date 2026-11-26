package com.pengin0503.itemmilestonelog;

import com.mojang.logging.LogUtils;
import com.pengin0503.itemmilestonelog.compat.McCompat;
import com.pengin0503.itemmilestonelog.gametest.MilestoneGameTestRegistration;
import com.pengin0503.itemmilestonelog.network.ModNetworking;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
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
        modContainer.registerConfig(McCompat.config().type(), Config.SPEC);

        McCompat.triggers().register(modEventBus);
        modEventBus.addListener(ModNetworking::registerPayloads);
        modEventBus.addListener(MilestoneGameTestRegistration::register);
        // F43: PermissionAPI posts PermissionGatherEvent.Nodes on the NeoForge game event bus
        // (NeoForge.EVENT_BUS), never on the mod event bus; a game event listener on the mod bus
        // is rejected with IllegalArgumentException during mod construction.
        NeoForge.EVENT_BUS.addListener(MilestonePermissions::register);
        NeoForge.EVENT_BUS.register(Tracker.class);
        NeoForge.EVENT_BUS.register(MilestoneCommands.class);
        // F-140-2 / review 4-1 defense in depth: run our corruption-evacuation hook before
        // other mods' ServerStarting listeners so the on-disk SavedData is preserved first.
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ItemMilestoneLog::onServerStarting);
        NeoForge.EVENT_BUS.addListener(ItemMilestoneLog::onAddServerReloadListeners);
        NeoForge.EVENT_BUS.addListener(ItemMilestoneLog::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ItemMilestoneLog::onServerTick);
        NeoForge.EVENT_BUS.addListener(ItemMilestoneLog::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ItemMilestoneLog::onServerStopped);
        NeoForge.EVENT_BUS.addListener(MilestoneBackup::onLevelSave);

        LOGGER.info("Item Milestone Log loaded");
    }

    private static void onServerStarting(ServerStartingEvent event) {
        // F-140-3 (N45): fresh daemon I/O executor for every server session.
        MilestoneServerStop.onServerStarting();
        int custom = Presets.reload();
        LOGGER.info("[ItemMilestoneLog] Loaded {} preset(s) ({} custom)", Presets.names().size(), custom);
        MilestoneService.warnUnknownFirstJoinPresets();
        MilestoneService.warnUnknownNextPresets();
        MilestoneBackup.initForServer(event.getServer());
        // F-140-2 (N141): preserve the on-disk file before the first save can overwrite it.
        CorruptionEvacuation.onServerStarting(event.getServer());
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
        // F-140-4 (N149): fixed drain -> final backup -> existing clears -> executor shutdown.
        MilestoneServerStop.onServerStopping(event.getServer());
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        // F-140-4: clear executor/import state so the next world creates a fresh executor.
        MilestoneServerStop.onServerStopped();
        MilestoneBackup.resetState();
        DiscordNotifier.resetDigestThrottler();
        // F21: data pack presets belong to this world; do not carry them into the next one.
        Presets.clearDatapackPresets();
    }
}
