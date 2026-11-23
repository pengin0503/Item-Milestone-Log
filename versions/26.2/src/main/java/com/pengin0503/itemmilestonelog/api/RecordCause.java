package com.pengin0503.itemmilestonelog.api;

/**
 * Indicates how a milestone record was confirmed when {@link MilestoneRecordedEvent} is posted.
 */
public enum RecordCause {
    /** Confirmed during normal gameplay (inventory scan, item pickup, or craft/smelt verification). */
    GAMEPLAY,
    /** Confirmed programmatically via {@link ItemMilestoneLogApi#recordNow}. */
    API,
    /** Granted by a server administrator via {@code /milestonelog admin <player> grant}. */
    ADMIN_GRANT
}
