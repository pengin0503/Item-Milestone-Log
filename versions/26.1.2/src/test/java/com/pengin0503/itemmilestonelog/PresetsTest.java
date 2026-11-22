package com.pengin0503.itemmilestonelog;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresetsTest {
    @Test
    void allPresetsContainValidIdentifiers() {
        assertFalse(Presets.PRESETS.isEmpty());
        for (Map.Entry<String, List<String>> entry : Presets.PRESETS.entrySet()) {
            assertFalse(entry.getValue().isEmpty(), "Preset should not be empty: " + entry.getKey());
            for (String itemId : entry.getValue()) {
                assertTrue(itemId.contains(":"), "Preset item ID missing namespace: " + itemId);
                assertNotNull(Identifier.tryParse(itemId), "Invalid Identifier: " + itemId);
            }
        }
    }

    @Test
    void namesAreSortedAndCoverBuiltins(@TempDir Path dir) {
        Presets.reloadFrom(dir);
        List<String> names = Presets.names();
        assertEquals(List.of("atm_star", "vanilla_endgame"), names);
    }

    @Test
    void loadsCustomPresetsHandlesMalformedAndIgnoresBuiltinCollision(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("my_pack.json"), """
                {
                  "name": "My Pack Goals",
                  "items": [
                    "minecraft:diamond",
                    "othermod:custom_item",
                    "minecraft:diamond",
                    "not valid id"
                  ]
                }
                """);
        Files.writeString(dir.resolve("broken.json"), "{ not valid json");
        Files.writeString(dir.resolve("empty.json"), """
                { "name": "Empty", "items": [] }
                """);
        Files.writeString(dir.resolve("atm_star.json"), """
                { "name": "Override Attempt", "items": ["minecraft:dirt"] }
                """);

        int customCount = Presets.reloadFrom(dir);
        assertEquals(1, customCount);
        assertEquals(List.of("atm_star", "my_pack", "vanilla_endgame"), Presets.names());

        Presets.PresetDefinition custom = Presets.get("my_pack");
        assertNotNull(custom);
        assertFalse(custom.builtin());
        assertEquals("My Pack Goals", custom.displayName());
        assertEquals(List.of("minecraft:diamond", "othermod:custom_item"), custom.items());

        assertNull(Presets.get("broken"));
        assertNull(Presets.get("empty"));
        assertTrue(Presets.get("atm_star").builtin());

        Presets.reloadFrom(dir.resolve("clean"));
    }

    @Test
    void savesOverwritesAndDeletesCustomPresets(@TempDir Path dir) throws Exception {
        Presets.reloadFrom(dir);

        assertFalse(Presets.saveCustomTo(dir, "starter_set", List.of("minecraft:oak_log", "minecraft:cobblestone")));
        assertTrue(Files.isRegularFile(dir.resolve("starter_set.json")));
        assertNotNull(Presets.get("starter_set"));

        assertTrue(Presets.saveCustomTo(dir, "starter_set", List.of("minecraft:diamond")));
        assertEquals(List.of("minecraft:diamond"), Presets.get("starter_set").items());

        assertThrows(IllegalArgumentException.class, () -> Presets.saveCustomTo(dir, "atm_star", List.of("minecraft:dirt")));
        assertThrows(IllegalArgumentException.class, () -> Presets.saveCustomTo(dir, "Bad Name", List.of("minecraft:dirt")));

        assertTrue(Presets.deleteCustomFrom(dir, "starter_set"));
        assertFalse(Files.exists(dir.resolve("starter_set.json")));
        assertNull(Presets.get("starter_set"));
        assertFalse(Presets.deleteCustomFrom(dir, "atm_star"));

        Presets.reloadFrom(dir.resolve("clean"));
    }
}
