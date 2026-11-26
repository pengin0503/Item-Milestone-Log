package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresetMetadataLogicTest {
    @Test
    void categoryStripsControlCharactersTrimsAndCapsAt64() {
        assertNull(PresetMetadataLogic.normalizeCategory(null));
        assertNull(PresetMetadataLogic.normalizeCategory("   "));
        assertEquals("Overworld Ores", PresetMetadataLogic.normalizeCategory("  Overworld Ores  "));
        assertEquals("ab", PresetMetadataLogic.normalizeCategory("a\u0000b\n\t"));
        assertEquals("X".repeat(64), PresetMetadataLogic.normalizeCategory("X".repeat(100)));
    }

    @Test
    void orderAcceptsNegativeAndRejectsNonIntegers() {
        assertEquals(0, PresetMetadataLogic.parseOrder("0"));
        assertEquals(-7, PresetMetadataLogic.parseOrder("-7"));
        assertEquals(42, PresetMetadataLogic.parseOrder(" 42 "));
        assertNull(PresetMetadataLogic.parseOrder("1.5"));
        assertNull(PresetMetadataLogic.parseOrder("abc"));
        assertNull(PresetMetadataLogic.parseOrder(""));
        assertNull(PresetMetadataLogic.parseOrder("99999999999999999999"));
    }

    @Test
    void versionMustBeAnIntegerAtLeastOne() {
        assertEquals(1, PresetMetadataLogic.parseVersion("1"));
        assertEquals(99, PresetMetadataLogic.parseVersion("99"));
        assertNull(PresetMetadataLogic.parseVersion("0"));
        assertNull(PresetMetadataLogic.parseVersion("-1"));
        assertNull(PresetMetadataLogic.parseVersion("1.5"));
        assertNull(PresetMetadataLogic.parseVersion("x"));
        assertNull(PresetMetadataLogic.parseVersion(null));
    }

    @Test
    void unlockAfterHoursAcceptsNonNegativeFiniteNumbers() {
        assertEquals(0.0d, PresetMetadataLogic.parseUnlockAfterHours("0"));
        assertEquals(2.5d, PresetMetadataLogic.parseUnlockAfterHours("2.5"));
        assertNull(PresetMetadataLogic.parseUnlockAfterHours("-0.1"));
        assertNull(PresetMetadataLogic.parseUnlockAfterHours("NaN"));
        assertNull(PresetMetadataLogic.parseUnlockAfterHours("Infinity"));
        assertNull(PresetMetadataLogic.parseUnlockAfterHours("abc"));
        assertNull(PresetMetadataLogic.parseUnlockAfterHours(null));
    }

    @Test
    void modIdValidationAcceptsLoaderStyleIds() {
        assertTrue(PresetMetadataLogic.isValidModId("create"));
        assertTrue(PresetMetadataLogic.isValidModId("appliedenergistics2"));
        assertTrue(PresetMetadataLogic.isValidModId("my_mod-1.x"));
        assertFalse(PresetMetadataLogic.isValidModId(""));
        assertFalse(PresetMetadataLogic.isValidModId("Bad Mod"));
        assertFalse(PresetMetadataLogic.isValidModId("Create"));
        assertFalse(PresetMetadataLogic.isValidModId("mod:thing"));
        assertFalse(PresetMetadataLogic.isValidModId("X".repeat(65)));
    }
}
