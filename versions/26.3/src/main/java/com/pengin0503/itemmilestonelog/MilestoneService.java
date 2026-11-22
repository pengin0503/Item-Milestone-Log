package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.network.SyncMilestonesPayload;
import com.pengin0503.itemmilestonelog.network.TargetEntry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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

    public static ActionResult addTarget(ServerPlayer player, String itemId) {
        if (!isValidItemId(itemId)) {
            return new ActionResult(false, ServerLang.tr("message.itemmilestonelog.invalid_item", String.valueOf(itemId)));
        }
        Identifier id = Identifier.parse(itemId);
        if (BuiltInRegistries.ITEM.getValue(id) == Items.AIR) {
            return new ActionResult(false, ServerLang.tr("message.itemmilestonelog.invalid_item", itemId));
        }
        MilestoneData data = MilestoneData.get(player.level().getServer());
        MilestoneData.PlayerData pd = data.player(player.getUUID());
        if (!pd.targets.add(itemId)) {
            return new ActionResult(false, ServerLang.tr("message.itemmilestonelog.already_target", itemName(itemId)));
        }
        data.setDirty();
        return new ActionResult(true, ServerLang.tr("message.itemmilestonelog.added", itemName(itemId)));
    }

    public static ActionResult addHandTarget(ServerPlayer player) {
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            return new ActionResult(false, ServerLang.tr("message.itemmilestonelog.addhand_empty"));
        }
        String id = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        return addTarget(player, id);
    }

    public static ActionResult removeTarget(ServerPlayer player, String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return new ActionResult(false, ServerLang.tr("message.itemmilestonelog.invalid_item", String.valueOf(itemId)));
        }
        MilestoneData data = MilestoneData.get(player.level().getServer());
        MilestoneData.PlayerData pd = data.playerOrNull(player.getUUID());
        if (pd != null && pd.targets.remove(itemId)) {
            data.setDirty();
            return new ActionResult(true, ServerLang.tr("message.itemmilestonelog.removed", itemName(itemId)));
        }
        if (!isValidItemId(itemId)) {
            return new ActionResult(false, ServerLang.tr("message.itemmilestonelog.invalid_item", itemId));
        }
        return new ActionResult(false, ServerLang.tr("message.itemmilestonelog.not_target", itemName(itemId)));
    }

    public static ActionResult applyPreset(ServerPlayer player, String name) {
        List<String> items = Presets.PRESETS.get(name);
        if (items == null) {
            return new ActionResult(false, ServerLang.tr("message.itemmilestonelog.preset_unknown", name,
                    String.join(", ", Presets.names())));
        }
        MilestoneData data = MilestoneData.get(player.level().getServer());
        MilestoneData.PlayerData pd = data.player(player.getUUID());
        int added = 0;
        int skippedMissing = 0;
        for (String idStr : items) {
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
        return new ActionResult(true, ServerLang.tr("message.itemmilestonelog.preset_applied", name, added, skippedMissing));
    }

    public static ActionResult resetRecord(ServerPlayer player, String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return new ActionResult(false, ServerLang.tr("message.itemmilestonelog.invalid_item", String.valueOf(itemId)));
        }
        MilestoneData data = MilestoneData.get(player.level().getServer());
        MilestoneData.PlayerData pd = data.playerOrNull(player.getUUID());
        if (pd != null && pd.records.remove(itemId) != null) {
            data.setDirty();
            return new ActionResult(true, ServerLang.tr("message.itemmilestonelog.reset_done", itemName(itemId)));
        }
        if (!isValidItemId(itemId)) {
            return new ActionResult(false, ServerLang.tr("message.itemmilestonelog.invalid_item", itemId));
        }
        return new ActionResult(false, ServerLang.tr("message.itemmilestonelog.not_recorded", itemName(itemId)));
    }

    public static ActionResult resetAllRecords(ServerPlayer player) {
        MilestoneData data = MilestoneData.get(player.level().getServer());
        MilestoneData.PlayerData pd = data.playerOrNull(player.getUUID());
        int count = pd != null ? pd.records.size() : 0;
        if (count > 0) {
            pd.records.clear();
            data.setDirty();
        }
        return new ActionResult(true, ServerLang.tr("message.itemmilestonelog.resetall_done", count));
    }

    /**
     * Builds a complete snapshot of the player's milestone state to send to the client GUI.
     */
    public static SyncMilestonesPayload createSyncPayload(ServerPlayer player, boolean openScreen, Component feedback) {
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

        Map<String, Integer> records = new LinkedHashMap<>();
        if (pd != null) {
            records.putAll(pd.records);
        }

        List<String> presetNames = Presets.names();

        int currentPlaytime = Playtime.currentTicks(player);
        return new SyncMilestonesPayload(targetEntries, records, currentPlaytime, presetNames, openScreen, feedback);
    }
}
