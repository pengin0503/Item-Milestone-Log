package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared server-side milestone operations and validation used by both
 * {@link MilestoneCommands} and network handlers for the GUI.
 */
public final class MilestoneService {
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

    public static boolean canManagePresets(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        if (server == null || !server.isDedicatedServer()) {
            return true;
        }
        return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    public static boolean canManagePresets(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null || !server.isDedicatedServer()) {
            return true;
        }
        return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    public static ActionResult addTarget(ServerPlayer player, String itemId) {
        if (!isValidItemId(itemId)) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.invalid_item", String.valueOf(itemId)));
        }
        Identifier id = Identifier.parse(itemId);
        if (BuiltInRegistries.ITEM.getValue(id) == Items.AIR) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.invalid_item", itemId));
        }
        MilestoneData data = MilestoneData.get(player.level().getServer());
        MilestoneData.PlayerData pd = data.player(player.getUUID());
        if (!pd.targets.add(itemId)) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.already_target", itemName(itemId)));
        }
        data.setDirty();
        return new ActionResult(true, ServerLang.tr(player, "message.itemmilestonelog.added", itemName(itemId)));
    }

    public static ActionResult addHandTarget(ServerPlayer player) {
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.addhand_empty"));
        }
        String id = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        return addTarget(player, id);
    }

    public static ActionResult removeTarget(ServerPlayer player, String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.invalid_item", String.valueOf(itemId)));
        }
        MilestoneData data = MilestoneData.get(player.level().getServer());
        MilestoneData.PlayerData pd = data.playerOrNull(player.getUUID());
        if (pd != null && pd.targets.remove(itemId)) {
            data.setDirty();
            return new ActionResult(true, ServerLang.tr(player, "message.itemmilestonelog.removed", itemName(itemId)));
        }
        if (!isValidItemId(itemId)) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.invalid_item", itemId));
        }
        return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.not_target", itemName(itemId)));
    }

    public static ActionResult applyPreset(ServerPlayer player, String name) {
        Presets.PresetDefinition preset = Presets.get(name);
        if (preset == null) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.preset_unknown", name,
                    String.join(", ", Presets.names())));
        }
        MilestoneData data = MilestoneData.get(player.level().getServer());
        MilestoneData.PlayerData pd = data.player(player.getUUID());
        int added = 0;
        int skippedMissing = 0;
        for (String idStr : preset.items()) {
            Identifier id = Identifier.tryParse(idStr);
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
                skippedMissing++;
                continue;
            }
            if (pd.targets.add(idStr)) {
                added++;
            }
        }
        if (added > 0) {
            data.setDirty();
        }
        return new ActionResult(true, ServerLang.tr(player, "message.itemmilestonelog.preset_applied", name, added, skippedMissing));
    }

    public static ActionResult savePreset(ServerPlayer player, String rawName) {
        if (!canManagePresets(player)) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.preset_no_permission"));
        }
        String name = rawName != null ? rawName.trim() : "";
        if (!Presets.isValidId(name)) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.preset_invalid_name", name));
        }
        if (Presets.isBuiltin(name)) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.preset_builtin_protected", name));
        }
        MilestoneData data = MilestoneData.get(player.level().getServer());
        MilestoneData.PlayerData pd = data.playerOrNull(player.getUUID());
        if (pd == null || pd.targets.isEmpty()) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.preset_no_personal_targets"));
        }
        List<String> items = List.copyOf(pd.targets);
        try {
            boolean overwritten = Presets.saveCustom(name, items);
            String key = overwritten ? "message.itemmilestonelog.preset_overwritten" : "message.itemmilestonelog.preset_saved";
            return new ActionResult(true, ServerLang.tr(player, key, name, items.size()));
        } catch (IOException | IllegalArgumentException e) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.preset_save_failed", name, e.getMessage()));
        }
    }

    public static ActionResult deletePreset(CommandSourceStack source, String name) {
        if (!canManagePresets(source)) {
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
            if (Presets.deleteCustom(name)) {
                return new ActionResult(true, ServerLang.tr(source, "message.itemmilestonelog.preset_deleted", name));
            }
            return new ActionResult(false, ServerLang.tr(source, "message.itemmilestonelog.preset_unknown",
                    name, String.join(", ", Presets.names())));
        } catch (IOException e) {
            return new ActionResult(false, ServerLang.tr(source, "message.itemmilestonelog.preset_delete_failed", name, e.getMessage()));
        }
    }

    public static ActionResult deletePreset(ServerPlayer player, String name) {
        return deletePreset(player.createCommandSourceStack(), name);
    }

    public static ActionResult resetRecord(ServerPlayer player, String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.invalid_item", String.valueOf(itemId)));
        }
        MilestoneData data = MilestoneData.get(player.level().getServer());
        MilestoneData.PlayerData pd = data.playerOrNull(player.getUUID());
        if (pd != null && pd.records.remove(itemId) != null) {
            data.setDirty();
            return new ActionResult(true, ServerLang.tr(player, "message.itemmilestonelog.reset_done", itemName(itemId)));
        }
        if (!isValidItemId(itemId)) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.invalid_item", itemId));
        }
        return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.not_recorded", itemName(itemId)));
    }

    public static ActionResult resetAllRecords(ServerPlayer player) {
        MilestoneData data = MilestoneData.get(player.level().getServer());
        MilestoneData.PlayerData pd = data.playerOrNull(player.getUUID());
        int count = pd != null ? pd.records.size() : 0;
        if (count > 0) {
            pd.records.clear();
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

        Set<String> defaultSet = new LinkedHashSet<>();
        for (String s : Config.DEFAULT_TARGETS.get()) {
            defaultSet.add(s);
        }
        Set<String> personalSet = pd != null ? pd.targets : Set.of();

        Set<String> effective = Tracker.effectiveTargets(pd);
        List<String> sortedTargets = new ArrayList<>(effective);
        sortedTargets.sort(String::compareTo);

        List<TargetEntry> targetEntries = new ArrayList<>(sortedTargets.size());
        for (String id : sortedTargets) {
            targetEntries.add(new TargetEntry(id, personalSet.contains(id), defaultSet.contains(id)));
        }

        Map<String, MilestoneRecord> records = new LinkedHashMap<>();
        if (pd != null) {
            records.putAll(pd.records);
        }

        List<PresetEntry> presetEntries = new ArrayList<>();
        for (Presets.PresetDefinition p : Presets.all()) {
            presetEntries.add(new PresetEntry(
                    p.id(),
                    p.builtin() ? "" : p.displayName(),
                    p.items().size(),
                    p.builtin()
            ));
        }

        int currentPlaytime = Playtime.currentTicks(player);
        return new SyncMilestonesPayload(
                targetEntries,
                records,
                currentPlaytime,
                presetEntries,
                canManagePresets(player),
                openScreen,
                feedback
        );
    }
}
