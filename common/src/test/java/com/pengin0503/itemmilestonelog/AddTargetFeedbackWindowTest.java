package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F41: only the first feedback-carrying sync within two seconds is shown. */
class AddTargetFeedbackWindowTest {
    @Test
    void ignoresSyncsBeforeAnyActionWasSent() {
        AddTargetFeedbackWindow window = new AddTargetFeedbackWindow();
        assertFalse(window.consume(1000L, true));
        assertFalse(window.consume(1000L, false));
    }

    @Test
    void consumesFirstFeedbackWithinWindowOnly() {
        AddTargetFeedbackWindow window = new AddTargetFeedbackWindow();
        window.markSent(10_000L);
        assertTrue(window.isPending());

        assertTrue(window.consume(10_500L, true));
        assertFalse(window.isPending());
        assertFalse(window.consume(10_600L, true));
    }

    @Test
    void syncWithoutFeedbackDoesNotConsumeTheWindow() {
        AddTargetFeedbackWindow window = new AddTargetFeedbackWindow();
        window.markSent(10_000L);

        assertFalse(window.consume(10_100L, false));
        assertTrue(window.isPending());
        assertTrue(window.consume(10_200L, true));
    }

    @Test
    void windowIsInclusiveAtExactlyTwoSeconds() {
        AddTargetFeedbackWindow window = new AddTargetFeedbackWindow();
        window.markSent(10_000L);
        assertTrue(window.consume(12_000L, true));
    }

    @Test
    void staleFeedbackIsDroppedAndClearsPendingState() {
        AddTargetFeedbackWindow window = new AddTargetFeedbackWindow();
        window.markSent(10_000L);

        assertFalse(window.consume(12_001L, true));
        assertFalse(window.isPending());
        assertFalse(window.consume(12_002L, true));
    }

    @Test
    void staleSyncWithoutFeedbackAlsoClearsPendingState() {
        AddTargetFeedbackWindow window = new AddTargetFeedbackWindow();
        window.markSent(10_000L);

        assertFalse(window.consume(12_500L, false));
        assertFalse(window.isPending());
        assertFalse(window.consume(12_501L, true));
    }

    @Test
    void markSentRestartsTheWindow() {
        AddTargetFeedbackWindow window = new AddTargetFeedbackWindow();
        window.markSent(10_000L);
        window.consume(10_100L, true);

        window.markSent(20_000L);
        assertEquals(2000L, AddTargetFeedbackWindow.WINDOW_MILLIS);
        assertTrue(window.consume(21_999L, true));
        assertFalse(window.isPending());
    }

    @Test
    void clearForgetsPendingAction() {
        AddTargetFeedbackWindow window = new AddTargetFeedbackWindow();
        window.markSent(10_000L);
        window.clear();

        assertFalse(window.isPending());
        assertFalse(window.consume(10_100L, true));
    }

    @Test
    void backwardsClockDoesNotShowStaleFeedback() {
        AddTargetFeedbackWindow window = new AddTargetFeedbackWindow();
        window.markSent(10_000L);

        assertFalse(window.consume(9_999L, true));
        assertFalse(window.isPending());
    }
}
