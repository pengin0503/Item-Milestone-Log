package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayTimeZoneTest {
    @Test
    void validatesEmptyIanaAndOffsetValues() {
        assertTrue(DisplayTimeZone.isValidConfigValue(""));
        assertTrue(DisplayTimeZone.isValidConfigValue("   "));
        assertTrue(DisplayTimeZone.isValidConfigValue("UTC"));
        assertTrue(DisplayTimeZone.isValidConfigValue("Z"));
        assertTrue(DisplayTimeZone.isValidConfigValue("Asia/Tokyo"));
        assertTrue(DisplayTimeZone.isValidConfigValue("  Asia/Tokyo  "));
        assertTrue(DisplayTimeZone.isValidConfigValue("America/New_York"));
        assertTrue(DisplayTimeZone.isValidConfigValue("+09:00"));
        assertTrue(DisplayTimeZone.isValidConfigValue("-05:00"));
        assertTrue(DisplayTimeZone.isValidConfigValue("GMT+9"));

        assertFalse(DisplayTimeZone.isValidConfigValue(null));
        assertFalse(DisplayTimeZone.isValidConfigValue(123));
        assertFalse(DisplayTimeZone.isValidConfigValue("Not/A_Zone"));
        assertFalse(DisplayTimeZone.isValidConfigValue("+99:00"));
        assertFalse(DisplayTimeZone.isValidConfigValue("invalid timezone"));
    }

    @Test
    void resolvesEmptyIanaOffsetsAndFollowsChanges() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC));
            assertEquals(ZoneId.systemDefault(), DisplayTimeZone.resolve(null));
            assertEquals(ZoneId.systemDefault(), DisplayTimeZone.resolve(""));
            assertEquals(ZoneId.systemDefault(), DisplayTimeZone.resolve("   "));

            assertEquals(ZoneId.of("Asia/Tokyo"), DisplayTimeZone.resolve("Asia/Tokyo"));
            assertEquals(ZoneId.of("Asia/Tokyo"), DisplayTimeZone.resolve("  Asia/Tokyo  "));
            assertEquals(ZoneId.of("+09:00"), DisplayTimeZone.resolve("+09:00"));
            assertEquals(ZoneId.of("-05:00"), DisplayTimeZone.resolve("-05:00"));
            assertEquals(ZoneId.of("UTC"), DisplayTimeZone.resolve("UTC"));

            // Invalid value falls back to system default
            assertEquals(ZoneId.systemDefault(), DisplayTimeZone.resolve("Not/A_Zone"));

            // Switching default timezone is reflected when configured value is empty
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            assertEquals(ZoneId.of("Asia/Tokyo"), DisplayTimeZone.resolve(""));
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
