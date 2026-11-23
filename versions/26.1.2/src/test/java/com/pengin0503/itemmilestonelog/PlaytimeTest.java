package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaytimeTest {
    @Test
    void formatsZeroAndNegativeTicks() {
        assertEquals("0h 00m 00s", Playtime.format(0));
        assertEquals("0h 00m 00s", Playtime.format(-1));
        assertEquals("0h 00m 00s", Playtime.format(-1000));
        assertEquals("0h 00m 00s", Playtime.format(Integer.MIN_VALUE));
    }

    @Test
    void formatsMinutesAndSeconds() {
        assertEquals("0h 01m 30s", Playtime.format(90 * 20));
    }

    @Test
    void formatsHoursBeyond24WithoutWrapping() {
        int ticks = (27 * 3600 + 15 * 60 + 30) * 20;
        assertEquals("27h 15m 30s", Playtime.format(ticks));
    }

    @Test
    void formatsTripleDigitHours() {
        int ticks = (120 * 3600 + 5 * 60 + 9) * 20;
        assertEquals("120h 05m 09s", Playtime.format(ticks));
    }

    @Test
    void formatsRecordWithDayAndDateDeterministically() {
        TimeZone originalTz = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC));
            int ticks = (27 * 3600 + 15 * 60 + 30) * 20;
            // 1795600000000L in UTC is 2026-11-25T09:46:40Z -> 2026-11-25
            assertEquals("27h 15m 30s (Day 12, 2026-11-25)",
                    Playtime.formatRecord(new MilestoneRecord(ticks, 11L, 1795600000000L)));
        } finally {
            TimeZone.setDefault(originalTz);
        }
    }

    @Test
    void omitsUnknownDayAndDateFromRecord() {
        int ticks = 90 * 20;
        assertEquals("0h 01m 30s", Playtime.formatRecord(MilestoneRecord.legacy(ticks)));
        assertEquals("0h 01m 30s (Day 1)", Playtime.formatRecord(new MilestoneRecord(ticks, 0L, 0L)));
    }
}
