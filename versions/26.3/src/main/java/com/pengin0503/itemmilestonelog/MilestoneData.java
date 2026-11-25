package com.pengin0503.itemmilestonelog;

import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.api.WorldFirstRecord;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
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
 * Per-world storage: for each player, their personal target items (with quantities)
 * and milestone records, plus server-wide world-first records.
 */
public class MilestoneData extends SavedData {
    private static final Identifier DATA_ID = Identifier.fromNamespaceAndPath(ItemMilestoneLog.MOD_ID, "milestones");

    /** The 1.21.1 version's file, relative to the world folder. */
    static final String LEGACY_FILE = "data/" + ItemMilestoneLog.MOD_ID + "_data.dat";

    /** Per-player state. */
    public static final class PlayerData {
        public final Set<TargetKey> targets = new LinkedHashSet<>();
        public final Map<TargetKey, MilestoneRecord> records = new LinkedHashMap<>();
        public final Set<String> carriedFromCreative = new LinkedHashSet<>();
        public String lastKnownName = "";
        public boolean firstJoinHintShown = false;
        public boolean muteBroadcasts = false;
        public boolean muteProgressAlerts = false;
        public boolean excludeFromDiscord = false;
        public boolean hideFromLookup = false;
        public long lastSeen = 0L;
        public final Map<TargetKey, Integer> progressNotified = new LinkedHashMap<>();
        public final Set<String> completedPresets = new LinkedHashSet<>();

        /** Whether this player hides their records from lookups by non-privileged other players (F14). */
        public boolean isHiddenFromLookup() {
            return hideFromLookup;
        }
    }

    private record WorldFirstEntry(String uuid, String name, int ticks, long day, long epoch) {
    }

    private static final Codec<MilestoneRecord> RECORD_OBJECT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("ticks").forGetter(MilestoneRecord::playTicks),
            Codec.LONG.optionalFieldOf("day", MilestoneRecord.UNKNOWN_DAY).forGetter(MilestoneRecord::day),
            Codec.LONG.optionalFieldOf("epoch", MilestoneRecord.UNKNOWN_EPOCH).forGetter(MilestoneRecord::epochMillis)
    ).apply(instance, MilestoneRecord::new));

    /**
     * Lenient per-entry wrapper: a malformed record (e.g. missing "ticks") decodes to a sentinel with
     * negative ticks, which the PlayerData factory skips with a warning, instead of failing the whole
     * SavedData. Mirrors the 1.21.1 NBT loader's per-entry skipping. Encoding is unchanged.
     * Only used for values inside count maps; item-level format detection needs the strict codec.
     */
    private static final Codec<MilestoneRecord> LENIENT_RECORD_CODEC = RECORD_OBJECT_CODEC.orElse(
            new MilestoneRecord(-1, MilestoneRecord.UNKNOWN_DAY, MilestoneRecord.UNKNOWN_EPOCH));

    /**
     * Decodes all three record formats under {@code records[itemId]}:
     * 1.0.0 plain int (count = 1), early 1.0.1 compound {ticks, day, epoch} (count = 1),
     * and quantity map { "<count>": {ticks, day, epoch}, ... }.
     */
    private static final Codec<Map<String, MilestoneRecord>> ITEM_RECORDS_CODEC = Codec.either(
            Codec.INT,
            Codec.either(RECORD_OBJECT_CODEC, Codec.unboundedMap(Codec.STRING, LENIENT_RECORD_CODEC))
    ).xmap(
            either -> either.map(
                    ticks -> Map.of("1", MilestoneRecord.legacy(ticks)),
                    inner -> inner.map(
                            single -> Map.of("1", single),
                            countMap -> countMap
                    )
            ),
            countMap -> Either.right(Either.right(countMap))
    // An item whose value matches none of the formats decodes to no records instead of failing the player.
    ).orElse(Map.of());

    private static final Codec<WorldFirstEntry> WORLD_FIRST_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("uuid").forGetter(WorldFirstEntry::uuid),
            Codec.STRING.optionalFieldOf("name", "").forGetter(WorldFirstEntry::name),
            Codec.INT.fieldOf("ticks").forGetter(WorldFirstEntry::ticks),
            Codec.LONG.optionalFieldOf("day", MilestoneRecord.UNKNOWN_DAY).forGetter(WorldFirstEntry::day),
            Codec.LONG.optionalFieldOf("epoch", MilestoneRecord.UNKNOWN_EPOCH).forGetter(WorldFirstEntry::epoch)
    ).apply(instance, WorldFirstEntry::new));

    /** Lenient per-entry wrapper (see {@link #LENIENT_RECORD_CODEC}): failed entries become ticks == -1 and are skipped. */
    private static final Codec<WorldFirstEntry> LENIENT_WORLD_FIRST_CODEC = WORLD_FIRST_CODEC.orElse(
            new WorldFirstEntry("", "", -1, MilestoneRecord.UNKNOWN_DAY, MilestoneRecord.UNKNOWN_EPOCH));

    /**
     * Decodes both early 1.0.1 worldFirsts (itemId -> entry, count = 1)
     * and quantity worldFirsts (itemId -> { "<count>": entry, ... }).
     */
    private static final Codec<Map<String, WorldFirstEntry>> ITEM_WORLD_FIRSTS_CODEC = Codec.either(
            WORLD_FIRST_CODEC,
            Codec.unboundedMap(Codec.STRING, LENIENT_WORLD_FIRST_CODEC)
    ).xmap(
            either -> either.map(
                    single -> Map.of("1", single),
                    countMap -> countMap
            ),
            Either::right
    // An item whose value matches neither format decodes to no entries instead of failing the file.
    ).orElse(Map.of());

    private static List<String> encodeTargets(PlayerData pd) {
        if (pd.targets.isEmpty()) {
            return List.of();
        }
        List<String> list = new ArrayList<>(pd.targets.size());
        for (TargetKey key : pd.targets) {
            list.add(key.toEncodedString());
        }
        return list;
    }

    private static Map<String, Map<String, MilestoneRecord>> encodeRecords(PlayerData pd) {
        if (pd.records.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, MilestoneRecord>> nested = new LinkedHashMap<>();
        for (Map.Entry<TargetKey, MilestoneRecord> e : pd.records.entrySet()) {
            TargetKey key = e.getKey();
            nested.computeIfAbsent(key.itemId(), k -> new LinkedHashMap<>())
                    .put(String.valueOf(key.count()), e.getValue());
        }
        return nested;
    }

    /** Lenient per-entry wrapper for {@code progressNotified} values: non-numeric values decode to -1 and are skipped. */
    private static final Codec<Integer> LENIENT_PROGRESS_THRESHOLD_CODEC = Codec.INT.orElse(-1);

    /** Lenient per-entry wrapper for {@code completedPresets} elements: non-string values decode to "" and are skipped. */
    private static final Codec<String> LENIENT_COMPLETED_PRESET_CODEC = Codec.STRING.orElse("");

    private static Map<String, Integer> encodeProgressNotified(PlayerData pd) {
        if (pd.progressNotified.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> encoded = new LinkedHashMap<>(pd.progressNotified.size());
        for (Map.Entry<TargetKey, Integer> e : pd.progressNotified.entrySet()) {
            encoded.put(e.getKey().toEncodedString(), e.getValue());
        }
        return encoded;
    }

    private static final Codec<PlayerData> PLAYER_DATA_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.listOf().optionalFieldOf("targets", List.of())
                    .forGetter(MilestoneData::encodeTargets),
            Codec.unboundedMap(Codec.STRING, ITEM_RECORDS_CODEC).optionalFieldOf("records", Map.of())
                    .forGetter(MilestoneData::encodeRecords),
            Codec.STRING.listOf().optionalFieldOf("carriedFromCreative", List.of())
                    .forGetter(pd -> pd.carriedFromCreative.isEmpty() ? List.of() : new ArrayList<>(pd.carriedFromCreative)),
            Codec.STRING.optionalFieldOf("lastKnownName", "")
                    .forGetter(pd -> pd.lastKnownName != null ? pd.lastKnownName : ""),
            Codec.BOOL.optionalFieldOf("firstJoinHintShown", false)
                    .forGetter(pd -> pd.firstJoinHintShown),
            Codec.BOOL.optionalFieldOf("muteBroadcasts", false)
                    .forGetter(pd -> pd.muteBroadcasts),
            Codec.BOOL.lenientOptionalFieldOf("muteProgressAlerts", false)
                    .forGetter(pd -> pd.muteProgressAlerts),
            Codec.BOOL.lenientOptionalFieldOf("excludeFromDiscord", false)
                    .forGetter(pd -> pd.excludeFromDiscord),
            Codec.BOOL.lenientOptionalFieldOf("hideFromLookup", false)
                    .forGetter(pd -> pd.hideFromLookup),
            Codec.LONG.lenientOptionalFieldOf("lastSeen", 0L)
                    .forGetter(pd -> pd.lastSeen > 0L ? pd.lastSeen : 0L),
            Codec.unboundedMap(Codec.STRING, LENIENT_PROGRESS_THRESHOLD_CODEC)
                    .lenientOptionalFieldOf("progressNotified", Map.of())
                    .forGetter(MilestoneData::encodeProgressNotified),
            Codec.list(LENIENT_COMPLETED_PRESET_CODEC)
                    .lenientOptionalFieldOf("completedPresets", List.of())
                    .forGetter(pd -> pd.completedPresets.isEmpty() ? List.of() : new ArrayList<>(pd.completedPresets))
    ).apply(instance, (targets, records, carriedFromCreative, lastKnownName, firstJoinHintShown, muteBroadcasts, muteProgressAlerts, excludeFromDiscord, hideFromLookup, lastSeen, progressNotified, completedPresets) -> {
        PlayerData pd = new PlayerData();
        for (String raw : targets) {
            TargetKey parsed = TargetKey.tryParseEncoded(raw);
            if (parsed != null) {
                pd.targets.add(parsed);
            } else {
                MilestoneLoadReport.recordElementSkip();
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid target entry '{}' in SavedData", raw);
            }
        }
        for (Map.Entry<String, Map<String, MilestoneRecord>> itemEntry : records.entrySet()) {
            String itemId = itemEntry.getKey();
            if (!TargetKey.isValidItemId(itemId)) {
                MilestoneLoadReport.recordElementSkip();
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid record item key '{}' in SavedData", itemId);
                continue;
            }
            for (Map.Entry<String, MilestoneRecord> countEntry : itemEntry.getValue().entrySet()) {
                int count = TargetKey.tryParseCount(countEntry.getKey());
                if (count < TargetKey.MIN_COUNT) {
                    MilestoneLoadReport.recordElementSkip();
                    ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid record count '{}' for '{}' in SavedData",
                            countEntry.getKey(), itemId);
                    continue;
                }
                MilestoneRecord rec = countEntry.getValue();
                if (rec == null) {
                    MilestoneLoadReport.recordElementSkip();
                    ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping null record for '{}#{}' in SavedData",
                            itemId, countEntry.getKey());
                    continue;
                }
                if (rec.playTicks() < 0) {
                    MilestoneLoadReport.recordElementSkip();
                    ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping record for '{}#{}' with negative ticks {} in SavedData",
                            itemId, countEntry.getKey(), rec.playTicks());
                    continue;
                }
                pd.records.put(new TargetKey(itemId, count), rec);
            }
        }
        pd.carriedFromCreative.addAll(carriedFromCreative);
        pd.lastKnownName = lastKnownName;
        pd.firstJoinHintShown = firstJoinHintShown;
        pd.muteBroadcasts = muteBroadcasts;
        pd.muteProgressAlerts = muteProgressAlerts;
        pd.excludeFromDiscord = excludeFromDiscord;
        pd.hideFromLookup = hideFromLookup;
        if (lastSeen > 0L) {
            pd.lastSeen = lastSeen;
        } else if (lastSeen < 0L) {
            MilestoneLoadReport.recordElementSkip();
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping negative lastSeen value {} in SavedData", lastSeen);
        }
        for (Map.Entry<String, Integer> progEntry : progressNotified.entrySet()) {
            String rawKey = progEntry.getKey();
            TargetKey parsed = TargetKey.tryParseEncoded(rawKey);
            if (parsed == null) {
                MilestoneLoadReport.recordElementSkip();
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid progressNotified key '{}' in SavedData", rawKey);
                continue;
            }
            Integer threshold = progEntry.getValue();
            if (threshold == null || !ProgressAlertLogic.isValidStoredThreshold(threshold)) {
                MilestoneLoadReport.recordElementSkip();
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping out-of-range progressNotified value '{}' for '{}' in SavedData",
                        threshold, rawKey);
                continue;
            }
            pd.progressNotified.put(parsed, threshold);
        }
        for (String presetId : completedPresets) {
            if (Presets.isValidId(presetId)) {
                pd.completedPresets.add(presetId);
            } else {
                MilestoneLoadReport.recordElementSkip();
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid completedPresets entry '{}' in SavedData", presetId);
            }
        }
        return pd;
    }));

    /**
     * Lenient {@code schemaVersion} decoding (F-140-1): a present but non-numeric value decodes to
     * {@link SchemaVersionLogic#MALFORMED_SCHEMA_VERSION} so the loader can warn and treat it as 0
     * instead of failing the whole SavedData file.
     */
    private static final Codec<Integer> LENIENT_SCHEMA_VERSION_CODEC =
            Codec.INT.orElse(SchemaVersionLogic.MALFORMED_SCHEMA_VERSION);

    private static final Codec<MilestoneData> RAW_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Codec.STRING, PLAYER_DATA_CODEC).optionalFieldOf("players", Map.of())
                    .forGetter(MilestoneData::playersByStringKey),
            Codec.unboundedMap(Codec.STRING, ITEM_WORLD_FIRSTS_CODEC).optionalFieldOf("worldFirsts", Map.of())
                    .forGetter(MilestoneData::worldFirstsAsNestedMap),
            Codec.LONG.lenientOptionalFieldOf("lastDigestEpoch", 0L)
                    .forGetter(d -> d.lastDigestEpochMillis > 0L ? d.lastDigestEpochMillis : 0L),
            // F-140-1 (N88): always written; never downgrade a value loaded from a newer release.
            LENIENT_SCHEMA_VERSION_CODEC
                    .optionalFieldOf("schemaVersion", SchemaVersionLogic.ABSENT_SCHEMA_VERSION)
                    .forGetter(MilestoneData::schemaVersionForSave)
    ).apply(instance, MilestoneData::fromCodec));

    /**
     * F-140-2 (N141): wraps {@link #RAW_CODEC} so every decode has an active
     * {@link MilestoneLoadReport}, including nested per-player/per-record decoders that run
     * before {@link #fromCodec}. The finished report is attached to the decoded instance.
     */
    public static final Codec<MilestoneData> CODEC = new Codec<>() {
        @Override
        public <T> DataResult<Pair<MilestoneData, T>> decode(DynamicOps<T> ops, T input) {
            MilestoneLoadReport report = MilestoneLoadReport.begin();
            try {
                DataResult<Pair<MilestoneData, T>> result = RAW_CODEC.decode(ops, input);
                result.result().ifPresent(pair -> pair.getFirst().loadReport = report);
                return result;
            } finally {
                MilestoneLoadReport.end(report);
            }
        }

        @Override
        public <T> DataResult<T> encode(MilestoneData input, DynamicOps<T> ops, T prefix) {
            return RAW_CODEC.encode(input, ops, prefix);
        }
    };

    static final SavedDataType<MilestoneData> TYPE = new SavedDataType<>(
            DATA_ID,
            MilestoneData::new,
            CODEC
    );

    private final Map<UUID, PlayerData> players = new LinkedHashMap<>();
    private final Map<TargetKey, WorldFirstRecord> worldFirsts = new LinkedHashMap<>();
    /** Epoch millis of the last Discord digest (0 = never initialized/sent, F9). */
    private long lastDigestEpochMillis = 0L;
    /** Schema version retained from the last load; 0 when the field was missing or invalid (F-140-1). */
    private int retainedSchemaVersion = 0;
    /** Element-skip/schema accounting for F-140-2 (N141); set by {@link #CODEC} after a decode. */
    @Nullable
    private MilestoneLoadReport loadReport;

    /** Load accounting for the most recent decode of this instance, or {@code null} if not loaded. */
    @Nullable
    MilestoneLoadReport loadReport() {
        return loadReport;
    }

    /** SchemaVersion value retained from the last load (0 when the field was missing or invalid). */
    int retainedSchemaVersion() {
        return retainedSchemaVersion;
    }

    /** SchemaVersion to write on save: {@code max(CURRENT_SCHEMA_VERSION, retainedSchemaVersion)}. */
    int schemaVersionForSave() {
        return SchemaVersionLogic.versionToSave(retainedSchemaVersion);
    }

    /** Returns the epoch millis of the last Discord digest, or {@code 0} if not yet recorded (F9). */
    public long lastDigestEpochMillis() {
        return lastDigestEpochMillis;
    }

    /** Updates the last Discord digest timestamp (clamping negative values to 0) and marks dirty when changed (F9). */
    public void setLastDigestEpochMillis(long epochMillis) {
        long normalized = Math.max(0L, epochMillis);
        if (this.lastDigestEpochMillis != normalized) {
            this.lastDigestEpochMillis = normalized;
            setDirty();
        }
    }

    /** Returns {@code true} when neither player entries nor world-first records exist. */
    public boolean isEmpty() {
        return players.isEmpty() && worldFirsts.isEmpty();
    }

    /**
     * F10: serializes this in-memory {@link MilestoneData} into the exact root NBT wrapper
     * ({@code {"data": ..., "DataVersion": ...}}) written by vanilla {@code SavedDataStorage}.
     * Does not clear {@link #isDirty()}.
     */
    CompoundTag toSavedDataFileTag(@Nullable HolderLookup.Provider registries) {
        DynamicOps<Tag> ops = registries != null
                ? registries.createSerializationContext(NbtOps.INSTANCE)
                : NbtOps.INSTANCE;
        CompoundTag root = new CompoundTag();
        root.put("data", CODEC.encodeStart(ops, this).getOrThrow());
        SharedConstants.tryDetectVersion();
        NbtUtils.addCurrentDataVersion(root);
        return root;
    }

    public static MilestoneData get(MinecraftServer server) {
        var storage = server.getDataStorage();
        MilestoneData data = storage.get(TYPE);
        if (data == null) {
            Path newFormatFile = server.getWorldPath(LevelResource.ROOT)
                    .resolve("data/" + ItemMilestoneLog.MOD_ID + "/milestones.dat");
            if (Files.isRegularFile(newFormatFile)) {
                // New-format data exists but failed to load: importing the legacy file now would resurrect stale records.
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] The new-format data file {} exists but could not be loaded; "
                        + "the legacy data in {} is NOT being re-imported (to avoid resurrecting stale records). "
                        + "Starting with empty data.", newFormatFile, LEGACY_FILE);
                data = new MilestoneData();
            } else {
                data = readLegacy(server.getWorldPath(LevelResource.ROOT).resolve(LEGACY_FILE));
                if (data == null) {
                    data = new MilestoneData();
                } else {
                    data.setDirty();
                    ItemMilestoneLog.LOGGER.info("[ItemMilestoneLog] Imported the records of {} player(s) from {}",
                            data.players.size(), LEGACY_FILE);
                }
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
            if (!root.contains("data")) {
                return null;
            }
            CompoundTag dataTag = root.getCompoundOrEmpty("data");
            if (dataTag.isEmpty() && !root.getCompound("data").isPresent()) {
                return null;
            }
            return CODEC.parse(NbtOps.INSTANCE, dataTag)
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
    public WorldFirstRecord worldFirstOrNull(TargetKey key) {
        return worldFirsts.get(key);
    }

    public Map<TargetKey, WorldFirstRecord> worldFirsts() {
        return Collections.unmodifiableMap(worldFirsts);
    }

    public boolean recordWorldFirstIfAbsent(TargetKey key, UUID playerUuid, String playerName, MilestoneRecord record) {
        if (worldFirsts.containsKey(key)) {
            return false;
        }
        worldFirsts.put(key, new WorldFirstRecord(
                key.itemId(),
                key.count(),
                playerUuid,
                playerName != null ? playerName : "",
                record.playTicks(),
                record.day(),
                record.epochMillis()
        ));
        setDirty();
        return true;
    }

    public boolean removeWorldFirst(TargetKey key) {
        if (worldFirsts.remove(key) != null) {
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

    /**
     * Replaces the stored world-first map with {@code updated} and marks the data dirty
     * (used when merging imported world firsts in F31).
     */
    void replaceWorldFirsts(Map<TargetKey, WorldFirstRecord> updated) {
        worldFirsts.clear();
        worldFirsts.putAll(updated);
        setDirty();
    }

    private Map<String, PlayerData> playersByStringKey() {
        if (players.isEmpty()) {
            return Map.of();
        }
        Map<String, PlayerData> map = new LinkedHashMap<>(players.size());
        for (Map.Entry<UUID, PlayerData> e : players.entrySet()) {
            map.put(e.getKey().toString(), e.getValue());
        }
        return map;
    }

    private Map<String, Map<String, WorldFirstEntry>> worldFirstsAsNestedMap() {
        if (worldFirsts.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, WorldFirstEntry>> nested = new LinkedHashMap<>();
        for (Map.Entry<TargetKey, WorldFirstRecord> e : worldFirsts.entrySet()) {
            TargetKey key = e.getKey();
            WorldFirstRecord wf = e.getValue();
            nested.computeIfAbsent(key.itemId(), k -> new LinkedHashMap<>())
                    .put(String.valueOf(key.count()), new WorldFirstEntry(
                            wf.playerUuid().toString(),
                            wf.playerName(),
                            wf.playTicks(),
                            wf.day(),
                            wf.epochMillis()
                    ));
        }
        return nested;
    }

    private static MilestoneData fromCodec(Map<String, PlayerData> rawPlayers,
                                           Map<String, Map<String, WorldFirstEntry>> rawFirsts,
                                           long lastDigestEpoch,
                                           int rawSchemaVersion) {
        MilestoneData data = new MilestoneData();
        // F-140-1 (N88): missing = legacy data (0); future versions are preserved on save.
        SchemaVersionLogic.LoadResult schemaVersion = SchemaVersionLogic.fromStoredVersion(rawSchemaVersion);
        String schemaWarning = SchemaVersionLogic.warningMessage(rawSchemaVersion, schemaVersion);
        if (schemaWarning != null) {
            ItemMilestoneLog.LOGGER.warn(schemaWarning);
        }
        data.retainedSchemaVersion = schemaVersion.retainedVersion();
        MilestoneLoadReport.recordLoadedSchemaVersion(data.retainedSchemaVersion);
        if (schemaVersion.status() == SchemaVersionLogic.Status.INVALID) {
            MilestoneLoadReport.recordElementSkip();
        }
        if (lastDigestEpoch > 0L) {
            data.lastDigestEpochMillis = lastDigestEpoch;
        } else if (lastDigestEpoch < 0L) {
            MilestoneLoadReport.recordElementSkip();
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping negative lastDigestEpoch value {} in SavedData", lastDigestEpoch);
        }
        for (Map.Entry<String, PlayerData> e : rawPlayers.entrySet()) {
            UUID uuid;
            try {
                uuid = UUID.fromString(e.getKey());
            } catch (IllegalArgumentException ignored) {
                MilestoneLoadReport.recordElementSkip();
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping player entry with invalid UUID '{}' in SavedData", e.getKey());
                continue;
            }
            data.players.put(uuid, e.getValue());
        }
        for (Map.Entry<String, Map<String, WorldFirstEntry>> itemEntry : rawFirsts.entrySet()) {
            String itemId = itemEntry.getKey();
            if (!TargetKey.isValidItemId(itemId)) {
                MilestoneLoadReport.recordElementSkip();
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid worldFirst item key '{}' in SavedData", itemId);
                continue;
            }
            for (Map.Entry<String, WorldFirstEntry> countEntry : itemEntry.getValue().entrySet()) {
                int count = TargetKey.tryParseCount(countEntry.getKey());
                if (count < TargetKey.MIN_COUNT) {
                    MilestoneLoadReport.recordElementSkip();
                    ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid worldFirst count '{}' for '{}' in SavedData",
                            countEntry.getKey(), itemId);
                    continue;
                }
                WorldFirstEntry wf = countEntry.getValue();
                if (wf == null) {
                    MilestoneLoadReport.recordElementSkip();
                    ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping null worldFirst for '{}#{}' in SavedData",
                            itemId, countEntry.getKey());
                    continue;
                }
                if (wf.ticks() < 0) {
                    MilestoneLoadReport.recordElementSkip();
                    ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping worldFirst '{}#{}' with negative ticks {} in SavedData",
                            itemId, countEntry.getKey(), wf.ticks());
                    continue;
                }
                UUID uuid;
                try {
                    uuid = UUID.fromString(wf.uuid());
                } catch (IllegalArgumentException ignored) {
                    MilestoneLoadReport.recordElementSkip();
                    ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping worldFirst '{}#{}' with invalid player UUID '{}' in SavedData",
                            itemId, countEntry.getKey(), wf.uuid());
                    continue;
                }
                TargetKey key = new TargetKey(itemId, count);
                data.worldFirsts.put(key, new WorldFirstRecord(
                        itemId, count, uuid, wf.name(), wf.ticks(), wf.day(), wf.epoch()));
            }
        }
        return data;
    }
}
