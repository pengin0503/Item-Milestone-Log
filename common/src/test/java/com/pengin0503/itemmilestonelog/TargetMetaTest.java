package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetMetaTest {
    @Test
    void defaultsAndConstantsAreStable() {
        assertEquals(new TargetMeta(0, "", false), TargetMeta.DEFAULT);
        assertEquals(0, TargetMeta.DEFAULT.priority());
        assertEquals("", TargetMeta.DEFAULT.label());
        assertFalse(TargetMeta.DEFAULT.pinned());
        assertEquals(new TargetMeta(-1, "", false), TargetMeta.INVALID);
        assertEquals(32, TargetMeta.MAX_LABEL_LENGTH);
        assertEquals(TargetMeta.DEFAULT, TargetMeta.of(0, "", false));
    }

    @Test
    void isValidPriorityAcceptsZeroThroughNineOnly() {
        for (int i = 0; i <= 9; i++) {
            assertTrue(TargetMeta.isValidPriority(i), "priority " + i);
        }
        assertFalse(TargetMeta.isValidPriority(-1));
        assertFalse(TargetMeta.isValidPriority(10));
        assertFalse(TargetMeta.isValidPriority(Integer.MIN_VALUE));
        assertFalse(TargetMeta.isValidPriority(Integer.MAX_VALUE));
    }

    @Test
    void sanitizeLabelStripsNewlinesCarriageReturnsAndSectionCodes() {
        assertEquals("helloworldred", TargetMeta.sanitizeLabel("  hello\nworld\r\u00a7red  "));
        assertEquals("acbc", TargetMeta.sanitizeLabel("a\u00a7c\nb\rc"));
        assertEquals("", TargetMeta.sanitizeLabel(null));
        assertEquals("", TargetMeta.sanitizeLabel("   "));
        assertEquals("", TargetMeta.sanitizeLabel("\n\r\u00a7"));
        assertEquals("plain", TargetMeta.sanitizeLabel("plain"));
    }

    @Test
    void sanitizeLabelTruncatesByCodePointsNotUtf16Units() {
        String emoji = "\ud83d\ude00"; // one code point, two UTF-16 units
        String result = TargetMeta.sanitizeLabel(emoji.repeat(40) + "trailing");
        assertEquals(32, result.codePointCount(0, result.length()));
        assertEquals(emoji.repeat(32), result);
        assertEquals("x".repeat(32), TargetMeta.sanitizeLabel("x".repeat(33)));
        assertEquals("", TargetMeta.sanitizeLabel("\u00a7".repeat(100)));
    }

    @Test
    void ofReturnsInvalidForBadPriorityOrOverlongCleanedLabel() {
        assertEquals(TargetMeta.INVALID, TargetMeta.of(-1, "ok", false));
        assertEquals(TargetMeta.INVALID, TargetMeta.of(10, "ok", false));
        assertEquals(TargetMeta.INVALID, TargetMeta.of(Integer.MIN_VALUE, "", false));
        assertEquals(TargetMeta.INVALID, TargetMeta.of(0, "x".repeat(33), false));
        assertEquals(TargetMeta.INVALID, TargetMeta.of(5, "a\u00a7".repeat(34), false));
        // Section codes are removed before the length check.
        assertEquals(new TargetMeta(9, "x".repeat(32), true),
                TargetMeta.of(9, ("\u00a7" + "x".repeat(32)), true));
    }

    @Test
    void ofSanitizesLabelsAndNormalizesNull() {
        assertEquals(new TargetMeta(5, "hithere", true), TargetMeta.of(5, "  hi\n\u00a7there  ", true));
        assertEquals(new TargetMeta(0, "", false), TargetMeta.of(0, null, false));
        assertEquals(new TargetMeta(3, "", true), TargetMeta.of(3, "\r\n", true));
    }

    @Test
    void recordsAreImmutableValueObjects() {
        TargetMeta meta = TargetMeta.of(7, "label", true);
        assertEquals(new TargetMeta(7, "label", true), meta);
        assertEquals(new TargetMeta(7, "label", true).hashCode(), meta.hashCode());
        assertNotEquals(TargetMeta.DEFAULT, meta);
        assertNotEquals(TargetMeta.INVALID, meta);
        // The shared constants cannot be mutated through subsequent factory calls.
        TargetMeta.of(9, "changed", true);
        assertEquals("", TargetMeta.DEFAULT.label());
        assertEquals(-1, TargetMeta.INVALID.priority());
        assertEquals(new TargetMeta(0, null, false), new TargetMeta(0, "", false));
    }
}
