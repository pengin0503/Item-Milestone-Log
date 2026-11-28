package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-150-69 (N31): at most three particle effects per second, rest silently dropped. */
class EffectRateLimitTest {
    @Test
    void allowsThreeThenDropsUntilTheNextWindow() {
        EffectRateLimit limit = new EffectRateLimit();
        assertTrue(limit.allow(1_000L));
        assertTrue(limit.allow(1_200L));
        assertTrue(limit.allow(1_999L));
        assertFalse(limit.allow(1_999L));
        assertFalse(limit.allow(2_000L - 1L));
        assertTrue(limit.allow(2_000L), "a new one-second window starts exactly at +1000ms");
        assertEquals(1, limit.acceptedInWindow());
    }

    @Test
    void clockMovingBackwardsStartsAFreshWindow() {
        EffectRateLimit limit = new EffectRateLimit();
        assertTrue(limit.allow(5_000L));
        assertTrue(limit.allow(4_999L), "a backwards clock cannot extend the window");
        assertEquals(1, limit.acceptedInWindow());
    }

    @Test
    void logoutClearsTheWindow() {
        EffectRateLimit limit = new EffectRateLimit();
        assertTrue(limit.allow(3_000L));
        assertTrue(limit.allow(3_001L));
        assertTrue(limit.allow(3_002L));
        assertFalse(limit.allow(3_003L));
        limit.clear();
        assertTrue(limit.allow(3_004L));
    }
}
