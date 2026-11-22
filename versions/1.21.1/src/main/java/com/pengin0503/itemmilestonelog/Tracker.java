package com.pengin0503.itemmilestonelog;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Watches tracked items entering a player's possession and records the
 * cumulative playtime at that moment.
 *
 * Detection is deliberately possession-based ("first time the item is in
 * your inventory") rather than crafting-based, so items produced by
 * machines, automation or modded crafting (e.g. Mekanism, AE2 autocrafting
 * in modpacks such as All the Mods 10) are recorded as well.
 *
 * With {@code ignoreCreativeAndSpectator} (the default), nothing is recorded in
 * creative or spectator mode. The items a player still carries when switching back
 * are remembered (MilestoneData.PlayerData#carriedFromCreative) and not recorded
 * while any of them is still in the inventory; the scan forgets an item once it is
 * gone, and the next one to arrive is recorded as usual.
 */
public final class Tracker {
    private Tracker() {
    }

    /** Effective targets for a player: global defaults from config + personal targets. */
    public static Set<String> effectiveTargets(MilestoneData.PlayerData pd) {
        Set<String> all = new HashSet<>();
        for (String s : Config.DEFAULT_TARGETS.get()) {
            all.add(s);
        }
        if (pd != null) {
            all.addAll(pd.targets);
        }
        return all;
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        int interval = Config.SCAN_INTERVAL_TICKS.get();
        // Stagger scans a little per player so not everyone is scanned on the same tick.
        if ((player.tickCount + (player.getUUID().hashCode() & 0xF)) % interval != 0) {
            return;
        }
        if (recordingPaused(player)) {
            return;
        }
        MilestoneData data = MilestoneData.get(player.server);
        MilestoneData.PlayerData pd = data.playerOrNull(player.getUUID());
        Set<String> pending = new HashSet<>(effectiveTargets(pd));
        if (pd != null) {
            pending.removeAll(pd.records.keySet());
        }
        Set<String> carried = carriedFromCreative(pd);
        if (pending.isEmpty() && carried.isEmpty()) {
            return;
        }
        Set<String> stillCarried = new HashSet<>();
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            String id = itemId(stack);
            if (carried.contains(id)) {
                stillCarried.add(id);
            } else if (pending.remove(id)) {
                record(player, data, id);
            }
        }
        // Forget the items carried out of creative mode that are gone now.
        if (!carried.isEmpty() && carried.retainAll(stillCarried)) {
            data.setDirty();
        }
    }

    /**
     * Leaving creative or spectator mode: remember what the player still carries, so
     * those items are not recorded as if they had been obtained in survival.
     * LOWEST priority, so a listener that cancels the change runs first.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onGameModeChange(PlayerEvent.PlayerChangeGameModeEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !Config.IGNORE_CREATIVE_AND_SPECTATOR.get()) {
            return;
        }
        if (!isCreativeOrSpectator(event.getCurrentGameMode()) || isCreativeOrSpectator(event.getNewGameMode())) {
            return;
        }
        Set<String> carried = new LinkedHashSet<>();
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                carried.add(itemId(stack));
            }
        }
        MilestoneData data = MilestoneData.get(player.server);
        MilestoneData.PlayerData existing = data.playerOrNull(player.getUUID());
        if (existing != null) {
            carried.removeAll(existing.records.keySet());
        }
        if (carried.isEmpty()) {
            return;
        }
        if (data.player(player.getUUID()).carriedFromCreative.addAll(carried)) {
            data.setDirty();
        }
    }

    @SubscribeEvent
    public static void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            tryRecord(player, itemId(event.getCrafting()));
        }
    }

    @SubscribeEvent
    public static void onItemSmelted(PlayerEvent.ItemSmeltedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            tryRecord(player, itemId(event.getSmelting()));
        }
    }

    @SubscribeEvent
    public static void onItemPickup(ItemEntityPickupEvent.Post event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        // Post fires after the pickup: the item entity's own stack has already been
        // shrunk (and is empty, i.e. AIR, after a full pickup), so read the copy of
        // the stack from before the pickup and only record if something was taken.
        ItemStack before = event.getOriginalStack();
        if (before.isEmpty() || event.getItemEntity().getItem().getCount() >= before.getCount()) {
            return;
        }
        tryRecord(player, itemId(before));
    }

    /** True while nothing is recorded for the player: creative or spectator mode, if so configured. */
    static boolean recordingPaused(ServerPlayer player) {
        return Config.IGNORE_CREATIVE_AND_SPECTATOR.get() && (player.isCreative() || player.isSpectator());
    }

    private static boolean isCreativeOrSpectator(GameType mode) {
        return mode == GameType.CREATIVE || mode == GameType.SPECTATOR;
    }

    /** The player's items carried out of creative mode, or an empty set if they don't count. */
    private static Set<String> carriedFromCreative(MilestoneData.PlayerData pd) {
        if (pd == null || !Config.IGNORE_CREATIVE_AND_SPECTATOR.get()) {
            return Set.of();
        }
        return pd.carriedFromCreative;
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static void tryRecord(ServerPlayer player, String itemId) {
        if (recordingPaused(player)) {
            return;
        }
        MilestoneData data = MilestoneData.get(player.server);
        MilestoneData.PlayerData pd = data.playerOrNull(player.getUUID());
        if (!effectiveTargets(pd).contains(itemId)) {
            return;
        }
        if (pd != null && (pd.records.containsKey(itemId) || carriedFromCreative(pd).contains(itemId))) {
            return;
        }
        record(player, data, itemId);
    }

    private static void record(ServerPlayer player, MilestoneData data, String itemId) {
        MilestoneData.PlayerData pd = data.player(player.getUUID());
        if (pd.records.containsKey(itemId)) {
            return;
        }
        int ticks = Playtime.currentTicks(player);
        pd.records.put(itemId, ticks);
        data.setDirty();

        ItemMilestoneLog.LOGGER.info("[ItemMilestoneLog] {} first obtained {} at playtime {} ({} ticks)",
                player.getGameProfile().getName(), itemId, Playtime.format(ticks), ticks);

        Component itemName = MilestoneService.itemName(itemId);

        if (Config.NOTIFY_PLAYER.get()) {
            Component msg = ServerLang.tr("message.itemmilestonelog.recorded",
                    itemName, Playtime.format(ticks));
            player.sendSystemMessage(msg);
        }
        if (Config.BROADCAST.get()) {
            Component msg = ServerLang.tr("message.itemmilestonelog.recorded_broadcast",
                    player.getDisplayName(), itemName, Playtime.format(ticks));
            for (ServerPlayer other : player.server.getPlayerList().getPlayers()) {
                if (other != player) {
                    other.sendSystemMessage(msg);
                }
            }
        }
        if (player.connection != null && player.connection.hasChannel(com.pengin0503.itemmilestonelog.network.SyncMilestonesPayload.TYPE)) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                    player,
                    MilestoneService.createSyncPayload(player, false, null)
            );
        }
    }
}
