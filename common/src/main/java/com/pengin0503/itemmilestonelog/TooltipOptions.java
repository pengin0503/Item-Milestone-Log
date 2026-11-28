package com.pengin0503.itemmilestonelog;

import java.util.Locale;

/**
 * F-150-68 / F-150-79: client-local options for the F42 item-tooltip status lines.
 *
 * <p>Pure Java: no Minecraft or NeoForge types, so the same options (and the same precedence)
 * are unit-testable in every tree. {@link #DEFAULT} reproduces the shipped F42 output exactly;
 * the config accessors in the version trees only translate their persisted strings into these
 * values. Setting order is fixed here: max-lines, target, recorded, sort (N28), then colour and
 * tag names (N192) — the same order in which the config keys were appended.
 */
public record TooltipOptions(
        int maxLines,
        boolean showTarget,
        boolean showRecorded,
        Sort sort,
        StatusColor statusColor,
        boolean tagNames) {

    public static final int MIN_MAX_LINES = 1;
    public static final int MAX_MAX_LINES = 10;
    public static final TooltipOptions DEFAULT = new TooltipOptions(
            3, true, true, Sort.DEFAULT, StatusColor.DEFAULT, true);

    /** Sort direction. {@code DEFAULT} keeps the existing (target-key) order untouched. */
    public enum Sort {
        DEFAULT, TARGET_FIRST, RECORDED_FIRST
    }

    /** Status-line colour. {@code DEFAULT} is the existing {@code ChatFormatting.GRAY}. */
    public enum StatusColor {
        DEFAULT, GRAY, WHITE, GOLD
    }

    public TooltipOptions {
        maxLines = Math.max(MIN_MAX_LINES, Math.min(MAX_MAX_LINES, maxLines));
        sort = sort == null ? Sort.DEFAULT : sort;
        statusColor = statusColor == null ? StatusColor.DEFAULT : statusColor;
    }

    /**
     * F-150-68/72/75: takes the four tooltip override keys from the effective per-server
     * {@link DisplayOptions} and keeps the two non-profile keys (status colour and tag names)
     * from the global config snapshot. No new config key or settings class is introduced.
     */
    public static TooltipOptions fromDisplayOptions(DisplayOptions effective, TooltipOptions base) {
        if (effective == null || base == null) {
            return base == null ? DEFAULT : base;
        }
        return new TooltipOptions(
                effective.tooltipMaxLines(), effective.tooltipShowTarget(),
                effective.tooltipShowRecorded(), parseSort(effective.tooltipSort()),
                base.statusColor(), base.tagNames());
    }

    /**
     * Lenient conversion from the persisted config strings. Unknown values fall back to the
     * shipped defaults instead of throwing during client startup.
     */
    public static TooltipOptions of(int maxLines, boolean showTarget, boolean showRecorded,
                                    String sort, String statusColor, boolean tagNames) {
        return new TooltipOptions(maxLines, showTarget, showRecorded,
                parseSort(sort), parseColor(statusColor), tagNames);
    }

    public static Sort parseSort(String raw) {
        if (raw != null) {
            try {
                return Sort.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException ignored) {
                // Fall through to the shipped ordering.
            }
        }
        return Sort.DEFAULT;
    }

    public static StatusColor parseColor(String raw) {
        if (raw != null) {
            try {
                return StatusColor.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Fall through to the shipped grey.
            }
        }
        return StatusColor.DEFAULT;
    }

    /** Validates a config string before it is persisted (mirrors the lenient parser). */
    public static boolean isValidSort(Object value) {
        return value instanceof String s && matches(Sort.values(), s);
    }

    /** Validates a config string before it is persisted (mirrors the lenient parser). */
    public static boolean isValidColor(Object value) {
        return value instanceof String s && matches(StatusColor.values(), s);
    }

    private static boolean matches(Enum<?>[] values, String raw) {
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (Enum<?> value : values) {
            if (value.name().equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "TooltipOptions[maxLines=" + maxLines + ", showTarget=" + showTarget
                + ", showRecorded=" + showRecorded + ", sort=" + sort
                + ", statusColor=" + statusColor + ", tagNames=" + tagNames + "]";
    }
}
