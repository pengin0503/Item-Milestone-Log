package com.pengin0503.itemmilestonelog;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * the 1.21.1 version, which keeps it in {@code <world>/data/itemmilestonelog_data.dat}.
 * When a world has no data in the new place yet, that 1.21.1 file is read instead
 * (and left as it is), so records carry over when a 1.21.1 world is opened here.
 */
public class MilestoneData extends SavedData {
    private static final Identifier DATA_ID = Identifier.fromNamespaceAndPath(ItemMilestoneLog.MOD_ID, "milestones");

    /** The 1.21.1 version's file, relative to the world folder. */
    static final String LEGACY_FILE = "data/" + ItemMilestoneLog.MOD_ID + "_data.dat";

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

    private static final Codec<PlayerData> PLAYER_DATA_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.listOf().optionalFieldOf("targets", List.of())
                    .forGetter(pd -> new ArrayList<>(pd.targets)),
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("records", Map.of())
                    .forGetter(pd -> pd.records),
            Codec.STRING.listOf().optionalFieldOf("carriedFromCreative", List.of())
                    .forGetter(pd -> new ArrayList<>(pd.carriedFromCreative))
    ).apply(instance, (targets, records, carriedFromCreative) -> {
        PlayerData pd = new PlayerData();
        pd.targets.addAll(targets);
        pd.records.putAll(records);
        pd.carriedFromCreative.addAll(carriedFromCreative);
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
        var storage = server.getDataStorage();
        MilestoneData data = storage.get(TYPE);
        if (data == null) {
            data = readLegacy(server.getWorldPath(LevelResource.ROOT).resolve(LEGACY_FILE));
            if (data == null) {
                data = new MilestoneData();
            } else {
                // Marks it dirty, so it is written to the new file on the next save.
                ItemMilestoneLog.LOGGER.info("[ItemMilestoneLog] Imported the records of {} player(s) from {}",
                        data.players.size(), LEGACY_FILE);
            }
            storage.set(TYPE, data);
        }
        return data;
    }

    /**
     * Reads a file in the 1.21.1 format: a compressed NBT file whose "data" tag
     * holds the same structure as {@link #CODEC}. Returns null if there is no such
     * file or it cannot be read.
     */
    static MilestoneData readLegacy(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            return CODEC.parse(NbtOps.INSTANCE, root.getCompoundOrEmpty("data"))
                    .resultOrPartial(error -> ItemMilestoneLog.LOGGER.warn(
                            "[ItemMilestoneLog] Problem reading {}: {}", file, error))
                    .orElse(null);
        } catch (IOException | RuntimeException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Could not read {}; starting without its records", file, e);
            return null;
        }
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
