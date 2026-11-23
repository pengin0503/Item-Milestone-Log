package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Pure-Java unit tests for the F12 {@link ChatItemHover} hover item decision. */
class ChatItemHoverTest {
    private static final Set<String> REGISTRY = Set.of(
            "minecraft:diamond", "minecraft:iron_ingot", "minecraft:gold_ingot",
            "othermod:steel_ingot", "aaa:copper_ingot");
    private static final Predicate<String> REGISTERED = REGISTRY::contains;

    private static TagIndex tags() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        // Registry iteration order puts minecraft:* first; lexicographic order must win.
        map.put("#c:ingots", List.of("minecraft:iron_ingot", "minecraft:gold_ingot", "othermod:steel_ingot",
                "aaa:copper_ingot"));
        map.put("#c:gems", List.of("minecraft:diamond"));
        map.put("#c:empty", List.of());
        return TagIndex.of(map);
    }

    @Test
    void itemTargetHoversItselfWhenEnabled() {
        assertEquals("minecraft:diamond", ChatItemHover.hoverItemId(true, "minecraft:diamond", tags(), REGISTERED));
    }

    @Test
    void disabledConfigMeansNoHover() {
        assertNull(ChatItemHover.hoverItemId(false, "minecraft:diamond", tags(), REGISTERED));
        assertNull(ChatItemHover.hoverItemId(false, "#c:ingots", tags(), REGISTERED));
    }

    @Test
    void unknownItemMeansNoHover() {
        assertNull(ChatItemHover.hoverItemId(true, "removedmod:widget", tags(), REGISTERED));
    }

    @Test
    void tagTargetHoversLexicographicallyFirstMember() {
        assertEquals("aaa:copper_ingot", ChatItemHover.hoverItemId(true, "#c:ingots", tags(), REGISTERED));
        assertEquals("minecraft:diamond", ChatItemHover.hoverItemId(true, "#c:gems", tags(), REGISTERED));
    }

    @Test
    void emptyOrUnknownTagMeansNoHover() {
        assertNull(ChatItemHover.hoverItemId(true, "#c:empty", tags(), REGISTERED));
        assertNull(ChatItemHover.hoverItemId(true, "#c:missing", tags(), REGISTERED));
        assertNull(ChatItemHover.hoverItemId(true, "#c:ingots", TagIndex.EMPTY, REGISTERED));
        assertNull(ChatItemHover.hoverItemId(true, "#c:ingots", null, REGISTERED));
    }

    @Test
    void tagMemberMissingFromRegistryMeansNoHover() {
        assertNull(ChatItemHover.hoverItemId(true, "#c:ingots", tags(), id -> false));
    }

    @Test
    void nullOrEmptyTargetMeansNoHover() {
        assertNull(ChatItemHover.hoverItemId(true, null, tags(), REGISTERED));
        assertNull(ChatItemHover.hoverItemId(true, "", tags(), REGISTERED));
    }
}
