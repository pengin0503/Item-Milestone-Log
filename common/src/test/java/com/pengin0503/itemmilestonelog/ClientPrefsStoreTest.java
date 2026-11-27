package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-150-77: the once-only flag lives in client-local prefs.json and reads are lenient. */
class ClientPrefsStoreTest {
    @TempDir
    Path configDir;

    @Test
    void missingFileMeansNotShownAndMarkWritesTheExpectedJson() throws IOException {
        assertFalse(ClientPrefsStore.unboundHintShown(configDir));
        Path file = ClientPrefsStore.fileFor(configDir);
        assertEquals("prefs.json", file.getFileName().toString());
        assertTrue(ClientPrefsStore.markUnboundHintShown(configDir));
        assertTrue(ClientPrefsStore.unboundHintShown(configDir));
        assertEquals("{\"unboundHintShown\":true}\n".trim(), Files.readString(file, StandardCharsets.UTF_8).trim());
    }

    @Test
    void corruptOrWronglyShapedFilesAreTreatedAsNotShown() throws IOException {
        Path file = ClientPrefsStore.fileFor(configDir);
        ClientStorageIO.atomicWrite(file, "not json");
        assertFalse(ClientPrefsStore.unboundHintShown(configDir));
        ClientStorageIO.atomicWrite(file, "{\"unboundHintShown\":\"yes\"}");
        assertFalse(ClientPrefsStore.unboundHintShown(configDir));
        ClientStorageIO.atomicWrite(file, "[]");
        assertFalse(ClientPrefsStore.unboundHintShown(configDir));
    }

    @Test
    void fileStaysInTheClientDirectoryAndCarriesNoServerIdentity() {
        Path file = ClientPrefsStore.fileFor(configDir);
        assertEquals(ClientStorageIO.clientDir(configDir).resolve("prefs.json"), file);
        assertTrue(ClientPrefsStore.markUnboundHintShown(configDir));
        assertEquals(1, ClientStorageIO.clientDir(configDir).toFile().list().length);
    }
}
