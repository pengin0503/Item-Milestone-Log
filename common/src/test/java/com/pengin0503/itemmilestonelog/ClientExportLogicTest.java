package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-150-65: local export format, caps, naming and secret filtering. */
class ClientExportLogicTest {
    private static final String WEBHOOK = "https://discord.com/api/webhooks/123/token";

    @TempDir
    Path tempDir;

    private static LocalSyncCache.Snapshot snapshot(int records) {
        List<LocalSyncCache.CachedRecord> rows = new ArrayList<>();
        for (int i = 0; i < records; i++) {
            rows.add(new LocalSyncCache.CachedRecord("minecraft:diamond", 1, 20, i + 1,
                    1_700_000_000_000L + i));
        }
        return new LocalSyncCache.Snapshot(
                List.of(new LocalSyncCache.CachedTarget("minecraft:diamond", 1, true, false)),
                rows, List.of(), 200, List.of(), false, false, "", 0L, true);
    }

    @Test
    void csvKeepsServerColumnOrderAndOmitsPlayerIdentity() {
        String csv = ClientExportLogic.renderCsv(snapshot(2), true);
        assertTrue(csv.startsWith("\uFEFF# offline: true\r\n" + CsvExportRenderer.HEADER_LINE));
        String[] cells = csv.split("\r\n")[2].split(",");
        assertEquals(CsvExportRenderer.HEADER_COLUMNS.size(), cells.length);
        assertEquals("", cells[0]);
        assertEquals("", cells[1]);
        assertEquals("minecraft:diamond", cells[2]);
        assertEquals("1", cells[3]);
        assertEquals("20", cells[5]);
        assertEquals("false", cells[10]);
    }

    @Test
    void jsonKeepsServerKeysAndAddsOfflineMeta() {
        String json = ClientExportLogic.renderJson(snapshot(1), true);
        assertTrue(json.contains("\"format\":1"));
        assertTrue(json.contains("\"offline\":true"));
        assertTrue(json.contains("\"targets\""));
        assertTrue(json.contains("\"records\""));
        assertTrue(json.contains("minecraft:diamond"));
        assertFalse(json.contains("player"));
    }

    @Test
    void exportCapsAt4096LinesAndUsesSameSecondSuffixes() throws IOException {
        String csv = ClientExportLogic.renderCsv(snapshot(10_000), false);
        assertEquals(ClientExportLogic.MAX_LINES, ClientExportLogic.countLines(csv));

        Path dir = ClientExportLogic.resolveExportDir(tempDir, "");
        Files.createDirectories(dir);
        Instant now = Instant.parse("2026-11-28T03:04:05Z");
        assertEquals("client-20261128-030405.csv", ClientExportLogic.chooseFileName(
                now, ZoneOffset.UTC, "csv", name -> Files.exists(dir.resolve(name))));
        Files.writeString(dir.resolve("client-20261128-030405.csv"), "old");
        assertEquals("client-20261128-030405-1.csv", ClientExportLogic.chooseFileName(
                now, ZoneOffset.UTC, "csv", name -> Files.exists(dir.resolve(name))));

        ClientExportLogic.Result result = ClientExportLogic.export(tempDir, "", "json",
                snapshot(1), true, now, ZoneOffset.UTC);
        assertTrue(result.written());
        assertTrue(result.offline());
        assertTrue(Files.exists(result.file()));
        assertTrue(result.file().startsWith(ClientStorageIO.clientDir(tempDir)));
    }

    @Test
    void pathContainmentRejectsAbsoluteAndParentTraversal() throws IOException {
        assertThrows(IOException.class,
                () -> ClientExportLogic.resolveExportDir(tempDir, tempDir.toString()));
        assertThrows(IOException.class, () -> ClientExportLogic.resolveExportDir(tempDir, "../escape"));
        assertThrows(IOException.class, () -> ClientExportLogic.resolveExportDir(tempDir, "a/../../escape"));
        assertThrows(IOException.class, () -> ClientExportLogic.resolveExportDir(tempDir, "/tmp/escape"));
        assertEquals(ClientStorageIO.clientDir(tempDir).resolve("exports"),
                ClientExportLogic.resolveExportDir(tempDir, ""));
        assertEquals(ClientStorageIO.clientDir(tempDir).resolve("custom/exports"),
                ClientExportLogic.resolveExportDir(tempDir, "custom/exports"));
    }

    @Test
    void secretsAndOtherPlayerDataAreNeverWritten() throws IOException {
        LocalSyncCache.Snapshot dirty = new LocalSyncCache.Snapshot(
                List.of(new LocalSyncCache.CachedTarget(WEBHOOK, 1, true, false)),
                List.of(new LocalSyncCache.CachedRecord("Steve", 1, 5, 1, 1L),
                        new LocalSyncCache.CachedRecord(WEBHOOK, 1, 5, 1, 1L),
                        new LocalSyncCache.CachedRecord("minecraft:diamond", 1, 5, 1, 1L)),
                List.of(), 5, List.of(), false, false, "", 0L, true);
        String json = ClientExportLogic.renderJson(dirty, true);
        String csv = ClientExportLogic.renderCsv(dirty, true);
        for (String output : List.of(json, csv)) {
            assertFalse(output.contains(WEBHOOK));
            assertFalse(output.contains("Steve"));
            assertFalse(output.contains("env:"));
        }
        assertTrue(csv.contains("minecraft:diamond"));
        assertEquals(1, ClientExportLogic.countLines(
                ClientExportLogic.renderCsv(snapshot(1), false)) - 1);
    }
}
