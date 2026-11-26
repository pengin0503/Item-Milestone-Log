package com.pengin0503.itemmilestonelog;

import com.mojang.logging.LogUtils;
import com.pengin0503.itemmilestonelog.client.ItemMilestoneLogClient;
import com.pengin0503.itemmilestonelog.compat.McCompat;
import com.pengin0503.itemmilestonelog.network.ModNetworking;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Item Milestone Log (Forge 1.20.1)
 *
 * Records the cumulative playtime (the vanilla "Time Played" statistic,
 * Stats.PLAY_TIME) at which a player first obtains each of their personally
 * tracked target items.
 *
 * All tracking, storage and commands run on the (integrated or dedicated)
 * server, and the GUI network channel is optional so vanilla clients can
 * still join a server that has this mod installed.
 */
@Mod(ItemMilestoneLog.MOD_ID)
public class ItemMilestoneLog {
    public static final String MOD_ID = "itemmilestonelog";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ItemMilestoneLog() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // F2: 1.20.1 registers the criterion trigger globally via CriteriaTriggers.register.
        McCompat.triggers().register(modEventBus);
        // Optional GUI channel: vanilla clients are accepted, senders check isRemotePresent.
        ModNetworking.registerPayloads();
        // PHASE5-TODO: GameTest registration is a stretch goal and is not ported in Phase 2a.

        // F43: PermissionAPI posts PermissionGatherEvent.Nodes on the Forge game event bus,
        // never on the mod event bus.
        MinecraftForge.EVENT_BUS.addListener(MilestonePermissions::register);
        MinecraftForge.EVENT_BUS.register(Tracker.class);
        MinecraftForge.EVENT_BUS.register(MilestoneCommands.class);
        // F-140-2 / review 4-1 defense in depth: run our corruption-evacuation hook before
        // other mods' ServerStarting listeners so the on-disk SavedData is preserved first.
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ItemMilestoneLog::onServerStarting);
        // F21: datapack preset reload listener (AddReloadListenerEvent, Forge game bus).
        McCompat.reload().register();
        MinecraftForge.EVENT_BUS.addListener(ItemMilestoneLog::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(ItemMilestoneLog::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(ItemMilestoneLog::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(ItemMilestoneLog::onServerStopped);
        MinecraftForge.EVENT_BUS.addListener(MilestoneBackup::onLevelSave);

        // Phase 4b client init (key mappings, client tick, screens, tooltips). Forge has
        // no dist-specific @Mod class, so the client bootstrap is loaded only through this
        // DistExecutor guard: a dedicated server never touches client rendering classes.
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ItemMilestoneLogClient::init);

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

    private static void onServerStarted(ServerStartedEvent event) {
        if (!event.getServer().isDedicatedServer()) {
            DiscordNotifier.warnIfWebhookConfiguredInSingleplayer();
        }
        ScoreboardSync.refreshAll(event.getServer());
        DiscordNotifier.checkDigestOnServerStart(event.getServer());
    }

    private static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
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
