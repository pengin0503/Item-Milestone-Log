package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-150-64 regression coverage for the local cache: round-trip, caps, corruption, identity, offline shaping. */
class LocalSyncCacheTest {
    @TempDir
    Path configDir;

    private static final String SERVER_A = ClientStorageIO.serverKey("mc.example.com:25565", "World One");
    private static final String SERVER_B = ClientStorageIO.serverKey("10.0.0.9:25565", "World Two");

    private static LocalSyncCache.Snapshot sample(String serverKey) {
        return new LocalSyncCache.Snapshot(
                List.of(new LocalSyncCache.CachedTarget("minecraft:diamond", 100, true, false)),
                List.of(new LocalSyncCache.CachedRecord("minecraft:diamond", 100, 3600, 2, 1700000000000L)),
                List.of(new LocalSyncCache.CachedProgress("minecraft:diamond", 100, 42)),
                1234,
                List.of(new LocalSyncCache.CachedPreset("vanilla_endgame", "Vanilla Endgame", 5, true)),
                true, true, serverKey, 500L, false);
    }

    @Test
    void roundTripKeepsEveryPayloadFieldInWireOrder() throws Exception {
        LocalSyncCache.write(configDir, sample(SERVER_A));
        LocalSyncCache.LoadResult loaded = LocalSyncCache.read(configDir, SERVER_A);

        assertFalse(loaded.invalid());
        assertTrue(loaded.present());
        LocalSyncCache.Snapshot snapshot = loaded.snapshot();
        assertEquals(1, snapshot.targets().size());
        assertEquals("minecraft:diamond", snapshot.targets().get(0).itemId());
        assertEquals(100, snapshot.targets().get(0).count());
        assertTrue(snapshot.targets().get(0).personal());
        assertFalse(snapshot.targets().get(0).defaultTarget());
        assertEquals(3600, snapshot.records().get(0).playTicks());
        assertEquals(2L, snapshot.records().get(0).day());
        assertEquals(1700000000000L, snapshot.records().get(0).epochMillis());
        assertEquals(42, snapshot.progress().get(0).value());
        assertEquals(1234, snapshot.currentPlaytimeTicks());
        assertEquals("vanilla_endgame", snapshot.presets().get(0).id());
        assertEquals("Vanilla Endgame", snapshot.presets().get(0).displayName());
        assertEquals(5, snapshot.presets().get(0).itemCount());
        assertTrue(snapshot.presets().get(0).builtin());
        assertEquals(500L, snapshot.savedAtMillis());
    }

    @Test
    void everyListIsCappedAtThePayloadLimit() throws Exception {
        List<LocalSyncCache.CachedTarget> targets = new ArrayList<>();
        List<LocalSyncCache.CachedRecord> records = new ArrayList<>();
        List<LocalSyncCache.CachedProgress> progress = new ArrayList<>();
        List<LocalSyncCache.CachedPreset> presets = new ArrayList<>();
        for (int i = 0; i < LocalSyncCache.MAX_LIST_ENTRIES + 10; i++) {
            String id = "minecraft:item_" + i;
            targets.add(new LocalSyncCache.CachedTarget(id, i, false, false));
            records.add(new LocalSyncCache.CachedRecord(id, i, i, i, i));
            progress.add(new LocalSyncCache.CachedProgress(id, i, i));
            presets.add(new LocalSyncCache.CachedPreset("preset_" + i, "P" + i, i, false));
        }
        LocalSyncCache.Snapshot oversized = new LocalSyncCache.Snapshot(
                targets, records, progress, 0, presets, false, false, SERVER_A, 1L, false);

        LocalSyncCache.write(configDir, oversized);
        LocalSyncCache.Snapshot loaded = LocalSyncCache.read(configDir, SERVER_A).snapshot();
        assertEquals(LocalSyncCache.MAX_LIST_ENTRIES, loaded.targets().size());
        assertEquals(LocalSyncCache.MAX_LIST_ENTRIES, loaded.records().size());
        assertEquals(LocalSyncCache.MAX_LIST_ENTRIES, loaded.progress().size());
        assertEquals(LocalSyncCache.MAX_LIST_ENTRIES, loaded.presets().size());
        assertEquals("minecraft:item_0", loaded.targets().get(0).itemId());
        assertEquals("minecraft:item_4095", loaded.targets().get(4095).itemId());
    }

    @Test
    void corruptJsonIsToleratedAndReportedOnce() throws Exception {
        Path file = LocalSyncCache.fileFor(configDir);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ this is not json", java.nio.charset.StandardCharsets.UTF_8);

        LocalSyncCache.LoadResult loaded = LocalSyncCache.read(configDir, SERVER_A);
        assertTrue(loaded.invalid());
        assertFalse(loaded.present());
        assertTrue(loaded.snapshot().targets().isEmpty());
        assertTrue(loaded.snapshot().records().isEmpty());
        assertTrue(loaded.snapshot().offline());

        // A subsequent good write replaces the corrupt contents without throwing.
        LocalSyncCache.write(configDir, sample(SERVER_A));
        assertTrue(LocalSyncCache.read(configDir, SERVER_A).present());
    }

    @Test
    void serversAreIsolatedAndRawAddressesAreNeverPersisted() throws Exception {
        LocalSyncCache.write(configDir, sample(SERVER_A));
        LocalSyncCache.write(configDir, new LocalSyncCache.Snapshot(
                List.of(new LocalSyncCache.CachedTarget("minecraft:iron", 1, true, false)),
                List.of(), List.of(), 0, List.of(), false, false, SERVER_B, 2L, false));

        LocalSyncCache.Snapshot a = LocalSyncCache.read(configDir, SERVER_A).snapshot();
        LocalSyncCache.Snapshot b = LocalSyncCache.read(configDir, SERVER_B).snapshot();
        assertEquals("minecraft:diamond", a.targets().get(0).itemId());
        assertEquals("minecraft:iron", b.targets().get(0).itemId());
        assertEquals(SERVER_A, a.serverKey());
        assertEquals(SERVER_B, b.serverKey());

        String body = Files.readString(LocalSyncCache.fileFor(configDir));
        assertFalse(body.contains("mc.example.com"));
        assertFalse(body.contains("10.0.0.9"));
        assertFalse(body.contains("World One"));
        assertFalse(body.contains("World Two"));
        assertTrue(ClientStorageIO.isServerKey(SERVER_A));
    }

    @Test
    void writesAreDebouncedByOneSecond() {
        assertFalse(LocalSyncCache.writeDue(1000L, 1999L));
        assertTrue(LocalSyncCache.writeDue(1000L, 2000L));
        assertTrue(LocalSyncCache.writeDue(1000L, 5000L));
        assertTrue(LocalSyncCache.writeDue(5000L, 4000L), "backwards clock must not freeze the cache");
    }

    @Test
    void offlineViewKeepsPayloadDataButClearsServerFlags() {
        LocalSyncCache.Snapshot shaped = LocalSyncCache.offlineView(sample(SERVER_A));
        assertTrue(shaped.offline());
        assertFalse(shaped.openScreen());
        assertFalse(shaped.canManagePresets());
        assertEquals(1, shaped.targets().size());
        assertEquals(1, shaped.records().size());
        assertEquals(1, shaped.progress().size());
        assertEquals(1, shaped.presets().size());
        assertEquals(1234, shaped.currentPlaytimeTicks());
        assertEquals(SERVER_A, shaped.serverKey());

        // The original snapshot is untouched (no authority is mutated while shaping a view).
        assertFalse(sample(SERVER_A).offline());
    }
}
