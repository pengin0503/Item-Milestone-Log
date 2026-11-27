package com.pengin0503.itemmilestonelog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable snapshot of the twelve client-only display settings that a server/world profile may
 * override (F-150-72 / D-150-206, extended by F-150-80-5). The record exists so later client PRs
 * can read one stable type without mutating {@code Config.SPEC} at runtime; 26.3 uses
 * {@code ModConfig.Type.LOCAL} while the other trees use {@code COMMON}, and this record
 * deliberately depends on neither.
 *
 * <p>Only the twelve keys in {@link #KEYS} may be overridden. Server-authoritative config keys are
 * never included, which keeps the client free of authority and vanilla join compatibility intact.
 */
public record DisplayOptions(
        boolean pinHudEnabled,
        int pinHudMaxRows,
        int tooltipMaxLines,
        boolean tooltipShowTarget,
        boolean tooltipShowRecorded,
        String tooltipSort,
        boolean showRecordClock,
        String chatStyleMode,
        boolean clientEffectEnabled,
        String pinHudAnchor,
        boolean pinHudAutoHide,
        int pinHudScale) {

    /** The twelve profile-overridable display keys, in a fixed order (the original nine first). */
    public static final List<String> KEYS = List.of(
            "pinHudEnabled",
            "pinHudMaxRows",
            "tooltipMaxLines",
            "tooltipShowTarget",
            "tooltipShowRecorded",
            "tooltipSort",
            "showRecordClock",
            "chatStyleMode",
            "clientEffectEnabled",
            "pinHudAnchor",
            "pinHudAutoHide",
            "pinHudScale");

    /** Read-only view of {@link #KEYS} used by the profile store's allow-list. */
    public static final Set<String> KEY_SET = Set.copyOf(KEYS);

    public static final int DEFAULT_PIN_HUD_MAX_ROWS = 5;
    public static final int DEFAULT_TOOLTIP_MAX_LINES = 3;
    /** Config range is 1..10 (F-150-63); the default remains five rows. */
    public static final int MAX_PIN_HUD_ROWS = HudRowPlan.MAX_ROWS;
    public static final int MAX_TOOLTIP_LINES = 12;
    public static final String DEFAULT_PIN_HUD_ANCHOR = "top-left";
    public static final boolean DEFAULT_PIN_HUD_AUTO_HIDE = true;
    public static final int DEFAULT_PIN_HUD_SCALE = 100;

    public DisplayOptions {
        Objects.requireNonNull(tooltipSort, "tooltipSort");
        Objects.requireNonNull(chatStyleMode, "chatStyleMode");
        Objects.requireNonNull(pinHudAnchor, "pinHudAnchor");
        pinHudMaxRows = clamp(pinHudMaxRows, 1, MAX_PIN_HUD_ROWS);
        tooltipMaxLines = clamp(tooltipMaxLines, 1, MAX_TOOLTIP_LINES);
        pinHudScale = HudLayoutOptions.clampScale(pinHudScale);
    }

    /** Returns the global defaults; a connection without a profile uses exactly these values. */
    public static DisplayOptions defaults() {
        return new DisplayOptions(
                false, DEFAULT_PIN_HUD_MAX_ROWS, DEFAULT_TOOLTIP_MAX_LINES,
                true, true, "default", false, "default", false,
                DEFAULT_PIN_HUD_ANCHOR, DEFAULT_PIN_HUD_AUTO_HIDE, DEFAULT_PIN_HUD_SCALE);
    }

    /** Whether {@code key} is one of the twelve display-only keys. */
    public static boolean isDisplayKey(String key) {
        return key != null && KEY_SET.contains(key);
    }

    /**
     * Applies only recognised overrides; unknown keys are ignored and malformed values fall back
     * to the current value. The returned record is a new instance, never a mutation of this one.
     */
    public DisplayOptions apply(Map<String, String> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return this;
        }
        boolean pinHud = bool(overrides, "pinHudEnabled", pinHudEnabled);
        int hudRows = integer(overrides, "pinHudMaxRows", pinHudMaxRows);
        int tooltipLines = integer(overrides, "tooltipMaxLines", tooltipMaxLines);
        boolean showTarget = bool(overrides, "tooltipShowTarget", tooltipShowTarget);
        boolean showRecorded = bool(overrides, "tooltipShowRecorded", tooltipShowRecorded);
        String sort = text(overrides, "tooltipSort", tooltipSort);
        boolean clock = bool(overrides, "showRecordClock", showRecordClock);
        String chatStyle = text(overrides, "chatStyleMode", chatStyleMode);
        boolean effects = bool(overrides, "clientEffectEnabled", clientEffectEnabled);
        String rawAnchor = overrides.get("pinHudAnchor");
        String hudAnchor = rawAnchor == null ? pinHudAnchor
                : HudLayoutOptions.Anchor.fromConfig(rawAnchor).configName();
        boolean hudAutoHide = bool(overrides, "pinHudAutoHide", pinHudAutoHide);
        int hudScale = integer(overrides, "pinHudScale", pinHudScale);
        return new DisplayOptions(pinHud, hudRows, tooltipLines, showTarget, showRecorded,
                sort, clock, chatStyle, effects, hudAnchor, hudAutoHide, hudScale);
    }

    /** Serialises every display key to its string form for saving a per-server profile. */
    public Map<String, String> toOverrides() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("pinHudEnabled", Boolean.toString(pinHudEnabled));
        values.put("pinHudMaxRows", Integer.toString(pinHudMaxRows));
        values.put("tooltipMaxLines", Integer.toString(tooltipMaxLines));
        values.put("tooltipShowTarget", Boolean.toString(tooltipShowTarget));
        values.put("tooltipShowRecorded", Boolean.toString(tooltipShowRecorded));
        values.put("tooltipSort", tooltipSort);
        values.put("showRecordClock", Boolean.toString(showRecordClock));
        values.put("chatStyleMode", chatStyleMode);
        values.put("clientEffectEnabled", Boolean.toString(clientEffectEnabled));
        values.put("pinHudAnchor", pinHudAnchor);
        values.put("pinHudAutoHide", Boolean.toString(pinHudAutoHide));
        values.put("pinHudScale", Integer.toString(pinHudScale));
        return Map.copyOf(values);
    }

    private static boolean bool(Map<String, String> values, String key, boolean fallback) {
        String value = values.get(key);
        if (value == null) {
            return fallback;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        return fallback;
    }

    private static int integer(Map<String, String> values, String key, int fallback) {
        String value = values.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String text(Map<String, String> values, String key, String fallback) {
        String value = values.get(key);
        return value == null || value.length() > 64 ? fallback : value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
