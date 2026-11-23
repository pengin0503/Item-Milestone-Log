package com.pengin0503.itemmilestonelog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
    @BeforeEach
    void initCleanPresets(@TempDir Path emptyDir) {
        Presets.reloadFrom(emptyDir);
    }

    @AfterEach
    void resetPresets(@TempDir Path emptyDir) {
        Presets.reloadFrom(emptyDir);
    }

    @Test
    void allPresetsContainValidResourceLocations() {
        assertFalse(Presets.PRESETS.isEmpty());
        for (Map.Entry<String, List<String>> entry : Presets.PRESETS.entrySet()) {
            assertFalse(entry.getValue().isEmpty(), "Preset should not be empty: " + entry.getKey());
            for (String itemId : entry.getValue()) {
                assertTrue(itemId.contains(":"), "Preset item ID missing namespace: " + itemId);
                assertNotNull(ResourceLocation.tryParse(itemId), "Invalid ResourceLocation: " + itemId);
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
    void loadsCustomPresetsHandlesMalformedQuantityObjectsAndIgnoresBuiltinCollision(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("my_pack.json"), """
                {
                  "name": "My Pack Goals",
                  "items": [
                    "minecraft:diamond",
                    {"item": "minecraft:diamond", "count": 100},
                    "minecraft:emerald#50",
                    "othermod:custom_item",
                    "minecraft:diamond",
                    {"item": "minecraft:iron_ingot", "count": 0},
                    {"item": "minecraft:gold_ingot", "count": 1000001},
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
        assertEquals(List.of(
                TargetKey.of("minecraft:diamond"),
                TargetKey.of("minecraft:diamond", 100),
                TargetKey.of("minecraft:emerald", 50),
                TargetKey.of("othermod:custom_item")
        ), custom.items());

        assertNull(Presets.get("broken"));
        assertNull(Presets.get("empty"));
        Presets.PresetDefinition atmStar = Presets.get("atm_star");
        assertNotNull(atmStar);
        assertTrue(atmStar.builtin());
        assertEquals("atm_star", atmStar.displayName());
        assertEquals(10, atmStar.items().size());
        assertFalse(atmStar.items().contains(TargetKey.of("minecraft:dirt")));

        // Reset back to clean dir
        Presets.reloadFrom(dir.resolve("clean"));
    }

    @Test
    void savesOverwritesAndDeletesCustomPresetsWithQuantityFormat(@TempDir Path dir) throws Exception {
        Presets.reloadFrom(dir);

        List<TargetKey> initialItems = List.of(
                TargetKey.of("minecraft:oak_log"),
                TargetKey.of("minecraft:cobblestone", 64));
        assertFalse(Presets.saveCustomTo(dir, "starter_set", initialItems));
        Path savedPath = dir.resolve("starter_set.json");
        assertTrue(Files.isRegularFile(savedPath));
        JsonObject parsedJson = JsonParser.parseString(Files.readString(savedPath)).getAsJsonObject();
        JsonArray itemsArr = parsedJson.getAsJsonArray("items");
        assertEquals(2, itemsArr.size());
        assertTrue(itemsArr.get(0).isJsonPrimitive() && itemsArr.get(0).getAsJsonPrimitive().isString());
        assertEquals("minecraft:oak_log", itemsArr.get(0).getAsString());
        assertTrue(itemsArr.get(1).isJsonObject());
        assertEquals("minecraft:cobblestone", itemsArr.get(1).getAsJsonObject().get("item").getAsString());
        assertEquals(64, itemsArr.get(1).getAsJsonObject().get("count").getAsInt());
        assertNotNull(Presets.get("starter_set"));

        // Round-trip reload from disk
        Presets.reloadFrom(dir);
        assertEquals(initialItems, Presets.get("starter_set").items());

        assertTrue(Presets.saveCustomTo(dir, "starter_set", List.of(TargetKey.of("minecraft:diamond"))));
        assertEquals(List.of(TargetKey.of("minecraft:diamond")), Presets.get("starter_set").items());
        JsonObject overwrittenJson = JsonParser.parseString(Files.readString(savedPath)).getAsJsonObject();
        JsonArray overwrittenArr = overwrittenJson.getAsJsonArray("items");
        assertEquals(1, overwrittenArr.size());
        assertEquals("minecraft:diamond", overwrittenArr.get(0).getAsString());
        Presets.reloadFrom(dir);
        assertEquals(List.of(TargetKey.of("minecraft:diamond")), Presets.get("starter_set").items());

        assertThrows(IllegalArgumentException.class, () -> Presets.saveCustomTo(dir, "atm_star", List.of(TargetKey.of("minecraft:dirt"))));
        assertThrows(IllegalArgumentException.class, () -> Presets.saveCustomTo(dir, "Bad Name", List.of(TargetKey.of("minecraft:dirt"))));

        assertTrue(Presets.deleteCustomFrom(dir, "starter_set"));
        assertFalse(Files.exists(savedPath));
        assertNull(Presets.get("starter_set"));
        assertFalse(Presets.deleteCustomFrom(dir, "atm_star"));

        Presets.reloadFrom(dir.resolve("clean"));
    }
}
