package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.network.PresetEntry;
import com.pengin0503.itemmilestonelog.network.SyncMilestonesPayload;
import com.pengin0503.itemmilestonelog.network.TargetEntry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Shared server-side milestone operations and validation used by both
 * {@link MilestoneCommands} and network handlers for the GUI.
 */
public final class MilestoneService {
    public static final int MAX_PERSONAL_TARGETS = 1024;

    /**
     * @param undoable whether the operation stored an F23 undo snapshot (chat commands then append
     *                 {@code undo_hint}; GUI feedback ignores it)
     */
    public record ActionResult(boolean success, Component message, boolean undoable) {
        public ActionResult(boolean success, Component message) {
            this(success, message, false);
        }
    }

    /**
     * F23: per-player undo snapshots for the player's own resets (and F28 bulk removals).
     * In memory only; cleared on server stop. Admin operations on other players are not captured.
     */
    public static final UndoLogic.Store<TargetKey, MilestoneRecord> UNDO_SNAPSHOTS = new UndoLogic.Store<>();

    private MilestoneService() {
    }

    public static boolean isValidItemId(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return false;
        }
        ResourceLocation rl = ResourceLocation.tryParse(itemId);
        return rl != null && BuiltInRegistries.ITEM.containsKey(rl);
    }

    public static Component itemName(String itemId) {
        ResourceLocation rl = ResourceLocation.tryParse(itemId);
        if (rl != null && BuiltInRegistries.ITEM.containsKey(rl)) {
            Item item = BuiltInRegistries.ITEM.get(rl);
            return item.getDescription();
        }
        return Component.literal(itemId == null ? "" : itemId);
    }

    /** Formats a target display name, appending {@code " ×<count>"} when count > 1. */
    public static Component targetDisplayName(TargetKey key) {
        Component base = itemName(key.itemId());
        if (key.count() > TargetKey.MIN_COUNT) {
            return base.copy().append(Component.literal(key.formatSuffix()));
        }
        return base;
    }

    public static Component targetDisplayName(String itemId, int count) {
        Component base = itemName(itemId);
        if (count > TargetKey.MIN_COUNT) {
            return base.copy().append(Component.literal(" ×" + count));
        }
        return base;
    }

    /**
     * Resolves a preset's display name for {@code recipient} (F5):
     * built-in presets translate {@code gui.itemmilestonelog.preset.<id>} per recipient,
     * while custom presets embed their JSON {@code name} (or ID fallback) as a literal
     * component (§1 constraint 12).
     */
    public static Component presetDisplayName(@Nullable ServerPlayer recipient, Presets.PresetDefinition preset) {
        if (preset.builtin()) {
            return ServerLang.tr(recipient, "gui.itemmilestonelog.preset." + preset.id());
        }
        return customPresetLiteralName(preset);
    }

    public static Component presetDisplayNameForLang(@Nullable String language, Presets.PresetDefinition preset) {
        if (preset.builtin()) {
            return ServerLang.trForLang(language, "gui.itemmilestonelog.preset." + preset.id());
        }
        return customPresetLiteralName(preset);
    }

    private static Component customPresetLiteralName(Presets.PresetDefinition preset) {
        String display = (preset.displayName() != null && !preset.displayName().isEmpty())
                ? preset.displayName()
                : preset.id();
        return Component.literal(display != null ? display : "");
    }

    public static boolean isPrivileged(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        boolean dedicated = server == null || server.isDedicatedServer();
        ServerPlayer player = source.getPlayer();
        boolean isPlayer = player != null;
        boolean singleplayerOwner = isPlayer && server != null && server.isSingleplayerOwner(player.getGameProfile());
        boolean hasAdmin = source.hasPermission(PrivilegePolicy.ADMIN_PERMISSION_LEVEL);
        return PrivilegePolicy.isPrivileged(dedicated, isPlayer, singleplayerOwner, hasAdmin);
    }

    public static boolean isPrivileged(ServerPlayer player) {
        MinecraftServer server = player.server;
        boolean dedicated = server == null || server.isDedicatedServer();
        boolean singleplayerOwner = server != null && server.isSingleplayerOwner(player.getGameProfile());
        boolean hasAdmin = player.hasPermissions(PrivilegePolicy.ADMIN_PERMISSION_LEVEL);
        return PrivilegePolicy.isPrivileged(dedicated, true, singleplayerOwner, hasAdmin);
    }

    public static boolean canManagePresets(CommandSourceStack source) {
        return isPrivileged(source);
    }

    public static boolean canManagePresets(ServerPlayer player) {
        return isPrivileged(player);
    }

    static boolean isKnownNonAirItem(String normalizedId) {
        ResourceLocation rl = ResourceLocation.tryParse(normalizedId);
        return rl != null && BuiltInRegistries.ITEM.containsKey(rl) && BuiltInRegistries.ITEM.get(rl) != Items.AIR;
    }

    private enum AddTargetStatus {
        INVALID_COUNT,
        INVALID_ITEM,
        ALREADY_TARGET,
        LIMIT_REACHED,
        ADDED
    }

    private record AddTargetOutcome(AddTargetStatus status, @Nullable TargetKey key) {
    }

    private static AddTargetOutcome performAddTarget(
            MilestoneData data,
            UUID playerId,
            String itemId,
            int count,
            Predicate<String> isKnownNonAirItem) {
        if (!TargetKey.isValidCount(count)) {
            return new AddTargetOutcome(AddTargetStatus.INVALID_COUNT, null);
        }
        String normalizedId = TargetKey.normalizeItemId(itemId);
        if (normalizedId == null || !isKnownNonAirItem.test(normalizedId)) {
            return new AddTargetOutcome(AddTargetStatus.INVALID_ITEM, null);
        }
        TargetKey key = new TargetKey(normalizedId, count);
        MilestoneData.PlayerData pd = data.player(playerId);
        if (pd.targets.contains(key)) {
            return new AddTargetOutcome(AddTargetStatus.ALREADY_TARGET, key);
        }
        if (pd.targets.size() >= MAX_PERSONAL_TARGETS) {
            return new AddTargetOutcome(AddTargetStatus.LIMIT_REACHED, key);
        }
        pd.targets.add(key);
        data.setDirty();
        return new AddTargetOutcome(AddTargetStatus.ADDED, key);
    }

    public static ActionResult addTarget(ServerPlayer player, String itemId) {
        return addTarget(player, itemId, TargetKey.MIN_COUNT);
    }

    public static ActionResult addTarget(ServerPlayer player, String itemId, int count) {
        return addTarget(MilestoneData.get(player.server), player.getUUID(), player, itemId, count,
                MilestoneService::isKnownNonAirItem, MilestoneService::targetDisplayName);
    }

    static ActionResult addTarget(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                                  String itemId, int count,
                                  Predicate<String> isKnownNonAirItem,
                                  Function<TargetKey, Component> displayName) {
        AddTargetOutcome outcome = performAddTarget(data, playerId, itemId, count, isKnownNonAirItem);
        return switch (outcome.status()) {
            case INVALID_COUNT -> new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.invalid_count",
                    TargetKey.MIN_COUNT, TargetKey.MAX_COUNT));
            case INVALID_ITEM -> new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.invalid_item",
                    String.valueOf(itemId)));
            case ALREADY_TARGET -> new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.already_target",
                    displayName.apply(outcome.key())));
            case LIMIT_REACHED -> new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.targets_limit_reached",
                    MAX_PERSONAL_TARGETS));
            case ADDED -> new ActionResult(true, ServerLang.tr(player, "message.itemmilestonelog.added",
                    displayName.apply(outcome.key())));
        };
    }

    /**
     * F33 {@code /milestonelog admin <player> add <item> [<count>]}: adds a personal target for
     * {@code targetId} (online or offline) using the same validation and cap as {@link #addTarget},
     * re-syncs the target's GUI if online, and is not captured for F23 undo.
     */
    public static ActionResult adminAddTarget(MinecraftServer server, CommandSourceStack source,
                                              UUID targetId, String targetName,
                                              String itemId, int count) {
        MilestoneData data = MilestoneData.get(server);
        ActionResult result = adminAddTarget(data, targetId, targetName, source.getPlayer(), itemId, count,
                MilestoneService::isKnownNonAirItem, MilestoneService::targetDisplayName);
        if (result.success()) {
            ServerPlayer online = server.getPlayerList().getPlayer(targetId);
            if (online != null) {
                ensureLastKnownName(data, targetId, online.getGameProfile().getName());
                syncPlayerIfOpen(online);
            }
        }
        return result;
    }

    static ActionResult adminAddTarget(MilestoneData data, UUID targetId, String targetName,
                                       @Nullable ServerPlayer executor,
                                       String itemId, int count,
                                       Predicate<String> isKnownNonAirItem,
                                       Function<TargetKey, Component> displayName) {
        AddTargetOutcome outcome = performAddTarget(data, targetId, itemId, count, isKnownNonAirItem);
        Component literalName = Component.literal(targetName);
        return switch (outcome.status()) {
            case INVALID_COUNT -> new ActionResult(false, ServerLang.tr(executor, "message.itemmilestonelog.invalid_count",
                    TargetKey.MIN_COUNT, TargetKey.MAX_COUNT));
            case INVALID_ITEM -> new ActionResult(false, ServerLang.tr(executor, "message.itemmilestonelog.invalid_item",
                    String.valueOf(itemId)));
            case ALREADY_TARGET -> new ActionResult(false, ServerLang.tr(executor, "message.itemmilestonelog.admin_already_target",
                    literalName, displayName.apply(outcome.key())));
            case LIMIT_REACHED -> new ActionResult(false, ServerLang.tr(executor, "message.itemmilestonelog.targets_limit_reached",
                    MAX_PERSONAL_TARGETS));
            case ADDED -> new ActionResult(true, ServerLang.tr(executor, "message.itemmilestonelog.admin_added",
                    literalName, displayName.apply(outcome.key())));
        };
    }

    private static void ensureLastKnownName(MilestoneData data, UUID playerId, @Nullable String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return;
        }
        MilestoneData.PlayerData pd = data.playerOrNull(playerId);
        if (pd != null && (pd.lastKnownName == null || pd.lastKnownName.isEmpty())) {
            pd.lastKnownName = profileName;
            data.setDirty();
        }
    }

    public static ActionResult addHandTarget(ServerPlayer player) {
        return addHandTarget(player, TargetKey.MIN_COUNT);
    }

    public static ActionResult addHandTarget(ServerPlayer player, int count) {
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.addhand_empty"));
        }
        String id = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        return addTarget(player, id, count);
    }

    private enum RemoveTargetStatus {
        INVALID_COUNT,
        INVALID_ITEM,
        NOT_TARGET,
        REMOVED
    }

    private record RemoveTargetOutcome(RemoveTargetStatus status, @Nullable TargetKey key) {
    }

    private static RemoveTargetOutcome performRemoveTarget(
            MilestoneData data,
            UUID playerId,
            String itemId,
            int count,
            Predicate<String> isKnownItem) {
        if (!TargetKey.isValidCount(count)) {
            return new RemoveTargetOutcome(RemoveTargetStatus.INVALID_COUNT, null);
        }
        if (!TargetKey.isValidItemId(itemId)) {
            return new RemoveTargetOutcome(RemoveTargetStatus.INVALID_ITEM, null);
        }
        String normalized = TargetKey.normalizeItemId(itemId);
        String normalizedId = normalized != null ? normalized : itemId;
        TargetKey key = new TargetKey(normalizedId, count);
        MilestoneData.PlayerData pd = data.playerOrNull(playerId);
        if (pd != null) {
            TargetKey rawKey = !normalizedId.equals(itemId) ? new TargetKey(itemId, count) : null;
            if (pd.targets.remove(key) || (rawKey != null && pd.targets.remove(rawKey))) {
                pd.progressNotified.remove(key);
                if (rawKey != null) {
                    pd.progressNotified.remove(rawKey);
                }
                data.setDirty();
                return new RemoveTargetOutcome(RemoveTargetStatus.REMOVED, key);
            }
        }
        if (!isKnownItem.test(normalizedId)) {
            return new RemoveTargetOutcome(RemoveTargetStatus.INVALID_ITEM, key);
        }
        return new RemoveTargetOutcome(RemoveTargetStatus.NOT_TARGET, key);
    }

    public static ActionResult removeTarget(ServerPlayer player, String itemId) {
        return removeTarget(player, itemId, TargetKey.MIN_COUNT);
    }

    public static ActionResult removeTarget(ServerPlayer player, String itemId, int count) {
        return removeTarget(MilestoneData.get(player.server), player.getUUID(), player, itemId, count,
                MilestoneService::isValidItemId, MilestoneService::targetDisplayName);
    }

    static ActionResult removeTarget(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                                     String itemId, int count,
                                     Predicate<String> isKnownItem,
                                     Function<TargetKey, Component> displayName) {
        RemoveTargetOutcome outcome = performRemoveTarget(data, playerId, itemId, count, isKnownItem);
        return switch (outcome.status()) {
            case INVALID_COUNT -> new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.invalid_count",
                    TargetKey.MIN_COUNT, TargetKey.MAX_COUNT));
            case INVALID_ITEM -> new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.invalid_item",
                    String.valueOf(itemId)));
            case NOT_TARGET -> new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.not_target",
                    displayName.apply(outcome.key())));
            case REMOVED -> new ActionResult(true, ServerLang.tr(player, "message.itemmilestonelog.removed",
                    displayName.apply(outcome.key())));
        };
    }

    /**
     * F33 {@code /milestonelog admin <player> remove <item> [<count>]}: removes a personal target for
     * {@code targetId} (online or offline) with the same validation and §1.1 {@code progressNotified}
     * cleanup as {@link #removeTarget}, re-syncs the target's GUI if online, and is not an F23 undo target.
     */
    public static ActionResult adminRemoveTarget(MinecraftServer server, CommandSourceStack source,
                                                 UUID targetId, String targetName,
                                                 String itemId, int count) {
        ActionResult result = adminRemoveTarget(MilestoneData.get(server), targetId, targetName, source.getPlayer(),
                itemId, count, MilestoneService::isValidItemId, MilestoneService::targetDisplayName);
        if (result.success()) {
            syncOnlinePlayerIfOpen(server, targetId);
        }
        return result;
    }

    static ActionResult adminRemoveTarget(MilestoneData data, UUID targetId, String targetName,
                                          @Nullable ServerPlayer executor,
                                          String itemId, int count,
                                          Predicate<String> isKnownItem,
                                          Function<TargetKey, Component> displayName) {
        RemoveTargetOutcome outcome = performRemoveTarget(data, targetId, itemId, count, isKnownItem);
        Component literalName = Component.literal(targetName);
        return switch (outcome.status()) {
            case INVALID_COUNT -> new ActionResult(false, ServerLang.tr(executor, "message.itemmilestonelog.invalid_count",
                    TargetKey.MIN_COUNT, TargetKey.MAX_COUNT));
            case INVALID_ITEM -> new ActionResult(false, ServerLang.tr(executor, "message.itemmilestonelog.invalid_item",
                    String.valueOf(itemId)));
            case NOT_TARGET -> new ActionResult(false, ServerLang.tr(executor, "message.itemmilestonelog.admin_not_target",
                    literalName, displayName.apply(outcome.key())));
            case REMOVED -> new ActionResult(true, ServerLang.tr(executor, "message.itemmilestonelog.admin_removed",
                    literalName, displayName.apply(outcome.key())));
        };
    }

    /** Display names are not shown for API calls; avoid registry lookups for the unused feedback message. */
    private static final Function<TargetKey, Component> API_DISPLAY_NAME = key -> Component.literal(key.formatDisplayId());

    /**
     * F32 {@code ItemMilestoneLogApi#addTarget}: adds {@code target} to the player's personal targets
     * with the same validation and limit as the {@code add} command, creating the player's data if
     * missing, and re-syncs the player's GUI if they are online. Callers check the server thread.
     */
    public static boolean addTargetFromApi(MinecraftServer server, UUID playerId, @Nullable TargetKey target) {
        boolean added = addTargetFromApi(MilestoneData.get(server), playerId, target, MilestoneService::isKnownNonAirItem);
        if (added) {
            syncOnlinePlayerIfOpen(server, playerId);
        }
        return added;
    }

    static boolean addTargetFromApi(MilestoneData data, UUID playerId, @Nullable TargetKey target,
                                    Predicate<String> isKnownNonAirItem) {
        if (target == null) {
            return false;
        }
        return addTarget(data, playerId, null, target.itemId(), target.count(), isKnownNonAirItem, API_DISPLAY_NAME)
                .success();
    }

    /**
     * F32 {@code ItemMilestoneLogApi#removeTarget}: removes {@code target} from the player's personal
     * targets with the same cleanup as the {@code remove} command (§1.1: drops the key's
     * {@code progressNotified} entry). Keys in {@code defaultTargets} cannot be removed. Re-syncs the
     * player's GUI if they are online. Callers check the server thread.
     */
    public static boolean removeTargetFromApi(MinecraftServer server, UUID playerId, @Nullable TargetKey target) {
        boolean removed = removeTargetFromApi(MilestoneData.get(server), playerId, target, Tracker.defaultTargets());
        if (removed) {
            syncOnlinePlayerIfOpen(server, playerId);
        }
        return removed;
    }

    static boolean removeTargetFromApi(MilestoneData data, UUID playerId, @Nullable TargetKey target,
                                       Set<TargetKey> defaultTargets) {
        if (target == null) {
            return false;
        }
        TargetKey normalized = TargetKey.normalize(target.itemId(), target.count());
        if (defaultTargets.contains(target) || (normalized != null && defaultTargets.contains(normalized))) {
            return false;
        }
        return removeTarget(data, playerId, null, target.itemId(), target.count(), id -> true, API_DISPLAY_NAME)
                .success();
    }

    /** Re-syncs the GUI of {@code playerId} if that player is online and has the GUI channel. */
    public static void syncOnlinePlayerIfOpen(MinecraftServer server, UUID playerId) {
        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        if (online != null) {
            syncPlayerIfOpen(online);
        }
    }

    private record PresetApplyCounts(int added, int addedWithCount, int skippedMissing, int skippedLimit) {
    }

    private static PresetApplyCounts applyPresetTargets(
            MilestoneData data,
            UUID playerId,
            Presets.PresetDefinition preset,
            Predicate<String> isKnownNonAirItem) {
        MilestoneData.PlayerData pd = data.player(playerId);
        int added = 0;
        int addedWithCount = 0;
        int skippedMissing = 0;
        int skippedLimit = 0;
        for (TargetKey entry : preset.items()) {
            TargetKey normalized = TargetKey.normalize(entry.itemId(), entry.count(), false, true);
            if (normalized == null || !isKnownNonAirItem.test(normalized.itemId())) {
                skippedMissing++;
                continue;
            }
            if (pd.targets.contains(normalized)) {
                continue;
            }
            if (pd.targets.size() >= MAX_PERSONAL_TARGETS) {
                skippedLimit++;
                continue;
            }
            if (pd.targets.add(normalized)) {
                added++;
                if (normalized.count() > TargetKey.MIN_COUNT) {
                    addedWithCount++;
                }
            }
        }
        if (added > 0) {
            data.setDirty();
        }
        return new PresetApplyCounts(added, addedWithCount, skippedMissing, skippedLimit);
    }

    public static ActionResult applyPreset(ServerPlayer player, String name) {
        return applyPreset(MilestoneData.get(player.server), player.getUUID(), player, name,
                MilestoneService::isKnownNonAirItem, Config.PRESETS_ENABLED.get());
    }

    static ActionResult applyPreset(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                                    String name, Predicate<String> isKnownNonAirItem) {
        return applyPreset(data, playerId, player, name, isKnownNonAirItem, true);
    }

    static ActionResult applyPreset(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                                    String name, Predicate<String> isKnownNonAirItem, boolean presetsEnabled) {
        if (!presetsEnabled) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.feature_disabled"));
        }
        Presets.PresetDefinition preset = Presets.get(name);
        if (preset == null) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.preset_unknown", name,
                    String.join(", ", Presets.names())));
        }
        PresetApplyCounts counts = applyPresetTargets(data, playerId, preset, isKnownNonAirItem);
        Component result;
        if (counts.addedWithCount() > 0) {
            result = ServerLang.tr(player, "message.itemmilestonelog.preset_applied_with_counts",
                    name, counts.added(), counts.addedWithCount(), counts.skippedMissing());
        } else {
            result = ServerLang.tr(player, "message.itemmilestonelog.preset_applied",
                    name, counts.added(), counts.skippedMissing());
        }
        if (counts.skippedLimit() > 0) {
            result = result.copy().append(Component.literal("\n"))
                    .append(ServerLang.tr(player, "message.itemmilestonelog.preset_applied_limit", counts.skippedLimit()));
        }
        return new ActionResult(true, result);
    }

    /**
     * F33 {@code /milestonelog admin <player> preset <name>}: applies a preset to {@code targetId}
     * (online or offline) with the same validation and cap as {@link #applyPreset}, re-syncs the
     * target's GUI if online, and is not captured for F23 undo.
     */
    public static ActionResult adminApplyPreset(MinecraftServer server, CommandSourceStack source,
                                                UUID targetId, String targetName, String name) {
        MilestoneData data = MilestoneData.get(server);
        ActionResult result = adminApplyPreset(data, targetId, targetName, source.getPlayer(), name,
                MilestoneService::isKnownNonAirItem, Config.PRESETS_ENABLED.get());
        if (result.success()) {
            ServerPlayer online = server.getPlayerList().getPlayer(targetId);
            if (online != null) {
                ensureLastKnownName(data, targetId, online.getGameProfile().getName());
                syncPlayerIfOpen(online);
            }
        }
        return result;
    }

    static ActionResult adminApplyPreset(MilestoneData data, UUID targetId, String targetName,
                                         @Nullable ServerPlayer executor, String name,
                                         Predicate<String> isKnownNonAirItem, boolean presetsEnabled) {
        if (!presetsEnabled) {
            return new ActionResult(false, ServerLang.tr(executor, "message.itemmilestonelog.feature_disabled"));
        }
        Presets.PresetDefinition preset = Presets.get(name);
        if (preset == null) {
            return new ActionResult(false, ServerLang.tr(executor, "message.itemmilestonelog.preset_unknown", name,
                    String.join(", ", Presets.names())));
        }
        PresetApplyCounts counts = applyPresetTargets(data, targetId, preset, isKnownNonAirItem);
        Component result = ServerLang.tr(executor, "message.itemmilestonelog.admin_preset_applied",
                preset.id(), Component.literal(targetName), counts.added(), counts.skippedMissing());
        if (counts.skippedLimit() > 0) {
            result = result.copy().append(Component.literal("\n"))
                    .append(ServerLang.tr(executor, "message.itemmilestonelog.preset_applied_limit", counts.skippedLimit()));
        }
        return new ActionResult(true, result);
    }

    /**
     * F40: applies {@link Config#FIRST_JOIN_PRESETS} as personal targets if {@code playerId} has
     * no {@link MilestoneData.PlayerData} yet, using the same target-addition logic as
     * {@link #applyPreset}. Unknown preset IDs are skipped silently (warned once at server startup
     * via {@link #warnUnknownFirstJoinPresets()}). Not an F23 undo target.
     *
     * @return total number of personal targets added across all listed presets
     */
    public static int applyFirstJoinPresets(MilestoneData data, UUID playerId) {
        return applyFirstJoinPresets(
                data,
                playerId,
                Config.FIRST_JOIN_PRESETS.get(),
                MilestoneService::isKnownNonAirItem,
                Config.PRESETS_ENABLED.get());
    }

    static int applyFirstJoinPresets(
            MilestoneData data,
            UUID playerId,
            @Nullable List<? extends String> presetIds,
            Predicate<String> isKnownNonAirItem,
            boolean presetsEnabled) {
        if (!presetsEnabled || presetIds == null || presetIds.isEmpty()) {
            return 0;
        }
        if (data.playerOrNull(playerId) != null) {
            return 0;
        }
        int totalAdded = 0;
        for (String id : presetIds) {
            if (id == null) {
                continue;
            }
            Presets.PresetDefinition preset = Presets.get(id);
            if (preset == null) {
                continue;
            }
            PresetApplyCounts counts = applyPresetTargets(data, playerId, preset, isKnownNonAirItem);
            totalAdded += counts.added();
        }
        return totalAdded;
    }

    /**
     * F40: logs a single warning at server startup if {@link Config#FIRST_JOIN_PRESETS} references
     * any preset ID that is not currently loaded. Does nothing when {@link Config#PRESETS_ENABLED}
     * is false or all configured IDs exist.
     */
    public static void warnUnknownFirstJoinPresets() {
        if (!Config.PRESETS_ENABLED.get()) {
            return;
        }
        List<String> unknown = findUnknownFirstJoinPresets(Config.FIRST_JOIN_PRESETS.get());
        if (!unknown.isEmpty()) {
            ItemMilestoneLog.LOGGER.warn(
                    "[ItemMilestoneLog] Unknown preset ID(s) in firstJoinPresets will be skipped: {}",
                    String.join(", ", unknown));
        }
    }

    static List<String> findUnknownFirstJoinPresets(@Nullable List<? extends String> presetIds) {
        return findUnknownFirstJoinPresets(presetIds, id -> Presets.get(id) != null);
    }

    static List<String> findUnknownFirstJoinPresets(
            @Nullable List<? extends String> presetIds,
            Predicate<String> presetExists) {
        if (presetIds == null || presetIds.isEmpty()) {
            return List.of();
        }
        Set<String> unknown = new LinkedHashSet<>();
        for (String id : presetIds) {
            if (id != null && !presetExists.test(id)) {
                unknown.add(id);
            }
        }
        return unknown.isEmpty() ? List.of() : List.copyOf(unknown);
    }

    /**
     * F28 {@code /milestonelog presetremove <name>}: removes all keys of the given preset from the
     * player's personal targets (after the same normalization as {@link #applyPreset}, including
     * keys whose item is not currently in the registry). Keeps records, default targets, and world
     * firsts, cleans {@code progressNotified} for removed keys (§1.1), and registers an F23 undo
     * snapshot when at least one target was removed.
     */
    public static ActionResult removePresetTargets(ServerPlayer player, String name) {
        ActionResult result = removePresetTargets(
                MilestoneData.get(player.server), player.getUUID(), player, name,
                Config.PRESETS_ENABLED.get(), UNDO_SNAPSHOTS, System.nanoTime());
        if (result.success() && result.undoable()) {
            syncPlayerIfOpen(player);
        }
        return result;
    }

    static ActionResult removePresetTargets(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                                            String name, boolean presetsEnabled,
                                            @Nullable UndoLogic.Store<TargetKey, MilestoneRecord> undoStore,
                                            long nowNanos) {
        if (!presetsEnabled) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.feature_disabled"));
        }
        Presets.PresetDefinition preset = Presets.get(name);
        if (preset == null) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.preset_unknown", name,
                    String.join(", ", Presets.names())));
        }
        return removePresetTargets(data, playerId, player, preset, undoStore, nowNanos);
    }

    static ActionResult removePresetTargets(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                                            Presets.PresetDefinition preset,
                                            @Nullable UndoLogic.Store<TargetKey, MilestoneRecord> undoStore,
                                            long nowNanos) {
        MilestoneData.PlayerData pd = data.playerOrNull(playerId);
        if (pd == null || pd.targets.isEmpty()) {
            return new ActionResult(true, ServerLang.tr(player, "message.itemmilestonelog.presetremove_done",
                    preset.id(), 0), false);
        }
        Set<TargetKey> presetKeys = new LinkedHashSet<>();
        for (TargetKey entry : preset.items()) {
            if (entry == null) {
                continue;
            }
            TargetKey normalized = TargetKey.normalize(entry.itemId(), entry.count(), false, true);
            if (normalized != null) {
                presetKeys.add(normalized);
            }
        }
        List<TargetKey> removedTargets = new ArrayList<>();
        Map<TargetKey, Integer> removedProgress = new LinkedHashMap<>();
        if (!presetKeys.isEmpty()) {
            Iterator<TargetKey> it = pd.targets.iterator();
            while (it.hasNext()) {
                TargetKey existing = it.next();
                TargetKey normExisting = TargetKey.normalize(existing.itemId(), existing.count(), false, true);
                if (presetKeys.contains(existing) || (normExisting != null && presetKeys.contains(normExisting))) {
                    it.remove();
                    removedTargets.add(existing);
                    Integer prev = pd.progressNotified.remove(existing);
                    if (prev != null) {
                        removedProgress.put(existing, prev);
                    }
                    if (normExisting != null && !normExisting.equals(existing)) {
                        Integer normPrev = pd.progressNotified.remove(normExisting);
                        if (normPrev != null) {
                            removedProgress.put(normExisting, normPrev);
                        }
                    }
                }
            }
        }
        boolean undoable = false;
        if (!removedTargets.isEmpty()) {
            data.setDirty();
            if (undoStore != null) {
                undoable = undoStore.offer(playerId,
                        UndoLogic.Snapshot.bulkRemoval(removedTargets, removedProgress, nowNanos));
            }
        }
        return new ActionResult(true, ServerLang.tr(player, "message.itemmilestonelog.presetremove_done",
                preset.id(), removedTargets.size()), undoable);
    }

    /**
     * F28 {@code /milestonelog removeall}: removes all of the player's personal targets while
     * keeping records, default targets, and world firsts, cleaning {@code progressNotified} for
     * removed keys (§1.1), and registering an F23 undo snapshot when at least one target was removed.
     */
    public static ActionResult removeAllTargets(ServerPlayer player) {
        ActionResult result = removeAllTargets(
                MilestoneData.get(player.server), player.getUUID(), player,
                UNDO_SNAPSHOTS, System.nanoTime());
        if (result.success() && result.undoable()) {
            syncPlayerIfOpen(player);
        }
        return result;
    }

    static ActionResult removeAllTargets(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                                         @Nullable UndoLogic.Store<TargetKey, MilestoneRecord> undoStore,
                                         long nowNanos) {
        MilestoneData.PlayerData pd = data.playerOrNull(playerId);
        if (pd == null || pd.targets.isEmpty()) {
            return new ActionResult(true, ServerLang.tr(player, "message.itemmilestonelog.removeall_done", 0), false);
        }
        List<TargetKey> removedTargets = new ArrayList<>(pd.targets);
        pd.targets.clear();
        Map<TargetKey, Integer> removedProgress = new LinkedHashMap<>();
        for (TargetKey removed : removedTargets) {
            Integer prev = pd.progressNotified.remove(removed);
            if (prev != null) {
                removedProgress.put(removed, prev);
            }
            TargetKey normRemoved = TargetKey.normalize(removed.itemId(), removed.count(), false, true);
            if (normRemoved != null && !normRemoved.equals(removed)) {
                Integer normPrev = pd.progressNotified.remove(normRemoved);
                if (normPrev != null) {
                    removedProgress.put(normRemoved, normPrev);
                }
            }
        }
        data.setDirty();
        boolean undoable = undoStore != null && undoStore.offer(playerId,
                UndoLogic.Snapshot.bulkRemoval(removedTargets, removedProgress, nowNanos));
        return new ActionResult(true, ServerLang.tr(player, "message.itemmilestonelog.removeall_done",
                removedTargets.size()), undoable);
    }

    public static ActionResult savePreset(ServerPlayer player, String rawName) {
        return savePreset(MilestoneData.get(player.server), player.getUUID(), player,
                isPrivileged(player), rawName, Presets::presetsDir, Config.PRESETS_ENABLED.get());
    }

    static ActionResult savePreset(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                                   boolean allowed, String rawName, Supplier<Path> presetsDir) {
        return savePreset(data, playerId, player, allowed, rawName, presetsDir, true);
    }

    static ActionResult savePreset(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                                   boolean allowed, String rawName, Supplier<Path> presetsDir, boolean presetsEnabled) {
        if (!presetsEnabled) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.feature_disabled"));
        }
        if (!allowed) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.preset_no_permission"));
        }
        String name = rawName != null ? rawName.trim() : "";
        if (!Presets.isValidId(name)) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.preset_invalid_name", name));
        }
        if (Presets.isBuiltin(name)) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.preset_builtin_protected", name));
        }
        MilestoneData.PlayerData pd = data.playerOrNull(playerId);
        if (pd == null || pd.targets.isEmpty()) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.preset_no_personal_targets"));
        }
        List<TargetKey> items = List.copyOf(pd.targets);
        try {
            boolean overwritten = Presets.saveCustomTo(presetsDir.get(), name, items);
            String key = overwritten ? "message.itemmilestonelog.preset_overwritten" : "message.itemmilestonelog.preset_saved";
            return new ActionResult(true, ServerLang.tr(player, key, name, items.size()));
        } catch (IOException | IllegalArgumentException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Failed to save preset {}", name, e);
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.preset_save_failed", name));
        }
    }

    public static ActionResult deletePreset(CommandSourceStack source, String name) {
        return deletePreset(source, isPrivileged(source), name, Presets::presetsDir, Config.PRESETS_ENABLED.get());
    }

    static ActionResult deletePreset(@Nullable CommandSourceStack source, boolean allowed,
                                     String name, Supplier<Path> presetsDir) {
        return deletePreset(source, allowed, name, presetsDir, true);
    }

    static ActionResult deletePreset(@Nullable CommandSourceStack source, boolean allowed,
                                     String name, Supplier<Path> presetsDir, boolean presetsEnabled) {
        if (!presetsEnabled) {
            return new ActionResult(false, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        if (!allowed) {
            return new ActionResult(false, ServerLang.tr(source, "message.itemmilestonelog.preset_no_permission"));
        }
        if (Presets.isBuiltin(name)) {
            return new ActionResult(false, ServerLang.tr(source, "message.itemmilestonelog.preset_builtin_protected", name));
        }
        if (!Presets.isValidId(name) || Presets.get(name) == null) {
            return new ActionResult(false, ServerLang.tr(source, "message.itemmilestonelog.preset_unknown",
                    String.valueOf(name), String.join(", ", Presets.names())));
        }
        try {
            if (Presets.deleteCustomFrom(presetsDir.get(), name)) {
                return new ActionResult(true, ServerLang.tr(source, "message.itemmilestonelog.preset_deleted", name));
            }
            return new ActionResult(false, ServerLang.tr(source, "message.itemmilestonelog.preset_unknown",
                    name, String.join(", ", Presets.names())));
        } catch (IOException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Failed to delete preset {}", name, e);
            return new ActionResult(false, ServerLang.tr(source, "message.itemmilestonelog.preset_delete_failed", name));
        }
    }

    public static ActionResult deletePreset(ServerPlayer player, String name) {
        return deletePreset(player.createCommandSourceStack(), name);
    }

    public static ActionResult resetRecord(ServerPlayer player, String itemId) {
        return resetRecord(player, itemId, TargetKey.MIN_COUNT);
    }

    public static ActionResult resetRecord(ServerPlayer player, String itemId, int count) {
        return resetRecord(MilestoneData.get(player.server), player.getUUID(), player, itemId, count,
                MilestoneService::isValidItemId, MilestoneService::targetDisplayName,
                UNDO_SNAPSHOTS, System.nanoTime());
    }

    static ActionResult resetRecord(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                                    String itemId, int count,
                                    Predicate<String> isKnownItem,
                                    Function<TargetKey, Component> displayName) {
        return resetRecord(data, playerId, player, itemId, count, isKnownItem, displayName, null, 0L);
    }

    /**
     * @param undoStore where to store the F23 snapshot of what was removed ({@code null}: not captured)
     * @param nowNanos  {@code System.nanoTime()} used as the snapshot's capture time
     */
    static ActionResult resetRecord(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                                    String itemId, int count,
                                    Predicate<String> isKnownItem,
                                    Function<TargetKey, Component> displayName,
                                    @Nullable UndoLogic.Store<TargetKey, MilestoneRecord> undoStore,
                                    long nowNanos) {
        if (!TargetKey.isValidCount(count)) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.invalid_count",
                    TargetKey.MIN_COUNT, TargetKey.MAX_COUNT));
        }
        if (!TargetKey.isValidItemId(itemId)) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.invalid_item", String.valueOf(itemId)));
        }
        String normalized = TargetKey.normalizeItemId(itemId);
        String normalizedId = normalized != null ? normalized : itemId;
        TargetKey key = new TargetKey(normalizedId, count);
        MilestoneData.PlayerData pd = data.playerOrNull(playerId);
        if (pd != null) {
            TargetKey rawKey = !normalizedId.equals(itemId) ? new TargetKey(itemId, count) : null;
            Map<TargetKey, MilestoneRecord> removedRecords = new LinkedHashMap<>();
            MilestoneRecord removed = pd.records.remove(key);
            if (removed != null) {
                removedRecords.put(key, removed);
            } else if (rawKey != null && (removed = pd.records.remove(rawKey)) != null) {
                removedRecords.put(rawKey, removed);
            }
            if (removed != null) {
                Map<TargetKey, Integer> removedProgress = new LinkedHashMap<>();
                Integer progress = pd.progressNotified.remove(key);
                if (progress != null) {
                    removedProgress.put(key, progress);
                }
                if (rawKey != null && (progress = pd.progressNotified.remove(rawKey)) != null) {
                    removedProgress.put(rawKey, progress);
                }
                data.setDirty();
                boolean undoable = undoStore != null && undoStore.offer(playerId,
                        UndoLogic.Snapshot.reset(removedRecords, Set.of(), removedProgress, nowNanos));
                return new ActionResult(true, ServerLang.tr(player, "message.itemmilestonelog.reset_done", displayName.apply(key)),
                        undoable);
            }
        }
        if (!isKnownItem.test(normalizedId)) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.invalid_item", itemId));
        }
        return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.not_recorded", displayName.apply(key)));
    }

    public static ActionResult resetAllRecords(ServerPlayer player) {
        return resetAllRecords(MilestoneData.get(player.server), player.getUUID(), player,
                UNDO_SNAPSHOTS, System.nanoTime());
    }

    static ActionResult resetAllRecords(MilestoneData data, UUID playerId, @Nullable ServerPlayer player) {
        return resetAllRecords(data, playerId, player, null, 0L);
    }

    /**
     * @param undoStore where to store the F23 snapshot of what was removed ({@code null}: not captured).
     *                  A 0-record resetall stores nothing and keeps the previous snapshot.
     * @param nowNanos  {@code System.nanoTime()} used as the snapshot's capture time
     */
    static ActionResult resetAllRecords(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                                        @Nullable UndoLogic.Store<TargetKey, MilestoneRecord> undoStore,
                                        long nowNanos) {
        MilestoneData.PlayerData pd = data.playerOrNull(playerId);
        int count = pd != null ? pd.records.size() : 0;
        boolean changed = false;
        boolean undoable = false;
        if (pd != null) {
            if (undoStore != null) {
                undoable = undoStore.offer(playerId, UndoLogic.Snapshot.reset(
                        pd.records, pd.completedPresets, pd.progressNotified, nowNanos));
            }
            if (count > 0) {
                pd.records.clear();
                changed = true;
            }
            if (!pd.progressNotified.isEmpty()) {
                pd.progressNotified.clear();
                changed = true;
            }
            if (!pd.completedPresets.isEmpty()) {
                pd.completedPresets.clear();
                changed = true;
            }
        }
        if (changed) {
            data.setDirty();
        }
        return new ActionResult(true, ServerLang.tr(player, "message.itemmilestonelog.resetall_done", count), undoable);
    }

    /**
     * F23 {@code /milestonelog undo}: restores the player's last reset (or F28 bulk removal) from
     * the past 10 minutes. Fires no events, notifications, Discord posts, or F5 judgments; only
     * re-syncs the player's GUI.
     */
    public static ActionResult undo(ServerPlayer player) {
        ActionResult result = undo(MilestoneData.get(player.server), player.getUUID(), player,
                UNDO_SNAPSHOTS, System.nanoTime());
        if (result.success()) {
            syncPlayerIfOpen(player);
        }
        return result;
    }

    static ActionResult undo(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                             UndoLogic.Store<TargetKey, MilestoneRecord> undoStore, long nowNanos) {
        UndoLogic.Snapshot<TargetKey, MilestoneRecord> snapshot = undoStore.peekValid(playerId, nowNanos);
        if (snapshot == null) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.undo_nothing"));
        }
        MilestoneData.PlayerData pd = data.player(playerId);
        UndoLogic.Result<TargetKey, MilestoneRecord> restored = UndoLogic.restore(snapshot,
                pd.records, pd.completedPresets, pd.progressNotified, pd.targets,
                MAX_PERSONAL_TARGETS, MilestoneRecord::playTicks);
        pd.records.clear();
        pd.records.putAll(restored.records());
        pd.completedPresets.clear();
        pd.completedPresets.addAll(restored.completedPresets());
        pd.progressNotified.clear();
        pd.progressNotified.putAll(restored.progressNotified());
        pd.targets.clear();
        pd.targets.addAll(restored.targets());
        data.setDirty();
        undoStore.consume(playerId, snapshot);

        if (snapshot.kind() == UndoLogic.Kind.BULK_REMOVAL) {
            Component message = ServerLang.tr(player, "message.itemmilestonelog.undo_done_targets", restored.restoredTargets());
            if (restored.skippedTargetsLimit() > 0) {
                message = message.copy().append(Component.literal("\n"))
                        .append(ServerLang.tr(player, "message.itemmilestonelog.preset_applied_limit",
                                restored.skippedTargetsLimit()));
            }
            return new ActionResult(true, message);
        }
        return new ActionResult(true, ServerLang.tr(player, "message.itemmilestonelog.undo_done", restored.restoredRecords()));
    }

    public static ActionResult toggleDiscordOptOut(ServerPlayer player) {
        return toggleDiscordOptOut(MilestoneData.get(player.server), player.getUUID(), player,
                Config.ALLOW_DISCORD_OPT_OUT.get());
    }

    static ActionResult toggleDiscordOptOut(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                                            boolean allowDiscordOptOut) {
        if (!allowDiscordOptOut) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.feature_disabled"));
        }
        MilestoneData.PlayerData pd = data.player(playerId);
        pd.excludeFromDiscord = !pd.excludeFromDiscord;
        data.setDirty();
        String key = pd.excludeFromDiscord
                ? "message.itemmilestonelog.discordoptout_on"
                : "message.itemmilestonelog.discordoptout_off";
        return new ActionResult(true, ServerLang.tr(player, key));
    }

    /**
     * F14: toggles {@code hideFromLookup} for the executing player. Only affects what other,
     * non-privileged players can see (lookup, top, recent, stats); no Config switch.
     */
    public static ActionResult togglePrivacy(ServerPlayer player) {
        return togglePrivacy(MilestoneData.get(player.server), player.getUUID(), player);
    }

    static ActionResult togglePrivacy(MilestoneData data, UUID playerId, @Nullable ServerPlayer player) {
        MilestoneData.PlayerData pd = data.player(playerId);
        pd.hideFromLookup = !pd.hideFromLookup;
        data.setDirty();
        String key = pd.hideFromLookup
                ? "message.itemmilestonelog.privacy_on"
                : "message.itemmilestonelog.privacy_off";
        return new ActionResult(true, ServerLang.tr(player, key));
    }

    /** Builds the pure-Java snapshot inspected by {@link PruneDataLogic#isPruneTarget} (F37). */
    static PruneDataLogic.PlayerState pruneStateOf(MilestoneData.PlayerData pd) {
        return new PruneDataLogic.PlayerState(
                pd.records.size(),
                pd.targets.size(),
                pd.carriedFromCreative.isEmpty(),
                pd.muteBroadcasts,
                pd.hideFromLookup,
                pd.muteProgressAlerts,
                pd.excludeFromDiscord
        );
    }

    /**
     * F37: counts inactive empty players eligible for {@code /milestonelog prunedata}, deleting their
     * {@link MilestoneData.PlayerData} when {@code confirm} is true. World-first records are kept.
     */
    public static int pruneInactivePlayers(MinecraftServer server, int days, boolean confirm) {
        MilestoneData data = MilestoneData.get(server);
        Path playerDataDir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        return pruneInactivePlayers(
                data,
                uuid -> server.getPlayerList().getPlayer(uuid) != null,
                playerDataDir,
                System.currentTimeMillis(),
                days,
                confirm);
    }

    static int pruneInactivePlayers(
            MilestoneData data,
            Predicate<UUID> isOnline,
            @Nullable Path playerDataDir,
            long nowMillis,
            int days,
            boolean confirm) {
        List<UUID> targets = new ArrayList<>();
        for (Map.Entry<UUID, MilestoneData.PlayerData> entry : data.allPlayers().entrySet()) {
            UUID uuid = entry.getKey();
            MilestoneData.PlayerData pd = entry.getValue();
            PruneDataLogic.PlayerState state = pruneStateOf(pd);
            boolean online = isOnline.test(uuid);
            long lastSeenMillis = (online || !PruneDataLogic.isEmptyWithDefaultSettings(state))
                    ? PruneDataLogic.UNKNOWN_LAST_SEEN
                    : PruneDataLogic.resolveLastSeen(pd.lastSeen, playerDataDir, uuid);
            if (PruneDataLogic.isPruneTarget(state, online, lastSeenMillis, nowMillis, days)) {
                targets.add(uuid);
            }
        }
        if (confirm) {
            for (UUID uuid : targets) {
                data.purgePlayer(uuid);
            }
        }
        return targets.size();
    }

    public static void syncPlayerIfOpen(ServerPlayer player) {
        if (player.connection != null && player.connection.hasChannel(SyncMilestonesPayload.TYPE)) {
            PacketDistributor.sendToPlayer(player, createSyncPayload(player, false, null));
        }
    }

    public static void syncAllOnlineGuiPlayers(MinecraftServer server, @Nullable ServerPlayer exclude) {
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other != exclude) {
                syncPlayerIfOpen(other);
            }
        }
    }

    /**
     * Builds a complete snapshot of the player's milestone state to send to the client GUI.
     */
    public static SyncMilestonesPayload createSyncPayload(ServerPlayer player, boolean openScreen, @Nullable Component feedback) {
        MilestoneData data = MilestoneData.get(player.server);
        MilestoneData.PlayerData pd = data.playerOrNull(player.getUUID());
        return buildSyncPayload(
                pd,
                Tracker.defaultTargets(),
                Tracker.carriedFromCreative(pd),
                itemIds -> Tracker.countItemsInPossession(player, itemIds),
                Playtime.currentTicks(player),
                isPrivileged(player),
                openScreen,
                feedback,
                Config.PRESETS_ENABLED.get()
        );
    }

    static SyncMilestonesPayload buildSyncPayload(
            @Nullable MilestoneData.PlayerData pd,
            Set<TargetKey> defaultSet,
            Set<String> carried,
            Function<Set<String>, Map<String, Integer>> possessionCounter,
            int currentPlaytime,
            boolean canManagePresets,
            boolean openScreen,
            @Nullable Component feedback) {
        return buildSyncPayload(pd, defaultSet, carried, possessionCounter, currentPlaytime,
                canManagePresets, openScreen, feedback, true);
    }

    static SyncMilestonesPayload buildSyncPayload(
            @Nullable MilestoneData.PlayerData pd,
            Set<TargetKey> defaultSet,
            Set<String> carried,
            Function<Set<String>, Map<String, Integer>> possessionCounter,
            int currentPlaytime,
            boolean canManagePresets,
            boolean openScreen,
            @Nullable Component feedback,
            boolean presetsEnabled) {
        Set<TargetKey> personalSet = pd != null ? pd.targets : Set.of();

        Set<TargetKey> effective = Tracker.effectiveTargets(defaultSet, pd);
        List<TargetKey> sortedTargets = new ArrayList<>(effective);
        sortedTargets.sort(TargetKey::compareTo);

        List<TargetEntry> targetEntries = new ArrayList<>(sortedTargets.size());
        for (TargetKey key : sortedTargets) {
            targetEntries.add(new TargetEntry(
                    key.itemId(), key.count(), personalSet.contains(key), defaultSet.contains(key)));
        }

        Map<TargetKey, MilestoneRecord> records = new LinkedHashMap<>();
        if (pd != null) {
            records.putAll(pd.records);
        }

        // Q9: compute current possession count for unachieved targets with count > 1 at sync time.
        Set<String> progressItemIds = new java.util.HashSet<>();
        for (TargetKey key : sortedTargets) {
            if (key.count() > TargetKey.MIN_COUNT && !records.containsKey(key) && !carried.contains(key.itemId())) {
                progressItemIds.add(key.itemId());
            }
        }
        Map<TargetKey, Integer> progress = new LinkedHashMap<>();
        Map<String, Integer> counts = progressItemIds.isEmpty()
                ? Map.of()
                : possessionCounter.apply(progressItemIds);
        for (TargetKey key : sortedTargets) {
            if (key.count() > TargetKey.MIN_COUNT && !records.containsKey(key)) {
                int current = carried.contains(key.itemId()) ? 0 : counts.getOrDefault(key.itemId(), 0);
                progress.put(key, current);
            }
        }

        List<PresetEntry> presetEntries = new ArrayList<>();
        if (presetsEnabled) {
            for (Presets.PresetDefinition p : Presets.all()) {
                presetEntries.add(new PresetEntry(
                        p.id(),
                        p.builtin() ? "" : p.displayName(),
                        p.items().size(),
                        p.builtin()
                ));
            }
        }

        return new SyncMilestonesPayload(
                targetEntries,
                records,
                progress,
                currentPlaytime,
                presetEntries,
                presetsEnabled && canManagePresets,
                openScreen,
                feedback
        );
    }
}
