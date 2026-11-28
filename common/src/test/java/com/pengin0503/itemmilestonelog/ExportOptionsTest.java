package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportOptionsTest {
    @Test
    void defaultsPreserveTheExistingExportShape() {
        ExportOptions options = ExportOptions.DEFAULT;
        assertEquals(0, options.sinceDays());
        assertFalse(options.anonymized());
        assertFalse(options.snapshot());
        assertEquals("pretty", options.formatVariant());
    }

    @Test
    void sinceDaysIsClampedToTheSingleSharedRange() {
        assertEquals(365, new ExportOptions(9999, false, false, "pretty").sinceDays());
        assertEquals(0, new ExportOptions(-3, false, false, "pretty").sinceDays());
        assertEquals(7, ExportOptions.DEFAULT.withSinceDays(7).sinceDays());
    }

    @Test
    void withersKeepEveryOtherField() {
        ExportOptions options = ExportOptions.DEFAULT.withSinceDays(30)
                .withAnonymized(true).withSnapshot(true);
        assertEquals(30, options.sinceDays());
        assertTrue(options.anonymized());
        assertTrue(options.snapshot());
        assertEquals("pretty", options.formatVariant());
        assertEquals("pretty", new ExportOptions(0, false, false, null).formatVariant());
    }
}
