package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
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
        assertEquals("0h 01m 30s", Playtime.formatRecord(MilestoneRecord.legacy(ticks), ZoneId.of("Asia/Tokyo")));
    }

    @Test
    void formatsDateAndRecordInSpecifiedTimeZoneWhileGuiOverloadUsesSystemDefault() {
        TimeZone originalTz = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC));
            int ticks = (27 * 3600 + 15 * 60 + 30) * 20;
            // 2026-11-25T20:00:00Z is 2026-11-25 in UTC / -05:00, and 2026-11-26 in Asia/Tokyo (+09:00).
            long lateUtc = Instant.parse("2026-11-25T20:00:00Z").toEpochMilli();
            MilestoneRecord lateRec = new MilestoneRecord(ticks, 11L, lateUtc);

            assertEquals("2026-11-25", Playtime.formatDate(lateUtc, ZoneOffset.UTC));
            assertEquals("2026-11-26", Playtime.formatDate(lateUtc, ZoneId.of("Asia/Tokyo")));
            assertEquals("2026-11-26", Playtime.formatDate(lateUtc, ZoneId.of("+09:00")));
            assertEquals("2026-11-25", Playtime.formatDate(lateUtc, ZoneId.of("-05:00")));
            assertEquals("2026-11-25", Playtime.formatDate(lateUtc, null));

            assertEquals("27h 15m 30s (Day 12, 2026-11-25)", Playtime.formatRecord(lateRec, ZoneOffset.UTC));
            assertEquals("27h 15m 30s (Day 12, 2026-11-26)", Playtime.formatRecord(lateRec, ZoneId.of("Asia/Tokyo")));
            assertEquals("27h 15m 30s (Day 12, 2026-11-26)", Playtime.formatRecord(lateRec, ZoneId.of("+09:00")));

            // 2026-11-25T02:00:00Z is 2026-11-24 in -05:00 and 2026-11-25 in UTC.
            long earlyUtc = Instant.parse("2026-11-25T02:00:00Z").toEpochMilli();
            assertEquals("2026-11-24", Playtime.formatDate(earlyUtc, ZoneId.of("-05:00")));
            assertEquals("2026-11-25", Playtime.formatDate(earlyUtc, ZoneOffset.UTC));

            // No-zone GUI overload continues to reflect ZoneId.systemDefault().
            assertEquals("2026-11-25", Playtime.formatDate(lateUtc));
            assertEquals("27h 15m 30s (Day 12, 2026-11-25)", Playtime.formatRecord(lateRec));
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            assertEquals("2026-11-26", Playtime.formatDate(lateUtc));
            assertEquals("27h 15m 30s (Day 12, 2026-11-26)", Playtime.formatRecord(lateRec));
        } finally {
            TimeZone.setDefault(originalTz);
        }
    }
}
