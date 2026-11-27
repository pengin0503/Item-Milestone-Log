package com.pengin0503.itemmilestonelog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure-Java local cache for F-150-64 (N115): the two received sync payloads are flattened into one
 * JSON snapshot per opaque server identifier inside
 * {@code <configDir>/itemmilestonelog-client/cache.json}.
 *
 * <p>The cache is a display-only snapshot. Nothing here writes to the server or to SavedData, and
 * the offline view is shaped from the exact same fields (in the same order) that
 * {@code SyncMilestonesPayload} uses, so a later client PR can hand it to the existing
 * {@code MilestoneScreen} constructor without a new screen (FL-2). Each list is capped at
 * {@value #MAX_LIST_ENTRIES} entries and malformed JSON is treated as empty data.
 *
 * <p>Reads/writes go through {@link ClientStorageIO} (atomic move, hashed identity). Must stay
 * Java 17 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class LocalSyncCache {
    /** File name inside {@link ClientStorageIO#CLIENT_DIR}. */
    public static final String FILE_NAME = "cache.json";
    /** Same cap as {@code SyncMilestonesPayload.MAX_LIST_ENTRIES}. */
    public static final int MAX_LIST_ENTRIES = 4096;
    /** Debounce delay for writes, in milliseconds (F-150-64 item 2). */
    public static final long DEBOUNCE_MILLIS = 1000L;
    private static final String VERSION = "1";

    private LocalSyncCache() {
    }

    /** Mirrors {@code network.TargetEntry} without any Minecraft type. */
    public record CachedTarget(String itemId, int count, boolean personal, boolean defaultTarget) {
    }

    /** Mirrors the wire fields of {@code api.MilestoneRecord} keyed by an item id + count. */
    public record CachedRecord(String itemId, int count, int playTicks, long day, long epochMillis) {
    }

    /** Mirrors one {@code TargetKey -> Integer} progress entry. */
    public record CachedProgress(String itemId, int count, int value) {
    }

    /** Mirrors {@code network.PresetEntry} without a Component display name. */
    public record CachedPreset(String id, String displayName, int itemCount, boolean builtin) {
    }

    /**
     * One cached snapshot. {@code offline} is true for every snapshot produced from disk so the UI
     * can label it "offline view" and never infer live server state.
     */
    public record Snapshot(
            List<CachedTarget> targets,
            List<CachedRecord> records,
            List<CachedProgress> progress,
            int currentPlaytimeTicks,
            List<CachedPreset> presets,
            boolean canManagePresets,
            boolean openScreen,
            String serverKey,
            long savedAtMillis,
            boolean offline) {

        public Snapshot {
            targets = List.copyOf(targets);
            records = List.copyOf(records);
            progress = List.copyOf(progress);
            presets = List.copyOf(presets);
            serverKey = serverKey == null ? "" : serverKey;
        }
    }

    /** Result of a lenient read: {@code invalid} means corrupt JSON, {@code present} means a cached entry existed. */
    public record LoadResult(Snapshot snapshot, boolean invalid, boolean present) {
    }

    /** Resolves the cache file below {@code configDir}. */
    public static Path fileFor(Path configDir) {
        return ClientStorageIO.clientDir(configDir).resolve(FILE_NAME);
    }

    /** Returns an empty offline snapshot for {@code serverKey}. */
    public static Snapshot empty(String serverKey) {
        return new Snapshot(List.of(), List.of(), List.of(), 0, List.of(),
                false, false, serverKey, 0L, true);
    }

    /**
     * Debounce check: a write is due once at least {@link #DEBOUNCE_MILLIS} have elapsed. A clock
     * that moved backwards is treated as due so a cache is never indefinitely stale.
     */
    public static boolean writeDue(long lastWriteMillis, long nowMillis) {
        return nowMillis < lastWriteMillis || nowMillis - lastWriteMillis >= DEBOUNCE_MILLIS;
    }

    /** Applies the 4096-entry cap to every list, preserving insertion order. */
    public static Snapshot cap(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new Snapshot(
                capList(snapshot.targets()),
                capList(snapshot.records()),
                capList(snapshot.progress()),
                snapshot.currentPlaytimeTicks(),
                capList(snapshot.presets()),
                snapshot.canManagePresets(),
                snapshot.openScreen(),
                snapshot.serverKey(),
                snapshot.savedAtMillis(),
                snapshot.offline());
    }

    /**
     * Shapes cached data for offline viewing. The payload fields keep their wire order and values;
     * only the server-dependent flags are cleared so no offline view looks editable or live.
     */
    public static Snapshot offlineView(Snapshot snapshot) {
        if (snapshot == null) {
            return empty("");
        }
        Snapshot capped = cap(snapshot);
        return new Snapshot(
                capped.targets(), capped.records(), capped.progress(),
                capped.currentPlaytimeTicks(), capped.presets(),
                false, false, capped.serverKey(), capped.savedAtMillis(), true);
    }

    /**
     * Writes one server's snapshot, merging it with any other cached server identities already on
     * disk. Malformed existing data is replaced rather than propagated. Failures are swallowed so
     * the local cache can never interrupt gameplay (the caller logs a single warning if needed).
     */
    public static void write(Path configDir, Snapshot snapshot) {
        Objects.requireNonNull(configDir, "configDir");
        Objects.requireNonNull(snapshot, "snapshot");
        if (!ClientStorageIO.isServerKey(snapshot.serverKey())) {
            return;
        }
        Snapshot capped = cap(snapshot);
        long savedAt = capped.savedAtMillis() > 0 ? capped.savedAtMillis() : System.currentTimeMillis();
        Map<String, Snapshot> servers = new java.util.LinkedHashMap<>();
        String raw = ClientStorageIO.readOrNull(fileFor(configDir));
        if (raw != null && !raw.isBlank()) {
            try {
                JsonElement parsed = JsonParser.parseString(raw);
                if (parsed.isJsonObject()) {
                    JsonElement existing = parsed.getAsJsonObject().get("servers");
                    if (existing != null && existing.isJsonObject()) {
                        for (Map.Entry<String, JsonElement> entry : existing.getAsJsonObject().entrySet()) {
                            Snapshot decoded = decodeSnapshot(entry.getKey(), entry.getValue());
                            if (decoded != null) {
                                servers.put(entry.getKey(), decoded);
                            }
                        }
                    }
                }
            } catch (RuntimeException e) {
                // Corrupt previous content is discarded; the freshly written snapshot wins.
                servers.clear();
            }
        }
        servers.put(capped.serverKey(), new Snapshot(
                capped.targets(), capped.records(), capped.progress(), capped.currentPlaytimeTicks(),
                capped.presets(), capped.canManagePresets(), capped.openScreen(),
                capped.serverKey(), savedAt, capped.offline()));

        JsonObject root = new JsonObject();
        root.addProperty("v", VERSION);
        JsonObject allServers = new JsonObject();
        for (Map.Entry<String, Snapshot> entry : servers.entrySet()) {
            allServers.add(entry.getKey(), encodeSnapshot(entry.getValue()));
        }
        root.add("servers", allServers);
        try {
            ClientStorageIO.atomicWrite(fileFor(configDir), root.toString());
        } catch (Exception e) {
            // Best-effort local cache; never blocks the client.
        }
    }

    /**
     * Reads one server's cache. Missing data returns an empty snapshot; corrupt JSON sets
     * {@code invalid} so the caller can show {@code cache_invalid} once. A key with no cached
     * entry is never mixed in from another server.
     */
    public static LoadResult read(Path configDir, String serverKey) {
        if (!ClientStorageIO.isServerKey(serverKey)) {
            return new LoadResult(empty(serverKey), false, false);
        }
        String raw = ClientStorageIO.readOrNull(fileFor(configDir));
        if (raw == null || raw.isBlank()) {
            return new LoadResult(empty(serverKey), false, false);
        }
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) {
                return new LoadResult(empty(serverKey), true, false);
            }
            root = parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            return new LoadResult(empty(serverKey), true, false);
        }
        JsonElement serversElement = root.get("servers");
        if (serversElement == null || !serversElement.isJsonObject()) {
            return new LoadResult(empty(serverKey), true, false);
        }
        JsonElement entry = serversElement.getAsJsonObject().get(serverKey);
        if (entry == null || !entry.isJsonObject()) {
            return new LoadResult(empty(serverKey), false, false);
        }
        Snapshot snapshot = decodeSnapshot(serverKey, entry);
        if (snapshot == null) {
            return new LoadResult(empty(serverKey), true, false);
        }
        return new LoadResult(offlineView(snapshot), false, true);
    }

    private static <T> List<T> capList(List<T> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return List.copyOf(values.subList(0, Math.min(values.size(), MAX_LIST_ENTRIES)));
    }

    private static JsonObject encodeSnapshot(Snapshot snapshot) {
        JsonObject out = new JsonObject();
        out.addProperty("savedAt", snapshot.savedAtMillis());
        JsonArray targets = new JsonArray();
        for (CachedTarget target : snapshot.targets()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", target.itemId());
            item.addProperty("count", target.count());
            item.addProperty("personal", target.personal());
            item.addProperty("default", target.defaultTarget());
            targets.add(item);
        }
        out.add("targets", targets);
        JsonArray records = new JsonArray();
        for (CachedRecord record : snapshot.records()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", record.itemId());
            item.addProperty("count", record.count());
            item.addProperty("playTicks", record.playTicks());
            item.addProperty("day", record.day());
            item.addProperty("epochMillis", record.epochMillis());
            records.add(item);
        }
        out.add("records", records);
        JsonArray progress = new JsonArray();
        for (CachedProgress entry : snapshot.progress()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", entry.itemId());
            item.addProperty("count", entry.count());
            item.addProperty("value", entry.value());
            progress.add(item);
        }
        out.add("progress", progress);
        out.addProperty("currentPlaytimeTicks", snapshot.currentPlaytimeTicks());
        JsonArray presets = new JsonArray();
        for (CachedPreset preset : snapshot.presets()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", preset.id());
            item.addProperty("displayName", preset.displayName() == null ? "" : preset.displayName());
            item.addProperty("itemCount", preset.itemCount());
            item.addProperty("builtin", preset.builtin());
            presets.add(item);
        }
        out.add("presets", presets);
        out.addProperty("canManagePresets", snapshot.canManagePresets());
        out.addProperty("openScreen", snapshot.openScreen());
        return out;
    }

    private static Snapshot decodeSnapshot(String serverKey, JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject json = element.getAsJsonObject();
        List<CachedTarget> targets = readArray(json.get("targets"), item -> new CachedTarget(
                string(item, "id"), integer(item, "count"), bool(item, "personal"), bool(item, "default")));
        List<CachedRecord> records = readArray(json.get("records"), item -> new CachedRecord(
                string(item, "id"), integer(item, "count"), integer(item, "playTicks"),
                longValue(item, "day"), longValue(item, "epochMillis")));
        List<CachedProgress> progress = readArray(json.get("progress"), item -> new CachedProgress(
                string(item, "id"), integer(item, "count"), integer(item, "value")));
        List<CachedPreset> presets = readArray(json.get("presets"), item -> new CachedPreset(
                string(item, "id"), string(item, "displayName"), integer(item, "itemCount"),
                bool(item, "builtin")));
        return new Snapshot(
                targets, records, progress, integer(json, "currentPlaytimeTicks"), presets,
                bool(json, "canManagePresets"), bool(json, "openScreen"), serverKey,
                longValue(json, "savedAt"), true);
    }

    private interface Decoder<T> {
        T decode(JsonObject item);
    }

    private static <T> List<T> readArray(JsonElement element, Decoder<T> decoder) {
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<T> values = new ArrayList<>();
        for (JsonElement child : element.getAsJsonArray()) {
            if (child.isJsonObject()) {
                try {
                    values.add(decoder.decode(child.getAsJsonObject()));
                } catch (RuntimeException e) {
                    // Skip one malformed entry instead of rejecting the whole snapshot.
                }
            }
            if (values.size() >= MAX_LIST_ENTRIES) {
                break;
            }
        }
        return values;
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : "";
    }

    private static int integer(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return 0;
        }
        try {
            return value.getAsInt();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static long longValue(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return 0L;
        }
        try {
            return value.getAsLong();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static boolean bool(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                && value.getAsBoolean();
    }
}
