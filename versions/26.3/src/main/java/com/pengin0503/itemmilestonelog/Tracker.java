package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.ItemMilestoneLogApi;
import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.MilestoneRecordedEvent;
import com.pengin0503.itemmilestonelog.api.PresetCompletedEvent;
import com.pengin0503.itemmilestonelog.api.RecordCause;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.api.WorldFirstRecord;
import com.pengin0503.itemmilestonelog.network.ModNetworking;
import com.pengin0503.itemmilestonelog.network.SyncMilestonesPayload;
import net.minecraft.commands.CommandSourceStack;
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
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
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
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

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

    record QueuedPresetCompletion(Presets.PresetDefinition preset, long epochMillis) {
    }

    static final class PendingNotifications {
        final UUID playerId;
        String playerName;
        Component actorDisplayName;
        final List<QueuedRecord> records = new ArrayList<>();
        final List<QueuedRecord> discordRecords = new ArrayList<>();
        final List<QueuedPresetCompletion> presetCompletions = new ArrayList<>();
        final List<QueuedPresetCompletion> discordPresetCompletions = new ArrayList<>();

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

    /** F8b: defaultTargets tags already warned about since the last tag (re)load. */
    private static final Set<String> WARNED_DEFAULT_TAGS = ConcurrentHashMap.newKeySet();

    /**
     * F8b: rebuilds the {@link TagTargets} cache whenever tags are (re)loaded (server start,
     * {@code /reload}, and the client receiving tags on login), and on the server warns once per
     * missing or empty tag in {@code defaultTargets}.
     */
    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        TagIndex index = TagTargets.rebuild();
        // NeoForge 26.3 removed TagsUpdatedEvent#getUpdateCause()/UpdateCause (only getRegistries()
        // and shouldUpdateStaticData() remain). shouldUpdateStaticData() is the closest equivalent
        // of the old SERVER_DATA_LOAD cause: it is true when tags come from a static-data
        // (datapack) load such as server start or /reload.
        if (event.shouldUpdateStaticData()) {
            WARNED_DEFAULT_TAGS.clear();
            warnMissingDefaultTagTargets(index);
        }
    }

    /** F8b: makes sure the tag cache and the defaultTargets tag warning are in place at server start. */
    @SubscribeEvent
    public static void onServerStartedTags(ServerStartedEvent event) {
        warnMissingDefaultTagTargets(TagTargets.index());
    }

    private static void warnMissingDefaultTagTargets(TagIndex index) {
        Set<TargetKey> defaults;
        try {
            defaults = defaultTargets();
        } catch (IllegalStateException e) {
            // Config not loaded yet; the ServerStartedEvent pass warns instead.
            return;
        }
        for (String tag : missingOrEmptyDefaultTags(defaults, index)) {
            if (WARNED_DEFAULT_TAGS.add(tag)) {
                ItemMilestoneLog.LOGGER.warn(
                        "[ItemMilestoneLog] defaultTargets tag '{}' does not exist or is empty; it counts as 0 items", tag);
            }
        }
    }

    /** F8b: distinct tag IDs in {@code defaults} that do not exist or are empty in {@code index} (in target order). */
    static List<String> missingOrEmptyDefaultTags(Set<TargetKey> defaults, TagIndex index) {
        Set<String> missing = new LinkedHashSet<>();
        for (TargetKey key : defaults) {
            if (key.isTag() && !index.isNonEmptyTag(key.itemId())) {
                missing.add(key.itemId());
            }
        }
        return List.copyOf(missing);
    }

    /** IDs of the tag targets ({@code '#'}-prefixed) among {@code keys}. */
    static Set<String> tagIdsOf(Collection<TargetKey> keys) {
        Set<String> ids = new LinkedHashSet<>();
        for (TargetKey key : keys) {
            if (key.isTag()) {
                ids.add(key.itemId());
            }
        }
        return ids;
    }

    /**
     * F50: whether a target row gets the {@code target_creative_carried} note: an item target whose
     * item is carried out of creative, or a tag target with at least one carried member.
     */
    public static boolean isCreativeCarriedTarget(TargetKey key, Set<String> carried, TagIndex index) {
        if (carried.isEmpty()) {
            return false;
        }
        return key.isTag() ? index.anyMemberIn(key.itemId(), carried) : carried.contains(key.itemId());
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
        MilestoneData.PlayerData existingPd = data.playerOrNull(player.getUUID());
        String previousName = existingPd != null ? existingPd.lastKnownName : null;
        boolean hasMod = player.connection != null
                && player.connection.hasChannel(SyncMilestonesPayload.TYPE);
        List<Component> messages = processPlayerLogin(
                data,
                player.getUUID(),
                player.getGameProfile().name(),
                defaultTargets(),
                Config.FIRST_JOIN_PRESETS.get(),
                Config.PRESETS_ENABLED.get(),
                MilestoneService::isKnownNonAirItem,
                Config.SHOW_FIRST_JOIN_HINT.get(),
                hasMod,
                player,
                System.currentTimeMillis());
        for (Component msg : messages) {
            player.sendSystemMessage(msg);
        }
        ScoreboardSync.removeStaleNameScores(server, player.getUUID(), previousName);
        ScoreboardSync.refreshScores(server, player.getUUID());
        triggerExistingRecords(player, data, Config.WORLD_FIRST_ENABLED.get());
    }

    /**
     * F2: retroactive advancement criterion trigger on {@code /reload} ({@code event.getPlayer() == null})
     * for all online players. The login case ({@code event.getPlayer() != null}) is intentionally ignored
     * here and handled in {@link #onPlayerLoggedIn} after the player is added to the player list.
     */
    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) {
            return;
        }
        MinecraftServer server = event.getPlayerList().getServer();
        if (server == null) {
            return;
        }
        MilestoneData data = MilestoneData.get(server);
        boolean worldFirstEnabled = Config.WORLD_FIRST_ENABLED.get();
        for (ServerPlayer player : List.copyOf(event.getPlayerList().getPlayers())) {
            triggerExistingRecords(player, data, worldFirstEnabled);
        }
    }

    record RetroactiveTriggerEntry(TargetKey key, boolean worldFirst) {
    }

    /**
     * F2: collects every existing milestone record for {@code playerId} along with whether that
     * player holds the world-first for the key (always {@code false} when {@code worldFirstEnabled}
     * is {@code false}).
     */
    static List<RetroactiveTriggerEntry> collectRetroactiveTriggers(
            @Nullable MilestoneData data, UUID playerId, boolean worldFirstEnabled) {
        if (data == null || playerId == null) {
            return List.of();
        }
        MilestoneData.PlayerData pd = data.playerOrNull(playerId);
        if (pd == null || pd.records.isEmpty()) {
            return List.of();
        }
        List<RetroactiveTriggerEntry> entries = new ArrayList<>(pd.records.size());
        for (TargetKey key : pd.records.keySet()) {
            WorldFirstRecord wf = data.worldFirstOrNull(key);
            boolean worldFirst = MilestoneTriggerLogic.isRetroactiveWorldFirst(
                    playerId, wf != null ? wf.playerUuid() : null, worldFirstEnabled);
            entries.add(new RetroactiveTriggerEntry(key, worldFirst));
        }
        return entries;
    }

    private static void triggerExistingRecords(
            @Nullable ServerPlayer player, @Nullable MilestoneData data, boolean worldFirstEnabled) {
        if (player == null || player instanceof FakePlayer || data == null) {
            return;
        }
        for (RetroactiveTriggerEntry entry : collectRetroactiveTriggers(data, player.getUUID(), worldFirstEnabled)) {
            ModTriggers.trigger(player, entry.key(), entry.worldFirst(), worldFirstEnabled);
        }
    }

    static List<Component> processPlayerLogin(
            MilestoneData data,
            UUID playerId,
            @Nullable String playerName,
            Set<TargetKey> defaultTargets,
            @Nullable List<? extends String> firstJoinPresets,
            boolean presetsEnabled,
            Predicate<String> isKnownNonAirItem,
            boolean showFirstJoinHint,
            boolean hasModChannel,
            @Nullable ServerPlayer player) {
        return processPlayerLogin(
                data, playerId, playerName, defaultTargets, firstJoinPresets, presetsEnabled,
                isKnownNonAirItem, showFirstJoinHint, hasModChannel, player, System.currentTimeMillis());
    }

    static List<Component> processPlayerLogin(
            MilestoneData data,
            UUID playerId,
            @Nullable String playerName,
            Set<TargetKey> defaultTargets,
            @Nullable List<? extends String> firstJoinPresets,
            boolean presetsEnabled,
            Predicate<String> isKnownNonAirItem,
            boolean showFirstJoinHint,
            boolean hasModChannel,
            @Nullable ServerPlayer player,
            long nowMillis) {
        int firstJoinAdded = data.playerOrNull(playerId) == null
                ? MilestoneService.applyFirstJoinPresets(
                        data, playerId, firstJoinPresets, isKnownNonAirItem, presetsEnabled)
                : 0;
        MilestoneData.PlayerData pd = data.player(playerId);
        if (playerName != null && !playerName.equals(pd.lastKnownName)) {
            pd.lastKnownName = playerName;
            data.setDirty();
        }
        if (nowMillis > 0L && pd.lastSeen != nowMillis) {
            pd.lastSeen = nowMillis;
            data.setDirty();
        }
        if (cleanupProgressNotified(pd, effectiveTargets(defaultTargets, pd))) {
            data.setDirty();
        }
        List<Component> messages = new ArrayList<>(2);
        if (firstJoinAdded > 0) {
            messages.add(ServerLang.tr(
                    player, "message.itemmilestonelog.first_join_presets", firstJoinAdded));
        }
        if (showFirstJoinHint && !pd.firstJoinHintShown) {
            pd.firstJoinHintShown = true;
            data.setDirty();
            String key = hasModChannel
                    ? "message.itemmilestonelog.join_hint_mod"
                    : "message.itemmilestonelog.join_hint_vanilla";
            messages.add(ServerLang.tr(player, key));
        }
        return messages;
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof FakePlayer) {
            return;
        }
        UUID playerId = event.getEntity().getUUID();
        if (event.getEntity() instanceof ServerPlayer player) {
            MinecraftServer server = player.level().getServer();
            if (server != null) {
                processPlayerLogout(MilestoneData.get(server), playerId, System.currentTimeMillis());
            }
        }
        ModNetworking.removeRateLimit(playerId);
        PENDING_CRAFT_CHECKS.remove(playerId);
    }

    static void processPlayerLogout(MilestoneData data, UUID playerId, long nowMillis) {
        MilestoneData.PlayerData pd = data.playerOrNull(playerId);
        if (pd != null && nowMillis > 0L && pd.lastSeen != nowMillis) {
            pd.lastSeen = nowMillis;
            data.setDirty();
        }
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

        Set<String> stillCarried = new HashSet<>();
        Map<String, Integer> counts = countPendingPossession(
                possessedStacks(player), pending, carried, stillCarried, TagTargets.index());

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

    /** One non-empty stack in a player's possession (item ID and stack size). */
    record StackCount(String itemId, int count) {
    }

    /** Non-empty stacks in inventory (incl. armor/offhand), cursor, and 2x2 craft grid. */
    private static List<StackCount> possessedStacks(ServerPlayer player) {
        List<StackCount> stacks = new ArrayList<>();
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            addStack(stacks, inventory.getItem(i));
        }
        if (player.containerMenu != null) {
            addStack(stacks, player.containerMenu.getCarried());
        }
        for (ItemStack craftStack : player.inventoryMenu.getCraftSlots().getItems()) {
            addStack(stacks, craftStack);
        }
        return stacks;
    }

    private static void addStack(List<StackCount> stacks, ItemStack stack) {
        if (!stack.isEmpty()) {
            stacks.add(new StackCount(itemId(stack), stack.getCount()));
        }
    }

    /**
     * One scan pass over {@code stacks}: sums possession of the pending targets' items (tag targets
     * are expanded to their members, F8b) while recording which creative-carried items are still held
     * in {@code stillCarried}. Creative-carried items count neither for themselves nor toward any tag.
     * Returns item counts plus one total per pending tag ID.
     */
    static Map<String, Integer> countPendingPossession(List<StackCount> stacks, Set<TargetKey> pending,
                                                       Set<String> carried, Set<String> stillCarried,
                                                       TagIndex tagIndex) {
        Set<String> neededItemIds = tagIndex.expandForCounting(pendingItemIdsToCount(pending, carried), carried);
        Map<String, Integer> counts = new HashMap<>();
        for (StackCount stack : stacks) {
            accumulateItem(stack.itemId(), stack.count(), carried, stillCarried, neededItemIds, counts);
        }
        Set<String> tagIds = tagIdsOf(pending);
        return tagIds.isEmpty() ? counts : tagIndex.withTagTotals(counts, tagIds, carried);
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
                int affected = countCreativeCarriedTargetItems(
                        newlyMarked, effectiveTargets(pd), pd.records.keySet(), TagTargets.index());
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
        return countCreativeCarriedTargetItems(markedItemIds, effectiveTargets, recordedKeys, TagIndex.EMPTY);
    }

    /**
     * F50 (1.2.0): like {@link #countCreativeCarriedTargetItems(Set, Set, Set)}, also counting marked
     * items that belong to an unrecorded tag target (each item ID counted once).
     */
    static int countCreativeCarriedTargetItems(
            Set<String> markedItemIds,
            Set<TargetKey> effectiveTargets,
            Set<TargetKey> recordedKeys,
            TagIndex tagIndex) {
        if (markedItemIds == null || markedItemIds.isEmpty()
                || effectiveTargets == null || effectiveTargets.isEmpty()) {
            return 0;
        }
        Set<String> affectedItemIds = new HashSet<>();
        for (TargetKey key : effectiveTargets) {
            if (key == null || (recordedKeys != null && recordedKeys.contains(key))) {
                continue;
            }
            if (key.isTag()) {
                for (String member : tagIndex.members(key.itemId())) {
                    if (markedItemIds.contains(member)) {
                        affectedItemIds.add(member);
                    }
                }
            } else if (markedItemIds.contains(key.itemId())) {
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

    /**
     * Counts simultaneous possession for the requested IDs. Tag IDs ({@code '#'}-prefixed, F8b) are
     * expanded to their member items and summed; the player's creative-carried items are not counted
     * toward a tag (item IDs are counted as-is; callers apply the per-item creative-carry rule).
     */
    public static Map<String, Integer> countItemsInPossession(ServerPlayer player, Set<String> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        MilestoneData.PlayerData pd = MilestoneData.get(player.level().getServer()).playerOrNull(player.getUUID());
        return countRequestedPossession(possessedStacks(player), itemIds, carriedFromCreative(pd), TagTargets.index());
    }

    /** Pure counterpart of {@link #countItemsInPossession(ServerPlayer, Set)}. */
    static Map<String, Integer> countRequestedPossession(List<StackCount> stacks, Set<String> requestedIds,
                                                         Set<String> carried, TagIndex tagIndex) {
        if (requestedIds.isEmpty()) {
            return Map.of();
        }
        Set<String> concrete = tagIndex.expandForCounting(requestedIds, carried);
        Map<String, Integer> counts = new HashMap<>();
        for (StackCount stack : stacks) {
            if (concrete.contains(stack.itemId())) {
                counts.merge(stack.itemId(), stack.count(), Integer::sum);
            }
        }
        Set<String> tagIds = new LinkedHashSet<>();
        for (String id : requestedIds) {
            if (TargetKey.isTagId(id)) {
                tagIds.add(id);
            }
        }
        return tagIds.isEmpty() ? counts : tagIndex.withTagTotals(counts, tagIds, carried);
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

    /**
     * F8b / F36: unrecorded tag targets (defaults first, then personal targets not in defaults)
     * whose tag contains {@code itemId}.
     */
    static List<TargetKey> collectUnrecordedTagTargetsForItem(
            String itemId,
            Set<TargetKey> defaultTargets,
            @Nullable MilestoneData.PlayerData pd,
            TagIndex tagIndex) {
        Set<String> tags = tagIndex.tagsContaining(itemId);
        if (tags.isEmpty()) {
            return List.of();
        }
        List<TargetKey> pending = new ArrayList<>();
        for (TargetKey key : defaultTargets) {
            if (key.isTag() && tags.contains(key.itemId()) && (pd == null || !pd.records.containsKey(key))) {
                pending.add(key);
            }
        }
        if (pd != null) {
            for (TargetKey key : pd.targets) {
                if (key.isTag() && tags.contains(key.itemId())
                        && !pd.records.containsKey(key) && !defaultTargets.contains(key)) {
                    pending.add(key);
                }
            }
        }
        return pending;
    }

    record TagAcquisitionOutcome(List<TargetKey> achieved, List<TargetKey> pending, Map<String, Integer> totals) {
        static final TagAcquisitionOutcome EMPTY = new TagAcquisitionOutcome(List.of(), List.of(), Map.of());
    }

    /**
     * F8b: evaluates the tag targets containing a just-acquired item. {@code possessionCounter}
     * returns per-tag totals (creative-carried members excluded) and is only called when at least one
     * unrecorded tag target contains the item.
     */
    static TagAcquisitionOutcome evaluateTagAcquisition(
            String itemId,
            Set<TargetKey> defaultTargets,
            @Nullable MilestoneData.PlayerData pd,
            TagIndex tagIndex,
            Function<Set<String>, Map<String, Integer>> possessionCounter) {
        List<TargetKey> pending = collectUnrecordedTagTargetsForItem(itemId, defaultTargets, pd, tagIndex);
        if (pending.isEmpty()) {
            return TagAcquisitionOutcome.EMPTY;
        }
        Map<String, Integer> totals = possessionCounter.apply(tagIdsOf(pending));
        List<TargetKey> achieved = new ArrayList<>();
        for (TargetKey key : pending) {
            if (totals.getOrDefault(key.itemId(), 0) >= key.count()) {
                achieved.add(key);
            }
        }
        achieved.sort(TargetKey::compareTo);
        return new TagAcquisitionOutcome(achieved, pending, totals);
    }

    private static void tryRecordForItem(ServerPlayer player, String itemId, int minCountFromEvent,
                                         boolean alreadyAddedToInventory) {
        if (recordingPaused(player)) {
            return;
        }
        MilestoneData data = MilestoneData.get(player.level().getServer());
        MilestoneData.PlayerData pd = data.playerOrNull(player.getUUID());
        Set<String> carried = carriedFromCreative(pd);
        Set<TargetKey> defaults = defaultTargets();
        ItemAcquisitionOutcome outcome = evaluateItemAcquisition(
                itemId,
                () -> countItemInPossession(player, itemId),
                minCountFromEvent,
                alreadyAddedToInventory,
                defaults,
                pd,
                carried);
        if (outcome != ItemAcquisitionOutcome.EMPTY) {
            applyItemAcquisition(player, data, pd, itemId, minCountFromEvent, alreadyAddedToInventory, outcome);
        }
        // Runs after the item pass so a just-cleared creative carry lets this pickup count toward tags.
        TagAcquisitionOutcome tagOutcome = evaluateTagAcquisition(
                itemId, defaults, data.playerOrNull(player.getUUID()), TagTargets.index(),
                ids -> countItemsInPossession(player, ids));
        if (tagOutcome != TagAcquisitionOutcome.EMPTY) {
            applyTagAcquisition(player, data, tagOutcome);
        }
    }

    private static void applyTagAcquisition(ServerPlayer player, MilestoneData data, TagAcquisitionOutcome outcome) {
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
        if (Config.PROGRESS_ALERTS.get()) {
            List<ProgressAlertNotification> alerts = evaluateProgressAlerts(
                    data,
                    player.getUUID(),
                    outcome.pending(),
                    Set.of(),
                    outcome.totals(),
                    true,
                    Config.progressAlertThresholds(),
                    Config.PROGRESS_ALERT_MIN_COUNT.get());
            if (!alerts.isEmpty()) {
                sendProgressAlerts(player, alerts);
            }
        }
    }

    private static void applyItemAcquisition(ServerPlayer player, MilestoneData data,
                                             @Nullable MilestoneData.PlayerData pd, String itemId,
                                             int minCountFromEvent, boolean alreadyAddedToInventory,
                                             ItemAcquisitionOutcome outcome) {
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

    static List<Presets.PresetDefinition> evaluatePresetCompletions(
            MilestoneData data,
            UUID playerId,
            TargetKey justRecordedKey,
            Collection<Presets.PresetDefinition> allPresets,
            Predicate<String> itemExists,
            boolean presetsEnabled) {
        if (!presetsEnabled) {
            return List.of();
        }
        MilestoneData.PlayerData pd = data.playerOrNull(playerId);
        if (pd == null || pd.records.isEmpty()) {
            return List.of();
        }
        List<Presets.PresetDefinition> newlyCompleted = Presets.findNewlyCompletedPresets(
                justRecordedKey,
                pd.records,
                allPresets,
                itemExists,
                pd.completedPresets,
                true);
        if (newlyCompleted.isEmpty()) {
            return List.of();
        }
        boolean dirty = false;
        for (Presets.PresetDefinition preset : newlyCompleted) {
            if (pd.completedPresets.add(preset.id())) {
                dirty = true;
            }
        }
        if (dirty) {
            data.setDirty();
        }
        return newlyCompleted;
    }

    static boolean shouldReceivePresetCompletionChat(
            UUID actorId,
            UUID recipientId,
            boolean recipientMutedBroadcasts,
            boolean announcePresetCompletion,
            boolean notifyPlayer) {
        if (announcePresetCompletion) {
            return actorId.equals(recipientId) || !recipientMutedBroadcasts;
        }
        return notifyPlayer && actorId.equals(recipientId);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!PENDING_NOTIFICATIONS.isEmpty()) {
            flushPendingNotifications(event.getServer(), drainPendingNotifications());
        }
        MilestoneCommandRunner.drainAndRun(event.getServer());
    }

    static void queuePendingNotification(
            UUID playerId,
            @Nullable String playerName,
            @Nullable Component actorDisplayName,
            TargetKey key,
            MilestoneRecord record,
            boolean worldFirst) {
        queuePendingNotification(playerId, playerName, actorDisplayName, key, record, worldFirst, true);
    }

    static void queuePendingNotification(
            UUID playerId,
            @Nullable String playerName,
            @Nullable Component actorDisplayName,
            TargetKey key,
            MilestoneRecord record,
            boolean worldFirst,
            boolean postToDiscord) {
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
        QueuedRecord queued = new QueuedRecord(key, record, worldFirst);
        pending.records.add(queued);
        if (postToDiscord) {
            pending.discordRecords.add(queued);
        }
    }

    static void queuePendingPresetCompletions(
            UUID playerId,
            @Nullable String playerName,
            @Nullable Component actorDisplayName,
            List<Presets.PresetDefinition> completedPresets,
            long epochMillis,
            boolean postToDiscord) {
        if (completedPresets == null || completedPresets.isEmpty()) {
            return;
        }
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
        for (Presets.PresetDefinition preset : completedPresets) {
            if (preset == null) {
                continue;
            }
            QueuedPresetCompletion queued = new QueuedPresetCompletion(preset, epochMillis);
            pending.presetCompletions.add(queued);
            if (postToDiscord) {
                pending.discordPresetCompletions.add(queued);
            }
        }
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

    /** Maximum nesting depth of {@code ItemMilestoneLogApi#recordNow} calls (F32). */
    static final int MAX_RECORD_NOW_DEPTH = ItemMilestoneLogApi.MAX_RECORD_NOW_DEPTH;

    private static final NestingGuard RECORD_NOW_GUARD = new NestingGuard(MAX_RECORD_NOW_DEPTH);

    /**
     * Limits the nesting depth of re-entrant calls (F32: {@code recordNow} called from a
     * {@link MilestoneRecordedEvent} listener) so an endless chain cannot exhaust the stack.
     * Server-thread only (not thread-safe).
     */
    static final class NestingGuard {
        private final int maxDepth;
        private int depth;

        NestingGuard(int maxDepth) {
            this.maxDepth = maxDepth;
        }

        int depth() {
            return depth;
        }

        /**
         * Runs {@code action} one level deeper and returns its result, or, if that would exceed
         * {@code maxDepth}, runs {@code onOverflow} instead and returns {@code false}.
         */
        boolean run(BooleanSupplier action, Runnable onOverflow) {
            if (depth >= maxDepth) {
                onOverflow.run();
                return false;
            }
            depth++;
            try {
                return action.getAsBoolean();
            } finally {
                depth--;
            }
        }
    }

    /**
     * F32 {@code recordNow} preconditions: {@code false} for a FakePlayer, while recording is paused,
     * when {@code key} is not an effective target (defaults + personal), or when already recorded.
     */
    static boolean canRecordNow(boolean fakePlayer, boolean recordingPaused, Set<TargetKey> defaults,
                                @Nullable MilestoneData.PlayerData pd, TargetKey key) {
        if (fakePlayer || recordingPaused) {
            return false;
        }
        if (!effectiveTargets(defaults, pd).contains(key)) {
            return false;
        }
        return pd == null || !pd.records.containsKey(key);
    }

    /**
     * Backs {@code ItemMilestoneLogApi#recordNow} (F32): records {@code key} without checking possession,
     * through the normal record path with {@link RecordCause#API}. Returns {@code false} without doing
     * anything when {@link #canRecordNow} rejects it or when the nesting depth would exceed
     * {@value #MAX_RECORD_NOW_DEPTH} (logged as a warning). Callers check nulls and the server thread.
     */
    public static boolean recordNow(ServerPlayer player, TargetKey key) {
        return RECORD_NOW_GUARD.run(
                () -> recordNowUnguarded(player, key),
                () -> ItemMilestoneLog.LOGGER.warn(
                        "[ItemMilestoneLog] recordNow nesting depth exceeded {}; ignoring {} for {}",
                        MAX_RECORD_NOW_DEPTH, key.formatDisplayId(), player.getUUID()));
    }

    private static boolean recordNowUnguarded(ServerPlayer player, TargetKey key) {
        TargetKey normalized = TargetKey.normalize(key.itemId(), key.count());
        if (normalized == null) {
            return false;
        }
        MilestoneData data = MilestoneData.get(player.level().getServer());
        if (!canRecordNow(player instanceof FakePlayer, recordingPaused(player), defaultTargets(),
                data.playerOrNull(player.getUUID()), normalized)) {
            return false;
        }
        return record(player, data, normalized, true, RecordCause.API);
    }

    enum GrantStatus {
        OFFLINE,
        INVALID_COUNT,
        INVALID_ITEM,
        ALREADY_RECORDED,
        TARGETS_LIMIT_REACHED,
        GRANTED
    }

    record GrantResult(
            GrantStatus status,
            Component executorMessage,
            @Nullable TargetKey key,
            @Nullable MilestoneRecord record,
            boolean autoAddedTarget,
            List<Presets.PresetDefinition> completedPresets) {
        boolean success() {
            return status == GrantStatus.GRANTED;
        }
    }

    /**
     * F33 {@code /milestonelog admin <player> grant <item> [<count>]}: grants a milestone to an
     * online player without checking possession or recording pause, never registers a world-first,
     * auto-adds a missing target to personal targets (enforcing the cap), cleans F1 progress,
     * evaluates F5 preset completion (updating {@code completedPresets}, notifying the target when
     * {@code notifyPlayer} is true, and posting {@link PresetCompletedEvent}), notifies the target
     * with {@code granted_notice} regardless of {@code notifyPlayer}, posts
     * {@link MilestoneRecordedEvent} with {@link RecordCause#ADMIN_GRANT} and {@code worldFirst = false},
     * logs an info line, and re-syncs the target's GUI. Does not broadcast, announce world-first,
     * play sound, post to Discord, or broadcast F5 completion.
     */
    public static MilestoneService.ActionResult grantMilestone(
            CommandSourceStack source,
            UUID targetId,
            String targetName,
            String itemId,
            int count) {
        MinecraftServer server = source.getServer();
        ServerPlayer online = server.getPlayerList().getPlayer(targetId);
        String resolvedName = targetName;
        if (online != null) {
            String profileName = online.getGameProfile().name();
            if (profileName != null && !profileName.isBlank()) {
                resolvedName = profileName;
            }
        }
        String finalName = resolvedName;
        MilestoneData data = MilestoneData.get(server);
        GrantResult result = grantMilestone(
                data,
                targetId,
                finalName,
                online != null,
                online,
                source.getPlayer(),
                itemId,
                count,
                defaultTargets(),
                () -> new MilestoneRecord(
                        Playtime.currentTicks(online),
                        Playtime.currentDay(server),
                        System.currentTimeMillis()),
                Presets.all(),
                MilestoneService::isKnownNonAirItem,
                MilestoneService::targetDisplayName,
                Config.PRESETS_ENABLED.get(),
                Config.NOTIFY_PLAYER.get(),
                (grantedKey, grantedRec) -> ItemMilestoneLog.LOGGER.info(
                        "[ItemMilestoneLog] Admin {} granted {} to {} ({}) at playtime {} ({} ticks)",
                        source.getTextName(),
                        grantedKey.formatDisplayId(),
                        finalName,
                        targetId,
                        Playtime.formatRecord(grantedRec, Config.displayTimeZone()),
                        grantedRec.playTicks()),
                msg -> {
                    if (online != null) {
                        online.sendSystemMessage(msg);
                    }
                },
                event -> NeoForge.EVENT_BUS.post(event));
        if (result.success()) {
            ScoreboardSync.refreshScores(server, targetId);
            if (online != null) {
                if (result.key() != null) {
                    ModTriggers.trigger(online, result.key(), false, Config.WORLD_FIRST_ENABLED.get());
                }
                MilestoneService.syncPlayerIfOpen(online);
            }
        }
        return new MilestoneService.ActionResult(result.success(), result.executorMessage());
    }

    static GrantResult grantMilestone(
            MilestoneData data,
            UUID targetId,
            String targetName,
            boolean targetOnline,
            @Nullable ServerPlayer targetPlayer,
            @Nullable ServerPlayer executorPlayer,
            String itemId,
            int count,
            Set<TargetKey> defaultTargets,
            Supplier<MilestoneRecord> recordSupplier,
            Collection<Presets.PresetDefinition> allPresets,
            Predicate<String> isKnownNonAirItem,
            Function<TargetKey, Component> displayName,
            boolean presetsEnabled,
            boolean notifyPlayer,
            BiConsumer<TargetKey, MilestoneRecord> onLogged,
            Consumer<Component> targetMessageSender,
            Consumer<Event> eventPoster) {
        Component literalTargetName = Component.literal(targetName);
        if (!targetOnline) {
            return new GrantResult(
                    GrantStatus.OFFLINE,
                    ServerLang.tr(executorPlayer, "message.itemmilestonelog.admin_grant_offline", literalTargetName),
                    null, null, false, List.of());
        }
        if (!TargetKey.isValidCount(count)) {
            return new GrantResult(
                    GrantStatus.INVALID_COUNT,
                    ServerLang.tr(executorPlayer, "message.itemmilestonelog.invalid_count",
                            TargetKey.MIN_COUNT, TargetKey.MAX_COUNT),
                    null, null, false, List.of());
        }
        String normalizedId = TargetKey.normalizeItemId(itemId);
        if (normalizedId == null || !isKnownNonAirItem.test(normalizedId)) {
            return new GrantResult(
                    GrantStatus.INVALID_ITEM,
                    ServerLang.tr(executorPlayer, "message.itemmilestonelog.invalid_item", String.valueOf(itemId)),
                    null, null, false, List.of());
        }
        TargetKey key = new TargetKey(normalizedId, count);
        Component keyDisplay = displayName.apply(key);

        MilestoneData.PlayerData existingPd = data.playerOrNull(targetId);
        if (existingPd != null && existingPd.records.containsKey(key)) {
            return new GrantResult(
                    GrantStatus.ALREADY_RECORDED,
                    ServerLang.tr(executorPlayer, "message.itemmilestonelog.admin_grant_already",
                            literalTargetName, keyDisplay),
                    key, null, false, List.of());
        }

        boolean inEffective = effectiveTargets(defaultTargets, existingPd).contains(key);
        boolean autoAdded = false;
        if (!inEffective) {
            int currentPersonalSize = existingPd != null ? existingPd.targets.size() : 0;
            if (currentPersonalSize >= MilestoneService.MAX_PERSONAL_TARGETS) {
                return new GrantResult(
                        GrantStatus.TARGETS_LIMIT_REACHED,
                        ServerLang.tr(executorPlayer, "message.itemmilestonelog.targets_limit_reached",
                                MilestoneService.MAX_PERSONAL_TARGETS),
                        key, null, false, List.of());
            }
            data.player(targetId).targets.add(key);
            autoAdded = true;
        }

        MilestoneRecord rec = recordSupplier.get();
        // World-first is never registered on admin grant; applyRecord also performs F1 progressNotified cleanup.
        RecordOutcome outcome = applyRecord(data, targetId, targetName, key, rec, false);
        if (outcome == RecordOutcome.ALREADY_RECORDED) {
            return new GrantResult(
                    GrantStatus.ALREADY_RECORDED,
                    ServerLang.tr(executorPlayer, "message.itemmilestonelog.admin_grant_already",
                            literalTargetName, keyDisplay),
                    key, null, false, List.of());
        }

        List<Presets.PresetDefinition> completedPresets = evaluatePresetCompletions(
                data,
                targetId,
                key,
                allPresets,
                isKnownNonAirItem,
                presetsEnabled);

        onLogged.accept(key, rec);

        // granted_notice is sent regardless of notifyPlayer.
        targetMessageSender.accept(ServerLang.tr(
                targetPlayer,
                "message.itemmilestonelog.granted_notice",
                keyDisplay,
                Playtime.formatComponent(targetPlayer, rec.playTicks())));

        // F5 preset completion: only personal preset_completed to the target when notifyPlayer is true (no broadcast).
        if (notifyPlayer && !completedPresets.isEmpty()) {
            for (Presets.PresetDefinition preset : completedPresets) {
                Component presetName = MilestoneService.presetDisplayName(targetPlayer, preset);
                targetMessageSender.accept(ServerLang.tr(
                        targetPlayer,
                        "message.itemmilestonelog.preset_completed",
                        presetName));
            }
        }

        postRecordEvents(targetPlayer, key, rec, outcome, completedPresets, RecordCause.ADMIN_GRANT, eventPoster);

        return new GrantResult(
                GrantStatus.GRANTED,
                ServerLang.tr(executorPlayer, "message.itemmilestonelog.admin_granted",
                        literalTargetName, keyDisplay),
                key, rec, autoAdded, completedPresets);
    }

    private static boolean record(ServerPlayer player, MilestoneData data, TargetKey key, boolean syncImmediately) {
        return record(player, data, key, syncImmediately, RecordCause.GAMEPLAY);
    }

    private static boolean record(ServerPlayer player, MilestoneData data, TargetKey key, boolean syncImmediately,
                                  RecordCause cause) {
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
        boolean worldFirstEnabled = Config.WORLD_FIRST_ENABLED.get();
        RecordOutcome outcome = applyRecord(data, player.getUUID(), playerName, key, rec,
                worldFirstEnabled);
        if (outcome == RecordOutcome.ALREADY_RECORDED) {
            return false;
        }
        ScoreboardSync.refreshScores(server, player.getUUID());
        boolean worldFirst = outcome == RecordOutcome.RECORDED_WORLD_FIRST;
        ModTriggers.trigger(player, key, worldFirst, worldFirstEnabled);
        List<Presets.PresetDefinition> completedPresets = evaluatePresetCompletions(
                data,
                player.getUUID(),
                key,
                Presets.all(),
                MilestoneService::isKnownNonAirItem,
                Config.PRESETS_ENABLED.get());
        ZoneId zone = Config.displayTimeZone();
        String formattedRecord = Playtime.formatRecord(rec, zone);

        ItemMilestoneLog.LOGGER.info("[ItemMilestoneLog] {} first obtained {} at playtime {} ({} ticks)",
                playerName, key.formatDisplayId(), formattedRecord, ticks);
        MilestoneCommandRunner.enqueue(player.getUUID(), safePlayerName, key, ticks, worldFirst);

        boolean postToDiscord = DiscordNotifier.shouldPostToDiscord(pd);
        if (Config.GROUP_SIMULTANEOUS_RECORDS.get()) {
            queuePendingNotification(
                    player.getUUID(),
                    safePlayerName,
                    player.getDisplayName(),
                    key,
                    rec,
                    worldFirst,
                    postToDiscord);
            if (!completedPresets.isEmpty()) {
                queuePendingPresetCompletions(
                        player.getUUID(),
                        safePlayerName,
                        player.getDisplayName(),
                        completedPresets,
                        rec.epochMillis(),
                        postToDiscord);
            }
            postRecordEvents(player, key, rec, outcome, completedPresets, cause, event -> NeoForge.EVENT_BUS.post(event));
        } else {
            sendImmediateNotifications(server, data, player, key, ticks, worldFirst, rec, zone);
            for (Presets.PresetDefinition preset : completedPresets) {
                sendPresetCompletionChat(
                        server,
                        data,
                        player.getUUID(),
                        player.getDisplayName(),
                        player,
                        preset,
                        Config.ANNOUNCE_PRESET_COMPLETION.get(),
                        Config.NOTIFY_PLAYER.get());
            }
            postRecordEvents(player, key, rec, outcome, completedPresets, cause, event -> NeoForge.EVENT_BUS.post(event));
            if (postToDiscord) {
                DiscordNotifier.notifyMilestone(pd, safePlayerName, key, rec, worldFirst);
                for (Presets.PresetDefinition preset : completedPresets) {
                    notifyDiscordPresetCompletion(pd, safePlayerName, preset, rec.epochMillis());
                }
            }
        }

        if (syncImmediately) {
            MilestoneService.syncPlayerIfOpen(player);
        }
        return true;
    }

    static void postRecordEvents(
            ServerPlayer player,
            TargetKey key,
            MilestoneRecord record,
            RecordOutcome outcome,
            List<Presets.PresetDefinition> completedPresets,
            Consumer<Event> eventPoster) {
        postRecordEvents(player, key, record, outcome, completedPresets, RecordCause.GAMEPLAY, eventPoster);
    }

    static void postRecordEvents(
            ServerPlayer player,
            TargetKey key,
            MilestoneRecord record,
            RecordOutcome outcome,
            List<Presets.PresetDefinition> completedPresets,
            RecordCause cause,
            Consumer<Event> eventPoster) {
        if (outcome == RecordOutcome.ALREADY_RECORDED) {
            return;
        }
        eventPoster.accept(new MilestoneRecordedEvent(
                player,
                key.itemId(),
                key.count(),
                record.playTicks(),
                record.day(),
                record.epochMillis(),
                outcome == RecordOutcome.RECORDED_WORLD_FIRST,
                cause));
        if (completedPresets != null) {
            for (Presets.PresetDefinition preset : completedPresets) {
                eventPoster.accept(new PresetCompletedEvent(player, preset.id(), preset.builtin()));
            }
        }
    }

    private static void sendPresetCompletionChat(
            MinecraftServer server,
            MilestoneData data,
            UUID actorId,
            Component actorDisplayName,
            @Nullable ServerPlayer onlineActor,
            Presets.PresetDefinition preset,
            boolean announcePresetCompletion,
            boolean notifyPlayer) {
        if (announcePresetCompletion) {
            for (ServerPlayer recipient : server.getPlayerList().getPlayers()) {
                MilestoneData.PlayerData recPd = data.playerOrNull(recipient.getUUID());
                boolean muted = recPd != null && recPd.muteBroadcasts;
                if (!shouldReceivePresetCompletionChat(actorId, recipient.getUUID(), muted, true, notifyPlayer)) {
                    continue;
                }
                Component presetName = MilestoneService.presetDisplayName(recipient, preset);
                recipient.sendSystemMessage(ServerLang.tr(
                        recipient,
                        "message.itemmilestonelog.preset_completed_broadcast",
                        actorDisplayName,
                        presetName));
            }
        } else if (onlineActor != null
                && shouldReceivePresetCompletionChat(actorId, onlineActor.getUUID(), false, false, notifyPlayer)) {
            Component presetName = MilestoneService.presetDisplayName(onlineActor, preset);
            onlineActor.sendSystemMessage(ServerLang.tr(
                    onlineActor,
                    "message.itemmilestonelog.preset_completed",
                    presetName));
        }
    }

    /**
     * Posts a newly detected preset completion to Discord (F19).
     * Called only when {@link DiscordNotifier#shouldPostToDiscord(MilestoneData.PlayerData)} allows
     * posting for the completing player (both immediately when {@code groupSimultaneousRecords} is
     * false and on tick-end flush when true).
     */
    private static void notifyDiscordPresetCompletion(
            @Nullable MilestoneData.PlayerData playerData,
            String playerName,
            Presets.PresetDefinition preset,
            long epochMillis) {
        DiscordNotifier.notifyPresetCompletion(playerData, playerName, preset, epochMillis);
    }

    private static void sendImmediateNotifications(
            MinecraftServer server,
            MilestoneData data,
            ServerPlayer player,
            TargetKey key,
            int ticks,
            boolean worldFirst,
            MilestoneRecord rec,
            ZoneId zone) {
        Component displayName = MilestoneService.targetDisplayName(key);

        if (Config.NOTIFY_PLAYER.get()) {
            Component msg = ServerLang.tr(player, "message.itemmilestonelog.recorded",
                    displayName, Playtime.formatComponent(player, ticks));
            player.sendSystemMessage(msg);
        }
        if (Config.BROADCAST.get()) {
            for (ServerPlayer other : server.getPlayerList().getPlayers()) {
                if (other == player) {
                    continue;
                }
                MilestoneData.PlayerData otherPd = data.playerOrNull(other.getUUID());
                if (otherPd != null && otherPd.muteBroadcasts) {
                    continue;
                }
                other.sendSystemMessage(ServerLang.tr(other, "message.itemmilestonelog.recorded_broadcast",
                        player.getDisplayName(), displayName, Playtime.formatComponent(other, ticks)));
            }
        }
        if (worldFirst && Config.ANNOUNCE_WORLD_FIRST.get()) {
            for (ServerPlayer recipient : server.getPlayerList().getPlayers()) {
                MilestoneData.PlayerData recPd = data.playerOrNull(recipient.getUUID());
                if (recipient != player && recPd != null && recPd.muteBroadcasts) {
                    continue;
                }
                recipient.sendSystemMessage(ServerLang.tr(recipient, "message.itemmilestonelog.world_first_recorded",
                        player.getDisplayName(), displayName, Playtime.formatRecordComponent(recipient, rec, zone)));
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
        boolean announcePresetCompletion = Config.ANNOUNCE_PRESET_COMPLETION.get();
        boolean milestoneSoundEnabled = Config.MILESTONE_SOUND_ENABLED.get();
        String discordScope = Config.DISCORD_NOTIFY_SCOPE.get();
        ZoneId zone = Config.displayTimeZone();

        for (PendingNotifications pending : batch) {
            flushPlayerNotifications(
                    server, data, pending,
                    notifyPlayer, broadcastMilestones, announceWorldFirst, announcePresetCompletion,
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
            boolean announcePresetCompletion,
            boolean milestoneSoundEnabled,
            String discordScope,
            ZoneId zone) {
        MilestoneData.PlayerData actorPd = data.playerOrNull(pending.playerId);
        boolean postToDiscord = DiscordNotifier.shouldPostToDiscord(actorPd);
        NotificationGrouping.FlushPlan<QueuedRecord> plan = NotificationGrouping.planFlush(
                pending.records,
                pending.discordRecords,
                QueuedRecord::worldFirst,
                Comparator.comparing(QueuedRecord::key),
                notifyPlayer,
                broadcastMilestones,
                announceWorldFirst,
                milestoneSoundEnabled,
                postToDiscord,
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

        // 4. F5 preset completion chat notifications (sent per preset, never grouped)
        if (!pending.presetCompletions.isEmpty()) {
            for (QueuedPresetCompletion completed : pending.presetCompletions) {
                sendPresetCompletionChat(
                        server,
                        data,
                        pending.playerId,
                        actorDisplayName,
                        onlineActor,
                        completed.preset(),
                        announcePresetCompletion,
                        notifyPlayer);
            }
        }

        // 5. Discord record notifications (still sent even if player went offline)
        if (postToDiscord) {
            sendDiscordChannel(actorPd, pending.playerName, plan.discord());
        }

        // 6. F19 Discord preset completion notifications (still sent even if player went offline;
        //    gated by DiscordNotifier.shouldPostToDiscord)
        if (postToDiscord && !pending.discordPresetCompletions.isEmpty()) {
            for (QueuedPresetCompletion completed : pending.discordPresetCompletions) {
                notifyDiscordPresetCompletion(
                        actorPd,
                        pending.playerName,
                        completed.preset(),
                        completed.epochMillis());
            }
        }
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
                        displayName, Playtime.formatComponent(player, q.record().playTicks())));
            }
            return;
        }
        List<QueuedRecord> items = channel.items();
        Component formattedPlaytime = Playtime.formatComponent(player, items.get(0).record().playTicks());
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
                int ticks = q.record().playTicks();
                for (ServerPlayer other : server.getPlayerList().getPlayers()) {
                    if (other.getUUID().equals(actorId)) {
                        continue;
                    }
                    MilestoneData.PlayerData otherPd = data.playerOrNull(other.getUUID());
                    if (otherPd != null && otherPd.muteBroadcasts) {
                        continue;
                    }
                    other.sendSystemMessage(ServerLang.tr(other, "message.itemmilestonelog.recorded_broadcast",
                            actorDisplayName, displayName, Playtime.formatComponent(other, ticks)));
                }
            }
            return;
        }
        List<QueuedRecord> items = channel.items();
        int groupTicks = items.get(0).record().playTicks();
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
                    actorDisplayName, items.size(), Playtime.formatComponent(other, groupTicks), targetList));
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
                MilestoneRecord queuedRecord = q.record();
                for (ServerPlayer recipient : server.getPlayerList().getPlayers()) {
                    MilestoneData.PlayerData recPd = data.playerOrNull(recipient.getUUID());
                    if (!recipient.getUUID().equals(actorId) && recPd != null && recPd.muteBroadcasts) {
                        continue;
                    }
                    recipient.sendSystemMessage(ServerLang.tr(recipient, "message.itemmilestonelog.world_first_recorded",
                            actorDisplayName, displayName, Playtime.formatRecordComponent(recipient, queuedRecord, zone)));
                }
            }
            return;
        }
        List<QueuedRecord> items = channel.items();
        MilestoneRecord groupRecord = items.get(0).record();
        for (ServerPlayer recipient : server.getPlayerList().getPlayers()) {
            MilestoneData.PlayerData recPd = data.playerOrNull(recipient.getUUID());
            if (!recipient.getUUID().equals(actorId) && recPd != null && recPd.muteBroadcasts) {
                continue;
            }
            Component targetList = buildTargetListComponent(recipient, items);
            recipient.sendSystemMessage(ServerLang.tr(recipient, "message.itemmilestonelog.world_first_group",
                    actorDisplayName, items.size(), Playtime.formatRecordComponent(recipient, groupRecord, zone), targetList));
        }
    }

    private static void sendDiscordChannel(
            @Nullable MilestoneData.PlayerData playerData,
            String playerName,
            NotificationGrouping.ChannelPlan<QueuedRecord> channel) {
        if (channel.mode() == NotificationGrouping.SendMode.NONE) {
            return;
        }
        if (channel.mode() == NotificationGrouping.SendMode.INDIVIDUAL) {
            for (QueuedRecord q : channel.items()) {
                DiscordNotifier.notifyMilestone(playerData, playerName, q.key(), q.record(), q.worldFirst());
            }
            return;
        }
        List<DiscordNotifier.MilestoneEntry> entries = new ArrayList<>(channel.items().size());
        for (QueuedRecord q : channel.items()) {
            entries.add(new DiscordNotifier.MilestoneEntry(q.key(), q.record(), q.worldFirst()));
        }
        DiscordNotifier.notifyMilestonesGrouped(playerData, playerName, entries);
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
