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
        Presets.clearDatapackPresets();
    }

    @AfterEach
    void resetPresets(@TempDir Path emptyDir) {
        Presets.reloadFrom(emptyDir);
        Presets.clearDatapackPresets();
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
        // F54: a plain-string name resolves to itself for every language
        assertEquals("My Pack Goals", custom.resolveDisplayName("en_us"));
        assertEquals("My Pack Goals", custom.resolveDisplayName("ja_jp"));
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
        assertEquals("atm_star", atmStar.resolveDisplayName("en_us"));
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
        assertEquals("My Handcrafted Pack", initial.resolveDisplayName("en_us"));
        assertEquals(List.of(
                TargetKey.of("minecraft:dirt"),
                TargetKey.of("#c:ingots/iron"),
                TargetKey.of("#minecraft:logs", 64)
        ), initial.items());

        List<TargetKey> updated = List.of(
                TargetKey.of("minecraft:diamond"),
                TargetKey.of("minecraft:emerald", 16));
        assertTrue(Presets.saveCustomTo(dir, "handcrafted", updated));

        JsonObject savedJson = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals("My Handcrafted Pack", savedJson.get("name").getAsString());
        assertFalse(savedJson.has("extra_field"));
        assertEquals("My Handcrafted Pack", Presets.get("handcrafted").resolveDisplayName("en_us"));
        assertEquals(updated, Presets.get("handcrafted").items());

        Presets.reloadFrom(dir);
        assertEquals("My Handcrafted Pack", Presets.get("handcrafted").resolveDisplayName("en_us"));
        assertEquals(updated, Presets.get("handcrafted").items());

        // Blank, non-string, missing name, or corrupt JSON falls back to preset ID
        Files.writeString(dir.resolve("blank_name.json"), """
                { "name": "   ", "items": ["minecraft:dirt"] }
                """);
        assertTrue(Presets.saveCustomTo(dir, "blank_name", List.of(TargetKey.of("minecraft:stone"))));
        assertEquals("blank_name", Presets.get("blank_name").resolveDisplayName("en_us"));

        Files.writeString(dir.resolve("num_name.json"), """
                { "name": 42, "items": ["minecraft:dirt"] }
                """);
        assertTrue(Presets.saveCustomTo(dir, "num_name", List.of(TargetKey.of("minecraft:stone"))));
        assertEquals("num_name", Presets.get("num_name").resolveDisplayName("en_us"));

        Files.writeString(dir.resolve("corrupt_existing.json"), "{ not json");
        assertTrue(Presets.saveCustomTo(dir, "corrupt_existing", List.of(TargetKey.of("minecraft:stone"))));
        assertEquals("corrupt_existing", Presets.get("corrupt_existing").resolveDisplayName("en_us"));

        Presets.reloadFrom(dir.resolve("clean"));
    }

    @Test
    void keepsNonexistentTagTargetsInCustomPresetsAndRoundTrips(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("tag_pack.json"), """
                {
                  "name": "Tag Pack",
                  "items": [
                    "#nonexistentmod:rare_ores",
                    "#nonexistentmod:rare_ores#100",
                    { "item": "#nonexistentmod:gems", "count": 50 },
                    "#nonexistentmod:rare_ores",
                    "#",
                    "##c:x",
                    "#c:x#",
                    "#c:x#0",
                    "#c:x#abc",
                    "#:x",
                    "#c:",
                    "c:x#1#2",
                    { "item": "#nonexistentmod:bad_count", "count": 0 }
                  ]
                }
                """);

        assertEquals(1, Presets.reloadFrom(dir));
        Presets.PresetDefinition preset = Presets.get("tag_pack");
        assertNotNull(preset);
        List<TargetKey> expected = List.of(
                TargetKey.of("#nonexistentmod:rare_ores"),
                TargetKey.of("#nonexistentmod:rare_ores", 100),
                TargetKey.of("#nonexistentmod:gems", 50)
        );
        assertEquals(expected, preset.items());
        assertTrue(preset.items().get(0).isTag());
        assertEquals("nonexistentmod:rare_ores", preset.items().get(0).tagId());
        assertEquals(100, preset.items().get(1).count());
        assertEquals("nonexistentmod:gems", preset.items().get(2).tagId());

        // Saving and reloading a preset with nonexistent tag targets preserves them cleanly
        assertFalse(Presets.saveCustomTo(dir, "saved_tags", expected));
        JsonObject savedJson = JsonParser.parseString(Files.readString(dir.resolve("saved_tags.json"))).getAsJsonObject();
        JsonArray itemsArr = savedJson.getAsJsonArray("items");
        assertEquals(3, itemsArr.size());
        assertEquals("#nonexistentmod:rare_ores", itemsArr.get(0).getAsString());
        assertEquals("#nonexistentmod:rare_ores", itemsArr.get(1).getAsJsonObject().get("item").getAsString());
        assertEquals(100, itemsArr.get(1).getAsJsonObject().get("count").getAsInt());
        assertEquals("#nonexistentmod:gems", itemsArr.get(2).getAsJsonObject().get("item").getAsString());
        assertEquals(50, itemsArr.get(2).getAsJsonObject().get("count").getAsInt());

        assertEquals(2, Presets.reloadFrom(dir));
        assertEquals(expected, Presets.get("saved_tags").items());

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

    @Test
    void presetProgressCountsLikeF5AndListsUnrecordedInTargetKeyOrder() {
        TargetKey diamond1 = TargetKey.of("minecraft:diamond", 1);
        TargetKey diamond64 = TargetKey.of("minecraft:diamond", 64);
        TargetKey emerald50 = TargetKey.of("minecraft:emerald", 50);
        TargetKey iron1 = TargetKey.of("minecraft:iron_ingot", 1);
        TargetKey missingA = TargetKey.of("missingmod:absent_star", 1);
        TargetKey missingB = TargetKey.of("missingmod:absent_gem", 5);
        Set<String> knownItems = Set.of("minecraft:diamond", "minecraft:emerald", "minecraft:iron_ingot");

        Presets.PresetDefinition preset = new Presets.PresetDefinition("mixed", "Mixed",
                List.of(iron1, emerald50, missingA, diamond64, diamond1, missingB, iron1), false);

        // diamond#64 recorded only as diamond#1 -> exact match, so diamond#64 stays unrecorded
        Set<TargetKey> recorded = Set.of(diamond1, TargetKey.of("minecraft:emerald", 1), missingA);
        Presets.PresetProgress progress = Presets.presetProgress(preset, recorded, knownItems::contains, Presets.PROGRESS_MAX_ROWS);
        assertEquals(1, progress.recorded());
        assertEquals(Presets.resolvableTargets(preset, knownItems::contains).size(), progress.total());
        assertEquals(4, progress.total());
        assertEquals(List.of(diamond64, emerald50, iron1), progress.unrecordedShown());
        assertEquals(0, progress.unrecordedHidden());
        assertEquals(2, progress.skipped());
        assertFalse(progress.complete());

        // All of R(S) recorded -> complete (missing items do not block), skipped still reported
        Set<TargetKey> all = Set.of(diamond1, diamond64, emerald50, iron1);
        Presets.PresetProgress done = Presets.presetProgress(preset, all, knownItems::contains, Presets.PROGRESS_MAX_ROWS);
        assertTrue(done.complete());
        assertEquals(4, done.recorded());
        assertTrue(done.unrecordedShown().isEmpty());
        assertEquals(2, done.skipped());
        // Consistent with F5: recording the last key of R(S) completes the preset
        assertEquals(List.of("mixed"), Presets.findNewlyCompletedPresetIds(
                iron1, Map.of(diamond1, new MilestoneRecord(1, 1L, 1L), diamond64, new MilestoneRecord(1, 1L, 1L),
                        emerald50, new MilestoneRecord(1, 1L, 1L), iron1, new MilestoneRecord(1, 1L, 1L)),
                List.of(preset), knownItems::contains, Set.of(), true));

        // Empty R(S) is never "complete" (0 / 0), and every key counts as skipped
        Presets.PresetDefinition allMissing = new Presets.PresetDefinition("gone", "Gone", List.of(missingA, missingB), false);
        Presets.PresetProgress empty = Presets.presetProgress(allMissing, Set.of(), knownItems::contains, Presets.PROGRESS_MAX_ROWS);
        assertEquals(0, empty.total());
        assertFalse(empty.complete());
        assertEquals(2, empty.skipped());

        // Null preset / null records are tolerated
        assertEquals(0, Presets.presetProgress(null, recorded, knownItems::contains, 50).total());
        assertEquals(0, Presets.presetProgress(preset, null, knownItems::contains, 50).recorded());
    }

    @Test
    void presetProgressCapsListAtFiftyRowsAndReportsRemainder() {
        List<TargetKey> items = new java.util.ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            items.add(TargetKey.of("minecraft:diamond", i));
        }
        Presets.PresetDefinition big = new Presets.PresetDefinition("big", "Big", items, false);
        Set<String> knownItems = Set.of("minecraft:diamond");

        Presets.PresetProgress progress = Presets.presetProgress(
                big, Set.of(TargetKey.of("minecraft:diamond", 1)), knownItems::contains, Presets.PROGRESS_MAX_ROWS);
        assertEquals(50, Presets.PROGRESS_MAX_ROWS);
        assertEquals(1, progress.recorded());
        assertEquals(60, progress.total());
        assertEquals(50, progress.unrecordedShown().size());
        assertEquals(9, progress.unrecordedHidden());
        assertEquals(TargetKey.of("minecraft:diamond", 2), progress.unrecordedShown().get(0));
        assertEquals(TargetKey.of("minecraft:diamond", 51), progress.unrecordedShown().get(49));
        assertEquals(0, progress.skipped());

        // Exactly 50 unrecorded -> no "more" line
        Presets.PresetProgress exact = Presets.presetProgress(
                big, Set.copyOf(items.subList(0, 10)), knownItems::contains, Presets.PROGRESS_MAX_ROWS);
        assertEquals(50, exact.unrecordedShown().size());
        assertEquals(0, exact.unrecordedHidden());
    }

    // --------------------------------------------------------- F21 datapack presets

    @Test
    void resolveDatapackPresetsIgnoresSubfoldersInvalidIdsAndBuiltins() {
        Map<String, Map<String, String>> files = new LinkedHashMap<>();
        // Files in subfolders are ignored so preset IDs can never contain '/'
        files.put("nested_mod", Map.of(
                "deep/inner", "{ \"items\": [\"minecraft:iron_ingot\"] }",
                "ok_nested", "{ \"items\": [\"minecraft:iron_block\"] }"));
        // Invalid, reserved and over-long IDs
        files.put("bad_mod", Map.of(
                "Bad-ID", "{ \"items\": [\"minecraft:dirt\"] }",
                "list", "{ \"items\": [\"minecraft:dirt\"] }",
                "show", "{ \"items\": [\"minecraft:dirt\"] }",
                "diff", "{ \"items\": [\"minecraft:dirt\"] }",
                "share", "{ \"items\": [\"minecraft:dirt\"] }",
                "importcode", "{ \"items\": [\"minecraft:dirt\"] }",
                "way_too_long_preset_id_name_over_32_chars", "{ \"items\": [\"minecraft:dirt\"] }"));
        // Built-in IDs never lose to a datapack file
        files.put("builtin_mod", Map.of("atm_star", "{ \"items\": [\"minecraft:dirt\"] }"));

        Map<String, Presets.PresetDefinition> resolved = Presets.resolveDatapackPresets(files);
        assertEquals(Set.of("ok_nested"), resolved.keySet());
        Presets.PresetDefinition nested = resolved.get("ok_nested");
        assertEquals(Presets.PresetKind.DATAPACK, nested.kind());
        assertFalse(nested.builtin());
        assertEquals(List.of(TargetKey.of("minecraft:iron_block")), nested.items());

        // Null and empty inputs are tolerated
        assertTrue(Presets.resolveDatapackPresets(null).isEmpty());
        assertTrue(Presets.resolveDatapackPresets(Map.of("empty_ns", Map.of())).isEmpty());
    }

    @Test
    void resolveDatapackPresetsResolvesNamespaceCollisionsLexicographically() {
        // Deliberately out of lexicographic order: "aaa_mod" must still win the shared "clash" ID.
        Map<String, Map<String, String>> files = new LinkedHashMap<>();
        files.put("zzz_mod", Map.of(
                "clash", "{ \"name\": \"ZZZ Clash\", \"items\": [\"minecraft:gold_ingot\"] }",
                "unique_z", "{ \"items\": [\"minecraft:gold_block\"] }"));
        files.put("aaa_mod", Map.of(
                "clash", "{ \"name\": \"AAA Clash\", \"items\": [\"minecraft:diamond\"] }",
                "unique_a", "{ \"items\": [\"minecraft:emerald\"] }"));

        Map<String, Presets.PresetDefinition> resolved = Presets.resolveDatapackPresets(files);
        assertEquals(Set.of("clash", "unique_a", "unique_z"), resolved.keySet());
        assertEquals("AAA Clash", resolved.get("clash").resolveDisplayName("en_us"));
        assertEquals(List.of(TargetKey.of("minecraft:diamond")), resolved.get("clash").items());

        // The winner is picked by namespace order even when its file is broken: the loser stays unused.
        Map<String, Map<String, String>> brokenWinner = Map.of(
                "aaa_mod", Map.of("shared", "{ not valid json"),
                "bbb_mod", Map.of("shared", "{ \"items\": [\"minecraft:dirt\"] }"));
        assertTrue(Presets.resolveDatapackPresets(brokenWinner).isEmpty());
    }

    @Test
    void datapackPresetsFollowBuiltinFileDatapackPriority(@TempDir Path dir) throws Exception {
        Presets.reloadFrom(dir);
        Presets.setDatapackPresets(Presets.resolveDatapackPresets(Map.of(
                "pack_mod", Map.of(
                        "pack_only", "{ \"name\": \"Pack Only\", \"items\": [\"minecraft:emerald\"] }",
                        "shared_id", "{ \"name\": \"Pack Shared\", \"items\": [\"minecraft:diamond\"] }",
                        "atm_star", "{ \"items\": [\"minecraft:dirt\"] }"))));

        // A datapack-only ID resolves as a read-only DATAPACK preset
        Presets.PresetDefinition packOnly = Presets.get("pack_only");
        assertNotNull(packOnly);
        assertEquals(Presets.PresetKind.DATAPACK, packOnly.kind());
        assertFalse(packOnly.builtin());
        assertEquals("Pack Only", packOnly.resolveDisplayName("en_us"));
        assertTrue(Presets.isDatapack("pack_only"));
        assertEquals(List.of("pack_only", "shared_id"), Presets.datapackPresetIds());

        // Built-in presets still win over datapack files
        assertEquals(Presets.PresetKind.BUILTIN, Presets.get("atm_star").kind());
        assertEquals(10, Presets.get("atm_star").items().size());

        // A config-folder file with the same ID shadows the datapack preset
        Files.writeString(dir.resolve("shared_id.json"),
                "{ \"name\": \"File Shared\", \"items\": [\"minecraft:iron_ingot\"] }");
        assertEquals(1, Presets.reloadFrom(dir));
        Presets.PresetDefinition shared = Presets.get("shared_id");
        assertEquals(Presets.PresetKind.FILE, shared.kind());
        assertFalse(Presets.isDatapack("shared_id"));
        assertEquals("File Shared", shared.resolveDisplayName("en_us"));
        assertEquals(List.of("pack_only"), Presets.datapackPresetIds());

        // Deleting the config file lets the datapack preset reappear
        assertTrue(Presets.deleteCustomFrom(dir, "shared_id"));
        Presets.PresetDefinition resurfaced = Presets.get("shared_id");
        assertNotNull(resurfaced);
        assertEquals(Presets.PresetKind.DATAPACK, resurfaced.kind());
        assertEquals("Pack Shared", resurfaced.resolveDisplayName("en_us"));

        // preset reload re-reads only the config folder: datapack presets stay loaded
        Presets.reloadFrom(dir);
        assertEquals(Presets.PresetKind.DATAPACK, Presets.get("pack_only").kind());
    }

    @Test
    void datapackOnlyPresetsAreReadOnly(@TempDir Path dir) throws Exception {
        Presets.reloadFrom(dir);
        Presets.setDatapackPresets(Presets.resolveDatapackPresets(Map.of(
                "pack_mod", Map.of("ro_pack", "{ \"name\": \"Read Only\", \"items\": [\"minecraft:diamond\"] }"))));
        assertTrue(Presets.isDatapack("ro_pack"));

        // In-game save/delete cannot overwrite or remove a datapack-only preset
        assertThrows(IllegalArgumentException.class,
                () -> Presets.saveCustomTo(dir, "ro_pack", List.of(TargetKey.of("minecraft:dirt"))));
        assertFalse(Presets.deleteCustomFrom(dir, "ro_pack"));
        assertFalse(Files.exists(dir.resolve("ro_pack.json")));
        assertEquals(Presets.PresetKind.DATAPACK, Presets.get("ro_pack").kind());

        // A hand-placed config file with the same ID wins and is editable as usual
        Files.writeString(dir.resolve("ro_pack.json"), "{ \"items\": [\"minecraft:stone\"] }");
        Presets.reloadFrom(dir);
        assertFalse(Presets.isDatapack("ro_pack"));
        assertEquals(Presets.PresetKind.FILE, Presets.get("ro_pack").kind());
        assertTrue(Presets.saveCustomTo(dir, "ro_pack", List.of(TargetKey.of("minecraft:cobblestone"))));
        assertTrue(Presets.deleteCustomFrom(dir, "ro_pack"));

        Presets.clearDatapackPresets();
        assertNull(Presets.get("ro_pack"));
        assertTrue(Presets.datapackPresetIds().isEmpty());
    }

    // --------------------------------------------------------- F54 per-language names

    @Test
    void objectFormNameParsesPerLanguageEntriesAndResolvesByPriority(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("multi.json"), """
                {
                  "name": {
                    "EN_US": "  Starter Kit  ",
                    "ja_jp": "スターターキット",
                    "fr_fr": "Kit de départ",
                    "": "dropped: blank key",
                    "   ": "dropped: blank key too",
                    "de_de": "   ",
                    "es_es": 42,
                    "pt_br": ["not a string"]
                  },
                  "items": ["minecraft:diamond"]
                }
                """);
        Files.writeString(dir.resolve("first_valid.json"), """
                { "name": { "ja_jp": "日本語", "fr_fr": "Français" }, "items": ["minecraft:diamond"] }
                """);
        Files.writeString(dir.resolve("invalid_only.json"), """
                { "name": { "": "x", "de_de": "   ", "es_es": 42 }, "items": ["minecraft:diamond"] }
                """);
        Files.writeString(dir.resolve("capped.json"), """
                { "name": { "en_us": "%s", "ja_jp": "%s" }, "items": ["minecraft:diamond"] }
                """.formatted("E".repeat(300), "J".repeat(10)));

        assertEquals(4, Presets.reloadFrom(dir));

        // Exact language match wins; keys were normalized to lower case and values trimmed
        Presets.PresetDefinition multi = Presets.get("multi");
        assertNotNull(multi);
        assertEquals("Starter Kit", multi.resolveDisplayName("en_us"));
        assertEquals("Starter Kit", multi.resolveDisplayName("EN_US"));
        assertEquals("スターターキット", multi.resolveDisplayName("ja_jp"));
        assertEquals("Kit de départ", multi.resolveDisplayName("fr_fr"));
        // No match -> en_us priority
        assertEquals("Starter Kit", multi.resolveDisplayName("de_de"));

        // No match and no en_us -> the first valid value in JSON writing order
        Presets.PresetDefinition firstValid = Presets.get("first_valid");
        assertNotNull(firstValid);
        assertEquals("日本語", firstValid.resolveDisplayName("en_us"));
        assertEquals("日本語", firstValid.resolveDisplayName("de_de"));
        assertEquals("Français", firstValid.resolveDisplayName("fr_fr"));

        // An object with no valid entry is the same as no name at all: the ID becomes the name
        Presets.PresetDefinition invalidOnly = Presets.get("invalid_only");
        assertNotNull(invalidOnly);
        assertEquals("invalid_only", invalidOnly.resolveDisplayName("en_us"));
        assertEquals("invalid_only", invalidOnly.resolveDisplayName("ja_jp"));

        // The 256-char cap applies per language value, not to the whole object
        Presets.PresetDefinition capped = Presets.get("capped");
        assertNotNull(capped);
        assertEquals("E".repeat(256), capped.resolveDisplayName("en_us"));
        assertEquals("J".repeat(10), capped.resolveDisplayName("ja_jp"));

        Presets.reloadFrom(dir.resolve("clean"));
    }

    @Test
    void overwritingCustomPresetCarriesAnObjectFormNameOverUnchanged(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("multilang.json");
        Files.writeString(file, """
                {
                  "name": { "en_us": "Starter Kit", "ja_jp": "スターターキット" },
                  "items": ["minecraft:dirt"]
                }
                """);
        assertEquals(1, Presets.reloadFrom(dir));
        assertEquals("Starter Kit", Presets.get("multilang").resolveDisplayName("en_us"));
        assertEquals("スターターキット", Presets.get("multilang").resolveDisplayName("ja_jp"));

        // G2/F54: overwriting carries the existing object-form name over, like the string form
        List<TargetKey> updated = List.of(TargetKey.of("minecraft:diamond"));
        assertTrue(Presets.saveCustomTo(dir, "multilang", updated));

        JsonObject savedJson = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertTrue(savedJson.get("name").isJsonObject());
        JsonObject savedName = savedJson.getAsJsonObject("name");
        assertEquals("Starter Kit", savedName.get("en_us").getAsString());
        assertEquals("スターターキット", savedName.get("ja_jp").getAsString());
        assertEquals("minecraft:diamond", savedJson.getAsJsonArray("items").get(0).getAsString());

        Presets.PresetDefinition after = Presets.get("multilang");
        assertNotNull(after);
        assertEquals("Starter Kit", after.resolveDisplayName("en_us"));
        assertEquals("スターターキット", after.resolveDisplayName("ja_jp"));
        assertEquals(updated, after.items());

        // Round-trip through the file keeps the object name
        Presets.reloadFrom(dir);
        assertEquals("スターターキット", Presets.get("multilang").resolveDisplayName("ja_jp"));

        // An existing object name with only invalid entries is no valid name: the ID is written instead
        Path junk = dir.resolve("junk_name.json");
        Files.writeString(junk, "{ \"name\": { \"\": \"x\", \"de_de\": \"   \" }, \"items\": [\"minecraft:dirt\"] }");
        assertTrue(Presets.saveCustomTo(dir, "junk_name", List.of(TargetKey.of("minecraft:stone"))));
        JsonObject junkJson = JsonParser.parseString(Files.readString(junk)).getAsJsonObject();
        assertEquals("junk_name", junkJson.get("name").getAsString());
        assertEquals("junk_name", Presets.get("junk_name").resolveDisplayName("en_us"));

        Presets.reloadFrom(dir.resolve("clean"));
    }

    // --------------------------------------------------------- F-140-5..11 definition format

    @Test
    void newFormatFieldsDefaultWhenAbsent(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("plain.json"), """
                { "items": ["minecraft:dirt"] }
                """);
        assertEquals(1, Presets.reloadFrom(dir));

        Presets.PresetDefinition plain = Presets.get("plain");
        assertNotNull(plain);
        assertEquals(List.of(), plain.includes());
        assertFalse(plain.description().present());
        assertNull(plain.category());
        assertEquals(0, plain.order());
        assertNull(plain.version());
        assertEquals(List.of(), plain.requiredMods());
        assertEquals(List.of(), plain.groups());
        assertEquals(List.of(), plain.nextPresets());
        assertNull(plain.unlockAfterHours());

        // Built-ins use the same defaults.
        Presets.PresetDefinition builtin = Presets.get("atm_star");
        assertNotNull(builtin);
        assertEquals(List.of(), builtin.includes());
        assertFalse(builtin.description().present());
        assertNull(builtin.category());
        assertEquals(0, builtin.order());
        assertNull(builtin.version());
        assertEquals(List.of(), builtin.requiredMods());
        assertEquals(List.of(), builtin.groups());
        assertEquals(List.of(), builtin.nextPresets());
        assertNull(builtin.unlockAfterHours());

        Presets.reloadFrom(dir.resolve("clean"));
    }

    @Test
    void parsesAllNewFieldsAndExpandsIncludes(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("base.json"), """
                { "items": ["minecraft:diamond"] }
                """);
        Files.writeString(dir.resolve("full.json"), """
                {
                  "name": { "en_us": "Full", "ja_jp": "全部" },
                  "description": { "en_us": "Full description", "ja_jp": "説明" },
                  "category": "  Ores  ",
                  "order": -3,
                  "version": 4,
                  "includes": ["base", "base", "bad id"],
                  "requiredMods": ["create", "create", "Bad Mod"],
                  "groups": [
                    { "id": "ores", "name": { "en_us": "Ores" },
                      "items": ["minecraft:diamond", "minecraft:emerald"] }
                  ],
                  "nextPresets": ["base", "bad id"],
                  "unlockAfterHours": 12.5,
                  "items": ["minecraft:emerald"]
                }
                """);
        assertEquals(2, Presets.reloadFrom(dir));

        Presets.PresetDefinition full = Presets.get("full");
        assertNotNull(full);
        // includes are recursively expanded before the preset's own items, deduped in order
        assertEquals(List.of(TargetKey.of("minecraft:diamond"), TargetKey.of("minecraft:emerald")),
                full.items());
        assertEquals(List.of("base"), full.includes());
        assertEquals("Full description", full.description().resolve("en_us", "full"));
        assertEquals("説明", full.description().resolve("ja_jp", "full"));
        assertEquals("Ores", full.category());
        assertEquals(-3, full.order());
        assertEquals(4, full.version());
        assertEquals(List.of("create"), full.requiredMods());
        assertEquals(List.of("base"), full.nextPresets());
        assertEquals(12.5d, full.unlockAfterHours());
        assertEquals(List.of(full), Presets.allWithUnlockAfterHours());
        assertEquals(1, full.groups().size());
        assertEquals("ores", full.groups().get(0).id());
        assertEquals("Ores", full.groups().get(0).name().resolve("en_us", "ores"));
        assertEquals(List.of(TargetKey.of("minecraft:diamond"), TargetKey.of("minecraft:emerald")),
                full.groups().get(0).items());

        Presets.reloadFrom(dir.resolve("clean"));
        assertTrue(Presets.allWithUnlockAfterHours().isEmpty());
    }

    @Test
    void invalidNewFieldElementsAreSkippedWithoutRejectingThePreset(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("bad_fields.json"), """
                {
                  "items": ["minecraft:dirt"],
                  "order": "first",
                  "version": 0,
                  "category": 42,
                  "includes": ["bad id", 7, true],
                  "requiredMods": ["Bad Mod", 5, null],
                  "groups": "not-an-array",
                  "nextPresets": ["bad id", {}],
                  "unlockAfterHours": -2
                }
                """);
        assertEquals(1, Presets.reloadFrom(dir));

        Presets.PresetDefinition preset = Presets.get("bad_fields");
        assertNotNull(preset);
        assertEquals(List.of(TargetKey.of("minecraft:dirt")), preset.items());
        assertEquals(List.of(), preset.includes());
        assertEquals(0, preset.order());
        assertNull(preset.version());
        assertNull(preset.category());
        assertEquals(List.of(), preset.requiredMods());
        assertEquals(List.of(), preset.groups());
        assertEquals(List.of(), preset.nextPresets());
        assertNull(preset.unlockAfterHours());

        // A valid version 1 is accepted, while 0/negative are unversioned.
        Files.writeString(dir.resolve("v1.json"), """
                { "items": ["minecraft:dirt"], "version": 1 }
                """);
        Files.writeString(dir.resolve("vneg.json"), """
                { "items": ["minecraft:dirt"], "version": -4 }
                """);
        assertEquals(3, Presets.reloadFrom(dir));
        assertEquals(1, Presets.get("v1").version());
        assertNull(Presets.get("vneg").version());

        Presets.reloadFrom(dir.resolve("clean"));
    }

    @Test
    void descriptionAcceptsPlainAndLocalizedForms(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("plain_desc.json"), """
                { "description": "  A plain description  ", "items": ["minecraft:dirt"] }
                """);
        Files.writeString(dir.resolve("localized_desc.json"), """
                {
                  "description": { "EN_US": "Ores", "ja_jp": "鉱石", "de_de": "  ", "es_es": 42 },
                  "items": ["minecraft:dirt"]
                }
                """);
        Files.writeString(dir.resolve("bad_desc.json"), """
                { "description": 42, "items": ["minecraft:dirt"] }
                """);
        assertEquals(3, Presets.reloadFrom(dir));

        assertEquals("A plain description", Presets.get("plain_desc").description().resolve("ja_jp", "plain_desc"));
        assertEquals("Ores", Presets.get("localized_desc").description().resolve("en_us", "localized_desc"));
        assertEquals("鉱石", Presets.get("localized_desc").description().resolve("ja_jp", "localized_desc"));
        assertEquals("Ores", Presets.get("localized_desc").description().resolve("de_de", "localized_desc"));
        assertFalse(Presets.get("bad_desc").description().present());

        Presets.reloadFrom(dir.resolve("clean"));
    }

    @Test
    void includesChainDiamondCycleAndMissingAreResolvedAtLoad(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("leaf.json"), """
                { "items": ["minecraft:iron_ingot"] }
                """);
        Files.writeString(dir.resolve("mid.json"), """
                { "includes": ["leaf"], "items": ["minecraft:gold_ingot"] }
                """);
        Files.writeString(dir.resolve("top.json"), """
                { "includes": ["mid", "ghost"], "items": ["minecraft:diamond"] }
                """);
        Files.writeString(dir.resolve("cycle_a.json"), """
                { "includes": ["cycle_b"], "items": ["minecraft:stone"] }
                """);
        Files.writeString(dir.resolve("cycle_b.json"), """
                { "includes": ["cycle_a"], "items": ["minecraft:dirt"] }
                """);
        assertEquals(5, Presets.reloadFrom(dir));

        assertEquals(List.of(TargetKey.of("minecraft:iron_ingot")), Presets.get("leaf").items());
        assertEquals(List.of(TargetKey.of("minecraft:iron_ingot"), TargetKey.of("minecraft:gold_ingot")),
                Presets.get("mid").items());
        assertEquals(List.of(TargetKey.of("minecraft:iron_ingot"), TargetKey.of("minecraft:gold_ingot"),
                        TargetKey.of("minecraft:diamond")),
                Presets.get("top").items());
        // The cycle branch is cut for each root: cycle_a keeps cycle_b's own item plus its own,
        // and cycle_b keeps cycle_a's own item plus its own.
        assertEquals(List.of(TargetKey.of("minecraft:dirt"), TargetKey.of("minecraft:stone")),
                Presets.get("cycle_a").items());
        assertEquals(List.of(TargetKey.of("minecraft:stone"), TargetKey.of("minecraft:dirt")),
                Presets.get("cycle_b").items());

        Presets.reloadFrom(dir.resolve("clean"));
    }

    @Test
    void presetListOrderUsesOrderAscendingThenId(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("zeta.json"), """
                { "order": -5, "items": ["minecraft:dirt"] }
                """);
        Files.writeString(dir.resolve("alpha.json"), """
                { "items": ["minecraft:dirt"] }
                """);
        Files.writeString(dir.resolve("beta.json"), """
                { "order": 0, "items": ["minecraft:dirt"] }
                """);
        Files.writeString(dir.resolve("gamma.json"), """
                { "order": 10, "items": ["minecraft:dirt"] }
                """);
        assertEquals(4, Presets.reloadFrom(dir));

        List<String> expected = List.of("zeta", "alpha", "atm_star", "beta", "vanilla_endgame", "gamma");
        assertEquals(expected, Presets.names());
        assertEquals(expected, Presets.all().stream().map(Presets.PresetDefinition::id).toList());

        Presets.reloadFrom(dir.resolve("clean"));
    }

    @Test
    void groupsAreValidatedAgainstExpandedItems(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("base_grp.json"), """
                { "items": ["minecraft:diamond", "minecraft:emerald"] }
                """);
        Files.writeString(dir.resolve("group_pack.json"), """
                {
                  "includes": ["base_grp"],
                  "items": ["minecraft:iron_ingot"],
                  "groups": [
                    { "id": "g1", "items": ["minecraft:diamond", "missingmod:nope"] },
                    { "id": "g2", "items": ["minecraft:diamond", "minecraft:emerald"] },
                    { "id": "g3", "items": [] },
                    { "id": "g1", "items": ["minecraft:iron_ingot"] }
                  ]
                }
                """);
        assertEquals(2, Presets.reloadFrom(dir));

        Presets.PresetDefinition pack = Presets.get("group_pack");
        assertNotNull(pack);
        assertEquals(List.of(TargetKey.of("minecraft:diamond"), TargetKey.of("minecraft:emerald"),
                TargetKey.of("minecraft:iron_ingot")), pack.items());
        assertEquals(2, pack.groups().size());
        assertEquals("g1", pack.groups().get(0).id());
        assertEquals(List.of(TargetKey.of("minecraft:diamond")), pack.groups().get(0).items());
        assertEquals("g2", pack.groups().get(1).id());
        assertEquals(List.of(TargetKey.of("minecraft:emerald")), pack.groups().get(1).items());

        Presets.reloadFrom(dir.resolve("clean"));
    }

    @Test
    void saveCustomToCarriesNewFieldsAndRevalidatesGroupsAgainstNewItems(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("base_carry.json"), """
                { "items": ["minecraft:diamond", "minecraft:emerald"] }
                """);
        Path carryFile = dir.resolve("carry.json");
        Files.writeString(carryFile, """
                {
                  "name": { "en_us": "Carry Me" },
                  "description": { "en_us": "Old description", "ja_jp": "古い説明" },
                  "category": "Metals",
                  "order": 7,
                  "version": 3,
                  "includes": ["base_carry"],
                  "requiredMods": ["create"],
                  "groups": [
                    { "id": "keep", "items": ["minecraft:diamond", "minecraft:emerald"] },
                    { "id": "own", "items": ["minecraft:gold_ingot"] }
                  ],
                  "nextPresets": ["base_carry"],
                  "unlockAfterHours": 4.0,
                  "items": ["minecraft:gold_ingot"]
                }
                """);
        assertEquals(2, Presets.reloadFrom(dir));
        assertEquals(List.of(TargetKey.of("minecraft:diamond"), TargetKey.of("minecraft:emerald"),
                TargetKey.of("minecraft:gold_ingot")), Presets.get("carry").items());

        // GUI save equivalent: the player's current targets are just diamond.
        assertTrue(Presets.saveCustomTo(dir, "carry", List.of(TargetKey.of("minecraft:diamond"))));

        JsonObject saved = JsonParser.parseString(Files.readString(carryFile)).getAsJsonObject();
        assertEquals("Carry Me", saved.getAsJsonObject("name").get("en_us").getAsString());
        assertEquals("Old description", saved.getAsJsonObject("description").get("en_us").getAsString());
        assertEquals("Metals", saved.get("category").getAsString());
        assertEquals(7, saved.get("order").getAsInt());
        assertEquals(3, saved.get("version").getAsInt());
        assertEquals("base_carry", saved.getAsJsonArray("includes").get(0).getAsString());
        assertEquals("create", saved.getAsJsonArray("requiredMods").get(0).getAsString());
        assertEquals("base_carry", saved.getAsJsonArray("nextPresets").get(0).getAsString());
        assertEquals(4.0d, saved.get("unlockAfterHours").getAsDouble());
        // The JSON keeps the raw GUI items; includes are still the carried reference.
        assertEquals(1, saved.getAsJsonArray("items").size());
        assertEquals("minecraft:diamond", saved.getAsJsonArray("items").get(0).getAsString());
        // groups are re-validated against the new expanded items: the group pointing at the
        // removed gold item is gone, the diamond/emerald group survives.
        assertEquals(1, saved.getAsJsonArray("groups").size());
        assertEquals("keep", saved.getAsJsonArray("groups").get(0).getAsJsonObject().get("id").getAsString());

        Presets.PresetDefinition afterSave = Presets.get("carry");
        assertNotNull(afterSave);
        assertEquals(List.of(TargetKey.of("minecraft:diamond"), TargetKey.of("minecraft:emerald")),
                afterSave.items());
        assertEquals("Old description", afterSave.description().resolve("en_us", "carry"));
        assertEquals("Metals", afterSave.category());
        assertEquals(7, afterSave.order());
        assertEquals(3, afterSave.version());
        assertEquals(List.of("base_carry"), afterSave.includes());
        assertEquals(List.of("create"), afterSave.requiredMods());
        assertEquals(List.of("base_carry"), afterSave.nextPresets());
        assertEquals(4.0d, afterSave.unlockAfterHours());
        assertEquals(1, afterSave.groups().size());
        assertEquals("keep", afterSave.groups().get(0).id());

        // Reloading from disk produces the same expanded definition.
        Presets.reloadFrom(dir);
        Presets.PresetDefinition afterReload = Presets.get("carry");
        assertEquals(afterSave.items(), afterReload.items());
        assertEquals(afterSave.groups(), afterReload.groups());
        assertEquals(afterSave.description(), afterReload.description());
        assertEquals(afterSave.category(), afterReload.category());
        assertEquals(afterSave.order(), afterReload.order());
        assertEquals(afterSave.version(), afterReload.version());
        assertEquals(afterSave.includes(), afterReload.includes());
        assertEquals(afterSave.requiredMods(), afterReload.requiredMods());
        assertEquals(afterSave.nextPresets(), afterReload.nextPresets());
        assertEquals(afterSave.unlockAfterHours(), afterReload.unlockAfterHours());

        Presets.reloadFrom(dir.resolve("clean"));
    }
    // ------------------------------------------------------------------
    // F-140-21: docs/preset-schema.json
    // ------------------------------------------------------------------

    private static Path presetSchemaPath() {
        // Under Gradle the test JVM's working directory is the NeoForge run directory
        // (for example versions/1.21.1/run), so neither fixed relative path below resolves.
        // Walk up from the real working directory to the filesystem root instead.
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path schema = dir.resolve("docs").resolve("preset-schema.json");
            if (Files.isRegularFile(schema)) {
                return schema;
            }
        }
        // Fallbacks for working directories that are not inside the repository checkout.
        Path fromVersionDir = Path.of("..", "..", "docs", "preset-schema.json").normalize();
        if (Files.isRegularFile(fromVersionDir)) {
            return fromVersionDir;
        }
        Path fromRepoRoot = Path.of("docs", "preset-schema.json");
        if (Files.isRegularFile(fromRepoRoot)) {
            return fromRepoRoot;
        }
        throw new AssertionError("Could not locate docs/preset-schema.json from working directory "
                + Path.of("").toAbsolutePath());
    }

    @Test
    void presetSchemaParsesAndCoversEveryGBFieldAtTheKeySetLevel() throws Exception {
        JsonObject schema = JsonParser.parseString(Files.readString(presetSchemaPath())).getAsJsonObject();
        assertEquals("https://json-schema.org/draft/2020-12/schema", schema.get("$schema").getAsString());
        assertEquals("object", schema.get("type").getAsString());
        assertFalse(schema.get("additionalProperties").getAsBoolean());
        assertNull(schema.get("$ref"), "the schema must be a single file with no external references");

        Set<String> documentedFields = Set.of(
                "$schema", "name", "items", "includes", "description", "category", "order",
                "version", "requiredMods", "groups", "nextPresets", "unlockAfterHours");
        JsonObject properties = schema.getAsJsonObject("properties");
        assertEquals(documentedFields, properties.keySet());
        for (String field : documentedFields) {
            assertTrue(properties.has(field), "schema is missing a property for " + field);
        }

        // A sample using every field must have exactly the schema's root key set.
        String sample = """
                {
                  "$schema": "./preset-schema.json",
                  "name": { "en_us": "Sample", "ja_jp": "サンプル" },
                  "items": [
                    "minecraft:diamond",
                    "#c:ingots/iron#64",
                    { "item": "minecraft:emerald", "count": 5 }
                  ],
                  "includes": ["vanilla_endgame"],
                  "description": "Sample description",
                  "category": "progression",
                  "order": 1,
                  "version": 2,
                  "requiredMods": ["minecraft"],
                  "groups": [
                    { "id": "core", "name": "Core", "items": ["minecraft:diamond"] }
                  ],
                  "nextPresets": ["vanilla_endgame"],
                  "unlockAfterHours": 24
                }
                """;
        JsonObject sampleJson = JsonParser.parseString(sample).getAsJsonObject();
        assertEquals(documentedFields, sampleJson.keySet());

        // The item object form uses the parser's "item" key (not "id"), with count 1..1000000.
        JsonObject itemDefinition = schema.getAsJsonObject("$defs").getAsJsonObject("item");
        assertTrue(itemDefinition.has("oneOf"));
        JsonObject itemObjectForm = itemDefinition.getAsJsonArray("oneOf").get(1).getAsJsonObject();
        assertFalse(itemObjectForm.get("additionalProperties").getAsBoolean());
        assertEquals(Set.of("item", "count"), itemObjectForm.getAsJsonObject("properties").keySet());
        assertTrue(itemObjectForm.getAsJsonArray("required").toString().contains("\"item\""));

        // groups use the same item definition and reject unknown keys.
        JsonObject group = schema.getAsJsonObject("$defs").getAsJsonObject("group");
        assertFalse(group.get("additionalProperties").getAsBoolean());
        assertEquals(Set.of("id", "name", "items"), group.getAsJsonObject("properties").keySet());
    }

    @Test
    void schemaKeyIsAcceptedWithoutValidationProblems(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("with_schema.json"), """
                {
                  "$schema": "../docs/preset-schema.json",
                  "name": "Schema Key",
                  "items": ["minecraft:diamond"]
                }
                """);

        assertEquals(1, Presets.reloadFrom(dir));
        Presets.PresetDefinition preset = Presets.get("with_schema");
        assertNotNull(preset);
        assertEquals(List.of(TargetKey.of("minecraft:diamond")), preset.items());
        assertTrue(preset.problems().isEmpty(), () -> "unexpected problems: " + preset.problems());
    }

    // ------------------------------------------------------------------
    // F-140-20: strictPresetValidation
    // ------------------------------------------------------------------

    @Test
    void strictPresetValidationDefaultsToFalse() {
        assertEquals(Boolean.FALSE, Config.STRICT_PRESET_VALIDATION.getDefault());
        assertFalse(Config.strictPresetValidation());
    }

    @Test
    void strictModeRejectsAPresetWithAnInvalidElementThatLenientModeLoads(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("bad_element.json"), """
                {
                  "name": "Bad Element",
                  "items": [
                    "minecraft:diamond",
                    "not a valid id",
                    { "item": "minecraft:emerald", "count": 0 }
                  ]
                }
                """);

        // Default lenient behavior: skip only the invalid elements and keep the preset.
        assertEquals(1, Presets.reloadFrom(dir));
        Presets.PresetDefinition lenient = Presets.get("bad_element");
        assertNotNull(lenient);
        assertEquals(List.of(TargetKey.of("minecraft:diamond")), lenient.items());
        assertFalse(lenient.problems().isEmpty());

        // Strict behavior: the whole preset is rejected and treated as unknown.
        assertEquals(0, Presets.reloadFrom(dir, true));
        assertNull(Presets.get("bad_element"));
        assertFalse(Presets.names().contains("bad_element"));
    }

    @Test
    void strictModeRejectsIncludesFailuresWhileLenientModeKeepsTheExpandablePart(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("missing_include.json"), """
                {
                  "items": ["minecraft:diamond"],
                  "includes": ["ghost_preset"]
                }
                """);
        Files.writeString(dir.resolve("cycle_a.json"), """
                {
                  "items": ["minecraft:diamond"],
                  "includes": ["cycle_b"]
                }
                """);
        Files.writeString(dir.resolve("cycle_b.json"), """
                {
                  "items": ["minecraft:emerald"],
                  "includes": ["cycle_a"]
                }
                """);

        // Lenient: the missing include is skipped and the cycle branch is cut; all three load.
        assertEquals(3, Presets.reloadFrom(dir));
        assertNotNull(Presets.get("missing_include"));
        assertNotNull(Presets.get("cycle_a"));
        assertNotNull(Presets.get("cycle_b"));
        assertEquals(2, Presets.get("cycle_a").items().size());

        // Strict: a missing include or a cycle rejects every preset whose expansion hit it.
        assertEquals(0, Presets.reloadFrom(dir, true));
        assertNull(Presets.get("missing_include"));
        assertNull(Presets.get("cycle_a"));
        assertNull(Presets.get("cycle_b"));
    }

    @Test
    void strictModeRejectsGroupsFailuresWhileLenientModeDropsOnlyTheGroup(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("bad_groups.json"), """
                {
                  "items": ["minecraft:diamond"],
                  "groups": [
                    { "id": "valid", "items": ["minecraft:diamond"] },
                    { "id": "unknown", "items": ["minecraft:emerald"] }
                  ]
                }
                """);

        // Lenient: the group pointing outside the items is dropped, the preset still loads.
        assertEquals(1, Presets.reloadFrom(dir));
        Presets.PresetDefinition lenient = Presets.get("bad_groups");
        assertNotNull(lenient);
        assertEquals(1, lenient.groups().size());
        assertEquals("valid", lenient.groups().get(0).id());

        // Strict: the groups failure rejects the whole preset.
        assertEquals(0, Presets.reloadFrom(dir, true));
        assertNull(Presets.get("bad_groups"));
    }

    @Test
    void strictModeKeepsFullyValidPresets(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("strict_valid.json"), """
                {
                  "name": { "en_us": "Strict Valid", "ja_jp": "厳格検証OK" },
                  "items": [
                    "minecraft:diamond",
                    { "item": "minecraft:emerald", "count": 5 }
                  ],
                  "includes": ["vanilla_endgame"],
                  "description": "All fields are valid",
                  "category": "progression",
                  "order": 3,
                  "version": 2,
                  "requiredMods": ["minecraft"],
                  "groups": [
                    { "id": "core", "items": ["minecraft:diamond", "minecraft:emerald#5"] }
                  ],
                  "nextPresets": ["vanilla_endgame"],
                  "unlockAfterHours": 12.5
                }
                """);

        assertEquals(1, Presets.reloadFrom(dir, true));
        Presets.PresetDefinition preset = Presets.get("strict_valid");
        assertNotNull(preset);
        assertTrue(preset.problems().isEmpty(), () -> "unexpected problems: " + preset.problems());
        assertEquals(3, preset.order());
        assertEquals(2, preset.version());
        assertEquals(1, preset.groups().size());
        assertEquals("core", preset.groups().get(0).id());
        assertEquals(12.5d, preset.unlockAfterHours());
        assertTrue(preset.items().contains(TargetKey.of("minecraft:emerald", 5)));
    }

    @Test
    void collectedProblemMessagesSubstitutePlaceholderArguments(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("bad_includes.json"), """
                {
                  "items": ["minecraft:dirt"],
                  "includes": "not-an-array"
                }
                """);

        assertEquals(1, Presets.reloadFrom(dir));
        Presets.PresetDefinition preset = Presets.get("bad_includes");
        assertNotNull(preset);
        String expected = "Preset file bad_includes.json has a non-array 'includes'; ignoring it";
        assertTrue(preset.problems().contains(expected),
                () -> "expected a fully substituted problem, got: " + preset.problems());
        assertFalse(preset.problems().stream().anyMatch(problem -> problem.contains("{}")),
                () -> "placeholder left unsubstituted: " + preset.problems());
    }

    @Test
    void collectedProblemSubstitutionDoesNotRescanArgumentText(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("brace_id.json"), """
                {
                  "items": ["minecraft:dirt"],
                  "includes": ["{}"]
                }
                """);

        assertEquals(1, Presets.reloadFrom(dir));
        Presets.PresetDefinition preset = Presets.get("brace_id");
        assertNotNull(preset);
        assertTrue(preset.problems().contains("Skipping invalid 'includes' ID '{}' in preset brace_id.json"),
                () -> "argument text must not be re-scanned for placeholders: " + preset.problems());
    }


    @Test
    void findNewlyCompletedPresetsPrefiltersPresetsWithoutTheRecordedItem() {
        TargetKey diamond = TargetKey.of("minecraft:diamond");
        TargetKey emerald = TargetKey.of("minecraft:emerald");
        MilestoneRecord rec = new MilestoneRecord(1200, 1L, 1_700_000_000_000L);
        Presets.PresetDefinition diamondPreset = new Presets.PresetDefinition(
                "diamond_pack", "Diamond Pack", List.of(diamond), true);
        Presets.PresetDefinition emeraldPreset = new Presets.PresetDefinition(
                "emerald_pack", "Emerald Pack", List.of(emerald), true);
        java.util.concurrent.atomic.AtomicInteger registryChecks =
                new java.util.concurrent.atomic.AtomicInteger();

        List<Presets.PresetDefinition> completed = Presets.findNewlyCompletedPresets(
                diamond,
                Map.of(diamond, rec),
                List.of(diamondPreset, emeraldPreset),
                id -> {
                    registryChecks.incrementAndGet();
                    return true;
                },
                Set.of(),
                true);

        assertEquals(List.of(diamondPreset), completed);
        assertEquals(1, registryChecks.get(),
                "presets that cannot contain the recorded key must not be registry-scanned");
    }

    // SF-4: pathological nesting is rejected before Gson's recursive tree builder can overflow.
    @Test
    void deeplyNestedPresetJsonIsRejectedInsteadOfEscapingStackOverflow() {
        String json = "{\"items\":[\"minecraft:dirt\"],\"junk\":"
                + "[".repeat(6_000) + "]".repeat(6_000) + "}";
        assertNull(Presets.parsePresetContent(json, "deep.json", "deep", Presets.PresetKind.FILE));
    }

    @Test
    void deeplyNestedExistingPresetFileCannotBeSilentlyOverwritten(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("deep_existing.json");
        String json = "{\"name\":\"Deep\",\"items\":[\"minecraft:dirt\"],\"junk\":"
                + "[".repeat(6_000) + "]".repeat(6_000) + "}";
        java.nio.file.Files.writeString(file, json);
        assertThrows(java.io.IOException.class,
                () -> Presets.saveCustomTo(dir, "deep_existing", List.of(TargetKey.of("minecraft:diamond")), false));
        assertEquals(json, java.nio.file.Files.readString(file), "unreadable metadata must not be overwritten");
    }

    // SF-6: JSON Schema integers include integral decimals and exponents.
    @Test
    void integralJsonNumbersAreAcceptedInBothValidationModes(@TempDir Path dir) throws Exception {
        java.nio.file.Files.writeString(dir.resolve("integral.json"), """
                {
                  "version": 1.0,
                  "order": -2e0,
                  "items": [
                    {"item": "minecraft:diamond", "count": 3.0},
                    {"item": "minecraft:emerald", "count": 4e0}
                  ]
                }
                """);
        List<TargetKey> expected = List.of(TargetKey.of("minecraft:diamond", 3), TargetKey.of("minecraft:emerald", 4));
        for (boolean strict : List.of(false, true)) {
            assertEquals(1, Presets.reloadFrom(dir, strict));
            Presets.PresetDefinition preset = Presets.get("integral");
            assertNotNull(preset);
            assertEquals(Integer.valueOf(1), preset.version());
            assertEquals(-2, preset.order());
            assertEquals(expected, preset.items());
            assertTrue(preset.problems().isEmpty(), () -> "integral numbers must not be rejected: " + preset.problems());
        }
        Presets.reloadFrom(dir.resolve("clean"));
    }

    // SF-12: a directory error must keep the already loaded custom presets in memory.
    @Test
    void directoryListingFailureKeepsPreviouslyLoadedPresets(@TempDir Path dir) throws Exception {
        java.nio.file.Files.writeString(dir.resolve("kept.json"), """
                { "items": ["minecraft:diamond"] }
                """);
        assertEquals(1, Presets.reloadFrom(dir));
        assertNotNull(Presets.get("kept"));

        Path notDirectory = dir.resolve("not-a-directory");
        java.nio.file.Files.writeString(notDirectory, "regular file");
        assertEquals(0, Presets.reloadFrom(notDirectory));
        assertNotNull(Presets.get("kept"), "a transient directory failure must not clear in-memory presets");
        assertEquals(List.of("atm_star", "kept", "vanilla_endgame"), Presets.names());

        Presets.reloadFrom(dir.resolve("clean"));
    }

    // SF-13: an unreadable file must make the save fail instead of silently overwriting it.
    @Test
    void saveWithUnreadableExistingFileAbortsInsteadOfOverwriting(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("unreadable.json");
        java.nio.file.Files.createDirectory(target);
        assertThrows(java.io.IOException.class,
                () -> Presets.saveCustomTo(dir, "unreadable", List.of(TargetKey.of("minecraft:diamond"))));
        assertTrue(java.nio.file.Files.isDirectory(target), "the existing file must be left untouched");
        Presets.reloadFrom(dir.resolve("clean"));
    }

    // SF-14: strict validation must not report success for a preset the next rebuild drops.
    @Test
    void strictSaveRejectsProblemsBeforeWritingAndLenientSaveStillWorks(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("strict_save.json");
        String original = """
                {"name":"Kept","items":["minecraft:dirt"],"includes":["missing_include"]}
                """;
        java.nio.file.Files.writeString(file, original);
        assertEquals(1, Presets.reloadFrom(dir, false));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Presets.saveCustomTo(dir, "strict_save", List.of(TargetKey.of("minecraft:diamond")), true));
        assertTrue(failure.getMessage().contains("missing_include"), () -> "collected problem not surfaced: " + failure.getMessage());
        assertEquals(original, java.nio.file.Files.readString(file), "a rejected strict save must not write the file");

        assertTrue(Presets.saveCustomTo(dir, "strict_save", List.of(TargetKey.of("minecraft:diamond")), false));
        assertEquals(0, Presets.reloadFrom(dir, true), "the lenient save's stale include still fails strict reload");
        Presets.reloadFrom(dir.resolve("clean"));
    }

    @Test
    void strictImportRejectsProblemsBeforeWritingAndLenientImportStillWorks(@TempDir Path dir) throws Exception {
        Presets.PresetDefinition definition = new Presets.PresetDefinition(
                "strict_import", PresetName.ofPlain("Strict Import"),
                List.of(TargetKey.of("minecraft:diamond")), Presets.PresetKind.FILE,
                List.of("missing_include"), PresetName.absent(), null, 0, null,
                List.of(), List.of(), List.of(), null);
        Path file = dir.resolve("strict_import.json");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Presets.saveImportedTo(dir, definition, true));
        assertTrue(failure.getMessage().contains("missing_include"), () -> "collected problem not surfaced: " + failure.getMessage());
        assertFalse(java.nio.file.Files.exists(file), "a rejected strict import must not write the file");
        assertNull(Presets.get("strict_import"));

        assertFalse(Presets.saveImportedTo(dir, definition, false));
        assertTrue(java.nio.file.Files.exists(file));
        Presets.reloadFrom(dir.resolve("clean"));
    }

}
