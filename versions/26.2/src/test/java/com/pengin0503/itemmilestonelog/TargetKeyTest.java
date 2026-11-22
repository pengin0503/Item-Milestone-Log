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
        assertNull(TargetKey.tryParseEncoded("minecraft:diamond#abc"));
        assertNull(TargetKey.tryParseEncoded("minecraft:diamond#1#2"));

        assertThrows(IllegalArgumentException.class, () -> new TargetKey("minecraft:diamond", 0));
        assertThrows(IllegalArgumentException.class, () -> new TargetKey("minecraft:diamond", 1_000_001));
        assertThrows(IllegalArgumentException.class, () -> new TargetKey("minecraft:diamond#2", 1));
    }

    @Test
    void validatesConfigEntries() {
        assertTrue(Config.validateTargetEntry("minecraft:diamond"));
        assertTrue(Config.validateTargetEntry("minecraft:diamond#100"));
        assertTrue(Config.validateTargetEntry("minecraft:diamond#1000000"));
        assertFalse(Config.validateTargetEntry("minecraft:diamond#0"));
        assertFalse(Config.validateTargetEntry("minecraft:diamond#1000001"));
        assertFalse(Config.validateTargetEntry("minecraft:diamond#abc"));
        assertFalse(Config.validateTargetEntry("no_namespace#10"));
        assertFalse(Config.validateTargetEntry(123));
    }
}
