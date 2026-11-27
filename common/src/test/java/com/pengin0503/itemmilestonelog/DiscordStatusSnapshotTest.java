package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-Java tests for the F-150-54 {@code discordstatus} snapshot (shared by all five trees). */
class DiscordStatusSnapshotTest {
    private static final long SECRET_FREE_NOW = 1_700_000_000_000L;

    private static DiscordStatusSnapshot snapshot(String envVarName, long lastEpoch, int intervalDays) {
        return new DiscordStatusSnapshot("RESOLVED", envVarName, true, "all", true,
                intervalDays, lastEpoch, SECRET_FREE_NOW, true, 3);
    }

    @Test
    void statusFieldsRenderWithinTwelveLinesAndNeverCarryAUrl() {
        DiscordStatusSnapshot withEnv = snapshot("DISCORD_WEBHOOK", SECRET_FREE_NOW - 1_000L, 7);
        List<DiagnosticsSnapshot.Line> lines = withEnv.lines();
        assertTrue(lines.size() <= DiscordStatusSnapshot.MAX_LINES);
        assertEquals(9, lines.size(), "eight fixed fields plus the env-name line");
        assertEquals("message.itemmilestonelog.discordstatus_webhook", lines.get(0).key());
        assertEquals(List.of("RESOLVED"), lines.get(0).args());
        assertEquals("message.itemmilestonelog.discordstatus_env", lines.get(1).key());
        assertEquals(List.of("DISCORD_WEBHOOK"), lines.get(1).args(), "variable name only, never its value");
        assertEquals(List.of(true), lines.get(2).args(), "embed mode");
        assertEquals(List.of("all"), lines.get(3).args(), "notify scope");
        assertEquals(List.of(true), lines.get(4).args(), "digest enabled");
        assertEquals(List.of(7), lines.get(5).args(), "interval days");
        assertEquals(List.of(SECRET_FREE_NOW - 1_000L), lines.get(6).args(), "last epoch");
        assertEquals(List.of(withEnv.nextDigestDueMillis()), lines.get(7).args(), "next due");
        assertEquals(List.of(3), lines.get(8).args(), "pending send count");
        for (DiagnosticsSnapshot.Line line : lines) {
            assertFalse(line.key().contains("https://"));
            for (Object arg : line.args()) {
                assertFalse(String.valueOf(arg).contains("https://"), "no resolved URL in status line");
            }
        }
    }

    @Test
    void envLineIsOmittedForLiteralUrlsAndPendingCountCanFallBackToFixedWording() {
        DiscordStatusSnapshot literal = new DiscordStatusSnapshot("EMPTY", null, false,
                "world_first", false, 30, 0L, SECRET_FREE_NOW, false, 0);
        List<DiagnosticsSnapshot.Line> lines = literal.lines();
        assertEquals(8, lines.size());
        assertEquals("message.itemmilestonelog.discordstatus_webhook", lines.get(0).key());
        assertFalse(lines.stream().anyMatch(line -> line.key().endsWith("_env")));
        DiagnosticsSnapshot.Line pending = lines.get(lines.size() - 1);
        assertEquals("message.itemmilestonelog.discordstatus_pending_unavailable", pending.key());
        assertTrue(pending.args().isEmpty());
    }

    @Test
    void nextDueUsesTheSharedIntervalAndSaturatesOnOverflow() {
        assertEquals(SECRET_FREE_NOW, snapshot(null, 0L, 7).nextDigestDueMillis(),
                "first run is due at the current time");
        long last = SECRET_FREE_NOW - 1_000L;
        assertEquals(last + DiscordDigestLogic.intervalMillis(7), snapshot(null, last, 7).nextDigestDueMillis());
        assertEquals(last + DiscordDigestLogic.MILLIS_PER_DAY, snapshot(null, last, 0).nextDigestDueMillis(),
                "interval clamping is shared with the digest logic");
        assertEquals(Long.MAX_VALUE, snapshot(null, Long.MAX_VALUE - 10L, 30).nextDigestDueMillis());
    }
}
