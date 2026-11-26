package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.TargetKey;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
}
