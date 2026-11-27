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
import java.util.Optional;
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
        /** F-140-12 (D-140-8): per-preset G-C state ({@code autoApplied} + {@code lastSeenVersion}). */
        public final Map<String, PresetState> presetState = new LinkedHashMap<>();
        /** F-150-31 (N155): optional per-target metadata, keyed by {@code TargetKey#toEncodedString()}. */
        public final Map<String, TargetMeta> targetMeta = new LinkedHashMap<>();
        /** F-150-44 (N116): personal destination enum name; FOLLOW_SERVER means "use the server default". */
        public String progressAlertDestination = "FOLLOW_SERVER";

        /** F-150-49 (N114): personal 0..100 volume, or -1 to follow the server default. */
        public int soundVolume = SoundVolumeLogic.FOLLOW_SERVER;
        /** F-150-50 (N121): suppresses every notification sound packet (not chat/Discord). */
        public boolean muteNotificationSounds = false;

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
     * Wraps a codec so a malformed value records one element skip and decodes to {@code fallback}
     * instead of failing its enclosing record. The old strict field codecs could make one bad
     * field drop a whole player, or the whole SavedData; the lenient wrappers keep every valid
     * sibling and make F-140-2 evacuate the original file (#110/#130).
     */
    private static <T> Codec<T> lenient(String field, Codec<T> codec, T fallback) {
        return new Codec<>() {
            @Override
            public <U> DataResult<Pair<T, U>> decode(DynamicOps<U> ops, U input) {
                DataResult<Pair<T, U>> result = codec.decode(ops, input);
                if (result.isError()) {
                    MilestoneLoadReport.recordElementSkip();
                    ItemMilestoneLog.LOGGER.warn(
                            "[ItemMilestoneLog] Skipping malformed '{}' value in SavedData; using the default", field);
                    return DataResult.success(new Pair<>(fallback, input));
                }
                return result;
            }

            @Override
            public <U> DataResult<U> encode(T input, DynamicOps<U> ops, U prefix) {
                return codec.encode(input, ops, prefix);
            }
        };
    }

    /**
     * Decodes all three record formats under {@code records[itemId]}:
     * 1.0.0 plain int (count = 1), early 1.0.1 compound {ticks, day, epoch} (count = 1),
     * and quantity map { "<count>": {ticks, day, epoch}, ... }.
     */
    private static final Codec<Map<String, MilestoneRecord>> ITEM_RECORDS_CODEC = lenient("records item",
            Codec.either(
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
            ),
            // An item whose value matches none of the formats decodes to no records instead of failing the player.
            Map.of());

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

    /** F-150-38 (N167): one past world-first holder ({@code uuid, name, playTicks, epochMillis}). */
    private static final Codec<HolderEntry> HOLDER_ENTRY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("uuid").forGetter(HolderEntry::uuid),
            Codec.STRING.optionalFieldOf("name", "").forGetter(HolderEntry::name),
            Codec.INT.fieldOf("playTicks").forGetter(HolderEntry::playTicks),
            Codec.LONG.optionalFieldOf("epochMillis", 0L).forGetter(HolderEntry::epochMillis)
    ).apply(instance, HolderEntry::new));

    /** Lenient per-element wrapper: a malformed holder decodes to INVALID and is skipped by the factory. */
    private static final Codec<HolderEntry> LENIENT_HOLDER_ENTRY_CODEC =
            HOLDER_ENTRY_CODEC.orElse(HolderEntry.INVALID);

    /** F-150-38 (N167): emit the optional history map directly, including empty maps. */
    private static Map<String, List<HolderEntry>> encodeFirstsHolderHistory(MilestoneData data) {
        return new LinkedHashMap<>(data.firstsHolderHistory);
    }

    /**
     * Decodes both early 1.0.1 worldFirsts (itemId -> entry, count = 1)
     * and quantity worldFirsts (itemId -> { "<count>": entry, ... }).
     */
    private static final Codec<Map<String, WorldFirstEntry>> ITEM_WORLD_FIRSTS_CODEC = lenient("worldFirst item",
            Codec.either(
                    WORLD_FIRST_CODEC,
                    Codec.unboundedMap(Codec.STRING, LENIENT_WORLD_FIRST_CODEC)
            ).xmap(
                    either -> either.map(
                            single -> Map.of("1", single),
                            countMap -> countMap
                    ),
                    Either::right
            ),
            // An item whose value matches neither format decodes to no entries instead of failing the file.
            Map.of());

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

    /** Lenient per-entry wrapper for string list elements: non-string values decode to "" and are skipped. */
    private static final Codec<String> LENIENT_STRING_CODEC = Codec.STRING.orElse("");

    /** Lenient per-entry wrapper for {@code completedPresets} elements: non-string values decode to "" and are skipped. */
    private static final Codec<String> LENIENT_COMPLETED_PRESET_CODEC = Codec.STRING.orElse("");

    /** F-140-12 (D-140-8): one {@code presetState} value ({@code autoApplied} + {@code lastSeenVersion}). */
    private static final Codec<PresetState> PRESET_STATE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("autoApplied", false).forGetter(PresetState::autoApplied),
            Codec.INT.optionalFieldOf("lastSeenVersion", 0).forGetter(PresetState::lastSeenVersion)
    ).apply(instance, PresetState::new));

    /** Lenient per-entry wrapper: a malformed state decodes to {@code lastSeenVersion == -1} and is skipped. */
    private static final Codec<PresetState> LENIENT_PRESET_STATE_CODEC =
            PRESET_STATE_CODEC.orElse(new PresetState(false, -1));

    private static Map<String, PresetState> encodePresetState(PlayerData pd) {
        return PresetStateLogic.copyOf(pd.presetState);
    }

    /** F-150-31 (N155): one optional target metadata value (priority + label + pinned). */
    private static final Codec<TargetMeta> TARGET_META_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("priority", 0).forGetter(TargetMeta::priority),
            Codec.STRING.optionalFieldOf("label", "").forGetter(TargetMeta::label),
            Codec.BOOL.optionalFieldOf("pinned", false).forGetter(TargetMeta::pinned)
    ).apply(instance, TargetMeta::new));

    /** Lenient per-entry wrapper: a malformed value decodes to the INVALID sentinel and is skipped. */
    private static final Codec<TargetMeta> LENIENT_TARGET_META_CODEC =
            TARGET_META_CODEC.orElse(TargetMeta.INVALID);

    /** Mirrors {@link #encodePresetState}: emit the map directly, including empty maps. */
    private static Map<String, TargetMeta> encodeTargetMeta(PlayerData pd) {
        return new LinkedHashMap<>(pd.targetMeta);
    }

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

    private static final Codec<PlayerData> PLAYER_DATA_BASE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            lenient("targets", Codec.list(LENIENT_STRING_CODEC), List.of())
                    .optionalFieldOf("targets", List.of())
                    .forGetter(MilestoneData::encodeTargets),
            lenient("records", Codec.unboundedMap(Codec.STRING, ITEM_RECORDS_CODEC), Map.of())
                    .optionalFieldOf("records", Map.of())
                    .forGetter(MilestoneData::encodeRecords),
            lenient("carriedFromCreative", Codec.list(LENIENT_STRING_CODEC), List.of())
                    .optionalFieldOf("carriedFromCreative", List.of())
                    .forGetter(pd -> pd.carriedFromCreative.isEmpty() ? List.of() : new ArrayList<>(pd.carriedFromCreative)),
            lenient("lastKnownName", Codec.STRING, "")
                    .optionalFieldOf("lastKnownName", "")
                    .forGetter(pd -> pd.lastKnownName != null ? pd.lastKnownName : ""),
            lenient("firstJoinHintShown", Codec.BOOL, false)
                    .optionalFieldOf("firstJoinHintShown", false)
                    .forGetter(pd -> pd.firstJoinHintShown),
            lenient("muteBroadcasts", Codec.BOOL, false)
                    .optionalFieldOf("muteBroadcasts", false)
                    .forGetter(pd -> pd.muteBroadcasts),
            lenient("muteProgressAlerts", Codec.BOOL, false)
                    .optionalFieldOf("muteProgressAlerts", false)
                    .forGetter(pd -> pd.muteProgressAlerts),
            lenient("excludeFromDiscord", Codec.BOOL, false)
                    .optionalFieldOf("excludeFromDiscord", false)
                    .forGetter(pd -> pd.excludeFromDiscord),
            lenient("hideFromLookup", Codec.BOOL, false)
                    .optionalFieldOf("hideFromLookup", false)
                    .forGetter(pd -> pd.hideFromLookup),
            lenient("lastSeen", Codec.LONG, 0L)
                    .optionalFieldOf("lastSeen", 0L)
                    .forGetter(pd -> pd.lastSeen > 0L ? pd.lastSeen : 0L),
            lenient("progressNotified",
                    Codec.unboundedMap(Codec.STRING, LENIENT_PROGRESS_THRESHOLD_CODEC), Map.of())
                    .optionalFieldOf("progressNotified", Map.of())
                    .forGetter(MilestoneData::encodeProgressNotified),
            lenient("completedPresets", Codec.list(LENIENT_COMPLETED_PRESET_CODEC), List.of())
                    .optionalFieldOf("completedPresets", List.of())
                    .forGetter(pd -> pd.completedPresets.isEmpty() ? List.of() : new ArrayList<>(pd.completedPresets)),
            lenient("presetState",
                    Codec.unboundedMap(Codec.STRING, LENIENT_PRESET_STATE_CODEC), Map.of())
                    .optionalFieldOf("presetState", Map.of())
                    .forGetter(MilestoneData::encodePresetState),
            lenient("targetMeta",
                    Codec.unboundedMap(Codec.STRING, LENIENT_TARGET_META_CODEC), Map.of())
                    .optionalFieldOf("targetMeta", Map.of())
                    .forGetter(MilestoneData::encodeTargetMeta),
            lenient("progressAlertDestination", Codec.STRING, "FOLLOW_SERVER")
                    .optionalFieldOf("progressAlertDestination")
                    .forGetter(pd -> NotificationDestinationLogic.isValidChoice(pd.progressAlertDestination)
                            && !"FOLLOW_SERVER".equals(pd.progressAlertDestination)
                            ? Optional.of(pd.progressAlertDestination) : Optional.empty())
    ).apply(instance, (var targets, var records, var carriedFromCreative, var lastKnownName, var firstJoinHintShown, var muteBroadcasts, var muteProgressAlerts, var excludeFromDiscord, var hideFromLookup, var lastSeen, var progressNotified, var completedPresets, var presetState, var targetMeta, var progressAlertDestination) -> {
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
        for (String raw : carriedFromCreative) {
            if (TargetKey.isValidItemId(raw)) {
                pd.carriedFromCreative.add(raw);
            } else {
                MilestoneLoadReport.recordElementSkip();
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid carriedFromCreative entry '{}' in SavedData", raw);
            }
        }
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
        for (Map.Entry<String, PresetState> stateEntry : presetState.entrySet()) {
            String presetId = stateEntry.getKey();
            PresetState state = stateEntry.getValue();
            if (!Presets.isValidId(presetId) || state == null || state.lastSeenVersion() < 0) {
                MilestoneLoadReport.recordElementSkip();
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid presetState entry '{}' in SavedData", presetId);
                continue;
            }
            pd.presetState.put(presetId, state);
        }
        for (Map.Entry<String, TargetMeta> metaEntry : targetMeta.entrySet()) {
            String encodedKey = metaEntry.getKey();
            TargetMeta stored = metaEntry.getValue();
            // LENIENT_TARGET_META_CODEC maps malformed values to INVALID; also re-validate
            // decoded values so one bad element never fails the player or its siblings.
            TargetMeta meta = stored == null ? TargetMeta.INVALID : TargetMeta.of(
                    stored.priority(), stored.label(), stored.pinned());
            if (meta == TargetMeta.INVALID) {
                MilestoneLoadReport.recordElementSkip();
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid targetMeta entry '{}' in SavedData",
                        encodedKey);
                continue;
            }
            pd.targetMeta.put(encodedKey, meta);
        }
        String storedDestination = progressAlertDestination.orElse("FOLLOW_SERVER");
        if (!NotificationDestinationLogic.isValidChoice(storedDestination)) {
            MilestoneLoadReport.recordElementSkip();
        }
        pd.progressAlertDestination = NotificationDestinationLogic.parseChoice(storedDestination).name();
        return pd;
    }));

    /**
     * F-150-49/F-150-50: two optional, lenient player fields added as a thin wrapper instead of
     * growing {@code RecordCodecBuilder.group} past its 16-field limit. The on-disk shape is still
     * two plain keys on the player compound, and no schemaVersion change is needed.
     */
    private static <T> int readSoundVolume(DynamicOps<T> ops, T input) {
        DataResult<T> raw = ops.get(input, "soundVolume");
        if (raw.result().isEmpty()) {
            return SoundVolumeLogic.FOLLOW_SERVER;
        }
        Integer value = raw.flatMap(v -> Codec.INT.parse(ops, v)).result().orElse(null);
        if (value == null || !SoundVolumeLogic.isStoredPersonal(value)) {
            MilestoneLoadReport.recordElementSkip();
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping malformed soundVolume in SavedData");
            return SoundVolumeLogic.FOLLOW_SERVER;
        }
        return value;
    }

    private static <T> boolean readMuteNotificationSounds(DynamicOps<T> ops, T input) {
        DataResult<T> raw = ops.get(input, "muteNotificationSounds");
        if (raw.result().isEmpty()) {
            return false;
        }
        Boolean value = raw.flatMap(v -> Codec.BOOL.parse(ops, v)).result().orElse(null);
        if (value == null) {
            MilestoneLoadReport.recordElementSkip();
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping malformed muteNotificationSounds in SavedData");
            return false;
        }
        return value;
    }

    private static final Codec<PlayerData> PLAYER_DATA_CODEC = new Codec<>() {
        @Override
        public <T> DataResult<Pair<PlayerData, T>> decode(DynamicOps<T> ops, T input) {
            return PLAYER_DATA_BASE_CODEC.decode(ops, input).map(decoded -> {
                PlayerData pd = decoded.getFirst();
                pd.soundVolume = readSoundVolume(ops, input);
                pd.muteNotificationSounds = readMuteNotificationSounds(ops, input);
                return decoded;
            });
        }

        @Override
        public <T> DataResult<T> encode(PlayerData pd, DynamicOps<T> ops, T prefix) {
            T base = PLAYER_DATA_BASE_CODEC.encode(pd, ops, prefix).result().orElse(prefix);
            if (SoundVolumeLogic.isStoredPersonal(pd.soundVolume)
                    && pd.soundVolume != SoundVolumeLogic.FOLLOW_SERVER) {
                base = ops.set(base, "soundVolume", ops.createInt(pd.soundVolume));
            }
            if (pd.muteNotificationSounds) {
                base = ops.set(base, "muteNotificationSounds", ops.createBoolean(true));
            }
            return DataResult.success(base);
        }
    };

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
            lenient("lastDigestEpoch", Codec.LONG, 0L)
                    .optionalFieldOf("lastDigestEpoch", 0L)
                    .forGetter(d -> d.lastDigestEpochMillis > 0L ? d.lastDigestEpochMillis : 0L),
            // F-140-1 (N88): always written; never downgrade a value loaded from a newer release.
            LENIENT_SCHEMA_VERSION_CODEC
                    .optionalFieldOf("schemaVersion", SchemaVersionLogic.ABSENT_SCHEMA_VERSION)
                    .forGetter(MilestoneData::schemaVersionForSave),
            // F-150-38 (N167): optional root field; missing = empty map, no schemaVersion bump,
            // and the export/import JSON and backup formats are left untouched.
            lenient("firstsHolderHistory",
                    Codec.unboundedMap(Codec.STRING, Codec.list(LENIENT_HOLDER_ENTRY_CODEC)), Map.of())
                    .optionalFieldOf("firstsHolderHistory", Map.of())
                    .forGetter(MilestoneData::encodeFirstsHolderHistory)
    ).apply(instance, MilestoneData::fromCodec));

    /**
     * Accepts the buggy 1.21.5-1.21.11 payload that manually wrapped {@code data.save(...)} in a
     * second {@code "data"} compound (#99). Vanilla already strips the outer SavedData wrapper, so
     * an affected file reaches this decoder as {@code {"data": {players, worldFirsts, ...}}}; a
     * correctly written payload has the root fields directly and is returned unchanged. Kept
     * idempotent so a doubly nested file is recovered as well.
     */
    static CompoundTag unwrapDoubleWrappedData(CompoundTag tag) {
        CompoundTag current = tag;
        while (current.contains("data")
                && !current.contains("players")
                && !current.contains("worldFirsts")
                && current.get("data") instanceof CompoundTag nested) {
            current = nested;
        }
        return current;
    }

    /**
     * F-140-2 (N141): wraps {@link #RAW_CODEC} so every decode has an active
     * {@link MilestoneLoadReport}, including nested per-player/per-record decoders that run
     * before {@link #fromCodec}. The finished report is attached to the decoded instance.
     */
    public static final Codec<MilestoneData> CODEC = new Codec<>() {
        @Override
        @SuppressWarnings("unchecked")
        public <T> DataResult<Pair<MilestoneData, T>> decode(DynamicOps<T> ops, T input) {
            MilestoneLoadReport report = MilestoneLoadReport.begin();
            try {
                T effectiveInput = input;
                if (input instanceof CompoundTag compound) {
                    CompoundTag unwrapped = unwrapDoubleWrappedData(compound);
                    if (unwrapped != compound) {
                        effectiveInput = (T) unwrapped;
                    }
                }
                DataResult<Pair<MilestoneData, T>> result = RAW_CODEC.decode(ops, effectiveInput);
                // #130: vanilla keeps a partial result (resultOrPartial(...).orElse(null)), so the
                // report must be attached to that result too. Any error means at least one element
                // was dropped; record it so F-140-2 evacuates the original before the next save.
                if (result.isError()) {
                    MilestoneLoadReport.recordElementSkip();
                }
                result.resultOrPartial(error -> ItemMilestoneLog.LOGGER.warn(
                                "[ItemMilestoneLog] Problem decoding SavedData: {}", error))
                        .ifPresent(pair -> pair.getFirst().loadReport = report);
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
    /** F-150-38 (N167): past world-first holders per encoded target key, oldest first. */
    private final Map<String, List<HolderEntry>> firstsHolderHistory = new LinkedHashMap<>();
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

    /** F-150-38 (N167): past holders for one encoded target key (oldest first). */
    public List<HolderEntry> holderHistory(String encodedKey) {
        List<HolderEntry> stored = firstsHolderHistory.get(encodedKey);
        return stored == null ? List.of() : Collections.unmodifiableList(stored);
    }

    /** F-150-38 (N167): the whole optional history map (encoded target key -> oldest-first list). */
    public Map<String, List<HolderEntry>> firstsHolderHistory() {
        return Collections.unmodifiableMap(firstsHolderHistory);
    }

    /**
     * F-150-38 (N167): appends the outgoing holder when a world first is removed or cleared.
     * This is the only holder-change path: normal records are first-writer-wins and never
     * replace the holder (D-150-92 / pitfall 12).
     */
    private void appendPastHolder(TargetKey key, WorldFirstRecord outgoing) {
        if (key == null || outgoing == null) {
            return;
        }
        HolderEntry entry = HolderEntry.of(
                outgoing.playerUuid().toString(), outgoing.playerName(),
                outgoing.playTicks(), outgoing.epochMillis());
        if (!entry.isValid()) {
            return;
        }
        String encodedKey = key.toEncodedString();
        firstsHolderHistory.put(encodedKey,
                FirstsHolderHistory.append(firstsHolderHistory.get(encodedKey), entry));
        setDirty();
    }

    public boolean removeWorldFirst(TargetKey key) {
        WorldFirstRecord outgoing = worldFirsts.get(key);
        if (outgoing != null) {
            appendPastHolder(key, outgoing);
        }
        if (worldFirsts.remove(key) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    public int clearWorldFirsts() {
        int count = worldFirsts.size();
        if (count > 0) {
            for (Map.Entry<TargetKey, WorldFirstRecord> e : worldFirsts.entrySet()) {
                appendPastHolder(e.getKey(), e.getValue());
            }
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
                                           int rawSchemaVersion,
                                           Map<String, List<HolderEntry>> rawHolderHistory) {
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
        // F-150-38 (N167): lenient per-element decode. Invalid UUIDs / negative ticks were
        // mapped to the INVALID sentinel by LENIENT_HOLDER_ENTRY_CODEC and are skipped here so
        // one bad entry never fails its key or its sibling keys.
        for (Map.Entry<String, List<HolderEntry>> historyEntry : rawHolderHistory.entrySet()) {
            String encodedKey = historyEntry.getKey();
            List<HolderEntry> rawList = historyEntry.getValue();
            if (rawList == null || TargetKey.tryParseEncoded(encodedKey) == null) {
                if (rawList != null) {
                    MilestoneLoadReport.recordElementSkip();
                    ItemMilestoneLog.LOGGER.warn(
                            "[ItemMilestoneLog] Skipping invalid firstsHolderHistory key '{}' in SavedData", encodedKey);
                }
                continue;
            }
            List<HolderEntry> accepted = new ArrayList<>();
            for (HolderEntry stored : rawList) {
                HolderEntry entry = stored == null ? HolderEntry.INVALID : HolderEntry.of(
                        stored.uuid(), stored.name(), stored.playTicks(), stored.epochMillis());
                if (!entry.isValid()) {
                    MilestoneLoadReport.recordElementSkip();
                    ItemMilestoneLog.LOGGER.warn(
                            "[ItemMilestoneLog] Skipping invalid firstsHolderHistory element for '{}' in SavedData",
                            encodedKey);
                    continue;
                }
                accepted.add(entry);
                if (accepted.size() >= FirstsHolderHistory.HISTORY_LIMIT) {
                    break;
                }
            }
            if (!accepted.isEmpty()) {
                data.firstsHolderHistory.put(encodedKey, List.copyOf(accepted));
            }
        }
        return data;
    }
}
