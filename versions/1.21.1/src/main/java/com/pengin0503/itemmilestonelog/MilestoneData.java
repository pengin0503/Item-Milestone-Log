package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.WorldFirstRecord;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-world storage: for each player, their personal target items and the
 * milestone records at which each target was first obtained, plus server-wide
 * world-first records.
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
        /** Item ID -> milestone record when first obtained. */
        public final Map<String, MilestoneRecord> records = new LinkedHashMap<>();
        /**
         * Item IDs the player still carried when leaving creative or spectator mode.
         * They are not recorded until they have left the inventory (see Tracker).
         */
        public final Set<String> carriedFromCreative = new LinkedHashSet<>();
        /** Last known player username (updated on join for offline admin inspection/export). */
        public String lastKnownName = "";
        /** Whether the one-time join hint has already been shown to this player. */
        public boolean firstJoinHintShown = false;
        /** Whether this player has opted out of other players' milestone broadcasts. */
        public boolean muteBroadcasts = false;
    }

    private final Map<UUID, PlayerData> players = new LinkedHashMap<>();
    private final Map<String, WorldFirstRecord> worldFirsts = new LinkedHashMap<>();

    public static MilestoneData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public PlayerData player(UUID uuid) {
        return players.computeIfAbsent(uuid, u -> {
            setDirty();
            return new PlayerData();
        });
    }

    @Nullable
    public PlayerData playerOrNull(UUID uuid) {
        return players.get(uuid);
    }

    public Map<UUID, PlayerData> allPlayers() {
        return Collections.unmodifiableMap(players);
    }

    public boolean purgePlayer(UUID uuid) {
        if (players.remove(uuid) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    @Nullable
    public WorldFirstRecord worldFirstOrNull(String itemId) {
        return worldFirsts.get(itemId);
    }

    public Map<String, WorldFirstRecord> worldFirsts() {
        return Collections.unmodifiableMap(worldFirsts);
    }

    public boolean recordWorldFirstIfAbsent(String itemId, UUID playerUuid, String playerName, MilestoneRecord record) {
        if (worldFirsts.containsKey(itemId)) {
            return false;
        }
        worldFirsts.put(itemId, new WorldFirstRecord(
                itemId,
                playerUuid,
                playerName != null ? playerName : "",
                record.playTicks(),
                record.day(),
                record.epochMillis()
        ));
        setDirty();
        return true;
    }

    public boolean removeWorldFirst(String itemId) {
        if (worldFirsts.remove(itemId) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    public int clearWorldFirsts() {
        int count = worldFirsts.size();
        if (count > 0) {
            worldFirsts.clear();
            setDirty();
        }
        return count;
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
                if (records.contains(itemId, Tag.TAG_COMPOUND)) {
                    CompoundTag recTag = records.getCompound(itemId);
                    int ticks = recTag.getInt("ticks");
                    long day = recTag.contains("day", Tag.TAG_ANY_NUMERIC)
                            ? recTag.getLong("day")
                            : MilestoneRecord.UNKNOWN_DAY;
                    long epoch = recTag.contains("epoch", Tag.TAG_ANY_NUMERIC)
                            ? recTag.getLong("epoch")
                            : MilestoneRecord.UNKNOWN_EPOCH;
                    pd.records.put(itemId, new MilestoneRecord(ticks, day, epoch));
                } else {
                    // Backward compatibility: 1.0.0 stored records as plain integers.
                    pd.records.put(itemId, MilestoneRecord.legacy(records.getInt(itemId)));
                }
            }
            for (Tag t : entry.getList("carriedFromCreative", Tag.TAG_STRING)) {
                pd.carriedFromCreative.add(t.getAsString());
            }
            if (entry.contains("lastKnownName", Tag.TAG_STRING)) {
                pd.lastKnownName = entry.getString("lastKnownName");
            }
            if (entry.contains("firstJoinHintShown")) {
                pd.firstJoinHintShown = entry.getBoolean("firstJoinHintShown");
            }
            if (entry.contains("muteBroadcasts")) {
                pd.muteBroadcasts = entry.getBoolean("muteBroadcasts");
            }
            data.players.put(uuid, pd);
        }

        CompoundTag firsts = tag.getCompound("worldFirsts");
        for (String itemId : firsts.getAllKeys()) {
            if (!firsts.contains(itemId, Tag.TAG_COMPOUND)) {
                continue;
            }
            CompoundTag wfTag = firsts.getCompound(itemId);
            UUID uuid;
            try {
                uuid = UUID.fromString(wfTag.getString("uuid"));
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            String name = wfTag.getString("name");
            int ticks = wfTag.getInt("ticks");
            long day = wfTag.contains("day", Tag.TAG_ANY_NUMERIC)
                    ? wfTag.getLong("day")
                    : MilestoneRecord.UNKNOWN_DAY;
            long epoch = wfTag.contains("epoch", Tag.TAG_ANY_NUMERIC)
                    ? wfTag.getLong("epoch")
                    : MilestoneRecord.UNKNOWN_EPOCH;
            data.worldFirsts.put(itemId, new WorldFirstRecord(itemId, uuid, name, ticks, day, epoch));
        }

        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        CompoundTag all = new CompoundTag();
        for (Map.Entry<UUID, PlayerData> e : players.entrySet()) {
            PlayerData pd = e.getValue();
            CompoundTag entry = new CompoundTag();
            ListTag targets = new ListTag();
            for (String t : pd.targets) {
                targets.add(StringTag.valueOf(t));
            }
            entry.put("targets", targets);
            CompoundTag records = new CompoundTag();
            for (Map.Entry<String, MilestoneRecord> r : pd.records.entrySet()) {
                MilestoneRecord rec = r.getValue();
                CompoundTag recTag = new CompoundTag();
                recTag.putInt("ticks", rec.playTicks());
                recTag.putLong("day", rec.day());
                recTag.putLong("epoch", rec.epochMillis());
                records.put(r.getKey(), recTag);
            }
            entry.put("records", records);
            if (!pd.carriedFromCreative.isEmpty()) {
                ListTag carried = new ListTag();
                for (String id : pd.carriedFromCreative) {
                    carried.add(StringTag.valueOf(id));
                }
                entry.put("carriedFromCreative", carried);
            }
            if (pd.lastKnownName != null && !pd.lastKnownName.isEmpty()) {
                entry.putString("lastKnownName", pd.lastKnownName);
            }
            if (pd.firstJoinHintShown) {
                entry.putBoolean("firstJoinHintShown", true);
            }
            if (pd.muteBroadcasts) {
                entry.putBoolean("muteBroadcasts", true);
            }
            all.put(e.getKey().toString(), entry);
        }
        tag.put("players", all);

        if (!worldFirsts.isEmpty()) {
            CompoundTag firsts = new CompoundTag();
            for (Map.Entry<String, WorldFirstRecord> e : worldFirsts.entrySet()) {
                WorldFirstRecord wf = e.getValue();
                CompoundTag wfTag = new CompoundTag();
                wfTag.putString("uuid", wf.playerUuid().toString());
                wfTag.putString("name", wf.playerName());
                wfTag.putInt("ticks", wf.playTicks());
                wfTag.putLong("day", wf.day());
                wfTag.putLong("epoch", wf.epochMillis());
                firsts.put(e.getKey(), wfTag);
            }
            tag.put("worldFirsts", firsts);
        }

        return tag;
    }
}
