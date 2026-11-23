package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-Java unit tests for the F8b {@link TagIndex} tag membership snapshot. */
class TagIndexTest {
    private static TagIndex sample() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("#c:ingots/iron", List.of("minecraft:iron_ingot", "othermod:steel_ingot"));
        map.put("c:ingots", List.of("minecraft:iron_ingot", "minecraft:gold_ingot"));
        map.put("#c:empty", List.of());
        return TagIndex.of(map);
    }

    @Test
    void ofNormalizesKeysDropsEmptyTagsAndKeepsMemberOrder() {
        TagIndex index = sample();
        assertTrue(index.isNonEmptyTag("#c:ingots/iron"));
        assertTrue(index.isNonEmptyTag("#c:ingots"));
        assertFalse(index.isNonEmptyTag("#c:empty"), "empty tags are treated as unknown");
        assertFalse(index.isNonEmptyTag("#c:missing"));
        assertFalse(index.isNonEmptyTag("c:ingots"), "lookups use the '#' form");
        assertFalse(index.isNonEmptyTag(null));
        assertEquals(2, index.size());
        assertEquals(Set.of("#c:ingots", "#c:ingots/iron"), index.tagKeys());
        assertEquals("minecraft:iron_ingot", index.firstMember("#c:ingots/iron"));
        assertNull(index.firstMember("#c:missing"));
        assertEquals(Set.of(), index.members("#c:missing"));
    }

    @Test
    void lexicographicFirstMemberIgnoresInsertionOrder() {
        TagIndex index = sample();
        // "#c:ingots" was inserted iron, gold: firstMember keeps insertion order, F12 uses ID order.
        assertEquals("minecraft:iron_ingot", index.firstMember("#c:ingots"));
        assertEquals("minecraft:gold_ingot", index.lexicographicFirstMember("#c:ingots"));
        assertEquals("minecraft:iron_ingot", index.lexicographicFirstMember("#c:ingots/iron"));
        assertNull(index.lexicographicFirstMember("#c:empty"));
        assertNull(index.lexicographicFirstMember("#c:missing"));
        assertNull(index.lexicographicFirstMember(null));
        assertNull(TagIndex.EMPTY.lexicographicFirstMember("#c:ingots"));
    }

    @Test
    void tagsContainingIsTheReverseIndex() {
        TagIndex index = sample();
        assertEquals(Set.of("#c:ingots", "#c:ingots/iron"), index.tagsContaining("minecraft:iron_ingot"));
        assertEquals(Set.of("#c:ingots/iron"), index.tagsContaining("othermod:steel_ingot"));
        assertEquals(Set.of(), index.tagsContaining("minecraft:diamond"));
        assertEquals(Set.of(), TagIndex.EMPTY.tagsContaining("minecraft:iron_ingot"));
    }

    @Test
    void expandForCountingReplacesTagsWithNonExcludedMembers() {
        TagIndex index = sample();
        Set<String> expanded = index.expandForCounting(
                List.of("minecraft:diamond", "#c:ingots/iron", "#c:missing"),
                Set.of("othermod:steel_ingot"));
        assertEquals(Set.of("minecraft:diamond", "minecraft:iron_ingot"), expanded);
    }

    @Test
    void withTagTotalsSumsMembersAndExcludesCreativeCarriedItems() {
        TagIndex index = sample();
        Map<String, Integer> itemCounts = Map.of(
                "minecraft:iron_ingot", 10,
                "othermod:steel_ingot", 5,
                "minecraft:gold_ingot", 3);

        Map<String, Integer> totals = index.withTagTotals(itemCounts,
                List.of("#c:ingots/iron", "#c:ingots", "#c:missing", "minecraft:iron_ingot"), Set.of());
        assertEquals(15, totals.get("#c:ingots/iron"));
        assertEquals(13, totals.get("#c:ingots"));
        assertFalse(totals.containsKey("#c:missing"));
        assertEquals(10, totals.get("minecraft:iron_ingot"), "item counts are kept");

        Map<String, Integer> excluded = index.withTagTotals(itemCounts,
                List.of("#c:ingots/iron"), Set.of("minecraft:iron_ingot"));
        assertEquals(5, excluded.get("#c:ingots/iron"));

        Map<String, Integer> none = index.withTagTotals(itemCounts,
                List.of("#c:ingots/iron"), Set.of("minecraft:iron_ingot", "othermod:steel_ingot"));
        assertFalse(none.containsKey("#c:ingots/iron"));
    }

    @Test
    void anyMemberInDetectsCarriedMembers() {
        TagIndex index = sample();
        assertTrue(index.anyMemberIn("#c:ingots/iron", Set.of("othermod:steel_ingot")));
        assertFalse(index.anyMemberIn("#c:ingots/iron", Set.of("minecraft:gold_ingot")));
        assertFalse(index.anyMemberIn("#c:ingots/iron", Set.of()));
        assertFalse(index.anyMemberIn("#c:missing", Set.of("minecraft:iron_ingot")));
    }
}
