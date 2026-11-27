package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D-150-206 regression coverage for the stable immutable display-options record. */
class DisplayOptionsTest {
    @Test
    void defaultsKeepExistingPlayerExperience() {
        DisplayOptions defaults = DisplayOptions.defaults();
        assertFalse(defaults.pinHudEnabled(), "pin HUD is OFF by default (D-150-188)");
        assertEquals(5, defaults.pinHudMaxRows());
        assertEquals(3, defaults.tooltipMaxLines());
        assertTrue(defaults.tooltipShowTarget());
        assertTrue(defaults.tooltipShowRecorded());
        assertFalse(defaults.showRecordClock());
        assertFalse(defaults.clientEffectEnabled());
    }

    @Test
    void applyIgnoresUnknownKeysAndBadValues() {
        DisplayOptions original = DisplayOptions.defaults();
        DisplayOptions changed = original.apply(Map.of(
                "pinHudEnabled", "true",
                "tooltipMaxLines", "7",
                "serverOnlyFlag", "false",
                "pinHudMaxRows", "not-a-number"));
        assertNotSame(original, changed);
        assertTrue(changed.pinHudEnabled());
        assertEquals(7, changed.tooltipMaxLines());
        assertEquals(5, changed.pinHudMaxRows(), "malformed value keeps the previous value");
        assertFalse(original.pinHudEnabled(), "the original record is immutable");
    }

    @Test
    void toOverridesContainsExactlyTheNineAllowedKeys() {
        Map<String, String> overrides = DisplayOptions.defaults().toOverrides();
        assertEquals(DisplayOptions.KEY_SET, overrides.keySet());
        assertEquals(DisplayOptions.defaults(), DisplayOptions.defaults().apply(overrides));
    }

    @Test
    void numericOverridesAreClamped() {
        DisplayOptions options = DisplayOptions.defaults().apply(Map.of(
                "pinHudMaxRows", "999", "tooltipMaxLines", "-4"));
        assertEquals(DisplayOptions.MAX_PIN_HUD_ROWS, options.pinHudMaxRows());
        assertEquals(1, options.tooltipMaxLines());
    }
}
