package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.TargetKey;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresetIncludesLogicTest {
    private static TargetKey key(String itemId) {
        return TargetKey.of(itemId);
    }

    private static Map<String, List<TargetKey>> items(Object... idAndKeys) {
        Map<String, List<TargetKey>> map = new LinkedHashMap<>();
        for (int i = 0; i < idAndKeys.length; i += 2) {
            map.put((String) idAndKeys[i], (List<TargetKey>) idAndKeys[i + 1]);
        }
        return map;
    }

    private static Map<String, List<String>> includes(Object... idAndIncludes) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (int i = 0; i < idAndIncludes.length; i += 2) {
            map.put((String) idAndIncludes[i], (List<String>) idAndIncludes[i + 1]);
        }
        return map;
    }

    @Test
    void linearChainExpandsIncludesBeforeThePresetsOwnItems() {
        PresetIncludesLogic.Result result = PresetIncludesLogic.resolve(
                items("a", List.of(key("minecraft:a")),
                        "b", List.of(key("minecraft:b")),
                        "c", List.of(key("minecraft:c"))),
                includes("a", List.of("b"), "b", List.of("c")));

        assertEquals(List.of(key("minecraft:c"), key("minecraft:b"), key("minecraft:a")),
                result.items("a"));
        assertEquals(List.of(key("minecraft:c"), key("minecraft:b")), result.items("b"));
        assertEquals(List.of(key("minecraft:c")), result.items("c"));
        assertTrue(result.warnings().isEmpty(), () -> "unexpected warnings: " + result.warnings());
    }

    @Test
    void diamondExpandsEachBranchOnceAndKeepsFirstOccurrenceOrder() {
        PresetIncludesLogic.Result result = PresetIncludesLogic.resolve(
                items("a", List.of(key("minecraft:a")),
                        "b", List.of(key("minecraft:b")),
                        "c", List.of(key("minecraft:c")),
                        "d", List.of(key("minecraft:d"))),
                includes("a", List.of("b", "c"), "b", List.of("d"), "c", List.of("d")));

        // b = d,b; c = d,c -> a = d,b + d,c + a (second d deduped)
        assertEquals(List.of(key("minecraft:d"), key("minecraft:b"), key("minecraft:c"), key("minecraft:a")),
                result.items("a"));
        assertEquals(List.of(key("minecraft:d"), key("minecraft:b")), result.items("b"));
        assertEquals(List.of(key("minecraft:d"), key("minecraft:c")), result.items("c"));
        assertTrue(result.warnings().isEmpty(), () -> "unexpected warnings: " + result.warnings());
    }

    @Test
    void cycleCutsTheBranchWarnsAndTerminates() {
        PresetIncludesLogic.Result result = PresetIncludesLogic.resolve(
                items("a", List.of(key("minecraft:a")),
                        "b", List.of(key("minecraft:b"))),
                includes("a", List.of("b"), "b", List.of("a")));

        // Each root cuts the cycle at its own stack: a keeps b's own item, b keeps a's own item.
        assertEquals(List.of(key("minecraft:b"), key("minecraft:a")), result.items("a"));
        assertEquals(List.of(key("minecraft:a"), key("minecraft:b")), result.items("b"));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("cycle")),
                () -> "expected a cycle warning, got: " + result.warnings());
        assertTrue(result.problems("a").stream().anyMatch(w -> w.contains("cycle")),
                () -> "expected a per-preset cycle problem for a, got: " + result.problems("a"));
        assertTrue(result.problems("b").stream().anyMatch(w -> w.contains("cycle")),
                () -> "expected a per-preset cycle problem for b, got: " + result.problems("b"));
    }

    @Test
    void selfReferenceIsACycle() {
        PresetIncludesLogic.Result result = PresetIncludesLogic.resolve(
                items("self", List.of(key("minecraft:diamond"))),
                includes("self", List.of("self")));

        assertEquals(List.of(key("minecraft:diamond")), result.items("self"));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("cycle")),
                () -> "expected a cycle warning, got: " + result.warnings());
    }

    @Test
    void missingIncludeIdIsWarnedAndSkipped() {
        PresetIncludesLogic.Result result = PresetIncludesLogic.resolve(
                items("a", List.of(key("minecraft:a"))),
                includes("a", List.of("ghost")));

        assertEquals(List.of(key("minecraft:a")), result.items("a"));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("ghost")),
                () -> "expected an unknown-include warning, got: " + result.warnings());
        assertTrue(result.problems("a").stream().anyMatch(w -> w.contains("ghost")),
                () -> "expected a per-preset problem for a, got: " + result.problems("a"));
    }

    @Test
    void deepChainExpandsWithoutLosingOrderOrDuplicating() {
        int depth = 200;
        Map<String, List<TargetKey>> items = new LinkedHashMap<>();
        Map<String, List<String>> includes = new LinkedHashMap<>();
        for (int i = 0; i < depth; i++) {
            items.put("p" + i, List.of(key("minecraft:item" + i)));
            if (i + 1 < depth) {
                includes.put("p" + i, List.of("p" + (i + 1)));
            }
        }
        PresetIncludesLogic.Result result = PresetIncludesLogic.resolve(items, includes);
        List<TargetKey> expected = new java.util.ArrayList<>();
        for (int i = depth - 1; i >= 0; i--) {
            expected.add(key("minecraft:item" + i));
        }
        assertEquals(expected, result.items("p0"));
        assertEquals(depth, result.items("p0").size());
        assertTrue(result.warnings().isEmpty(), () -> "unexpected warnings: " + result.warnings());
    }

    @Test
    void duplicateItemsKeepTheFirstOccurrence() {
        PresetIncludesLogic.Result result = PresetIncludesLogic.resolve(
                items("a", List.of(key("minecraft:diamond"), key("minecraft:diamond"), key("minecraft:iron_ingot"))),
                includes("a", List.of()));
        assertEquals(List.of(key("minecraft:diamond"), key("minecraft:iron_ingot")), result.items("a"));
    }

    @Test
    void deepDiamondIncludesResolveFastWithTheExactOrderedItems() {
        int depth = 25; // 1 root + 2 presets per layer = 51 presets, the size called out in #102.
        Map<String, List<TargetKey>> items = new LinkedHashMap<>();
        Map<String, List<String>> includes = new LinkedHashMap<>();
        items.put("root", List.of(key("minecraft:root")));
        for (int i = 1; i <= depth; i++) {
            items.put("a" + i, List.of(key("minecraft:a" + i)));
            items.put("b" + i, List.of(key("minecraft:b" + i)));
            List<String> next = i < depth ? List.of("a" + (i + 1), "b" + (i + 1)) : List.of();
            includes.put("a" + i, next);
            includes.put("b" + i, next);
        }
        includes.put("root", List.of("a1", "b1"));

        PresetIncludesLogic.Result result = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> PresetIncludesLogic.resolve(items, includes));

        // Deepest layer first, then each shallower layer a/b pair, then the root's own item.
        List<TargetKey> expectedRoot = new ArrayList<>();
        for (int i = depth; i >= 1; i--) {
            expectedRoot.add(key("minecraft:a" + i));
            expectedRoot.add(key("minecraft:b" + i));
        }
        expectedRoot.add(key("minecraft:root"));
        assertEquals(expectedRoot, result.items("root"));
        assertEquals(2 * depth + 1, result.items("root").size());

        for (int i = 1; i <= depth; i++) {
            List<TargetKey> expectedA = new ArrayList<>();
            List<TargetKey> expectedB = new ArrayList<>();
            for (int j = depth; j > i; j--) {
                expectedA.add(key("minecraft:a" + j));
                expectedA.add(key("minecraft:b" + j));
                expectedB.add(key("minecraft:a" + j));
                expectedB.add(key("minecraft:b" + j));
            }
            expectedA.add(key("minecraft:a" + i));
            expectedB.add(key("minecraft:b" + i));
            assertEquals(expectedA, result.items("a" + i));
            assertEquals(expectedB, result.items("b" + i));
        }
        assertTrue(result.warnings().isEmpty(), () -> "unexpected warnings: " + result.warnings());
    }

    @Test
    void duplicatedIncludeListsResolveOncePerNodeWithoutChangingOrder() {
        int depth = 256;
        int duplicates = 128;
        Map<String, List<TargetKey>> items = new LinkedHashMap<>();
        Map<String, List<String>> includes = new LinkedHashMap<>();
        for (int i = 0; i < depth; i++) {
            items.put("p" + i, List.of(key("minecraft:item" + i)));
            if (i + 1 < depth) {
                List<String> repeated = new ArrayList<>(duplicates);
                for (int j = 0; j < duplicates; j++) {
                    repeated.add("p" + (i + 1));
                }
                includes.put("p" + i, repeated);
            }
        }

        PresetIncludesLogic.Result result = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> PresetIncludesLogic.resolve(items, includes));

        List<TargetKey> expected = new ArrayList<>();
        for (int i = depth - 1; i >= 0; i--) {
            expected.add(key("minecraft:item" + i));
        }
        assertEquals(expected, result.items("p0"));
        assertEquals(depth, result.items("p0").size());
        assertTrue(result.warnings().isEmpty(), () -> "unexpected warnings: " + result.warnings());
    }

    // ---- #149: memoization with a cycle elsewhere in the graph -------------------------

    /** Literal copy of the pre-#102 algorithm; used only as a differential oracle in tests. */
    private static List<TargetKey> oldExpand(String id, Map<String, List<TargetKey>> raw,
                                             Map<String, List<String>> inc, LinkedHashSet<String> stack,
                                             List<String> warnings) {
        if (!raw.containsKey(id)) {
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
        LinkedHashSet<TargetKey> resolved = new LinkedHashSet<>();
        for (String includeId : inc.getOrDefault(id, List.of())) {
            if (includeId == null || includeId.isBlank()) {
                warnings.add("blank preset include id in '" + id + "' (skipped)");
                continue;
            }
            resolved.addAll(oldExpand(includeId.trim(), raw, inc, stack, warnings));
        }
        for (TargetKey key : raw.get(id)) {
            if (key != null) {
                resolved.add(key);
            }
        }
        stack.remove(id);
        return List.copyOf(resolved);
    }

    private record OldResult(Map<String, List<TargetKey>> items, List<String> warnings,
                             Map<String, List<String>> problems) {
    }

    private static OldResult oldResolve(Map<String, List<TargetKey>> raw,
                                        Map<String, List<String>> inc) {
        Map<String, List<TargetKey>> items = new LinkedHashMap<>();
        Map<String, List<String>> problems = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        for (String id : raw.keySet()) {
            List<String> rootWarnings = new ArrayList<>();
            items.put(id, oldExpand(id, raw, inc, new LinkedHashSet<>(), rootWarnings));
            if (!rootWarnings.isEmpty()) {
                problems.put(id, new ArrayList<>(new LinkedHashSet<>(rootWarnings)));
            }
            warnings.addAll(rootWarnings);
        }
        return new OldResult(items, new ArrayList<>(new LinkedHashSet<>(warnings)), problems);
    }

    /** Depth-n diamond whose two branches meet in a self-looping node at the bottom. */
    private static Map<String, List<TargetKey>> cyclicDiamondItems(int depth,
                                                                   Map<String, List<String>> inc) {
        Map<String, List<TargetKey>> items = new LinkedHashMap<>();
        items.put("root", List.of(key("minecraft:root")));
        for (int i = 1; i <= depth; i++) {
            items.put("a" + i, List.of(key("minecraft:a" + i)));
            items.put("b" + i, List.of(key("minecraft:b" + i)));
            inc.put("a" + i, i < depth ? List.of("a" + (i + 1), "b" + (i + 1)) : List.of("loop"));
            inc.put("b" + i, i < depth ? List.of("a" + (i + 1), "b" + (i + 1)) : List.of("loop"));
        }
        items.put("loop", List.of(key("minecraft:loop")));
        inc.put("root", List.of("a1", "b1"));
        inc.put("loop", List.of("loop"));
        return items;
    }

    @Test
    void cyclicDiamondDiagnosticsStillMatchTheOldAlgorithmExactly() {
        Map<String, List<String>> inc = new LinkedHashMap<>();
        Map<String, List<TargetKey>> items = cyclicDiamondItems(5, inc);
        OldResult oldResult = oldResolve(items, inc);
        PresetIncludesLogic.Result result = PresetIncludesLogic.resolve(items, inc);

        assertEquals(oldResult.items(), result.expandedItems());
        assertEquals(oldResult.warnings(), new ArrayList<>(new LinkedHashSet<>(result.warnings())));
        assertEquals(oldResult.problems(), result.problemsByPreset());
    }

    @Test
    void cyclicDiamondDepth25ExpandsFastWithTheExactItemLists() {
        int depth = 25;
        Map<String, List<String>> inc = new LinkedHashMap<>();
        Map<String, List<TargetKey>> items = cyclicDiamondItems(depth, inc);

        long start = System.nanoTime();
        PresetIncludesLogic.Result result = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> PresetIncludesLogic.resolve(items, inc));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        // The old algorithm reaches the loop once per path; with working memoization the same
        // ordered, deduplicated item list is produced in linear instead of exponential work.
        List<TargetKey> expectedRoot = new ArrayList<>();
        expectedRoot.add(key("minecraft:loop"));
        for (int i = depth; i >= 1; i--) {
            expectedRoot.add(key("minecraft:a" + i));
            expectedRoot.add(key("minecraft:b" + i));
        }
        expectedRoot.add(key("minecraft:root"));
        assertEquals(expectedRoot, result.items("root"));
        assertEquals(2 * depth + 2, result.items("root").size());
        for (int i = 1; i <= depth; i++) {
            List<TargetKey> expectedA = new ArrayList<>();
            List<TargetKey> expectedB = new ArrayList<>();
            expectedA.add(key("minecraft:loop"));
            expectedB.add(key("minecraft:loop"));
            for (int j = depth; j > i; j--) {
                expectedA.add(key("minecraft:a" + j));
                expectedA.add(key("minecraft:b" + j));
                expectedB.add(key("minecraft:a" + j));
                expectedB.add(key("minecraft:b" + j));
            }
            expectedA.add(key("minecraft:a" + i));
            expectedB.add(key("minecraft:b" + i));
            assertEquals(expectedA, result.items("a" + i));
            assertEquals(expectedB, result.items("b" + i));
        }
        // Generous bound: the acyclic depth-25 benchmark was ~58 ms; a regression back to
        // exponential expansion cannot finish anywhere near 5 s.
        assertTrue(elapsedMillis < 5_000L,
                () -> "cyclic diamond resolve took " + elapsedMillis + " ms");
    }

    // ---- SF-5: dense strongly connected graphs ----------------------------------------

    @Test
    void denseFullyConnectedGraphExpandsInBoundedTimeWithTheExactOrderedItems() {
        int size = 12; // each simple DFS path is a distinct memo key with the old algorithm
        Map<String, List<TargetKey>> items = new LinkedHashMap<>();
        Map<String, List<String>> includes = new LinkedHashMap<>();
        List<String> allIds = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            allIds.add("n" + i);
        }
        for (int i = 0; i < size; i++) {
            // Every preset includes itself and every other preset.
            items.put("n" + i, List.of(key("minecraft:item" + i)));
            includes.put("n" + i, new ArrayList<>(allIds));
        }

        long start = System.nanoTime();
        PresetIncludesLogic.Result result = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> PresetIncludesLogic.resolve(items, includes));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        // Every include that is already being expanded is cut, so each root keeps its own item
        // once, after all the other items in the post-order of the first DFS that reaches them.
        for (int root = 0; root < size; root++) {
            List<TargetKey> expected = new ArrayList<>();
            for (int i = size - 1; i >= 0; i--) {
                if (i != root) {
                    expected.add(key("minecraft:item" + i));
                }
            }
            expected.add(key("minecraft:item" + root));
            assertEquals(expected, result.items("n" + root), "unexpected items for n" + root);
            assertEquals(size, result.items("n" + root).size());
        }
        assertTrue(elapsedMillis < 5_000L,
                () -> "dense fully connected resolve took " + elapsedMillis + " ms");
    }
}
