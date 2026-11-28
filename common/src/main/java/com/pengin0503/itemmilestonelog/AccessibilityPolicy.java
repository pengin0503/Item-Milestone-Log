package com.pengin0503.itemmilestonelog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pure-Java policy for F-150-82 (N195), HUD portion (PR-3): narration of HUD rows, high-contrast
 * HUD colours and reduced motion. The key-conflict warning is computed here without changing any
 * binding (D-150-226). No Minecraft types, no new dependencies, no extra screens or widgets.
 */
public record AccessibilityPolicy(boolean accessibleNarration, boolean hudHighContrast, boolean reducedMotion) {
    public static AccessibilityPolicy defaults() {
        return new AccessibilityPolicy(true, false, false);
    }

    public static AccessibilityPolicy fromConfig(boolean narration, boolean highContrast, boolean reduced) {
        return new AccessibilityPolicy(narration, highContrast, reduced);
    }

    /** One key code assigned to two or more named key mappings. */
    public record Conflict(int keyCode, String first, String second) {
    }

    /**
     * Detects conflicts among bound keys. Unbound keys use {@code InputConstants.UNKNOWN} (-1) and
     * are ignored; key code {@code 0} (left mouse button) is a valid assignment and is included.
     * The game never changes a player's assignment automatically.
     */
    public static List<Conflict> detectConflicts(Map<String, Integer> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return List.of();
        }
        Map<Integer, List<String>> byCode = new TreeMap<>();
        for (Map.Entry<String, Integer> entry : bindings.entrySet()) {
            Integer code = entry.getValue();
            if (code == null || code < 0 || entry.getKey() == null) {
                continue;
            }
            byCode.computeIfAbsent(code, ignored -> new ArrayList<>()).add(entry.getKey());
        }
        List<Conflict> conflicts = new ArrayList<>();
        for (Map.Entry<Integer, List<String>> entry : byCode.entrySet()) {
            List<String> names = new ArrayList<>(entry.getValue());
            names.sort(String::compareTo);
            for (int i = 1; i < names.size(); i++) {
                conflicts.add(new Conflict(entry.getKey(), names.get(0), names.get(i)));
            }
        }
        return List.copyOf(conflicts);
    }
}
