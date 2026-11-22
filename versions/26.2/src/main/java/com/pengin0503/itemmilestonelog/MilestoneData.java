package com.pengin0503.itemmilestonelog;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.WorldFirstRecord;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelResource;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-world storage: for each player, their personal target items and milestone records,
 * plus server-wide world-first records.
 */
public class MilestoneData extends SavedData {
    private static final Identifier DATA_ID = Identifier.fromNamespaceAndPath(ItemMilestoneLog.MOD_ID, "milestones");

    /** The 1.21.1 version's file, relative to the world folder. */
    static final String LEGACY_FILE = "data/" + ItemMilestoneLog.MOD_ID + "_data.dat";

    /** Per-player state. */
    public static final class PlayerData {
        public final Set<String> targets = new LinkedHashSet<>();
        public final Map<String, MilestoneRecord> records = new LinkedHashMap<>();
        public final Set<String> carriedFromCreative = new LinkedHashSet<>();
        public String lastKnownName = "";
        public boolean firstJoinHintShown = false;
        public boolean muteBroadcasts = false;
    }

    private record WorldFirstEntry(String uuid, String name, int ticks, long day, long epoch) {
    }

    private static final Codec<MilestoneRecord> RECORD_OBJECT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("ticks").forGetter(MilestoneRecord::playTicks),
            Codec.LONG.optionalFieldOf("day", MilestoneRecord.UNKNOWN_DAY).forGetter(MilestoneRecord::day),
            Codec.LONG.optionalFieldOf("epoch", MilestoneRecord.UNKNOWN_EPOCH).forGetter(MilestoneRecord::epochMillis)
    ).apply(instance, MilestoneRecord::new));

    /** Decodes both legacy 1.0.0 plain integers and 1.0.1 compound objects. */
    public static final Codec<MilestoneRecord> RECORD_CODEC = Codec.either(Codec.INT, RECORD_OBJECT_CODEC)
            .xmap(
                    either -> either.map(MilestoneRecord::legacy, r -> r),
                    Either::right
            );

    private static final Codec<WorldFirstEntry> WORLD_FIRST_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("uuid").forGetter(WorldFirstEntry::uuid),
            Codec.STRING.optionalFieldOf("name", "").forGetter(WorldFirstEntry::name),
            Codec.INT.fieldOf("ticks").forGetter(WorldFirstEntry::ticks),
            Codec.LONG.optionalFieldOf("day", MilestoneRecord.UNKNOWN_DAY).forGetter(WorldFirstEntry::day),
            Codec.LONG.optionalFieldOf("epoch", MilestoneRecord.UNKNOWN_EPOCH).forGetter(WorldFirstEntry::epoch)
    ).apply(instance, WorldFirstEntry::new));

    private static final Codec<PlayerData> PLAYER_DATA_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.listOf().optionalFieldOf("targets", List.of())
                    .forGetter(pd -> new ArrayList<>(pd.targets)),
            Codec.unboundedMap(Codec.STRING, RECORD_CODEC).optionalFieldOf("records", Map.of())
                    .forGetter(pd -> pd.records),
            Codec.STRING.listOf().optionalFieldOf("carriedFromCreative", List.of())
                    .forGetter(pd -> new ArrayList<>(pd.carriedFromCreative)),
            Codec.STRING.optionalFieldOf("lastKnownName", "")
                    .forGetter(pd -> pd.lastKnownName != null ? pd.lastKnownName : ""),
            Codec.BOOL.optionalFieldOf("firstJoinHintShown", false)
                    .forGetter(pd -> pd.firstJoinHintShown),
            Codec.BOOL.optionalFieldOf("muteBroadcasts", false)
                    .forGetter(pd -> pd.muteBroadcasts)
    ).apply(instance, (targets, records, carriedFromCreative, lastKnownName, firstJoinHintShown, muteBroadcasts) -> {
        PlayerData pd = new PlayerData();
        pd.targets.addAll(targets);
        pd.records.putAll(records);
        pd.carriedFromCreative.addAll(carriedFromCreative);
        pd.lastKnownName = lastKnownName;
        pd.firstJoinHintShown = firstJoinHintShown;
        pd.muteBroadcasts = muteBroadcasts;
        return pd;
    }));

    public static final Codec<MilestoneData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Codec.STRING, PLAYER_DATA_CODEC).optionalFieldOf("players", Map.of())
                    .forGetter(MilestoneData::playersByStringKey),
            Codec.unboundedMap(Codec.STRING, WORLD_FIRST_CODEC).optionalFieldOf("worldFirsts", Map.of())
                    .forGetter(MilestoneData::worldFirstsAsEntries)
    ).apply(instance, MilestoneData::fromCodec));

    private static final SavedDataType<MilestoneData> TYPE = new SavedDataType<>(
            DATA_ID,
            MilestoneData::new,
            CODEC
    );

    private final Map<UUID, PlayerData> players = new LinkedHashMap<>();
    private final Map<String, WorldFirstRecord> worldFirsts = new LinkedHashMap<>();

    public static MilestoneData get(MinecraftServer server) {
        var storage = server.getDataStorage();
        MilestoneData data = storage.get(TYPE);
        if (data == null) {
            data = readLegacy(server.getWorldPath(LevelResource.ROOT).resolve(LEGACY_FILE));
            if (data == null) {
                data = new MilestoneData();
            } else {
                ItemMilestoneLog.LOGGER.info("[ItemMilestoneLog] Imported the records of {} player(s) from {}",
                        data.players.size(), LEGACY_FILE);
            }
            storage.set(TYPE, data);
        }
        return data;
    }

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

    private Map<String, PlayerData> playersByStringKey() {
        Map<String, PlayerData> map = new LinkedHashMap<>(players.size());
        for (Map.Entry<UUID, PlayerData> e : players.entrySet()) {
            map.put(e.getKey().toString(), e.getValue());
        }
        return map;
    }

    private Map<String, WorldFirstEntry> worldFirstsAsEntries() {
        Map<String, WorldFirstEntry> map = new LinkedHashMap<>(worldFirsts.size());
        for (Map.Entry<String, WorldFirstRecord> e : worldFirsts.entrySet()) {
            WorldFirstRecord wf = e.getValue();
            map.put(e.getKey(), new WorldFirstEntry(
                    wf.playerUuid().toString(),
                    wf.playerName(),
                    wf.playTicks(),
                    wf.day(),
                    wf.epochMillis()
            ));
        }
        return map;
    }

    private static MilestoneData fromCodec(Map<String, PlayerData> rawPlayers, Map<String, WorldFirstEntry> rawFirsts) {
        MilestoneData data = new MilestoneData();
        for (Map.Entry<String, PlayerData> e : rawPlayers.entrySet()) {
            UUID uuid;
            try {
                uuid = UUID.fromString(e.getKey());
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            data.players.put(uuid, e.getValue());
        }
        for (Map.Entry<String, WorldFirstEntry> e : rawFirsts.entrySet()) {
            WorldFirstEntry wf = e.getValue();
            UUID uuid;
            try {
                uuid = UUID.fromString(wf.uuid());
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            data.worldFirsts.put(e.getKey(), new WorldFirstRecord(
                    e.getKey(), uuid, wf.name(), wf.ticks(), wf.day(), wf.epoch()));
        }
        return data;
    }
}
