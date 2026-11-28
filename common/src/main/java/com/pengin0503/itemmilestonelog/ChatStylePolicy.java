package com.pengin0503.itemmilestonelog;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * F-150-74 (N72): pure-Java decision layer for client-side chat styling.
 *
 * <p>Only chat components carrying this mod's own translation keys are eligible; every other
 * message (other mods, vanilla, player-entered pin lists) is left byte-for-byte untouched.
 * The version layer owns the actual {@code Component} traversal and style application, because
 * those APIs differ across the five trees. No hex colours are introduced: the colour names below
 * map to existing {@code ChatFormatting} constants only.
 */
public final class ChatStylePolicy {
    public static final String MESSAGE_PREFIX = "message.itemmilestonelog.";
    public static final String GUI_PREFIX = "gui.itemmilestonelog.";

    /** Lines that embed player-entered text (F-150-31 pin lists) are never restyled. */
    private static final Set<String> EXCLUDED_KEYS = Set.of(
            "message.itemmilestonelog.pinned_header",
            "message.itemmilestonelog.pinned_entry",
            "message.itemmilestonelog.pinned_empty",
            "command.itemmilestonelog.imlclient.pin.list",
            "command.itemmilestonelog.imlclient.pin.list_empty");

    public enum Mode {
        DEFAULT,
        SUBTLE,
        BOLD,
        HIGH_CONTRAST;

        /** Unknown or missing values keep the shipped appearance (D-150-209). */
        public static Mode fromConfig(String value) {
            if (value == null) {
                return DEFAULT;
            }
            String normalized = value.trim().replace('-', '_');
            for (Mode mode : values()) {
                if (mode.name().equalsIgnoreCase(normalized)) {
                    return mode;
                }
            }
            return DEFAULT;
        }
    }

    /** {@code color} is a {@code ChatFormatting} name, or empty when the style must not change. */
    public record Style(boolean bold, String color) {
    }

    private static final Style UNCHANGED = new Style(false, "");

    private ChatStylePolicy() {
    }

    public static boolean isOwnKey(String key) {
        return key != null && (key.startsWith(MESSAGE_PREFIX) || key.startsWith(GUI_PREFIX));
    }

    /**
     * A component is stylable only when at least one of its translation keys belongs to this mod
     * and none of them is a player-text line. An empty/unknown key list is never stylable.
     */
    public static boolean isStylable(List<String> translationKeys) {
        if (translationKeys == null || translationKeys.isEmpty()) {
            return false;
        }
        boolean own = false;
        for (String key : translationKeys) {
            if (key == null) {
                continue;
            }
            if (EXCLUDED_KEYS.contains(key)) {
                return false;
            }
            if (isOwnKey(key)) {
                own = true;
            }
        }
        return own;
    }

    /** The four fixed modes; {@code DEFAULT} is exactly the pre-existing style. */
    public static Style styleFor(Mode mode) {
        if (mode == null) {
            return UNCHANGED;
        }
        return switch (mode) {
            case DEFAULT -> UNCHANGED;
            case SUBTLE -> new Style(false, "GRAY");
            case BOLD -> new Style(true, "GOLD");
            case HIGH_CONTRAST -> new Style(true, "WHITE");
        };
    }

    /** Internal values stay lower-case ASCII with a hyphen in {@code Config.SPEC}. */
    public static String configName(Mode mode) {
        return (mode == null ? Mode.DEFAULT : mode).name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static boolean isValidConfigValue(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().replace('-', '_');
        for (Mode mode : Mode.values()) {
            if (mode.name().equalsIgnoreCase(normalized)) {
                return true;
            }
        }
        return false;
    }

    /** Config specs validate {@code Object}; keep the typed helper for callers and tests. */
    public static boolean isValidConfigValue(Object value) {
        return value instanceof String text && isValidConfigValue(text);
    }
}
