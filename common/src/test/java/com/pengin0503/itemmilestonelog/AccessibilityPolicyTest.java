package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-150-82 (HUD part): narration, contrast, reduced motion and conflict warning only. */
class AccessibilityPolicyTest {
    @Test
    void defaultsKeepNarrationOnAndContrastOff() {
        AccessibilityPolicy policy = AccessibilityPolicy.defaults();
        assertTrue(policy.accessibleNarration());
        assertFalse(policy.hudHighContrast());
        assertFalse(policy.reducedMotion());
        assertEquals(policy, AccessibilityPolicy.fromConfig(true, false, false));
    }

    @Test
    void conflictsIgnoreUnboundKeysAndDoNotChangeBindings() {
        Map<String, Integer> bindings = Map.of(
                "open_gui", -1,
                "add_hovered", -1,
                "mute_notification_sound", -1,
                "copy_recent", 42,
                "hotbar_1", 42);
        List<AccessibilityPolicy.Conflict> conflicts = AccessibilityPolicy.detectConflicts(bindings);
        assertEquals(1, conflicts.size());
        assertEquals(42, conflicts.get(0).keyCode());
        assertEquals("copy_recent", conflicts.get(0).first());
        assertEquals("hotbar_1", conflicts.get(0).second());
        assertEquals(42, bindings.get("copy_recent"), "warnings never rebind a key (D-150-226)");
        assertTrue(AccessibilityPolicy.detectConflicts(Map.of()).isEmpty());
        assertTrue(AccessibilityPolicy.detectConflicts(null).isEmpty());
    }

    @Test
    void reducedMotionOnlyRemovesTheFadeNotTheInformation() {
        List<PinStore.Pin> pins = List.of(new PinStore.Pin("minecraft:diamond", 1));
        Set<String> available = Set.of("minecraft:diamond");
        List<HudRowPlan.HudRow> normal = HudRowPlan.plan(pins, available);
        List<HudRowPlan.HudRow> reduced = HudRowPlan.plan(pins, available, Map.of(), Set.of(),
                Map.of(), false, HudRowPlan.DEFAULT_MAX_ROWS);
        assertEquals(1, normal.size());
        assertEquals(normal, reduced, "reducedMotion never reduces row count or contents");
        assertTrue(new AccessibilityPolicy(true, false, true).reducedMotion());
    }
}
