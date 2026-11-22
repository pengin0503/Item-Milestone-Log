package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.MilestoneRecordedEvent;
import com.pengin0503.itemmilestonelog.api.TargetKey;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watches tracked items entering a player's possession and records the
 * cumulative playtime, in-game day, and wall-clock time at that moment.
 */
public final class Tracker {
    private static volatile String lastWarnedSoundId = null;
    private static final Set<String> WARNED_DEFAULT_TARGETS = ConcurrentHashMap.newKeySet();

    private Tracker() {
    }

    /** Parses server-configured defaultTargets into valid {@link TargetKey}s, warning once per invalid entry. */
    public static Set<TargetKey> defaultTargets() {
        Set<TargetKey> result = new LinkedHashSet<>();
        for (String raw : Config.DEFAULT_TARGETS.get()) {
            TargetKey parsed = TargetKey.tryParseEncoded(raw);
            if (parsed != null && parsed.itemId().contains(":") && ResourceLocation.tryParse(parsed.itemId()) != null) {
                result.add(parsed);
            } else if (raw != null && WARNED_DEFAULT_TARGETS.add(raw)) {
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Ignoring invalid defaultTargets entry '{}'", raw);
            }
        }
        return result;
    }

    /** Effective targets for a player: global defaults from config + personal targets. */
    public static Set<TargetKey> effectiveTargets(MilestoneData.PlayerData pd) {
        Set<TargetKey> all = new LinkedHashSet<>(defaultTargets());
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
        Set<TargetKey> pending = new HashSet<>(effectiveTargets(pd));
        if (pd != null) {
            pending.removeAll(pd.records.keySet());
        }
        Set<String> carried = carriedFromCreative(pd);
        if (pending.isEmpty() && carried.isEmpty()) {
            return;
        }

        // Only sum counts for item IDs that actually appear in this player's pending targets.
        Set<String> neededItemIds = new HashSet<>();
        for (TargetKey key : pending) {
            if (!carried.contains(key.itemId())) {
                neededItemIds.add(key.itemId());
            }
        }

        Map<String, Integer> counts = new HashMap<>();
        Set<String> stillCarried = new HashSet<>();
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            accumulateStack(inventory.getItem(i), carried, stillCarried, neededItemIds, counts);
        }
        if (player.containerMenu != null) {
            accumulateStack(player.containerMenu.getCarried(), carried, stillCarried, neededItemIds, counts);
        }

        if (!counts.isEmpty()) {
            List<TargetKey> achieved = new ArrayList<>();
            for (TargetKey key : pending) {
                if (!carried.contains(key.itemId()) && counts.getOrDefault(key.itemId(), 0) >= key.count()) {
                    achieved.add(key);
                }
            }
            if (!achieved.isEmpty()) {
                achieved.sort(TargetKey::compareTo);
                for (TargetKey key : achieved) {
                    record(player, data, key);
                }
            }
        }

        // Forget the items carried out of creative mode that are gone now.
        if (!carried.isEmpty() && carried.retainAll(stillCarried)) {
            data.setDirty();
        }
    }

    private static void accumulateStack(ItemStack stack, Set<String> carried, Set<String> stillCarried,
                                        Set<String> neededItemIds, Map<String, Integer> counts) {
        if (stack.isEmpty()) {
            return;
        }
        String id = itemId(stack);
        if (carried.contains(id)) {
            stillCarried.add(id);
        } else if (neededItemIds.contains(id)) {
            counts.merge(id, stack.getCount(), Integer::sum);
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
        if (player.containerMenu != null && !player.containerMenu.getCarried().isEmpty()) {
            carried.add(itemId(player.containerMenu.getCarried()));
        }
        MilestoneData data = MilestoneData.get(player.server);
        MilestoneData.PlayerData existing = data.playerOrNull(player.getUUID());
        if (existing != null) {
            Set<TargetKey> effective = effectiveTargets(existing);
            Set<String> itemsWithPendingTargets = new HashSet<>();
            for (TargetKey key : effective) {
                if (!existing.records.containsKey(key)) {
                    itemsWithPendingTargets.add(key.itemId());
                }
            }
            Set<String> recordedItems = new HashSet<>();
            for (TargetKey key : existing.records.keySet()) {
                recordedItems.add(key.itemId());
            }
            carried.removeIf(id -> recordedItems.contains(id) && !itemsWithPendingTargets.contains(id));
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
            ItemStack stack = event.getCrafting();
            if (!stack.isEmpty()) {
                tryRecordForItem(player, itemId(stack), stack.getCount());
            }
        }
    }

    @SubscribeEvent
    public static void onItemSmelted(PlayerEvent.ItemSmeltedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ItemStack stack = event.getSmelting();
            if (!stack.isEmpty()) {
                tryRecordForItem(player, itemId(stack), stack.getCount());
            }
        }
    }

    @SubscribeEvent
    public static void onItemPickup(ItemEntityPickupEvent.Post event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack before = event.getOriginalStack();
        int pickedUp = before.getCount() - event.getItemEntity().getItem().getCount();
        if (before.isEmpty() || pickedUp <= 0) {
            return;
        }
        tryRecordForItem(player, itemId(before), pickedUp);
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

    /** Counts total simultaneous possession of {@code targetItemId} in inventory, offhand, and cursor. */
    private static int countItemInPossession(ServerPlayer player, String targetItemId) {
        return countItemsInPossession(player, Set.of(targetItemId)).getOrDefault(targetItemId, 0);
    }

    /**
     * Counts total simultaneous possession (main inventory + armor + offhand + cursor stack)
     * for only the requested {@code itemIds}.
     */
    public static Map<String, Integer> countItemsInPossession(ServerPlayer player, Set<String> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> counts = new HashMap<>();
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                String id = itemId(stack);
                if (itemIds.contains(id)) {
                    counts.merge(id, stack.getCount(), Integer::sum);
                }
            }
        }
        if (player.containerMenu != null) {
            ItemStack cursor = player.containerMenu.getCarried();
            if (!cursor.isEmpty()) {
                String id = itemId(cursor);
                if (itemIds.contains(id)) {
                    counts.merge(id, cursor.getCount(), Integer::sum);
                }
            }
        }
        return counts;
    }

    private static void tryRecordForItem(ServerPlayer player, String itemId, int minCountFromEvent) {
        if (recordingPaused(player)) {
            return;
        }
        MilestoneData data = MilestoneData.get(player.server);
        MilestoneData.PlayerData pd = data.playerOrNull(player.getUUID());
        if (carriedFromCreative(pd).contains(itemId)) {
            return;
        }
        List<TargetKey> pendingForItem = new ArrayList<>();
        for (TargetKey key : effectiveTargets(pd)) {
            if (key.itemId().equals(itemId) && (pd == null || !pd.records.containsKey(key))) {
                pendingForItem.add(key);
            }
        }
        if (pendingForItem.isEmpty()) {
            return;
        }
        int total = Math.max(countItemInPossession(player, itemId), minCountFromEvent);
        pendingForItem.sort(TargetKey::compareTo);
        for (TargetKey key : pendingForItem) {
            if (total >= key.count()) {
                record(player, data, key);
            }
        }
    }

    private static void record(ServerPlayer player, MilestoneData data, TargetKey key) {
        MilestoneData.PlayerData pd = data.player(player.getUUID());
        if (pd.records.containsKey(key)) {
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
        pd.records.put(key, rec);
        boolean worldFirst = data.recordWorldFirstIfAbsent(key, player.getUUID(), playerName, rec);
        data.setDirty();

        ItemMilestoneLog.LOGGER.info("[ItemMilestoneLog] {} first obtained {} at playtime {} ({} ticks)",
                playerName, key.formatDisplayId(), Playtime.formatRecord(rec), ticks);

        Component displayName = MilestoneService.targetDisplayName(key);

        if (Config.NOTIFY_PLAYER.get()) {
            Component msg = ServerLang.tr(player, "message.itemmilestonelog.recorded",
                    displayName, Playtime.format(ticks));
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
                        player.getDisplayName(), displayName, formattedTicks));
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
                        player.getDisplayName(), displayName, formattedRecord));
            }
        }
        if (Config.MILESTONE_SOUND_ENABLED.get()) {
            playMilestoneSound(player);
        }

        NeoForge.EVENT_BUS.post(new MilestoneRecordedEvent(player, key.itemId(), key.count(), ticks, day, epochMillis));
        DiscordNotifier.notifyMilestone(playerName != null ? playerName : "", key, rec, worldFirst);
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
