package com.pengin0503.itemmilestonelog;

/**
 * F-150-69 (N31): fixed-window limit for incoming client effects (at most three per second).
 *
 * <p>Pure Java with an injectable clock so the boundary is unit-testable. Effects that do not fit
 * are dropped without a message, a new screen or a C2S rate-limit token (D-150-200).
 */
public final class EffectRateLimit {
    public static final int MAX_PER_WINDOW = 3;
    public static final long WINDOW_MILLIS = 1_000L;

    private long windowStartMillis = Long.MIN_VALUE;
    private int accepted;

    /** Returns {@code true} when the effect at {@code nowMillis} may be played. */
    public boolean allow(long nowMillis) {
        if (accepted == 0 || nowMillis < windowStartMillis
                || nowMillis - windowStartMillis >= WINDOW_MILLIS) {
            windowStartMillis = nowMillis;
            accepted = 0;
        }
        if (accepted >= MAX_PER_WINDOW) {
            return false;
        }
        accepted++;
        return true;
    }

    /** Forgets the current window on logout. */
    public void clear() {
        windowStartMillis = Long.MIN_VALUE;
        accepted = 0;
    }

    public int acceptedInWindow() {
        return accepted;
    }
}
