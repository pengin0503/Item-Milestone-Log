package com.pengin0503.itemmilestonelog.client;

/**
 * Layout rule for the action buttons at the bottom of {@link MilestoneScreen}.
 *
 * The buttons share one row of equal-width buttons when every label fits; otherwise
 * the add buttons and the preset buttons get a row each. The decision uses the real
 * width of the translated labels, so a long translation never ends up as a label that
 * scrolls inside a too-narrow button.
 *
 * Kept free of Minecraft classes so that it can be unit tested.
 */
public final class ActionRowLayout {
    /**
     * Horizontal space a button needs besides its label: vanilla insets button text by
     * 2 px on each side, plus 2 px of clear space on each side so the text does not
     * touch the button border.
     */
    public static final int LABEL_PADDING = 8;

    private ActionRowLayout() {
    }

    /** Width of each of {@code count} equal buttons sharing {@code rowWidth}, with {@code gap} between them. */
    public static int buttonWidth(int rowWidth, int count, int gap) {
        if (count <= 0) {
            return rowWidth;
        }
        return (rowWidth - gap * (count - 1)) / count;
    }

    /**
     * Whether the widest of {@code count} labels does not fit in a single row of
     * equal-width buttons, so the buttons have to be split over two rows.
     */
    public static boolean needsSplit(int rowWidth, int count, int gap, int widestLabelWidth) {
        return widestLabelWidth + LABEL_PADDING > buttonWidth(rowWidth, count, gap);
    }
}
