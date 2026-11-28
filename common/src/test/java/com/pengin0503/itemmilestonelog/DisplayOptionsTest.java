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
        // F-150-63/80/82: HUD is off by default; its layout options are profile-overridable.
        assertEquals("top-left", defaults.pinHudAnchor());
        assertTrue(defaults.pinHudAutoHide());
        assertEquals(100, defaults.pinHudScale());
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
    void toOverridesContainsExactlyTheTwelveAllowedKeys() {
        Map<String, String> overrides = DisplayOptions.defaults().toOverrides();
        assertEquals(DisplayOptions.KEY_SET, overrides.keySet());
        assertEquals(12, overrides.size());
        assertEquals(DisplayOptions.defaults(), DisplayOptions.defaults().apply(overrides));
    }

    @Test
    void hudLayoutKeysAreProfileOverridableAndClamped() {
        DisplayOptions changed = DisplayOptions.defaults().apply(Map.of(
                "pinHudAnchor", "bottom-right",
                "pinHudAutoHide", "false",
                "pinHudScale", "999",
                "pinHudEnabled", "true"));
        assertEquals("bottom-right", changed.pinHudAnchor());
        assertFalse(changed.pinHudAutoHide());
        assertEquals(HudLayoutOptions.MAX_SCALE, changed.pinHudScale());
        assertTrue(changed.pinHudEnabled());
        assertEquals(DisplayOptions.defaults().pinHudAnchor(),
                DisplayOptions.defaults().apply(Map.of("pinHudAnchor", "not-an-anchor")).pinHudAnchor());
    }

    @Test
    void profileOverridesReachTheTooltipRefineGate() {
        assertEquals(TooltipOptions.MAX_MAX_LINES, DisplayOptions.MAX_TOOLTIP_LINES);
        DisplayOptions profile = DisplayOptions.defaults().apply(Map.of(
                "tooltipMaxLines", "9", "tooltipShowTarget", "false",
                "tooltipSort", "recorded-first"));
        TooltipOptions refined = TooltipOptions.fromDisplayOptions(profile, TooltipOptions.DEFAULT);
        assertEquals(9, refined.maxLines());
        assertFalse(refined.showTarget());
        assertEquals(TooltipOptions.Sort.RECORDED_FIRST, refined.sort());
        assertEquals(TooltipOptions.DEFAULT.statusColor(), refined.statusColor());
        assertEquals(TooltipOptions.DEFAULT.tagNames(), refined.tagNames());
    }

    @Test
    void numericOverridesAreClamped() {
        DisplayOptions options = DisplayOptions.defaults().apply(Map.of(
                "pinHudMaxRows", "999", "tooltipMaxLines", "-4"));
        assertEquals(DisplayOptions.MAX_PIN_HUD_ROWS, options.pinHudMaxRows());
        assertEquals(1, options.tooltipMaxLines());
    }
}
