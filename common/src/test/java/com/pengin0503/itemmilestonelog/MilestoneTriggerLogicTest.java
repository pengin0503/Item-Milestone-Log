package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MilestoneTriggerLogicTest {
    private static final UUID PLAYER_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAYER_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void emptyConditionsMatchAnyMilestone() {
        assertTrue(MilestoneTriggerLogic.matchesOptional(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                "minecraft:diamond", 1, false, true));
        assertTrue(MilestoneTriggerLogic.matchesOptional(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                "#c:ingots/iron", 100, true, true));
        assertTrue(MilestoneTriggerLogic.matchesOptional(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                "minecraft:diamond", 1, true, false));
    }

    @Test
    void itemConditionUsesExactStringMatchIncludingTagIdsWithoutTagMembershipMatching() {
        // Exact item ID match
        assertTrue(MilestoneTriggerLogic.matches(
                "minecraft:diamond", null, null, null,
                "minecraft:diamond", 1, false, true));
        assertFalse(MilestoneTriggerLogic.matches(
                "minecraft:diamond", null, null, null,
                "minecraft:emerald", 1, false, true));
        assertFalse(MilestoneTriggerLogic.matches(
                "minecraft:diamond", null, null, null,
                "diamond", 1, false, true));
        assertFalse(MilestoneTriggerLogic.matches(
                "minecraft:diamond", null, null, null,
                null, 1, false, true));

        // Tag target ID matches only the identical "#..." string, never member items or non-tag IDs
        assertTrue(MilestoneTriggerLogic.matches(
                "#c:ingots/iron", null, null, null,
                "#c:ingots/iron", 1, false, true));
        assertFalse(MilestoneTriggerLogic.matches(
                "#c:ingots/iron", null, null, null,
                "minecraft:iron_ingot", 1, false, true));
        assertFalse(MilestoneTriggerLogic.matches(
                "#c:ingots/iron", null, null, null,
                "c:ingots/iron", 1, false, true));
        assertFalse(MilestoneTriggerLogic.matches(
                "minecraft:iron_ingot", null, null, null,
                "#c:ingots/iron", 1, false, true));
    }

    @Test
    void countBoundsSupportExactMinMaxRangeAndAbsent() {
        // Absent (any)
        assertTrue(MilestoneTriggerLogic.matches(
                null, null, null, null, "minecraft:diamond", 1, false, true));
        assertTrue(MilestoneTriggerLogic.matches(
                null, null, null, null, "minecraft:diamond", 1_000_000, false, true));

        // Exact (bare number in JSON -> min == max)
        assertFalse(MilestoneTriggerLogic.matches(
                null, 100, 100, null, "minecraft:diamond", 99, false, true));
        assertTrue(MilestoneTriggerLogic.matches(
                null, 100, 100, null, "minecraft:diamond", 100, false, true));
        assertFalse(MilestoneTriggerLogic.matches(
                null, 100, 100, null, "minecraft:diamond", 101, false, true));

        // Min only ({"min": 100})
        assertFalse(MilestoneTriggerLogic.matches(
                null, 100, null, null, "minecraft:diamond", 99, false, true));
        assertTrue(MilestoneTriggerLogic.matches(
                null, 100, null, null, "minecraft:diamond", 100, false, true));
        assertTrue(MilestoneTriggerLogic.matches(
                null, 100, null, null, "minecraft:diamond", 500, false, true));

        // Max only ({"max": 10})
        assertTrue(MilestoneTriggerLogic.matches(
                null, null, 10, null, "minecraft:diamond", 1, false, true));
        assertTrue(MilestoneTriggerLogic.matches(
                null, null, 10, null, "minecraft:diamond", 10, false, true));
        assertFalse(MilestoneTriggerLogic.matches(
                null, null, 10, null, "minecraft:diamond", 11, false, true));

        // Range ({"min": 10, "max": 100})
        assertFalse(MilestoneTriggerLogic.matches(
                null, 10, 100, null, "minecraft:diamond", 9, false, true));
        assertTrue(MilestoneTriggerLogic.matches(
                null, 10, 100, null, "minecraft:diamond", 10, false, true));
        assertTrue(MilestoneTriggerLogic.matches(
                null, 10, 100, null, "minecraft:diamond", 64, false, true));
        assertTrue(MilestoneTriggerLogic.matches(
                null, 10, 100, null, "minecraft:diamond", 100, false, true));
        assertFalse(MilestoneTriggerLogic.matches(
                null, 10, 100, null, "minecraft:diamond", 101, false, true));
    }

    @Test
    void worldFirstConditionRespectsWorldFirstEnabledToggle() {
        // worldFirstEnabled = true
        assertTrue(MilestoneTriggerLogic.matches(
                null, null, null, true, "minecraft:diamond", 1, true, true));
        assertFalse(MilestoneTriggerLogic.matches(
                null, null, null, true, "minecraft:diamond", 1, false, true));
        assertTrue(MilestoneTriggerLogic.matches(
                null, null, null, false, "minecraft:diamond", 1, false, true));
        assertFalse(MilestoneTriggerLogic.matches(
                null, null, null, false, "minecraft:diamond", 1, true, true));
        assertTrue(MilestoneTriggerLogic.matches(
                null, null, null, null, "minecraft:diamond", 1, true, true));
        assertTrue(MilestoneTriggerLogic.matches(
                null, null, null, null, "minecraft:diamond", 1, false, true));

        // worldFirstEnabled = false: world_first: true NEVER matches (even if actualWorldFirst is true)
        assertFalse(MilestoneTriggerLogic.matches(
                null, null, null, true, "minecraft:diamond", 1, true, false));
        assertFalse(MilestoneTriggerLogic.matches(
                null, null, null, true, "minecraft:diamond", 1, false, false));
        // world_first: false matches when disabled, and absent still matches
        assertTrue(MilestoneTriggerLogic.matches(
                null, null, null, false, "minecraft:diamond", 1, false, false));
        assertTrue(MilestoneTriggerLogic.matches(
                null, null, null, false, "minecraft:diamond", 1, true, false));
        assertTrue(MilestoneTriggerLogic.matches(
                null, null, null, null, "minecraft:diamond", 1, true, false));
        assertTrue(MilestoneTriggerLogic.matches(
                null, null, null, null, "minecraft:diamond", 1, false, false));
    }

    @Test
    void combinedConditionsRequireAllSpecifiedCriteriaToMatch() {
        assertTrue(MilestoneTriggerLogic.matchesOptional(
                Optional.of("#c:ingots/iron"), Optional.of(10), Optional.of(100), Optional.of(true),
                "#c:ingots/iron", 64, true, true));
        // Wrong item
        assertFalse(MilestoneTriggerLogic.matchesOptional(
                Optional.of("#c:ingots/iron"), Optional.of(10), Optional.of(100), Optional.of(true),
                "minecraft:iron_ingot", 64, true, true));
        // Wrong count
        assertFalse(MilestoneTriggerLogic.matchesOptional(
                Optional.of("#c:ingots/iron"), Optional.of(10), Optional.of(100), Optional.of(true),
                "#c:ingots/iron", 9, true, true));
        // Wrong world_first
        assertFalse(MilestoneTriggerLogic.matchesOptional(
                Optional.of("#c:ingots/iron"), Optional.of(10), Optional.of(100), Optional.of(true),
                "#c:ingots/iron", 64, false, true));
        // worldFirstEnabled = false blocks world_first: true
        assertFalse(MilestoneTriggerLogic.matchesOptional(
                Optional.of("#c:ingots/iron"), Optional.of(10), Optional.of(100), Optional.of(true),
                "#c:ingots/iron", 64, true, false));
    }

    @Test
    void retroactiveWorldFirstRequiresMatchingHolderAndEnabledToggle() {
        assertTrue(MilestoneTriggerLogic.isRetroactiveWorldFirst(PLAYER_A, PLAYER_A, true));
        assertFalse(MilestoneTriggerLogic.isRetroactiveWorldFirst(PLAYER_A, PLAYER_A, false));
        assertFalse(MilestoneTriggerLogic.isRetroactiveWorldFirst(PLAYER_A, PLAYER_B, true));
        assertFalse(MilestoneTriggerLogic.isRetroactiveWorldFirst(PLAYER_A, null, true));
        assertFalse(MilestoneTriggerLogic.isRetroactiveWorldFirst(null, PLAYER_A, true));
        assertFalse(MilestoneTriggerLogic.isRetroactiveWorldFirst(null, null, true));
    }
}
