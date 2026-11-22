package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaytimeTest {
    @Test
    void formatsZeroTicks() {
        assertEquals("0h 00m 00s", Playtime.format(0));
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
    void formatsRecordWithDayAndDate() {
        int ticks = (27 * 3600 + 15 * 60 + 30) * 20;
        long epoch = 1795600000000L;
        String expectedDate = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(epoch));
        assertEquals("27h 15m 30s (Day 12, " + expectedDate + ")",
                Playtime.formatRecord(new MilestoneRecord(ticks, 11L, epoch)));
    }

    @Test
    void omitsUnknownDayAndDateFromRecord() {
        int ticks = 90 * 20;
        assertEquals("0h 01m 30s", Playtime.formatRecord(MilestoneRecord.legacy(ticks)));
        assertEquals("0h 01m 30s (Day 1)", Playtime.formatRecord(new MilestoneRecord(ticks, 0L, 0L)));
    }
}
