package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.api.WorldFirstRecord;
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
    static final String DATA_NAME = ItemMilestoneLog.MOD_ID + "_data";

    static final Factory<MilestoneData> FACTORY = new Factory<>(
            MilestoneData::new,
            MilestoneData::load,
            null);

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

        /** Whether this player hides their records from lookups by non-privileged other players (F14). */
        public boolean isHiddenFromLookup() {
            return hideFromLookup;
        }
    }

    private final Map<UUID, PlayerData> players = new LinkedHashMap<>();
    private final Map<TargetKey, WorldFirstRecord> worldFirsts = new LinkedHashMap<>();
    /** Epoch millis of the last Discord digest (0 = never initialized/sent, F9). */
    private long lastDigestEpochMillis = 0L;

    public static MilestoneData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
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
        if (!recTag.contains("ticks", Tag.TAG_ANY_NUMERIC)) {
            return null;
        }
        int ticks = recTag.getInt("ticks");
        if (ticks < 0) {
            return null;
        }
        long day = recTag.contains("day", Tag.TAG_ANY_NUMERIC)
                ? recTag.getLong("day")
                : MilestoneRecord.UNKNOWN_DAY;
        long epoch = recTag.contains("epoch", Tag.TAG_ANY_NUMERIC)
                ? recTag.getLong("epoch")
                : MilestoneRecord.UNKNOWN_EPOCH;
        return new MilestoneRecord(ticks, day, epoch);
    }

    private static void readWorldFirstTag(Map<TargetKey, WorldFirstRecord> out, String itemId, int count, CompoundTag wfTag) {
        if (!wfTag.contains("ticks", Tag.TAG_ANY_NUMERIC)) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping worldFirst '{}' without ticks in SavedData", itemId);
            return;
        }
        int ticks = wfTag.getInt("ticks");
        if (ticks < 0) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping worldFirst '{}' with negative ticks {} in SavedData", itemId, ticks);
            return;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(wfTag.getString("uuid"));
        } catch (IllegalArgumentException ignored) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping worldFirst '{}' with invalid player UUID '{}' in SavedData",
                    itemId, wfTag.getString("uuid"));
            return;
        }
        String name = wfTag.getString("name");
        long day = wfTag.contains("day", Tag.TAG_ANY_NUMERIC)
                ? wfTag.getLong("day")
                : MilestoneRecord.UNKNOWN_DAY;
        long epoch = wfTag.contains("epoch", Tag.TAG_ANY_NUMERIC)
                ? wfTag.getLong("epoch")
                : MilestoneRecord.UNKNOWN_EPOCH;
        TargetKey key = new TargetKey(itemId, count);
        out.put(key, new WorldFirstRecord(itemId, count, uuid, name, ticks, day, epoch));
    }

    public static MilestoneData load(CompoundTag tag, HolderLookup.Provider provider) {
        MilestoneData data = new MilestoneData();
        CompoundTag all = tag.getCompound("players");
        for (String uuidKey : all.getAllKeys()) {
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidKey);
            } catch (IllegalArgumentException ignored) {
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping player entry with invalid UUID '{}' in SavedData", uuidKey);
                continue;
            }
            CompoundTag entry = all.getCompound(uuidKey);
            PlayerData pd = new PlayerData();
            ListTag targets = entry.getList("targets", Tag.TAG_STRING);
            for (Tag t : targets) {
                String raw = t.getAsString();
                TargetKey parsed = TargetKey.tryParseEncoded(raw);
                if (parsed != null) {
                    pd.targets.add(parsed);
                } else {
                    ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid target entry '{}' in SavedData", raw);
                }
            }
            CompoundTag records = entry.getCompound("records");
            for (String itemId : records.getAllKeys()) {
                if (!TargetKey.isValidItemId(itemId)) {
                    ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid record item key '{}' in SavedData", itemId);
                    continue;
                }
                if (records.contains(itemId, Tag.TAG_COMPOUND)) {
                    CompoundTag itemTag = records.getCompound(itemId);
                    if (itemTag.contains("ticks", Tag.TAG_ANY_NUMERIC) || itemTag.contains("day", Tag.TAG_ANY_NUMERIC)
                            || itemTag.contains("epoch", Tag.TAG_ANY_NUMERIC)) {
                        // Backward compatibility: early 1.0.1 stored itemId -> {ticks, day, epoch} (count = 1).
                        MilestoneRecord rec = readRecordTag(itemTag);
                        if (rec != null) {
                            pd.records.put(TargetKey.of(itemId), rec);
                        } else {
                            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping record for '{}' with missing or negative ticks in SavedData",
                                    itemId);
                        }
                    } else {
                        // Quantity format: itemId -> { "<count>": {ticks, day, epoch}, ... }.
                        for (String countKey : itemTag.getAllKeys()) {
                            int count = TargetKey.tryParseCount(countKey);
                            if (count < TargetKey.MIN_COUNT || !itemTag.contains(countKey, Tag.TAG_COMPOUND)) {
                                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid record count '{}' for '{}' in SavedData",
                                        countKey, itemId);
                                continue;
                            }
                            MilestoneRecord rec = readRecordTag(itemTag.getCompound(countKey));
                            if (rec != null) {
                                pd.records.put(new TargetKey(itemId, count), rec);
                            } else {
                                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping record for '{}#{}' with missing or negative ticks in SavedData",
                                        itemId, countKey);
                            }
                        }
                    }
                } else if (records.contains(itemId, Tag.TAG_ANY_NUMERIC)) {
                    // Backward compatibility: 1.0.0 stored records as plain integers (count = 1).
                    int ticks = records.getInt(itemId);
                    if (ticks >= 0) {
                        pd.records.put(TargetKey.of(itemId), MilestoneRecord.legacy(ticks));
                    } else {
                        ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping legacy record for '{}' with negative ticks {} in SavedData",
                                itemId, ticks);
                    }
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
            if (entry.contains("muteProgressAlerts", Tag.TAG_ANY_NUMERIC)) {
                pd.muteProgressAlerts = entry.getBoolean("muteProgressAlerts");
            } else if (entry.contains("muteProgressAlerts")) {
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping malformed muteProgressAlerts tag in SavedData");
            }
            if (entry.contains("excludeFromDiscord", Tag.TAG_ANY_NUMERIC)) {
                pd.excludeFromDiscord = entry.getBoolean("excludeFromDiscord");
            } else if (entry.contains("excludeFromDiscord")) {
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping malformed excludeFromDiscord tag in SavedData");
            }
            if (entry.contains("hideFromLookup", Tag.TAG_ANY_NUMERIC)) {
                pd.hideFromLookup = entry.getBoolean("hideFromLookup");
            } else if (entry.contains("hideFromLookup")) {
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping malformed hideFromLookup tag in SavedData");
            }
            if (entry.contains("lastSeen", Tag.TAG_ANY_NUMERIC)) {
                long lastSeen = entry.getLong("lastSeen");
                if (lastSeen > 0L) {
                    pd.lastSeen = lastSeen;
                } else if (lastSeen < 0L) {
                    ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping negative lastSeen value {} in SavedData", lastSeen);
                }
            } else if (entry.contains("lastSeen")) {
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping malformed lastSeen tag in SavedData");
            }
            if (entry.contains("progressNotified", Tag.TAG_COMPOUND)) {
                CompoundTag progressTag = entry.getCompound("progressNotified");
                for (String rawKey : progressTag.getAllKeys()) {
                    TargetKey parsed = TargetKey.tryParseEncoded(rawKey);
                    if (parsed == null) {
                        ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid progressNotified key '{}' in SavedData", rawKey);
                        continue;
                    }
                    if (!progressTag.contains(rawKey, Tag.TAG_ANY_NUMERIC)) {
                        ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping non-numeric progressNotified value for '{}' in SavedData", rawKey);
                        continue;
                    }
                    int threshold = progressTag.getInt(rawKey);
                    if (!ProgressAlertLogic.isValidStoredThreshold(threshold)) {
                        ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping out-of-range progressNotified value {} for '{}' in SavedData",
                                threshold, rawKey);
                        continue;
                    }
                    pd.progressNotified.put(parsed, threshold);
                }
            } else if (entry.contains("progressNotified")) {
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping malformed progressNotified tag in SavedData");
            }
            if (entry.contains("completedPresets", Tag.TAG_LIST)) {
                Tag rawCompleted = entry.get("completedPresets");
                if (rawCompleted instanceof ListTag rawList && !rawList.isEmpty() && rawList.getElementType() != Tag.TAG_STRING) {
                    ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping malformed completedPresets tag in SavedData");
                } else {
                    for (Tag t : entry.getList("completedPresets", Tag.TAG_STRING)) {
                        String presetId = t.getAsString();
                        if (Presets.isValidId(presetId)) {
                            pd.completedPresets.add(presetId);
                        } else {
                            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid completedPresets entry '{}' in SavedData", presetId);
                        }
                    }
                }
            } else if (entry.contains("completedPresets")) {
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping malformed completedPresets tag in SavedData");
            }
            data.players.put(uuid, pd);
        }

        CompoundTag firsts = tag.getCompound("worldFirsts");
        for (String itemId : firsts.getAllKeys()) {
            if (!TargetKey.isValidItemId(itemId) || !firsts.contains(itemId, Tag.TAG_COMPOUND)) {
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid worldFirst item key '{}' in SavedData", itemId);
                continue;
            }
            CompoundTag itemTag = firsts.getCompound(itemId);
            if (itemTag.contains("ticks", Tag.TAG_ANY_NUMERIC) || itemTag.contains("uuid", Tag.TAG_STRING)) {
                // Backward compatibility: early 1.0.1 stored itemId -> {uuid, name, ticks, day, epoch} (count = 1).
                readWorldFirstTag(data.worldFirsts, itemId, TargetKey.MIN_COUNT, itemTag);
            } else {
                for (String countKey : itemTag.getAllKeys()) {
                    int count = TargetKey.tryParseCount(countKey);
                    if (count < TargetKey.MIN_COUNT || !itemTag.contains(countKey, Tag.TAG_COMPOUND)) {
                        ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid worldFirst count '{}' for '{}' in SavedData",
                                countKey, itemId);
                        continue;
                    }
                    readWorldFirstTag(data.worldFirsts, itemId, count, itemTag.getCompound(countKey));
                }
            }
        }

        if (tag.contains("lastDigestEpoch", Tag.TAG_ANY_NUMERIC)) {
            long lastDigest = tag.getLong("lastDigestEpoch");
            if (lastDigest > 0L) {
                data.lastDigestEpochMillis = lastDigest;
            } else if (lastDigest < 0L) {
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping negative lastDigestEpoch value {} in SavedData", lastDigest);
            }
        } else if (tag.contains("lastDigestEpoch")) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping malformed lastDigestEpoch tag in SavedData");
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
            for (TargetKey t : pd.targets) {
                targets.add(StringTag.valueOf(t.toEncodedString()));
            }
            entry.put("targets", targets);
            CompoundTag records = new CompoundTag();
            for (Map.Entry<TargetKey, MilestoneRecord> r : pd.records.entrySet()) {
                TargetKey key = r.getKey();
                MilestoneRecord rec = r.getValue();
                CompoundTag itemTag = records.contains(key.itemId(), Tag.TAG_COMPOUND)
                        ? records.getCompound(key.itemId())
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
            all.put(e.getKey().toString(), entry);
        }
        tag.put("players", all);

        if (!worldFirsts.isEmpty()) {
            CompoundTag firsts = new CompoundTag();
            for (Map.Entry<TargetKey, WorldFirstRecord> e : worldFirsts.entrySet()) {
                TargetKey key = e.getKey();
                WorldFirstRecord wf = e.getValue();
                CompoundTag itemTag = firsts.contains(key.itemId(), Tag.TAG_COMPOUND)
                        ? firsts.getCompound(key.itemId())
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
