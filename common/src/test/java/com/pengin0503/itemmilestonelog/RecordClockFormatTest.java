package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** F-150-76 (N120): fixed expected values at UTC, Asia/Tokyo and a DST boundary. */
class RecordClockFormatTest {
    @Test
    void formatsHhMmInTheRequestedZone() {
        long epoch = Instant.parse("2026-11-22T13:45:00Z").toEpochMilli();
        assertEquals("13:45", RecordClockFormat.format(epoch, ZoneId.of("UTC")));
        assertEquals("22:45", RecordClockFormat.format(epoch, ZoneId.of("Asia/Tokyo")));
    }

    @Test
    void dstBoundaryUsesTheDisplayZonesOffset() {
        long before = Instant.parse("2026-03-08T06:30:00Z").toEpochMilli();
        long after = Instant.parse("2026-03-08T07:30:00Z").toEpochMilli();
        ZoneId ny = ZoneId.of("America/New_York");
        assertEquals("01:30", RecordClockFormat.format(before, ny));
        assertEquals("03:30", RecordClockFormat.format(after, ny));
    }

    @Test
    void unknownEpochProducesNoClockLine() {
        assertEquals("", RecordClockFormat.format(0L, ZoneId.of("UTC")));
        assertEquals("", RecordClockFormat.format(-1L, ZoneId.of("UTC")));
    }
}
