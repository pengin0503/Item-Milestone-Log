package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresetRequiredModsLogicTest {
    @Test
    void noRequirementMeansNothingMissing() {
        assertTrue(PresetRequiredModsLogic.missingMods(List.of(), id -> false).isEmpty());
        assertTrue(PresetRequiredModsLogic.missingMods(null, id -> false).isEmpty());
    }

    @Test
    void allModsLoadedMeansNothingMissing() {
        Set<String> loaded = Set.of("create", "jei");
        assertTrue(PresetRequiredModsLogic.missingMods(List.of("create", "jei"), loaded::contains).isEmpty());
    }

    @Test
    void someMissingAreReportedInRequirementOrder() {
        Set<String> loaded = Set.of("create");
        assertEquals(List.of("jei", "ae2"),
                PresetRequiredModsLogic.missingMods(List.of("create", "jei", "ae2"), loaded::contains));
    }

    @Test
    void allMissingAreReported() {
        assertEquals(List.of("create", "jei"),
                PresetRequiredModsLogic.missingMods(List.of("create", "jei"), id -> false));
    }

    @Test
    void duplicatesAndBlanksAreIgnored() {
        assertEquals(List.of("jei"),
                PresetRequiredModsLogic.missingMods(java.util.Arrays.asList("jei", "jei", " ", null), id -> false));
    }

    @Test
    void nullPredicateMeansNothingIsLoaded() {
        assertEquals(List.of("create"), PresetRequiredModsLogic.missingMods(List.of("create"), null));
    }
}
