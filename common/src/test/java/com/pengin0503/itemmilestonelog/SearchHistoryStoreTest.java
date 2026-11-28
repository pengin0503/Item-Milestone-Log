package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure F-150-84 tests: 50-entry FIFO, de-duplication, lenient JSON and no secret capture. */
class SearchHistoryStoreTest {
    @TempDir
    Path configDir;

    @Test
    void fiftyEntryFifoDropsTheOldestQuery() {
        List<String> raw = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            raw.add("query-" + i);
        }
        List<String> capped = SearchHistoryStore.normalize(raw, SearchHistoryStore.MAX_ENTRIES);
        assertEquals(50, capped.size());
        assertEquals("query-10", capped.get(0));
        assertEquals("query-59", capped.get(49));
        assertEquals(50, SearchHistoryStore.MAX_ENTRIES);
    }

    @Test
    void duplicatesCollapseAndTheNewestPositionWins() {
        List<String> normalized = SearchHistoryStore.normalize(
                List.of("oak", "stone", "oak", "gold"), 50);
        assertEquals(List.of("stone", "oak", "gold"), normalized);
        assertEquals(List.of("OAK", "stone"),
                SearchHistoryStore.normalize(List.of("oak", "OAK", "stone"), 50));
    }

    @Test
    void corruptJsonIsReadAsEmptyHistory() {
        assertEquals(List.of(), SearchHistoryStore.decode(null));
        assertEquals(List.of(), SearchHistoryStore.decode("not json at all"));
        assertEquals(List.of(), SearchHistoryStore.decode("{}"));
        assertEquals(List.of(), SearchHistoryStore.decode("{\"queries\":42}"));
        assertEquals(List.of("a"), SearchHistoryStore.decode("{\"queries\":[\"a\",7,null]}"));
    }

    @Test
    void secretsAndControlCharactersAreNeverPersisted() {
        assertFalse(SearchHistoryStore.isSafeQuery("https://discord.com/api/webhooks/123/abc"));
        assertFalse(SearchHistoryStore.isSafeQuery("http://example.invalid/chat?msg=secret"));
        assertFalse(SearchHistoryStore.isSafeQuery("line\nwith control char"));
        assertFalse(SearchHistoryStore.isSafeQuery("x".repeat(257)));
        assertEquals(List.of(), SearchHistoryStore.normalize(List.of(
                "http://example.com", "https://example.com", "user chat text \n"), 50));

        Path file = SearchHistoryStore.fileFor(configDir);
        assertTrue(file.toString().endsWith("itemmilestonelog-client/history.json"));
        SearchHistoryStore.add(configDir, "https://discord.com/api/webhooks/x/y");
        assertFalse(Files.exists(file), "an unsafe query must not create a history file");
    }

    @Test
    void addReadsWritesAndClearsThroughAtomicStorage() throws Exception {
        Path file = SearchHistoryStore.fileFor(configDir);
        assertTrue(SearchHistoryStore.add(configDir, "diamond").contains("diamond"));
        assertTrue(Files.exists(file));
        SearchHistoryStore.add(configDir, "iron");
        assertEquals(List.of("diamond", "iron"), SearchHistoryStore.read(configDir));
        SearchHistoryStore.add(configDir, "DIAMOND");
        assertEquals(List.of("iron", "DIAMOND"), SearchHistoryStore.read(configDir));
        assertTrue(SearchHistoryStore.clear(configDir));
        assertEquals(List.of(), SearchHistoryStore.read(configDir));
        assertFalse(SearchHistoryStore.clear(configDir));
        assertThrows(NullPointerException.class, () -> SearchHistoryStore.fileFor(null));
    }
}
