package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-150-77: only all-unbound keys trigger the hint, after 2 seconds, and only once. */
class UnboundKeyHintTest {
    private static UnboundKeyHintPolicy.Keys keys(boolean open, boolean hover, boolean mute, boolean copy) {
        return new UnboundKeyHintPolicy.Keys(open, hover, mute, copy);
    }

    @Test
    void allSixteenKeyCombinationsOnlyTriggerWhenEverythingIsUnbound() {
        UnboundKeyHintPolicy.Keys allUnbound = keys(true, true, true, true);
        int allUnboundCount = 0;
        for (int mask = 0; mask < 16; mask++) {
            UnboundKeyHintPolicy.Keys keys = keys(
                    (mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0);
            assertEquals(mask == 15, keys.allUnbound(), "unbound mask " + mask);
            UnboundKeyHintPolicy policy = new UnboundKeyHintPolicy();
            policy.schedule(1_000L);
            if (policy.shouldShow(5_000L, keys, false)) {
                allUnboundCount++;
                assertEquals(allUnbound, keys);
            }
        }
        assertEquals(1, allUnboundCount);
    }

    @Test
    void hintWaitsForTheTwoSecondWindowAndIsShownOnlyOnce() {
        UnboundKeyHintPolicy policy = new UnboundKeyHintPolicy();
        UnboundKeyHintPolicy.Keys allUnbound = keys(true, true, true, true);
        assertFalse(policy.shouldShow(0L, allUnbound, false), "nothing scheduled yet");
        policy.schedule(1_000L);
        assertFalse(policy.shouldShow(2_999L, allUnbound, false));
        assertTrue(policy.shouldShow(3_000L, allUnbound, false));
        policy.markShown();
        assertFalse(policy.shouldShow(9_000L, allUnbound, false));
        assertEquals(AddTargetFeedbackWindow.WINDOW_MILLIS, UnboundKeyHintPolicy.DELAY_MILLIS);
        assertEquals(2_000L, UnboundKeyHintPolicy.DELAY_MILLIS);
    }

    @Test
    void storedFlagOrLogoutSuppressesTheHint() {
        UnboundKeyHintPolicy policy = new UnboundKeyHintPolicy();
        UnboundKeyHintPolicy.Keys allUnbound = keys(true, true, true, true);
        policy.schedule(0L);
        assertFalse(policy.shouldShow(5_000L, allUnbound, true), "prefs.json already says shown");
        policy.clear();
        assertFalse(policy.isPending());
        assertFalse(policy.shouldShow(5_000L, allUnbound, false));
        policy.schedule(0L);
        assertFalse(policy.shouldShow(1_500L, keys(true, true, true, false), false));
        assertTrue(policy.shouldShow(5_000L, allUnbound, false));
    }
}
