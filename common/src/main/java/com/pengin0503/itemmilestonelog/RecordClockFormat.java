package com.pengin0503.itemmilestonelog;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * F-150-76 (N120): the single HH:mm formatter for record rows.
 *
 * <p>The timestamp is always interpreted at display time in the current local zone; no server
 * timezone is transmitted and no other date formatter is created (D-150-213 / D-150-214).
 * Records with an unknown epoch return an empty string, so the existing day-only display survives.
 */
public final class RecordClockFormat {
    public static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private RecordClockFormat() {
    }

    /** Formats a record timestamp as {@code HH:mm}, or {@code ""} when the epoch is unknown. */
    public static String format(long epochMillis, ZoneId zone) {
        if (epochMillis <= 0L) {
            return "";
        }
        ZoneId effective = zone != null ? zone : ZoneId.systemDefault();
        return FORMATTER.withZone(effective).format(Instant.ofEpochMilli(epochMillis));
    }

    public static String format(long epochMillis) {
        return format(epochMillis, ZoneId.systemDefault());
    }
}
