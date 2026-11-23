package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.network.PresetEntry;
import com.pengin0503.itemmilestonelog.network.SyncMilestonesPayload;
import com.pengin0503.itemmilestonelog.network.TargetEntry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    public record ActionResult(boolean success, Component message) {
    }

    private MilestoneService() {
    }

    public static boolean isValidItemId(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return false;
        }
        Identifier id = Identifier.tryParse(itemId);
        return id != null && BuiltInRegistries.ITEM.containsKey(id);
    }

    public static Component itemName(String itemId) {
        Identifier id = Identifier.tryParse(itemId);
        if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
            Item item = BuiltInRegistries.ITEM.getValue(id);
            return item.getName(new ItemStack(item));
        }
        return Component.literal(itemId == null ? "" : itemId);
    }

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

    public static boolean isPrivileged(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        boolean dedicated = server == null || server.isDedicatedServer();
        ServerPlayer player = source.getPlayer();
        boolean isPlayer = player != null;
        boolean singleplayerOwner = isPlayer && server != null && server.isSingleplayerOwner(player.nameAndId());
        boolean hasAdmin = source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        return PrivilegePolicy.isPrivileged(dedicated, isPlayer, singleplayerOwner, hasAdmin);
    }

    public static boolean isPrivileged(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        boolean dedicated = server == null || server.isDedicatedServer();
        boolean singleplayerOwner = server != null && server.isSingleplayerOwner(player.nameAndId());
        boolean hasAdmin = player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        return PrivilegePolicy.isPrivileged(dedicated, true, singleplayerOwner, hasAdmin);
    }

    public static boolean canManagePresets(CommandSourceStack source) {
        return isPrivileged(source);
    }

    public static boolean canManagePresets(ServerPlayer player) {
        return isPrivileged(player);
    }

    private static boolean isKnownNonAirItem(String normalizedId) {
        Identifier id = Identifier.tryParse(normalizedId);
        return id != null && BuiltInRegistries.ITEM.containsKey(id) && BuiltInRegistries.ITEM.getValue(id) != Items.AIR;
    }

    public static ActionResult addTarget(ServerPlayer player, String itemId) {
        return addTarget(player, itemId, TargetKey.MIN_COUNT);
    }

    public static ActionResult addTarget(ServerPlayer player, String itemId, int count) {
        return addTarget(MilestoneData.get(player.level().getServer()), player.getUUID(), player, itemId, count,
                MilestoneService::isKnownNonAirItem, MilestoneService::targetDisplayName);
    }

    static ActionResult addTarget(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                                  String itemId, int count,
                                  Predicate<String> isKnownNonAirItem,
                                  Function<TargetKey, Component> displayName) {
        if (!TargetKey.isValidCount(count)) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.invalid_count",
                    TargetKey.MIN_COUNT, TargetKey.MAX_COUNT));
        }
        String normalizedId = TargetKey.normalizeItemId(itemId);
        if (normalizedId == null || !isKnownNonAirItem.test(normalizedId)) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.invalid_item", String.valueOf(itemId)));
        }
        TargetKey key = new TargetKey(normalizedId, count);
        MilestoneData.PlayerData pd = data.player(playerId);
        if (pd.targets.contains(key)) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.already_target", displayName.apply(key)));
        }
        if (pd.targets.size() >= MAX_PERSONAL_TARGETS) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.targets_limit_reached", MAX_PERSONAL_TARGETS));
        }
        pd.targets.add(key);
        data.setDirty();
        return new ActionResult(true, ServerLang.tr(player, "message.itemmilestonelog.added", displayName.apply(key)));
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

    public static ActionResult removeTarget(ServerPlayer player, String itemId) {
        return removeTarget(player, itemId, TargetKey.MIN_COUNT);
    }

    public static ActionResult removeTarget(ServerPlayer player, String itemId, int count) {
        return removeTarget(MilestoneData.get(player.level().getServer()), player.getUUID(), player, itemId, count,
                MilestoneService::isValidItemId, MilestoneService::targetDisplayName);
    }

    static ActionResult removeTarget(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                                     String itemId, int count,
                                     Predicate<String> isKnownItem,
                                     Function<TargetKey, Component> displayName) {
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
            if (pd.targets.remove(key) || (rawKey != null && pd.targets.remove(rawKey))) {
                pd.progressNotified.remove(key);
                if (rawKey != null) {
                    pd.progressNotified.remove(rawKey);
                }
                data.setDirty();
                return new ActionResult(true, ServerLang.tr(player, "message.itemmilestonelog.removed", displayName.apply(key)));
            }
        }
        if (!isKnownItem.test(normalizedId)) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.invalid_item", itemId));
        }
        return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.not_target", displayName.apply(key)));
    }

    public static ActionResult applyPreset(ServerPlayer player, String name) {
        return applyPreset(MilestoneData.get(player.level().getServer()), player.getUUID(), player, name,
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
        Component result;
        if (addedWithCount > 0) {
            result = ServerLang.tr(player, "message.itemmilestonelog.preset_applied_with_counts",
                    name, added, addedWithCount, skippedMissing);
        } else {
            result = ServerLang.tr(player, "message.itemmilestonelog.preset_applied", name, added, skippedMissing);
        }
        if (skippedLimit > 0) {
            result = result.copy().append(Component.literal("\n"))
                    .append(ServerLang.tr(player, "message.itemmilestonelog.preset_applied_limit", skippedLimit));
        }
        return new ActionResult(true, result);
    }

    public static ActionResult savePreset(ServerPlayer player, String rawName) {
        return savePreset(MilestoneData.get(player.level().getServer()), player.getUUID(), player,
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
        return resetRecord(MilestoneData.get(player.level().getServer()), player.getUUID(), player, itemId, count,
                MilestoneService::isValidItemId, MilestoneService::targetDisplayName);
    }

    static ActionResult resetRecord(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                                    String itemId, int count,
                                    Predicate<String> isKnownItem,
                                    Function<TargetKey, Component> displayName) {
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
            if (pd.records.remove(key) != null || (rawKey != null && pd.records.remove(rawKey) != null)) {
                pd.progressNotified.remove(key);
                if (rawKey != null) {
                    pd.progressNotified.remove(rawKey);
                }
                data.setDirty();
                return new ActionResult(true, ServerLang.tr(player, "message.itemmilestonelog.reset_done", displayName.apply(key)));
            }
        }
        if (!isKnownItem.test(normalizedId)) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.invalid_item", itemId));
        }
        return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.not_recorded", displayName.apply(key)));
    }

    public static ActionResult resetAllRecords(ServerPlayer player) {
        return resetAllRecords(MilestoneData.get(player.level().getServer()), player.getUUID(), player);
    }

    static ActionResult resetAllRecords(MilestoneData data, UUID playerId, @Nullable ServerPlayer player) {
        MilestoneData.PlayerData pd = data.playerOrNull(playerId);
        int count = pd != null ? pd.records.size() : 0;
        boolean changed = false;
        if (pd != null) {
            if (count > 0) {
                pd.records.clear();
                changed = true;
            }
            if (!pd.progressNotified.isEmpty()) {
                pd.progressNotified.clear();
                changed = true;
            }
        }
        if (changed) {
            data.setDirty();
        }
        return new ActionResult(true, ServerLang.tr(player, "message.itemmilestonelog.resetall_done", count));
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

    public static SyncMilestonesPayload createSyncPayload(ServerPlayer player, boolean openScreen, @Nullable Component feedback) {
        MilestoneData data = MilestoneData.get(player.level().getServer());
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
            targetEntries.add(new TargetEntry(key.itemId(), key.count(), personalSet.contains(key), defaultSet.contains(key)));
        }

        Map<TargetKey, MilestoneRecord> records = new LinkedHashMap<>();
        if (pd != null) {
            records.putAll(pd.records);
        }

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
