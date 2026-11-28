package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure F-150-75 tests for the existing EditBox's prefix completion (FL-5). */
class ItemNameCompletionTest {
    private static final List<ItemNameCompletion.Candidate> CATALOG = List.of(
            ItemNameCompletion.Candidate.of("minecraft:oak_log", "Oak Log"),
            ItemNameCompletion.Candidate.of("minecraft:oak_planks", "Oak Planks"),
            ItemNameCompletion.Candidate.of("minecraft:obsidian", "Obsidian"),
            ItemNameCompletion.Candidate.of("minecraft:stone", "Stone"),
            ItemNameCompletion.Candidate.of("minecraft:stone_bricks", "Stone Bricks"),
            ItemNameCompletion.Candidate.of("mod:iron_ingot", "Iron Ingot"),
            ItemNameCompletion.Candidate.of("mod:iron_block", "Iron Block"),
            ItemNameCompletion.Candidate.of("mod:gold_ingot", "\u697c\u724c\u306e\u5730\u91d1"),
            ItemNameCompletion.Candidate.of("mod:gold_block", "\u697c\u724c\u306e\u30d6\u30ed\u30c3\u30af")
    );

    @Test
    void prefixMatchesKeepCatalogOrderAndAreCappedAtFive() {
        List<ItemNameCompletion.Candidate> oak = ItemNameCompletion.complete(CATALOG, "oak", 5);
        assertEquals(List.of("minecraft:oak_log", "minecraft:oak_planks"),
                oak.stream().map(ItemNameCompletion.Candidate::itemId).toList());

        List<ItemNameCompletion.Candidate> stones = ItemNameCompletion.complete(CATALOG, "stone", 99);
        assertEquals(2, stones.size());
        assertEquals("minecraft:stone", stones.get(0).itemId());

        List<ItemNameCompletion.Candidate> six = ItemNameCompletion.complete(CATALOG, "o", 99);
        assertTrue(six.size() <= ItemNameCompletion.MAX_SUGGESTIONS);
        assertEquals(5, ItemNameCompletion.MAX_SUGGESTIONS);
    }

    @Test
    void shortQueriesAndModFiltersDoNotComplete() {
        assertTrue(ItemNameCompletion.complete(CATALOG, "o", 5).isEmpty());
        assertTrue(ItemNameCompletion.complete(CATALOG, "@mod", 5).isEmpty());
        assertTrue(ItemNameCompletion.complete(CATALOG, "oak @mod", 5).isEmpty());
        assertTrue(ItemNameCompletion.complete(CATALOG, null, 5).isEmpty());
        assertTrue(ItemNameCompletion.complete(null, "oak", 5).isEmpty());
    }

    @Test
    void comparisonUsesRootLocaleNotTheDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            List<ItemNameCompletion.Candidate> upper = ItemNameCompletion.complete(CATALOG, "IRON", 5);
            assertEquals(List.of("mod:iron_ingot", "mod:iron_block"),
                    upper.stream().map(ItemNameCompletion.Candidate::itemId).toList(),
                    "Locale.ROOT must fold I to i even under tr_TR");
            assertEquals("iron ingot", "IRON INGOT".toLowerCase(Locale.ROOT));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void japaneseNamesCompleteAndFormatIntoTheHint() {
        List<ItemNameCompletion.Candidate> japanese =
                ItemNameCompletion.complete(CATALOG, "\u697c\u724c", 5);
        assertEquals(List.of("mod:gold_ingot", "mod:gold_block"),
                japanese.stream().map(ItemNameCompletion.Candidate::itemId).toList());
        assertEquals("\u697c\u724c\u306e\u5730\u91d1 / \u697c\u724c\u306e\u30d6\u30ed\u30c3\u30af",
                ItemNameCompletion.formatHint(japanese));
        assertEquals("", ItemNameCompletion.formatHint(List.of()));
    }
}
