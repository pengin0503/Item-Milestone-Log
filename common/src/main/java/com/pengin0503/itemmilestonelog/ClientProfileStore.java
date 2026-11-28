package com.pengin0503.itemmilestonelog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Pure-Java storage for F-150-72 (N70): per-server overrides of the twelve client display keys in
 * {@code <configDir>/itemmilestonelog-client/profiles.json}. Profiles are keyed by the opaque
 * {@link ClientStorageIO#serverKey(String, String)} hash; raw addresses and world names are never
 * written. At most {@value #MAX_PROFILES} profiles are kept, oldest insertion first.
 *
 * <p>Reads are lenient: a missing or malformed file is treated as no profiles (never an exception).
 * The config file itself is never rewritten, and nothing is ever sent to the server.
 */
public final class ClientProfileStore {
    /** File name inside {@link ClientStorageIO#CLIENT_DIR}. */
    public static final String FILE_NAME = "profiles.json";
    /** Maximum number of remembered server profiles. */
    public static final int MAX_PROFILES = 32;
    private static final String VERSION = "1";

    private ClientProfileStore() {
    }

    /** Result of a lenient read; {@code invalid} distinguishes a corrupt file from an empty one. */
    public record LoadResult(Map<String, Map<String, String>> profiles, boolean invalid) {
        public LoadResult {
            // Keep JSON insertion order: the MAX_PROFILES eviction drops the oldest entry first.
            profiles = Collections.unmodifiableMap(new LinkedHashMap<>(profiles));
        }
    }

    /** Resolves the profile file below {@code configDir}. */
    public static Path fileFor(Path configDir) {
        return ClientStorageIO.clientDir(configDir).resolve(FILE_NAME);
    }

    /**
     * Reads all profiles. Corrupt JSON yields an empty map with {@code invalid = true}; a missing
     * file yields an empty map with {@code invalid = false}. Unknown keys found on disk are dropped.
     */
    public static LoadResult readAll(Path configDir) {
        String raw = ClientStorageIO.readOrNull(fileFor(configDir));
        if (raw == null || raw.isBlank()) {
            return new LoadResult(Map.of(), false);
        }
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) {
                return new LoadResult(Map.of(), true);
            }
            JsonObject root = parsed.getAsJsonObject();
            JsonElement serversElement = root.get("profiles");
            if (serversElement == null || !serversElement.isJsonObject()) {
                return new LoadResult(Map.of(), true);
            }
            Map<String, Map<String, String>> profiles = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> server : serversElement.getAsJsonObject().entrySet()) {
                if (!ClientStorageIO.isServerKey(server.getKey()) || !server.getValue().isJsonObject()) {
                    continue;
                }
                Map<String, String> overrides = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> entry : server.getValue().getAsJsonObject().entrySet()) {
                    JsonElement value = entry.getValue();
                    if (DisplayOptions.isDisplayKey(entry.getKey())
                            && value != null && value.isJsonPrimitive()
                            && value.getAsJsonPrimitive().isString()) {
                        overrides.put(entry.getKey(), value.getAsString());
                    }
                }
                profiles.put(server.getKey(), Map.copyOf(overrides));
            }
            // On-disk order is oldest first; keep the newest tail on load (§1.4-3).
            if (profiles.size() > MAX_PROFILES) {
                Map<String, Map<String, String>> trimmed = new LinkedHashMap<>();
                int skip = profiles.size() - MAX_PROFILES;
                int index = 0;
                for (Map.Entry<String, Map<String, String>> entry : profiles.entrySet()) {
                    if (index++ >= skip) {
                        trimmed.put(entry.getKey(), entry.getValue());
                    }
                }
                return new LoadResult(trimmed, false);
            }
            return new LoadResult(profiles, false);
        } catch (RuntimeException e) {
            // JsonParseException / IllegalStateException / malformed shapes all mean "no usable data".
            return new LoadResult(Map.of(), true);
        }
    }

    /** Returns the overrides for one server, or an empty map when there is no profile. */
    public static Map<String, String> load(Path configDir, String serverKey) {
        if (!ClientStorageIO.isServerKey(serverKey)) {
            return Map.of();
        }
        return readAll(configDir).profiles().getOrDefault(serverKey, Map.of());
    }

    /**
     * Saves (or replaces) one server profile. Every value is coerced to a string, unknown keys are
     * discarded, and the oldest profiles are dropped above {@link #MAX_PROFILES}.
     */
    public static void save(Path configDir, String serverKey, Map<String, String> overrides) {
        Objects.requireNonNull(configDir, "configDir");
        if (!ClientStorageIO.isServerKey(serverKey)) {
            return;
        }
        Map<String, Map<String, String>> existing = readAll(configDir).profiles();
        Map<String, Map<String, String>> merged = new LinkedHashMap<>(existing);
        merged.remove(serverKey);
        merged.put(serverKey, filter(overrides));
        while (merged.size() > MAX_PROFILES) {
            String oldest = merged.keySet().iterator().next();
            merged.remove(oldest);
        }

        JsonObject root = new JsonObject();
        root.addProperty("v", VERSION);
        JsonObject servers = new JsonObject();
        for (Map.Entry<String, Map<String, String>> server : merged.entrySet()) {
            JsonObject profile = new JsonObject();
            for (Map.Entry<String, String> entry : server.getValue().entrySet()) {
                profile.add(entry.getKey(), new JsonPrimitive(entry.getValue()));
            }
            servers.add(server.getKey(), profile);
        }
        root.add("profiles", servers);
        try {
            ClientStorageIO.atomicWrite(fileFor(configDir), root.toString());
        } catch (Exception e) {
            // A local preference must never stop gameplay; the caller sees no exception.
        }
    }

    /** Removes one server's profile, leaving every other profile untouched. */
    public static void clear(Path configDir, String serverKey) {
        if (!ClientStorageIO.isServerKey(serverKey)) {
            return;
        }
        Map<String, Map<String, String>> existing = readAll(configDir).profiles();
        if (!existing.containsKey(serverKey)) {
            return;
        }
        Map<String, Map<String, String>> remaining = new LinkedHashMap<>(existing);
        remaining.remove(serverKey);
        JsonObject root = new JsonObject();
        root.addProperty("v", VERSION);
        JsonObject servers = new JsonObject();
        for (Map.Entry<String, Map<String, String>> server : remaining.entrySet()) {
            JsonObject profile = new JsonObject();
            for (Map.Entry<String, String> entry : server.getValue().entrySet()) {
                profile.add(entry.getKey(), new JsonPrimitive(entry.getValue()));
            }
            servers.add(server.getKey(), profile);
        }
        root.add("profiles", servers);
        try {
            ClientStorageIO.atomicWrite(fileFor(configDir), root.toString());
        } catch (Exception e) {
            // Best-effort local clear; no gameplay impact.
        }
    }

    /** Returns only the twelve allowed display keys; unknown keys are silently discarded. */
    public static Map<String, String> filter(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> kept = new LinkedHashMap<>();
        // Iterate the canonical key order so the persisted body is deterministic.
        for (String key : DisplayOptions.KEYS) {
            String value = raw.get(key);
            if (value != null) {
                kept.put(key, value.length() > 256 ? value.substring(0, 256) : value);
            }
        }
        return Map.copyOf(kept);
    }

    /** Returns the set of keys this store accepts (mirrors {@link DisplayOptions#KEY_SET}). */
    public static TreeSet<String> allowedKeys() {
        return new TreeSet<>(DisplayOptions.KEY_SET);
    }
}
