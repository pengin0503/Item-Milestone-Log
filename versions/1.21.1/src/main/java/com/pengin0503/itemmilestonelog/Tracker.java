package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.MilestoneRecordedEvent;
import com.pengin0503.itemmilestonelog.network.SyncMilestonesPayload;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Watches tracked items entering a player's possession and records the
 * cumulative playtime, in-game day, and wall-clock time at that moment.
 */
public final class Tracker {
    private static volatile String lastWarnedSoundId = null;

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
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MilestoneData data = MilestoneData.get(player.server);
        MilestoneData.PlayerData pd = data.player(player.getUUID());
        String name = player.getGameProfile().getName();
        if (name != null && !name.equals(pd.lastKnownName)) {
            pd.lastKnownName = name;
            data.setDirty();
        }
        if (Config.SHOW_FIRST_JOIN_HINT.get() && !pd.firstJoinHintShown) {
            pd.firstJoinHintShown = true;
            data.setDirty();
            boolean hasMod = player.connection != null
                    && player.connection.hasChannel(SyncMilestonesPayload.TYPE);
            String key = hasMod
                    ? "message.itemmilestonelog.join_hint_mod"
                    : "message.itemmilestonelog.join_hint_vanilla";
            player.sendSystemMessage(ServerLang.tr(player, key));
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        int interval = Config.SCAN_INTERVAL_TICKS.get();
        // Spread player scans across the entire interval using floorMod.
        int phase = Math.floorMod(player.getUUID().hashCode(), interval);
        if ((player.tickCount + phase) % interval != 0) {
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
        long day = Playtime.currentDay(player.server);
        long epochMillis = System.currentTimeMillis();
        MilestoneRecord rec = new MilestoneRecord(ticks, day, epochMillis);

        String playerName = player.getGameProfile().getName();
        if (playerName != null && !playerName.equals(pd.lastKnownName)) {
            pd.lastKnownName = playerName;
        }
        pd.records.put(itemId, rec);
        boolean worldFirst = data.recordWorldFirstIfAbsent(itemId, player.getUUID(), playerName, rec);
        data.setDirty();

        ItemMilestoneLog.LOGGER.info("[ItemMilestoneLog] {} first obtained {} at playtime {} ({} ticks)",
                playerName, itemId, Playtime.formatRecord(rec), ticks);

        Component itemName = MilestoneService.itemName(itemId);

        if (Config.NOTIFY_PLAYER.get()) {
            Component msg = ServerLang.tr(player, "message.itemmilestonelog.recorded",
                    itemName, Playtime.format(ticks));
            player.sendSystemMessage(msg);
        }
        if (Config.BROADCAST.get()) {
            String formattedTicks = Playtime.format(ticks);
            for (ServerPlayer other : player.server.getPlayerList().getPlayers()) {
                if (other == player) {
                    continue;
                }
                MilestoneData.PlayerData otherPd = data.playerOrNull(other.getUUID());
                if (otherPd != null && otherPd.muteBroadcasts) {
                    continue;
                }
                other.sendSystemMessage(ServerLang.tr(other, "message.itemmilestonelog.recorded_broadcast",
                        player.getDisplayName(), itemName, formattedTicks));
            }
        }
        if (worldFirst && Config.ANNOUNCE_WORLD_FIRST.get()) {
            String formattedRecord = Playtime.formatRecord(rec);
            for (ServerPlayer recipient : player.server.getPlayerList().getPlayers()) {
                MilestoneData.PlayerData recPd = data.playerOrNull(recipient.getUUID());
                if (recPd != null && recPd.muteBroadcasts) {
                    continue;
                }
                recipient.sendSystemMessage(ServerLang.tr(recipient, "message.itemmilestonelog.world_first_recorded",
                        player.getDisplayName(), itemName, formattedRecord));
            }
        }
        if (Config.MILESTONE_SOUND_ENABLED.get()) {
            playMilestoneSound(player);
        }

        NeoForge.EVENT_BUS.post(new MilestoneRecordedEvent(player, itemId, ticks, day, epochMillis));
        DiscordNotifier.notifyMilestone(playerName != null ? playerName : "", itemId, rec, worldFirst);
        MilestoneService.syncPlayerIfOpen(player);
    }

    private static void playMilestoneSound(ServerPlayer player) {
        String soundId = Config.MILESTONE_SOUND.get();
        ResourceLocation rl = soundId != null ? ResourceLocation.tryParse(soundId) : null;
        if (rl == null || !BuiltInRegistries.SOUND_EVENT.containsKey(rl)) {
            if (!Objects.equals(lastWarnedSoundId, soundId)) {
                lastWarnedSoundId = soundId;
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Unknown milestoneSound '{}'; skipping sound", soundId);
            }
            return;
        }
        SoundEvent event = BuiltInRegistries.SOUND_EVENT.get(rl);
        if (event != null) {
            player.playNotifySound(event, SoundSource.PLAYERS, 1.0F, 1.0F);
        }
    }
}
