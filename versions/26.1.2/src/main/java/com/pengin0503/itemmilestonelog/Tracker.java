package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.MilestoneRecordedEvent;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.network.ModNetworking;
import com.pengin0503.itemmilestonelog.network.SyncMilestonesPayload;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
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
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.IntSupplier;

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

    record QueuedRecord(TargetKey key, MilestoneRecord record, boolean worldFirst) {
    }

    static final class PendingNotifications {
        final UUID playerId;
        String playerName;
        Component actorDisplayName;
        final List<QueuedRecord> records = new ArrayList<>();

        PendingNotifications(UUID playerId, String playerName, Component actorDisplayName) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.actorDisplayName = actorDisplayName;
        }
    }

    /** Queued human-facing notifications to flush on ServerTickEvent.Post (insertion order). Server thread only. */
    private static final Map<UUID, PendingNotifications> PENDING_NOTIFICATIONS = new LinkedHashMap<>();

    private Tracker() {
    }

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
        MinecraftServer server = player.level().getServer();
        MilestoneData data = MilestoneData.get(server);
        MilestoneData.PlayerData pd = data.player(player.getUUID());
        String name = player.getGameProfile().name();
        if (name != null && !name.equals(pd.lastKnownName)) {
            pd.lastKnownName = name;
            data.setDirty();
        }
        if (cleanupProgressNotified(pd, effectiveTargets(pd))) {
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
        if (!shouldScanOnTick(player.getUUID(), player.tickCount, interval)) {
            return;
        }
        if (recordingPaused(player)) {
            return;
        }
        scanInventory(player);
    }

    /**
     * Runs one inventory scan pass for {@code player}, recording any newly achieved targets
     * and updating progress-alert and creative-carry state. Extracted from {@link #onPlayerTick}
     * so GameTests in {@code com.pengin0503.itemmilestonelog.gametest} can invoke the scan path
     * deterministically on mock players.
     */
    public static void scanInventory(ServerPlayer player) {
        MilestoneData data = MilestoneData.get(player.level().getServer());
        MilestoneData.PlayerData pd = data.playerOrNull(player.getUUID());
        Set<TargetKey> pending = new HashSet<>(effectiveTargets(pd));
        if (pd != null) {
            pending.removeAll(pd.records.keySet());
        }
        Set<String> carried = carriedFromCreative(pd);
        if (pending.isEmpty() && carried.isEmpty()) {
            return;
        }

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

        if (!pending.isEmpty()) {
            List<ProgressAlertNotification> alerts = evaluateProgressAlerts(
                    data,
                    player.getUUID(),
                    pending,
                    carried,
                    counts,
                    Config.PROGRESS_ALERTS.get(),
                    Config.progressAlertThresholds(),
                    Config.PROGRESS_ALERT_MIN_COUNT.get());
            if (!alerts.isEmpty()) {
                sendProgressAlerts(player, alerts);
            }
        }

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
        MilestoneData data = MilestoneData.get(player.level().getServer());
        MilestoneData.PlayerData pd = data.player(player.getUUID());
        Set<String> newlyMarked = new LinkedHashSet<>();
        for (String id : carried) {
            if (pd.carriedFromCreative.add(id)) {
                newlyMarked.add(id);
            }
        }
        if (!newlyMarked.isEmpty()) {
            data.setDirty();
            if (Config.NOTIFY_PLAYER.get()) {
                int affected = countCreativeCarriedTargetItems(newlyMarked, effectiveTargets(pd), pd.records.keySet());
                if (affected > 0) {
                    player.sendSystemMessage(ServerLang.tr(
                            player, "message.itemmilestonelog.creative_carried_notice", affected));
                }
            }
        }
    }

    /**
     * Counts distinct item IDs among {@code markedItemIds} that match at least one unrecorded
     * target in {@code effectiveTargets} (deduplicating multiple quantity targets for the same item).
     */
    static int countCreativeCarriedTargetItems(
            Set<String> markedItemIds,
            Set<TargetKey> effectiveTargets,
            Set<TargetKey> recordedKeys) {
        if (markedItemIds == null || markedItemIds.isEmpty()
                || effectiveTargets == null || effectiveTargets.isEmpty()) {
            return 0;
        }
        Set<String> affectedItemIds = new HashSet<>();
        for (TargetKey key : effectiveTargets) {
            if (key != null
                    && markedItemIds.contains(key.itemId())
                    && (recordedKeys == null || !recordedKeys.contains(key))) {
                affectedItemIds.add(key.itemId());
            }
        }
        return affectedItemIds.size();
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
                queueCraftCheck(player, itemId(stack), event.getAmountRemoved() > 0 ? event.getAmountRemoved() : stack.getCount());
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

    private static int countItemInPossession(ServerPlayer player, String targetItemId) {
        return countItemsInPossession(player, Set.of(targetItemId)).getOrDefault(targetItemId, 0);
    }

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

    record ItemAcquisitionOutcome(
            boolean clearedCreativeCarry,
            List<TargetKey> achieved,
            List<TargetKey> pendingForItem,
            int totalInPossession) {
        static final ItemAcquisitionOutcome EMPTY = new ItemAcquisitionOutcome(false, List.of(), List.of(), 0);
    }

    static List<TargetKey> collectUnrecordedTargetsForItem(
            String itemId,
            Set<TargetKey> defaultTargets,
            @Nullable MilestoneData.PlayerData pd) {
        List<TargetKey> pending = null;
        for (TargetKey key : defaultTargets) {
            if (key.itemId().equals(itemId) && (pd == null || !pd.records.containsKey(key))) {
                if (pending == null) {
                    pending = new ArrayList<>();
                }
                pending.add(key);
            }
        }
        if (pd != null && !pd.targets.isEmpty()) {
            for (TargetKey key : pd.targets) {
                if (key.itemId().equals(itemId)
                        && !pd.records.containsKey(key)
                        && !defaultTargets.contains(key)) {
                    if (pending == null) {
                        pending = new ArrayList<>();
                    }
                    pending.add(key);
                }
            }
        }
        return pending == null ? List.of() : pending;
    }

    static ItemAcquisitionOutcome evaluateItemAcquisition(
            String itemId,
            int totalInPossession,
            int minCountFromEvent,
            boolean alreadyAddedToInventory,
            Set<TargetKey> defaultTargets,
            @Nullable MilestoneData.PlayerData pd,
            Set<String> carried) {
        return evaluateItemAcquisition(
                itemId, () -> totalInPossession, minCountFromEvent, alreadyAddedToInventory,
                defaultTargets, pd, carried);
    }

    static ItemAcquisitionOutcome evaluateItemAcquisition(
            String itemId,
            IntSupplier possessionCounter,
            int minCountFromEvent,
            boolean alreadyAddedToInventory,
            Set<TargetKey> defaultTargets,
            @Nullable MilestoneData.PlayerData pd,
            Set<String> carried) {
        List<TargetKey> pendingForItem = collectUnrecordedTargetsForItem(itemId, defaultTargets, pd);
        boolean isCarried = carried.contains(itemId);
        if (!isCarried && pendingForItem.isEmpty()) {
            return ItemAcquisitionOutcome.EMPTY;
        }
        int totalInPossession = possessionCounter.getAsInt();
        boolean clearedCreativeCarry = false;
        if (isCarried) {
            int priorCount = alreadyAddedToInventory ? totalInPossession - minCountFromEvent : totalInPossession;
            if (priorCount <= 0) {
                clearedCreativeCarry = true;
            } else {
                return new ItemAcquisitionOutcome(false, List.of(), pendingForItem, totalInPossession);
            }
        }
        if (pendingForItem.isEmpty()) {
            return new ItemAcquisitionOutcome(clearedCreativeCarry, List.of(), List.of(), totalInPossession);
        }
        // When the event amount is already reflected in possession (pickup, or the deferred craft/smelt
        // check one tick later when the crafted stack sits in the inventory or cursor), possession is the
        // ground truth: a Q-dropped result stack is on the ground by then and is correctly not counted.
        int total = alreadyAddedToInventory ? totalInPossession : Math.max(totalInPossession, minCountFromEvent);
        List<TargetKey> achieved = new ArrayList<>();
        for (TargetKey key : pendingForItem) {
            if (total >= key.count()) {
                achieved.add(key);
            }
        }
        achieved.sort(TargetKey::compareTo);
        return new ItemAcquisitionOutcome(clearedCreativeCarry, achieved, pendingForItem, totalInPossession);
    }

    private static void tryRecordForItem(ServerPlayer player, String itemId, int minCountFromEvent,
                                         boolean alreadyAddedToInventory) {
        if (recordingPaused(player)) {
            return;
        }
        MilestoneData data = MilestoneData.get(player.level().getServer());
        MilestoneData.PlayerData pd = data.playerOrNull(player.getUUID());
        Set<String> carried = carriedFromCreative(pd);
        ItemAcquisitionOutcome outcome = evaluateItemAcquisition(
                itemId,
                () -> countItemInPossession(player, itemId),
                minCountFromEvent,
                alreadyAddedToInventory,
                defaultTargets(),
                pd,
                carried);
        if (outcome == ItemAcquisitionOutcome.EMPTY) {
            return;
        }
        if (outcome.clearedCreativeCarry()) {
            if (pd != null) {
                pd.carriedFromCreative.remove(itemId);
            }
            data.setDirty();
        }
        if (!outcome.achieved().isEmpty()) {
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
        if (!outcome.pendingForItem().isEmpty() && Config.PROGRESS_ALERTS.get()) {
            int effectiveCount = alreadyAddedToInventory
                    ? outcome.totalInPossession()
                    : Math.max(outcome.totalInPossession(), minCountFromEvent);
            Set<String> activeCarried = carriedFromCreative(data.playerOrNull(player.getUUID()));
            List<ProgressAlertNotification> alerts = evaluateProgressAlerts(
                    data,
                    player.getUUID(),
                    outcome.pendingForItem(),
                    activeCarried,
                    Map.of(itemId, effectiveCount),
                    true,
                    Config.progressAlertThresholds(),
                    Config.PROGRESS_ALERT_MIN_COUNT.get());
            if (!alerts.isEmpty()) {
                sendProgressAlerts(player, alerts);
            }
        }
    }

    record ProgressAlertNotification(TargetKey key, int currentCount, int targetCount, int percent) {
    }

    /** Removes stale {@code progressNotified} entries for keys already recorded or no longer in effective targets. */
    static boolean cleanupProgressNotified(@Nullable MilestoneData.PlayerData pd, Set<TargetKey> effectiveTargets) {
        if (pd == null || pd.progressNotified.isEmpty()) {
            return false;
        }
        return pd.progressNotified.keySet().removeIf(
                key -> pd.records.containsKey(key) || !effectiveTargets.contains(key));
    }

    static List<ProgressAlertNotification> evaluateProgressAlerts(
            MilestoneData data,
            UUID playerId,
            Collection<TargetKey> candidateTargets,
            Set<String> carried,
            Map<String, Integer> counts,
            boolean progressAlertsEnabled,
            List<Integer> thresholds,
            int minCount) {
        if (!progressAlertsEnabled || candidateTargets.isEmpty() || thresholds.isEmpty()) {
            return List.of();
        }
        MilestoneData.PlayerData existingPd = data.playerOrNull(playerId);
        int effectiveMinCount = Math.max(ProgressAlertLogic.MIN_ALERT_COUNT_LOWER, minCount);
        List<TargetKey> sorted = new ArrayList<>();
        for (TargetKey key : candidateTargets) {
            if (key.count() >= effectiveMinCount && (existingPd == null || !existingPd.records.containsKey(key))) {
                sorted.add(key);
            }
        }
        if (sorted.isEmpty()) {
            return List.of();
        }
        sorted.sort(TargetKey::compareTo);
        List<ProgressAlertNotification> notifications = new ArrayList<>();
        boolean dirty = false;
        MilestoneData.PlayerData pd = existingPd;
        boolean muted = existingPd != null && existingPd.muteProgressAlerts;
        for (TargetKey key : sorted) {
            int currentCount = carried.contains(key.itemId()) ? 0 : counts.getOrDefault(key.itemId(), 0);
            Integer alreadyNotified = pd != null ? pd.progressNotified.get(key) : null;
            ProgressAlertLogic.Evaluation eval = ProgressAlertLogic.evaluate(
                    currentCount, key.count(), thresholds, alreadyNotified, effectiveMinCount);
            if (eval.shouldStore()) {
                if (pd == null) {
                    pd = data.player(playerId);
                }
                pd.progressNotified.put(key, eval.newStoredThreshold());
                dirty = true;
            }
            if (!muted && eval.shouldNotify()) {
                notifications.add(new ProgressAlertNotification(key, currentCount, key.count(), eval.percent()));
            }
        }
        if (dirty) {
            data.setDirty();
        }
        return notifications;
    }

    private static void sendProgressAlerts(ServerPlayer player, List<ProgressAlertNotification> notifications) {
        for (ProgressAlertNotification n : notifications) {
            Component msg = ServerLang.tr(player, "message.itemmilestonelog.progress_alert",
                    MilestoneService.targetDisplayName(n.key()),
                    n.currentCount(),
                    n.targetCount(),
                    n.percent());
            player.sendSystemMessage(msg);
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
        pd.progressNotified.remove(key);
        boolean worldFirst = worldFirstEnabled && data.recordWorldFirstIfAbsent(key, playerId, playerName, rec);
        data.setDirty();
        return worldFirst ? RecordOutcome.RECORDED_WORLD_FIRST : RecordOutcome.RECORDED;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING_NOTIFICATIONS.isEmpty()) {
            return;
        }
        flushPendingNotifications(event.getServer(), drainPendingNotifications());
    }

    static void queuePendingNotification(
            UUID playerId,
            @Nullable String playerName,
            @Nullable Component actorDisplayName,
            TargetKey key,
            MilestoneRecord record,
            boolean worldFirst) {
        String safeName = playerName != null ? playerName : "";
        Component safeDisplayName = actorDisplayName != null ? actorDisplayName : Component.literal(safeName);
        PendingNotifications pending = PENDING_NOTIFICATIONS.computeIfAbsent(
                playerId, id -> new PendingNotifications(id, safeName, safeDisplayName));
        if (!safeName.isEmpty()) {
            pending.playerName = safeName;
        }
        if (actorDisplayName != null) {
            pending.actorDisplayName = actorDisplayName;
        }
        pending.records.add(new QueuedRecord(key, record, worldFirst));
    }

    static List<PendingNotifications> drainPendingNotifications() {
        if (PENDING_NOTIFICATIONS.isEmpty()) {
            return List.of();
        }
        List<PendingNotifications> drained = new ArrayList<>(PENDING_NOTIFICATIONS.values());
        PENDING_NOTIFICATIONS.clear();
        return drained;
    }

    /** Discards queued notifications without sending (called on ServerStoppingEvent). */
    public static void clearPendingNotifications() {
        PENDING_NOTIFICATIONS.clear();
    }

    private static boolean record(ServerPlayer player, MilestoneData data, TargetKey key, boolean syncImmediately) {
        MilestoneData.PlayerData pd = data.player(player.getUUID());
        if (pd.records.containsKey(key)) {
            return false;
        }
        MinecraftServer server = player.level().getServer();
        int ticks = Playtime.currentTicks(player);
        long day = Playtime.currentDay(server);
        long epochMillis = System.currentTimeMillis();
        MilestoneRecord rec = new MilestoneRecord(ticks, day, epochMillis);

        String playerName = player.getGameProfile().name();
        String safePlayerName = playerName != null ? playerName : "";
        RecordOutcome outcome = applyRecord(data, player.getUUID(), playerName, key, rec,
                Config.WORLD_FIRST_ENABLED.get());
        if (outcome == RecordOutcome.ALREADY_RECORDED) {
            return false;
        }
        boolean worldFirst = outcome == RecordOutcome.RECORDED_WORLD_FIRST;
        ZoneId zone = Config.displayTimeZone();
        String formattedRecord = Playtime.formatRecord(rec, zone);

        ItemMilestoneLog.LOGGER.info("[ItemMilestoneLog] {} first obtained {} at playtime {} ({} ticks)",
                playerName, key.formatDisplayId(), formattedRecord, ticks);

        if (Config.GROUP_SIMULTANEOUS_RECORDS.get()) {
            queuePendingNotification(player.getUUID(), safePlayerName, player.getDisplayName(), key, rec, worldFirst);
            NeoForge.EVENT_BUS.post(new MilestoneRecordedEvent(player, key.itemId(), key.count(), ticks, day, epochMillis));
        } else {
            sendImmediateNotifications(server, data, player, key, ticks, worldFirst, formattedRecord);
            NeoForge.EVENT_BUS.post(new MilestoneRecordedEvent(player, key.itemId(), key.count(), ticks, day, epochMillis));
            DiscordNotifier.notifyMilestone(safePlayerName, key, rec, worldFirst);
        }

        if (syncImmediately) {
            MilestoneService.syncPlayerIfOpen(player);
        }
        return true;
    }

    private static void sendImmediateNotifications(
            MinecraftServer server,
            MilestoneData data,
            ServerPlayer player,
            TargetKey key,
            int ticks,
            boolean worldFirst,
            String formattedRecord) {
        Component displayName = MilestoneService.targetDisplayName(key);

        if (Config.NOTIFY_PLAYER.get()) {
            Component msg = ServerLang.tr(player, "message.itemmilestonelog.recorded",
                    displayName, Playtime.format(ticks));
            player.sendSystemMessage(msg);
        }
        if (Config.BROADCAST.get()) {
            String formattedTicks = Playtime.format(ticks);
            for (ServerPlayer other : server.getPlayerList().getPlayers()) {
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
            for (ServerPlayer recipient : server.getPlayerList().getPlayers()) {
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
    }

    private static void flushPendingNotifications(MinecraftServer server, List<PendingNotifications> batch) {
        if (batch.isEmpty()) {
            return;
        }
        MilestoneData data = MilestoneData.get(server);
        boolean notifyPlayer = Config.NOTIFY_PLAYER.get();
        boolean broadcastMilestones = Config.BROADCAST.get();
        boolean announceWorldFirst = Config.ANNOUNCE_WORLD_FIRST.get();
        boolean milestoneSoundEnabled = Config.MILESTONE_SOUND_ENABLED.get();
        String discordScope = Config.DISCORD_NOTIFY_SCOPE.get();
        ZoneId zone = Config.displayTimeZone();

        for (PendingNotifications pending : batch) {
            flushPlayerNotifications(
                    server, data, pending,
                    notifyPlayer, broadcastMilestones, announceWorldFirst,
                    milestoneSoundEnabled, discordScope, zone);
        }
    }

    private static void flushPlayerNotifications(
            MinecraftServer server,
            MilestoneData data,
            PendingNotifications pending,
            boolean notifyPlayer,
            boolean broadcastMilestones,
            boolean announceWorldFirst,
            boolean milestoneSoundEnabled,
            String discordScope,
            ZoneId zone) {
        NotificationGrouping.FlushPlan<QueuedRecord> plan = NotificationGrouping.planFlush(
                pending.records,
                QueuedRecord::worldFirst,
                Comparator.comparing(QueuedRecord::key),
                notifyPlayer,
                broadcastMilestones,
                announceWorldFirst,
                milestoneSoundEnabled,
                discordScope);

        ServerPlayer onlineActor = server.getPlayerList().getPlayer(pending.playerId);
        Component actorDisplayName = onlineActor != null
                ? onlineActor.getDisplayName()
                : (pending.actorDisplayName != null ? pending.actorDisplayName : Component.literal(pending.playerName));

        // 1. Player chat notification (skip if offline)
        if (onlineActor != null) {
            sendPlayerChannel(onlineActor, plan.playerChat());
        }

        // 2. Broadcast to other online players
        sendBroadcastChannel(server, data, pending.playerId, actorDisplayName, plan.broadcastChat());

        // 3. World-first announcement to online players
        sendWorldFirstChannel(server, data, pending.playerId, actorDisplayName, plan.worldFirstChat(), zone);

        // Sound: at most once per player per tick (skip if offline)
        if (onlineActor != null && plan.playSound()) {
            playMilestoneSound(onlineActor);
        }

        // 4. F5 preset completion chat notifications will be flushed here when F5 is implemented.

        // 5. Discord record notifications (still sent even if player went offline)
        sendDiscordChannel(pending.playerName, plan.discord());

        // 6. F19 Discord preset completion notifications will be sent here when F19 is implemented.
    }

    private static void sendPlayerChannel(
            ServerPlayer player,
            NotificationGrouping.ChannelPlan<QueuedRecord> channel) {
        if (channel.mode() == NotificationGrouping.SendMode.NONE) {
            return;
        }
        if (channel.mode() == NotificationGrouping.SendMode.INDIVIDUAL) {
            for (QueuedRecord q : channel.items()) {
                Component displayName = MilestoneService.targetDisplayName(q.key());
                player.sendSystemMessage(ServerLang.tr(player, "message.itemmilestonelog.recorded",
                        displayName, Playtime.format(q.record().playTicks())));
            }
            return;
        }
        List<QueuedRecord> items = channel.items();
        String formattedPlaytime = Playtime.format(items.get(0).record().playTicks());
        Component targetList = buildTargetListComponent(player, items);
        player.sendSystemMessage(ServerLang.tr(player, "message.itemmilestonelog.recorded_group",
                items.size(), formattedPlaytime, targetList));
    }

    private static void sendBroadcastChannel(
            MinecraftServer server,
            MilestoneData data,
            UUID actorId,
            Component actorDisplayName,
            NotificationGrouping.ChannelPlan<QueuedRecord> channel) {
        if (channel.mode() == NotificationGrouping.SendMode.NONE) {
            return;
        }
        if (channel.mode() == NotificationGrouping.SendMode.INDIVIDUAL) {
            for (QueuedRecord q : channel.items()) {
                Component displayName = MilestoneService.targetDisplayName(q.key());
                String formattedTicks = Playtime.format(q.record().playTicks());
                for (ServerPlayer other : server.getPlayerList().getPlayers()) {
                    if (other.getUUID().equals(actorId)) {
                        continue;
                    }
                    MilestoneData.PlayerData otherPd = data.playerOrNull(other.getUUID());
                    if (otherPd != null && otherPd.muteBroadcasts) {
                        continue;
                    }
                    other.sendSystemMessage(ServerLang.tr(other, "message.itemmilestonelog.recorded_broadcast",
                            actorDisplayName, displayName, formattedTicks));
                }
            }
            return;
        }
        List<QueuedRecord> items = channel.items();
        String formattedPlaytime = Playtime.format(items.get(0).record().playTicks());
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other.getUUID().equals(actorId)) {
                continue;
            }
            MilestoneData.PlayerData otherPd = data.playerOrNull(other.getUUID());
            if (otherPd != null && otherPd.muteBroadcasts) {
                continue;
            }
            Component targetList = buildTargetListComponent(other, items);
            other.sendSystemMessage(ServerLang.tr(other, "message.itemmilestonelog.recorded_group_broadcast",
                    actorDisplayName, items.size(), formattedPlaytime, targetList));
        }
    }

    private static void sendWorldFirstChannel(
            MinecraftServer server,
            MilestoneData data,
            UUID actorId,
            Component actorDisplayName,
            NotificationGrouping.ChannelPlan<QueuedRecord> channel,
            ZoneId zone) {
        if (channel.mode() == NotificationGrouping.SendMode.NONE) {
            return;
        }
        if (channel.mode() == NotificationGrouping.SendMode.INDIVIDUAL) {
            for (QueuedRecord q : channel.items()) {
                Component displayName = MilestoneService.targetDisplayName(q.key());
                String formattedRecord = Playtime.formatRecord(q.record(), zone);
                for (ServerPlayer recipient : server.getPlayerList().getPlayers()) {
                    MilestoneData.PlayerData recPd = data.playerOrNull(recipient.getUUID());
                    if (!recipient.getUUID().equals(actorId) && recPd != null && recPd.muteBroadcasts) {
                        continue;
                    }
                    recipient.sendSystemMessage(ServerLang.tr(recipient, "message.itemmilestonelog.world_first_recorded",
                            actorDisplayName, displayName, formattedRecord));
                }
            }
            return;
        }
        List<QueuedRecord> items = channel.items();
        String formattedRecord = Playtime.formatRecord(items.get(0).record(), zone);
        for (ServerPlayer recipient : server.getPlayerList().getPlayers()) {
            MilestoneData.PlayerData recPd = data.playerOrNull(recipient.getUUID());
            if (!recipient.getUUID().equals(actorId) && recPd != null && recPd.muteBroadcasts) {
                continue;
            }
            Component targetList = buildTargetListComponent(recipient, items);
            recipient.sendSystemMessage(ServerLang.tr(recipient, "message.itemmilestonelog.world_first_group",
                    actorDisplayName, items.size(), formattedRecord, targetList));
        }
    }

    private static void sendDiscordChannel(
            String playerName,
            NotificationGrouping.ChannelPlan<QueuedRecord> channel) {
        if (channel.mode() == NotificationGrouping.SendMode.NONE) {
            return;
        }
        if (channel.mode() == NotificationGrouping.SendMode.INDIVIDUAL) {
            for (QueuedRecord q : channel.items()) {
                DiscordNotifier.notifyMilestone(playerName, q.key(), q.record(), q.worldFirst());
            }
            return;
        }
        List<DiscordNotifier.MilestoneEntry> entries = new ArrayList<>(channel.items().size());
        for (QueuedRecord q : channel.items()) {
            entries.add(new DiscordNotifier.MilestoneEntry(q.key(), q.record(), q.worldFirst()));
        }
        DiscordNotifier.notifyMilestonesGrouped(playerName, entries);
    }

    private static MutableComponent buildTargetListComponent(
            @Nullable ServerPlayer recipient, List<QueuedRecord> sortedRecords) {
        String lang = recipient != null && recipient.clientInformation() != null
                ? recipient.clientInformation().language()
                : null;
        List<TargetKey> keys = new ArrayList<>(sortedRecords.size());
        for (QueuedRecord q : sortedRecords) {
            keys.add(q.key());
        }
        return buildTargetListComponent(lang, keys, MilestoneService::targetDisplayName);
    }

    static MutableComponent buildTargetListComponent(
            @Nullable String language,
            List<TargetKey> sortedKeys,
            Function<TargetKey, Component> displayName) {
        NotificationGrouping.ListSummary<TargetKey> summary = NotificationGrouping.summarizeChatList(sortedKeys);
        MutableComponent list = Component.empty();
        List<TargetKey> shown = summary.shown();
        for (int i = 0; i < shown.size(); i++) {
            if (i > 0) {
                list.append(ServerLang.trForLang(language, "message.itemmilestonelog.list_separator"));
            }
            list.append(displayName.apply(shown.get(i)));
        }
        if (summary.hasMore()) {
            list.append(ServerLang.trForLang(language, "message.itemmilestonelog.list_more", summary.remainingCount()));
        }
        return list;
    }

    private static void playMilestoneSound(ServerPlayer player) {
        String soundId = Config.MILESTONE_SOUND.get();
        Identifier id = soundId != null ? Identifier.tryParse(soundId) : null;
        if (id == null || !BuiltInRegistries.SOUND_EVENT.containsKey(id)) {
            if (!Objects.equals(lastWarnedSoundId, soundId)) {
                lastWarnedSoundId = soundId;
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Unknown milestoneSound '{}'; skipping sound", soundId);
            }
            return;
        }
        SoundEvent event = BuiltInRegistries.SOUND_EVENT.getValue(id);
        if (event != null && player.connection != null) {
            player.connection.send(new ClientboundSoundPacket(
                    BuiltInRegistries.SOUND_EVENT.wrapAsHolder(event),
                    SoundSource.PLAYERS,
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    1.0F,
                    1.0F,
                    ThreadLocalRandom.current().nextLong()
            ));
        }
    }
}
