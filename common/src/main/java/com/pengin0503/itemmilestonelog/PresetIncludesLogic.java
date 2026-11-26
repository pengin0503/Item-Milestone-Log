package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.TargetKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure-Java resolution of preset {@code includes} (spec 1.4.0 F-140-6 / N17), shared across
 * all Minecraft versions via {@code common/src/main/java}.
 *
 * <p>The resolver receives the raw (not yet expanded) item lists and the {@code includes}
 * lists of every loaded preset, expands each preset depth-first in include order, appends the
 * preset's own items, and removes duplicates while keeping the first occurrence. A cyclic
 * include branch is cut with a warning (including a self-reference); a reference to an unknown
 * preset ID is skipped with a warning. The caller decides what to do with a preset whose
 * expansion ends up empty (the per-version {@code Presets} drops it, matching the historical
 * "0 valid items" rule).
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class PresetIncludesLogic {
    /**
     * Result of one includes resolution.
     *
     * @param expandedItems expanded items per preset ID, in the input ID order (may be empty)
     * @param warnings      human-readable warnings for cycles and unknown IDs, in discovery order
     */
    public record Result(Map<String, List<TargetKey>> expandedItems, List<String> warnings) {
        public Result {
            Map<String, List<TargetKey>> copy = new LinkedHashMap<>();
            if (expandedItems != null) {
                for (Map.Entry<String, List<TargetKey>> entry : expandedItems.entrySet()) {
                    if (entry.getKey() != null) {
                        copy.put(entry.getKey(), entry.getValue() == null ? List.of() : List.copyOf(entry.getValue()));
                    }
                }
            }
            expandedItems = Collections.unmodifiableMap(copy);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        /** Expanded items for {@code id}, or an empty list when {@code id} was not an input. */
        public List<TargetKey> items(String id) {
            List<TargetKey> items = expandedItems.get(id);
            return items != null ? items : List.of();
        }
    }

    private PresetIncludesLogic() {
    }

    /**
     * Expands every preset in {@code rawItemsById}.
     *
     * @param rawItemsById  preset ID to the preset's own (not yet expanded) items; iteration order
     *                      determines the result order
     * @param includesById  preset ID to its include IDs; missing entries mean "no includes"
     */
    public static Result resolve(
            Map<String, ? extends List<TargetKey>> rawItemsById,
            Map<String, ? extends List<String>> includesById) {
        Map<String, List<TargetKey>> rawItems = new LinkedHashMap<>();
        if (rawItemsById != null) {
            for (Map.Entry<String, ? extends List<TargetKey>> entry : rawItemsById.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                rawItems.put(entry.getKey(), entry.getValue() == null ? List.of() : entry.getValue());
            }
        }
        Map<String, List<String>> includes = new LinkedHashMap<>();
        if (includesById != null) {
            for (Map.Entry<String, ? extends List<String>> entry : includesById.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    includes.put(entry.getKey(), entry.getValue());
                }
            }
        }

        List<String> warnings = new ArrayList<>();
        Map<String, List<TargetKey>> expanded = new LinkedHashMap<>();
        for (String id : rawItems.keySet()) {
            // Each preset is expanded independently: a cycle branches differently depending
            // on which preset is the root, so results must not be memoized across roots.
            expanded.put(id, expand(id, rawItems, includes, new LinkedHashSet<>(), warnings));
        }
        return new Result(expanded, warnings);
    }

    /**
     * Depth-first expansion of one preset. {@code stack} holds the currently expanding IDs so a
     * repeated ID is a cycle and its branch is cut.
     */
    private static List<TargetKey> expand(
            String id,
            Map<String, List<TargetKey>> rawItems,
            Map<String, List<String>> includes,
            Set<String> stack,
            List<String> warnings) {
        if (!rawItems.containsKey(id)) {
            warnings.add("unknown preset include '" + id + "' (skipped)");
            return List.of();
        }
        if (stack.contains(id)) {
            List<String> path = new ArrayList<>(stack);
            path.add(id);
            warnings.add("preset include cycle detected: " + String.join(" -> ", path) + " (branch cut)");
            return List.of();
        }

        stack.add(id);
        Set<TargetKey> resolved = new LinkedHashSet<>();
        for (String includeId : includes.getOrDefault(id, List.of())) {
            if (includeId == null || includeId.isBlank()) {
                warnings.add("blank preset include id in '" + id + "' (skipped)");
                continue;
            }
            resolved.addAll(expand(includeId.trim(), rawItems, includes, stack, warnings));
        }
        for (TargetKey key : rawItems.get(id)) {
            if (key != null) {
                resolved.add(key);
            }
        }
        stack.remove(id);

        return List.copyOf(resolved);
    }
}
