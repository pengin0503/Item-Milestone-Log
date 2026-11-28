package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-150-85: local-only carry file, caps, duplicates and secret filtering. */
class ClientCarryStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void capsAtSixtyFourEntriesAndDeduplicates() {
        List<ClientCarryStore.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            entries.add(new ClientCarryStore.Entry("minecraft:item_" + i, 1));
        }
        entries.add(new ClientCarryStore.Entry("minecraft:item_0", 1));
        assertEquals(ClientCarryStore.MAX_ITEMS, ClientCarryStore.merge(entries, List.of()).size());
        assertEquals(64, ClientCarryStore.render(entries).split("\"id\"", -1).length - 1);
    }

    @Test
    void capKeepsTheNewestTailAndImportInstallsDisplayOnlyCandidates() throws Exception {
        List<ClientCarryStore.Entry> many = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            many.add(new ClientCarryStore.Entry("minecraft:item_" + i, 1));
        }
        List<ClientCarryStore.Entry> capped = ClientCarryStore.merge(many, List.of());
        assertTrue(capped.contains(new ClientCarryStore.Entry("minecraft:item_99", 1)));
        assertFalse(capped.contains(new ClientCarryStore.Entry("minecraft:item_0", 1)));

        ClientCarryStore.export(tempDir, List.of(new ClientCarryStore.Entry("minecraft:iron", 1)),
                List.of());
        assertEquals(List.of(new ClientCarryStore.Entry("minecraft:iron", 1)),
                ClientCarryStore.importCandidates(tempDir));
        assertEquals(ClientCarryStore.imported(), ClientCarryStore.importCandidates(tempDir));
        ClientCarryStore.clearImported();
        assertTrue(ClientCarryStore.imported().isEmpty());
    }

    @Test
    void pinsAndPersonalTargetsAreMergedFirstWins() {
        List<ClientCarryStore.Entry> pins = List.of(new ClientCarryStore.Entry("minecraft:diamond", 2));
        List<ClientCarryStore.Entry> targets = List.of(
                new ClientCarryStore.Entry("minecraft:diamond", 2),
                new ClientCarryStore.Entry("minecraft:iron", 1));
        List<ClientCarryStore.Entry> merged = ClientCarryStore.merge(pins, targets);
        assertEquals(2, merged.size());
        assertEquals("minecraft:diamond x 2", merged.get(0).encoded());
    }

    @Test
    void exportAndImportArePureLocalFileOperations() throws IOException {
        int written = ClientCarryStore.export(tempDir,
                List.of(new ClientCarryStore.Entry("minecraft:diamond", 1)), List.of());
        assertEquals(1, written);
        Path file = ClientCarryStore.fileFor(tempDir);
        assertTrue(file.startsWith(ClientStorageIO.clientDir(tempDir)));
        assertTrue(Files.exists(file));
        String body = Files.readString(file);
        assertTrue(body.contains("\"localOnly\":true"));
        assertEquals(1, ClientCarryStore.importCandidates(tempDir).size());
    }

    @Test
    void malformedJsonBecomesEmptyCandidates() throws IOException {
        ClientStorageIO.atomicWrite(ClientCarryStore.fileFor(tempDir), "{not json");
        assertTrue(ClientCarryStore.read(tempDir).isEmpty());
    }

    @Test
    void otherPlayerNamesWebhooksAndUnsafeIdsAreNeverPersisted() {
        List<ClientCarryStore.Entry> dirty = List.of(
                new ClientCarryStore.Entry("Steve", 1),
                new ClientCarryStore.Entry("https://discord.com/api/webhooks/123/token", 1),
                new ClientCarryStore.Entry("env:DISCORD_WEBHOOK", 1),
                new ClientCarryStore.Entry("minecraft:diamond", 1));
        String json = ClientCarryStore.render(dirty);
        assertFalse(json.contains("Steve"));
        assertFalse(json.contains("https://"));
        assertFalse(json.contains("env:"));
        assertTrue(json.contains("minecraft:diamond"));
        assertEquals(1, ClientCarryStore.merge(dirty, List.of()).size());
    }
}
