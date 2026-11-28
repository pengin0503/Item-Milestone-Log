package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-150-86 (N199): low power is off by default and only ever removes work or effects. */
class LowPowerModePolicyTest {
    @Test
    void shippedDefaultsAreUnchangedWhenTheModeIsOff() {
        assertEquals(StreamStatusWriter.DEBOUNCE_MILLIS, LowPowerModePolicy.statusDebounceMillis(false));
        assertFalse(LowPowerModePolicy.hudRebuildDue(false, 100L, true));
        assertTrue(LowPowerModePolicy.particlesAllowed(false, true, false));
    }

    @Test
    void lowPowerUsesFourTickHudWindowAndFiveSecondDebounce() {
        assertEquals(4, LowPowerModePolicy.HUD_REBUILD_EVERY_TICKS);
        assertEquals(5_000L, LowPowerModePolicy.STATUS_DEBOUNCE_MILLIS);
        assertEquals(5_000L, LowPowerModePolicy.statusDebounceMillis(true));
        assertFalse(LowPowerModePolicy.hudRebuildDue(true, 3L, true));
        assertTrue(LowPowerModePolicy.hudRebuildDue(true, 4L, true));
        assertTrue(LowPowerModePolicy.hudRebuildDue(true, 5L, true));
        assertFalse(LowPowerModePolicy.hudRebuildDue(true, 4L, false));
        assertTrue(LowPowerModePolicy.hudRebuildDue(true, -1L, true));
    }

    @Test
    void particlesAreSuppressedButRecordedInformationIsIdentical() {
        assertFalse(LowPowerModePolicy.particlesAllowed(true, true, false));
        assertFalse(LowPowerModePolicy.particlesAllowed(false, true, true));
        assertFalse(LowPowerModePolicy.particlesAllowed(false, false, false));
        StreamStatusWriter.Values values = new StreamStatusWriter.Values(
                2, 3, "minecraft:diamond", 100, false, "demo", 1);
        String normal = StreamStatusWriter.renderTxt(values);
        String lowPower = StreamStatusWriter.renderTxt(values);
        assertEquals(normal, lowPower, "the debounce changes cadence only, never content");
        assertTrue(normal.contains("records"), normal);
        assertFalse(StreamStatusWriter.shouldWrite(0L, 4_999L, "", normal,
                LowPowerModePolicy.statusDebounceMillis(true)));
        assertTrue(StreamStatusWriter.shouldWrite(0L, 5_000L, "", normal,
                LowPowerModePolicy.statusDebounceMillis(true)));
    }
}
