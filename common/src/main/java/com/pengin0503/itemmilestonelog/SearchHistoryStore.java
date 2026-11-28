package com.pengin0503.itemmilestonelog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * F-150-84 (N197): client-local search history at
 * {@code <configDir>/itemmilestonelog-client/history.json}.
 *
 * <p>Only the value of the item picker's {@code EditBox} is ever persisted: no player names,
 * chat messages, webhook URLs or other secrets are collected (D-150-229). At most
 * {@value #MAX_ENTRIES} distinct queries are kept, oldest first; malformed JSON is treated as an
 * empty history. Reads/writes reuse {@link ClientStorageIO}'s atomic-write flow and never touch
 * the server or SavedData.
 */
public final class SearchHistoryStore {
    /** File name inside {@link ClientStorageIO#CLIENT_DIR}. */
    public static final String FILE_NAME = "history.json";
    /** Maximum number of remembered queries (spec §1.4). */
    public static final int MAX_ENTRIES = 50;
    /** Maximum length of one remembered query, mirroring the wire string cap. */
    public static final int MAX_QUERY_LENGTH = 256;
    private static final String ARRAY_KEY = "queries";
    private static final String VERSION = "1";

    private SearchHistoryStore() {
    }

    public static Path fileFor(Path configDir) {
        return ClientStorageIO.clientDir(Objects.requireNonNull(configDir, "configDir"))
                .resolve(FILE_NAME);
    }

    /** Returns whether a raw EditBox value may be remembered (never URLs or token-like text). */
    public static boolean isSafeQuery(String raw) {
        if (raw == null) {
            return false;
        }
        for (int i = 0; i < raw.length(); i++) {
            if (Character.isISOControl(raw.charAt(i))) {
                return false;
            }
        }
        String query = raw.trim();
        if (query.isEmpty() || query.length() > MAX_QUERY_LENGTH) {
            return false;
        }
        String lower = query.toLowerCase(Locale.ROOT);
        return !lower.contains("http://") && !lower.contains("https://")
                && !lower.contains("discord.com/api/webhooks") && !lower.contains("://");
    }

    /** Pure FIFO + de-duplication helper (most recent occurrence wins). */
    public static List<String> normalize(List<String> raw, int limit) {
        if (raw == null || raw.isEmpty() || limit <= 0) {
            return List.of();
        }
        // Most recent occurrence wins (case-insensitively), so an older duplicate never
        // pushes a fresh query out. The newest spelling is the one persisted.
        Map<String, String> unique = new LinkedHashMap<>();
        for (String value : raw) {
            if (isSafeQuery(value)) {
                String trimmed = value.trim();
                String key = trimmed.toLowerCase(Locale.ROOT);
                unique.remove(key);
                unique.put(key, trimmed);
            }
        }
        List<String> all = new ArrayList<>(unique.values());
        if (all.size() <= limit) {
            return List.copyOf(all);
        }
        return List.copyOf(all.subList(all.size() - limit, all.size()));
    }

    /** Lenient decode: any wrong shape or bad element is ignored, never thrown. */
    public static List<String> decode(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                return List.of();
            }
            JsonElement array = parsed.getAsJsonObject().get(ARRAY_KEY);
            if (array == null || !array.isJsonArray()) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            for (JsonElement element : array.getAsJsonArray()) {
                if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                    values.add(element.getAsString());
                }
            }
            return normalize(values, MAX_ENTRIES);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** Stable JSON encoding with only the query array and a version marker. */
    public static String encode(List<String> queries) {
        List<String> normalized = normalize(queries, MAX_ENTRIES);
        JsonObject root = new JsonObject();
        root.addProperty("v", VERSION);
        JsonArray array = new JsonArray();
        for (String query : normalized) {
            array.add(query);
        }
        root.add(ARRAY_KEY, array);
        return root + "\n";
    }

    /** Reads the local history; missing or corrupt files simply mean "no history". */
    public static List<String> read(Path configDir) {
        return decode(ClientStorageIO.readOrNull(fileFor(configDir)));
    }

    /** Appends one EditBox value, returning the resulting (capped, de-duplicated) history. */
    public static List<String> add(Path configDir, String query) {
        if (!isSafeQuery(query)) {
            return read(configDir);
        }
        List<String> current = new ArrayList<>(read(configDir));
        current.removeIf(existing -> existing.equalsIgnoreCase(query.trim()));
        current.add(query.trim());
        List<String> updated = normalize(current, MAX_ENTRIES);
        try {
            ClientStorageIO.atomicWrite(fileFor(configDir), encode(updated));
        } catch (IOException | RuntimeException ignored) {
            // Local history is cosmetic; a failed write must never interrupt the game.
        }
        return updated;
    }

    /** Clears the history file. Returns false only when the file could not be removed. */
    public static boolean clear(Path configDir) {
        try {
            return Files.deleteIfExists(fileFor(configDir));
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }
}
