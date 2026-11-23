package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.TargetKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetKeyTest {
    @Test
    void encodesAndParsesCounts() {
        TargetKey one = TargetKey.of("minecraft:diamond");
        assertEquals("minecraft:diamond", one.toEncodedString());
        assertEquals("", one.formatSuffix());
        assertEquals("minecraft:diamond", one.formatDisplayId());
        assertEquals(one, TargetKey.tryParseEncoded("minecraft:diamond"));
        assertEquals(one, TargetKey.tryParseEncoded("minecraft:diamond#1"));

        TargetKey hundred = TargetKey.of("minecraft:diamond", 100);
        assertEquals("minecraft:diamond#100", hundred.toEncodedString());
        assertEquals(" ×100", hundred.formatSuffix());
        assertEquals("minecraft:diamond ×100", hundred.formatDisplayId());
        assertEquals(hundred, TargetKey.tryParseEncoded("minecraft:diamond#100"));

        TargetKey max = TargetKey.of("minecraft:stone", TargetKey.MAX_COUNT);
        assertEquals(max, TargetKey.tryParseEncoded("minecraft:stone#1000000"));
    }

    @Test
    void rejectsInvalidEncodedStringsAndCounts() {
        assertNull(TargetKey.tryParseEncoded(null));
        assertNull(TargetKey.tryParseEncoded(""));
        assertNull(TargetKey.tryParseEncoded("   "));
        assertNull(TargetKey.tryParseEncoded("#10"));
        assertNull(TargetKey.tryParseEncoded("minecraft:diamond#"));
        assertNull(TargetKey.tryParseEncoded("minecraft:diamond#0"));
        assertNull(TargetKey.tryParseEncoded("minecraft:diamond#-5"));
        assertNull(TargetKey.tryParseEncoded("minecraft:diamond#+5"));
        assertNull(TargetKey.tryParseEncoded("minecraft:diamond#1000001"));
        assertNull(TargetKey.tryParseEncoded("minecraft:diamond#2147483648"));
        assertNull(TargetKey.tryParseEncoded("minecraft:diamond#99999999999999999999"));
        assertNull(TargetKey.tryParseEncoded("minecraft:diamond#abc"));
        assertNull(TargetKey.tryParseEncoded("minecraft:diamond#1#2"));
        assertNull(TargetKey.tryParseEncoded("minecraft:stone #5"));
        assertNull(TargetKey.tryParseEncoded("minecraft: stone"));
        assertNull(TargetKey.tryParseEncoded("minecraft:\tstone"));
        assertNull(TargetKey.tryParseEncoded("minecraft:\nstone#5"));
        assertNull(TargetKey.tryParseEncoded("minecraft:\u0000stone"));

        assertEquals(-1, TargetKey.tryParseCount("2147483648"));
        assertEquals(-1, TargetKey.tryParseCount("99999999999999999999"));

        assertThrows(IllegalArgumentException.class, () -> new TargetKey(null, 1));
        assertThrows(IllegalArgumentException.class, () -> new TargetKey("", 1));
        assertThrows(IllegalArgumentException.class, () -> new TargetKey(" ", 1));
        assertThrows(IllegalArgumentException.class, () -> new TargetKey(" minecraft:stone ", 1));
        assertThrows(IllegalArgumentException.class, () -> new TargetKey("minecraft:stone ", 1));
        assertThrows(IllegalArgumentException.class, () -> new TargetKey("minecraft:\tstone", 1));
        assertThrows(IllegalArgumentException.class, () -> new TargetKey("minecraft:\nstone", 1));
        assertThrows(IllegalArgumentException.class, () -> new TargetKey("minecraft:\u0000stone", 1));
        assertThrows(IllegalArgumentException.class, () -> new TargetKey("minecraft:diamond", 0));
        assertThrows(IllegalArgumentException.class, () -> new TargetKey("minecraft:diamond", 1_000_001));
        assertThrows(IllegalArgumentException.class, () -> new TargetKey("minecraft:diamond#2", 1));
    }

    @Test
    void normalizesItemIdsAndRejectsTagSyntaxIn110() {
        // Explicit and implicit namespaces
        assertEquals("minecraft:diamond", TargetKey.normalizeItemId("minecraft:diamond", true));
        assertEquals("minecraft:diamond", TargetKey.normalizeItemId("minecraft:diamond", false));
        assertEquals("minecraft:diamond", TargetKey.normalizeItemId("diamond", false));
        assertNull(TargetKey.normalizeItemId("diamond", true));
        assertEquals("mod_1.2-a:sub/path_1.0-b", TargetKey.normalizeItemId("mod_1.2-a:sub/path_1.0-b", true));

        // Invalid syntax and #tag rejection in 1.1.0
        assertNull(TargetKey.normalizeItemId(null));
        assertNull(TargetKey.normalizeItemId(""));
        assertNull(TargetKey.normalizeItemId(" minecraft:diamond "));
        assertNull(TargetKey.normalizeItemId("minecraft:Diamond"));
        assertNull(TargetKey.normalizeItemId("Minecraft:diamond"));
        assertNull(TargetKey.normalizeItemId(":diamond"));
        assertNull(TargetKey.normalizeItemId("minecraft:"));
        assertNull(TargetKey.normalizeItemId("a:b:c"));
        assertNull(TargetKey.normalizeItemId("#c:ingots/iron"));
        assertNull(TargetKey.normalizeItemId("#c:ingots/iron", true));

        // normalize(rawItemId, count, requireNamespace, rejectAir)
        assertEquals(TargetKey.of("minecraft:diamond", 64),
                TargetKey.normalize("diamond", 64, false, true));
        assertNull(TargetKey.normalize("diamond", 64, true, true));
        assertNull(TargetKey.normalize("minecraft:air", 1, false, true));
        assertNull(TargetKey.normalize("air", 1, false, true));
        assertEquals(TargetKey.of("minecraft:air", 1),
                TargetKey.normalize("minecraft:air", 1, false, false));
        assertNull(TargetKey.normalize("minecraft:diamond", 0, false, false));

        // tryParseNormalized trims and normalizes encoded keys while rejecting tags
        assertEquals(TargetKey.of("minecraft:diamond", 10),
                TargetKey.tryParseNormalized("  diamond#10  ", false, true));
        assertNull(TargetKey.tryParseNormalized("diamond#10", true, true));
        assertNull(TargetKey.tryParseNormalized("minecraft:air#5", true, true));
        assertNull(TargetKey.tryParseNormalized("#c:ingots/iron", false, true));
        assertNull(TargetKey.tryParseNormalized("#c:ingots/iron#10", false, true));
    }

    @Test
    void validatesConfigEntries() {
        assertTrue(Config.validateTargetEntry("minecraft:diamond"));
        assertTrue(Config.validateTargetEntry("minecraft:diamond#100"));
        assertTrue(Config.validateTargetEntry("minecraft:diamond#1000000"));
        assertFalse(Config.validateTargetEntry("minecraft:air"));
        assertFalse(Config.validateTargetEntry("minecraft:air#5"));
        assertFalse(Config.validateTargetEntry("minecraft:diamond#0"));
        assertFalse(Config.validateTargetEntry("minecraft:diamond#1000001"));
        assertFalse(Config.validateTargetEntry("minecraft:diamond#2147483648"));
        assertFalse(Config.validateTargetEntry("minecraft:diamond#abc"));
        assertFalse(Config.validateTargetEntry("no_namespace#10"));
        assertFalse(Config.validateTargetEntry("#c:ingots/iron"));
        assertFalse(Config.validateTargetEntry("#c:ingots/iron#10"));
        assertFalse(Config.validateTargetEntry(123));

        assertTrue(Config.validateDisplayTimeZone(""));
        assertTrue(Config.validateDisplayTimeZone("UTC"));
        assertTrue(Config.validateDisplayTimeZone("Asia/Tokyo"));
        assertTrue(Config.validateDisplayTimeZone("+09:00"));
        assertFalse(Config.validateDisplayTimeZone("Invalid/Zone"));
        assertFalse(Config.validateDisplayTimeZone(123));
        assertFalse(Config.validateDisplayTimeZone(null));
    }
}
