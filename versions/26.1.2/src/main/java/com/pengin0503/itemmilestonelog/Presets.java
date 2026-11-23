package com.pengin0503.itemmilestonelog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import net.neoforged.fml.loading.FMLPaths;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Target item presets (built-in plus custom JSON presets in {@code config/itemmilestonelog/presets/}).
 */
public final class Presets {
    public record PresetDefinition(String id, String displayName, List<TargetKey> items, boolean builtin) {
    }

    public static final Map<String, List<String>> PRESETS = Map.of(
            "atm_star", List.of(
                    "allthetweaks:atm_star",
                    "allthetweaks:dragon_soul",
                    "allthetweaks:dimensional_seed",
                    "allthetweaks:improbable_probability_device",
                    "allthetweaks:nexium_emitter",
                    "allthetweaks:oblivion_shard",
                    "allthetweaks:patrick_star",
                    "allthetweaks:philosophers_fuel",
                    "allthetweaks:pulsating_black_hole",
                    "allthetweaks:withers_compass"
            ),
            "vanilla_endgame", List.of(
                    "minecraft:diamond",
                    "minecraft:netherite_ingot",
                    "minecraft:elytra",
                    "minecraft:nether_star",
                    "minecraft:beacon",
                    "minecraft:dragon_egg",
                    "minecraft:enchanted_golden_apple",
                    "minecraft:totem_of_undying"
            )
    );

    private static final Pattern VALID_ID_PATTERN = Pattern.compile("^[a-z0-9_-]{1,32}$");
    /** Command literals under /milestone preset; IDs equal to these could never be applied from chat. */
    private static final Set<String> RESERVED_IDS = Set.of("list", "save", "delete", "reload");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile Map<String, PresetDefinition> loadedPresets = builtinMap();

    private Presets() {
    }

    private static List<TargetKey> toBuiltinTargets(List<String> itemIds) {
        List<TargetKey> list = new ArrayList<>(itemIds.size());
        for (String id : itemIds) {
            list.add(TargetKey.of(id));
        }
        return List.copyOf(list);
    }

    private static Map<String, PresetDefinition> builtinMap() {
        Map<String, PresetDefinition> map = new TreeMap<>();
        for (Map.Entry<String, List<String>> entry : PRESETS.entrySet()) {
            map.put(entry.getKey(), new PresetDefinition(entry.getKey(), entry.getKey(), toBuiltinTargets(entry.getValue()), true));
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    public static Path presetsDir() {
        return FMLPaths.CONFIGDIR.get().resolve(ItemMilestoneLog.MOD_ID).resolve("presets");
    }

    public static boolean isValidId(String id) {
        return id != null && VALID_ID_PATTERN.matcher(id).matches() && !RESERVED_IDS.contains(id);
    }

    public static boolean isBuiltin(String id) {
        return id != null && PRESETS.containsKey(id);
    }

    /** Reloads custom presets from the default config directory. */
    public static int reload() {
        return reloadFrom(presetsDir());
    }

    /** Reloads custom presets from {@code dir} (creates the directory if absent). */
    public static synchronized int reloadFrom(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            ItemMilestoneLog.LOGGER.error("[ItemMilestoneLog] Could not create presets directory {}: {}", dir, e.getMessage());
            loadedPresets = builtinMap();
            return 0;
        }

        Map<String, PresetDefinition> next = new TreeMap<>();
        for (Map.Entry<String, List<String>> entry : PRESETS.entrySet()) {
            next.put(entry.getKey(), new PresetDefinition(entry.getKey(), entry.getKey(), toBuiltinTargets(entry.getValue()), true));
        }

        int customCount = 0;
        List<Path> jsonFiles = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(jsonFiles::add);
        } catch (IOException e) {
            ItemMilestoneLog.LOGGER.error("[ItemMilestoneLog] Could not list presets directory {}: {}", dir, e.getMessage());
            loadedPresets = Collections.unmodifiableMap(new LinkedHashMap<>(next));
            return 0;
        }

        for (Path file : jsonFiles) {
            String fileName = file.getFileName().toString();
            String id = fileName.substring(0, fileName.length() - ".json".length());
            if (isBuiltin(id)) {
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Ignoring preset file {} because '{}' is a built-in preset", fileName, id);
                continue;
            }
            if (!isValidId(id)) {
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Ignoring preset file {} (filename must match [a-z0-9_-]{1,32})", fileName);
                continue;
            }
            PresetDefinition parsed = parsePresetFile(file, fileName, id);
            if (parsed != null) {
                next.put(id, parsed);
                customCount++;
            }
        }

        loadedPresets = Collections.unmodifiableMap(new LinkedHashMap<>(next));
        return customCount;
    }

    private static int parseStrictJsonCount(JsonElement countEl) {
        if (countEl == null || !countEl.isJsonPrimitive()) {
            return -1;
        }
        JsonPrimitive prim = countEl.getAsJsonPrimitive();
        if (!prim.isNumber()) {
            return -1;
        }
        String raw = prim.getAsString();
        return TargetKey.tryParseCount(raw);
    }

    private static String extractDisplayName(JsonObject root, String fallbackId) {
        if (root.has("name") && root.get("name").isJsonPrimitive() && root.get("name").getAsJsonPrimitive().isString()) {
            String rawName = root.get("name").getAsString().trim();
            if (!rawName.isEmpty()) {
                // Cap to the 256-char sync limit so a hand-written preset cannot break the sync payload for clients.
                return TextEscaping.truncateUtf16(rawName, 256);
            }
        }
        return fallbackId;
    }

    private static String readExistingDisplayName(Path file, String id) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement el = JsonParser.parseReader(reader);
            if (el.isJsonObject()) {
                return extractDisplayName(el.getAsJsonObject(), id);
            }
        } catch (Exception ignored) {
            // Corrupt or unreadable existing file: fall back to the preset ID.
        }
        return id;
    }

    private static PresetDefinition parsePresetFile(Path file, String fileName, String id) {
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement el = JsonParser.parseReader(reader);
            if (!el.isJsonObject()) {
                ItemMilestoneLog.LOGGER.error("[ItemMilestoneLog] Preset file {} must contain a JSON object", fileName);
                return null;
            }
            root = el.getAsJsonObject();
        } catch (Exception e) {
            ItemMilestoneLog.LOGGER.error("[ItemMilestoneLog] Failed to parse preset file {}: {}", fileName, e.getMessage());
            return null;
        }

        if (!root.has("items") || !root.get("items").isJsonArray()) {
            ItemMilestoneLog.LOGGER.error("[ItemMilestoneLog] Preset file {} is missing required 'items' array", fileName);
            return null;
        }

        String displayName = extractDisplayName(root, id);

        Set<TargetKey> deduped = new LinkedHashSet<>();
        JsonArray arr = root.getAsJsonArray("items");
        for (JsonElement elem : arr) {
            if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isString()) {
                String rawItem = elem.getAsString().trim();
                if (rawItem.isEmpty()) {
                    continue;
                }
                TargetKey normalized = TargetKey.tryParseNormalized(rawItem, false, true);
                if (normalized != null) {
                    deduped.add(normalized);
                } else {
                    ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid item '{}' in preset {}", rawItem, fileName);
                }
            } else if (elem.isJsonObject()) {
                JsonObject obj = elem.getAsJsonObject();
                if (!obj.has("item") || !obj.get("item").isJsonPrimitive() || !obj.get("item").getAsJsonPrimitive().isString()) {
                    ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid item object in preset {}", fileName);
                    continue;
                }
                String rawItem = obj.get("item").getAsString().trim();
                String normalizedId = TargetKey.normalizeItemId(rawItem, false);
                if (normalizedId == null || "minecraft:air".equals(normalizedId)) {
                    ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid item '{}' in preset {}", rawItem, fileName);
                    continue;
                }
                int count = TargetKey.MIN_COUNT;
                if (obj.has("count")) {
                    count = parseStrictJsonCount(obj.get("count"));
                    if (count < TargetKey.MIN_COUNT) {
                        ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping item '{}' with invalid count in preset {}", rawItem, fileName);
                        continue;
                    }
                }
                TargetKey normalized = TargetKey.normalize(normalizedId, count, false, true);
                if (normalized != null) {
                    deduped.add(normalized);
                }
            }
        }

        if (deduped.isEmpty()) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping preset file {}: 0 valid item IDs", fileName);
            return null;
        }

        return new PresetDefinition(id, displayName, List.copyOf(deduped), false);
    }

    /** Saves a custom preset file to the default config directory and updates the in-memory table. */
    public static boolean saveCustom(String id, List<TargetKey> items) throws IOException {
        return saveCustomTo(presetsDir(), id, items);
    }

    /** Saves a custom preset file to {@code dir} and updates the in-memory table. Returns true if overwritten. */
    public static synchronized boolean saveCustomTo(Path dir, String id, List<TargetKey> items) throws IOException {
        if (!isValidId(id)) {
            throw new IllegalArgumentException("Invalid preset ID: " + id);
        }
        if (isBuiltin(id)) {
            throw new IllegalArgumentException("Cannot overwrite built-in preset: " + id);
        }
        Files.createDirectories(dir);
        Path target = dir.resolve(id + ".json");
        boolean existed = Files.exists(target);
        String displayName = existed ? readExistingDisplayName(target, id) : id;

        Set<TargetKey> deduped = new LinkedHashSet<>();
        for (TargetKey key : items) {
            if (key == null) {
                continue;
            }
            TargetKey normalized = TargetKey.normalize(key.itemId(), key.count(), false, true);
            if (normalized != null) {
                deduped.add(normalized);
            }
        }
        if (deduped.isEmpty()) {
            throw new IllegalArgumentException("No valid items to save");
        }

        JsonObject json = new JsonObject();
        json.addProperty("name", displayName);
        JsonArray arr = new JsonArray();
        for (TargetKey key : deduped) {
            if (key.count() > TargetKey.MIN_COUNT) {
                JsonObject obj = new JsonObject();
                obj.addProperty("item", key.itemId());
                obj.addProperty("count", key.count());
                arr.add(obj);
            } else {
                arr.add(key.itemId());
            }
        }
        json.add("items", arr);

        Path temp = Files.createTempFile(dir, "preset-" + id + ".", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(json, writer);
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
            }
            throw e;
        }

        Map<String, PresetDefinition> next = new TreeMap<>(loadedPresets);
        next.put(id, new PresetDefinition(id, displayName, List.copyOf(deduped), false));
        loadedPresets = Collections.unmodifiableMap(new LinkedHashMap<>(next));
        return existed;
    }

    /** Deletes a custom preset file from the default config directory and updates the in-memory table. */
    public static boolean deleteCustom(String id) throws IOException {
        return deleteCustomFrom(presetsDir(), id);
    }

    /** Deletes a custom preset file from {@code dir} and updates the in-memory table. */
    public static synchronized boolean deleteCustomFrom(Path dir, String id) throws IOException {
        if (!isValidId(id) || isBuiltin(id)) {
            return false;
        }
        Path target = dir.resolve(id + ".json");
        boolean deleted = Files.deleteIfExists(target);
        boolean removedFromMemory = false;
        if (loadedPresets.containsKey(id)) {
            Map<String, PresetDefinition> next = new TreeMap<>(loadedPresets);
            next.remove(id);
            loadedPresets = Collections.unmodifiableMap(new LinkedHashMap<>(next));
            removedFromMemory = true;
        }
        return removedFromMemory || deleted;
    }

    public static PresetDefinition get(String id) {
        return id != null ? loadedPresets.get(id) : null;
    }

    public static List<PresetDefinition> all() {
        return List.copyOf(loadedPresets.values());
    }

    /** Preset IDs in a stable (alphabetical) order for suggestions, messages and the GUI. */
    public static List<String> names() {
        return List.copyOf(loadedPresets.keySet());
    }

    /**
     * Computes {@code R(S)} for a preset (F5 / F16): the targets in {@code preset} whose item
     * exists in the registry at evaluation time according to {@code itemExists}.
     */
    public static List<TargetKey> resolvableTargets(
            @Nullable PresetDefinition preset,
            @Nullable Predicate<String> itemExists) {
        if (preset == null) {
            return List.of();
        }
        return resolvableTargets(preset.items(), itemExists);
    }

    /**
     * Computes {@code R(S)} for a preset target list (F5 / F16): filters {@code presetItems} to
     * non-null targets whose {@link TargetKey#itemId()} satisfies {@code itemExists}, preserving
     * order and deduplicating.
     */
    public static List<TargetKey> resolvableTargets(
            @Nullable Collection<TargetKey> presetItems,
            @Nullable Predicate<String> itemExists) {
        if (presetItems == null || presetItems.isEmpty() || itemExists == null) {
            return List.of();
        }
        Set<TargetKey> resolvable = new LinkedHashSet<>();
        for (TargetKey key : presetItems) {
            if (key != null && itemExists.test(key.itemId())) {
                resolvable.add(key);
            }
        }
        return resolvable.isEmpty() ? List.of() : List.copyOf(resolvable);
    }

    /**
     * Pure judgment function for F5 preset completion: returns all presets from {@code allPresets}
     * newly completed by recording {@code justRecordedKey}.
     *
     * <p>A preset {@code S} is newly completed iff:
     * <ul>
     *   <li>{@code presetsEnabled} is {@code true},</li>
     *   <li>{@code R(S)} (targets in {@code S} whose item satisfies {@code itemExists}) is non-empty,</li>
     *   <li>{@code justRecordedKey} is in {@code R(S)} (exact item + count match),</li>
     *   <li>{@code S.id()} is not in {@code alreadyCompletedIds}, and</li>
     *   <li>every key in {@code R(S)} is present in {@code recordedKeys} with an exact {@link TargetKey} match.</li>
     * </ul>
     */
    public static List<PresetDefinition> findNewlyCompletedPresets(
            @Nullable TargetKey justRecordedKey,
            @Nullable Set<TargetKey> recordedKeys,
            @Nullable Collection<PresetDefinition> allPresets,
            @Nullable Predicate<String> itemExists,
            @Nullable Set<String> alreadyCompletedIds,
            boolean presetsEnabled) {
        if (!presetsEnabled
                || justRecordedKey == null
                || recordedKeys == null
                || recordedKeys.isEmpty()
                || allPresets == null
                || allPresets.isEmpty()
                || itemExists == null) {
            return List.of();
        }
        List<PresetDefinition> completed = new ArrayList<>();
        for (PresetDefinition preset : allPresets) {
            if (preset == null || preset.id() == null) {
                continue;
            }
            if (alreadyCompletedIds != null && alreadyCompletedIds.contains(preset.id())) {
                continue;
            }
            List<TargetKey> resolvable = resolvableTargets(preset.items(), itemExists);
            if (resolvable.isEmpty() || !resolvable.contains(justRecordedKey)) {
                continue;
            }
            if (recordedKeys.containsAll(resolvable)) {
                completed.add(preset);
            }
        }
        return completed.isEmpty() ? List.of() : List.copyOf(completed);
    }

    /**
     * Pure judgment function for F5 preset completion accepting the player's records map.
     */
    public static List<PresetDefinition> findNewlyCompletedPresets(
            @Nullable TargetKey justRecordedKey,
            @Nullable Map<TargetKey, ?> playerRecords,
            @Nullable Collection<PresetDefinition> allPresets,
            @Nullable Predicate<String> itemExists,
            @Nullable Set<String> alreadyCompletedIds,
            boolean presetsEnabled) {
        return findNewlyCompletedPresets(
                justRecordedKey,
                playerRecords != null ? playerRecords.keySet() : null,
                allPresets,
                itemExists,
                alreadyCompletedIds,
                presetsEnabled);
    }

    /**
     * Pure judgment function for F5 preset completion returning the newly completed preset IDs.
     */
    public static List<String> findNewlyCompletedPresetIds(
            @Nullable TargetKey justRecordedKey,
            @Nullable Map<TargetKey, ?> playerRecords,
            @Nullable Collection<PresetDefinition> allPresets,
            @Nullable Predicate<String> itemExists,
            @Nullable Set<String> alreadyCompletedIds,
            boolean presetsEnabled) {
        return findNewlyCompletedPresetIds(
                justRecordedKey,
                playerRecords != null ? playerRecords.keySet() : null,
                allPresets,
                itemExists,
                alreadyCompletedIds,
                presetsEnabled);
    }

    /**
     * Pure judgment function for F5 preset completion returning the newly completed preset IDs.
     */
    public static List<String> findNewlyCompletedPresetIds(
            @Nullable TargetKey justRecordedKey,
            @Nullable Set<TargetKey> recordedKeys,
            @Nullable Collection<PresetDefinition> allPresets,
            @Nullable Predicate<String> itemExists,
            @Nullable Set<String> alreadyCompletedIds,
            boolean presetsEnabled) {
        List<PresetDefinition> completed = findNewlyCompletedPresets(
                justRecordedKey, recordedKeys, allPresets, itemExists, alreadyCompletedIds, presetsEnabled);
        if (completed.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(completed.size());
        for (PresetDefinition preset : completed) {
            ids.add(preset.id());
        }
        return List.copyOf(ids);
    }
}
