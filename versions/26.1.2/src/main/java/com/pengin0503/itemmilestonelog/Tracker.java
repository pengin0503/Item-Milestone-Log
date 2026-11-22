package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.network.SyncMilestonesPayload;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashSet;
import java.util.Set;

/**
 * Watches tracked items entering a player's possession and records the
 * cumulative playtime at that moment.
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
        if ((player.tickCount + (player.getUUID().hashCode() & 0xF)) % interval != 0) {
            return;
        }
        MilestoneData data = MilestoneData.get(player.level().getServer());
        MilestoneData.PlayerData pd = data.playerOrNull(player.getUUID());
        Set<String> targets = effectiveTargets(pd);
        if (targets.isEmpty()) {
            return;
        }
        Set<String> pending = new HashSet<>(targets);
        if (pd != null) {
            pending.removeAll(pd.records.keySet());
        }
        if (pending.isEmpty()) {
            return;
        }
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize() && !pending.isEmpty(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (pending.remove(id)) {
                record(player, data, id);
            }
        }
    }

    @SubscribeEvent
    public static void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            tryRecord(player, BuiltInRegistries.ITEM.getKey(event.getCrafting().getItem()).toString());
        }
    }

    @SubscribeEvent
    public static void onItemSmelted(PlayerEvent.ItemSmeltedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            tryRecord(player, BuiltInRegistries.ITEM.getKey(event.getSmelting().getItem()).toString());
        }
    }

    @SubscribeEvent
    public static void onItemPickup(ItemEntityPickupEvent.Post event) {
        if (event.getPlayer() instanceof ServerPlayer player) {
            tryRecord(player, BuiltInRegistries.ITEM.getKey(event.getItemEntity().getItem().getItem()).toString());
        }
    }

    private static void tryRecord(ServerPlayer player, String itemId) {
        MilestoneData data = MilestoneData.get(player.level().getServer());
        MilestoneData.PlayerData pd = data.playerOrNull(player.getUUID());
        if (!effectiveTargets(pd).contains(itemId)) {
            return;
        }
        if (pd != null && pd.records.containsKey(itemId)) {
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
                player.getGameProfile().name(), itemId, Playtime.format(ticks), ticks);

        Component itemName = MilestoneService.itemName(itemId);

        if (Config.NOTIFY_PLAYER.get()) {
            Component msg = Component.translatable("message.itemmilestonelog.recorded",
                    itemName, Playtime.format(ticks));
            player.sendSystemMessage(msg);
        }
        if (Config.BROADCAST.get()) {
            Component msg = Component.translatable("message.itemmilestonelog.recorded_broadcast",
                    player.getDisplayName(), itemName, Playtime.format(ticks));
            for (ServerPlayer other : player.level().getServer().getPlayerList().getPlayers()) {
                if (other != player) {
                    other.sendSystemMessage(msg);
                }
            }
        }
        if (player.connection != null && player.connection.hasChannel(SyncMilestonesPayload.TYPE)) {
            PacketDistributor.sendToPlayer(
                    player,
                    MilestoneService.createSyncPayload(player, false, null)
            );
        }
    }
}
