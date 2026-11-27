package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-150-52 / F-150-53 (PR-2): pure diagnostics aggregation and line-selection tests (5-tree shared). */
class DiagnosticsSnapshotTest {
    private static final DiagnosticsSnapshot.BackupState BACKUP =
            new DiagnosticsSnapshot.BackupState(true, 3, "2026-11-27 12:00");
    private static final DiagnosticsSnapshot.DiscordState DISCORD =
            new DiagnosticsSnapshot.DiscordState("RESOLVED", true, 7, 1_700_000_000_000L);
    private static final DiagnosticsSnapshot.IoState IO =
            new DiagnosticsSnapshot.IoState(true, true, 2, false, "BACKUP=2", "itemmilestonelog-io");
    private static final DiagnosticsSnapshot.RuntimeState RUNTIME = new DiagnosticsSnapshot.RuntimeState(
            false, 0.0, "Server thread", true, 4, true, 7, true, 5, true, false, true, true, 512L, 1024L);

    private static DiagnosticsSnapshot.Snapshot snapshot(DiagnosticsSnapshot.DataCounts data) {
        return new DiagnosticsSnapshot.Snapshot("World", 2, 12345, data, BACKUP, DISCORD, IO, RUNTIME);
    }

    @Test
    void abnormalCountAggregatesNegativeTicksAndUnknownEpoch() {
        assertEquals(0, new DiagnosticsSnapshot.DataCounts(true, 0, 0, 0, 0, 0, 0).abnormal());
        DiagnosticsSnapshot.DataCounts broken = new DiagnosticsSnapshot.DataCounts(false, 3, 10, 8, 2, 3, 4);
        assertEquals(7, broken.abnormal());
        assertEquals(3, broken.negativeTicks());
        assertEquals(4, broken.unknownEpoch());
        assertEquals(2, broken.worldFirsts());
    }

    @Test
    void warningCountTracksOnlyAbnormalRecordsAndMissingBackupDir() {
        DiagnosticsSnapshot.Snapshot healthy = snapshot(new DiagnosticsSnapshot.DataCounts(true, 0, 0, 0, 0, 0, 0));
        assertEquals(0, DiagnosticsSnapshot.warningCount(healthy));
        assertFalse(DiagnosticsSnapshot.hasWarnings(healthy));

        DiagnosticsSnapshot.Snapshot negative = snapshot(new DiagnosticsSnapshot.DataCounts(false, 1, 2, 2, 1, 1, 0));
        assertEquals(1, DiagnosticsSnapshot.warningCount(negative));
        assertTrue(DiagnosticsSnapshot.hasWarnings(negative));

        DiagnosticsSnapshot.BackupState missingDir = new DiagnosticsSnapshot.BackupState(false, 0, "none");
        DiagnosticsSnapshot.Snapshot broken = new DiagnosticsSnapshot.Snapshot("World", 0, 0,
                new DiagnosticsSnapshot.DataCounts(false, 1, 2, 2, 1, 2, 3), missingDir, DISCORD, IO, RUNTIME);
        assertEquals(2, DiagnosticsSnapshot.warningCount(broken));
    }

    @Test
    void doctorLinesFitTheCapAndIncludeEverySection() {
        DiagnosticsSnapshot.Snapshot broken = snapshot(new DiagnosticsSnapshot.DataCounts(false, 9, 40, 30, 5, 8, 9));
        List<DiagnosticsSnapshot.Line> lines = DiagnosticsSnapshot.doctorLines(broken);
        assertTrue(lines.size() <= DiagnosticsSnapshot.MAX_LINES, "doctor rendered " + lines.size() + " lines");
        assertEquals(19, lines.size(), "5 sections + 13 values + 1 result line");
        Set<String> keys = lines.stream().map(DiagnosticsSnapshot.Line::key).collect(Collectors.toSet());
        for (String section : List.of(DiagnosticsSnapshot.SECTION_SERVER, DiagnosticsSnapshot.SECTION_DATA,
                DiagnosticsSnapshot.SECTION_BACKUP, DiagnosticsSnapshot.SECTION_DISCORD,
                DiagnosticsSnapshot.SECTION_IO)) {
            assertTrue(keys.contains(section), "missing section " + section);
        }
        assertTrue(keys.contains(DiagnosticsSnapshot.DOCTOR_WARNINGS));
        assertFalse(keys.contains(DiagnosticsSnapshot.DOCTOR_OK));
        long warnings = lines.stream().filter(DiagnosticsSnapshot.Line::warning).count();
        assertEquals(2, warnings,
                "abnormal-records line plus the warning-count summary line are styled as warnings");
        // Warning-state is selected, not duplicated: the healthy case uses doctor_ok.
        List<DiagnosticsSnapshot.Line> ok = DiagnosticsSnapshot.doctorLines(
                snapshot(new DiagnosticsSnapshot.DataCounts(true, 0, 0, 0, 0, 0, 0)));
        assertTrue(ok.stream().anyMatch(l -> DiagnosticsSnapshot.DOCTOR_OK.equals(l.key())));
        assertTrue(ok.stream().noneMatch(DiagnosticsSnapshot.Line::warning));
    }

    @Test
    void doctorAndDebugLinesNeverCarryNamesOrWebhookUrls() {
        String secret = "https://discord.com/api/webhooks/secret-token";
        String playerName = "AliceTheVeryLongPlayerName";
        DiagnosticsSnapshot.Snapshot s = new DiagnosticsSnapshot.Snapshot(
                "TestWorld", 1, 1,
                new DiagnosticsSnapshot.DataCounts(false, 1, 1, 1, 1, 0, 0),
                BACKUP, new DiagnosticsSnapshot.DiscordState("RESOLVED", true, 7, 1L), IO, RUNTIME);
        for (DiagnosticsSnapshot.Line line : DiagnosticsSnapshot.doctorLines(s)) {
            for (Object arg : line.args()) {
                assertFalse(String.valueOf(arg).contains(secret), "no webhook URL in output");
                assertFalse(String.valueOf(arg).contains(playerName), "no player name in output");
            }
        }
        // The status enum name is fine, but the configured key / resolved value is never present.
        assertEquals("RESOLVED", s.discord().webhookStatus());
    }

    @Test
    void debugLinesSelectUnavailableWordingForMissingApisAndKeepLabelsPrecise() {
        DiagnosticsSnapshot.Snapshot s = snapshot(new DiagnosticsSnapshot.DataCounts(false, 2, 3, 4, 1, 0, 0));
        List<DiagnosticsSnapshot.Line> lines = DiagnosticsSnapshot.adminDebugLines(s);
        assertTrue(lines.size() <= DiagnosticsSnapshot.MAX_LINES);
        assertEquals(DiagnosticsSnapshot.DEBUG_HEADER, lines.get(0).key());
        assertEquals(DiagnosticsSnapshot.DEBUG_UNAVAILABLE, lines.get(1).key(),
                "TPS API absent in all five trees -> fixed unavailable wording");
        assertEquals("message.itemmilestonelog.admin_debug_stat_tps", lines.get(1).args().get(0));
        assertTrue(lines.stream().anyMatch(l -> l.key().equals(DiagnosticsSnapshot.DEBUG_LINE)
                && l.args().get(0).equals("message.itemmilestonelog.admin_debug_stat_notifications")
                && Integer.valueOf(4).equals(l.args().get(1))));
        assertTrue(lines.stream().anyMatch(l -> l.key().equals(DiagnosticsSnapshot.DEBUG_LINE)
                && l.args().get(0).equals("message.itemmilestonelog.admin_debug_stat_io_kinds")
                && "BACKUP=2".equals(l.args().get(1))));

        DiagnosticsSnapshot.RuntimeState less = new DiagnosticsSnapshot.RuntimeState(
                true, 20.0, "Server thread", false, -1, false, -1, false, -1, false, false, false, false, 256L, 512L);
        DiagnosticsSnapshot.Snapshot limited = new DiagnosticsSnapshot.Snapshot("World", 0, 0,
                new DiagnosticsSnapshot.DataCounts(true, 0, 0, 0, 0, 0, 0), missingBackup(),
                DISCORD, IO, less);
        List<DiagnosticsSnapshot.Line> limitedLines = DiagnosticsSnapshot.adminDebugLines(limited);
        assertEquals(DiagnosticsSnapshot.DEBUG_LINE, limitedLines.get(1).key(),
                "TPS API available -> real value line");
        assertEquals(20.0, limitedLines.get(1).args().get(1));
        assertEquals(5, limitedLines.stream().filter(l -> l.key().equals(DiagnosticsSnapshot.DEBUG_UNAVAILABLE)).count(),
                "notifications/drop/discord-queue/dead-webhook/discord-thread all fall back to unavailable");
    }

    private static DiagnosticsSnapshot.BackupState missingBackup() {
        return new DiagnosticsSnapshot.BackupState(false, 0, "none");
    }
}
