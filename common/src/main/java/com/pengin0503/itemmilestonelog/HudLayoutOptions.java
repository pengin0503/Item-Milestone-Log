package com.pengin0503.itemmilestonelog;

import java.util.Locale;
import java.util.Objects;

/**
 * Pure-Java options for F-150-80 (N193): the position, automatic retreat and scale of the new
 * independent HUD overlay. Only the overlay itself is affected — none of the five frozen screens
 * or their widgets are touched (FL-3). Kept free of Minecraft types for the five-tree test suite.
 */
public record HudLayoutOptions(Anchor anchor, boolean autoHide, int scale) {
    /** Anchors are appended to Config.SPEC as lower-case hyphenated strings. */
    public enum Anchor {
        TOP_LEFT("top-left"),
        TOP_RIGHT("top-right"),
        BOTTOM_LEFT("bottom-left"),
        BOTTOM_RIGHT("bottom-right");

        private final String configName;

        Anchor(String configName) {
            this.configName = configName;
        }

        public String configName() {
            return configName;
        }

        /** Unknown config values fall back to {@link #TOP_LEFT}. */
        public static Anchor fromConfig(String value) {
            if (value != null) {
                for (Anchor candidate : values()) {
                    if (candidate.configName.equalsIgnoreCase(value.trim())) {
                        return candidate;
                    }
                }
            }
            return TOP_LEFT;
        }
    }

    public static final int MIN_SCALE = 50;
    public static final int MAX_SCALE = 200;
    public static final int DEFAULT_SCALE = 100;
    /** F-150-80-3: fade/retreat window. */
    public static final long AUTO_HIDE_MILLIS = 3000L;
    /** Margin from the screen edge, in unscaled pixels. */
    public static final int MARGIN = 8;

    public HudLayoutOptions {
        anchor = anchor == null ? Anchor.TOP_LEFT : anchor;
        scale = clampScale(scale);
    }

    public static HudLayoutOptions defaults() {
        return new HudLayoutOptions(Anchor.TOP_LEFT, true, DEFAULT_SCALE);
    }

    public static HudLayoutOptions fromConfig(String anchor, boolean autoHide, int scale) {
        return new HudLayoutOptions(Anchor.fromConfig(anchor), autoHide, scale);
    }

    public static int clampScale(int value) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
    }

    /** Scale a HUD measurement (row height, font matrix) as a percentage; widgets are not scaled. */
    public int scaled(int value) {
        return value * scale / DEFAULT_SCALE;
    }

    /** Top-left corner of the overlay box for one of the four supported anchors. */
    public Position position(int screenWidth, int screenHeight, int hudWidth, int hudHeight) {
        int x = switch (anchor) {
            case TOP_RIGHT, BOTTOM_RIGHT -> screenWidth - MARGIN - hudWidth;
            default -> MARGIN;
        };
        int y = switch (anchor) {
            case BOTTOM_LEFT, BOTTOM_RIGHT -> screenHeight - MARGIN - hudHeight;
            default -> MARGIN;
        };
        return new Position(Math.max(0, x), Math.max(0, y));
    }

    /**
     * F-150-80-3: damage, chat/screen input or F1 starts a three-second retreat while auto-hide
     * is on. {@code triggeredAtMillis == 0} means no retreat is active; the caller records the
     * timestamp of the most recent trigger. Rows themselves are never removed (F-150-82-3).
     */
    public boolean retreating(long nowMillis, long triggeredAtMillis) {
        return autoHide && triggeredAtMillis > 0 && nowMillis >= triggeredAtMillis
                && nowMillis - triggeredAtMillis < AUTO_HIDE_MILLIS;
    }

    /**
     * Draw alpha for the retreat window (255 = fully visible). Reduced motion skips the fade and
     * hides immediately, as F-150-82-3 requires. No new alpha API is introduced.
     */
    public int fadeAlpha(long nowMillis, long triggeredAtMillis, boolean reducedMotion) {
        if (!retreating(nowMillis, triggeredAtMillis)) {
            return 255;
        }
        if (reducedMotion) {
            return 0;
        }
        long elapsed = nowMillis - triggeredAtMillis;
        return 255 - (int) (255L * elapsed / AUTO_HIDE_MILLIS);
    }

    public record Position(int x, int y) {
    }

    /** Lower-case config value for this anchor, for profile round-trips. */
    public String anchorConfigName() {
        return anchor.configName().toLowerCase(Locale.ROOT);
    }
}
