package com.pengin0503.itemmilestonelog.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionRowLayoutTest {
    // MilestoneScreen: rows are at most 500 px wide, 4 px apart; 2 add buttons + 2 presets.
    private static final int ROW_WIDTH = 500;
    private static final int GAP = 4;
    private static final int BUTTONS = 4;

    @Test
    void splitsEqualWidthBetweenButtons() {
        assertEquals(122, ActionRowLayout.buttonWidth(ROW_WIDTH, BUTTONS, GAP));
        assertEquals(248, ActionRowLayout.buttonWidth(ROW_WIDTH, 2, GAP));
    }

    @Test
    void wholeRowForNoButtons() {
        assertEquals(ROW_WIDTH, ActionRowLayout.buttonWidth(ROW_WIDTH, 0, GAP));
        assertFalse(ActionRowLayout.needsSplit(ROW_WIDTH, 0, GAP, 0));
    }

    @Test
    void splitsWhenJapaneseLabelIsWiderThanTheButton() {
        // Measured in game: "手に持っているアイテムを追加" is about 126 px wide and
        // "プリセット: Vanilla Endgame" about 129 px. The old fixed rule (split only below
        // 120 px) kept 122 px buttons in one row, and both labels scrolled.
        assertTrue(ActionRowLayout.needsSplit(ROW_WIDTH, BUTTONS, GAP, 126));
        assertTrue(ActionRowLayout.needsSplit(ROW_WIDTH, BUTTONS, GAP, 129));
    }

    @Test
    void splitsWhenLabelWouldTouchTheBorder() {
        // "Preset: Vanilla Endgame" is 118 px: it fits 122 px buttons only with no clear space.
        assertTrue(ActionRowLayout.needsSplit(ROW_WIDTH, BUTTONS, GAP, 118));
    }

    @Test
    void keepsOneRowWhenLabelsFitWithPadding() {
        assertFalse(ActionRowLayout.needsSplit(ROW_WIDTH, BUTTONS, GAP, 80));
        assertFalse(ActionRowLayout.needsSplit(ROW_WIDTH, BUTTONS, GAP, 122 - ActionRowLayout.LABEL_PADDING));
        assertTrue(ActionRowLayout.needsSplit(ROW_WIDTH, BUTTONS, GAP, 122 - ActionRowLayout.LABEL_PADDING + 1));
    }

    @Test
    void splitRowsFitTheLongestLabelsOnTheNarrowestScreen() {
        // GUI scale "auto" keeps the scaled width at 320 px or more: rows are 320 - 16 = 304 px.
        int narrowRow = 320 - 16;
        assertFalse(ActionRowLayout.needsSplit(narrowRow, 2, GAP, 129));
    }
}
