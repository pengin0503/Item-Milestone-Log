package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-150-70: screenshot naming and 20-generation rotation within the client-local area. */
class ScreenshotStoreTest {
    @Test
    void choosesCollisionFreeNamesWithinTheClientDirectory() {
        Path dir = ScreenshotStore.screenshotDir(Path.of("config"));
        assertEquals(Path.of("config", ClientStorageIO.CLIENT_DIR, "screenshots"), dir);
        Instant now = Instant.parse("2026-11-28T03:04:05Z");
        assertEquals("shot-20261128-030405.png", ScreenshotStore.chooseFileName(
                now, ZoneOffset.UTC, name -> false));
        assertEquals("shot-20261128-030405-1.png", ScreenshotStore.chooseFileName(
                now, ZoneOffset.UTC, name -> name.equals("shot-20261128-030405.png")));
    }

    @Test
    void rotationKeepsOnlyTheNewestTwentyOwnedFiles() {
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            names.add(String.format("shot-20261127-%02d0000.png", i));
        }
        names.add("foreign.png");
        List<String> prune = ScreenshotStore.selectFilesToPrune(names);
        assertEquals(10, prune.size());
        assertTrue(prune.contains("shot-20261127-010000.png"));
        assertFalse(prune.contains("foreign.png"));
        assertTrue(ScreenshotStore.isScreenshotFileName("shot-20261128-030405-2.png"));
        assertFalse(ScreenshotStore.isScreenshotFileName("Steve.png"));
    }
}
