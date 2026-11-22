package com.pengin0503.itemmilestonelog;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-world storage: for each player, their personal target items and the
 * cumulative playtime ticks at which each target was first obtained.
 *
 * Stored in the overworld's data storage as "itemmilestonelog_data",
 * so records survive restarts and are kept per world (per server).
 */
public class MilestoneData extends SavedData {
    private static final String DATA_NAME = ItemMilestoneLog.MOD_ID + "_data";

    private static final Factory<MilestoneData> FACTORY = new Factory<>(
            MilestoneData::new,
            MilestoneData::load,
            null);

    /** Per-player state. */
    public static final class PlayerData {
        /** Personal target item IDs, in the order they were added. */
        public final Set<String> targets = new LinkedHashSet<>();
        /** Item ID -> cumulative playtime ticks when first obtained. */
        public final Map<String, Integer> records = new LinkedHashMap<>();
        /**
         * Item IDs the player still carried when leaving creative or spectator mode.
         * They are not recorded until they have left the inventory (see Tracker).
         */
        public final Set<String> carriedFromCreative = new LinkedHashSet<>();
    }

    private final Map<UUID, PlayerData> players = new LinkedHashMap<>();

    public static MilestoneData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public PlayerData player(UUID uuid) {
        return players.computeIfAbsent(uuid, u -> {
            setDirty();
            return new PlayerData();
        });
    }

    public PlayerData playerOrNull(UUID uuid) {
        return players.get(uuid);
    }

    // ------------------------------------------------------------------ NBT

    public static MilestoneData load(CompoundTag tag, HolderLookup.Provider provider) {
        MilestoneData data = new MilestoneData();
        CompoundTag all = tag.getCompound("players");
        for (String uuidKey : all.getAllKeys()) {
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidKey);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            CompoundTag entry = all.getCompound(uuidKey);
            PlayerData pd = new PlayerData();
            ListTag targets = entry.getList("targets", Tag.TAG_STRING);
            for (Tag t : targets) {
                pd.targets.add(t.getAsString());
            }
            CompoundTag records = entry.getCompound("records");
            for (String itemId : records.getAllKeys()) {
                pd.records.put(itemId, records.getInt(itemId));
            }
            for (Tag t : entry.getList("carriedFromCreative", Tag.TAG_STRING)) {
                pd.carriedFromCreative.add(t.getAsString());
            }
            data.players.put(uuid, pd);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        CompoundTag all = new CompoundTag();
        for (Map.Entry<UUID, PlayerData> e : players.entrySet()) {
            CompoundTag entry = new CompoundTag();
            ListTag targets = new ListTag();
            for (String t : e.getValue().targets) {
                targets.add(StringTag.valueOf(t));
            }
            entry.put("targets", targets);
            CompoundTag records = new CompoundTag();
            for (Map.Entry<String, Integer> r : e.getValue().records.entrySet()) {
                records.putInt(r.getKey(), r.getValue());
            }
            entry.put("records", records);
            if (!e.getValue().carriedFromCreative.isEmpty()) {
                ListTag carried = new ListTag();
                for (String id : e.getValue().carriedFromCreative) {
                    carried.add(StringTag.valueOf(id));
                }
                entry.put("carriedFromCreative", carried);
            }
            all.put(e.getKey().toString(), entry);
        }
        tag.put("players", all);
        return tag;
    }
}
