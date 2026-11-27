package com.pengin0503.itemmilestonelog;

/**
 * F-150-77 (N122, guidance part): one-time, delayed hint when every client key is unbound.
 *
 * <p>The server's existing {@code join_hint_mod} / {@code join_hint_vanilla} messages stay
 * untouched. This client-only supplement is shown only when all keys are still
 * {@code InputConstants.UNKNOWN}, is delayed by {@link #DELAY_MILLIS} so it does not race the
 * server hint, and is recorded in {@code prefs.json} so it never appears twice (D-150-215/216).
 *
 * <p>Pure Java so all five version trees share the timing and once-only rules.
 */
public final class UnboundKeyHintPolicy {
    /** Same window as the F41 feedback window, as prescribed by F-150-77. */
    public static final long DELAY_MILLIS = AddTargetFeedbackWindow.WINDOW_MILLIS;

    /** The three shipped keys plus the copy key owned by a later client PR (absent = unbound). */
    public record Keys(boolean openGuiUnbound, boolean addHoveredUnbound,
                       boolean muteUnbound, boolean copyRecentUnbound) {
        public boolean allUnbound() {
            return openGuiUnbound && addHoveredUnbound && muteUnbound && copyRecentUnbound;
        }
    }

    private boolean pending;
    private boolean shownThisSession;
    private long scheduledAtMillis;

    /** Schedules the hint on login. Called once per connection, even when it will not be shown. */
    public void schedule(long nowMillis) {
        pending = true;
        scheduledAtMillis = nowMillis;
    }

    /** Forgets a pending hint on logout; the stored once-only flag is untouched. */
    public void clear() {
        pending = false;
        scheduledAtMillis = 0L;
    }

    public boolean isPending() {
        return pending;
    }

    /**
     * Whether the action bar should now show the hint. Requires a pending login, all four keys
     * unbound, no stored flag, no hint already shown this session, and the 2 second delay.
     */
    public boolean shouldShow(long nowMillis, Keys keys, boolean storedShown) {
        if (!pending || shownThisSession || storedShown || keys == null || !keys.allUnbound()) {
            return false;
        }
        long elapsed = nowMillis - scheduledAtMillis;
        return elapsed >= 0L && elapsed >= DELAY_MILLIS;
    }

    /** Consumes the hint; callers also persist the once-only flag in {@code prefs.json}. */
    public void markShown() {
        pending = false;
        shownThisSession = true;
    }
}
