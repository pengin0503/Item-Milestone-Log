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
 * Target item presets (built-in plus custom JSON presets in {@code config/itemmilestonelog/presets/},
 * plus F21 datapack presets in {@code data/<namespace>/itemmilestonelog/presets/}). F54: the JSON
 * {@code name} accepts, in addition to a plain string, an object mapping a language code to a
 * display name, resolved per receiving player.
 */
public final class Presets {
    /**
     * Where a {@link PresetDefinition} was loaded from (F21). Not a public API shape; the
     * historical {@link PresetDefinition#builtin()} flag keeps meaning "BUILTIN kind".
     */
    public enum PresetKind {
        /** Hard-coded presets shipped with the mod (highest priority, read-only). */
        BUILTIN,
        /** JSON files in {@code config/itemmilestonelog/presets/} (editable in-game). */
        FILE,
        /** Datapack files in {@code data/<namespace>/itemmilestonelog/presets/} (read-only in-game). */
        DATAPACK
    }

    /**
     * One resolved preset. F54: the display name is a {@link PresetName} — either a single plain
     * string (the same for every language) or an insertion-ordered language code to name map.
     */
    public record PresetDefinition(String id, PresetName name, List<TargetKey> items, PresetKind kind) {
        /** Historical constructor shape: the boolean is the old "builtin" flag, the name a plain string. */
        public PresetDefinition(String id, String displayName, List<TargetKey> items, boolean builtin) {
            this(id, PresetName.ofPlain(displayName), items, builtin ? PresetKind.BUILTIN : PresetKind.FILE);
        }

        public boolean builtin() {
            return kind == PresetKind.BUILTIN;
        }

        /**
         * F54: the display name for {@code langCode} — an exact (lower-cased) language match,
         * then {@code "en_us"}, then the first value in insertion order, then the preset ID.
         * A plain-string name resolves to itself for every language; a null language code
         * (console / RCON) resolves with {@code en_us} priority.
         */
        public String resolveDisplayName(@Nullable String langCode) {
            return name().resolve(langCode, id != null ? id : "");
        }
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

    /** F21: datapack presets live in {@code data/<namespace>/itemmilestonelog/presets/<id>.json}. */
    public static final String DATAPACK_PRESETS_DIR = "itemmilestonelog/presets";

    /** Resolved presets (built-in &gt; config folder &gt; datapack), always sorted by ID. */
    private static volatile Map<String, PresetDefinition> loadedPresets = builtinMap();
    /** Custom presets loaded from the config folder (FILE kind); kept apart so either reload can rebuild. */
    private static volatile Map<String, PresetDefinition> filePresets = Map.of();
    /** Datapack presets set by the server's reload listener (DATAPACK kind), cleared on ServerStoppedEvent. */
    private static volatile Map<String, PresetDefinition> datapackPresets = Map.of();

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
            map.put(entry.getKey(), new PresetDefinition(entry.getKey(), PresetName.ofPlain(entry.getKey()),
                    toBuiltinTargets(entry.getValue()), PresetKind.BUILTIN));
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

    /**
     * F21: whether {@code id} currently resolves to a datapack preset, i.e. it exists only as a
     * datapack file (a same-ID config-folder file would win and make it an editable FILE preset).
     */
    public static boolean isDatapack(String id) {
        PresetDefinition preset = get(id);
        return preset != null && preset.kind() == PresetKind.DATAPACK;
    }

    /** Reloads custom presets from the default config directory. */
    public static int reload() {
        return reloadFrom(presetsDir());
    }

    /**
     * Reloads custom presets from {@code dir} (creates the directory if absent). F21: only the
     * config folder is re-read here; datapack presets reload with the server's data packs.
     */
    public static synchronized int reloadFrom(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            ItemMilestoneLog.LOGGER.error("[ItemMilestoneLog] Could not create presets directory {}: {}", dir, e.getMessage());
            filePresets = Map.of();
            rebuildLoadedPresets();
            return 0;
        }

        Map<String, PresetDefinition> next = new TreeMap<>();
        int customCount = 0;
        List<Path> jsonFiles = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(jsonFiles::add);
        } catch (IOException e) {
            ItemMilestoneLog.LOGGER.error("[ItemMilestoneLog] Could not list presets directory {}: {}", dir, e.getMessage());
            filePresets = Map.of();
            rebuildLoadedPresets();
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

        filePresets = Collections.unmodifiableMap(new LinkedHashMap<>(next));
        rebuildLoadedPresets();
        return customCount;
    }

    /**
     * F21: pure resolution of datapack preset files. Applies the datapack-specific rules:
     * <ul>
     *   <li>files in subfolders are ignored (with a warning) so IDs can never contain {@code /},</li>
     *   <li>file names must be valid, non-reserved preset IDs and must not collide with built-ins,</li>
     *   <li>when several namespaces ship the same ID, the lexicographically first namespace wins
     *       (the losing files are unused, with a warning) — regardless of whether the winning
     *       file parses,</li>
     *   <li>the JSON itself is parsed by the same code path as config-folder preset files.</li>
     * </ul>
     *
     * @param filesByNamespace namespace -> (file name without {@code .json}, may contain {@code /}
     *                         for subfolders) -> file content; iteration order is irrelevant
     * @return datapack presets by ID (DATAPACK kind), sorted by ID
     */
    public static Map<String, PresetDefinition> resolveDatapackPresets(
            @Nullable Map<String, ? extends Map<String, ? extends String>> filesByNamespace) {
        Map<String, PresetDefinition> resolved = new TreeMap<>();
        if (filesByNamespace == null || filesByNamespace.isEmpty()) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(resolved));
        }

        // Sort namespaces so the collision winner is deterministic (lexicographically first).
        Map<String, Map<String, String>> sortedByNamespace = new TreeMap<>();
        for (Map.Entry<String, ? extends Map<String, ? extends String>> nsEntry : filesByNamespace.entrySet()) {
            if (nsEntry.getKey() == null || nsEntry.getValue() == null) {
                continue;
            }
            Map<String, String> files = new TreeMap<>();
            for (Map.Entry<String, ? extends String> fileEntry : nsEntry.getValue().entrySet()) {
                if (fileEntry.getKey() != null && fileEntry.getValue() != null) {
                    files.put(fileEntry.getKey(), fileEntry.getValue());
                }
            }
            sortedByNamespace.put(nsEntry.getKey(), files);
        }

        Map<String, String> winnerNamespaceById = new TreeMap<>();
        for (Map.Entry<String, Map<String, String>> nsEntry : sortedByNamespace.entrySet()) {
            String namespace = nsEntry.getKey();
            for (Map.Entry<String, String> fileEntry : nsEntry.getValue().entrySet()) {
                String fileKey = fileEntry.getKey();
                String sourceLabel = namespace + ":" + DATAPACK_PRESETS_DIR + "/" + fileKey + ".json";
                if (fileKey.contains("/")) {
                    ItemMilestoneLog.LOGGER.warn(
                            "[ItemMilestoneLog] Ignoring datapack preset {} (files in subfolders are ignored; use a flat file name)",
                            sourceLabel);
                    continue;
                }
                String id = fileKey;
                if (isBuiltin(id)) {
                    ItemMilestoneLog.LOGGER.warn(
                            "[ItemMilestoneLog] Ignoring datapack preset {} because '{}' is a built-in preset", sourceLabel, id);
                    continue;
                }
                if (!isValidId(id)) {
                    ItemMilestoneLog.LOGGER.warn(
                            "[ItemMilestoneLog] Ignoring datapack preset {} (file name must match [a-z0-9_-]{1,32} and must not be a reserved word)",
                            sourceLabel);
                    continue;
                }
                String winner = winnerNamespaceById.putIfAbsent(id, namespace);
                if (winner != null) {
                    ItemMilestoneLog.LOGGER.warn(
                            "[ItemMilestoneLog] Ignoring datapack preset {} because preset '{}' is already provided by namespace '{}' (lexicographically first namespace wins)",
                            sourceLabel, id, winner);
                    continue;
                }
                PresetDefinition parsed = parsePresetContent(fileEntry.getValue(), sourceLabel, id, PresetKind.DATAPACK);
                if (parsed != null) {
                    resolved.put(id, parsed);
                }
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(resolved));
    }

    /**
     * F21: replaces the datapack presets (the server's reload listener passes
     * {@link #resolveDatapackPresets} output) and rebuilds the resolved table.
     */
    public static synchronized void setDatapackPresets(@Nullable Map<String, ? extends PresetDefinition> presets) {
        Map<String, PresetDefinition> next = new TreeMap<>();
        if (presets != null) {
            for (Map.Entry<String, ? extends PresetDefinition> entry : presets.entrySet()) {
                String id = entry.getKey();
                PresetDefinition preset = entry.getValue();
                if (id == null || preset == null) {
                    continue;
                }
                next.put(id, preset.kind() == PresetKind.DATAPACK
                        ? preset
                        : new PresetDefinition(id, preset.name(), preset.items(), PresetKind.DATAPACK));
            }
        }
        datapackPresets = Collections.unmodifiableMap(new LinkedHashMap<>(next));
        rebuildLoadedPresets();
    }

    /**
     * F21: clears the datapack presets. Called on {@code ServerStoppedEvent} so a single-player
     * world's presets do not carry over into the next world (they reload with its data packs).
     */
    public static synchronized void clearDatapackPresets() {
        datapackPresets = Map.of();
        rebuildLoadedPresets();
    }

    /** F21: IDs that currently resolve to datapack presets (read-only for commands and the GUI). */
    public static List<String> datapackPresetIds() {
        List<String> ids = new ArrayList<>();
        for (PresetDefinition preset : loadedPresets.values()) {
            if (preset.kind() == PresetKind.DATAPACK) {
                ids.add(preset.id());
            }
        }
        return List.copyOf(ids);
    }

    /**
     * Rebuilds {@link #loadedPresets} from the three sources by F21 priority
     * (built-in &gt; config-folder file &gt; datapack), warning about unused losers.
     * Only called with the class monitor held.
     */
    private static void rebuildLoadedPresets() {
        Map<String, PresetDefinition> next = new TreeMap<>();
        for (Map.Entry<String, PresetDefinition> entry : builtinMap().entrySet()) {
            next.put(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, PresetDefinition> entry : filePresets.entrySet()) {
            next.put(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, PresetDefinition> entry : datapackPresets.entrySet()) {
            String id = entry.getKey();
            PresetDefinition existing = next.get(id);
            if (existing != null) {
                ItemMilestoneLog.LOGGER.warn(
                        "[ItemMilestoneLog] Ignoring datapack preset '{}' because '{}' is already provided by a {} preset",
                        id, id, existing.kind() == PresetKind.BUILTIN ? "built-in" : "config-folder file");
                continue;
            }
            next.put(id, entry.getValue());
        }
        loadedPresets = Collections.unmodifiableMap(new LinkedHashMap<>(next));
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

    /**
     * F54: parses the optional {@code name} field of a preset JSON document. It is either the
     * historical single string (used for every language) or an object mapping a language code
     * to a display name. Entries whose value is not a string are ignored here; {@link PresetName}
     * normalizes keys/values and drops unusable entries. A missing or invalid form — including
     * an object with no valid value — is absent, so the preset ID becomes the display name.
     */
    private static PresetName extractName(JsonObject root) {
        if (root.has("name")) {
            JsonElement nameEl = root.get("name");
            if (nameEl.isJsonPrimitive() && nameEl.getAsJsonPrimitive().isString()) {
                return PresetName.ofPlain(nameEl.getAsString());
            }
            if (nameEl.isJsonObject()) {
                Map<String, String> values = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> entry : nameEl.getAsJsonObject().entrySet()) {
                    JsonElement value = entry.getValue();
                    if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                        values.put(entry.getKey(), value.getAsString());
                    }
                }
                return PresetName.ofLocalized(values);
            }
        }
        return PresetName.absent();
    }

    /**
     * G2/F54: the existing file's {@code name} to carry over when overwriting it — a plain
     * string or an object form, whichever the file validly has. Corrupt or unreadable files
     * yield an absent name (the preset ID).
     */
    private static PresetName readExistingName(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement el = JsonParser.parseReader(reader);
            if (el.isJsonObject()) {
                return extractName(el.getAsJsonObject());
            }
        } catch (Exception ignored) {
            // Corrupt or unreadable existing file: fall back to the preset ID.
        }
        return PresetName.absent();
    }

    private static PresetDefinition parsePresetFile(Path file, String fileName, String id) {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            ItemMilestoneLog.LOGGER.error("[ItemMilestoneLog] Failed to parse preset file {}: {}", fileName, e.getMessage());
            return null;
        }
        return parsePresetContent(content, fileName, id, PresetKind.FILE);
    }

    /**
     * Parses one preset JSON document. Shared by config-folder files (F33) and datapack files
     * (F21), so both sources accept the same format: optional {@code name}, required
     * non-empty {@code items} (strings or {@code {"item": ..., "count": ...}} objects).
     */
    private static PresetDefinition parsePresetContent(String content, String sourceLabel, String id, PresetKind kind) {
        JsonObject root;
        try {
            JsonElement el = JsonParser.parseString(content);
            if (!el.isJsonObject()) {
                ItemMilestoneLog.LOGGER.error("[ItemMilestoneLog] Preset file {} must contain a JSON object", sourceLabel);
                return null;
            }
            root = el.getAsJsonObject();
        } catch (Exception e) {
            ItemMilestoneLog.LOGGER.error("[ItemMilestoneLog] Failed to parse preset file {}: {}", sourceLabel, e.getMessage());
            return null;
        }

        if (!root.has("items") || !root.get("items").isJsonArray()) {
            ItemMilestoneLog.LOGGER.error("[ItemMilestoneLog] Preset file {} is missing required 'items' array", sourceLabel);
            return null;
        }

        PresetName name = extractName(root);

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
                    ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid item '{}' in preset {}", rawItem, sourceLabel);
                }
            } else if (elem.isJsonObject()) {
                JsonObject obj = elem.getAsJsonObject();
                if (!obj.has("item") || !obj.get("item").isJsonPrimitive() || !obj.get("item").getAsJsonPrimitive().isString()) {
                    ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid item object in preset {}", sourceLabel);
                    continue;
                }
                String rawItem = obj.get("item").getAsString().trim();
                String normalizedId = TargetKey.normalizeItemId(rawItem, false);
                if (normalizedId == null || "minecraft:air".equals(normalizedId)) {
                    ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping invalid item '{}' in preset {}", rawItem, sourceLabel);
                    continue;
                }
                int count = TargetKey.MIN_COUNT;
                if (obj.has("count")) {
                    count = parseStrictJsonCount(obj.get("count"));
                    if (count < TargetKey.MIN_COUNT) {
                        ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping item '{}' with invalid count in preset {}", rawItem, sourceLabel);
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
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping preset file {}: 0 valid item IDs", sourceLabel);
            return null;
        }

        return new PresetDefinition(id, name, List.copyOf(deduped), kind);
    }

    /** Saves a custom preset file to the default config directory and updates the in-memory table. */
    public static boolean saveCustom(String id, List<TargetKey> items) throws IOException {
        return saveCustomTo(presetsDir(), id, items);
    }

    /**
     * Saves a custom preset file to {@code dir} and updates the in-memory table. Returns true if
     * overwritten. F21: an ID that currently resolves to a datapack preset is rejected (place a
     * config-folder file by hand to intentionally shadow one).
     */
    public static synchronized boolean saveCustomTo(Path dir, String id, List<TargetKey> items) throws IOException {
        if (!isValidId(id)) {
            throw new IllegalArgumentException("Invalid preset ID: " + id);
        }
        if (isBuiltin(id)) {
            throw new IllegalArgumentException("Cannot overwrite built-in preset: " + id);
        }
        if (isDatapack(id)) {
            throw new IllegalArgumentException("Cannot overwrite datapack preset: " + id);
        }
        Files.createDirectories(dir);
        Path target = dir.resolve(id + ".json");
        boolean existed = Files.exists(target);
        // G2/F54: an existing file's name — plain string or object form — is carried over
        // unchanged; a new preset or an existing file without a valid name falls back to the ID.
        PresetName name = existed ? readExistingName(target) : PresetName.absent();
        if (!name.present()) {
            name = PresetName.ofPlain(id);
        }

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
        if (name.localizedForm()) {
            // G2/F54: write the carried-over object-form name back unchanged (like the string form).
            JsonObject localizedNames = new JsonObject();
            for (Map.Entry<String, String> entry : name.localizedValues().entrySet()) {
                localizedNames.addProperty(entry.getKey(), entry.getValue());
            }
            json.add("name", localizedNames);
        } else {
            json.addProperty("name", name.plainValue() != null ? name.plainValue() : id);
        }
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

        Map<String, PresetDefinition> next = new TreeMap<>(filePresets);
        next.put(id, new PresetDefinition(id, name, List.copyOf(deduped), PresetKind.FILE));
        filePresets = Collections.unmodifiableMap(new LinkedHashMap<>(next));
        rebuildLoadedPresets();
        return existed;
    }

    /** Deletes a custom preset file from the default config directory and updates the in-memory table. */
    public static boolean deleteCustom(String id) throws IOException {
        return deleteCustomFrom(presetsDir(), id);
    }

    /**
     * Deletes a custom preset file from {@code dir}. F21: datapack-resolved IDs are read-only and
     * are rejected; deleting the config file that shadows a datapack preset makes the datapack
     * preset reappear.
     */
    public static synchronized boolean deleteCustomFrom(Path dir, String id) throws IOException {
        if (!isValidId(id) || isBuiltin(id) || isDatapack(id)) {
            return false;
        }
        Path target = dir.resolve(id + ".json");
        boolean deleted = Files.deleteIfExists(target);
        boolean removedFromMemory = filePresets.containsKey(id);
        if (removedFromMemory) {
            Map<String, PresetDefinition> next = new TreeMap<>(filePresets);
            next.remove(id);
            filePresets = Collections.unmodifiableMap(new LinkedHashMap<>(next));
            rebuildLoadedPresets();
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

    /** F16: maximum number of unrecorded rows listed by {@code presetprogress}. */
    public static final int PROGRESS_MAX_ROWS = 50;

    /**
     * Result of {@link #presetProgress} (F16).
     *
     * @param recorded         X: keys of {@code R(S)} present in the player's records (exact {@link TargetKey} match)
     * @param total            Y: {@code |R(S)|}
     * @param unrecordedShown  unrecorded keys of {@code R(S)} in {@link TargetKey} order, at most {@code maxRows}
     * @param unrecordedHidden unrecorded keys beyond {@code maxRows} (the "...and N more" count)
     * @param skipped          distinct preset keys excluded from {@code R(S)} because their item does not exist
     */
    public record PresetProgress(int recorded, int total, List<TargetKey> unrecordedShown,
                                 int unrecordedHidden, int skipped) {
        /**
         * Every key of a non-empty {@code R(S)} is recorded. An empty {@code R(S)} is never
         * "complete", matching F5 (no "0 / 0 completed").
         */
        public boolean complete() {
            return total > 0 && recorded == total;
        }
    }

    /**
     * Pure progress computation for F16 {@code presetprogress}. Uses the same {@code R(S)} as the
     * F5 completion judgment ({@link #resolvableTargets(Collection, Predicate)}) and the same exact
     * {@link TargetKey} match against {@code recordedKeys}, so X / Y agree with F5.
     */
    public static PresetProgress presetProgress(
            @Nullable PresetDefinition preset,
            @Nullable Set<TargetKey> recordedKeys,
            @Nullable Predicate<String> itemExists,
            int maxRows) {
        if (preset == null || preset.items() == null) {
            return new PresetProgress(0, 0, List.of(), 0, 0);
        }
        Set<TargetKey> distinct = new LinkedHashSet<>();
        for (TargetKey key : preset.items()) {
            if (key != null) {
                distinct.add(key);
            }
        }
        List<TargetKey> resolvable = resolvableTargets(distinct, itemExists);
        Set<TargetKey> recorded = recordedKeys != null ? recordedKeys : Set.of();
        int recordedCount = 0;
        List<TargetKey> unrecorded = new ArrayList<>();
        for (TargetKey key : resolvable) {
            if (recorded.contains(key)) {
                recordedCount++;
            } else {
                unrecorded.add(key);
            }
        }
        unrecorded.sort(TargetKey::compareTo);
        int limit = Math.max(0, Math.min(maxRows, unrecorded.size()));
        return new PresetProgress(
                recordedCount,
                resolvable.size(),
                List.copyOf(unrecorded.subList(0, limit)),
                unrecorded.size() - limit,
                distinct.size() - resolvable.size());
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
