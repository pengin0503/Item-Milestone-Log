package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.TargetKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
     * @param expandedItems    expanded items per preset ID, in the input ID order (may be empty)
     * @param warnings         human-readable warnings for cycles and unknown IDs, in discovery order
     * @param problemsByPreset problems encountered while expanding each root preset (missing IDs,
     *                         cycles, blank IDs); F-140-20 uses this to reject the whole preset in
     *                         strict mode.
     */
    public record Result(Map<String, List<TargetKey>> expandedItems, List<String> warnings,
                         Map<String, List<String>> problemsByPreset) {
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
            Map<String, List<String>> problems = new LinkedHashMap<>();
            if (problemsByPreset != null) {
                for (Map.Entry<String, List<String>> entry : problemsByPreset.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                        problems.put(entry.getKey(), List.copyOf(entry.getValue()));
                    }
                }
            }
            problemsByPreset = Collections.unmodifiableMap(problems);
        }

        /** Expanded items for {@code id}, or an empty list when {@code id} was not an input. */
        public List<TargetKey> items(String id) {
            List<TargetKey> items = expandedItems.get(id);
            return items != null ? items : List.of();
        }

        /** Problems encountered while expanding {@code id} (empty when its expansion is clean). */
        public List<String> problems(String id) {
            List<String> problems = problemsByPreset.get(id);
            return problems != null ? problems : List.of();
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
        Map<String, List<String>> problemsByPreset = new LinkedHashMap<>();
        Map<String, List<TargetKey>> expanded = new LinkedHashMap<>();
        for (String id : rawItems.keySet()) {
            // #102: memoize clean expansions per root and skip duplicate include IDs. An
            // expansion that never hit a cycle cannot depend on the include stack, so reusing
            // it inside the same root is exact. Cyclic expansions stay uncached because their
            // cut point (and therefore their result) depends on which IDs are on the stack.
            Map<String, List<TargetKey>> cleanMemo = new HashMap<>();
            // Emit each distinct diagnostic once per root. Diamond graphs would otherwise
            // repeat the same warning once per path (exponentially many times) while strict
            // validation only needs to know that the problem exists.
            Set<String> emittedDiagnostics = new LinkedHashSet<>();
            List<String> rootProblems = new ArrayList<>();
            expanded.put(id, expand(id, rawItems, includes, new LinkedHashSet<>(),
                    rootProblems, emittedDiagnostics, cleanMemo));
            if (!rootProblems.isEmpty()) {
                problemsByPreset.put(id, rootProblems);
                warnings.addAll(rootProblems);
            }
        }
        return new Result(expanded, warnings, problemsByPreset);
    }

    /** Items of one node plus whether any cycle was cut anywhere in its subtree. */
    private record Expansion(List<TargetKey> items, boolean cyclic) {
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
            List<String> warnings,
            Set<String> emittedDiagnostics,
            Map<String, List<TargetKey>> cleanMemo) {
        return expandNode(id, rawItems, includes, stack, warnings, emittedDiagnostics, cleanMemo).items();
    }

    private static Expansion expandNode(
            String id,
            Map<String, List<TargetKey>> rawItems,
            Map<String, List<String>> includes,
            Set<String> stack,
            List<String> warnings,
            Set<String> emittedDiagnostics,
            Map<String, List<TargetKey>> cleanMemo) {
        List<TargetKey> cached = cleanMemo.get(id);
        if (cached != null) {
            return new Expansion(cached, false);
        }
        if (!rawItems.containsKey(id)) {
            report(warnings, emittedDiagnostics,
                    "unknown preset include '" + id + "' (skipped)");
            return new Expansion(List.of(), false);
        }
        if (stack.contains(id)) {
            List<String> path = new ArrayList<>(stack);
            path.add(id);
            report(warnings, emittedDiagnostics,
                    "preset include cycle detected: " + String.join(" -> ", path) + " (branch cut)");
            return new Expansion(List.of(), true);
        }
        stack.add(id);
        Set<TargetKey> resolved = new LinkedHashSet<>();
        boolean cyclic = false;
        // #102: a repeated include ID adds nothing to the ordered set; expanding it once keeps
        // item order/dedup identical while making duplicated include lists linear.
        Set<String> seenIncludes = new LinkedHashSet<>();
        for (String rawIncludeId : includes.getOrDefault(id, List.of())) {
            if (rawIncludeId == null || rawIncludeId.isBlank()) {
                report(warnings, emittedDiagnostics,
                        "blank preset include id in '" + id + "' (skipped)");
                continue;
            }
            String includeId = rawIncludeId.trim();
            if (!seenIncludes.add(includeId)) {
                continue;
            }
            Expansion child = expandNode(includeId, rawItems, includes, stack,
                    warnings, emittedDiagnostics, cleanMemo);
            cyclic |= child.cyclic();
            resolved.addAll(child.items());
        }
        for (TargetKey key : rawItems.get(id)) {
            if (key != null) {
                resolved.add(key);
            }
        }
        stack.remove(id);

        List<TargetKey> result = List.copyOf(resolved);
        if (!cyclic) {
            cleanMemo.put(id, result);
        }
        return new Expansion(result, cyclic);
    }

    private static void report(List<String> warnings, Set<String> emittedDiagnostics, String message) {
        if (emittedDiagnostics.add(message)) {
            warnings.add(message);
        }
    }
}
