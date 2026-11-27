package com.pengin0503.itemmilestonelog.client;

import com.pengin0503.itemmilestonelog.Config;
import com.pengin0503.itemmilestonelog.DisplayOptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * F-150-63 / F-150-80 / F-150-82 (HUD part): the independent overlay is default-off, reads only
 * local/synced data and holds no screen widget, so the five frozen screens are untouched (FL-3).
 */
class HudWiringTest {
    @Test
    void hudIsOffByDefaultWithoutAnySnapshot() {
        assertFalse(Config.pinHudEnabled(), "config default is OFF while not loaded");
        assertFalse(DisplayOptions.defaults().pinHudEnabled(), "HUD is OFF by default (D-150-188)");
        assertFalse(MilestoneHudOverlay.isEnabled());
        assertNull(MilestoneHudOverlay.availableIds(), "no snapshot means no addable pin ids");
    }

    @Test
    void logoutClearsAllSnapshotState() {
        MilestoneHudOverlay.onLogout();
        assertNull(MilestoneHudOverlay.availableIds());
        assertFalse(MilestoneHudOverlay.isEnabled());
    }

    @Test
    void overlayHoldsNoScreenWidget() {
        for (Field field : MilestoneHudOverlay.class.getDeclaredFields()) {
            assertFalse(field.getType().getName().contains("Screen"),
                    field.getName() + " must not be a Screen widget (FL-3)");
        }
    }
}
