package com.pengin0503.itemmilestonelog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Target item presets (built-in plus custom JSON presets in {@code config/itemmilestonelog/presets/}).
 */
public final class Presets {
    public record PresetDefinition(String id, String displayName, List<String> items, boolean builtin) {
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
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile Map<String, PresetDefinition> loadedPresets = builtinMap();

    private Presets() {
    }

    private static Map<String, PresetDefinition> builtinMap() {
        Map<String, PresetDefinition> map = new TreeMap<>();
        for (Map.Entry<String, List<String>> entry : PRESETS.entrySet()) {
            map.put(entry.getKey(), new PresetDefinition(entry.getKey(), entry.getKey(), entry.getValue(), true));
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    public static Path presetsDir() {
        return FMLPaths.CONFIGDIR.get().resolve(ItemMilestoneLog.MOD_ID).resolve("presets");
    }

    public static boolean isValidId(String id) {
        return id != null && VALID_ID_PATTERN.matcher(id).matches();
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
            next.put(entry.getKey(), new PresetDefinition(entry.getKey(), entry.getKey(), entry.getValue(), true));
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
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Ignoring preset file {} (filename must match [a-z0-9_-]{{1,32}})", fileName);
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

        String displayName = id;
        if (root.has("name") && root.get("name").isJsonPrimitive() && root.get("name").getAsJsonPrimitive().isString()) {
            String rawName = root.get("name").getAsString().trim();
            if (!rawName.isEmpty()) {
                displayName = rawName;
            }
        }

        Set<String> deduped = new LinkedHashSet<>();
        JsonArray arr = root.getAsJsonArray("items");
        for (JsonElement elem : arr) {
            if (!elem.isJsonPrimitive() || !elem.getAsJsonPrimitive().isString()) {
                continue;
            }
            String rawItem = elem.getAsString().trim();
            if (rawItem.isEmpty()) {
                continue;
            }
            ResourceLocation rl = ResourceLocation.tryParse(rawItem);
            if (rl != null && !"minecraft:air".equals(rl.toString())) {
                deduped.add(rl.toString());
            }
        }

        if (deduped.isEmpty()) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping preset file {}: 0 valid item IDs", fileName);
            return null;
        }

        return new PresetDefinition(id, displayName, List.copyOf(deduped), false);
    }

    /** Saves a custom preset file to the default config directory and updates the in-memory table. */
    public static boolean saveCustom(String id, List<String> items) throws IOException {
        return saveCustomTo(presetsDir(), id, items);
    }

    /** Saves a custom preset file to {@code dir} and updates the in-memory table. Returns true if overwritten. */
    public static synchronized boolean saveCustomTo(Path dir, String id, List<String> items) throws IOException {
        if (!isValidId(id)) {
            throw new IllegalArgumentException("Invalid preset ID: " + id);
        }
        if (isBuiltin(id)) {
            throw new IllegalArgumentException("Cannot overwrite built-in preset: " + id);
        }
        Files.createDirectories(dir);
        Path target = dir.resolve(id + ".json");
        boolean existed = Files.exists(target);

        Set<String> deduped = new LinkedHashSet<>();
        for (String item : items) {
            ResourceLocation rl = ResourceLocation.tryParse(item);
            if (rl != null && !"minecraft:air".equals(rl.toString())) {
                deduped.add(rl.toString());
            }
        }
        if (deduped.isEmpty()) {
            throw new IllegalArgumentException("No valid items to save");
        }

        JsonObject json = new JsonObject();
        json.addProperty("name", id);
        JsonArray arr = new JsonArray();
        for (String item : deduped) {
            arr.add(item);
        }
        json.add("items", arr);

        try (Writer writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            GSON.toJson(json, writer);
        }

        Map<String, PresetDefinition> next = new TreeMap<>(loadedPresets);
        next.put(id, new PresetDefinition(id, id, List.copyOf(deduped), false));
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
        if (loadedPresets.containsKey(id)) {
            Map<String, PresetDefinition> next = new TreeMap<>(loadedPresets);
            next.remove(id);
            loadedPresets = Collections.unmodifiableMap(new LinkedHashMap<>(next));
        }
        return deleted;
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
}
