package com.pengin0503.itemmilestonelog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Pure-Java local "carry" store for F-150-85 (N198): exports the player's own pins and personal
 * target ids to {@code carry.json} and reads them back as local-only candidates for the existing
 * {@code /milestonelog add} command. Nothing here writes to the server, SavedData or a packet;
 * the player must explicitly add a target on each server (D-150-231).
 *
 * <p>Must stay Java 17 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class ClientCarryStore {
    /** Same cap as {@link PinStore#MAX_PINS}. */
    public static final int MAX_ITEMS = 64;
    /** File name inside {@link ClientStorageIO#CLIENT_DIR}. */
    public static final String FILE_NAME = "carry.json";
    private static final String VERSION = "1";
    /** Display-only candidates populated by {@link #importCandidates(Path)} (F-150-85-1). */
    private static volatile List<Entry> imported = List.of();

    private ClientCarryStore() {
    }

    /** One canonical target id. No player name, UUID or chat text is stored. */
    public record Entry(String itemId, int count) {
        public Entry {
            itemId = itemId == null ? "" : itemId;
            count = Math.max(1, count);
        }

        public String encoded() {
            return count > 1 ? itemId + " x " + count : itemId;
        }
    }

    /** Resolves the carry file below {@code configDir}. */
    public static Path fileFor(Path configDir) {
        return ClientStorageIO.clientDir(configDir).resolve(FILE_NAME);
    }

    /**
     * Deduplicates by encoded id and keeps at most {@link #MAX_ITEMS} entries. On overflow the
     * oldest (earliest) entries are dropped first, so the newest tail survives (§1.4-3).
     */
    public static List<Entry> merge(Collection<Entry> first, Collection<Entry> second) {
        Set<String> seen = new LinkedHashSet<>();
        List<Entry> result = new ArrayList<>();
        for (Collection<Entry> part : List.<Collection<Entry>>of(first, second)) {
            if (part == null) {
                continue;
            }
            for (Entry entry : part) {
                if (entry == null || !ClientExportLogic.isSafeItemId(entry.itemId())) {
                    continue;
                }
                if (seen.add(entry.encoded())) {
                    result.add(entry);
                }
            }
        }
        if (result.size() > MAX_ITEMS) {
            return List.copyOf(result.subList(result.size() - MAX_ITEMS, result.size()));
        }
        return List.copyOf(result);
    }

    /** Renders the portable JSON with an explicit local-only marker. */
    public static String render(List<Entry> entries) {
        JsonObject root = new JsonObject();
        root.addProperty("v", VERSION);
        root.addProperty("localOnly", true);
        JsonArray items = new JsonArray();
        for (Entry entry : merge(entries, List.of())) {
            JsonObject item = new JsonObject();
            item.addProperty("id", entry.itemId());
            item.addProperty("count", entry.count());
            items.add(item);
        }
        root.add("items", items);
        return root.toString();
    }

    /** Writes pins + personal targets locally. Never sends anything anywhere. */
    public static int export(Path configDir, Collection<Entry> pins, Collection<Entry> personalTargets)
            throws IOException {
        Objects.requireNonNull(configDir, "configDir");
        List<Entry> entries = merge(pins, personalTargets);
        ClientStorageIO.atomicWrite(fileFor(configDir), render(entries));
        return entries.size();
    }

    /** Reads local candidates leniently; malformed JSON becomes an empty list. */
    public static List<Entry> read(Path configDir) {
        String raw = ClientStorageIO.readOrNull(fileFor(configDir));
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) {
                return List.of();
            }
            JsonElement items = parsed.getAsJsonObject().get("items");
            if (items == null || !items.isJsonArray()) {
                return List.of();
            }
            List<Entry> result = new ArrayList<>();
            for (JsonElement element : items.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                JsonElement id = item.get("id");
                JsonElement count = item.get("count");
                if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) {
                    continue;
                }
                int value = 1;
                if (count != null && count.isJsonPrimitive()) {
                    try {
                        value = count.getAsInt();
                    } catch (RuntimeException ignored) {
                        value = 1;
                    }
                }
                result.add(new Entry(id.getAsString(), value));
            }
            return merge(result, List.of());
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** The last imported display-only candidate list; never sent to a server. */
    public static List<Entry> imported() {
        return imported;
    }

    /** Drops the in-memory candidate list (client-only; no file is changed). */
    public static void clearImported() {
        imported = List.of();
    }

    /**
     * Reads the local file and installs the entries as the display-only candidate list used by
     * {@code /imlclient carry list}. No server write or {@code /milestonelog add} is performed
     * here: the player must add each target explicitly on every server (D-150-231).
     */
    public static List<Entry> importCandidates(Path configDir) {
        List<Entry> entries = read(configDir);
        imported = entries;
        return entries;
    }
}
