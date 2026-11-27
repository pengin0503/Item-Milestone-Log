package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-150-72 regression coverage: hashed identity, twelve-key allow-list, 32-profile cap, lenient reads. */
class ClientProfileStoreTest {
    @TempDir
    Path configDir;

    private static final String SERVER = ClientStorageIO.serverKey("mc.example.com:25565", "World One");

    @Test
    void onlyTheTwelveDisplayKeysAreAccepted() {
        assertEquals(12, DisplayOptions.KEYS.size());
        assertEquals(12, ClientProfileStore.allowedKeys().size());
        assertTrue(ClientProfileStore.allowedKeys().containsAll(DisplayOptions.KEYS));

        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("pinHudEnabled", "true");
        raw.put("serverAddress", "mc.example.com:25565");
        raw.put("autoBackupEnabled", "false");
        raw.put("tooltipMaxLines", "5");
        // F-150-80-5: the three HUD layout keys are profile-overridable too.
        raw.put("pinHudAnchor", "bottom-right");
        raw.put("pinHudAutoHide", "false");
        raw.put("pinHudScale", "125");
        Map<String, String> filtered = ClientProfileStore.filter(raw);
        assertEquals(5, filtered.size());
        assertEquals("true", filtered.get("pinHudEnabled"));
        assertEquals("5", filtered.get("tooltipMaxLines"));
        assertEquals("bottom-right", filtered.get("pinHudAnchor"));
        assertEquals("false", filtered.get("pinHudAutoHide"));
        assertEquals("125", filtered.get("pinHudScale"));
        assertFalse(filtered.containsKey("serverAddress"));
        assertFalse(filtered.containsKey("autoBackupEnabled"));
    }

    @Test
    void saveThenLoadRestoresOverridesWithoutTouchingConfig() throws Exception {
        Map<String, String> overrides = Map.of("pinHudEnabled", "true", "tooltipSort", "count",
                "pinHudMaxRows", "4");
        ClientProfileStore.save(configDir, SERVER, overrides);
        Map<String, String> loaded = ClientProfileStore.load(configDir, SERVER);
        assertEquals(overrides, loaded);
        assertTrue(ClientProfileStore.readAll(configDir).profiles().containsKey(SERVER));

        ClientProfileStore.clear(configDir, SERVER);
        assertTrue(ClientProfileStore.load(configDir, SERVER).isEmpty());
        assertFalse(Files.exists(ClientProfileStore.fileFor(configDir).getParent().resolve("config.toml")));
    }

    @Test
    void profilesAreKeptToThirtyTwoOldestFirst() {
        for (int i = 0; i < ClientProfileStore.MAX_PROFILES + 5; i++) {
            ClientProfileStore.save(configDir, ClientStorageIO.serverKey("host-" + i, "world"),
                    Map.of("tooltipSort", "count"));
        }
        ClientProfileStore.LoadResult all = ClientProfileStore.readAll(configDir);
        assertFalse(all.invalid());
        assertEquals(ClientProfileStore.MAX_PROFILES, all.profiles().size());
        // The most recently saved server survives; the oldest five were dropped.
        assertTrue(all.profiles().containsKey(ClientStorageIO.serverKey("host-36", "world")));
        assertFalse(all.profiles().containsKey(ClientStorageIO.serverKey("host-0", "world")));
    }

    @Test
    void malformedProfilesFileIsLenient() throws Exception {
        Path file = ClientProfileStore.fileFor(configDir);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "not-json-at-all");
        ClientProfileStore.LoadResult loaded = ClientProfileStore.readAll(configDir);
        assertTrue(loaded.invalid());
        assertTrue(loaded.profiles().isEmpty());
        assertTrue(ClientProfileStore.load(configDir, SERVER).isEmpty());
    }

    @Test
    void fileBodyNeverContainsTheRawAddress() throws Exception {
        ClientProfileStore.save(configDir, SERVER, Map.of("pinHudEnabled", "true"));
        String body = Files.readString(ClientProfileStore.fileFor(configDir));
        assertFalse(body.contains("mc.example.com"));
        assertFalse(body.contains("World One"));
        assertTrue(body.contains(SERVER));
    }
}
