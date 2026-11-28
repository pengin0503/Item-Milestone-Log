package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.ItemMilestoneLogApi;
import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.MilestoneRecordedEvent;
import com.pengin0503.itemmilestonelog.api.PresetCompletedEvent;
import com.pengin0503.itemmilestonelog.api.RecordCause;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.api.WorldFirstRecord;
import com.pengin0503.itemmilestonelog.compat.McCompat;
import com.pengin0503.itemmilestonelog.network.ModNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;

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

    /** Test seam: intercepts resolved sound requests instead of sending vanilla packets. */
    private static volatile Consumer<NotificationSoundLogic.SoundRequest> soundSink = null;

    static void setSoundSinkForTests(@Nullable Consumer<NotificationSoundLogic.SoundRequest> sink) {
        soundSink = sink;
    }

    /**
     * SF-1: the actor's world-first sound event is derived from the queued records, never
     * from the announcement chat gate ({@code announceWorldFirst}), which applies only to
     * the recipients' opt-in sound inside {@link NotificationSoundLogic#plan}.
     */
    static boolean hasWorldFirstQueued(@Nullable PendingNotifications pending) {
        return pending != null && pending.records.stream().anyMatch(QueuedRecord::worldFirst);
    }

    /** Explicit config snapshot for one window; keeps the sound planner unit-testable. */
    record SoundConfigSnapshot(
            boolean milestoneSoundEnabled,
            String milestoneSoundId,
            String worldFirstSoundId,
            String presetCompleteSoundId,
            String progressAlertSoundId,
            boolean progressAlertsEnabled,
            boolean announceWorldFirst,
            boolean announceWorldFirstSound,
            int serverVolume) {
        static SoundConfigSnapshot fromConfig() {
            return new SoundConfigSnapshot(
                    Config.MILESTONE_SOUND_ENABLED.get(),
                    Config.MILESTONE_SOUND.get(),
                    Config.worldFirstSound(),
                    Config.presetCompleteSound(),
                    Config.progressAlertSound(),
                    Config.PROGRESS_ALERTS.get(),
                    Config.ANNOUNCE_WORLD_FIRST.get(),
                    Config.announceWorldFirstSound(),
                    Config.notificationSoundVolume());
        }
    }

    /**
     * F-150-47/48: builds the one precedence snapshot for a window and resolves every packet.
     * The actor's single sound plus (opted-in) world-first announcement sounds share this call.
     */
    static List<NotificationSoundLogic.SoundRequest> planSoundsForWindow(
            @Nullable MinecraftServer server,
            MilestoneData data,
            UUID actorId,
            boolean hasRecord,
            boolean hasWorldFirst,
            boolean hasPresetComplete,
            boolean hasProgressAlert,
            SoundConfigSnapshot cfg) {
        MilestoneData.PlayerData actorPd = data.playerOrNull(actorId);
        List<UUID> worldFirstRecipients = new ArrayList<>();
        Set<UUID> mutedBroadcast = new HashSet<>();
        Set<UUID> mutedSound = new HashSet<>();
        Map<UUID, Integer> personalVolumes = new HashMap<>();
        for (ServerPlayer recipient : server == null ? List.<ServerPlayer>of()
                : server.getPlayerList().getPlayers()) {
            UUID id = recipient.getUUID();
            MilestoneData.PlayerData pd = data.playerOrNull(id);
            if (pd != null) {
                if (pd.muteBroadcasts) {
                    mutedBroadcast.add(id);
                }
                if (pd.muteNotificationSounds) {
                    mutedSound.add(id);
                }
                personalVolumes.put(id, pd.soundVolume);
            }
            // Identical filter to sendWorldFirstChannel: everybody, including the actor,
            // except other players who muted broadcasts.
            if (id.equals(actorId) || pd == null || !pd.muteBroadcasts) {
                worldFirstRecipients.add(id);
            }
        }
        return NotificationSoundLogic.plan(new NotificationSoundLogic.SoundContext(
                actorId,
                cfg.milestoneSoundEnabled(),
                actorPd != null && actorPd.muteNotificationSounds,
                hasRecord,
                hasWorldFirst,
                hasPresetComplete,
                hasProgressAlert,
                cfg.progressAlertsEnabled(),
                actorPd != null && actorPd.muteProgressAlerts,
                cfg.announceWorldFirst(),
                cfg.announceWorldFirstSound(),
                worldFirstRecipients,
                mutedBroadcast,
                mutedSound,
                personalVolumes,
                cfg.milestoneSoundId(),
                cfg.worldFirstSoundId(),
                cfg.presetCompleteSoundId(),
                cfg.progressAlertSoundId(),
                cfg.serverVolume()));
    }

    /** Sends (or, under test, captures) every resolved sound request for one window. */
    static void emitSounds(@Nullable MinecraftServer server,
                           List<NotificationSoundLogic.SoundRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return;
        }
        Consumer<NotificationSoundLogic.SoundRequest> sink = soundSink;
        if (sink != null) {
            for (NotificationSoundLogic.SoundRequest request : requests) {
                sink.accept(request);
            }
            return;
        }
        if (server == null) {
            return;
        }
        for (NotificationSoundLogic.SoundRequest request : requests) {
            ServerPlayer recipient = server.getPlayerList().getPlayer(request.recipient());
            if (recipient != null) {
                sendSound(recipient, request.soundId(), request.volume());
            }
        }
    }
    private static final Set<String> WARNED_DEFAULT_TARGETS = ConcurrentHashMap.newKeySet();
    /** Immutable snapshot of the config list a parse was derived from and its result; published atomically. */
    private record DefaultTargetsCache(List<? extends String> source, Set<TargetKey> targets) {
    }

    private static volatile DefaultTargetsCache defaultTargetsCache = new DefaultTargetsCache(null, Set.of());
    /** Craft/smelt results awaiting verification next tick: player -> itemId -> max event count. Server thread only. */
    private static final Map<UUID, Map<String, Integer>> PENDING_CRAFT_CHECKS = new HashMap<>();
    /** RB-2: stored {@code lastSeen} captured before each login overwrote it. Server thread only. */
    private static final Map<UUID, Long> PRE_LOGIN_LAST_SEEN = new HashMap<>();

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
        /** N63: progress alerts are their own channel inside the same window (never sent to Discord). */
        final List<ProgressAlertNotification> progressAlerts = new ArrayList<>();
        final List<QueuedPresetCompletion> presetCompletions = new ArrayList<>();
        final List<QueuedPresetCompletion> discordPresetCompletions = new ArrayList<>();
        final List<MilestoneService.NextPresetAddition> nextPresetAdditions = new ArrayList<>();

        /** Shared N63/N125 window this batch belongs to; one index per player queue, not a map key. */
        final long windowIndex;

        PendingNotifications(UUID playerId, String playerName, Component actorDisplayName) {
            this(playerId, playerName, actorDisplayName, 0L);
        }

        PendingNotifications(UUID playerId, String playerName, Component actorDisplayName, long windowIndex) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.actorDisplayName = actorDisplayName;
            this.windowIndex = windowIndex;
        }
    }

    /** Queued human-facing notifications to flush on ServerTickEvent.Post (insertion order). Server thread only. */
    private static final Map<UUID, PendingNotifications> PENDING_NOTIFICATIONS = new LinkedHashMap<>();
    /** Tick last observed at Post; enqueues during the following tick inherit its window. Server thread only. */
    private static volatile long lastObservedServerTick = 0L;

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
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
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
        MilestoneData data = MilestoneData.get(player.level().getServer());
        MilestoneData.PlayerData existingPd = data.playerOrNull(player.getUUID());
        long previousLastSeen = existingPd != null ? existingPd.lastSeen : 0L;
        String previousName = existingPd != null ? existingPd.lastKnownName : null;
        boolean hasMod = player.connection != null
                && ModNetworking.isRemotePresent(player);
        long nowMillis = System.currentTimeMillis();
        List<Component> messages = processPlayerLogin(
                data,
                player.getUUID(),
                McCompat.player().profileName(player.getGameProfile()),
                defaultTargets(),
                Config.FIRST_JOIN_PRESETS.get(),
                Config.PRESETS_ENABLED.get(),
                MilestoneService::isKnownNonAirItem,
                Config.SHOW_FIRST_JOIN_HINT.get(),
                hasMod,
                player,
                nowMillis,
                Playtime.currentTicks(player),
                MilestoneService::isModLoaded);
        for (Component msg : messages) {
            player.sendSystemMessage(msg);
        }
        sendLoginWorldFirstSummary(player, data, existingPd, previousLastSeen, nowMillis);
        ScoreboardSync.removeStaleNameScores(player.level().getServer(), player.getUUID(), previousName);
        ScoreboardSync.refreshScores(player.level().getServer(), player.getUUID());
        triggerExistingRecords(player, data, Config.WORLD_FIRST_ENABLED.get());
        if (hasMod) {
            // F42: send the player's current state on login so the client can build the item-tooltip
            // index without waiting for the GUI to be opened (openScreen=false, no feedback).
            MilestoneService.syncPlayerIfOpen(player);
        }
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
            McCompat.triggers().trigger(player, entry.key(), entry.worldFirst(), worldFirstEnabled);
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
        return processPlayerLogin(data, playerId, playerName, defaultTargets, firstJoinPresets,
                presetsEnabled, isKnownNonAirItem, showFirstJoinHint, hasModChannel, player,
                nowMillis, 0, id -> true);
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
            long nowMillis,
            int playTicks,
            Predicate<String> modLoaded) {
        // R2-MN-2(a): the production capture lives in the login path itself (the event handler
        // routes through this overload), before any first-join preset or lastSeen overwrite.
        MilestoneData.PlayerData beforeLogin = data.playerOrNull(playerId);
        rememberPreLoginLastSeen(playerId, beforeLogin != null ? beforeLogin.lastSeen : 0L);
        int firstJoinAdded = beforeLogin == null
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
        List<MilestoneService.NextPresetAddition> unlocked = MilestoneService.applyUnlockAfterHours(
                data, playerId, playTicks, isKnownNonAirItem, presetsEnabled, modLoaded);
        messages.addAll(unlockMessages(player, unlocked));
        messages.addAll(MilestoneService.notifyPresetVersionUpdates(data, playerId, player, presetsEnabled));
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

    /**
     * F52: sends the world-first summary for records created while the player was away.
     * The caller must pass the stored {@code lastSeen} value captured before this login
     * overwrote it, plus that login's timestamp.
     */
    private static void sendLoginWorldFirstSummary(
            ServerPlayer player,
            MilestoneData data,
            @Nullable MilestoneData.PlayerData previousData,
            long previousLastSeen,
            long nowMillis) {
        if (!Config.loginWorldFirstSummary() || previousLastSeen <= 0L) {
            return;
        }
        if (previousData != null && previousData.muteBroadcasts) {
            return;
        }
        LoginWorldFirstSummaryLogic.Selection selection = LoginWorldFirstSummaryLogic.select(
                data.worldFirsts(), player.getUUID(), previousLastSeen, nowMillis);
        if (selection.isEmpty()) {
            return;
        }
        ZoneId zone = Config.displayTimeZone();
        player.sendSystemMessage(ServerLang.tr(player,
                "message.itemmilestonelog.login_world_first_header", selection.totalCount()));
        for (WorldFirstRecord worldFirst : selection.entries()) {
            // F47: prefer the holder's current (last known) name; stored data is unchanged.
            MilestoneData.PlayerData holder = data.playerOrNull(worldFirst.playerUuid());
            WorldFirstNames.Choice choice = WorldFirstNames.choose(worldFirst.playerUuid(), worldFirst.playerName(),
                    holder != null, holder != null ? holder.lastKnownName : null);
            Component holderName = choice.renamed()
                    ? ServerLang.tr(player, "message.itemmilestonelog.firsts_name_renamed",
                            Component.literal(choice.name()), Component.literal(choice.recordedName()))
                    : Component.literal(choice.name());
            player.sendSystemMessage(ServerLang.tr(player, "message.itemmilestonelog.login_world_first_entry",
                    holderName,
                    MilestoneService.targetDisplayName(worldFirst.targetKey()),
                    Playtime.formatRecordComponent(player, worldFirst.toMilestoneRecord(), zone)));
        }
        if (selection.remainingCount() > 0) {
            player.sendSystemMessage(ServerLang.tr(player,
                    "message.itemmilestonelog.login_world_first_more", selection.remainingCount()));
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof FakePlayer) {
            return;
        }
        UUID playerId = event.getEntity().getUUID();
        if (event.getEntity() instanceof ServerPlayer player && player.level().getServer() != null) {
            processPlayerLogout(MilestoneData.get(player.level().getServer()), playerId, System.currentTimeMillis());
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
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        // Forge fires player ticks on both sides and in both phases; the inventory scan is
        // server-side only and must run once per tick.
        if (event.phase != TickEvent.Phase.END || event.side != LogicalSide.SERVER) {
            return;
        }
        if (!(event.player instanceof ServerPlayer player)) {
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
        // F-140-14: the lightweight unlockAfterHours check rides the existing scan cycle.
        List<MilestoneService.NextPresetAddition> unlocked = MilestoneService.applyUnlockAfterHours(
                data, player.getUUID(), Playtime.currentTicks(player));
        if (!unlocked.isEmpty()) {
            for (Component message : unlockMessages(player, unlocked)) {
                player.sendSystemMessage(message);
            }
            MilestoneService.syncPlayerIfOpen(player);
        }
        Set<TargetKey> pending = new HashSet<>(effectiveTargets(pd));
        if (pd != null) {
            pending.removeAll(pd.records.keySet());
        }
        Set<String> carried = carriedFromCreative(pd);
        if (pending.isEmpty() && carried.isEmpty()) {
            return;
        }

        // Only sum counts for item IDs that actually appear in this player's pending targets
        // (tag targets are expanded to their member items).
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
                submitProgressAlerts(player, data.playerOrNull(player.getUUID()), alerts);
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
        // #113: walk the player's ~46 stacks and consult the tag reverse index for each one instead
        // of expanding every pending tag into all of its member IDs on every scan tick. The counts
        // map, the tag totals, and the creative-carry bookkeeping stay identical to the expansion
        // path (a tag member that is present counts once; a carried item counts toward neither).
        Set<String> concreteItemIds = new HashSet<>();
        for (String id : pendingItemIdsToCount(pending, carried)) {
            if (!TargetKey.isTagId(id)) {
                concreteItemIds.add(id);
            }
        }
        Set<String> tagIds = tagIdsOf(pending);
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Long> tagTotals = new HashMap<>();
        for (StackCount stack : stacks) {
            String id = stack.itemId();
            if (carried.contains(id)) {
                stillCarried.add(id);
                continue;
            }
            boolean counted = concreteItemIds.contains(id);
            if (counted) {
                counts.merge(id, stack.count(), Integer::sum);
            }
            if (!tagIds.isEmpty()) {
                for (String tagId : tagIndex.tagsContaining(id)) {
                    if (tagIds.contains(tagId)) {
                        tagTotals.merge(tagId, (long) stack.count(), Long::sum);
                        if (!counted) {
                            counts.merge(id, stack.count(), Integer::sum);
                            counted = true;
                        }
                    }
                }
            }
        }
        for (String tagId : tagIds) {
            long total = tagTotals.getOrDefault(tagId, 0L);
            if (total > 0) {
                counts.put(tagId, (int) Math.min(Integer.MAX_VALUE, total));
            } else {
                counts.remove(tagId);
            }
        }
        return counts;
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

    /** RB-2: remembers the pre-login {@code lastSeen} for {@code recap return}. */
    static void rememberPreLoginLastSeen(UUID playerId, long value) {
        PRE_LOGIN_LAST_SEEN.put(playerId, value);
    }

    /** RB-2: the captured pre-login {@code lastSeen}, or 0 when this JVM saw no login for the player. */
    static long preLoginLastSeen(UUID playerId) {
        return PRE_LOGIN_LAST_SEEN.getOrDefault(playerId, 0L);
    }

    @SubscribeEvent
    public static void onItemPickup(PlayerEvent.ItemPickupEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (player instanceof FakePlayer) {
            return;
        }
        // 1.20.1 Forge passes a copy of exactly the picked-up portion in getStack().
        ItemStack pickedUp = event.getStack();
        if (pickedUp.isEmpty() || pickedUp.getCount() <= 0) {
            return;
        }
        tryRecordForItem(player, itemId(pickedUp), pickedUp.getCount(), true);
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
     * for only the requested {@code itemIds}. Tag IDs ({@code '#'}-prefixed, F8b) are expanded to their
     * member items and summed; the player's creative-carried items are not counted toward a tag (item
     * IDs are counted as-is; callers apply the per-item creative-carry rule).
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
        // #113: same reverse-index strategy as countPendingPossession. Non-tag IDs are counted
        // as-is (including a carried one, matching expandForCounting); tag members are skipped
        // while carried and are also kept in the concrete counts map like the expansion path.
        Set<String> concrete = new HashSet<>();
        Set<String> tagIds = new LinkedHashSet<>();
        for (String id : requestedIds) {
            if (TargetKey.isTagId(id)) {
                tagIds.add(id);
            } else if (id != null && !id.isEmpty()) {
                concrete.add(id);
            }
        }
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Long> tagTotals = new HashMap<>();
        for (StackCount stack : stacks) {
            String id = stack.itemId();
            boolean counted = concrete.contains(id);
            if (counted) {
                counts.merge(id, stack.count(), Integer::sum);
            }
            if (!tagIds.isEmpty() && !carried.contains(id)) {
                for (String tagId : tagIndex.tagsContaining(id)) {
                    if (tagIds.contains(tagId)) {
                        tagTotals.merge(tagId, (long) stack.count(), Long::sum);
                        if (!counted) {
                            counts.merge(id, stack.count(), Integer::sum);
                            counted = true;
                        }
                    }
                }
            }
        }
        for (String tagId : tagIds) {
            long total = tagTotals.getOrDefault(tagId, 0L);
            if (total > 0) {
                counts.put(tagId, (int) Math.min(Integer.MAX_VALUE, total));
            } else {
                counts.remove(tagId);
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
                submitProgressAlerts(player, data.playerOrNull(player.getUUID()), alerts);
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
                submitProgressAlerts(player, data.playerOrNull(player.getUUID()), alerts);
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

    /**
     * F-150-44: the one resolution call per send. A data-less player (or FOLLOW_SERVER) inherits
     * the server default; reading the saved choice never creates or dirties a PlayerData.
     */
    static NotificationDestinationLogic.Channel progressChannelFor(@Nullable MilestoneData.PlayerData pd) {
        return NotificationDestinationLogic.resolve(Config.progressAlertDestination(),
                NotificationDestinationLogic.parseChoice(pd != null ? pd.progressAlertDestination : null));
    }

    /** Production sender: vanilla chat for CHAT, the vanilla overlay packet for ACTION_BAR. */
    private static void sendProgressAlerts(ServerPlayer player, @Nullable MilestoneData.PlayerData pd,
                                           NotificationGrouping.ChannelPlan<ProgressAlertNotification> plan) {
        if (plan.mode() == NotificationGrouping.SendMode.NONE) {
            return;
        }
        emitProgressAlerts(player, pd, plan,
                component -> player.sendSystemMessage(component),
                component -> player.sendSystemMessage(component, true));
    }

    /** Resolves once, then renders into two sinks (unit-test seam for the routing decision). */
    static void emitProgressAlerts(@Nullable ServerPlayer player, @Nullable MilestoneData.PlayerData pd,
                                   NotificationGrouping.ChannelPlan<ProgressAlertNotification> plan,
                                   Consumer<Component> chatSink, Consumer<Component> actionBarSink) {
        if (plan.mode() == NotificationGrouping.SendMode.NONE || chatSink == null || actionBarSink == null) {
            return;
        }
        renderProgressAlerts(player, progressChannelFor(pd), plan, chatSink, actionBarSink);
    }

    /** Rendering only; the channel was already resolved by {@link #progressChannelFor}. */
    static void renderProgressAlerts(@Nullable ServerPlayer player,
                                     NotificationDestinationLogic.Channel channel,
                                     NotificationGrouping.ChannelPlan<ProgressAlertNotification> plan,
                                     Consumer<Component> chatSink, Consumer<Component> actionBarSink) {
        if (plan.mode() == NotificationGrouping.SendMode.NONE) {
            return;
        }
        boolean chat = NotificationDestinationLogic.sendsChat(channel);
        boolean overlay = NotificationDestinationLogic.sendsActionBar(channel);
        if (!chat && !overlay) {
            return;
        }
        if (plan.mode() == NotificationGrouping.SendMode.INDIVIDUAL) {
            for (ProgressAlertNotification n : plan.items()) {
                Component displayName = MilestoneService.targetDisplayName(n.key());
                String percent = n.percent() + "%";
                if (chat) {
                    chatSink.accept(ServerLang.tr(player, "message.itemmilestonelog.progress_alert",
                            displayName, n.currentCount(), n.targetCount(), n.percent()));
                }
                if (overlay) {
                    actionBarSink.accept(ServerLang.tr(player, "message.itemmilestonelog.progress_alert_actionbar",
                            displayName, n.currentCount(), n.targetCount(), percent));
                }
            }
            return;
        }
        List<ProgressAlertNotification> alerts = plan.items();
        List<TargetKey> keys = alerts.stream()
                .map(ProgressAlertNotification::key)
                .sorted()
                .toList();
        String language = player != null ? player.getLanguage() : null;
        Component targetList = buildTargetListComponent(language, keys, MilestoneService::targetDisplayName);
        if (chat) {
            chatSink.accept(ServerLang.tr(player, "message.itemmilestonelog.progress_alert_group",
                    alerts.size(), targetList));
        }
        if (overlay) {
            actionBarSink.accept(ServerLang.tr(player, "message.itemmilestonelog.progress_alert_group_actionbar",
                    alerts.size()));
        }
    }

    /**
     * N63/D-150-107: queued alerts resolve their destination at flush time; the immediate path stays
     * one-by-one but goes through the same resolution and vanilla delivery sinks.
     */
    private static void submitProgressAlerts(ServerPlayer player, @Nullable MilestoneData.PlayerData pd,
                                             List<ProgressAlertNotification> alerts) {
        if (alerts == null || alerts.isEmpty()) {
            return;
        }
        if (Config.GROUP_SIMULTANEOUS_RECORDS.get()) {
            queueProgressAlerts(player.getUUID(), player.getDisplayName(), alerts);
        } else {
            sendProgressAlerts(player, pd,
                    new NotificationGrouping.ChannelPlan<>(NotificationGrouping.SendMode.INDIVIDUAL, alerts));
        }
    }

    static void queueProgressAlerts(UUID playerId, @Nullable Component actorDisplayName,
                                    List<ProgressAlertNotification> alerts) {
        queueProgressAlerts(playerId, actorDisplayName, alerts, currentWindowIndex());
    }

    /** Explicit-window overload for deterministic queue tests; production callers use the observed tick. */
    static void queueProgressAlerts(UUID playerId, @Nullable Component actorDisplayName,
                                    List<ProgressAlertNotification> alerts, long windowIndex) {
        if (alerts == null || alerts.isEmpty()) {
            return;
        }
        String safeName = actorDisplayName != null ? actorDisplayName.getString() : "";
        PendingNotifications pending = PENDING_NOTIFICATIONS.computeIfAbsent(playerId,
                id -> new PendingNotifications(id, safeName, actorDisplayName, windowIndex));
        if (actorDisplayName != null) {
            pending.actorDisplayName = actorDisplayName;
        }
        pending.progressAlerts.addAll(alerts);
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
        return evaluatePresetCompletions(data, playerId, justRecordedKey, allPresets, itemExists,
                presetsEnabled, id -> true).completed();
    }

    /**
     * F-140-13: evaluates F5 completions and, for every preset newly added to
     * {@code completedPresets}, auto-adds its {@code nextPresets} through the normal apply path.
     * Only presets that newly entered the completed set can fire, so cycles stop and nothing is
     * applied retroactively.
     */
    static MilestoneService.PresetEvaluation evaluatePresetCompletions(
            MilestoneData data,
            UUID playerId,
            TargetKey justRecordedKey,
            Collection<Presets.PresetDefinition> allPresets,
            Predicate<String> itemExists,
            boolean presetsEnabled,
            Predicate<String> modLoaded) {
        if (!presetsEnabled) {
            return MilestoneService.PresetEvaluation.empty();
        }
        MilestoneData.PlayerData pd = data.playerOrNull(playerId);
        if (pd == null || pd.records.isEmpty()) {
            return MilestoneService.PresetEvaluation.empty();
        }
        List<Presets.PresetDefinition> newlyCompleted = Presets.findNewlyCompletedPresets(
                justRecordedKey,
                pd.records,
                allPresets,
                itemExists,
                pd.completedPresets,
                true);
        if (newlyCompleted.isEmpty()) {
            return MilestoneService.PresetEvaluation.empty();
        }
        List<Presets.PresetDefinition> newlyAdded = new ArrayList<>();
        for (Presets.PresetDefinition preset : newlyCompleted) {
            if (pd.completedPresets.add(preset.id())) {
                newlyAdded.add(preset);
            }
        }
        if (!newlyAdded.isEmpty()) {
            data.setDirty();
        }
        List<MilestoneService.NextPresetAddition> nextAdditions = new ArrayList<>();
        for (Presets.PresetDefinition preset : newlyAdded) {
            nextAdditions.addAll(MilestoneService.applyNextPresets(
                    data, playerId, preset, itemExists, modLoaded));
        }
        return new MilestoneService.PresetEvaluation(
                newlyCompleted,
                nextAdditions.isEmpty() ? List.of() : List.copyOf(nextAdditions));
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
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        long tick = event.getServer().getTickCount();
        lastObservedServerTick = tick;
        if (!PENDING_NOTIFICATIONS.isEmpty()) {
            int windowTicks = Config.notificationWindowTicks();
            boolean windowClosed = PENDING_NOTIFICATIONS.values().stream()
                    .anyMatch(pending -> NotificationGrouping.windowClosed(
                            pending.windowIndex, tick, windowTicks));
            if (windowClosed) {
                flushPendingNotifications(event.getServer(), drainPendingNotifications());
            }
        }
        MilestoneCommandRunner.drainAndRun(event.getServer());
    }

    /** Window used for queues filled before the next ServerTickEvent.Post updates the clock. */
    private static long currentWindowIndex() {
        return NotificationGrouping.windowIndex(lastObservedServerTick, Config.notificationWindowTicks());
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
                playerId, id -> new PendingNotifications(id, safeName, safeDisplayName, currentWindowIndex()));
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
        queuePendingPresetCompletions(playerId, playerName, actorDisplayName, completedPresets,
                List.of(), epochMillis, postToDiscord);
    }

    static void queuePendingPresetCompletions(
            UUID playerId,
            @Nullable String playerName,
            @Nullable Component actorDisplayName,
            List<Presets.PresetDefinition> completedPresets,
            List<MilestoneService.NextPresetAddition> nextAdditions,
            long epochMillis,
            boolean postToDiscord) {
        boolean hasCompletions = completedPresets != null && !completedPresets.isEmpty();
        boolean hasNextAdditions = nextAdditions != null && !nextAdditions.isEmpty();
        if (!hasCompletions && !hasNextAdditions) {
            return;
        }
        String safeName = playerName != null ? playerName : "";
        Component safeDisplayName = actorDisplayName != null ? actorDisplayName : Component.literal(safeName);
        PendingNotifications pending = PENDING_NOTIFICATIONS.computeIfAbsent(
                playerId, id -> new PendingNotifications(id, safeName, safeDisplayName, currentWindowIndex()));
        if (!safeName.isEmpty()) {
            pending.playerName = safeName;
        }
        if (actorDisplayName != null) {
            pending.actorDisplayName = actorDisplayName;
        }
        if (completedPresets != null) {
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
        if (nextAdditions != null) {
            pending.nextPresetAdditions.addAll(nextAdditions);
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
        lastObservedServerTick = 0L;
        // An integrated server can stop right after a craft: do not carry the queued check
        // into the next world's first tick.
        PENDING_CRAFT_CHECKS.clear();
    }

    /**
     * Sends every queued notification before {@code ServerStoppingEvent} completes and empties the
     * queue. This runs while players are still connected; Discord submissions are queued on the
     * notifier executor, which {@link DiscordNotifier#shutdown()} drains afterwards (#135).
     */
    public static void flushPendingOnStop(@Nullable MinecraftServer server) {
        List<PendingNotifications> batch = drainPendingNotifications();
        if (batch.isEmpty()) {
            return;
        }
        if (server == null) {
            return;
        }
        try {
            flushPendingNotifications(server, batch);
        } catch (RuntimeException e) {
            ItemMilestoneLog.LOGGER.warn(
                    "[ItemMilestoneLog] Could not flush queued notifications during server stop: {}",
                    e.getMessage());
        }
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
            String profileName = McCompat.player().profileName(online.getGameProfile());
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
                event -> MinecraftForge.EVENT_BUS.post(event));
        if (result.success()) {
            ScoreboardSync.refreshScores(server, targetId);
            if (online != null) {
                if (result.key() != null) {
                    McCompat.triggers().trigger(online, result.key(), false, Config.WORLD_FIRST_ENABLED.get());
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
                    MilestoneService.invalidTargetMessage(executorPlayer, itemId),
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

        MilestoneService.PresetEvaluation presetEvaluation = evaluatePresetCompletions(
                data,
                targetId,
                key,
                allPresets,
                isKnownNonAirItem,
                presetsEnabled,
                MilestoneService::isModLoaded);
        List<Presets.PresetDefinition> completedPresets = presetEvaluation.completed();

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
        sendNextPresetAdditions(targetMessageSender, targetPlayer,
                presetEvaluation.nextAdditions(), notifyPlayer);

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

        String playerName = McCompat.player().profileName(player.getGameProfile());
        String safePlayerName = playerName != null ? playerName : "";
        boolean worldFirstEnabled = Config.WORLD_FIRST_ENABLED.get();
        RecordOutcome outcome = applyRecord(data, player.getUUID(), playerName, key, rec,
                worldFirstEnabled);
        if (outcome == RecordOutcome.ALREADY_RECORDED) {
            return false;
        }
        ScoreboardSync.refreshScores(server, player.getUUID());
        boolean worldFirst = outcome == RecordOutcome.RECORDED_WORLD_FIRST;
        McCompat.triggers().trigger(player, key, worldFirst, worldFirstEnabled);
        MilestoneService.PresetEvaluation presetEvaluation = evaluatePresetCompletions(
                data,
                player.getUUID(),
                key,
                Presets.all(),
                MilestoneService::isKnownNonAirItem,
                Config.PRESETS_ENABLED.get(),
                MilestoneService::isModLoaded);
        List<Presets.PresetDefinition> completedPresets = presetEvaluation.completed();
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
                        presetEvaluation.nextAdditions(),
                        rec.epochMillis(),
                        postToDiscord);
            }
            postRecordEvents(player, key, rec, outcome, completedPresets, cause, event -> MinecraftForge.EVENT_BUS.post(event));
        } else {
            sendImmediateNotifications(server, data, player, key, ticks, worldFirst, !completedPresets.isEmpty(), rec, zone);
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
            sendNextPresetAdditions(msg -> player.sendSystemMessage(msg), player,
                    presetEvaluation.nextAdditions(), Config.NOTIFY_PLAYER.get());
            postRecordEvents(player, key, rec, outcome, completedPresets, cause, event -> MinecraftForge.EVENT_BUS.post(event));
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
        postRecordEvents(player,
                player != null ? player.getUUID() : null,
                player != null ? McCompat.player().profileName(player.getGameProfile()) : null,
                key, record, outcome, completedPresets, cause,
                MilestoneCommandRunner::enqueuePreset,
                MilestoneCommandRunner::enqueueWorldFirst,
                eventPoster);
    }

    /** Test seam: production passes the config-gated {@link MilestoneCommandRunner} methods. */
    @FunctionalInterface
    interface PresetCommandHook {
        void enqueue(UUID playerId, @Nullable String playerName, String presetId, boolean builtin);
    }

    @FunctionalInterface
    interface WorldFirstCommandHook {
        void enqueue(UUID playerId, @Nullable String playerName, TargetKey key, int playTicks);
    }

    /**
     * Posts the record/completion events and, on the same loop, queues one command per event for
     * the F-150-45 (N13) and F-150-46 (N109) hooks. Each hook has its own template and queue, so
     * F7's milestoneCommand path is unaffected. A null player (unit tests) never queues hooks.
     */
    static void postRecordEvents(
            ServerPlayer player,
            @Nullable UUID playerId,
            @Nullable String playerName,
            TargetKey key,
            MilestoneRecord record,
            RecordOutcome outcome,
            List<Presets.PresetDefinition> completedPresets,
            RecordCause cause,
            PresetCommandHook presetHook,
            WorldFirstCommandHook worldFirstHook,
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
        // N109: only the single first-writer-wins record queues a world-first hook command.
        if (playerId != null && outcome == RecordOutcome.RECORDED_WORLD_FIRST) {
            worldFirstHook.enqueue(playerId, playerName, key, record.playTicks());
        }
        if (completedPresets != null) {
            for (Presets.PresetDefinition preset : completedPresets) {
                eventPoster.accept(new PresetCompletedEvent(player, preset.id(), preset.builtin()));
                // N13: exactly one hook command immediately after that preset's completion event.
                if (playerId != null && Presets.isValidId(preset.id())) {
                    presetHook.enqueue(playerId, playerName, preset.id(), preset.builtin());
                }
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

    /** F-140-13: sends the personal "next preset auto-added" feedback for one completion. */
    static void sendNextPresetAdditions(
            Consumer<Component> sender,
            @Nullable ServerPlayer player,
            List<MilestoneService.NextPresetAddition> additions,
            boolean notifyPlayer) {
        if (!notifyPlayer || sender == null || additions == null || additions.isEmpty()) {
            return;
        }
        for (MilestoneService.NextPresetAddition addition : additions) {
            if (addition == null || addition.added() <= 0) {
                continue;
            }
            Component presetName = MilestoneService.presetDisplayName(player, addition.preset());
            sender.accept(ServerLang.tr(
                    player,
                    "message.itemmilestonelog.preset_next_added",
                    presetName,
                    addition.added()));
        }
    }

    /** F-140-14: renders the personal "playtime unlocked" feedback for auto-applied presets. */
    static List<Component> unlockMessages(@Nullable ServerPlayer player,
                                          List<MilestoneService.NextPresetAddition> additions) {
        if (additions == null || additions.isEmpty()) {
            return List.of();
        }
        List<Component> messages = new ArrayList<>(additions.size());
        for (MilestoneService.NextPresetAddition addition : additions) {
            if (addition == null || addition.added() <= 0) {
                continue;
            }
            Component presetName = MilestoneService.presetDisplayName(player, addition.preset());
            messages.add(ServerLang.tr(
                    player,
                    "message.itemmilestonelog.preset_unlock_added",
                    presetName,
                    addition.added()));
        }
        return messages;
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
            boolean presetComplete,
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
        // N15/N16/N114: one resolved plan covers record, world-first, preset-completion and
        // progress-alert sounds; the default plan keeps the single legacy record sound.
        emitSounds(server, planSoundsForWindow(
                server, data, player.getUUID(), true, worldFirst, presetComplete, false,
                SoundConfigSnapshot.fromConfig()));
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
        DiscordNotifier.ResolvedDestinations discordDestinations = DiscordNotifier.ResolvedDestinations.NONE;
        if (postToDiscord && !pending.discordRecords.isEmpty()) {
            boolean anyNormal = false;
            boolean anyWorldFirst = false;
            for (QueuedRecord q : pending.discordRecords) {
                if (q.worldFirst()) {
                    anyWorldFirst = true;
                } else {
                    anyNormal = true;
                }
            }
            discordDestinations = DiscordNotifier.resolveDestinationsForBatch(
                    anyNormal, anyWorldFirst, discordScope);
        }
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
                discordScope,
                discordDestinations.main().resolved(),
                discordDestinations.worldFirst().resolved());

        ServerPlayer onlineActor = server.getPlayerList().getPlayer(pending.playerId);
        Component actorDisplayName = onlineActor != null
                ? onlineActor.getDisplayName()
                : (pending.actorDisplayName != null ? pending.actorDisplayName : Component.literal(pending.playerName));

        // 1. Player chat notification (skip if offline)
        if (onlineActor != null) {
            sendPlayerChannel(onlineActor, plan.playerChat());
        }

        // 1b. N63 progress alerts: a separate channel in the same window, chat only, inserted
        // immediately after the player's record chat so the F27 ordering below stays unchanged.
        if (onlineActor != null) {
            sendProgressAlerts(onlineActor, actorPd,
                    NotificationGrouping.planProgressChannel(pending.progressAlerts));
        }

        // 2. Broadcast to other online players
        sendBroadcastChannel(server, data, pending.playerId, actorDisplayName, plan.broadcastChat());

        // 3. World-first announcement to online players
        sendWorldFirstChannel(server, data, pending.playerId, actorDisplayName, plan.worldFirstChat(), zone);

        // Sound: one per player per window, plus the opt-in world-first announcement sound.
        // The resolved requests are emitted here (never inside the world-first chat loop) so a
        // player who does not receive the announcement chat never receives its sound alone.
        emitSounds(server, planSoundsForWindow(
                server, data, pending.playerId,
                !pending.records.isEmpty(),
                hasWorldFirstQueued(pending),
                !pending.presetCompletions.isEmpty(),
                !pending.progressAlerts.isEmpty(),
                SoundConfigSnapshot.fromConfig()));

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
        // F-140-13: next-preset auto-add feedback travels with the completion notification.
        if (!pending.nextPresetAdditions.isEmpty() && onlineActor != null) {
            sendNextPresetAdditions(
                    msg -> onlineActor.sendSystemMessage(msg),
                    onlineActor,
                    pending.nextPresetAdditions,
                    notifyPlayer);
        }

        // 5. Discord record notifications (still sent even if player went offline)
        if (postToDiscord) {
            sendDiscordChannel(actorPd, pending.playerName, plan.discord(), discordDestinations.main());
            sendDiscordChannel(actorPd, pending.playerName, plan.discordWorldFirst(), discordDestinations.worldFirst());
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
            NotificationGrouping.ChannelPlan<QueuedRecord> channel,
            DiscordNotifier.DestinationResolution destination) {
        if (channel.mode() == NotificationGrouping.SendMode.NONE
                || destination == null || destination.deliverableUri() == null) {
            return;
        }
        if (channel.mode() == NotificationGrouping.SendMode.INDIVIDUAL) {
            for (QueuedRecord q : channel.items()) {
                DiscordNotifier.notifyMilestoneToDestination(
                        playerData, playerName, q.key(), q.record(), q.worldFirst(), destination);
            }
            return;
        }
        List<DiscordNotifier.MilestoneEntry> entries = new ArrayList<>(channel.items().size());
        for (QueuedRecord q : channel.items()) {
            entries.add(new DiscordNotifier.MilestoneEntry(q.key(), q.record(), q.worldFirst()));
        }
        DiscordNotifier.notifyMilestonesGroupedToDestination(playerData, playerName, entries, destination);
    }

    private static MutableComponent buildTargetListComponent(
            @Nullable ServerPlayer recipient, List<QueuedRecord> sortedRecords) {
        String lang = recipient != null ? recipient.getLanguage() : null;
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

    /** F-150-49: test seam for the volume embedded in the vanilla sound packet. */
    static final float SOUND_PITCH = 1.0F;

    static float soundPacketVolume(int volume) {
        return SoundVolumeLogic.packetVolume(volume);
    }

    private static void sendSound(ServerPlayer player, String soundId, int volume) {
        ResourceLocation id = soundId != null ? ResourceLocation.tryParse(soundId) : null;
        if (id == null || !BuiltInRegistries.SOUND_EVENT.containsKey(id)) {
            if (!Objects.equals(lastWarnedSoundId, soundId)) {
                lastWarnedSoundId = soundId;
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Unknown notification sound '{}'; skipping sound", soundId);
            }
            return;
        }
        SoundEvent event = McCompat.registries().soundOrNull(id);
        if (event != null) {
            McCompat.player().playNotifySound(player, event, SoundSource.PLAYERS,
                    soundPacketVolume(volume), SOUND_PITCH);
        }
    }
}
