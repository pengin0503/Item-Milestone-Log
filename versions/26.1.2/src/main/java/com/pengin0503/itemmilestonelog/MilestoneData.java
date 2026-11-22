package com.pengin0503.itemmilestonelog;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-world storage: for each player, their personal target items and the
 * cumulative playtime ticks at which each target was first obtained.
 *
 * Stored in the server-wide data storage (not a dimension's) as
 * {@code <world>/data/itemmilestonelog/milestones.dat}. The NBT structure inside
 * ("players" -> UUID -> { "targets": [...], "records": {...} }) is the same as in
 * the 1.21.1 version, but the 1.21.1 version keeps it in a different file
 * ({@code <world>/data/itemmilestonelog_data.dat}).
 */
public class MilestoneData extends SavedData {
    private static final Identifier DATA_ID = Identifier.fromNamespaceAndPath(ItemMilestoneLog.MOD_ID, "milestones");

    /** Per-player state. */
    public static final class PlayerData {
        /** Personal target item IDs, in the order they were added. */
        public final Set<String> targets = new LinkedHashSet<>();
        /** Item ID -> cumulative playtime ticks when first obtained. */
        public final Map<String, Integer> records = new LinkedHashMap<>();
    }

    private static final Codec<PlayerData> PLAYER_DATA_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.listOf().optionalFieldOf("targets", List.of())
                    .forGetter(pd -> new ArrayList<>(pd.targets)),
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("records", Map.of())
                    .forGetter(pd -> pd.records)
    ).apply(instance, (targets, records) -> {
        PlayerData pd = new PlayerData();
        pd.targets.addAll(targets);
        pd.records.putAll(records);
        return pd;
    }));

    public static final Codec<MilestoneData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Codec.STRING, PLAYER_DATA_CODEC).optionalFieldOf("players", Map.of())
                    .forGetter(MilestoneData::playersByStringKey)
    ).apply(instance, MilestoneData::fromStringKeyMap));

    private static final SavedDataType<MilestoneData> TYPE = new SavedDataType<>(
            DATA_ID,
            MilestoneData::new,
            CODEC
    );

    private final Map<UUID, PlayerData> players = new LinkedHashMap<>();

    public static MilestoneData get(MinecraftServer server) {
        // Not tied to a dimension, so it lives in the server's own data storage.
        return server.getDataStorage().computeIfAbsent(TYPE);
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

    private Map<String, PlayerData> playersByStringKey() {
        Map<String, PlayerData> map = new LinkedHashMap<>(players.size());
        for (Map.Entry<UUID, PlayerData> e : players.entrySet()) {
            map.put(e.getKey().toString(), e.getValue());
        }
        return map;
    }

    private static MilestoneData fromStringKeyMap(Map<String, PlayerData> raw) {
        MilestoneData data = new MilestoneData();
        for (Map.Entry<String, PlayerData> e : raw.entrySet()) {
            UUID uuid;
            try {
                uuid = UUID.fromString(e.getKey());
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            data.players.put(uuid, e.getValue());
        }
        return data;
    }
}
