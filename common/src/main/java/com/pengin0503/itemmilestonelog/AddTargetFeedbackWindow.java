package com.pengin0503.itemmilestonelog;

/**
 * Tracks the short window after the client sends an F41 "add hovered item" action.
 *
 * <p>The server answers every {@code ADD_TARGET} action with a milestone sync, but the
 * client normally drops syncs while no mod screen is open. To show the server's feedback
 * in the action bar only for the action the player just triggered, the client marks this
 * window when it sends the action and consumes the first sync that carries feedback within
 * {@value #WINDOW_MILLIS} milliseconds.
 *
 * <p>Syncs without feedback (for example the periodic record-triggered syncs) never
 * display and do not consume the window, so a record sync racing ahead of the answer
 * cannot swallow the feedback.
 *
 * <p>Pure Java: shared by every Minecraft version via {@code common/src/main/java}.
 */
public final class AddTargetFeedbackWindow {
    /** How long after sending {@code ADD_TARGET} feedback is considered the reply. */
    public static final long WINDOW_MILLIS = 2000L;

    private boolean pending;
    private long sentAtMillis;

    /** Marks that an {@code ADD_TARGET} action was just sent at {@code nowMillis}. */
    public void markSent(long nowMillis) {
        this.pending = true;
        this.sentAtMillis = nowMillis;
    }

    /** Forgets any pending action, for example when the player logs out. */
    public void clear() {
        this.pending = false;
    }

    /** Whether an action is still waiting for its feedback sync. */
    public boolean isPending() {
        return pending;
    }

    /**
     * Decides whether a received sync should show its feedback in the action bar.
     *
     * <p>Returns {@code true} at most once per {@link #markSent(long)} call, and only when
     * the sync carries feedback and arrives within {@link #WINDOW_MILLIS} milliseconds.
     * A sync without feedback returns {@code false} without consuming the window.
     * A sync that arrives after the window clears the stale pending state.
     *
     * @param nowMillis   current wall-clock time on the client thread
     * @param hasFeedback whether the received sync payload carries feedback
     * @return {@code true} when the caller should display the payload's feedback
     */
    public boolean consume(long nowMillis, boolean hasFeedback) {
        if (!pending) {
            return false;
        }
        long elapsed = nowMillis - sentAtMillis;
        if (elapsed < 0L || elapsed > WINDOW_MILLIS) {
            // Clock moved backwards or the reply took too long: this is no longer the
            // answer to the action the player just triggered.
            pending = false;
            return false;
        }
        if (!hasFeedback) {
            return false;
        }
        pending = false;
        return true;
    }
}
