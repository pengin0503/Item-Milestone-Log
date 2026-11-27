package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.api.WorldFirstRecord;
import com.pengin0503.itemmilestonelog.compat.McCompat;
import com.pengin0503.itemmilestonelog.compat.NbtCompat;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
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
 * Per-world storage: for each player, their personal target items (with quantities)
 * and the milestone records at which each target was first obtained, plus server-wide
 * world-first records.
 *
 * Stored in the overworld's data storage as "itemmilestonelog_data",
 * so records survive restarts and are kept per world (per server).
 */
public class MilestoneData extends SavedData {
    public static final String DATA_NAME = ItemMilestoneLog.MOD_ID + "_data";

    /** Per-player state. */
    public static final class PlayerData {
        /** Personal targets (item + quantity), in the order they were added. */
        public final Set<TargetKey> targets = new LinkedHashSet<>();
        /** Target (item + quantity) -> milestone record when first obtained. */
        public final Map<TargetKey, MilestoneRecord> records = new LinkedHashMap<>();
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
        /** Whether this player has opted out of quantity-target progress alerts. */
        public boolean muteProgressAlerts = false;
        /** Whether this player has opted out of having their milestones posted to Discord. */
        public boolean excludeFromDiscord = false;
        /** Whether this player hides their records from non-privileged players' lookups (F14). */
        public boolean hideFromLookup = false;
        /** Epoch millis when this player last logged in or out (0 = unknown, F37). */
        public long lastSeen = 0L;
        /** Target (item + quantity) -> highest progress alert threshold already notified (0 possible as baseline). */
        public final Map<TargetKey, Integer> progressNotified = new LinkedHashMap<>();
        /** Preset IDs that this player has already completed (announced at most once unless resetall). */
        public final Set<String> completedPresets = new LinkedHashSet<>();
        /**
         * F-140-12 (D-140-8): per-preset G-C state ({@code autoApplied} + {@code lastSeenVersion}).
         * Kept separate from {@link #completedPresets} so completion keeps its original meaning.
         */
        public final Map<String, PresetState> presetState = new LinkedHashMap<>();
        /** F-150-31 (N155): optional per-target metadata, keyed by {@code TargetKey#toEncodedString()}. */
        public final Map<String, TargetMeta> targetMeta = new LinkedHashMap<>();

        /** Whether this player hides their records from lookups by non-privileged other players (F14). */
        public boolean isHiddenFromLookup() {
            return hideFromLookup;
        }
    }

    private final Map<UUID, PlayerData> players = new LinkedHashMap<>();
    private final Map<TargetKey, WorldFirstRecord> worldFirsts = new LinkedHashMap<>();
    /** Epoch millis of the last Discord digest (0 = never initialized/sent, F9). */
    private long lastDigestEpochMillis = 0L;
    /** Schema version retained from the last load; 0 when the field was missing or invalid (F-140-1). */
    private int retainedSchemaVersion = 0;
    /** Element-skip/schema accounting for F-140-2 (N141); set by {@link #load(CompoundTag, HolderLookup.Provider)}. */
    @Nullable
    private MilestoneLoadReport loadReport;

    /** Load accounting for the most recent load of this instance, or {@code null} if not loaded. */
    @Nullable
    MilestoneLoadReport loadReport() {
        return loadReport;
    }

    public static MilestoneData get(MinecraftServer server) {
        return McCompat.data().getOrCreate(server.overworld().getDataStorage());
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

    /** SchemaVersion value retained from the last load (0 when the field was missing or invalid). */
    int retainedSchemaVersion() {
        return retainedSchemaVersion;
    }

    /** SchemaVersion to write on save: {@code max(CURRENT_SCHEMA_VERSION, retainedSchemaVersion)}. */
    int schemaVersionForSave() {
        return SchemaVersionLogic.versionToSave(retainedSchemaVersion);
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

    // ------------------------------------------------------------------ NBT

    @Nullable
    private static MilestoneRecord readRecordTag(CompoundTag recTag) {
        if (!McCompat.nbt().containsTyped(recTag, "ticks", NbtCompat.ANY_NUMERIC)) {
            return null;
        }
        int ticks = McCompat.nbt().getInt(recTag, "ticks");
        if (ticks < 0) {
            return null;
        }
        long day = McCompat.nbt().containsTyped(recTag, "day", NbtCompat.ANY_NUMERIC)
                ? McCompat.nbt().getLong(recTag, "day")
                : MilestoneRecord.UNKNOWN_DAY;
        long epoch = McCompat.nbt().containsTyped(recTag, "epoch", NbtCompat.ANY_NUMERIC)
                ? McCompat.nbt().getLong(recTag, "epoch")
                : MilestoneRecord.UNKNOWN_EPOCH;
        return new MilestoneRecord(ticks, day, epoch);
    }

    private static void readWorldFirstTag(Map<TargetKey, WorldFirstRecord> out, String itemId, int count, CompoundTag wfTag) {
        if (!McCompat.nbt().containsTyped(wfTag, "ticks", NbtCompat.ANY_NUMERIC)) {
            MilestoneLoadReport.recordElementSkip();
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping worldFirst '{}' without ticks in SavedData", itemId);
            return;
        }
        int ticks = McCompat.nbt().getInt(wfTag, "ticks");
        if (ticks < 0) {
            MilestoneLoadReport.recordElementSkip();
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping worldFirst '{}' with negative ticks {} in SavedData", itemId, ticks);
            return;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(McCompat.nbt().getString(wfTag, "uuid"));
        } catch (IllegalArgumentException ignored) {
            MilestoneLoadReport.recordElementSkip();
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping worldFirst '{}' with invalid player UUID '{}' in SavedData",
                    itemId, McCompat.nbt().getString(wfTag, "uuid"));
            return;
        }
        String name = McCompat.nbt().getString(wfTag, "name");
        long day = McCompat.nbt().containsTyped(wfTag, "day", NbtCompat.ANY_NUMERIC)
                ? McCompat.nbt().getLong(wfTag, "day")
                : MilestoneRecord.UNKNOWN_DAY;
        long epoch = McCompat.nbt().containsTyped(wfTag, "epoch", NbtCompat.ANY_NUMERIC)
                ? McCompat.nbt().getLong(wfTag, "epoch")
                : MilestoneRecord.UNKNOWN_EPOCH;
        TargetKey key = new TargetKey(itemId, count);
        out.put(key, new WorldFirstRecord(itemId, count, uuid, name, ticks, day, epoch));
    }

    /**
     * Accepts the buggy 1.21.5-1.21.11 payload that manually wrapped {@code save(...)} in a
     * second {@code "data"} compound (#99). Vanilla already strips the outer SavedData wrapper,
     * so an affected file reaches {@link #load} as {@code {"data": {players, worldFirsts, ...}}};
     * a correctly written payload has the root fields directly and is returned unchanged.
     */
    static CompoundTag unwrapDoubleWrappedData(CompoundTag tag) {
        CompoundTag current = tag;
        while (McCompat.nbt().containsTyped(current, "data", Tag.TAG_COMPOUND)
                && !McCompat.nbt().contains(current, "players")
                && !McCompat.nbt().contains(current, "worldFirsts")) {
            current = McCompat.nbt().getCompound(current, "data");
        }
        return current;
    }

    public static MilestoneData load(CompoundTag tag, HolderLookup.Provider provider) {
        MilestoneLoadReport report = MilestoneLoadReport.begin();
        try {
            MilestoneData data = loadInternal(tag, provider);
            data.loadReport = report;
            report.recordLoadedSchemaVersion(data.retainedSchemaVersion);
            return data;
        } finally {
            MilestoneLoadReport.end(report);
        }
    }

    private static MilestoneData loadInternal(CompoundTag tag, HolderLookup.Provider provider) {
        // #99: a buggy 1.21.5-1.21.11 DataCompatImpl wrote {"data": {"data": payload}} on
        // disk. Vanilla removes the outer wrapper before calling this method, so accept the
        // leftover inner wrapper instead of silently loading an empty world.
        tag = unwrapDoubleWrappedData(tag);
        MilestoneData data = new MilestoneData();
        CompoundTag all = McCompat.nbt().getCompound(tag, "players");
        for (String uuidKey : McCompat.nbt().keys(all)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidKey);
            } catch (IllegalArgumentException ignored) {
                MilestoneLoadReport.recordElementSkip();
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping player entry with invalid UUID '{}' in SavedData", uuidKey);
                continue;
            }
            CompoundTag entry = McCompat.nbt().getCompound(all, uuidKey);
            PlayerData pd = new PlayerData();
            ListTag targets = McCompat.nbt().getStringList(entry, "targets");
            for (Tag t : targets) {
                String raw = McCompat.nbt().tagAsString(t);
                TargetKey parsed = TargetKey.tryParseEncoded(raw);
                if (parsed != null) {
                    pd.targets.add(parsed);
                } else {
                    MilestoneLoadReport.recordElementSkip();
                    ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid target entry '{}' in SavedData", raw);
                }
            }
            CompoundTag records = McCompat.nbt().getCompound(entry, "records");
            for (String itemId : McCompat.nbt().keys(records)) {
                if (!TargetKey.isValidItemId(itemId)) {
                    MilestoneLoadReport.recordElementSkip();
                    ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid record item key '{}' in SavedData", itemId);
                    continue;
                }
                if (McCompat.nbt().containsTyped(records, itemId, Tag.TAG_COMPOUND)) {
                    CompoundTag itemTag = McCompat.nbt().getCompound(records, itemId);
                    if (McCompat.nbt().containsTyped(itemTag, "ticks", NbtCompat.ANY_NUMERIC) || McCompat.nbt().containsTyped(itemTag, "day", NbtCompat.ANY_NUMERIC)
                            || McCompat.nbt().containsTyped(itemTag, "epoch", NbtCompat.ANY_NUMERIC)) {
                        // Backward compatibility: early 1.0.1 stored itemId -> {ticks, day, epoch} (count = 1).
                        MilestoneRecord rec = readRecordTag(itemTag);
                        if (rec != null) {
                            pd.records.put(TargetKey.of(itemId), rec);
                        } else {
                            MilestoneLoadReport.recordElementSkip();
                            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping record for '{}' with missing or negative ticks in SavedData",
                                    itemId);
                        }
                    } else {
                        // Quantity format: itemId -> { "<count>": {ticks, day, epoch}, ... }.
                        for (String countKey : McCompat.nbt().keys(itemTag)) {
                            int count = TargetKey.tryParseCount(countKey);
                            if (count < TargetKey.MIN_COUNT || !McCompat.nbt().containsTyped(itemTag, countKey, Tag.TAG_COMPOUND)) {
                                MilestoneLoadReport.recordElementSkip();
                                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid record count '{}' for '{}' in SavedData",
                                        countKey, itemId);
                                continue;
                            }
                            MilestoneRecord rec = readRecordTag(McCompat.nbt().getCompound(itemTag, countKey));
                            if (rec != null) {
                                pd.records.put(new TargetKey(itemId, count), rec);
                            } else {
                                MilestoneLoadReport.recordElementSkip();
                                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping record for '{}#{}' with missing or negative ticks in SavedData",
                                        itemId, countKey);
                            }
                        }
                    }
                } else if (McCompat.nbt().containsTyped(records, itemId, NbtCompat.ANY_NUMERIC)) {
                    // Backward compatibility: 1.0.0 stored records as plain integers (count = 1).
                    int ticks = McCompat.nbt().getInt(records, itemId);
                    if (ticks >= 0) {
                        pd.records.put(TargetKey.of(itemId), MilestoneRecord.legacy(ticks));
                    } else {
                        MilestoneLoadReport.recordElementSkip();
                        ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping legacy record for '{}' with negative ticks {} in SavedData",
                                itemId, ticks);
                    }
                } else {
                    // #148: a records[itemId] value that is neither a compound nor a number is
                    // one skipped element in the 26.x codec; keep the player and count the skip.
                    MilestoneLoadReport.recordElementSkip();
                    ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping record item '{}' with invalid value type in SavedData",
                            itemId);
                }
            }
            // #148: parity with the 26.x lenient codec: wrong container types and invalid
            // item ids are element skips (F-140-2), while valid siblings keep loading.
            if (McCompat.nbt().containsTyped(entry, "carriedFromCreative", Tag.TAG_LIST)) {
                Tag rawCarried = entry.get("carriedFromCreative");
                if (rawCarried instanceof ListTag carriedList) {
                    for (Tag t : carriedList) {
                        String rawId = McCompat.nbt().tagAsString(t);
                        if (TargetKey.isValidItemId(rawId)) {
                            pd.carriedFromCreative.add(rawId);
                        } else {
                            MilestoneLoadReport.recordElementSkip();
                            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid carriedFromCreative entry '{}' in SavedData",
                                    rawId);
                        }
                    }
                }
            } else if (McCompat.nbt().contains(entry, "carriedFromCreative")) {
                MilestoneLoadReport.recordElementSkip();
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping malformed carriedFromCreative tag in SavedData");
            }
            if (McCompat.nbt().containsTyped(entry, "lastKnownName", Tag.TAG_STRING)) {
                pd.lastKnownName = McCompat.nbt().getString(entry, "lastKnownName");
            } else if (McCompat.nbt().contains(entry, "lastKnownName")) {
                MilestoneLoadReport.recordElementSkip();
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping malformed lastKnownName tag in SavedData");
            }
            if (McCompat.nbt().containsTyped(entry, "firstJoinHintShown", NbtCompat.ANY_NUMERIC)) {
                pd.firstJoinHintShown = McCompat.nbt().getBoolean(entry, "firstJoinHintShown");
            } else if (McCompat.nbt().contains(entry, "firstJoinHintShown")) {
                MilestoneLoadReport.recordElementSkip();
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping malformed firstJoinHintShown tag in SavedData");
            }
            if (McCompat.nbt().containsTyped(entry, "muteBroadcasts", NbtCompat.ANY_NUMERIC)) {
                pd.muteBroadcasts = McCompat.nbt().getBoolean(entry, "muteBroadcasts");
            } else if (McCompat.nbt().contains(entry, "muteBroadcasts")) {
                MilestoneLoadReport.recordElementSkip();
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping malformed muteBroadcasts tag in SavedData");
            }
            if (McCompat.nbt().containsTyped(entry, "muteProgressAlerts", NbtCompat.ANY_NUMERIC)) {
                pd.muteProgressAlerts = McCompat.nbt().getBoolean(entry, "muteProgressAlerts");
            } else if (McCompat.nbt().contains(entry, "muteProgressAlerts")) {
                MilestoneLoadReport.recordElementSkip();
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping malformed muteProgressAlerts tag in SavedData");
            }
            if (McCompat.nbt().containsTyped(entry, "excludeFromDiscord", NbtCompat.ANY_NUMERIC)) {
                pd.excludeFromDiscord = McCompat.nbt().getBoolean(entry, "excludeFromDiscord");
            } else if (McCompat.nbt().contains(entry, "excludeFromDiscord")) {
                MilestoneLoadReport.recordElementSkip();
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping malformed excludeFromDiscord tag in SavedData");
            }
            if (McCompat.nbt().containsTyped(entry, "hideFromLookup", NbtCompat.ANY_NUMERIC)) {
                pd.hideFromLookup = McCompat.nbt().getBoolean(entry, "hideFromLookup");
            } else if (McCompat.nbt().contains(entry, "hideFromLookup")) {
                MilestoneLoadReport.recordElementSkip();
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping malformed hideFromLookup tag in SavedData");
            }
            if (McCompat.nbt().containsTyped(entry, "lastSeen", NbtCompat.ANY_NUMERIC)) {
                long lastSeen = McCompat.nbt().getLong(entry, "lastSeen");
                if (lastSeen > 0L) {
                    pd.lastSeen = lastSeen;
                } else if (lastSeen < 0L) {
                    MilestoneLoadReport.recordElementSkip();
                    ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping negative lastSeen value {} in SavedData", lastSeen);
                }
            } else if (McCompat.nbt().contains(entry, "lastSeen")) {
                MilestoneLoadReport.recordElementSkip();
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping malformed lastSeen tag in SavedData");
            }
            if (McCompat.nbt().containsTyped(entry, "progressNotified", Tag.TAG_COMPOUND)) {
                CompoundTag progressTag = McCompat.nbt().getCompound(entry, "progressNotified");
                for (String rawKey : McCompat.nbt().keys(progressTag)) {
                    TargetKey parsed = TargetKey.tryParseEncoded(rawKey);
                    if (parsed == null) {
                        MilestoneLoadReport.recordElementSkip();
                        ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid progressNotified key '{}' in SavedData", rawKey);
                        continue;
                    }
                    if (!McCompat.nbt().containsTyped(progressTag, rawKey, NbtCompat.ANY_NUMERIC)) {
                        MilestoneLoadReport.recordElementSkip();
                        ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping non-numeric progressNotified value for '{}' in SavedData", rawKey);
                        continue;
                    }
                    int threshold = McCompat.nbt().getInt(progressTag, rawKey);
                    if (!ProgressAlertLogic.isValidStoredThreshold(threshold)) {
                        MilestoneLoadReport.recordElementSkip();
                        ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping out-of-range progressNotified value {} for '{}' in SavedData",
                                threshold, rawKey);
                        continue;
                    }
                    pd.progressNotified.put(parsed, threshold);
                }
            } else if (McCompat.nbt().contains(entry, "progressNotified")) {
                MilestoneLoadReport.recordElementSkip();
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping malformed progressNotified tag in SavedData");
            }
            if (McCompat.nbt().containsTyped(entry, "completedPresets", Tag.TAG_LIST)) {
                Tag rawCompleted = entry.get("completedPresets");
                if (rawCompleted instanceof ListTag rawList && !rawList.isEmpty() && McCompat.nbt().listElementType(rawList) != Tag.TAG_STRING) {
                    MilestoneLoadReport.recordElementSkip();
                    ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping malformed completedPresets tag in SavedData");
                } else {
                    for (Tag t : McCompat.nbt().getStringList(entry, "completedPresets")) {
                        String presetId = McCompat.nbt().tagAsString(t);
                        if (Presets.isValidId(presetId)) {
                            pd.completedPresets.add(presetId);
                        } else {
                            MilestoneLoadReport.recordElementSkip();
                            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid completedPresets entry '{}' in SavedData", presetId);
                        }
                    }
                }
            } else if (McCompat.nbt().contains(entry, "completedPresets")) {
                MilestoneLoadReport.recordElementSkip();
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping malformed completedPresets tag in SavedData");
            }
            if (McCompat.nbt().containsTyped(entry, "presetState", Tag.TAG_COMPOUND)) {
                CompoundTag presetStates = McCompat.nbt().getCompound(entry, "presetState");
                for (String presetId : McCompat.nbt().keys(presetStates)) {
                    if (!Presets.isValidId(presetId)
                            || !McCompat.nbt().containsTyped(presetStates, presetId, Tag.TAG_COMPOUND)) {
                        MilestoneLoadReport.recordElementSkip();
                        ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid presetState entry '{}' in SavedData", presetId);
                        continue;
                    }
                    CompoundTag stateTag = McCompat.nbt().getCompound(presetStates, presetId);
                    boolean autoApplied = false;
                    int lastSeenVersion = 0;
                    boolean malformed = false;
                    if (McCompat.nbt().contains(stateTag, "autoApplied")) {
                        if (McCompat.nbt().containsTyped(stateTag, "autoApplied", NbtCompat.ANY_NUMERIC)) {
                            autoApplied = McCompat.nbt().getBoolean(stateTag, "autoApplied");
                        } else {
                            malformed = true;
                        }
                    }
                    if (!malformed && McCompat.nbt().contains(stateTag, "lastSeenVersion")) {
                        if (McCompat.nbt().containsTyped(stateTag, "lastSeenVersion", NbtCompat.ANY_NUMERIC)) {
                            lastSeenVersion = McCompat.nbt().getInt(stateTag, "lastSeenVersion");
                            if (lastSeenVersion < 0) {
                                malformed = true;
                            }
                        } else {
                            malformed = true;
                        }
                    }
                    if (malformed) {
                        MilestoneLoadReport.recordElementSkip();
                        ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping malformed presetState entry '{}' in SavedData", presetId);
                        continue;
                    }
                    pd.presetState.put(presetId, new PresetState(autoApplied, lastSeenVersion));
                }
            } else if (McCompat.nbt().contains(entry, "presetState")) {
                MilestoneLoadReport.recordElementSkip();
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping malformed presetState tag in SavedData");
            }
            if (McCompat.nbt().containsTyped(entry, "targetMeta", Tag.TAG_COMPOUND)) {
                CompoundTag metas = McCompat.nbt().getCompound(entry, "targetMeta");
                for (String encodedKey : McCompat.nbt().keys(metas)) {
                    if (!McCompat.nbt().containsTyped(metas, encodedKey, Tag.TAG_COMPOUND)) {
                        MilestoneLoadReport.recordElementSkip();
                        ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping non-compound targetMeta entry '{}' in SavedData",
                                encodedKey);
                        continue;
                    }
                    CompoundTag metaTag = McCompat.nbt().getCompound(metas, encodedKey);
                    boolean malformed = false;
                    int priority = 0;
                    String label = "";
                    boolean pinned = false;
                    if (McCompat.nbt().contains(metaTag, "priority")) {
                        if (McCompat.nbt().containsTyped(metaTag, "priority", NbtCompat.ANY_NUMERIC)) {
                            priority = McCompat.nbt().getInt(metaTag, "priority");
                        } else {
                            malformed = true;
                        }
                    } else {
                        malformed = true;
                    }
                    if (McCompat.nbt().contains(metaTag, "label")) {
                        if (McCompat.nbt().containsTyped(metaTag, "label", Tag.TAG_STRING)) {
                            label = McCompat.nbt().getString(metaTag, "label");
                        } else {
                            malformed = true;
                        }
                    } else {
                        malformed = true;
                    }
                    if (McCompat.nbt().contains(metaTag, "pinned")) {
                        if (McCompat.nbt().containsTyped(metaTag, "pinned", NbtCompat.ANY_NUMERIC)) {
                            pinned = McCompat.nbt().getBoolean(metaTag, "pinned");
                        } else {
                            malformed = true;
                        }
                    } else {
                        malformed = true;
                    }
                    TargetMeta meta = malformed ? TargetMeta.INVALID : TargetMeta.of(priority, label, pinned);
                    if (meta == TargetMeta.INVALID) {
                        MilestoneLoadReport.recordElementSkip();
                        ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid targetMeta entry '{}' in SavedData",
                                encodedKey);
                        continue;
                    }
                    pd.targetMeta.put(encodedKey, meta);
                }
            } else if (McCompat.nbt().contains(entry, "targetMeta")) {
                MilestoneLoadReport.recordElementSkip();
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping malformed targetMeta tag in SavedData");
            }
            data.players.put(uuid, pd);
        }

        CompoundTag firsts = McCompat.nbt().getCompound(tag, "worldFirsts");
        for (String itemId : McCompat.nbt().keys(firsts)) {
            if (!TargetKey.isValidItemId(itemId) || !McCompat.nbt().containsTyped(firsts, itemId, Tag.TAG_COMPOUND)) {
                MilestoneLoadReport.recordElementSkip();
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid worldFirst item key '{}' in SavedData", itemId);
                continue;
            }
            CompoundTag itemTag = McCompat.nbt().getCompound(firsts, itemId);
            if (McCompat.nbt().containsTyped(itemTag, "ticks", NbtCompat.ANY_NUMERIC) || McCompat.nbt().containsTyped(itemTag, "uuid", Tag.TAG_STRING)) {
                // Backward compatibility: early 1.0.1 stored itemId -> {uuid, name, ticks, day, epoch} (count = 1).
                readWorldFirstTag(data.worldFirsts, itemId, TargetKey.MIN_COUNT, itemTag);
            } else {
                for (String countKey : McCompat.nbt().keys(itemTag)) {
                    int count = TargetKey.tryParseCount(countKey);
                    if (count < TargetKey.MIN_COUNT || !McCompat.nbt().containsTyped(itemTag, countKey, Tag.TAG_COMPOUND)) {
                        MilestoneLoadReport.recordElementSkip();
                        ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid worldFirst count '{}' for '{}' in SavedData",
                                countKey, itemId);
                        continue;
                    }
                    readWorldFirstTag(data.worldFirsts, itemId, count, McCompat.nbt().getCompound(itemTag, countKey));
                }
            }
        }

        if (McCompat.nbt().containsTyped(tag, "lastDigestEpoch", NbtCompat.ANY_NUMERIC)) {
            long lastDigest = McCompat.nbt().getLong(tag, "lastDigestEpoch");
            if (lastDigest > 0L) {
                data.lastDigestEpochMillis = lastDigest;
            } else if (lastDigest < 0L) {
                MilestoneLoadReport.recordElementSkip();
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping negative lastDigestEpoch value {} in SavedData", lastDigest);
            }
        } else if (McCompat.nbt().contains(tag, "lastDigestEpoch")) {
            MilestoneLoadReport.recordElementSkip();
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping malformed lastDigestEpoch tag in SavedData");
        }

        // F-140-1 (N88): missing = legacy data (0); future versions are preserved on save.
        int rawSchemaVersion = SchemaVersionLogic.ABSENT_SCHEMA_VERSION;
        if (McCompat.nbt().containsTyped(tag, "schemaVersion", NbtCompat.ANY_NUMERIC)) {
            rawSchemaVersion = McCompat.nbt().getInt(tag, "schemaVersion");
        } else if (McCompat.nbt().contains(tag, "schemaVersion")) {
            rawSchemaVersion = SchemaVersionLogic.MALFORMED_SCHEMA_VERSION;
        }
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

        return data;
    }

    // No @Override: SavedData declares this method only up to MC 1.21.4; on 1.21.5+ the DataCompat codec calls it.
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        CompoundTag all = new CompoundTag();
        for (Map.Entry<UUID, PlayerData> e : players.entrySet()) {
            PlayerData pd = e.getValue();
            CompoundTag entry = new CompoundTag();
            ListTag targets = new ListTag();
            for (TargetKey t : pd.targets) {
                targets.add(StringTag.valueOf(t.toEncodedString()));
            }
            entry.put("targets", targets);
            CompoundTag records = new CompoundTag();
            for (Map.Entry<TargetKey, MilestoneRecord> r : pd.records.entrySet()) {
                TargetKey key = r.getKey();
                MilestoneRecord rec = r.getValue();
                CompoundTag itemTag = McCompat.nbt().containsTyped(records, key.itemId(), Tag.TAG_COMPOUND)
                        ? McCompat.nbt().getCompound(records, key.itemId())
                        : new CompoundTag();
                CompoundTag recTag = new CompoundTag();
                recTag.putInt("ticks", rec.playTicks());
                recTag.putLong("day", rec.day());
                recTag.putLong("epoch", rec.epochMillis());
                itemTag.put(String.valueOf(key.count()), recTag);
                records.put(key.itemId(), itemTag);
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
            if (pd.muteProgressAlerts) {
                entry.putBoolean("muteProgressAlerts", true);
            }
            if (pd.excludeFromDiscord) {
                entry.putBoolean("excludeFromDiscord", true);
            }
            if (pd.hideFromLookup) {
                entry.putBoolean("hideFromLookup", true);
            }
            if (pd.lastSeen > 0L) {
                entry.putLong("lastSeen", pd.lastSeen);
            }
            if (!pd.progressNotified.isEmpty()) {
                CompoundTag progressTag = new CompoundTag();
                for (Map.Entry<TargetKey, Integer> p : pd.progressNotified.entrySet()) {
                    progressTag.putInt(p.getKey().toEncodedString(), p.getValue());
                }
                entry.put("progressNotified", progressTag);
            }
            if (!pd.completedPresets.isEmpty()) {
                ListTag completedTag = new ListTag();
                for (String presetId : pd.completedPresets) {
                    completedTag.add(StringTag.valueOf(presetId));
                }
                entry.put("completedPresets", completedTag);
            }
            if (!pd.presetState.isEmpty()) {
                CompoundTag presetStateTag = new CompoundTag();
                for (Map.Entry<String, PresetState> state : pd.presetState.entrySet()) {
                    CompoundTag stateTag = new CompoundTag();
                    stateTag.putBoolean("autoApplied", state.getValue().autoApplied());
                    stateTag.putInt("lastSeenVersion", state.getValue().lastSeenVersion());
                    presetStateTag.put(state.getKey(), stateTag);
                }
                entry.put("presetState", presetStateTag);
            }
            if (!pd.targetMeta.isEmpty()) {
                CompoundTag targetMetaTag = new CompoundTag();
                for (Map.Entry<String, TargetMeta> meta : pd.targetMeta.entrySet()) {
                    CompoundTag metaTag = new CompoundTag();
                    metaTag.putInt("priority", meta.getValue().priority());
                    metaTag.putString("label", meta.getValue().label());
                    metaTag.putBoolean("pinned", meta.getValue().pinned());
                    targetMetaTag.put(meta.getKey(), metaTag);
                }
                entry.put("targetMeta", targetMetaTag);
            }
            all.put(e.getKey().toString(), entry);
        }
        tag.put("players", all);

        if (!worldFirsts.isEmpty()) {
            CompoundTag firsts = new CompoundTag();
            for (Map.Entry<TargetKey, WorldFirstRecord> e : worldFirsts.entrySet()) {
                TargetKey key = e.getKey();
                WorldFirstRecord wf = e.getValue();
                CompoundTag itemTag = McCompat.nbt().containsTyped(firsts, key.itemId(), Tag.TAG_COMPOUND)
                        ? McCompat.nbt().getCompound(firsts, key.itemId())
                        : new CompoundTag();
                CompoundTag wfTag = new CompoundTag();
                wfTag.putString("uuid", wf.playerUuid().toString());
                wfTag.putString("name", wf.playerName());
                wfTag.putInt("ticks", wf.playTicks());
                wfTag.putLong("day", wf.day());
                wfTag.putLong("epoch", wf.epochMillis());
                itemTag.put(String.valueOf(key.count()), wfTag);
                firsts.put(key.itemId(), itemTag);
            }
            tag.put("worldFirsts", firsts);
        }

        if (lastDigestEpochMillis > 0L) {
            tag.putLong("lastDigestEpoch", lastDigestEpochMillis);
        }

        // F-140-1 (N88): always written; never downgrade a value loaded from a newer release.
        tag.putInt("schemaVersion", schemaVersionForSave());

        return tag;
    }

    /** Returns {@code true} when neither player entries nor world-first records exist. */
    public boolean isEmpty() {
        return players.isEmpty() && worldFirsts.isEmpty();
    }

    /**
     * F10: serializes this in-memory {@link MilestoneData} into the exact root NBT wrapper
     * ({@code {"data": ..., "DataVersion": ...}}) written by vanilla {@link SavedData}.
     * Does not clear {@link #isDirty()}.
     */
    CompoundTag toSavedDataFileTag(@Nullable HolderLookup.Provider registries) {
        CompoundTag root = new CompoundTag();
        root.put("data", save(new CompoundTag(), registries));
        SharedConstants.tryDetectVersion();
        NbtUtils.addCurrentDataVersion(root);
        return root;
    }
}
