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

    // ---- F48: locale-aware Component variants ----

    // 2026-11-25T09:46:40Z
    private static final long F48_EPOCH = 1795600000000L;
    private static final int[] F48_TICK_CASES = {
            0,                                   // 0 hours
            90 * 20,                             // 0h 01m 30s
            (27 * 3600 + 15 * 60 + 30) * 20,     // 27h 15m 30s
            (120 * 3600 + 5 * 60 + 9) * 20,      // 100+ hours
            -1000                                // clamped to 0
    };

    @Test
    void enUsPlaytimeComponentMatchesStringVariant() {
        for (int ticks : F48_TICK_CASES) {
            assertEquals(Playtime.format(ticks), Playtime.formatComponentForLang("en_us", ticks).getString(),
                    "ticks=" + ticks);
            // A null recipient falls back to en_us.
            assertEquals(Playtime.format(ticks),
                    Playtime.formatComponent((net.minecraft.server.level.ServerPlayer) null, ticks).getString());
        }
        assertEquals("0h 00m 00s", Playtime.formatComponentForLang("en_us", 0).getString());
        assertEquals("120h 05m 09s",
                Playtime.formatComponentForLang("en_us", (120 * 3600 + 5 * 60 + 9) * 20).getString());
    }

    @Test
    void enUsRecordComponentMatchesStringVariantForAllFourShapes() {
        ZoneId[] zones = {ZoneOffset.UTC, ZoneId.of("Asia/Tokyo"), ZoneId.of("-05:00")};
        for (int ticks : F48_TICK_CASES) {
            MilestoneRecord[] records = {
                    new MilestoneRecord(ticks, 11L, F48_EPOCH),                                   // Day + date
                    new MilestoneRecord(ticks, 0L, MilestoneRecord.UNKNOWN_EPOCH),                // Day only
                    new MilestoneRecord(ticks, MilestoneRecord.UNKNOWN_DAY, F48_EPOCH),           // date only
                    MilestoneRecord.legacy(ticks)                                                 // neither
            };
            for (MilestoneRecord rec : records) {
                for (ZoneId zone : zones) {
                    assertEquals(Playtime.formatRecord(rec, zone),
                            Playtime.formatRecordComponentForLang("en_us", rec, zone).getString(),
                            "rec=" + rec + " zone=" + zone);
                }
            }
        }
        int ticks = (27 * 3600 + 15 * 60 + 30) * 20;
        assertEquals("27h 15m 30s (Day 12, 2026-11-25)",
                Playtime.formatRecordComponentForLang("en_us", new MilestoneRecord(ticks, 11L, F48_EPOCH), ZoneOffset.UTC).getString());
        assertEquals("27h 15m 30s (Day 12)",
                Playtime.formatRecordComponentForLang("en_us", new MilestoneRecord(ticks, 11L, 0L), ZoneOffset.UTC).getString());
        assertEquals("27h 15m 30s (2026-11-25)",
                Playtime.formatRecordComponentForLang("en_us", new MilestoneRecord(ticks, -1L, F48_EPOCH), ZoneOffset.UTC).getString());
        assertEquals("27h 15m 30s",
                Playtime.formatRecordComponentForLang("en_us", MilestoneRecord.legacy(ticks), ZoneOffset.UTC).getString());
    }

    @Test
    void jaJpComponentsUseJapaneseTexts() {
        int ticks = (27 * 3600 + 3 * 60 + 5) * 20;
        assertEquals("27時間03分05秒", Playtime.formatComponentForLang("ja_jp", ticks).getString());
        assertEquals("0時間00分00秒", Playtime.formatComponentForLang("ja_jp", 0).getString());
        assertEquals("120時間05分09秒",
                Playtime.formatComponentForLang("ja_jp", (120 * 3600 + 5 * 60 + 9) * 20).getString());

        assertEquals("27時間03分05秒（5日目、2026-11-25）",
                Playtime.formatRecordComponentForLang("ja_jp", new MilestoneRecord(ticks, 4L, F48_EPOCH), ZoneOffset.UTC).getString());
        assertEquals("27時間03分05秒（5日目、2026-11-25）",
                Playtime.formatRecordComponentForLang("ja_JP", new MilestoneRecord(ticks, 4L, F48_EPOCH), ZoneOffset.UTC).getString());
        assertEquals("27時間03分05秒（5日目）",
                Playtime.formatRecordComponentForLang("ja_jp", new MilestoneRecord(ticks, 4L, 0L), ZoneOffset.UTC).getString());
        assertEquals("27時間03分05秒（2026-11-25）",
                Playtime.formatRecordComponentForLang("ja_jp", new MilestoneRecord(ticks, -1L, F48_EPOCH), ZoneOffset.UTC).getString());
        assertEquals("27時間03分05秒",
                Playtime.formatRecordComponentForLang("ja_jp", MilestoneRecord.legacy(ticks), ZoneOffset.UTC).getString());
        // F11 time zone still decides the date.
        long lateUtc = Instant.parse("2026-11-25T20:00:00Z").toEpochMilli();
        assertEquals("27時間03分05秒（5日目、2026-11-26）",
                Playtime.formatRecordComponentForLang("ja_jp", new MilestoneRecord(ticks, 4L, lateUtc), ZoneId.of("Asia/Tokyo")).getString());
    }

    @Test
    void recordComponentUsesExpectedKeysAndTranslator() {
        java.util.List<String> keys = new java.util.ArrayList<>();
        Playtime.Translator recording = (key, args) -> {
            keys.add(key);
            return net.minecraft.network.chat.Component.literal(key);
        };
        int ticks = 90 * 20;
        Playtime.formatRecordComponent(new MilestoneRecord(ticks, 0L, F48_EPOCH), ZoneOffset.UTC, recording);
        Playtime.formatRecordComponent(new MilestoneRecord(ticks, 0L, 0L), ZoneOffset.UTC, recording);
        Playtime.formatRecordComponent(new MilestoneRecord(ticks, -1L, F48_EPOCH), ZoneOffset.UTC, recording);
        Playtime.formatRecordComponent(MilestoneRecord.legacy(ticks), ZoneOffset.UTC, recording);
        assertEquals(java.util.List.of(
                "message.itemmilestonelog.playtime_format", "message.itemmilestonelog.record_day_date",
                "message.itemmilestonelog.playtime_format", "message.itemmilestonelog.record_day",
                "message.itemmilestonelog.playtime_format", "message.itemmilestonelog.record_date",
                "message.itemmilestonelog.playtime_format"), keys);
    }
}
