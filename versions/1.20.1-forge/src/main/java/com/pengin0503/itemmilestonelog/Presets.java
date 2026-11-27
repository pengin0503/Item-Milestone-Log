package com.pengin0503.itemmilestonelog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.MalformedJsonException;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import net.minecraftforge.fml.loading.FMLPaths;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.StringReader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
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
     *
     * <p>G-B (F-140-5): the definition also carries the optional format fields. {@link #items()}
     * is always the final, includes-expanded list; {@link #includes()} keeps the referenced IDs
     * for round-tripping and diagnostics. {@code nextPresets} and {@code unlockAfterHours} are
     * parsed and carried only in this PR (their behavior arrives with G-C).
     *
     * <p>F-140-20: {@link #problems()} collects the element-level validation problems (invalid
     * elements, includes failures, groups failures) instead of only logging them. Lenient mode
     * ignores the list; strict mode uses it to reject the whole custom preset.
     */
    public record PresetDefinition(
            String id,
            PresetName name,
            List<TargetKey> items,
            PresetKind kind,
            List<String> includes,
            PresetName description,
            @Nullable String category,
            int order,
            @Nullable Integer version,
            List<String> requiredMods,
            List<PresetGroup> groups,
            List<String> nextPresets,
            @Nullable Double unlockAfterHours,
            List<String> problems) {

        public PresetDefinition {
            items = items == null ? List.of() : List.copyOf(items);
            includes = includes == null ? List.of() : List.copyOf(includes);
            description = description == null ? PresetName.absent() : description;
            requiredMods = requiredMods == null ? List.of() : List.copyOf(requiredMods);
            groups = groups == null ? List.of() : List.copyOf(groups);
            nextPresets = nextPresets == null ? List.of() : List.copyOf(nextPresets);
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        /**
         * Historical full-field shape (F-140-5..11): no collected validation problems. Kept so
         * the existing callers and tests do not need a 14th argument.
         */
        public PresetDefinition(String id, PresetName name, List<TargetKey> items, PresetKind kind,
                                List<String> includes, PresetName description, @Nullable String category,
                                int order, @Nullable Integer version, List<String> requiredMods,
                                List<PresetGroup> groups, List<String> nextPresets,
                                @Nullable Double unlockAfterHours) {
            this(id, name, items, kind, includes, description, category, order, version, requiredMods,
                    groups, nextPresets, unlockAfterHours, List.of());
        }

        /**
         * Historical 4-argument shape (F-140-5): fills every new field with its documented
         * default so existing callers and tests keep compiling unchanged.
         */
        public PresetDefinition(String id, PresetName name, List<TargetKey> items, PresetKind kind) {
            this(id, name, items, kind, List.of(), PresetName.absent(), null, 0, null,
                    List.of(), List.of(), List.of(), null);
        }

        /** Historical constructor shape: the boolean is the old "builtin" flag, the name a plain string. */
        public PresetDefinition(String id, String displayName, List<TargetKey> items, boolean builtin) {
            this(id, PresetName.ofPlain(displayName), items, builtin ? PresetKind.BUILTIN : PresetKind.FILE);
        }

        public boolean builtin() {
            return kind == PresetKind.BUILTIN;
        }

        /** Returns a copy of this definition with a different source kind (F21 priority handling). */
        public PresetDefinition withKind(PresetKind newKind) {
            return new PresetDefinition(id, name, items, newKind, includes, description, category, order,
                    version, requiredMods, groups, nextPresets, unlockAfterHours, problems);
        }

        /** Returns a copy with the includes-expanded items and validated groups. */
        public PresetDefinition withResolvedItemsAndGroups(List<TargetKey> resolvedItems, List<PresetGroup> resolvedGroups) {
            return withResolvedItemsAndGroups(resolvedItems, resolvedGroups, problems);
        }

        /** Returns a copy with the includes-expanded items, validated groups and collected problems. */
        public PresetDefinition withResolvedItemsAndGroups(List<TargetKey> resolvedItems,
                                                           List<PresetGroup> resolvedGroups,
                                                           List<String> collectedProblems) {
            return new PresetDefinition(id, name, resolvedItems, kind, includes, description, category, order,
                    version, requiredMods, resolvedGroups, nextPresets, unlockAfterHours, collectedProblems);
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
    private static final Set<String> RESERVED_IDS = Set.of(
            "list", "save", "delete", "reload", "show", "diff", "share", "importcode");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * F-140-7 / B2-D5: preset list order is {@code order} ascending, then ID. With every
     * {@code order} at its default 0 this is exactly the historical ID order.
     */
    private static final Comparator<PresetDefinition> LIST_ORDER =
            Comparator.comparingInt(PresetDefinition::order).thenComparing(PresetDefinition::id);

    /**
     * F-140-20: collects the element-level problems of one preset while lenient parsing keeps
     * logging and skipping them exactly as before. The same code path feeds both modes; strict
     * mode only inspects the collected list, so there is no duplicate lenient/strict parser.
     */
    private static final class Problems {
        private final List<String> collected = new ArrayList<>();

        void warn(String message, Object... args) {
            String text = args.length == 0 ? message : substitutePlaceholders(message, args);
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] {}", text);
            collected.add(text);
        }

        /**
         * Substitutes SLF4J-style {@code {}} placeholders with {@code args} in a single pass.
         * Replacement text is appended as-is and never re-scanned, so an argument that itself
         * contains {@code {}} cannot consume a later argument. Extra arguments are ignored and
         * missing arguments leave their placeholders untouched.
         */
        private static String substitutePlaceholders(String message, Object[] args) {
            StringBuilder text = null;
            int argIndex = 0;
            int from = 0;
            while (argIndex < args.length) {
                int placeholder = message.indexOf("{}", from);
                if (placeholder < 0) {
                    break;
                }
                if (text == null) {
                    text = new StringBuilder(message.length() + 16);
                }
                text.append(message, from, placeholder).append(args[argIndex++]);
                from = placeholder + 2;
            }
            if (text == null) {
                return message;
            }
            return text.append(message, from, message.length()).toString();
        }

        List<String> list() {
            return List.copyOf(collected);
        }
    }

    /** F21: datapack presets live in {@code data/<namespace>/itemmilestonelog/presets/<id>.json}. */
    public static final String DATAPACK_PRESETS_DIR = "itemmilestonelog/presets";

    /** Resolved presets (built-in &gt; config folder &gt; datapack), ordered by {@link #LIST_ORDER}. */
    private static volatile Map<String, PresetDefinition> loadedPresets = builtinMap();
    /** Custom presets loaded from the config folder (FILE kind); kept apart so either reload can rebuild. */
    private static volatile Map<String, PresetDefinition> filePresets = Map.of();
    /** Datapack presets set by the server's reload listener (DATAPACK kind), cleared on ServerStoppedEvent. */
    private static volatile Map<String, PresetDefinition> datapackPresets = Map.of();
    /** Subset of {@link #loadedPresets} with an {@code unlockAfterHours} value, rebuilt with the table. */
    private static volatile List<PresetDefinition> unlockAfterHoursPresets =
            loadedPresets.values().stream().filter(preset -> preset.unlockAfterHours() != null).toList();

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
        return reloadFrom(dir, Config.strictPresetValidation());
    }

    /**
     * F-140-20: reload with an explicit strict flag. Production code uses
     * {@link #reloadFrom(Path)} (the Config value); the overload exists so unit tests can
     * exercise both modes without loading the Minecraft/NeoForge config.
     */
    static synchronized int reloadFrom(Path dir, boolean strict) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            // SF-12: a directory error must not discard already loaded custom presets.
            ItemMilestoneLog.LOGGER.error("[ItemMilestoneLog] Could not create presets directory {}: {}", dir, e.getMessage());
            return 0;
        }

        Map<String, PresetDefinition> next = new TreeMap<>();
        List<Path> jsonFiles = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(jsonFiles::add);
        } catch (IOException e) {
            // SF-12: keep the previous in-memory presets; the files are still intact.
            ItemMilestoneLog.LOGGER.error("[ItemMilestoneLog] Could not list presets directory {}: {}", dir, e.getMessage());
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
            }
        }

        filePresets = Collections.unmodifiableMap(new LinkedHashMap<>(next));
        rebuildLoadedPresets(strict);
        // F-140-6: a file whose includes expansion ends up empty is not adopted, so do not
        // count it (the warning is emitted by rebuildLoadedPresets).
        int customCount = 0;
        for (String id : filePresets.keySet()) {
            if (loadedPresets.containsKey(id)) {
                customCount++;
            }
        }
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
                        : preset.withKind(PresetKind.DATAPACK));
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
     *
     * <p>F-140-6: after the raw sources are merged, every preset's {@code includes} is resolved
     * against the complete raw set (all sources, regardless of kind) and its {@code items} is
     * replaced with the expanded list. F-140-10: groups are then validated against that expanded
     * list (B2-D6). A preset whose expansion is empty is dropped like the historical
     * "0 valid items" case. Finally the table is ordered by {@code order} then ID (F-140-7 /
     * B2-D5), which is the single supply point used by commands, suggestions and GUI sync.
     *
     * <p>F-140-20: when {@code strict} is true, any collected problem (element-level parse
     * problem, includes failure, or groups failure) rejects the whole file/datapack preset; it
     * is logged as an error and never reaches {@link #loadedPresets}, so listing, applying and
     * auto-apply all treat it as unknown. Built-in presets are never rejected.
     */
    private static void rebuildLoadedPresets() {
        rebuildLoadedPresets(Config.strictPresetValidation());
    }

    private static void rebuildLoadedPresets(boolean strict) {
        Map<String, PresetDefinition> raw = rawPresets(true);

        Map<String, List<TargetKey>> itemsById = new LinkedHashMap<>();
        Map<String, List<String>> includesById = new LinkedHashMap<>();
        for (PresetDefinition preset : raw.values()) {
            itemsById.put(preset.id(), preset.items());
            includesById.put(preset.id(), preset.includes());
        }
        PresetIncludesLogic.Result includeResult = PresetIncludesLogic.resolve(itemsById, includesById);
        for (String warning : includeResult.warnings()) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Preset includes: {}", warning);
        }

        List<PresetDefinition> resolved = new ArrayList<>();
        for (PresetDefinition preset : raw.values()) {
            List<String> problems = new ArrayList<>(preset.problems());
            problems.addAll(includeResult.problems(preset.id()));

            List<TargetKey> expanded = includeResult.items(preset.id());
            if (expanded.isEmpty()) {
                if (strict && !preset.builtin() && !problems.isEmpty()) {
                    logStrictRejection(preset.id(), problems);
                } else {
                    ItemMilestoneLog.LOGGER.warn(
                            "[ItemMilestoneLog] Skipping preset '{}': 0 valid items after includes expansion", preset.id());
                }
                continue;
            }
            PresetGroupLogic.Result groupResult = PresetGroupLogic.validate(preset.groups(), expanded);
            for (String warning : groupResult.warnings()) {
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Preset '{}': {}", preset.id(), warning);
            }
            problems.addAll(groupResult.warnings());
            if (strict && !preset.builtin() && !problems.isEmpty()) {
                logStrictRejection(preset.id(), problems);
                continue;
            }
            resolved.add(preset.withResolvedItemsAndGroups(expanded, groupResult.groups(), problems));
        }

        resolved.sort(LIST_ORDER);
        Map<String, PresetDefinition> next = new LinkedHashMap<>();
        for (PresetDefinition preset : resolved) {
            next.put(preset.id(), preset);
        }
        List<PresetDefinition> unlocked = next.values().stream()
                .filter(preset -> preset.unlockAfterHours() != null)
                .toList();
        loadedPresets = Collections.unmodifiableMap(next);
        unlockAfterHoursPresets = unlocked;
    }

    /** F-140-20: one error line with the preset ID and the first collected problem's reason. */
    private static void logStrictRejection(String id, List<String> problems) {
        ItemMilestoneLog.LOGGER.error(
                "[ItemMilestoneLog] Rejecting preset '{}' because strictPresetValidation is enabled: {}",
                id, problems.get(0));
    }

    /**
     * Merges the raw (not includes-expanded) definitions of all three sources by F21 priority.
     * {@code warnConflicts} is false for auxiliary callers such as {@code saveCustomTo} that only
     * need the map for one expansion and must not repeat the startup/reload warnings.
     */
    private static Map<String, PresetDefinition> rawPresets(boolean warnConflicts) {
        Map<String, PresetDefinition> next = new LinkedHashMap<>();
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
                if (warnConflicts) {
                    ItemMilestoneLog.LOGGER.warn(
                            "[ItemMilestoneLog] Ignoring datapack preset '{}' because '{}' is already provided by a {} preset",
                            id, id, existing.kind() == PresetKind.BUILTIN ? "built-in" : "config-folder file");
                }
                continue;
            }
            next.put(id, entry.getValue());
        }
        return next;
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
        // SF-6: the shipped schema declares count as an integer, which includes integral
        // decimals/exponents (1.0, 1e0). Do not widen TargetKey's encoded-string parser.
        Integer parsed = PresetMetadataLogic.parseStrictInt(raw);
        return parsed != null && TargetKey.isValidCount(parsed) ? parsed : -1;
    }

    /**
     * F54/F-140-7: parses an optional display-name field. It is either the historical single
     * string (used for every language) or an object mapping a language code to a display name.
     * Entries whose value is not a string are ignored here; {@link PresetName} normalizes
     * keys/values and drops unusable entries. A missing or invalid form — including an object
     * with no valid value — is absent, so the caller falls back to the preset ID.
     *
     * @param warnInvalid whether a present-but-wrong-typed field emits a warning (used for
     *                    {@code description}; the historical {@code name} stays silent)
     */
    private static PresetName extractName(
            JsonObject root, String field, @Nullable String sourceLabel, boolean warnInvalid, Problems problems) {
        if (!root.has(field)) {
            return PresetName.absent();
        }
        JsonElement nameEl = root.get(field);
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
        if (warnInvalid) {
            problems.warn(
                    "Preset file {} has an invalid '{}' (must be a string or an object of language -> string); ignoring it",
                    sourceLabel, field);
        }
        return PresetName.absent();
    }

    private static PresetName extractName(JsonObject root) {
        return extractName(root, "name", null, false, new Problems());
    }

    /** G-B: the optional fields carried alongside a preset's items. */
    private record ExtraFields(
            List<String> includes,
            PresetName description,
            @Nullable String category,
            int order,
            @Nullable Integer version,
            List<String> requiredMods,
            List<PresetGroup> groups,
            List<String> nextPresets,
            @Nullable Double unlockAfterHours) {
        private static final ExtraFields EMPTY = new ExtraFields(
                List.of(), PresetName.absent(), null, 0, null, List.of(), List.of(), List.of(), null);
    }

    /** G2/F54 + F-140-11: everything a rewrite carries over from an existing preset file. */
    private record ExistingFields(PresetName name, ExtraFields extra) {
    }

    /**
     * SF-4: the shipped schema never nests deeper than a handful of levels. Reject pathological
     * documents with a streaming, non-recursive scan before Gson builds its tree, so a deeply
     * nested file cannot overflow the stack on parsers that do recurse. Malformed JSON is left
     * to the normal parser to diagnose.
     */
    private static final int MAX_JSON_NESTING = 128;

    private static boolean isTooDeeplyNested(String json) {
        try {
            JsonReader reader = new JsonReader(new StringReader(json));
            int depth = 0;
            while (true) {
                JsonToken token = reader.peek();
                if (token == JsonToken.BEGIN_ARRAY || token == JsonToken.BEGIN_OBJECT) {
                    if (++depth > MAX_JSON_NESTING) {
                        return true;
                    }
                    if (token == JsonToken.BEGIN_ARRAY) {
                        reader.beginArray();
                    } else {
                        reader.beginObject();
                    }
                } else if (token == JsonToken.END_ARRAY || token == JsonToken.END_OBJECT) {
                    depth--;
                    if (token == JsonToken.END_ARRAY) {
                        reader.endArray();
                    } else {
                        reader.endObject();
                    }
                } else if (token == JsonToken.END_DOCUMENT) {
                    return false;
                } else if (token == JsonToken.NAME) {
                    reader.nextName();
                } else {
                    reader.skipValue();
                }
            }
        } catch (MalformedJsonException e) {
            // Newer Gson versions enforce their own nesting limit while peeking.
            return e.getMessage() != null && e.getMessage().contains("Nesting limit");
        } catch (Exception | StackOverflowError ignored) {
            return false;
        }
    }

    /** A JSON primitive number's textual form, or {@code null} when it is not a number. */
    @Nullable
    private static String numberText(@Nullable JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        return element.getAsString();
    }

    /**
     * F-140-6 / F-140-13: parses a string array of preset IDs. Invalid or reserved IDs and
     * non-string elements are warned about and skipped; duplicates keep the first occurrence.
     */
    private static List<String> parseIdList(JsonObject root, String field, String sourceLabel, Problems problems) {
        if (!root.has(field)) {
            return List.of();
        }
        JsonElement element = root.get(field);
        if (!element.isJsonArray()) {
            problems.warn("Preset file {} has a non-array '{}'; ignoring it", sourceLabel, field);
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (JsonElement elem : element.getAsJsonArray()) {
            if (!elem.isJsonPrimitive() || !elem.getAsJsonPrimitive().isString()) {
                problems.warn("Skipping non-string '{}' entry in preset {}", field, sourceLabel);
                continue;
            }
            String id = elem.getAsString().trim();
            if (!isValidId(id)) {
                problems.warn("Skipping invalid '{}' ID '{}' in preset {}", field, id, sourceLabel);
                continue;
            }
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    /** F-140-9: parses {@code requiredMods}, warning about and skipping invalid elements. */
    private static List<String> parseRequiredMods(JsonObject root, String sourceLabel, Problems problems) {
        if (!root.has("requiredMods")) {
            return List.of();
        }
        JsonElement element = root.get("requiredMods");
        if (!element.isJsonArray()) {
            problems.warn("Preset file {} has a non-array 'requiredMods'; ignoring it", sourceLabel);
            return List.of();
        }
        Set<String> mods = new LinkedHashSet<>();
        for (JsonElement elem : element.getAsJsonArray()) {
            if (!elem.isJsonPrimitive() || !elem.getAsJsonPrimitive().isString()) {
                problems.warn("Skipping non-string 'requiredMods' entry in preset {}", sourceLabel);
                continue;
            }
            String modId = elem.getAsString().trim();
            if (!PresetMetadataLogic.isValidModId(modId)) {
                problems.warn("Skipping invalid required mod ID '{}' in preset {}", modId, sourceLabel);
                continue;
            }
            mods.add(modId);
        }
        return List.copyOf(mods);
    }

    /**
     * F-140-10: parses the optional {@code groups} array. Invalid elements are warned about and
     * skipped. Validation against the expanded items (unknown keys, cross-group duplicates,
     * duplicate group IDs and emptied groups) happens later in {@link PresetGroupLogic}.
     */
    private static List<PresetGroup> parseGroups(JsonObject root, String sourceLabel, Problems problems) {
        if (!root.has("groups")) {
            return List.of();
        }
        JsonElement element = root.get("groups");
        if (!element.isJsonArray()) {
            problems.warn("Preset file {} has a non-array 'groups'; ignoring it", sourceLabel);
            return List.of();
        }
        List<PresetGroup> groups = new ArrayList<>();
        for (JsonElement elem : element.getAsJsonArray()) {
            if (!elem.isJsonObject()) {
                problems.warn("Skipping invalid group entry in preset {}", sourceLabel);
                continue;
            }
            JsonObject group = elem.getAsJsonObject();
            if (!group.has("id") || !group.get("id").isJsonPrimitive()
                    || !group.get("id").getAsJsonPrimitive().isString()) {
                problems.warn("Skipping group without a string 'id' in preset {}", sourceLabel);
                continue;
            }
            String groupId = group.get("id").getAsString().trim();
            if (!isValidId(groupId)) {
                problems.warn("Skipping group with invalid id '{}' in preset {}", groupId, sourceLabel);
                continue;
            }
            if (!group.has("items") || !group.get("items").isJsonArray()) {
                problems.warn("Skipping group '{}' in preset {} (missing 'items' array)", groupId, sourceLabel);
                continue;
            }
            PresetName groupName = extractName(group, "name", sourceLabel + " group '" + groupId + "'", true, problems);
            List<TargetKey> groupItems = parseTargetItems(
                    group.getAsJsonArray("items"), sourceLabel + " group '" + groupId + "'", problems);
            groups.add(new PresetGroup(groupId, groupName, groupItems));
        }
        return List.copyOf(groups);
    }

    /** F-140-5: the new fields of one preset JSON document, parsed element-by-element. */
    private static ExtraFields parseExtraFields(JsonObject root, String sourceLabel, Problems problems) {
        List<String> includes = parseIdList(root, "includes", sourceLabel, problems);
        PresetName description = extractName(root, "description", sourceLabel, true, problems);

        String category = null;
        if (root.has("category")) {
            JsonElement element = root.get("category");
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                category = PresetMetadataLogic.normalizeCategory(element.getAsString());
            } else {
                problems.warn("Preset file {} has a non-string 'category'; ignoring it", sourceLabel);
            }
        }

        int order = 0;
        if (root.has("order")) {
            String rawOrder = numberText(root.get("order"));
            Integer parsed = rawOrder != null ? PresetMetadataLogic.parseOrder(rawOrder) : null;
            if (parsed != null) {
                order = parsed;
            } else {
                problems.warn("Preset file {} has an invalid 'order' (must be an integer); using 0", sourceLabel);
            }
        }

        Integer version = null;
        if (root.has("version")) {
            String rawVersion = numberText(root.get("version"));
            version = rawVersion != null ? PresetMetadataLogic.parseVersion(rawVersion) : null;
            if (version == null) {
                problems.warn(
                        "Preset file {} has an invalid 'version' (must be an integer >= 1); treating it as unversioned",
                        sourceLabel);
            }
        }

        List<String> requiredMods = parseRequiredMods(root, sourceLabel, problems);
        List<PresetGroup> groups = parseGroups(root, sourceLabel, problems);
        List<String> nextPresets = parseIdList(root, "nextPresets", sourceLabel, problems);

        Double unlockAfterHours = null;
        if (root.has("unlockAfterHours")) {
            String rawHours = numberText(root.get("unlockAfterHours"));
            unlockAfterHours = rawHours != null ? PresetMetadataLogic.parseUnlockAfterHours(rawHours) : null;
            if (unlockAfterHours == null) {
                problems.warn(
                        "Preset file {} has an invalid 'unlockAfterHours' (must be a number >= 0); ignoring it",
                        sourceLabel);
            }
        }

        return new ExtraFields(includes, description, category, order, version, requiredMods, groups,
                nextPresets, unlockAfterHours);
    }

    /**
     * G2/F54 + F-140-11: reads an existing file's carried-over fields. Only the fields are
     * parsed (the items are replaced by the GUI save), and a corrupt-but-readable file yields
     * defaults exactly like the historical name-only carry-over.
     *
     * <p>SF-4: Gson's recursive parser can throw {@link StackOverflowError} on deeply nested
     * input; that is rejected with a warning like any other invalid document. SF-13: an I/O
     * failure (or a nesting overflow) is propagated so a GUI save aborts instead of silently
     * overwriting metadata that could not be read.
     */
    private static ExistingFields readExistingFields(Path file) throws IOException {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (isTooDeeplyNested(content)) {
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Preset file {} is too deeply nested to read; refusing to overwrite it",
                        file.getFileName());
                throw new IOException("Preset file is too deeply nested to read");
            }
            JsonElement el = JsonParser.parseReader(new StringReader(content));
            if (el.isJsonObject()) {
                JsonObject root = el.getAsJsonObject();
                return new ExistingFields(extractName(root), parseExtraFields(root, file.getFileName().toString(), new Problems()));
            }
        } catch (StackOverflowError e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Preset file {} is too deeply nested to read; refusing to overwrite it",
                    file.getFileName());
            throw new IOException("Preset file is too deeply nested to read", e);
        } catch (IOException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Could not read existing preset file {}; refusing to overwrite it: {}",
                    file.getFileName(), e.getMessage());
            throw e;
        } catch (Exception e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Could not read metadata from preset file {}; using defaults: {}",
                    file.getFileName(), e.getMessage());
        }
        return new ExistingFields(PresetName.absent(), ExtraFields.EMPTY);
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
     * (F21), so both sources accept the same format: optional {@code name}, {@code items}
     * (strings or {@code {"item": ..., "count": ...}} objects), and the G-B optional fields.
     *
     * <p>F-140-6: {@code items} may be absent or empty when {@code includes} is present; the
     * expansion then supplies the items. The historical rejections (non-object root, invalid
     * JSON, missing/non-array {@code items} without includes, zero own+included items) remain.
     */
    static PresetDefinition parsePresetContent(String content, String sourceLabel, String id, PresetKind kind) {
        return parsePresetContent(content, sourceLabel, id, kind, new Problems());
    }

    /** F-140-20: the shared parse path, collecting every element-level problem for the caller. */
    private static PresetDefinition parsePresetContent(
            String content, String sourceLabel, String id, PresetKind kind, Problems problems) {
        JsonObject root;
        try {
            if (isTooDeeplyNested(content)) {
                ItemMilestoneLog.LOGGER.error("[ItemMilestoneLog] Preset file {} is too deeply nested; rejecting it", sourceLabel);
                return null;
            }
            JsonElement el = JsonParser.parseString(content);
            if (!el.isJsonObject()) {
                ItemMilestoneLog.LOGGER.error("[ItemMilestoneLog] Preset file {} must contain a JSON object", sourceLabel);
                return null;
            }
            root = el.getAsJsonObject();
        } catch (Exception | StackOverflowError e) {
            // SF-4: JSON is parsed recursively; a deeply nested document can exhaust the stack.
            ItemMilestoneLog.LOGGER.error("[ItemMilestoneLog] Failed to parse preset file {}: {}", sourceLabel, e.getMessage());
            return null;
        }

        // F-140-21: "$schema" is a permitted editor-only key. Unknown fields are ignored
        // silently by this parser, so the schema reference never produces a validation problem.
        ExtraFields extra = parseExtraFields(root, sourceLabel, problems);
        PresetName name = extractName(root);

        List<TargetKey> ownItems;
        if (root.has("items")) {
            if (!root.get("items").isJsonArray()) {
                ItemMilestoneLog.LOGGER.error("[ItemMilestoneLog] Preset file {} has a non-array 'items'", sourceLabel);
                return null;
            }
            ownItems = parseTargetItems(root.getAsJsonArray("items"), sourceLabel, problems);
        } else if (!extra.includes().isEmpty()) {
            ownItems = List.of();
        } else {
            ItemMilestoneLog.LOGGER.error("[ItemMilestoneLog] Preset file {} is missing required 'items' array", sourceLabel);
            return null;
        }

        if (ownItems.isEmpty() && extra.includes().isEmpty()) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Skipping preset file {}: 0 valid item IDs", sourceLabel);
            return null;
        }

        return new PresetDefinition(id, name, ownItems, kind, extra.includes(), extra.description(),
                extra.category(), extra.order(), extra.version(), extra.requiredMods(), extra.groups(),
                extra.nextPresets(), extra.unlockAfterHours(), problems.list());
    }

    /** F-140-5: parses the standard item element format (shared by {@code items} and group items). */
    private static List<TargetKey> parseTargetItems(JsonArray arr, String sourceLabel, Problems problems) {
        Set<TargetKey> deduped = new LinkedHashSet<>();
        for (JsonElement elem : arr) {
            if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isString()) {
                String rawItem = elem.getAsString().trim();
                if (rawItem.isEmpty()) {
                    problems.warn("Skipping blank item in preset {}", sourceLabel);
                    continue;
                }
                TargetKey normalized = TargetKey.tryParseNormalized(rawItem, false, true);
                if (normalized != null) {
                    deduped.add(normalized);
                } else {
                    problems.warn("Skipping invalid item '{}' in preset {}", rawItem, sourceLabel);
                }
            } else if (elem.isJsonObject()) {
                JsonObject obj = elem.getAsJsonObject();
                if (!obj.has("item") || !obj.get("item").isJsonPrimitive() || !obj.get("item").getAsJsonPrimitive().isString()) {
                    problems.warn("Skipping invalid item object in preset {}", sourceLabel);
                    continue;
                }
                String rawItem = obj.get("item").getAsString().trim();
                String normalizedId = TargetKey.normalizeItemId(rawItem, false);
                if (normalizedId == null || "minecraft:air".equals(normalizedId)) {
                    problems.warn("Skipping invalid item '{}' in preset {}", rawItem, sourceLabel);
                    continue;
                }
                int count = TargetKey.MIN_COUNT;
                if (obj.has("count")) {
                    count = parseStrictJsonCount(obj.get("count"));
                    if (count < TargetKey.MIN_COUNT) {
                        problems.warn("Skipping item '{}' with invalid count in preset {}", rawItem, sourceLabel);
                        continue;
                    }
                }
                TargetKey normalized = TargetKey.normalize(normalizedId, count, false, true);
                if (normalized != null) {
                    deduped.add(normalized);
                }
            } else {
                problems.warn("Skipping invalid item entry in preset {}", sourceLabel);
            }
        }
        return List.copyOf(deduped);
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
    public static boolean saveCustomTo(Path dir, String id, List<TargetKey> items) throws IOException {
        return saveCustomTo(dir, id, items, Config.strictPresetValidation());
    }

    /**
     * SF-14: save path with an explicit validation mode (the production wrapper reads the
     * config; unit tests use this seam). A strict-mode rejection happens before any write or
     * in-memory update, so nothing is rolled back because nothing was changed.
     */
    static synchronized boolean saveCustomTo(Path dir, String id, List<TargetKey> items, boolean strict) throws IOException {
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
        // G2/F54 + F-140-11: carry over the existing file's name and all valid new fields.
        // A new preset or an unreadable/corrupt existing file gets the documented defaults.
        ExistingFields existing = existed
                ? readExistingFields(target)
                : new ExistingFields(PresetName.absent(), ExtraFields.EMPTY);
        PresetName name = existing.name();
        if (!name.present()) {
            name = PresetName.ofPlain(id);
        }
        ExtraFields extra = existing.extra();

        List<TargetKey> deduped = normalizeItems(items);
        if (deduped.isEmpty()) {
            throw new IllegalArgumentException("No valid items to save");
        }

        // F-140-11: groups are re-validated against the (includes-expanded) items that will
        // result from this save, so the written JSON matches the next reload exactly.
        SaveExpansion expansion = expandItemsForSave(id, deduped, extra.includes());
        List<TargetKey> finalItems = expansion.items();
        PresetGroupLogic.Result groupResult = PresetGroupLogic.validate(extra.groups(), finalItems);
        for (String warning : groupResult.warnings()) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Preset '{}': {}", id, warning);
        }
        List<PresetGroup> groups = groupResult.groups();
        List<String> problems = new ArrayList<>(expansion.problems());
        problems.addAll(groupResult.warnings());
        rejectStrictSave(id, strict, problems);

        writePresetFile(dir, id, name, extra, groups, deduped);

        Map<String, PresetDefinition> next = new TreeMap<>(filePresets);
        // Store the raw (not yet expanded) definition; rebuildLoadedPresets resolves includes
        // and groups again for the in-memory table.
        next.put(id, new PresetDefinition(id, name, deduped, PresetKind.FILE,
                extra.includes(), extra.description(), extra.category(), extra.order(), extra.version(),
                extra.requiredMods(), groups, extra.nextPresets(), extra.unlockAfterHours()));
        filePresets = Collections.unmodifiableMap(new LinkedHashMap<>(next));
        rebuildLoadedPresets();
        return existed;
    }

    /**
     * F-140-18: saves an imported share code's already-parsed definition as a new config-folder
     * preset. The write location, JSON format and in-memory table update are the same as
     * {@link #saveCustomTo(Path, String, List)}, but an existing file is never overwritten (the
     * command rejects ID collisions before calling this, and this check closes the race).
     *
     * @return always {@code false}: imports never overwrite an existing preset
     */
    public static boolean saveImportedTo(Path dir, PresetDefinition definition) throws IOException {
        return saveImportedTo(dir, definition, Config.strictPresetValidation());
    }

    /** SF-14: import counterpart of {@link #saveCustomTo(Path, String, List, boolean)}. */
    static synchronized boolean saveImportedTo(Path dir, PresetDefinition definition, boolean strict) throws IOException {
        if (definition == null) {
            throw new IllegalArgumentException("Missing preset definition");
        }
        String id = definition.id();
        if (!isValidId(id)) {
            throw new IllegalArgumentException("Invalid preset ID: " + id);
        }
        if (isBuiltin(id)) {
            throw new IllegalArgumentException("Cannot overwrite built-in preset: " + id);
        }
        if (isDatapack(id)) {
            throw new IllegalArgumentException("Cannot overwrite datapack preset: " + id);
        }
        if (Files.exists(dir.resolve(id + ".json"))) {
            throw new IllegalArgumentException("Preset already exists: " + id);
        }

        List<TargetKey> deduped = normalizeItems(definition.items());
        if (deduped.isEmpty()) {
            throw new IllegalArgumentException("No valid items to save");
        }
        ExtraFields extra = new ExtraFields(definition.includes(), definition.description(),
                definition.category(), definition.order(), definition.version(), definition.requiredMods(),
                definition.groups(), definition.nextPresets(), definition.unlockAfterHours());
        // Same re-validation as saveCustomTo so the written file matches the next reload.
        SaveExpansion expansion = expandItemsForSave(id, deduped, extra.includes());
        List<TargetKey> finalItems = expansion.items();
        PresetGroupLogic.Result groupResult = PresetGroupLogic.validate(extra.groups(), finalItems);
        for (String warning : groupResult.warnings()) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Preset '{}': {}", id, warning);
        }
        List<PresetGroup> groups = groupResult.groups();
        List<String> problems = new ArrayList<>(definition.problems());
        problems.addAll(expansion.problems());
        problems.addAll(groupResult.warnings());
        rejectStrictSave(id, strict, problems);

        writePresetFile(dir, id, definition.name(), extra, groups, deduped);

        Map<String, PresetDefinition> next = new TreeMap<>(filePresets);
        next.put(id, new PresetDefinition(id, definition.name(), deduped, PresetKind.FILE,
                extra.includes(), extra.description(), extra.category(), extra.order(), extra.version(),
                extra.requiredMods(), groups, extra.nextPresets(), extra.unlockAfterHours()));
        filePresets = Collections.unmodifiableMap(new LinkedHashMap<>(next));
        rebuildLoadedPresets();
        return false;
    }

    /** Normalizes item keys and removes exact duplicates, dropping invalid/air entries. */
    private static List<TargetKey> normalizeItems(Collection<TargetKey> items) {
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
        return List.copyOf(deduped);
    }

    /**
     * Writes one preset JSON document atomically in the normal preset-file format (the file name
     * carries the ID, so no {@code id} field is written). Shared by {@link #saveCustomTo} and
     * {@link #saveImportedTo}.
     */
    private static void writePresetFile(Path dir, String id, PresetName name, ExtraFields extra,
                                        List<PresetGroup> groups, List<TargetKey> items) throws IOException {
        Files.createDirectories(dir);
        Path target = dir.resolve(id + ".json");
        JsonObject json = new JsonObject();
        writePresetName(json, "name", name);
        writeExtraFields(json, extra, groups);
        json.add("items", targetItemsJson(items));

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
    }

    /**
     * F-140-11: expands the includes of the preset being saved against the current raw sources,
     * with the GUI's new items substituted for that preset. Used only to re-validate groups (the
     * in-memory table is rebuilt by {@link #rebuildLoadedPresets()}).
     */
    /** SF-14: expanded items together with the problems that strict mode would reject. */
    private record SaveExpansion(List<TargetKey> items, List<String> problems) {
    }

    private static SaveExpansion expandItemsForSave(String id, List<TargetKey> ownItems, List<String> includes) {
        Map<String, PresetDefinition> raw = rawPresets(false);
        Map<String, List<TargetKey>> itemsById = new LinkedHashMap<>();
        Map<String, List<String>> includesById = new LinkedHashMap<>();
        for (PresetDefinition preset : raw.values()) {
            itemsById.put(preset.id(), preset.items());
            includesById.put(preset.id(), preset.includes());
        }
        itemsById.put(id, ownItems);
        includesById.put(id, includes);
        PresetIncludesLogic.Result result = PresetIncludesLogic.resolve(itemsById, includesById);
        for (String warning : result.warnings()) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Preset includes: {}", warning);
        }
        return new SaveExpansion(result.items(id), result.problems(id));
    }

    /**
     * SF-14: strict mode rejects a save whose collected problems would make
     * {@link #rebuildLoadedPresets(boolean)} drop the preset on the next reload. Called before
     * the file is written so the failure needs no rollback; the exception message carries the
     * problem text and the command handler turns it into a user-visible failure.
     */
    private static void rejectStrictSave(String id, boolean strict, List<String> problems) {
        if (!strict || problems.isEmpty()) {
            return;
        }
        String first = problems.get(0);
        ItemMilestoneLog.LOGGER.error(
                "[ItemMilestoneLog] Refusing to save preset '{}' because strictPresetValidation is enabled: {}", id, first);
        throw new IllegalArgumentException("Preset rejected by strict validation: " + first);
    }

    /** Writes a plain or per-language preset name field when a name is present. */
    private static void writePresetName(JsonObject json, String field, PresetName name) {
        if (name == null || !name.present()) {
            return;
        }
        if (name.localizedForm()) {
            JsonObject localizedNames = new JsonObject();
            for (Map.Entry<String, String> entry : name.localizedValues().entrySet()) {
                localizedNames.addProperty(entry.getKey(), entry.getValue());
            }
            json.add(field, localizedNames);
        } else {
            json.addProperty(field, name.plainValue());
        }
    }

    /** F-140-11: writes the carried-over G-B fields (non-default values only). */
    private static void writeExtraFields(JsonObject json, ExtraFields extra, List<PresetGroup> groups) {
        if (!extra.includes().isEmpty()) {
            json.add("includes", stringArray(extra.includes()));
        }
        writePresetName(json, "description", extra.description());
        if (extra.category() != null) {
            json.addProperty("category", extra.category());
        }
        if (extra.order() != 0) {
            json.addProperty("order", extra.order());
        }
        if (extra.version() != null) {
            json.addProperty("version", extra.version());
        }
        if (!extra.requiredMods().isEmpty()) {
            json.add("requiredMods", stringArray(extra.requiredMods()));
        }
        if (!groups.isEmpty()) {
            JsonArray groupArray = new JsonArray();
            for (PresetGroup group : groups) {
                JsonObject groupJson = new JsonObject();
                groupJson.addProperty("id", group.id());
                writePresetName(groupJson, "name", group.name());
                groupJson.add("items", targetItemsJson(group.items()));
                groupArray.add(groupJson);
            }
            json.add("groups", groupArray);
        }
        if (!extra.nextPresets().isEmpty()) {
            json.add("nextPresets", stringArray(extra.nextPresets()));
        }
        if (extra.unlockAfterHours() != null) {
            json.addProperty("unlockAfterHours", extra.unlockAfterHours());
        }
    }

    private static JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static JsonArray targetItemsJson(Collection<TargetKey> keys) {
        JsonArray arr = new JsonArray();
        for (TargetKey key : keys) {
            if (key.count() > TargetKey.MIN_COUNT) {
                JsonObject obj = new JsonObject();
                obj.addProperty("item", key.itemId());
                obj.addProperty("count", key.count());
                arr.add(obj);
            } else {
                arr.add(key.itemId());
            }
        }
        return arr;
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

    /** Presets with a non-null {@code unlockAfterHours}, cached at rebuild for the per-scan check. */
    public static List<PresetDefinition> allWithUnlockAfterHours() {
        return unlockAfterHoursPresets;
    }

    /** Preset IDs in the F-140-7 list order ({@code order} ascending, then ID). */
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
            // The recorded key must be in the preset's own item list; skip the registry
            // filtering for every unrelated preset (the scan tick path is hot).
            if (preset.items() == null || !preset.items().contains(justRecordedKey)) {
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
