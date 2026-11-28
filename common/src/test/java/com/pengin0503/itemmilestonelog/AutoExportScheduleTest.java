package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AutoExportScheduleTest {
    private static final long START = 1_700_000_000_000L;

    @Test
    void firstThrottledTickOfASessionCollectsWithoutAStoredTimestamp() {
        assertEquals(AutoExportSchedule.Decision.COLLECT, AutoExportSchedule.decide(START, 0L, 24));
    }

    @Test
    void fewerThanIntervalHoursIsSkippedAndTheBoundaryCollects() {
        long last = START - 100L;
        assertEquals(AutoExportSchedule.Decision.SKIP,
                AutoExportSchedule.decide(last + 24L * 3_600_000L - 1L, last, 24));
        assertEquals(AutoExportSchedule.Decision.COLLECT,
                AutoExportSchedule.decide(last + 24L * 3_600_000L, last, 24));
        assertEquals(AutoExportSchedule.Decision.SKIP,
                AutoExportSchedule.decide(last + 23L * 3_600_000L, last, 24));
    }

    @Test
    void intervalIsClampedToOneThroughOneHundredSixtyEight() {
        assertEquals(1, AutoExportSchedule.clampIntervalHours(0));
        assertEquals(168, AutoExportSchedule.clampIntervalHours(9999));
        assertEquals(24, AutoExportSchedule.DEFAULT_INTERVAL_HOURS);
        assertEquals(AutoExportSchedule.Decision.COLLECT,
                AutoExportSchedule.decide(START + 3_600_000L, START, 0));
        assertEquals(AutoExportSchedule.Decision.SKIP,
                AutoExportSchedule.decide(START + 3_600_000L - 1L, START, 0));
    }

    @Test
    void throttleFiresOncePerConfiguredTickWindowAndResets() {
        AutoExportSchedule.Throttle throttle = new AutoExportSchedule.Throttle(3);
        assertEquals(false, throttle.tick());
        assertEquals(false, throttle.tick());
        assertEquals(true, throttle.tick());
        assertEquals(false, throttle.tick());
        throttle.reset();
        assertEquals(false, throttle.tick());
        assertEquals(false, throttle.tick());
        assertEquals(true, throttle.tick());
    }
}
