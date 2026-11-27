package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-150-80: four anchors, scale bounds, three-second retreat, no existing-screen changes. */
class HudLayoutOptionsTest {
    @Test
    void anchorsRoundTripAndUnknownValuesFallBack() {
        for (HudLayoutOptions.Anchor anchor : HudLayoutOptions.Anchor.values()) {
            assertEquals(anchor, HudLayoutOptions.Anchor.fromConfig(anchor.configName()));
        }
        assertEquals(HudLayoutOptions.Anchor.TOP_LEFT, HudLayoutOptions.Anchor.fromConfig(null));
        assertEquals(HudLayoutOptions.Anchor.TOP_LEFT, HudLayoutOptions.Anchor.fromConfig("sideways"));
    }

    @Test
    void scaleIsClampedToFiftyTwoHundredAndMatrixOnly() {
        assertEquals(50, HudLayoutOptions.clampScale(-1));
        assertEquals(100, HudLayoutOptions.clampScale(100));
        assertEquals(200, HudLayoutOptions.clampScale(999));
        assertEquals(75, HudLayoutOptions.fromConfig("top-left", true, 75).scaled(100));
        assertEquals(25, HudLayoutOptions.fromConfig("top-left", true, 50).scaled(50));
        assertEquals(400, HudLayoutOptions.fromConfig("top-left", true, 200).scaled(200));
    }

    @Test
    void positionUsesOnlyTheOverlaysOwnCorners() {
        HudLayoutOptions options = HudLayoutOptions.defaults();
        assertEquals(new HudLayoutOptions.Position(8, 8), options.position(1000, 600, 200, 100));
        assertEquals(new HudLayoutOptions.Position(792, 8),
                HudLayoutOptions.fromConfig("top-right", true, 100).position(1000, 600, 200, 100));
        assertEquals(new HudLayoutOptions.Position(8, 492),
                HudLayoutOptions.fromConfig("bottom-left", true, 100).position(1000, 600, 200, 100));
        assertEquals(new HudLayoutOptions.Position(792, 492),
                HudLayoutOptions.fromConfig("bottom-right", true, 100).position(1000, 600, 200, 100));
    }

    @Test
    void autoHideFadesForThreeSecondsUnlessReducedMotionIsOn() {
        HudLayoutOptions options = HudLayoutOptions.defaults();
        assertTrue(options.autoHide());
        assertEquals(3000L, HudLayoutOptions.AUTO_HIDE_MILLIS);
        // 0 is the "no trigger yet" sentinel; a real trigger is a positive wall-clock time.
        long triggeredAt = 1000L;
        assertFalse(options.retreating(0L, 0L), "zero sentinel means no retreat is active");
        assertFalse(options.retreating(triggeredAt - 1, triggeredAt), "retreat cannot precede its trigger");
        assertTrue(options.retreating(3999L, triggeredAt));
        assertFalse(options.retreating(4000L, triggeredAt));
        assertEquals(255, options.fadeAlpha(1000L, triggeredAt, false));
        assertEquals(255, options.fadeAlpha(4000L, triggeredAt, false));
        assertTrue(options.fadeAlpha(2500L, triggeredAt, false) < 255);
        assertEquals(0, options.fadeAlpha(2500L, triggeredAt, true), "reduced motion hides immediately");
        HudLayoutOptions disabled = HudLayoutOptions.fromConfig("top-left", false, 100);
        assertFalse(disabled.retreating(2500L, triggeredAt));
        assertEquals(255, disabled.fadeAlpha(2500L, triggeredAt, false));
    }
}
