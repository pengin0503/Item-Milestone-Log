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
    void parsesTagAndItemEncodedExamplesAndSupportsIsTagAndTagId() {
        // Spec F8a valid examples
        TargetKey ironTag = TargetKey.tryParseEncoded("#c:ingots/iron");
        assertEquals(TargetKey.of("#c:ingots/iron", 1), ironTag);
        assertTrue(ironTag.isTag());
        assertEquals("c:ingots/iron", ironTag.tagId());
        assertEquals("#c:ingots/iron", ironTag.toEncodedString());
        assertEquals("", ironTag.formatSuffix());
        assertEquals("#c:ingots/iron", ironTag.formatDisplayId());

        TargetKey ironTag100 = TargetKey.tryParseEncoded("#c:ingots/iron#100");
        assertEquals(TargetKey.of("#c:ingots/iron", 100), ironTag100);
        assertTrue(ironTag100.isTag());
        assertEquals("c:ingots/iron", ironTag100.tagId());
        assertEquals("#c:ingots/iron#100", ironTag100.toEncodedString());
        assertEquals(" ×100", ironTag100.formatSuffix());
        assertEquals("#c:ingots/iron ×100", ironTag100.formatDisplayId());

        TargetKey diamond5 = TargetKey.tryParseEncoded("minecraft:diamond#5");
        assertEquals(TargetKey.of("minecraft:diamond", 5), diamond5);
        assertFalse(diamond5.isTag());
        assertNull(diamond5.tagId());

        // Additional valid tag edge cases
        assertEquals(ironTag, TargetKey.tryParseEncoded("#c:ingots/iron#1"));
        assertEquals(ironTag100, TargetKey.tryParseEncoded("  #c:ingots/iron#100  "));
        TargetKey maxTag = TargetKey.tryParseEncoded("#minecraft:logs#1000000");
        assertEquals(TargetKey.of("#minecraft:logs", TargetKey.MAX_COUNT), maxTag);
        assertTrue(maxTag.isTag());
        assertEquals("minecraft:logs", maxTag.tagId());

        // Static isTagId helper
        assertTrue(TargetKey.isTagId("#c:ingots/iron"));
        assertFalse(TargetKey.isTagId("minecraft:diamond"));
        assertFalse(TargetKey.isTagId(""));
        assertFalse(TargetKey.isTagId(null));

        // isValidItemId accepts valid tag forms and rejects invalid ones
        assertTrue(TargetKey.isValidItemId("#c:ingots/iron"));
        assertTrue(TargetKey.isValidItemId("#mod_1.2-a:sub/path_1.0-b"));
        assertFalse(TargetKey.isValidItemId("#"));
        assertFalse(TargetKey.isValidItemId("##c:x"));
        assertFalse(TargetKey.isValidItemId("#:x"));
        assertFalse(TargetKey.isValidItemId("#c:"));
        assertFalse(TargetKey.isValidItemId("#10"));
        assertFalse(TargetKey.isValidItemId("#logs"));
        assertFalse(TargetKey.isValidItemId("#C:ingots/iron"));
        assertFalse(TargetKey.isValidItemId("#c:ingots/Iron"));
        assertFalse(TargetKey.isValidItemId("#c:a:b"));
        assertFalse(TargetKey.isValidItemId("#c:ingots/iron#10"));

        // Spec F8a invalid examples (every example from the spec)
        assertNull(TargetKey.tryParseEncoded("#"));
        assertNull(TargetKey.tryParseEncoded("##c:x"));
        assertNull(TargetKey.tryParseEncoded("##c:x#5"));
        assertNull(TargetKey.tryParseEncoded("#c:x#"));
        assertNull(TargetKey.tryParseEncoded("#c:x#0"));
        assertNull(TargetKey.tryParseEncoded("#c:x#abc"));
        assertNull(TargetKey.tryParseEncoded("#:x"));
        assertNull(TargetKey.tryParseEncoded("#c:"));
        assertNull(TargetKey.tryParseEncoded("c:x#1#2"));
        assertNull(TargetKey.tryParseEncoded("#c:x#1#2"));
        assertNull(TargetKey.tryParseEncoded("#c:x#-5"));
        assertNull(TargetKey.tryParseEncoded("#c:x#1000001"));
        assertNull(TargetKey.tryParseEncoded("#c:x #5"));
        assertNull(TargetKey.tryParseEncoded("#C:x"));
        assertNull(TargetKey.tryParseEncoded("#c:X"));

        // Constructor enforces tag syntax too
        assertThrows(IllegalArgumentException.class, () -> new TargetKey("#", 1));
        assertThrows(IllegalArgumentException.class, () -> new TargetKey("##c:x", 1));
        assertThrows(IllegalArgumentException.class, () -> new TargetKey("#:x", 1));
        assertThrows(IllegalArgumentException.class, () -> new TargetKey("#c:", 1));
        assertThrows(IllegalArgumentException.class, () -> new TargetKey("#c:x#5", 1));
    }

    @Test
    void normalizesItemAndTagIds() {
        // Explicit and implicit namespaces for items
        assertEquals("minecraft:diamond", TargetKey.normalizeItemId("minecraft:diamond", true));
        assertEquals("minecraft:diamond", TargetKey.normalizeItemId("minecraft:diamond", false));
        assertEquals("minecraft:diamond", TargetKey.normalizeItemId("diamond", false));
        assertNull(TargetKey.normalizeItemId("diamond", true));
        assertEquals("mod_1.2-a:sub/path_1.0-b", TargetKey.normalizeItemId("mod_1.2-a:sub/path_1.0-b", true));

        // Valid tag IDs (explicit namespace:path required)
        assertEquals("#c:ingots/iron", TargetKey.normalizeItemId("#c:ingots/iron"));
        assertEquals("#c:ingots/iron", TargetKey.normalizeItemId("#c:ingots/iron", true));
        assertEquals("#minecraft:logs", TargetKey.normalizeItemId("#minecraft:logs", false));
        assertEquals("#mod_1.2-a:sub/path_1.0-b", TargetKey.normalizeItemId("#mod_1.2-a:sub/path_1.0-b", true));

        // Invalid syntax
        assertNull(TargetKey.normalizeItemId(null));
        assertNull(TargetKey.normalizeItemId(""));
        assertNull(TargetKey.normalizeItemId(" minecraft:diamond "));
        assertNull(TargetKey.normalizeItemId(" #c:ingots/iron "));
        assertNull(TargetKey.normalizeItemId("minecraft:Diamond"));
        assertNull(TargetKey.normalizeItemId("Minecraft:diamond"));
        assertNull(TargetKey.normalizeItemId(":diamond"));
        assertNull(TargetKey.normalizeItemId("minecraft:"));
        assertNull(TargetKey.normalizeItemId("a:b:c"));
        assertNull(TargetKey.normalizeItemId("#"));
        assertNull(TargetKey.normalizeItemId("##c:x"));
        assertNull(TargetKey.normalizeItemId("#:x"));
        assertNull(TargetKey.normalizeItemId("#c:"));
        assertNull(TargetKey.normalizeItemId("#logs"));
        assertNull(TargetKey.normalizeItemId("#10"));
        assertNull(TargetKey.normalizeItemId("#c:ingots/Iron"));

        // normalize(rawItemId, count, requireNamespace, rejectAir)
        assertEquals(TargetKey.of("minecraft:diamond", 64),
                TargetKey.normalize("diamond", 64, false, true));
        assertNull(TargetKey.normalize("diamond", 64, true, true));
        assertNull(TargetKey.normalize("minecraft:air", 1, false, true));
        assertNull(TargetKey.normalize("air", 1, false, true));
        assertEquals(TargetKey.of("minecraft:air", 1),
                TargetKey.normalize("minecraft:air", 1, false, false));
        assertNull(TargetKey.normalize("minecraft:diamond", 0, false, false));
        assertEquals(TargetKey.of("#c:ingots/iron", 100),
                TargetKey.normalize("#c:ingots/iron", 100, true, true));
        assertEquals(TargetKey.of("#c:ingots/iron", 100),
                TargetKey.normalize("#c:ingots/iron", 100, false, true));
        assertNull(TargetKey.normalize("#c:ingots/iron", 0, true, true));

        // tryParseNormalized trims and normalizes encoded item and tag keys
        assertEquals(TargetKey.of("minecraft:diamond", 10),
                TargetKey.tryParseNormalized("  diamond#10  ", false, true));
        assertNull(TargetKey.tryParseNormalized("diamond#10", true, true));
        assertNull(TargetKey.tryParseNormalized("minecraft:air#5", true, true));
        assertEquals(TargetKey.of("#c:ingots/iron", 1),
                TargetKey.tryParseNormalized("#c:ingots/iron", false, true));
        assertEquals(TargetKey.of("#c:ingots/iron", 10),
                TargetKey.tryParseNormalized("  #c:ingots/iron#10  ", true, true));
        assertNull(TargetKey.tryParseNormalized("#c:x#0", false, true));
        assertNull(TargetKey.tryParseNormalized("##c:x", false, true));
        assertNull(TargetKey.tryParseNormalized("c:x#1#2", false, true));
    }

    @Test
    void validatesConfigEntries() {
        assertTrue(Config.validateTargetEntry("minecraft:diamond"));
        assertTrue(Config.validateTargetEntry("minecraft:diamond#100"));
        assertTrue(Config.validateTargetEntry("minecraft:diamond#1000000"));
        assertTrue(Config.validateTargetEntry("#c:ingots/iron"));
        assertTrue(Config.validateTargetEntry("#c:ingots/iron#10"));
        assertTrue(Config.validateTargetEntry("#nonexistent:future_tag#64"));
        assertFalse(Config.validateTargetEntry("minecraft:air"));
        assertFalse(Config.validateTargetEntry("minecraft:air#5"));
        assertFalse(Config.validateTargetEntry("minecraft:diamond#0"));
        assertFalse(Config.validateTargetEntry("minecraft:diamond#1000001"));
        assertFalse(Config.validateTargetEntry("minecraft:diamond#2147483648"));
        assertFalse(Config.validateTargetEntry("minecraft:diamond#abc"));
        assertFalse(Config.validateTargetEntry("no_namespace#10"));
        assertFalse(Config.validateTargetEntry("#"));
        assertFalse(Config.validateTargetEntry("##c:x"));
        assertFalse(Config.validateTargetEntry("#c:x#"));
        assertFalse(Config.validateTargetEntry("#c:x#0"));
        assertFalse(Config.validateTargetEntry("#c:x#abc"));
        assertFalse(Config.validateTargetEntry("#:x"));
        assertFalse(Config.validateTargetEntry("#c:"));
        assertFalse(Config.validateTargetEntry("c:x#1#2"));
        assertFalse(Config.validateTargetEntry(123));

        assertTrue(Config.validateDisplayTimeZone(""));
        assertTrue(Config.validateDisplayTimeZone("UTC"));
        assertTrue(Config.validateDisplayTimeZone("Asia/Tokyo"));
        assertTrue(Config.validateDisplayTimeZone("+09:00"));
        assertFalse(Config.validateDisplayTimeZone("Invalid/Zone"));
        assertFalse(Config.validateDisplayTimeZone(123));
        assertFalse(Config.validateDisplayTimeZone(null));

        assertTrue(Config.validateFirstJoinPreset("vanilla_endgame"));
        assertTrue(Config.validateFirstJoinPreset("atm_star"));
        assertTrue(Config.validateFirstJoinPreset("my_pack-1"));
        assertTrue(Config.validateFirstJoinPreset("a".repeat(32)));
        assertFalse(Config.validateFirstJoinPreset(""));
        assertFalse(Config.validateFirstJoinPreset(" "));
        assertFalse(Config.validateFirstJoinPreset("Vanilla_Endgame"));
        assertFalse(Config.validateFirstJoinPreset("bad name"));
        assertFalse(Config.validateFirstJoinPreset("bad.id"));
        assertFalse(Config.validateFirstJoinPreset("ns:preset"));
        assertFalse(Config.validateFirstJoinPreset("a".repeat(33)));
        assertFalse(Config.validateFirstJoinPreset("list"));
        assertFalse(Config.validateFirstJoinPreset("save"));
        assertFalse(Config.validateFirstJoinPreset("delete"));
        assertFalse(Config.validateFirstJoinPreset("reload"));
        assertFalse(Config.validateFirstJoinPreset(123));
        assertFalse(Config.validateFirstJoinPreset(null));
    }
}
