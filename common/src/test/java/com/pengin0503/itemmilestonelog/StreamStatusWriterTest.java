package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-150-66 / F-150-83: stable OBS keys, debounce, atomic writes and secret filtering. */
class StreamStatusWriterTest {
    @TempDir
    Path tempDir;

    private static final StreamStatusWriter.Values VALUES = new StreamStatusWriter.Values(
            3, 5, "minecraft:diamond#2", 2400, false, "test:preset", 1);

    @Test
    void txtHasFiveOriginalKeysThenTwoAppendedKeys() {
        List<String> lines = StreamStatusWriter.renderTxt(VALUES).lines().toList();
        assertEquals(7, lines.size());
        assertEquals(List.of("records", "targets", "last_record", "playtime_ticks",
                "offline", "current_preset", "world_first_count"), StreamStatusWriter.KEYS);
        for (int i = 0; i < lines.size(); i++) {
            assertTrue(lines.get(i).startsWith(StreamStatusWriter.KEYS.get(i) + ": "), lines.get(i));
        }
        assertTrue(lines.get(0).endsWith("3"));
        assertTrue(lines.get(4).endsWith("false"));
        assertTrue(lines.get(6).endsWith("1"));
    }

    @Test
    void offlineSnapshotHasOnlyOfflineKeyFilled() {
        String txt = StreamStatusWriter.renderTxt(StreamStatusWriter.offlineValues());
        assertTrue(txt.contains("offline: true"));
        for (String key : List.of("records", "targets", "last_record", "playtime_ticks",
                "current_preset", "world_first_count")) {
            assertTrue(txt.contains(key + ": \n"), key);
        }
    }

    @Test
    void debounceAndSameValueSkipAvoidNeedlessWrites() {
        String txt = StreamStatusWriter.renderTxt(VALUES);
        assertFalse(StreamStatusWriter.shouldWrite(1000L, 1500L, "old", txt));
        assertTrue(StreamStatusWriter.shouldWrite(1000L, 2000L, "old", txt));
        assertFalse(StreamStatusWriter.shouldWrite(1000L, 5000L, txt, txt));
        assertTrue(StreamStatusWriter.shouldWrite(5000L, 4000L, "old", txt));
    }

    @Test
    void writesBothFilesAtomicallyUnderClientDir() throws IOException {
        StreamStatusWriter.write(tempDir, VALUES);
        Path txt = StreamStatusWriter.txtFile(tempDir);
        Path json = StreamStatusWriter.jsonFile(tempDir);
        assertTrue(Files.exists(txt));
        assertTrue(Files.exists(json));
        assertTrue(txt.startsWith(ClientStorageIO.clientDir(tempDir)));
        assertEquals(StreamStatusWriter.renderTxt(VALUES), Files.readString(txt));
        String body = Files.readString(json);
        assertTrue(body.contains("\"world_first_count\":1"));
        assertTrue(body.contains("\"current_preset\":\"test:preset\""));
    }

    @Test
    void countedAndTagRecordsReachLastRecord() {
        assertTrue(StreamStatusWriter.isSafeValue("minecraft:diamond#2"));
        assertTrue(StreamStatusWriter.isSafeValue("#minecraft:planks"));
        assertTrue(StreamStatusWriter.isSafeValue("#minecraft:planks#3"));
        StreamStatusWriter.Values tagged = new StreamStatusWriter.Values(
                1, 1, "#minecraft:planks#3", 20, false, "", 0);
        assertTrue(StreamStatusWriter.renderTxt(tagged).contains("last_record: #minecraft:planks#3"));
        assertFalse(StreamStatusWriter.isSafeValue("minecraft:diamond#0"));
        assertFalse(StreamStatusWriter.isSafeValue("minecraft:diamond#1000001"));
    }

    @Test
    void lowercaseUrlAndEnvSchemesAreRejected() {
        for (String value : List.of("http://evil.example/a", "https://example/x",
                "env:TOKEN", "Env:TOKEN", "ENV:TOKEN", "minecraft:diamond env:secret")) {
            assertFalse(StreamStatusWriter.isSafeValue(value), value);
            assertFalse(StreamStatusWriter.sanitize(value).equals(value), value);
            assertFalse(ClientExportLogic.isSafeItemId(value), value);
        }
        assertTrue(StreamStatusWriter.isSafeValue("minecraft:diamond"));
    }

    @Test
    void secretsAndPlayerNamesAreNeverWritten() {
        StreamStatusWriter.Values dirty = new StreamStatusWriter.Values(
                1, 1, "https://discord.com/api/webhooks/123/token#Steve", 10, false,
                "env:DISCORD_WEBHOOK", 0);
        String txt = StreamStatusWriter.renderTxt(dirty);
        String json = StreamStatusWriter.renderJson(dirty);
        for (String output : List.of(txt, json)) {
            assertFalse(output.contains("https://"));
            assertFalse(output.contains("Steve"));
            assertFalse(output.contains("env:"));
            assertFalse(output.contains("token"));
        }
    }
}
