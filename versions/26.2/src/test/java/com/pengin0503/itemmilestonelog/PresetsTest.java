package com.pengin0503.itemmilestonelog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;

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

    @Test
    void overwritingCustomPresetPreservesDisplayNameAndDropsUnknownFields(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("handcrafted.json");
        Files.writeString(file, """
                {
                  "name": "My Handcrafted Pack",
                  "extra_field": "should_not_be_kept",
                  "items": [
                    "minecraft:dirt",
                    "#c:ingots/iron",
                    { "item": "#minecraft:logs", "count": 64 }
                  ]
                }
                """);
        assertEquals(1, Presets.reloadFrom(dir));
        Presets.PresetDefinition initial = Presets.get("handcrafted");
        assertNotNull(initial);
        assertEquals("My Handcrafted Pack", initial.displayName());
        assertEquals(List.of(TargetKey.of("minecraft:dirt")), initial.items());

        List<TargetKey> updated = List.of(
                TargetKey.of("minecraft:diamond"),
                TargetKey.of("minecraft:emerald", 16));
        assertTrue(Presets.saveCustomTo(dir, "handcrafted", updated));

        JsonObject savedJson = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals("My Handcrafted Pack", savedJson.get("name").getAsString());
        assertFalse(savedJson.has("extra_field"));
        assertEquals("My Handcrafted Pack", Presets.get("handcrafted").displayName());
        assertEquals(updated, Presets.get("handcrafted").items());

        Presets.reloadFrom(dir);
        assertEquals("My Handcrafted Pack", Presets.get("handcrafted").displayName());
        assertEquals(updated, Presets.get("handcrafted").items());

        // Blank, non-string, missing name, or corrupt JSON falls back to preset ID
        Files.writeString(dir.resolve("blank_name.json"), """
                { "name": "   ", "items": ["minecraft:dirt"] }
                """);
        assertTrue(Presets.saveCustomTo(dir, "blank_name", List.of(TargetKey.of("minecraft:stone"))));
        assertEquals("blank_name", Presets.get("blank_name").displayName());

        Files.writeString(dir.resolve("num_name.json"), """
                { "name": 42, "items": ["minecraft:dirt"] }
                """);
        assertTrue(Presets.saveCustomTo(dir, "num_name", List.of(TargetKey.of("minecraft:stone"))));
        assertEquals("num_name", Presets.get("num_name").displayName());

        Files.writeString(dir.resolve("corrupt_existing.json"), "{ not json");
        assertTrue(Presets.saveCustomTo(dir, "corrupt_existing", List.of(TargetKey.of("minecraft:stone"))));
        assertEquals("corrupt_existing", Presets.get("corrupt_existing").displayName());

        Presets.reloadFrom(dir.resolve("clean"));
    }

    @Test
    void findNewlyCompletedPresetIdsEnforcesAllJudgmentRules() {
        TargetKey diamond1 = TargetKey.of("minecraft:diamond", 1);
        TargetKey diamond100 = TargetKey.of("minecraft:diamond", 100);
        TargetKey emerald1 = TargetKey.of("minecraft:emerald", 1);
        TargetKey emerald50 = TargetKey.of("minecraft:emerald", 50);
        TargetKey iron1 = TargetKey.of("minecraft:iron_ingot", 1);
        TargetKey missingModItem = TargetKey.of("missingmod:absent_star", 1);

        Presets.PresetDefinition allMissingPreset = new Presets.PresetDefinition(
                "all_missing", "All Missing", List.of(missingModItem), false);
        Presets.PresetDefinition mixedPreset = new Presets.PresetDefinition(
                "mixed_pack", "Mixed Pack", List.of(diamond1, emerald50, missingModItem), false);
        Presets.PresetDefinition bulkDiamondPreset = new Presets.PresetDefinition(
                "bulk_diamond", "Bulk Diamond", List.of(diamond100), false);
        Presets.PresetDefinition starterPreset = new Presets.PresetDefinition(
                "starter", "Starter", List.of(iron1), false);
        Presets.PresetDefinition dualPreset = new Presets.PresetDefinition(
                "diamond_plus_iron", "Diamond Plus Iron", List.of(diamond1, iron1), false);

        List<Presets.PresetDefinition> presets = List.of(
                allMissingPreset, mixedPreset, bulkDiamondPreset, starterPreset, dualPreset);
        Set<String> knownItems = Set.of("minecraft:diamond", "minecraft:emerald", "minecraft:iron_ingot");

        // R(S) calculation filters out nonexistent items and yields empty for allMissingPreset
        assertTrue(Presets.resolvableTargets(allMissingPreset, knownItems::contains).isEmpty());
        assertEquals(List.of(diamond1, emerald50),
                Presets.resolvableTargets(mixedPreset, knownItems::contains));

        MilestoneRecord dummyRecord = new MilestoneRecord(100, 1L, 1_700_000_000_000L);
        Map<TargetKey, MilestoneRecord> records = new LinkedHashMap<>();

        // 1. Empty R(S): recording an item never completes a preset whose R(S) is empty
        records.put(iron1, dummyRecord);
        assertEquals(List.of("starter"),
                Presets.findNewlyCompletedPresetIds(iron1, records, presets, knownItems::contains, Set.of(), true));
        assertFalse(Presets.findNewlyCompletedPresetIds(
                iron1, records, List.of(allMissingPreset), knownItems::contains, Set.of(), true)
                .contains("all_missing"));
        // Even if the player somehow had a record for missingModItem, it's not in R(S) so R(S) is still empty
        Map<TargetKey, MilestoneRecord> withMissingRec = Map.of(missingModItem, dummyRecord);
        assertTrue(Presets.findNewlyCompletedPresetIds(
                missingModItem, withMissingRec, List.of(allMissingPreset), knownItems::contains, Set.of(), true)
                .isEmpty());

        // 2. Count mismatch: recording diamond#100 completes bulk_diamond, NOT mixed_pack (needs diamond#1)
        //    and recording emerald#1 does not satisfy emerald#50
        records.put(diamond100, dummyRecord);
        records.put(emerald1, dummyRecord);
        assertEquals(List.of("bulk_diamond"),
                Presets.findNewlyCompletedPresetIds(diamond100, records, presets, knownItems::contains, Set.of("starter"), true));
        assertTrue(Presets.findNewlyCompletedPresetIds(emerald1, records, presets, knownItems::contains, Set.of("starter", "bulk_diamond"), true)
                .isEmpty());

        // 3. K not in preset: starter is already satisfied in records, but recording diamond#1 (not in starter)
        //    must NOT report starter even if starter is not in alreadyCompletedIds
        records.put(diamond1, dummyRecord);
        assertEquals(List.of("diamond_plus_iron"),
                Presets.findNewlyCompletedPresetIds(diamond1, records, presets, knownItems::contains, Set.of(), true));

        // 4. Nonexistent items excluded from R(S): now recording emerald#50 completes mixed_pack
        //    even though missingmod:absent_star is not recorded
        records.put(emerald50, dummyRecord);
        assertEquals(List.of("mixed_pack"),
                Presets.findNewlyCompletedPresetIds(emerald50, records, presets, knownItems::contains, Set.of("diamond_plus_iron"), true));

        // 5. Already completed: if mixed_pack is already in completedPresets, it is not returned again
        assertTrue(Presets.findNewlyCompletedPresetIds(
                emerald50, records, presets, knownItems::contains, Set.of("mixed_pack"), true).isEmpty());

        // 6. presetsEnabled = false: always returns empty list
        assertTrue(Presets.findNewlyCompletedPresetIds(
                emerald50, records, presets, knownItems::contains, Set.of(), false).isEmpty());
    }
}
