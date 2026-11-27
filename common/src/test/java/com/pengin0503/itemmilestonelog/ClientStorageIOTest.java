package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §1.4 regression coverage: server hashing and temp-file/atomic-move write discipline. */
class ClientStorageIOTest {
    @TempDir
    Path configDir;

    @Test
    void serverKeyIsStableHashedAndNeverTheRawAddress() {
        String first = ClientStorageIO.serverKey(" MC.Example.COM:25565 ", "World One");
        String again = ClientStorageIO.serverKey("mc.example.com:25565", "World One");
        String other = ClientStorageIO.serverKey("mc.example.com:25565", "World Two");
        assertEquals(first, again);
        assertFalse(first.equals(other));
        assertTrue(ClientStorageIO.isServerKey(first));
        assertFalse(first.contains("example"));
        assertFalse(first.contains("25565"));
        assertFalse(ClientStorageIO.isServerKey("mc.example.com:25565"));
    }

    @Test
    void atomicWriteReplacesExistingContentAndLeavesNoTempFiles() throws IOException {
        Path file = ClientStorageIO.clientDir(configDir).resolve("cache.json");
        ClientStorageIO.atomicWrite(file, "first");
        assertEquals("first", Files.readString(file, StandardCharsets.UTF_8));
        ClientStorageIO.atomicWrite(file, "second");
        assertEquals("second", Files.readString(file, StandardCharsets.UTF_8));

        try (Stream<Path> entries = Files.list(file.getParent())) {
            assertTrue(entries.noneMatch(path -> path.getFileName().toString().contains(".tmp-")),
                    "temporary files must be consumed or deleted");
        }
    }

    @Test
    void readOrNullIsLenientForMissingAndUnreadableFiles() throws IOException {
        Path missing = configDir.resolve("missing.json");
        assertNull(ClientStorageIO.readOrNull(missing));
        assertNull(ClientStorageIO.readOrNull(null));

        Files.createDirectories(configDir.resolve("itemmilestonelog-client"));
        Path directory = ClientStorageIO.clientDir(configDir);
        // Reading a directory as a file fails; the helper must return null rather than throw.
        assertNull(ClientStorageIO.readOrNull(directory));

        ClientStorageIO.atomicWrite(missing, "value");
        assertNotNull(ClientStorageIO.readOrNull(missing));
        assertEquals("value", ClientStorageIO.readOrNull(missing));
    }
}
