package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.MilestoneRecordedEvent;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.network.ModNetworking;
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
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watches tracked items entering a player's possession and records the
 * cumulative playtime, in-game day, and wall-clock time at that moment.
 */
public final class Tracker {
    private static volatile String lastWarnedSoundId = null;
    private static final Set<String> WARNED_DEFAULT_TARGETS = ConcurrentHashMap.newKeySet();
    /** Immutable snapshot of the config list a parse was derived from and its result; published atomically. */
    private record DefaultTargetsCache(List<? extends String> source, Set<TargetKey> targets) {
    }

    private static volatile DefaultTargetsCache defaultTargetsCache = new DefaultTargetsCache(null, Set.of());
    /** Craft/smelt results awaiting verification next tick: player -> itemId -> max event count. Server thread only. */
    private static final Map<UUID, Map<String, Integer>> PENDING_CRAFT_CHECKS = new HashMap<>();

    private Tracker() {
    }

    /** Parses server-configured defaultTargets into valid {@link TargetKey}s, warning once per invalid entry. */
    public static Set<TargetKey> defaultTargets() {
        List<? extends String> current = Config.DEFAULT_TARGETS.get();
        DefaultTargetsCache cache = defaultTargetsCache;
        if (current == cache.source()) {
            return cache.targets();
        }
        Set<TargetKey> immutable = parseDefaultTargets(current);
        defaultTargetsCache = new DefaultTargetsCache(current, immutable);
        return immutable;
    }

    static Set<TargetKey> parseDefaultTargets(List<? extends String> rawEntries) {
        Set<TargetKey> result = new LinkedHashSet<>();
        for (String raw : rawEntries) {
            TargetKey normalized = TargetKey.tryParseNormalized(raw, true, true);
            if (normalized != null) {
                result.add(normalized);
            } else if (raw != null && WARNED_DEFAULT_TARGETS.add(raw)) {
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Ignoring invalid defaultTargets entry '{}'", raw);
            }
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(result));
    }

    /** Effective targets for a player: global defaults from config + personal targets. */
    public static Set<TargetKey> effectiveTargets(MilestoneData.PlayerData pd) {
        return effectiveTargets(defaultTargets(), pd);
    }

    static Set<TargetKey> effectiveTargets(Set<TargetKey> defaults, @Nullable MilestoneData.PlayerData pd) {
        Set<TargetKey> all = new LinkedHashSet<>(defaults);
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
        if (player instanceof FakePlayer) {
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
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof FakePlayer) {
            return;
        }
        ModNetworking.removeRateLimit(event.getEntity().getUUID());
        PENDING_CRAFT_CHECKS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (player instanceof FakePlayer) {
            return;
        }
        Map<String, Integer> pendingCrafts = takePendingCraftChecks(player.getUUID());
        if (pendingCrafts != null) {
            for (Map.Entry<String, Integer> entry : pendingCrafts.entrySet()) {
                tryRecordForItem(player, entry.getKey(), entry.getValue(), true);
            }
        }
        int interval = Config.SCAN_INTERVAL_TICKS.get();
        // Spread player scans across the entire interval using floorMod.
        if (!shouldScanOnTick(player.getUUID(), player.tickCount, interval)) {
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
        Set<String> neededItemIds = pendingItemIdsToCount(pending, carried);

        Map<String, Integer> counts = new HashMap<>();
        Set<String> stillCarried = new HashSet<>();
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            accumulateStack(inventory.getItem(i), carried, stillCarried, neededItemIds, counts);
        }
        if (player.containerMenu != null) {
            accumulateStack(player.containerMenu.getCarried(), carried, stillCarried, neededItemIds, counts);
        }
        for (ItemStack craftStack : player.inventoryMenu.getCraftSlots().getItems()) {
            accumulateStack(craftStack, carried, stillCarried, neededItemIds, counts);
        }

        List<TargetKey> achieved = findAchievedTargets(pending, carried, counts);
        if (!achieved.isEmpty()) {
            boolean anyRecorded = false;
            for (TargetKey key : achieved) {
                if (record(player, data, key, false)) {
                    anyRecorded = true;
                }
            }
            if (anyRecorded) {
                MilestoneService.syncPlayerIfOpen(player);
            }
        }

        // Forget the items carried out of creative mode that are gone now.
        if (!carried.isEmpty() && carried.retainAll(stillCarried)) {
            data.setDirty();
        }
    }

    static boolean shouldScanOnTick(UUID playerId, int tickCount, int interval) {
        int phase = Math.floorMod(playerId.hashCode(), interval);
        return (tickCount + phase) % interval == 0;
    }

    static Set<String> pendingItemIdsToCount(Set<TargetKey> pending, Set<String> carried) {
        Set<String> neededItemIds = new HashSet<>();
        for (TargetKey key : pending) {
            if (!carried.contains(key.itemId())) {
                neededItemIds.add(key.itemId());
            }
        }
        return neededItemIds;
    }

    static List<TargetKey> findAchievedTargets(Set<TargetKey> pending, Set<String> carried, Map<String, Integer> counts) {
        if (counts.isEmpty()) {
            return List.of();
        }
        List<TargetKey> achieved = new ArrayList<>();
        for (TargetKey key : pending) {
            if (!carried.contains(key.itemId()) && counts.getOrDefault(key.itemId(), 0) >= key.count()) {
                achieved.add(key);
            }
        }
        achieved.sort(TargetKey::compareTo);
        return achieved;
    }

    private static void accumulateStack(ItemStack stack, Set<String> carried, Set<String> stillCarried,
                                        Set<String> neededItemIds, Map<String, Integer> counts) {
        if (stack.isEmpty()) {
            return;
        }
        accumulateItem(itemId(stack), stack.getCount(), carried, stillCarried, neededItemIds, counts);
    }

    static void accumulateItem(String id, int stackCount, Set<String> carried, Set<String> stillCarried,
                               Set<String> neededItemIds, Map<String, Integer> counts) {
        if (carried.contains(id)) {
            stillCarried.add(id);
        } else if (neededItemIds.contains(id)) {
            counts.merge(id, stackCount, Integer::sum);
        }
    }

    /**
     * Leaving creative or spectator mode: remember what the player still carries, so
     * those items are not recorded as if they had been obtained in survival.
     * LOWEST priority, so a listener that cancels the change runs first.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onGameModeChange(PlayerEvent.PlayerChangeGameModeEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer
                || !Config.IGNORE_CREATIVE_AND_SPECTATOR.get()) {
            return;
        }
        if (!isLeavingCreativeOrSpectator(event.getCurrentGameMode(), event.getNewGameMode())) {
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
        for (ItemStack craftStack : player.inventoryMenu.getCraftSlots().getItems()) {
            if (!craftStack.isEmpty()) {
                carried.add(itemId(craftStack));
            }
        }
        if (carried.isEmpty()) {
            return;
        }
        MilestoneData data = MilestoneData.get(player.server);
        if (data.player(player.getUUID()).carriedFromCreative.addAll(carried)) {
            data.setDirty();
        }
    }

    @SubscribeEvent
    public static void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !(player instanceof FakePlayer)) {
            ItemStack stack = event.getCrafting();
            if (!stack.isEmpty()) {
                queueCraftCheck(player, itemId(stack), stack.getCount());
            }
        }
    }

    @SubscribeEvent
    public static void onItemSmelted(PlayerEvent.ItemSmeltedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !(player instanceof FakePlayer)) {
            ItemStack stack = event.getSmelting();
            if (!stack.isEmpty()) {
                queueCraftCheck(player, itemId(stack), stack.getCount());
            }
        }
    }

    private static void queueCraftCheck(ServerPlayer player, String itemId, int eventCount) {
        queueCraftCheck(player.getUUID(), itemId, eventCount);
    }

    static void queueCraftCheck(UUID playerId, String itemId, int eventCount) {
        PENDING_CRAFT_CHECKS.computeIfAbsent(playerId, k -> new HashMap<>())
                .merge(itemId, eventCount, Math::max);
    }

    @Nullable
    static Map<String, Integer> takePendingCraftChecks(UUID playerId) {
        return PENDING_CRAFT_CHECKS.remove(playerId);
    }

    @SubscribeEvent
    public static void onItemPickup(ItemEntityPickupEvent.Post event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        if (player instanceof FakePlayer) {
            return;
        }
        ItemStack before = event.getOriginalStack();
        int pickedUp = before.getCount() - event.getItemEntity().getItem().getCount();
        if (before.isEmpty() || pickedUp <= 0) {
            return;
        }
        tryRecordForItem(player, itemId(before), pickedUp, true);
    }

    /** True while nothing is recorded for the player: creative or spectator mode, if so configured. */
    static boolean recordingPaused(ServerPlayer player) {
        return isRecordingPaused(Config.IGNORE_CREATIVE_AND_SPECTATOR.get(), player.isCreative(), player.isSpectator());
    }

    static boolean isRecordingPaused(boolean ignoreCreativeAndSpectator, boolean isCreative, boolean isSpectator) {
        return ignoreCreativeAndSpectator && (isCreative || isSpectator);
    }

    static boolean isLeavingCreativeOrSpectator(GameType currentMode, GameType newMode) {
        return isCreativeOrSpectator(currentMode) && !isCreativeOrSpectator(newMode);
    }

    private static boolean isCreativeOrSpectator(GameType mode) {
        return mode == GameType.CREATIVE || mode == GameType.SPECTATOR;
    }

    /** The player's items carried out of creative mode, or an empty set if they don't count. */
    public static Set<String> carriedFromCreative(MilestoneData.PlayerData pd) {
        return carriedFromCreative(pd, Config.IGNORE_CREATIVE_AND_SPECTATOR.get());
    }

    static Set<String> carriedFromCreative(@Nullable MilestoneData.PlayerData pd, boolean ignoreCreativeAndSpectator) {
        if (pd == null || !ignoreCreativeAndSpectator) {
            return Set.of();
        }
        return pd.carriedFromCreative;
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** Counts total simultaneous possession of {@code targetItemId} in inventory, offhand, cursor, and 2x2 craft grid. */
    private static int countItemInPossession(ServerPlayer player, String targetItemId) {
        return countItemsInPossession(player, Set.of(targetItemId)).getOrDefault(targetItemId, 0);
    }

    /**
     * Counts total simultaneous possession (main inventory + armor + offhand + cursor stack + 2x2 craft grid)
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
        for (ItemStack craftStack : player.inventoryMenu.getCraftSlots().getItems()) {
            if (!craftStack.isEmpty()) {
                String id = itemId(craftStack);
                if (itemIds.contains(id)) {
                    counts.merge(id, craftStack.getCount(), Integer::sum);
                }
            }
        }
        return counts;
    }

    record ItemAcquisitionOutcome(boolean clearedCreativeCarry, List<TargetKey> achieved) {
    }

    static ItemAcquisitionOutcome evaluateItemAcquisition(
            String itemId,
            int totalInPossession,
            int minCountFromEvent,
            boolean alreadyAddedToInventory,
            Set<TargetKey> effectiveTargets,
            @Nullable MilestoneData.PlayerData pd,
            Set<String> carried) {
        boolean clearedCreativeCarry = false;
        if (carried.contains(itemId)) {
            int priorCount = alreadyAddedToInventory ? totalInPossession - minCountFromEvent : totalInPossession;
            if (priorCount <= 0) {
                clearedCreativeCarry = true;
            } else {
                return new ItemAcquisitionOutcome(false, List.of());
            }
        }
        List<TargetKey> pendingForItem = new ArrayList<>();
        for (TargetKey key : effectiveTargets) {
            if (key.itemId().equals(itemId) && (pd == null || !pd.records.containsKey(key))) {
                pendingForItem.add(key);
            }
        }
        if (pendingForItem.isEmpty()) {
            return new ItemAcquisitionOutcome(clearedCreativeCarry, List.of());
        }
        // When the event amount is already reflected in possession (pickup, or the deferred craft/smelt
        // check one tick later when the crafted stack sits in the inventory or cursor), possession is the
        // ground truth: a Q-dropped result stack is on the ground by then and is correctly not counted.
        int total = alreadyAddedToInventory ? totalInPossession : Math.max(totalInPossession, minCountFromEvent);
        pendingForItem.sort(TargetKey::compareTo);
        List<TargetKey> achieved = new ArrayList<>();
        for (TargetKey key : pendingForItem) {
            if (total >= key.count()) {
                achieved.add(key);
            }
        }
        return new ItemAcquisitionOutcome(clearedCreativeCarry, achieved);
    }

    private static void tryRecordForItem(ServerPlayer player, String itemId, int minCountFromEvent,
                                         boolean alreadyAddedToInventory) {
        if (recordingPaused(player)) {
            return;
        }
        MilestoneData data = MilestoneData.get(player.server);
        MilestoneData.PlayerData pd = data.playerOrNull(player.getUUID());
        int totalInPossession = countItemInPossession(player, itemId);
        Set<String> carried = carriedFromCreative(pd);
        ItemAcquisitionOutcome outcome = evaluateItemAcquisition(
                itemId, totalInPossession, minCountFromEvent, alreadyAddedToInventory,
                effectiveTargets(pd), pd, carried);
        if (outcome.clearedCreativeCarry()) {
            if (pd != null) {
                pd.carriedFromCreative.remove(itemId);
            }
            data.setDirty();
        }
        if (outcome.achieved().isEmpty()) {
            return;
        }
        boolean anyRecorded = false;
        for (TargetKey key : outcome.achieved()) {
            if (record(player, data, key, false)) {
                anyRecorded = true;
            }
        }
        if (anyRecorded) {
            MilestoneService.syncPlayerIfOpen(player);
        }
    }

    enum RecordOutcome {
        ALREADY_RECORDED,
        RECORDED,
        RECORDED_WORLD_FIRST
    }

    static RecordOutcome applyRecord(MilestoneData data, UUID playerId, @Nullable String playerName,
                                     TargetKey key, MilestoneRecord rec) {
        return applyRecord(data, playerId, playerName, key, rec, true);
    }

    static RecordOutcome applyRecord(MilestoneData data, UUID playerId, @Nullable String playerName,
                                     TargetKey key, MilestoneRecord rec, boolean worldFirstEnabled) {
        MilestoneData.PlayerData pd = data.player(playerId);
        if (pd.records.containsKey(key)) {
            return RecordOutcome.ALREADY_RECORDED;
        }
        if (playerName != null && !playerName.equals(pd.lastKnownName)) {
            pd.lastKnownName = playerName;
        }
        pd.records.put(key, rec);
        boolean worldFirst = worldFirstEnabled && data.recordWorldFirstIfAbsent(key, playerId, playerName, rec);
        data.setDirty();
        return worldFirst ? RecordOutcome.RECORDED_WORLD_FIRST : RecordOutcome.RECORDED;
    }

    private static boolean record(ServerPlayer player, MilestoneData data, TargetKey key, boolean syncImmediately) {
        MilestoneData.PlayerData pd = data.player(player.getUUID());
        if (pd.records.containsKey(key)) {
            return false;
        }
        int ticks = Playtime.currentTicks(player);
        long day = Playtime.currentDay(player.server);
        long epochMillis = System.currentTimeMillis();
        MilestoneRecord rec = new MilestoneRecord(ticks, day, epochMillis);

        String playerName = player.getGameProfile().getName();
        RecordOutcome outcome = applyRecord(data, player.getUUID(), playerName, key, rec,
                Config.WORLD_FIRST_ENABLED.get());
        if (outcome == RecordOutcome.ALREADY_RECORDED) {
            return false;
        }
        boolean worldFirst = outcome == RecordOutcome.RECORDED_WORLD_FIRST;
        String formattedRecord = Playtime.formatRecord(rec, Config.displayTimeZone());

        ItemMilestoneLog.LOGGER.info("[ItemMilestoneLog] {} first obtained {} at playtime {} ({} ticks)",
                playerName, key.formatDisplayId(), formattedRecord, ticks);

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
            for (ServerPlayer recipient : player.server.getPlayerList().getPlayers()) {
                MilestoneData.PlayerData recPd = data.playerOrNull(recipient.getUUID());
                if (recipient != player && recPd != null && recPd.muteBroadcasts) {
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
        if (syncImmediately) {
            MilestoneService.syncPlayerIfOpen(player);
        }
        return true;
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
