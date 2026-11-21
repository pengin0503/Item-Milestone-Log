package com.pengin0503.itemmilestonelog;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Item Milestone Log
 *
 * Records the cumulative playtime (the vanilla "Time Played" statistic,
 * Stats.PLAY_TIME) at which a player first obtains each of their personally
 * tracked target items.
 *
 * The mod is server-side only in behaviour: all tracking, storage and
 * commands run on the (integrated or dedicated) server, so vanilla clients
 * can join a server that has this mod installed.
 */
@Mod(ItemMilestoneLog.MOD_ID)
public class ItemMilestoneLog {
    public static final String MOD_ID = "itemmilestonelog";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ItemMilestoneLog(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        NeoForge.EVENT_BUS.register(Tracker.class);
        NeoForge.EVENT_BUS.register(MilestoneCommands.class);

        LOGGER.info("Item Milestone Log loaded");
    }
}
