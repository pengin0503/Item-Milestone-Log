package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.api.WorldFirstRecord;
import com.pengin0503.itemmilestonelog.network.PresetEntry;
import com.pengin0503.itemmilestonelog.network.SyncMilestonesPayload;
import com.pengin0503.itemmilestonelog.network.TargetEntry;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MilestoneServiceTest {
    private static final UUID PLAYER_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Function<TargetKey, Component> LITERAL_NAME = key -> Component.literal(key.formatDisplayId());

    @BeforeEach
    void initCleanPresets(@TempDir Path emptyDir) {
        Presets.reloadFrom(emptyDir);
    }

    @AfterEach
    void resetPresets(@TempDir Path emptyDir) {
        Presets.reloadFrom(emptyDir);
    }

    @Test
    void isPrivilegedRestrictsLanGuestsWithoutCheatsWhileAllowingHostAndAdmins() {
        // Integrated server: host allowed without cheats, LAN guest rejected unless level >= 2
        assertTrue(PrivilegePolicy.isPrivileged(false, true, true, false));
        assertTrue(PrivilegePolicy.isPrivileged(false, true, true, true));
        assertFalse(PrivilegePolicy.isPrivileged(false, true, false, false));
        assertTrue(PrivilegePolicy.isPrivileged(false, true, false, true));

        // Dedicated server: admin permission required
        assertFalse(PrivilegePolicy.isPrivileged(true, true, false, false));
        assertTrue(PrivilegePolicy.isPrivileged(true, true, false, true));

        // Non-player sources (console/RCON/command block): admin permission required
        assertFalse(PrivilegePolicy.isPrivileged(false, false, false, false));
        assertTrue(PrivilegePolicy.isPrivileged(false, false, false, true));
        assertFalse(PrivilegePolicy.isPrivileged(true, false, false, false));
        assertTrue(PrivilegePolicy.isPrivileged(true, false, false, true));
    }

    @Test
    void addTargetValidatesCountItemNormalizationDuplicatesAndLimit() {
        MilestoneData data = new MilestoneData();
        Set<String> knownItems = Set.of("minecraft:diamond", "minecraft:iron_ingot");

        // Invalid counts
        assertFalse(MilestoneService.addTarget(data, PLAYER_A, null, "minecraft:diamond", 0, knownItems::contains, LITERAL_NAME).success());
        assertFalse(MilestoneService.addTarget(data, PLAYER_A, null, "minecraft:diamond", -5, knownItems::contains, LITERAL_NAME).success());
        assertFalse(MilestoneService.addTarget(data, PLAYER_A, null, "minecraft:diamond", TargetKey.MAX_COUNT + 1, knownItems::contains, LITERAL_NAME).success());

        // Invalid / unknown / air item IDs
        assertFalse(MilestoneService.addTarget(data, PLAYER_A, null, null, 1, knownItems::contains, LITERAL_NAME).success());
        assertFalse(MilestoneService.addTarget(data, PLAYER_A, null, "", 1, knownItems::contains, LITERAL_NAME).success());
        assertFalse(MilestoneService.addTarget(data, PLAYER_A, null, "minecraft:air", 1, knownItems::contains, LITERAL_NAME).success());
        assertFalse(MilestoneService.addTarget(data, PLAYER_A, null, "minecraft:unknown", 1, knownItems::contains, LITERAL_NAME).success());
        assertNull(data.playerOrNull(PLAYER_A), "failed validation must not create PlayerData");

        // Un-namespaced ID normalizes to minecraft:<id>
        MilestoneService.ActionResult added = MilestoneService.addTarget(
                data, PLAYER_A, null, "diamond", 1, knownItems::contains, LITERAL_NAME);
        assertTrue(added.success());
        assertTrue(data.playerOrNull(PLAYER_A).targets.contains(TargetKey.of("minecraft:diamond")));

        // Duplicate rejected
        MilestoneService.ActionResult dup = MilestoneService.addTarget(
                data, PLAYER_A, null, "minecraft:diamond", 1, knownItems::contains, LITERAL_NAME);
        assertFalse(dup.success());
        assertTrue(dup.message().getString().contains("Already tracking"));

        // Different quantity of the same item is allowed
        MilestoneService.ActionResult qty = MilestoneService.addTarget(
                data, PLAYER_A, null, "minecraft:diamond", 64, knownItems::contains, LITERAL_NAME);
        assertTrue(qty.success());
        assertTrue(data.playerOrNull(PLAYER_A).targets.contains(TargetKey.of("minecraft:diamond", 64)));

        // Enforces MAX_PERSONAL_TARGETS limit (1024)
        MilestoneData.PlayerData pd = data.player(PLAYER_A);
        for (int i = pd.targets.size(); i < MilestoneService.MAX_PERSONAL_TARGETS; i++) {
            pd.targets.add(TargetKey.of("minecraft:filler_" + i));
        }
        assertEquals(MilestoneService.MAX_PERSONAL_TARGETS, pd.targets.size());
        MilestoneService.ActionResult overLimit = MilestoneService.addTarget(
                data, PLAYER_A, null, "minecraft:iron_ingot", 1, knownItems::contains, LITERAL_NAME);
        assertFalse(overLimit.success());
        assertTrue(overLimit.message().getString().contains(String.valueOf(MilestoneService.MAX_PERSONAL_TARGETS)));
        assertFalse(pd.targets.contains(TargetKey.of("minecraft:iron_ingot")));
    }

    @Test
    void removeTargetAllowsRemovingUninstalledItemsAndHandlesMissingTargets() {
        MilestoneData data = new MilestoneData();
        Set<String> knownItems = Set.of("minecraft:diamond");

        // Invalid count / invalid syntax
        assertFalse(MilestoneService.removeTarget(data, PLAYER_A, null, "minecraft:diamond", 0, knownItems::contains, LITERAL_NAME).success());
        assertFalse(MilestoneService.removeTarget(data, PLAYER_A, null, "bad item", 1, knownItems::contains, LITERAL_NAME).success());

        // Missing player data: unknown item -> invalid_item, known item -> not_target; never creates PlayerData
        MilestoneService.ActionResult missingUnknown = MilestoneService.removeTarget(
                data, PLAYER_A, null, "othermod:missing", 1, knownItems::contains, LITERAL_NAME);
        assertFalse(missingUnknown.success());
        assertTrue(missingUnknown.message().getString().contains("Invalid or unknown item ID"));
        MilestoneService.ActionResult missingKnown = MilestoneService.removeTarget(
                data, PLAYER_A, null, "minecraft:diamond", 1, knownItems::contains, LITERAL_NAME);
        assertFalse(missingKnown.success());
        assertTrue(missingKnown.message().getString().contains("Not a personal target"));
        assertNull(data.playerOrNull(PLAYER_A));

        // Existing target from an uninstalled mod (not in knownItems) can still be removed
        MilestoneData.PlayerData pd = data.player(PLAYER_A);
        pd.targets.add(TargetKey.of("uninstalled:relic", 5));
        pd.targets.add(TargetKey.of("minecraft:diamond"));
        MilestoneService.ActionResult removedModItem = MilestoneService.removeTarget(
                data, PLAYER_A, null, "uninstalled:relic", 5, knownItems::contains, LITERAL_NAME);
        assertTrue(removedModItem.success());
        assertFalse(pd.targets.contains(TargetKey.of("uninstalled:relic", 5)));

        // Un-namespaced ID removes normalized minecraft:<id> target
        MilestoneService.ActionResult removedNormalized = MilestoneService.removeTarget(
                data, PLAYER_A, null, "diamond", 1, knownItems::contains, LITERAL_NAME);
        assertTrue(removedNormalized.success());
        assertTrue(pd.targets.isEmpty());
    }

    // ---- F32: write API (addTarget / removeTarget) ----

    @Test
    void addTargetFromApiValidatesLikeAddCommandAndCreatesPlayerData() {
        MilestoneData data = new MilestoneData();
        Set<String> knownItems = Set.of("minecraft:diamond", "minecraft:iron_ingot");

        // null target -> false, no PlayerData
        assertFalse(MilestoneService.addTargetFromApi(data, PLAYER_A, null, knownItems::contains));
        // unknown item / air -> false, no PlayerData
        assertFalse(MilestoneService.addTargetFromApi(data, PLAYER_A, TargetKey.of("minecraft:unknown"), knownItems::contains));
        assertFalse(MilestoneService.addTargetFromApi(data, PLAYER_A, TargetKey.of("minecraft:air"), knownItems::contains));
        assertNull(data.playerOrNull(PLAYER_A));

        // Success creates PlayerData (F22); un-namespaced key is normalized
        assertTrue(MilestoneService.addTargetFromApi(data, PLAYER_A, TargetKey.of("diamond", 64), knownItems::contains));
        assertNotNull(data.playerOrNull(PLAYER_A));
        assertTrue(data.playerOrNull(PLAYER_A).targets.contains(TargetKey.of("minecraft:diamond", 64)));
        assertTrue(data.isDirty());

        // Duplicate -> false
        assertFalse(MilestoneService.addTargetFromApi(data, PLAYER_A, TargetKey.of("minecraft:diamond", 64), knownItems::contains));

        // 1024 cap
        MilestoneData.PlayerData pd = data.player(PLAYER_A);
        for (int i = pd.targets.size(); i < MilestoneService.MAX_PERSONAL_TARGETS; i++) {
            pd.targets.add(TargetKey.of("minecraft:filler_" + i));
        }
        assertFalse(MilestoneService.addTargetFromApi(data, PLAYER_A, TargetKey.of("minecraft:iron_ingot"), knownItems::contains));
        assertFalse(pd.targets.contains(TargetKey.of("minecraft:iron_ingot")));
        assertEquals(MilestoneService.MAX_PERSONAL_TARGETS, pd.targets.size());
    }

    @Test
    void removeTargetFromApiCleansUpProgressAndProtectsDefaultTargets() {
        MilestoneData data = new MilestoneData();
        TargetKey diamond = TargetKey.of("minecraft:diamond", 100);
        TargetKey defaultKey = TargetKey.of("minecraft:iron_ingot");
        Set<TargetKey> defaults = Set.of(defaultKey);

        // null target / no PlayerData -> false, never creates PlayerData
        assertFalse(MilestoneService.removeTargetFromApi(data, PLAYER_A, null, defaults));
        assertFalse(MilestoneService.removeTargetFromApi(data, PLAYER_A, diamond, defaults));
        assertNull(data.playerOrNull(PLAYER_A));

        MilestoneData.PlayerData pd = data.player(PLAYER_A);
        pd.targets.add(diamond);
        pd.targets.add(defaultKey);
        pd.progressNotified.put(diamond, 50);
        pd.progressNotified.put(defaultKey, 50);
        MilestoneRecord rec = new MilestoneRecord(10, 1L, 2L);
        pd.records.put(diamond, rec);

        // defaultTargets keys cannot be removed (also when given un-namespaced)
        assertFalse(MilestoneService.removeTargetFromApi(data, PLAYER_A, defaultKey, defaults));
        assertFalse(MilestoneService.removeTargetFromApi(data, PLAYER_A, TargetKey.of("iron_ingot"), defaults));
        assertTrue(pd.targets.contains(defaultKey));
        assertEquals(50, pd.progressNotified.get(defaultKey));

        // Personal target removed with §1.1 cleanup: progressNotified dropped, record kept
        assertTrue(MilestoneService.removeTargetFromApi(data, PLAYER_A, diamond, defaults));
        assertFalse(pd.targets.contains(diamond));
        assertFalse(pd.progressNotified.containsKey(diamond));
        assertEquals(rec, pd.records.get(diamond));

        // Not a personal target any more -> false
        assertFalse(MilestoneService.removeTargetFromApi(data, PLAYER_A, diamond, defaults));
    }

    @Test
    void applyPresetSkipsMissingItemsDuplicatesAndRespectsTargetLimit(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("mixed_pack.json"), """
                {
                  "name": "Mixed Pack",
                  "items": [
                    "minecraft:diamond",
                    {"item": "minecraft:diamond", "count": 64},
                    "minecraft:iron_ingot",
                    "missingmod:absent_item",
                    "minecraft:emerald"
                  ]
                }
                """);
        assertEquals(1, Presets.reloadFrom(dir));

        MilestoneData data = new MilestoneData();
        Set<String> knownItems = Set.of("minecraft:diamond", "minecraft:iron_ingot", "minecraft:emerald");

        // Unknown preset fails
        assertFalse(MilestoneService.applyPreset(data, PLAYER_A, null, "no_such_preset", knownItems::contains).success());

        // Pre-track minecraft:diamond so it is skipped as a duplicate
        MilestoneData.PlayerData pd = data.player(PLAYER_A);
        pd.targets.add(TargetKey.of("minecraft:diamond"));

        MilestoneService.ActionResult applied = MilestoneService.applyPreset(
                data, PLAYER_A, null, "mixed_pack", knownItems::contains);
        assertTrue(applied.success());
        assertTrue(applied.message().getString().contains("3 targets added (1 with quantity), 1 skipped"),
                () -> "unexpected message: " + applied.message().getString());
        assertEquals(Set.of(
                TargetKey.of("minecraft:diamond"),
                TargetKey.of("minecraft:diamond", 64),
                TargetKey.of("minecraft:iron_ingot"),
                TargetKey.of("minecraft:emerald")
        ), pd.targets);

        // Near MAX_PERSONAL_TARGETS: only 1 slot left, so 2 remaining known items are skipped due to limit
        MilestoneData limitedData = new MilestoneData();
        MilestoneData.PlayerData limitedPd = limitedData.player(PLAYER_A);
        for (int i = 0; i < MilestoneService.MAX_PERSONAL_TARGETS - 1; i++) {
            limitedPd.targets.add(TargetKey.of("minecraft:filler_" + i));
        }
        MilestoneService.ActionResult limitResult = MilestoneService.applyPreset(
                limitedData, PLAYER_A, null, "mixed_pack", knownItems::contains);
        assertTrue(limitResult.success());
        assertEquals(MilestoneService.MAX_PERSONAL_TARGETS, limitedPd.targets.size());
        assertTrue(limitResult.message().getString().contains("3 more target(s) were skipped because your target list is full"),
                () -> "unexpected message: " + limitResult.message().getString());
    }

    @Test
    void saveAndDeletePresetEnforcePermissionsIdsBuiltinsAndTargets(@TempDir Path dir) {
        Presets.reloadFrom(dir);
        MilestoneData data = new MilestoneData();

        // Permission denied
        assertFalse(MilestoneService.savePreset(data, PLAYER_A, null, false, "my_set", () -> dir).success());
        assertFalse(MilestoneService.deletePreset(null, false, "my_set", () -> dir).success());

        // Invalid / reserved / built-in IDs
        assertFalse(MilestoneService.savePreset(data, PLAYER_A, null, true, "Bad Name", () -> dir).success());
        assertFalse(MilestoneService.savePreset(data, PLAYER_A, null, true, "save", () -> dir).success());
        assertFalse(MilestoneService.savePreset(data, PLAYER_A, null, true, "atm_star", () -> dir).success());
        assertFalse(MilestoneService.deletePreset(null, true, "atm_star", () -> dir).success());

        // No personal targets
        assertFalse(MilestoneService.savePreset(data, PLAYER_A, null, true, "my_set", () -> dir).success());

        // Save, overwrite, then delete
        MilestoneData.PlayerData pd = data.player(PLAYER_A);
        pd.targets.add(TargetKey.of("minecraft:diamond"));
        pd.targets.add(TargetKey.of("minecraft:iron_ingot", 32));

        MilestoneService.ActionResult saved = MilestoneService.savePreset(data, PLAYER_A, null, true, "  my_set  ", () -> dir);
        assertTrue(saved.success());
        assertTrue(saved.message().getString().contains("Saved preset 'my_set' with 2 target(s)"));
        assertNotNull(Presets.get("my_set"));
        assertTrue(Files.isRegularFile(dir.resolve("my_set.json")));

        MilestoneService.ActionResult overwritten = MilestoneService.savePreset(data, PLAYER_A, null, true, "my_set", () -> dir);
        assertTrue(overwritten.success());
        assertTrue(overwritten.message().getString().contains("Overwrote preset 'my_set' with 2 target(s)"));

        MilestoneService.ActionResult deleted = MilestoneService.deletePreset(null, true, "my_set", () -> dir);
        assertTrue(deleted.success());
        assertNull(Presets.get("my_set"));
        assertFalse(Files.exists(dir.resolve("my_set.json")));

        // Deleting an already-deleted preset fails as unknown
        assertFalse(MilestoneService.deletePreset(null, true, "my_set", () -> dir).success());
    }

    @Test
    void resetRecordAndResetAllClearRecordsWhileKeepingTargets() {
        MilestoneData data = new MilestoneData();
        Set<String> knownItems = Set.of("minecraft:diamond");

        // Invalid count / syntax
        assertFalse(MilestoneService.resetRecord(data, PLAYER_A, null, "minecraft:diamond", 0, knownItems::contains, LITERAL_NAME).success());
        assertFalse(MilestoneService.resetRecord(data, PLAYER_A, null, "bad item", 1, knownItems::contains, LITERAL_NAME).success());

        // ResetAll on player without data succeeds with 0
        MilestoneService.ActionResult emptyAll = MilestoneService.resetAllRecords(data, PLAYER_A, null);
        assertTrue(emptyAll.success());
        assertTrue(emptyAll.message().getString().contains("0 records"));
        assertNull(data.playerOrNull(PLAYER_A));

        // Unrecorded known item -> not_recorded; unrecorded unknown item -> invalid_item
        assertTrue(MilestoneService.resetRecord(data, PLAYER_A, null, "minecraft:diamond", 1, knownItems::contains, LITERAL_NAME)
                .message().getString().contains("No record for"));
        assertTrue(MilestoneService.resetRecord(data, PLAYER_A, null, "othermod:missing", 1, knownItems::contains, LITERAL_NAME)
                .message().getString().contains("Invalid or unknown item ID"));

        MilestoneData.PlayerData pd = data.player(PLAYER_A);
        TargetKey diamond = TargetKey.of("minecraft:diamond");
        TargetKey relic = TargetKey.of("uninstalled:relic", 10);
        pd.targets.add(diamond);
        pd.targets.add(relic);
        pd.records.put(diamond, new MilestoneRecord(100, 1L, 1000L));
        pd.records.put(relic, new MilestoneRecord(200, 2L, 2000L));

        // Can reset record of an uninstalled-mod item while keeping its target
        MilestoneService.ActionResult resetMod = MilestoneService.resetRecord(
                data, PLAYER_A, null, "uninstalled:relic", 10, knownItems::contains, LITERAL_NAME);
        assertTrue(resetMod.success());
        assertFalse(pd.records.containsKey(relic));
        assertTrue(pd.targets.contains(relic));

        // ResetAll clears remaining records while keeping targets
        MilestoneService.ActionResult resetAll = MilestoneService.resetAllRecords(data, PLAYER_A, null);
        assertTrue(resetAll.success());
        assertTrue(resetAll.message().getString().contains("1 records"));
        assertTrue(pd.records.isEmpty());
        assertEquals(Set.of(diamond, relic), pd.targets);
    }

    @Test
    void buildSyncPayloadSortsTargetsComputesProgressAndHidesCreativeCarriedProgress(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("my_custom.json"), """
                {
                  "name": "My Custom Display",
                  "items": ["minecraft:diamond"]
                }
                """);
        Presets.reloadFrom(dir);

        MilestoneData data = new MilestoneData();
        MilestoneData.PlayerData pd = data.player(PLAYER_A);
        TargetKey diamond1 = TargetKey.of("minecraft:diamond", 1);
        TargetKey diamond64 = TargetKey.of("minecraft:diamond", 64);
        TargetKey emerald16 = TargetKey.of("minecraft:emerald", 16);
        TargetKey gold32 = TargetKey.of("minecraft:gold_ingot", 32);
        TargetKey iron1 = TargetKey.of("minecraft:iron_ingot", 1);

        // Personal targets include overlap with defaultTargets
        pd.targets.add(gold32);
        pd.targets.add(diamond64);
        pd.targets.add(diamond1);
        pd.targets.add(emerald16);
        // emerald16 is already recorded, gold32 is carried from creative
        pd.records.put(emerald16, new MilestoneRecord(500, 3L, 1_700_000_000_000L));

        Set<TargetKey> defaultTargets = Set.of(iron1, diamond64);
        Set<String> carried = Set.of("minecraft:gold_ingot");
        Set<String> queriedIds = new HashSet<>();

        SyncMilestonesPayload payload = MilestoneService.buildSyncPayload(
                pd,
                defaultTargets,
                carried,
                itemIds -> {
                    queriedIds.addAll(itemIds);
                    return Map.of("minecraft:diamond", 25, "minecraft:gold_ingot", 32);
                },
                12345,
                true,
                true,
                null
        );

        // Targets sorted by TargetKey::compareTo with accurate personal/default flags
        assertEquals(List.of(
                new TargetEntry("minecraft:diamond", 1, true, false),
                new TargetEntry("minecraft:diamond", 64, true, true),
                new TargetEntry("minecraft:emerald", 16, true, false),
                new TargetEntry("minecraft:gold_ingot", 32, true, false),
                new TargetEntry("minecraft:iron_ingot", 1, false, true)
        ), payload.targets());

        // Possession counter should only be queried for unrecorded count>1 items not carried from creative
        assertEquals(Set.of("minecraft:diamond"), queriedIds);

        // Progress includes only unrecorded count>1 targets; creative-carried shows 0; recorded & count=1 omitted
        assertEquals(Map.of(
                diamond64, 25,
                gold32, 0
        ), payload.progress());

        assertEquals(Map.of(emerald16, new MilestoneRecord(500, 3L, 1_700_000_000_000L)), payload.records());
        assertEquals(12345, payload.currentPlaytimeTicks());
        assertTrue(payload.canManagePresets());
        assertTrue(payload.openScreen());

        // Builtin presets carry empty displayName; custom presets carry their displayName
        PresetEntry builtin = payload.presets().stream().filter(p -> p.id().equals("atm_star")).findFirst().orElseThrow();
        assertTrue(builtin.builtin());
        assertEquals("", builtin.displayName());
        PresetEntry custom = payload.presets().stream().filter(p -> p.id().equals("my_custom")).findFirst().orElseThrow();
        assertFalse(custom.builtin());
        assertEquals("My Custom Display", custom.displayName());
        assertEquals(1, custom.itemCount());
    }

    @Test
    void presetsDisabledRejectsPresetOperationsAndHidesPresetsInSyncPayload(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("my_custom.json"), """
                {
                  "name": "My Custom",
                  "items": ["minecraft:diamond"]
                }
                """);
        Presets.reloadFrom(dir);

        MilestoneData data = new MilestoneData();
        MilestoneData.PlayerData pd = data.player(PLAYER_A);
        pd.targets.add(TargetKey.of("minecraft:iron_ingot"));
        Set<String> knownItems = Set.of("minecraft:diamond", "minecraft:iron_ingot");

        MilestoneService.ActionResult applyRes = MilestoneService.applyPreset(
                data, PLAYER_A, null, "my_custom", knownItems::contains, false);
        assertFalse(applyRes.success());
        assertEquals("This feature is disabled on this server.", applyRes.message().getString());
        assertFalse(pd.targets.contains(TargetKey.of("minecraft:diamond")));

        MilestoneService.ActionResult saveRes = MilestoneService.savePreset(
                data, PLAYER_A, null, true, "new_preset", () -> dir, false);
        assertFalse(saveRes.success());
        assertEquals("This feature is disabled on this server.", saveRes.message().getString());
        assertFalse(Files.exists(dir.resolve("new_preset.json")));

        MilestoneService.ActionResult deleteRes = MilestoneService.deletePreset(
                null, true, "my_custom", () -> dir, false);
        assertFalse(deleteRes.success());
        assertEquals("This feature is disabled on this server.", deleteRes.message().getString());
        assertTrue(Files.isRegularFile(dir.resolve("my_custom.json")));

        SyncMilestonesPayload disabledSync = MilestoneService.buildSyncPayload(
                pd, Set.of(), Set.of(), ids -> Map.of(), 100, true, true, null, false);
        assertTrue(disabledSync.presets().isEmpty());
        assertFalse(disabledSync.canManagePresets());
    }

    @Test
    void removeResetAndResetAllCleanUpProgressNotifiedEntries() {
        MilestoneData data = new MilestoneData();
        MilestoneData.PlayerData pd = data.player(PLAYER_A);
        Set<String> knownItems = Set.of("minecraft:diamond", "minecraft:iron_ingot", "minecraft:emerald");

        TargetKey diamond100 = TargetKey.of("minecraft:diamond", 100);
        TargetKey iron100 = TargetKey.of("minecraft:iron_ingot", 100);
        TargetKey emerald100 = TargetKey.of("minecraft:emerald", 100);

        pd.targets.add(diamond100);
        pd.targets.add(iron100);
        pd.targets.add(emerald100);
        pd.records.put(iron100, new MilestoneRecord(200, 1L, 1000L));
        pd.progressNotified.put(diamond100, 50);
        pd.progressNotified.put(iron100, 90);
        pd.progressNotified.put(emerald100, 50);
        pd.completedPresets.add("vanilla_endgame");
        pd.completedPresets.add("my_custom");
        pd.muteProgressAlerts = true;
        pd.excludeFromDiscord = true;

        // removeTarget deletes progressNotified for the removed key only and keeps completedPresets
        assertTrue(MilestoneService.removeTarget(
                data, PLAYER_A, null, "minecraft:diamond", 100, knownItems::contains, LITERAL_NAME).success());
        assertFalse(pd.progressNotified.containsKey(diamond100));
        assertEquals(90, pd.progressNotified.get(iron100));
        assertEquals(50, pd.progressNotified.get(emerald100));
        assertEquals(Set.of("vanilla_endgame", "my_custom"), pd.completedPresets);
        assertTrue(pd.muteProgressAlerts);
        assertTrue(pd.excludeFromDiscord);

        // resetRecord deletes progressNotified for the reset key only and keeps completedPresets
        assertTrue(MilestoneService.resetRecord(
                data, PLAYER_A, null, "minecraft:iron_ingot", 100, knownItems::contains, LITERAL_NAME).success());
        assertFalse(pd.progressNotified.containsKey(iron100));
        assertEquals(50, pd.progressNotified.get(emerald100));
        assertEquals(Set.of("vanilla_endgame", "my_custom"), pd.completedPresets);
        assertTrue(pd.muteProgressAlerts);
        assertTrue(pd.excludeFromDiscord);

        // resetAllRecords clears all remaining progressNotified and completedPresets even when records map is already empty
        assertTrue(pd.records.isEmpty());
        assertTrue(MilestoneService.resetAllRecords(data, PLAYER_A, null).success());
        assertTrue(pd.progressNotified.isEmpty());
        assertTrue(pd.completedPresets.isEmpty());
        assertTrue(pd.muteProgressAlerts);
        assertTrue(pd.excludeFromDiscord);
    }

    @Test
    void presetDisplayNameResolvesBuiltinsPerRecipientLanguageAndEmbedsCustomsLiterally() {
        Presets.PresetDefinition builtin = Presets.get("atm_star");
        assertNotNull(builtin);
        assertEquals("Preset: ATM Star",
                MilestoneService.presetDisplayNameForLang("en_us", builtin).getString());
        assertEquals("プリセット: ATM Star",
                MilestoneService.presetDisplayNameForLang("ja_jp", builtin).getString());

        Presets.PresetDefinition customWithName = new Presets.PresetDefinition(
                "my_goals", "100% Pack %s Goals", List.of(TargetKey.of("minecraft:diamond")), false);
        assertEquals("100% Pack %s Goals",
                MilestoneService.presetDisplayNameForLang("en_us", customWithName).getString());
        assertEquals("100% Pack %s Goals",
                MilestoneService.presetDisplayNameForLang("ja_jp", customWithName).getString());

        // Custom preset without name falls back to preset ID
        Presets.PresetDefinition customFallback = new Presets.PresetDefinition(
                "my_goals", "", List.of(TargetKey.of("minecraft:diamond")), false);
        assertEquals("my_goals",
                MilestoneService.presetDisplayNameForLang("en_us", customFallback).getString());
    }

    @Test
    void toggleDiscordOptOutTogglesFlagAndRespectsConfigSwitch() {
        MilestoneData data = new MilestoneData();

        // Disabled by config: returns feature_disabled and does NOT create PlayerData
        MilestoneService.ActionResult disabledMissing = MilestoneService.toggleDiscordOptOut(data, PLAYER_A, null, false);
        assertFalse(disabledMissing.success());
        assertEquals("This feature is disabled on this server.", disabledMissing.message().getString());
        assertNull(data.playerOrNull(PLAYER_A));

        // Enabled: toggles false -> true
        MilestoneService.ActionResult enabledOn = MilestoneService.toggleDiscordOptOut(data, PLAYER_A, null, true);
        assertTrue(enabledOn.success());
        assertEquals("Your milestones will no longer be posted to Discord.", enabledOn.message().getString());
        assertNotNull(data.playerOrNull(PLAYER_A));
        assertTrue(data.playerOrNull(PLAYER_A).excludeFromDiscord);

        // Disabled after opting out: returns feature_disabled and preserves existing excludeFromDiscord = true
        MilestoneService.ActionResult disabledExisting = MilestoneService.toggleDiscordOptOut(data, PLAYER_A, null, false);
        assertFalse(disabledExisting.success());
        assertEquals("This feature is disabled on this server.", disabledExisting.message().getString());
        assertTrue(data.playerOrNull(PLAYER_A).excludeFromDiscord);

        // Enabled again: toggles true -> false
        MilestoneService.ActionResult enabledOff = MilestoneService.toggleDiscordOptOut(data, PLAYER_A, null, true);
        assertTrue(enabledOff.success());
        assertEquals("Your milestones will be posted to Discord again.", enabledOff.message().getString());
        assertFalse(data.playerOrNull(PLAYER_A).excludeFromDiscord);

        // Verify Japanese translations render as specified
        assertEquals("あなたの記録を Discord に投稿しないようにしました。",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.discordoptout_on").getString());
        assertEquals("あなたの記録を Discord に投稿するようにしました。",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.discordoptout_off").getString());
    }

    @Test
    void togglePrivacyTogglesHideFromLookupAndRepliesWithNewState() {
        MilestoneData data = new MilestoneData();
        assertNull(data.playerOrNull(PLAYER_A));

        // First run: false -> true, creates PlayerData and marks dirty
        MilestoneService.ActionResult on = MilestoneService.togglePrivacy(data, PLAYER_A, null);
        assertTrue(on.success());
        assertEquals("Your milestones are now hidden from other players (lookup, top, recent and stats).",
                on.message().getString());
        assertNotNull(data.playerOrNull(PLAYER_A));
        assertTrue(data.playerOrNull(PLAYER_A).hideFromLookup);
        assertTrue(data.isDirty());

        // Second run: true -> false
        MilestoneService.ActionResult off = MilestoneService.togglePrivacy(data, PLAYER_A, null);
        assertTrue(off.success());
        assertEquals("Your milestones are now visible to other players.", off.message().getString());
        assertFalse(data.playerOrNull(PLAYER_A).hideFromLookup);

        // Only the executing player's hideFromLookup changes; other personal settings and players untouched
        MilestoneService.togglePrivacy(data, PLAYER_A, null);
        assertTrue(data.playerOrNull(PLAYER_A).hideFromLookup);
        assertFalse(data.playerOrNull(PLAYER_A).muteBroadcasts);
        assertFalse(data.playerOrNull(PLAYER_A).excludeFromDiscord);
        assertNull(data.playerOrNull(UUID.fromString("22222222-2222-2222-2222-222222222222")));

        // Japanese translations render as specified
        assertEquals("あなたの記録を他のプレイヤーから隠しました（lookup・top・recent・stats）。",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.privacy_on").getString());
        assertEquals("あなたの記録を他のプレイヤーに公開しました。",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.privacy_off").getString());
    }

    // ---- F23: undo ----

    @Test
    void undoRestoresResetRecordAndLeavesWorldFirstsUntouched() {
        MilestoneData data = new MilestoneData();
        UndoLogic.Store<TargetKey, MilestoneRecord> store = new UndoLogic.Store<>();
        Set<String> knownItems = Set.of("minecraft:diamond", "minecraft:iron_ingot");
        TargetKey diamond = TargetKey.of("minecraft:diamond");
        TargetKey iron = TargetKey.of("minecraft:iron_ingot");
        MilestoneRecord diamondRec = new MilestoneRecord(120, 1L, 1_000L);
        MilestoneRecord ironRec = new MilestoneRecord(240, 2L, 2_000L);

        MilestoneData.PlayerData pd = data.player(PLAYER_A);
        pd.records.put(diamond, diamondRec);
        pd.records.put(iron, ironRec);
        data.recordWorldFirstIfAbsent(diamond, PLAYER_A, "Alice", diamondRec);

        long t0 = 10_000L;
        MilestoneService.ActionResult reset = MilestoneService.resetRecord(
                data, PLAYER_A, null, "minecraft:diamond", 1, knownItems::contains, LITERAL_NAME, store, t0);
        assertTrue(reset.success());
        assertTrue(reset.undoable());
        assertFalse(pd.records.containsKey(diamond));
        assertNotNull(data.worldFirstOrNull(diamond));

        MilestoneService.ActionResult undone = MilestoneService.undo(data, PLAYER_A, null, store, t0 + 100L);
        assertTrue(undone.success());
        assertEquals("Restored 1 record(s) from your last reset.", undone.message().getString());
        assertEquals(diamondRec, pd.records.get(diamond));
        assertEquals(ironRec, pd.records.get(iron));
        assertEquals(PLAYER_A, data.worldFirstOrNull(diamond).playerUuid());

        // Snapshot is consumed after a successful undo
        MilestoneService.ActionResult second = MilestoneService.undo(data, PLAYER_A, null, store, t0 + 200L);
        assertFalse(second.success());
        assertEquals("Nothing to undo. Only your last reset or bulk removal within the past 10 minutes can be undone.",
                second.message().getString());
    }

    @Test
    void undoRestoresResetAllWithCompletedPresetsProgressNotifiedAndReRecordConflict() {
        MilestoneData data = new MilestoneData();
        UndoLogic.Store<TargetKey, MilestoneRecord> store = new UndoLogic.Store<>();
        TargetKey diamond = TargetKey.of("minecraft:diamond");
        TargetKey iron = TargetKey.of("minecraft:iron_ingot");
        TargetKey emerald100 = TargetKey.of("minecraft:emerald", 100);
        TargetKey gold100 = TargetKey.of("minecraft:gold_ingot", 100);

        MilestoneRecord oldDiamond = new MilestoneRecord(100, 1L, 1_000L);
        MilestoneRecord oldIron = new MilestoneRecord(500, 2L, 2_000L);
        MilestoneData.PlayerData pd = data.player(PLAYER_A);
        pd.records.put(diamond, oldDiamond);
        pd.records.put(iron, oldIron);
        pd.progressNotified.put(emerald100, 50);
        pd.progressNotified.put(gold100, 90);
        pd.completedPresets.add("vanilla_endgame");

        long t0 = 5_000L;
        MilestoneService.ActionResult resetAll = MilestoneService.resetAllRecords(data, PLAYER_A, null, store, t0);
        assertTrue(resetAll.success());
        assertTrue(resetAll.undoable());
        assertTrue(pd.records.isEmpty());
        assertTrue(pd.completedPresets.isEmpty());
        assertTrue(pd.progressNotified.isEmpty());

        // Before undo: diamond re-recorded later (300 > 100), iron re-recorded earlier (200 < 500),
        // and new progressNotified entries appeared for gold100 and diamond
        MilestoneRecord newDiamond = new MilestoneRecord(300, 3L, 3_000L);
        MilestoneRecord newIron = new MilestoneRecord(200, 3L, 3_000L);
        pd.records.put(diamond, newDiamond);
        pd.records.put(iron, newIron);
        pd.progressNotified.put(gold100, 10);
        pd.progressNotified.put(diamond, 50);

        MilestoneService.ActionResult undone = MilestoneService.undo(data, PLAYER_A, null, store, t0 + 1_000L);
        assertTrue(undone.success());
        assertEquals("Restored 1 record(s) from your last reset.", undone.message().getString());
        assertEquals(oldDiamond, pd.records.get(diamond));
        assertEquals(newIron, pd.records.get(iron));
        assertEquals(Set.of("vanilla_endgame"), pd.completedPresets);
        assertEquals(50, pd.progressNotified.get(emerald100));
        assertEquals(10, pd.progressNotified.get(gold100));
        assertFalse(pd.progressNotified.containsKey(diamond));
    }

    @Test
    void undoPreservesSnapshotOnEmptyOrFailedResetOverwritesOnNextResetAndRespectsExpiryAndClear() {
        MilestoneData data = new MilestoneData();
        UndoLogic.Store<TargetKey, MilestoneRecord> store = new UndoLogic.Store<>();
        Set<String> knownItems = Set.of("minecraft:diamond", "minecraft:iron_ingot");
        TargetKey diamond = TargetKey.of("minecraft:diamond");
        TargetKey iron = TargetKey.of("minecraft:iron_ingot");
        MilestoneRecord diamondRec = new MilestoneRecord(100, 1L, 1_000L);
        MilestoneRecord ironRec = new MilestoneRecord(200, 2L, 2_000L);

        MilestoneData.PlayerData pd = data.player(PLAYER_A);
        pd.records.put(diamond, diamondRec);
        long t0 = 1_000L;

        MilestoneService.ActionResult resetDiamond = MilestoneService.resetRecord(
                data, PLAYER_A, null, "minecraft:diamond", 1, knownItems::contains, LITERAL_NAME, store, t0);
        assertTrue(resetDiamond.success());
        assertTrue(resetDiamond.undoable());

        // Failed reset and 0-record resetall do not overwrite the stored snapshot
        MilestoneService.ActionResult failedReset = MilestoneService.resetRecord(
                data, PLAYER_A, null, "minecraft:iron_ingot", 1, knownItems::contains, LITERAL_NAME, store, t0 + 10L);
        assertFalse(failedReset.success());
        assertFalse(failedReset.undoable());
        MilestoneService.ActionResult emptyResetAll = MilestoneService.resetAllRecords(data, PLAYER_A, null, store, t0 + 20L);
        assertTrue(emptyResetAll.success());
        assertFalse(emptyResetAll.undoable());

        // Undo still restores diamond from the first reset
        assertTrue(MilestoneService.undo(data, PLAYER_A, null, store, t0 + 30L).success());
        assertEquals(diamondRec, pd.records.get(diamond));

        // 1-step only: a second non-empty reset replaces the first snapshot
        pd.records.put(iron, ironRec);
        assertTrue(MilestoneService.resetRecord(
                data, PLAYER_A, null, "minecraft:diamond", 1, knownItems::contains, LITERAL_NAME, store, t0 + 100L).undoable());
        assertTrue(MilestoneService.resetRecord(
                data, PLAYER_A, null, "minecraft:iron_ingot", 1, knownItems::contains, LITERAL_NAME, store, t0 + 200L).undoable());
        assertTrue(MilestoneService.undo(data, PLAYER_A, null, store, t0 + 300L).success());
        assertFalse(pd.records.containsKey(diamond));
        assertEquals(ironRec, pd.records.get(iron));

        // Expiry after 10 minutes
        assertTrue(MilestoneService.resetRecord(
                data, PLAYER_A, null, "minecraft:iron_ingot", 1, knownItems::contains, LITERAL_NAME, store, t0 + 400L).undoable());
        assertFalse(MilestoneService.undo(
                data, PLAYER_A, null, store, t0 + 400L + UndoLogic.VALIDITY_NANOS + 1L).success());

        // Server stop clears all in-memory snapshots
        pd.records.put(iron, ironRec);
        assertTrue(MilestoneService.resetRecord(
                data, PLAYER_A, null, "minecraft:iron_ingot", 1, knownItems::contains, LITERAL_NAME, store, t0 + 500L).undoable());
        store.clear();
        assertFalse(MilestoneService.undo(data, PLAYER_A, null, store, t0 + 501L).success());
    }

    @Test
    void undoBulkRemovalRestoresTargetsRespectsLimitAndRendersJapaneseMessages() {
        MilestoneData data = new MilestoneData();
        UndoLogic.Store<TargetKey, MilestoneRecord> store = new UndoLogic.Store<>();
        MilestoneData.PlayerData pd = data.player(PLAYER_A);

        TargetKey alreadyPresent = TargetKey.of("minecraft:diamond");
        TargetKey fits = TargetKey.of("minecraft:emerald");
        TargetKey overLimit = TargetKey.of("minecraft:gold_ingot");
        pd.targets.add(alreadyPresent);
        for (int i = 0; i < MilestoneService.MAX_PERSONAL_TARGETS - 2; i++) {
            pd.targets.add(TargetKey.of("minecraft:filler_" + i));
        }

        long t0 = 2_000L;
        assertTrue(store.offer(PLAYER_A, UndoLogic.Snapshot.bulkRemoval(
                List.of(alreadyPresent, fits, overLimit), Map.of(fits, 50), t0)));

        MilestoneService.ActionResult undone = MilestoneService.undo(data, PLAYER_A, null, store, t0 + 50L);
        assertTrue(undone.success());
        assertEquals("Restored 1 target(s) from your last bulk removal.\n"
                        + "1 more target(s) were skipped because your target list is full.",
                undone.message().getString());
        assertTrue(pd.targets.contains(fits));
        assertFalse(pd.targets.contains(overLimit));
        assertEquals(MilestoneService.MAX_PERSONAL_TARGETS, pd.targets.size());
        assertEquals(50, pd.progressNotified.get(fits));

        // Verify all F23 Japanese translations match the spec
        assertEquals("直前のリセットから記録を 3 件戻しました。",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.undo_done", 3).getString());
        assertEquals("直前の一括削除から目標を 2 件戻しました。",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.undo_done_targets", 2).getString());
        assertEquals("10 分以内なら /milestonelog undo で元に戻せます。",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.undo_hint").getString());
        assertEquals("取り消せるものがありません。取り消せるのは、10 分以内の直前のリセットか一括削除だけです。",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.undo_nothing").getString());
    }

    // ---- F28: presetremove / removeall ----

    @Test
    void removePresetTargetsMatchesNormalizedAndNonexistentKeysCleansProgressAndKeepsRecords(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("mixed_pack.json"), """
                {
                  "name": "Mixed Pack",
                  "items": [
                    "diamond",
                    {"item": "minecraft:diamond", "count": 64},
                    "missingmod:absent_item",
                    {"item": "missingmod:absent_item", "count": 16}
                  ]
                }
                """);
        assertEquals(1, Presets.reloadFrom(dir));

        MilestoneData data = new MilestoneData();
        UndoLogic.Store<TargetKey, MilestoneRecord> store = new UndoLogic.Store<>();

        // Disabled by config: rejects with feature_disabled, does not create PlayerData or store snapshot
        MilestoneService.ActionResult disabled = MilestoneService.removePresetTargets(
                data, PLAYER_A, null, "mixed_pack", false, store, 100L);
        assertFalse(disabled.success());
        assertFalse(disabled.undoable());
        assertEquals("This feature is disabled on this server.", disabled.message().getString());
        assertNull(data.playerOrNull(PLAYER_A));

        // Unknown preset: rejects with preset_unknown
        MilestoneService.ActionResult unknown = MilestoneService.removePresetTargets(
                data, PLAYER_A, null, "no_such_preset", true, store, 200L);
        assertFalse(unknown.success());
        assertFalse(unknown.undoable());
        assertTrue(unknown.message().getString().contains("Unknown preset 'no_such_preset'"));

        // Player with no data: succeeds with 0 removed, does not create PlayerData, not undoable
        MilestoneService.ActionResult noData = MilestoneService.removePresetTargets(
                data, PLAYER_A, null, "mixed_pack", true, store, 300L);
        assertTrue(noData.success());
        assertFalse(noData.undoable());
        assertEquals("Preset 'mixed_pack': stopped tracking 0 target(s). Records were kept.", noData.message().getString());
        assertNull(data.playerOrNull(PLAYER_A));

        TargetKey diamond = TargetKey.of("minecraft:diamond");
        TargetKey diamond64 = TargetKey.of("minecraft:diamond", 64);
        TargetKey diamond10 = TargetKey.of("minecraft:diamond", 10);
        TargetKey absent = TargetKey.of("missingmod:absent_item");
        TargetKey absent16 = TargetKey.of("missingmod:absent_item", 16);
        TargetKey emerald = TargetKey.of("minecraft:emerald");
        TargetKey defaultKey = TargetKey.of("minecraft:iron_ingot", 100);
        MilestoneRecord diamondRec = new MilestoneRecord(100, 1L, 1_000L);
        MilestoneRecord absent16Rec = new MilestoneRecord(200, 2L, 2_000L);

        MilestoneData.PlayerData pd = data.player(PLAYER_A);
        pd.targets.add(diamond);
        pd.targets.add(diamond64);
        pd.targets.add(diamond10);
        pd.targets.add(absent);
        pd.targets.add(absent16);
        pd.targets.add(emerald);
        pd.records.put(diamond, diamondRec);
        pd.records.put(absent16, absent16Rec);
        pd.progressNotified.put(diamond64, 50);
        pd.progressNotified.put(absent16, 90);
        pd.progressNotified.put(diamond10, 50);
        pd.progressNotified.put(defaultKey, 50);
        pd.completedPresets.add("vanilla_endgame");
        data.recordWorldFirstIfAbsent(diamond, PLAYER_A, "Alice", diamondRec);

        long t0 = 1_000L;
        MilestoneService.ActionResult removed = MilestoneService.removePresetTargets(
                data, PLAYER_A, null, "mixed_pack", true, store, t0);
        assertTrue(removed.success());
        assertTrue(removed.undoable());
        assertEquals("Preset 'mixed_pack': stopped tracking 4 target(s). Records were kept.", removed.message().getString());

        // Matching keys (including nonexistent-item keys) removed; non-matching count and other targets kept
        assertEquals(Set.of(diamond10, emerald), pd.targets);
        // §1.1: progressNotified cleaned for removed keys only; other personal and default target entries kept
        assertFalse(pd.progressNotified.containsKey(diamond64));
        assertFalse(pd.progressNotified.containsKey(absent16));
        assertEquals(50, pd.progressNotified.get(diamond10));
        assertEquals(50, pd.progressNotified.get(defaultKey));
        // Records, completedPresets, and world firsts untouched
        assertEquals(diamondRec, pd.records.get(diamond));
        assertEquals(absent16Rec, pd.records.get(absent16));
        assertEquals(Set.of("vanilla_endgame"), pd.completedPresets);
        assertEquals(PLAYER_A, data.worldFirstOrNull(diamond).playerUuid());

        // Undo restores all 4 targets (including nonexistent-item keys) and restores progressNotified only for unrecorded keys
        MilestoneService.ActionResult undone = MilestoneService.undo(data, PLAYER_A, null, store, t0 + 100L);
        assertTrue(undone.success());
        assertEquals("Restored 4 target(s) from your last bulk removal.", undone.message().getString());
        assertEquals(Set.of(diamond, diamond64, diamond10, absent, absent16, emerald), pd.targets);
        assertEquals(50, pd.progressNotified.get(diamond64));
        assertFalse(pd.progressNotified.containsKey(absent16), "already-recorded key must not regain progressNotified");

        // Normalization check with un-namespaced preset entries and legacy un-namespaced personal target
        TargetKey legacyIron = TargetKey.of("iron_ingot", 32);
        pd.targets.add(legacyIron);
        pd.progressNotified.put(legacyIron, 50);
        Presets.PresetDefinition rawPreset = new Presets.PresetDefinition(
                "raw_test", "Raw", List.of(TargetKey.of("diamond"), TargetKey.of("minecraft:iron_ingot", 32), TargetKey.of("minecraft:air")), false);
        MilestoneService.ActionResult rawRemoved = MilestoneService.removePresetTargets(
                data, PLAYER_A, null, rawPreset, store, t0 + 200L);
        assertTrue(rawRemoved.success());
        assertTrue(rawRemoved.undoable());
        assertEquals("Preset 'raw_test': stopped tracking 2 target(s). Records were kept.", rawRemoved.message().getString());
        assertFalse(pd.targets.contains(diamond));
        assertFalse(pd.targets.contains(legacyIron));
        assertFalse(pd.progressNotified.containsKey(legacyIron));

        // Japanese translation matches the spec verbatim
        assertEquals("プリセット 'mixed_pack' の目標を 4 件外しました。記録は残っています。",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.presetremove_done", "mixed_pack", 4).getString());
    }

    @Test
    void removeAllTargetsRemovesAllPersonalTargetsCleansProgressAndKeepsRecordsAndWorldFirsts() {
        MilestoneData data = new MilestoneData();
        UndoLogic.Store<TargetKey, MilestoneRecord> store = new UndoLogic.Store<>();

        // Missing PlayerData: succeeds with 0, not undoable, never creates PlayerData
        MilestoneService.ActionResult emptyMissing = MilestoneService.removeAllTargets(data, PLAYER_A, null, store, 100L);
        assertTrue(emptyMissing.success());
        assertFalse(emptyMissing.undoable());
        assertEquals("Stopped tracking 0 personal target(s). Records and default targets were kept.",
                emptyMissing.message().getString());
        assertNull(data.playerOrNull(PLAYER_A));

        TargetKey diamond = TargetKey.of("minecraft:diamond");
        TargetKey diamond64 = TargetKey.of("minecraft:diamond", 64);
        TargetKey relic5 = TargetKey.of("missingmod:relic", 5);
        TargetKey defaultKey = TargetKey.of("minecraft:iron_ingot", 100);
        MilestoneRecord diamondRec = new MilestoneRecord(150, 1L, 1_500L);

        MilestoneData.PlayerData pd = data.player(PLAYER_A);
        pd.targets.add(diamond);
        pd.targets.add(diamond64);
        pd.targets.add(relic5);
        pd.records.put(diamond, diamondRec);
        pd.progressNotified.put(diamond64, 50);
        pd.progressNotified.put(relic5, 90);
        pd.progressNotified.put(defaultKey, 50);
        pd.completedPresets.add("vanilla_endgame");
        pd.muteProgressAlerts = true;
        data.recordWorldFirstIfAbsent(diamond, PLAYER_A, "Alice", diamondRec);

        long t0 = 5_000L;
        MilestoneService.ActionResult result = MilestoneService.removeAllTargets(data, PLAYER_A, null, store, t0);
        assertTrue(result.success());
        assertTrue(result.undoable());
        assertEquals("Stopped tracking 3 personal target(s). Records and default targets were kept.",
                result.message().getString());
        assertTrue(pd.targets.isEmpty());
        // Removed keys' progressNotified are cleaned; non-personal default target entry stays intact
        assertFalse(pd.progressNotified.containsKey(diamond64));
        assertFalse(pd.progressNotified.containsKey(relic5));
        assertEquals(50, pd.progressNotified.get(defaultKey));
        // Records, completedPresets, world firsts, and personal settings are untouched
        assertEquals(diamondRec, pd.records.get(diamond));
        assertEquals(Set.of("vanilla_endgame"), pd.completedPresets);
        assertTrue(pd.muteProgressAlerts);
        assertEquals(PLAYER_A, data.worldFirstOrNull(diamond).playerUuid());

        // Undo restores all personal targets and their progressNotified entries
        MilestoneService.ActionResult undone = MilestoneService.undo(data, PLAYER_A, null, store, t0 + 50L);
        assertTrue(undone.success());
        assertEquals("Restored 3 target(s) from your last bulk removal.", undone.message().getString());
        assertEquals(Set.of(diamond, diamond64, relic5), pd.targets);
        assertEquals(50, pd.progressNotified.get(diamond64));
        assertEquals(90, pd.progressNotified.get(relic5));
        assertEquals(50, pd.progressNotified.get(defaultKey));

        // Japanese translation matches the spec verbatim
        assertEquals("個人の目標を 3 件外しました。記録とデフォルト目標は残っています。",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.removeall_done", 3).getString());
    }

    @Test
    void bulkRemovalZeroCountPreservesPreviousUndoSnapshotAndUndoHandlesDuplicatesAndCapOverflow(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("small.json"), """
                {
                  "name": "Small",
                  "items": ["minecraft:coal"]
                }
                """);
        assertEquals(1, Presets.reloadFrom(dir));

        MilestoneData data = new MilestoneData();
        UndoLogic.Store<TargetKey, MilestoneRecord> store = new UndoLogic.Store<>();
        Set<String> knownItems = Set.of("minecraft:diamond");
        TargetKey diamond = TargetKey.of("minecraft:diamond");
        TargetKey emerald = TargetKey.of("minecraft:emerald");
        TargetKey gold = TargetKey.of("minecraft:gold_ingot");
        TargetKey star = TargetKey.of("minecraft:nether_star");
        MilestoneRecord diamondRec = new MilestoneRecord(100, 1L, 1_000L);

        MilestoneData.PlayerData pd = data.player(PLAYER_A);
        pd.records.put(diamond, diamondRec);

        long t0 = 10_000L;
        assertTrue(MilestoneService.resetRecord(
                data, PLAYER_A, null, "minecraft:diamond", 1, knownItems::contains, LITERAL_NAME, store, t0).undoable());

        // 0-removal presetremove and 0-removal removeall must NOT overwrite the previous reset snapshot
        MilestoneService.ActionResult zeroPreset = MilestoneService.removePresetTargets(
                data, PLAYER_A, null, "small", true, store, t0 + 10L);
        assertTrue(zeroPreset.success());
        assertFalse(zeroPreset.undoable());
        MilestoneService.ActionResult zeroAll = MilestoneService.removeAllTargets(data, PLAYER_A, null, store, t0 + 20L);
        assertTrue(zeroAll.success());
        assertFalse(zeroAll.undoable());

        // Undo still restores the earlier record reset
        MilestoneService.ActionResult restoredReset = MilestoneService.undo(data, PLAYER_A, null, store, t0 + 30L);
        assertTrue(restoredReset.success());
        assertEquals("Restored 1 record(s) from your last reset.", restoredReset.message().getString());
        assertEquals(diamondRec, pd.records.get(diamond));

        // End-to-end removeall -> re-add duplicate + fill near 1024 cap -> undo
        pd.targets.add(diamond);
        pd.targets.add(emerald);
        pd.targets.add(gold);
        pd.targets.add(star);
        pd.progressNotified.put(TargetKey.of("minecraft:emerald"), 50);

        MilestoneService.ActionResult bulk = MilestoneService.removeAllTargets(data, PLAYER_A, null, store, t0 + 100L);
        assertTrue(bulk.success());
        assertTrue(bulk.undoable());
        assertTrue(pd.targets.isEmpty());

        // Re-add diamond (duplicate) and fill to MAX_PERSONAL_TARGETS - 1 (only 1 free slot left)
        pd.targets.add(diamond);
        for (int i = 0; i < MilestoneService.MAX_PERSONAL_TARGETS - 2; i++) {
            pd.targets.add(TargetKey.of("minecraft:filler_" + i));
        }
        assertEquals(MilestoneService.MAX_PERSONAL_TARGETS - 1, pd.targets.size());

        MilestoneService.ActionResult undone = MilestoneService.undo(data, PLAYER_A, null, store, t0 + 150L);
        assertTrue(undone.success());
        assertEquals("Restored 1 target(s) from your last bulk removal.\n"
                        + "2 more target(s) were skipped because your target list is full.",
                undone.message().getString());
        assertEquals(MilestoneService.MAX_PERSONAL_TARGETS, pd.targets.size());
        assertTrue(pd.targets.contains(diamond));
        assertTrue(pd.targets.contains(emerald));
        assertFalse(pd.targets.contains(gold));
        assertFalse(pd.targets.contains(star));
        assertEquals(50, pd.progressNotified.get(emerald));
    }

    // ---- F33: admin add / remove / preset ----

    @Test
    void adminAddTargetMatchesPlayerValidationCapMessagesAndSkipsUndo() {
        MilestoneData data = new MilestoneData();
        Set<String> knownItems = Set.of("minecraft:diamond", "minecraft:iron_ingot");
        MilestoneService.UNDO_SNAPSHOTS.clear();

        // Validation parity with player addTarget: invalid count / unknown / air never create PlayerData
        assertFalse(MilestoneService.adminAddTarget(
                data, PLAYER_A, "Alice", null, "minecraft:diamond", 0, knownItems::contains, LITERAL_NAME).success());
        assertFalse(MilestoneService.adminAddTarget(
                data, PLAYER_A, "Alice", null, "minecraft:diamond", TargetKey.MAX_COUNT + 1, knownItems::contains, LITERAL_NAME).success());
        assertFalse(MilestoneService.adminAddTarget(
                data, PLAYER_A, "Alice", null, "minecraft:air", 1, knownItems::contains, LITERAL_NAME).success());
        assertFalse(MilestoneService.adminAddTarget(
                data, PLAYER_A, "Alice", null, "minecraft:unknown", 1, knownItems::contains, LITERAL_NAME).success());
        assertNull(data.playerOrNull(PLAYER_A));

        // Normalization & literal player name safety (%s in player name is not interpreted as format)
        MilestoneService.ActionResult added = MilestoneService.adminAddTarget(
                data, PLAYER_A, "Alice%s", null, "diamond", 1, knownItems::contains, LITERAL_NAME);
        assertTrue(added.success());
        assertFalse(added.undoable());
        assertEquals("Alice%s now tracks minecraft:diamond.", added.message().getString());
        assertTrue(data.playerOrNull(PLAYER_A).targets.contains(TargetKey.of("minecraft:diamond")));
        assertTrue(data.isDirty());

        // Duplicate rejected with admin_already_target
        MilestoneService.ActionResult dup = MilestoneService.adminAddTarget(
                data, PLAYER_A, "Alice%s", null, "minecraft:diamond", 1, knownItems::contains, LITERAL_NAME);
        assertFalse(dup.success());
        assertEquals("Alice%s already tracks minecraft:diamond.", dup.message().getString());

        // Quantity target allowed
        MilestoneService.ActionResult qty = MilestoneService.adminAddTarget(
                data, PLAYER_A, "Alice", null, "minecraft:diamond", 64, knownItems::contains, LITERAL_NAME);
        assertTrue(qty.success());
        assertEquals("Alice now tracks minecraft:diamond ×64.", qty.message().getString());
        assertTrue(data.playerOrNull(PLAYER_A).targets.contains(TargetKey.of("minecraft:diamond", 64)));

        // Cap parity (1024)
        MilestoneData.PlayerData pd = data.player(PLAYER_A);
        for (int i = pd.targets.size(); i < MilestoneService.MAX_PERSONAL_TARGETS; i++) {
            pd.targets.add(TargetKey.of("minecraft:filler_" + i));
        }
        MilestoneService.ActionResult capped = MilestoneService.adminAddTarget(
                data, PLAYER_A, "Alice", null, "minecraft:iron_ingot", 1, knownItems::contains, LITERAL_NAME);
        assertFalse(capped.success());
        assertEquals("Personal target limit reached (1024).", capped.message().getString());
        assertFalse(pd.targets.contains(TargetKey.of("minecraft:iron_ingot")));
        assertNull(MilestoneService.UNDO_SNAPSHOTS.peekValid(PLAYER_A, System.nanoTime()));

        // Verify Japanese messages match spec verbatim
        assertEquals("Alice の目標に minecraft:diamond を追加しました。",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.admin_added",
                        Component.literal("Alice"), Component.literal("minecraft:diamond")).getString());
        assertEquals("Alice はすでに minecraft:diamond を目標にしています。",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.admin_already_target",
                        Component.literal("Alice"), Component.literal("minecraft:diamond")).getString());
    }

    @Test
    void adminRemoveTargetMatchesPlayerValidationCleanupMessagesAndSkipsUndo() {
        MilestoneData data = new MilestoneData();
        Set<String> knownItems = Set.of("minecraft:diamond");
        MilestoneService.UNDO_SNAPSHOTS.clear();

        // Invalid count / syntax / missing data parity
        assertFalse(MilestoneService.adminRemoveTarget(
                data, PLAYER_A, "Alice", null, "minecraft:diamond", 0, knownItems::contains, LITERAL_NAME).success());
        assertFalse(MilestoneService.adminRemoveTarget(
                data, PLAYER_A, "Alice", null, "bad item", 1, knownItems::contains, LITERAL_NAME).success());
        MilestoneService.ActionResult unknownMissing = MilestoneService.adminRemoveTarget(
                data, PLAYER_A, "Alice", null, "othermod:missing", 1, knownItems::contains, LITERAL_NAME);
        assertFalse(unknownMissing.success());
        assertTrue(unknownMissing.message().getString().contains("Invalid or unknown item ID"));
        MilestoneService.ActionResult knownMissing = MilestoneService.adminRemoveTarget(
                data, PLAYER_A, "Alice%s", null, "minecraft:diamond", 1, knownItems::contains, LITERAL_NAME);
        assertFalse(knownMissing.success());
        assertEquals("Alice%s does not track minecraft:diamond as a personal target.", knownMissing.message().getString());
        assertNull(data.playerOrNull(PLAYER_A));

        // Removes uninstalled-mod item and cleans progressNotified (§1.1) while keeping records and completedPresets
        TargetKey diamond100 = TargetKey.of("minecraft:diamond", 100);
        TargetKey relic5 = TargetKey.of("uninstalled:relic", 5);
        MilestoneRecord rec = new MilestoneRecord(300, 1L, 1_000L);
        MilestoneData.PlayerData pd = data.player(PLAYER_A);
        pd.targets.add(diamond100);
        pd.targets.add(relic5);
        pd.records.put(diamond100, rec);
        pd.progressNotified.put(diamond100, 50);
        pd.progressNotified.put(relic5, 90);
        pd.completedPresets.add("vanilla_endgame");

        MilestoneService.ActionResult removedMod = MilestoneService.adminRemoveTarget(
                data, PLAYER_A, "Alice", null, "uninstalled:relic", 5, knownItems::contains, LITERAL_NAME);
        assertTrue(removedMod.success());
        assertFalse(removedMod.undoable());
        assertEquals("Alice no longer tracks uninstalled:relic ×5.", removedMod.message().getString());
        assertFalse(pd.targets.contains(relic5));
        assertFalse(pd.progressNotified.containsKey(relic5));
        assertEquals(50, pd.progressNotified.get(diamond100));

        // Normalized un-namespaced removal cleans progressNotified and keeps records/completedPresets
        MilestoneService.ActionResult removedDiamond = MilestoneService.adminRemoveTarget(
                data, PLAYER_A, "Alice", null, "diamond", 100, knownItems::contains, LITERAL_NAME);
        assertTrue(removedDiamond.success());
        assertFalse(removedDiamond.undoable());
        assertEquals("Alice no longer tracks minecraft:diamond ×100.", removedDiamond.message().getString());
        assertFalse(pd.targets.contains(diamond100));
        assertFalse(pd.progressNotified.containsKey(diamond100));
        assertEquals(rec, pd.records.get(diamond100));
        assertEquals(Set.of("vanilla_endgame"), pd.completedPresets);
        assertNull(MilestoneService.UNDO_SNAPSHOTS.peekValid(PLAYER_A, System.nanoTime()));

        // Verify Japanese messages match spec verbatim
        assertEquals("Alice の目標から minecraft:diamond を外しました。",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.admin_removed",
                        Component.literal("Alice"), Component.literal("minecraft:diamond")).getString());
        assertEquals("Alice の個人の目標に minecraft:diamond はありません。",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.admin_not_target",
                        Component.literal("Alice"), Component.literal("minecraft:diamond")).getString());
    }

    @Test
    void adminApplyPresetMatchesPlayerValidationCapDisabledFlagAndMessages(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("mixed_pack.json"), """
                {
                  "name": "Mixed Pack",
                  "items": [
                    "minecraft:diamond",
                    {"item": "minecraft:diamond", "count": 64},
                    "minecraft:iron_ingot",
                    "missingmod:absent_item",
                    "minecraft:emerald"
                  ]
                }
                """);
        assertEquals(1, Presets.reloadFrom(dir));

        MilestoneData data = new MilestoneData();
        Set<String> knownItems = Set.of("minecraft:diamond", "minecraft:iron_ingot", "minecraft:emerald");

        // presetsEnabled == false -> T feature_disabled message, no PlayerData created
        MilestoneService.ActionResult disabled = MilestoneService.adminApplyPreset(
                data, PLAYER_A, "Alice", null, "mixed_pack", knownItems::contains, false);
        assertFalse(disabled.success());
        assertEquals("This feature is disabled on this server.", disabled.message().getString());
        assertNull(data.playerOrNull(PLAYER_A));

        // Unknown preset -> preset_unknown
        MilestoneService.ActionResult unknown = MilestoneService.adminApplyPreset(
                data, PLAYER_A, "Alice", null, "no_such_preset", knownItems::contains, true);
        assertFalse(unknown.success());
        assertTrue(unknown.message().getString().contains("Unknown preset 'no_such_preset'"));

        // Pre-track diamond so it is skipped as a duplicate
        MilestoneData.PlayerData pd = data.player(PLAYER_A);
        pd.targets.add(TargetKey.of("minecraft:diamond"));

        MilestoneService.ActionResult applied = MilestoneService.adminApplyPreset(
                data, PLAYER_A, "Alice%s", null, "mixed_pack", knownItems::contains, true);
        assertTrue(applied.success());
        assertFalse(applied.undoable());
        assertEquals(
                "Preset 'mixed_pack' applied to Alice%s: 3 targets added, 1 skipped (item not present in this pack).",
                applied.message().getString());
        assertEquals(Set.of(
                TargetKey.of("minecraft:diamond"),
                TargetKey.of("minecraft:diamond", 64),
                TargetKey.of("minecraft:iron_ingot"),
                TargetKey.of("minecraft:emerald")
        ), pd.targets);

        // Near cap (1 slot left): adds 1, skips 1 missing, appends preset_applied_limit for 3 capped
        MilestoneData limitedData = new MilestoneData();
        MilestoneData.PlayerData limitedPd = limitedData.player(PLAYER_A);
        for (int i = 0; i < MilestoneService.MAX_PERSONAL_TARGETS - 1; i++) {
            limitedPd.targets.add(TargetKey.of("minecraft:filler_" + i));
        }
        MilestoneService.ActionResult capped = MilestoneService.adminApplyPreset(
                limitedData, PLAYER_A, "Bob", null, "mixed_pack", knownItems::contains, true);
        assertTrue(capped.success());
        assertEquals(MilestoneService.MAX_PERSONAL_TARGETS, limitedPd.targets.size());
        assertEquals(
                "Preset 'mixed_pack' applied to Bob: 1 targets added, 1 skipped (item not present in this pack).\n"
                        + "3 more target(s) were skipped because your target list is full.",
                capped.message().getString());

        // Verify Japanese translation matches spec verbatim
        assertEquals("プリセット 'mixed_pack' を Alice に適用: 3件追加、1件スキップ（このパックに存在しないアイテム）",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.admin_preset_applied",
                        "mixed_pack", Component.literal("Alice"), 3, 1).getString());
    }

    // ---- F40: firstJoinPresets ----

    @Test
    void applyFirstJoinPresetsAppliesInOrderSkipsUnknownMissingDuplicatesAndCapsAtLimit(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("starter_a.json"), """
                {
                  "name": "Starter A",
                  "items": [
                    "diamond",
                    {"item": "minecraft:diamond", "count": 64},
                    "missingmod:absent_item"
                  ]
                }
                """);
        Files.writeString(dir.resolve("starter_b.json"), """
                {
                  "name": "Starter B",
                  "items": [
                    "minecraft:diamond",
                    "minecraft:iron_ingot",
                    "minecraft:emerald"
                  ]
                }
                """);

        // Build two large presets whose combined items exceed MAX_PERSONAL_TARGETS (1024)
        StringBuilder large1 = new StringBuilder("{\"name\":\"Large 1\",\"items\":[");
        StringBuilder large2 = new StringBuilder("{\"name\":\"Large 2\",\"items\":[");
        Set<String> allLargeKnown = new HashSet<>();
        for (int i = 0; i < 700; i++) {
            if (i > 0) {
                large1.append(',');
            }
            String id = "minecraft:item_" + i;
            allLargeKnown.add(id);
            large1.append('"').append(id).append('"');
        }
        large1.append("]}");
        for (int i = 0; i < 500; i++) {
            if (i > 0) {
                large2.append(',');
            }
            String id = "minecraft:extra_" + i;
            allLargeKnown.add(id);
            large2.append('"').append(id).append('"');
        }
        large2.append("]}");
        Files.writeString(dir.resolve("large_1.json"), large1.toString());
        Files.writeString(dir.resolve("large_2.json"), large2.toString());
        assertEquals(4, Presets.reloadFrom(dir));

        MilestoneService.UNDO_SNAPSHOTS.clear();
        MilestoneData data = new MilestoneData();
        Set<String> knownItems = Set.of("minecraft:diamond", "minecraft:iron_ingot", "minecraft:emerald");

        // Unknown preset IDs are skipped silently; valid presets apply in order with normalization & dedup
        int added = MilestoneService.applyFirstJoinPresets(
                data, PLAYER_A,
                List.of("no_such_preset", "starter_a", "another_unknown", "starter_b"),
                knownItems::contains, true);
        assertEquals(4, added);
        MilestoneData.PlayerData pd = data.playerOrNull(PLAYER_A);
        assertNotNull(pd);
        assertEquals(List.of(
                TargetKey.of("minecraft:diamond"),
                TargetKey.of("minecraft:diamond", 64),
                TargetKey.of("minecraft:iron_ingot"),
                TargetKey.of("minecraft:emerald")
        ), List.copyOf(pd.targets));
        assertTrue(pd.progressNotified.isEmpty(), "F1 baseline stays unset until first evaluation");
        assertTrue(data.isDirty());
        assertNull(MilestoneService.UNDO_SNAPSHOTS.peekValid(PLAYER_A, System.nanoTime()),
                "firstJoinPresets must not create an F23 undo snapshot");

        // Startup warning helper lists unknown IDs once in config order
        assertEquals(List.of("no_such_preset", "another_unknown"),
                MilestoneService.findUnknownFirstJoinPresets(
                        List.of("no_such_preset", "starter_a", "another_unknown", "no_such_preset", "vanilla_endgame")));
        assertTrue(MilestoneService.findUnknownFirstJoinPresets(List.of("starter_a", "vanilla_endgame")).isEmpty());
        assertTrue(MilestoneService.findUnknownFirstJoinPresets(List.of()).isEmpty());
        assertTrue(MilestoneService.findUnknownFirstJoinPresets(null).isEmpty());

        // Cap behavior: applying large_1 (700) then large_2 (500) caps at MAX_PERSONAL_TARGETS (1024)
        MilestoneData cappedData = new MilestoneData();
        int cappedAdded = MilestoneService.applyFirstJoinPresets(
                cappedData, PLAYER_A, List.of("large_1", "large_2"), allLargeKnown::contains, true);
        assertEquals(MilestoneService.MAX_PERSONAL_TARGETS, cappedAdded);
        MilestoneData.PlayerData cappedPd = cappedData.playerOrNull(PLAYER_A);
        assertNotNull(cappedPd);
        assertEquals(MilestoneService.MAX_PERSONAL_TARGETS, cappedPd.targets.size());
        assertTrue(cappedPd.targets.contains(TargetKey.of("minecraft:item_0")));
        assertTrue(cappedPd.targets.contains(TargetKey.of("minecraft:item_699")));
        assertTrue(cappedPd.targets.contains(TargetKey.of("minecraft:extra_0")));
        assertTrue(cappedPd.targets.contains(TargetKey.of("minecraft:extra_323")));
        assertFalse(cappedPd.targets.contains(TargetKey.of("minecraft:extra_324")));
    }

    @Test
    void applyFirstJoinPresetsSkipsExistingPlayerDataDisabledSwitchEmptyAndUnknown(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("all_missing.json"), """
                {
                  "name": "All Missing",
                  "items": ["missingmod:absent_item"]
                }
                """);
        assertEquals(1, Presets.reloadFrom(dir));
        Set<String> knownItems = Set.of("minecraft:diamond", "minecraft:netherite_ingot");

        // presetsEnabled = false -> does nothing, never creates PlayerData
        MilestoneData disabledData = new MilestoneData();
        assertEquals(0, MilestoneService.applyFirstJoinPresets(
                disabledData, PLAYER_A, List.of("vanilla_endgame"), knownItems::contains, false));
        assertNull(disabledData.playerOrNull(PLAYER_A));

        // Empty or null preset list -> does nothing, never creates PlayerData
        MilestoneData emptyData = new MilestoneData();
        assertEquals(0, MilestoneService.applyFirstJoinPresets(
                emptyData, PLAYER_A, List.of(), knownItems::contains, true));
        assertEquals(0, MilestoneService.applyFirstJoinPresets(
                emptyData, PLAYER_A, null, knownItems::contains, true));
        assertNull(emptyData.playerOrNull(PLAYER_A));

        // Only unknown preset IDs -> skips silently, never creates PlayerData
        MilestoneData unknownData = new MilestoneData();
        assertEquals(0, MilestoneService.applyFirstJoinPresets(
                unknownData, PLAYER_A, List.of("missing_preset_1", "missing_preset_2"), knownItems::contains, true));
        assertNull(unknownData.playerOrNull(PLAYER_A));

        // Existing PlayerData (even empty, e.g. pre-F40 player or importall-created) -> does nothing
        MilestoneData existingData = new MilestoneData();
        MilestoneData.PlayerData existingPd = existingData.player(PLAYER_A);
        existingPd.lastKnownName = "Veteran";
        assertEquals(0, MilestoneService.applyFirstJoinPresets(
                existingData, PLAYER_A, List.of("vanilla_endgame"), knownItems::contains, true));
        assertTrue(existingPd.targets.isEmpty());

        // Preset whose items are all absent from the pack adds 0 targets (and does not block a later preset)
        MilestoneData zeroOnlyData = new MilestoneData();
        assertEquals(0, MilestoneService.applyFirstJoinPresets(
                zeroOnlyData, PLAYER_A, List.of("all_missing"), knownItems::contains, true));
        MilestoneData chainedData = new MilestoneData();
        assertEquals(2, MilestoneService.applyFirstJoinPresets(
                chainedData, PLAYER_A, List.of("all_missing", "vanilla_endgame"), knownItems::contains, true));
        assertEquals(Set.of(TargetKey.of("minecraft:diamond"), TargetKey.of("minecraft:netherite_ingot")),
                chainedData.playerOrNull(PLAYER_A).targets);
    }

    @Test
    void pruneInactivePlayersPreviewsDeletesOnlyEligiblePlayersFallsBackToDatFileAndKeepsWorldFirsts(@TempDir Path playerDataDir) throws Exception {
        long now = 1_800_000_000_000L;
        int days = 30;
        long oldTime = now - (days * PruneDataLogic.MILLIS_PER_DAY) - 10_000L;
        long recentTime = now - (5L * PruneDataLogic.MILLIS_PER_DAY);

        UUID eligibleByLastSeen = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID eligibleByDatFile = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID eligibleWorldFirstHolder = UUID.fromString("00000000-0000-0000-0000-000000000003");
        UUID hasRecord = UUID.fromString("00000000-0000-0000-0000-000000000004");
        UUID hasTarget = UUID.fromString("00000000-0000-0000-0000-000000000005");
        UUID hasCreativeCarry = UUID.fromString("00000000-0000-0000-0000-000000000006");
        UUID hasMuteBroadcast = UUID.fromString("00000000-0000-0000-0000-000000000007");
        UUID hasHideFromLookup = UUID.fromString("00000000-0000-0000-0000-000000000008");
        UUID hasMuteProgress = UUID.fromString("00000000-0000-0000-0000-000000000009");
        UUID hasExcludeDiscord = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID currentlyOnline = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        UUID recentLastSeenWithOldDat = UUID.fromString("00000000-0000-0000-0000-00000000000c");
        UUID unknownLastSeenNoDat = UUID.fromString("00000000-0000-0000-0000-00000000000d");
        UUID recentDatOnly = UUID.fromString("00000000-0000-0000-0000-00000000000e");

        MilestoneData data = new MilestoneData();

        // 1. Eligible via lastSeen (firstJoinHintShown and lastKnownName do not block pruning)
        MilestoneData.PlayerData pd1 = data.player(eligibleByLastSeen);
        pd1.lastKnownName = "OldVisitor";
        pd1.firstJoinHintShown = true;
        pd1.lastSeen = oldTime;

        // 2. Eligible via vanilla <uuid>.dat mtime fallback (lastSeen == 0)
        MilestoneData.PlayerData pd2 = data.player(eligibleByDatFile);
        pd2.lastKnownName = "LegacyVisitor";
        Path oldDat = playerDataDir.resolve(eligibleByDatFile + ".dat");
        Files.writeString(oldDat, "nbt");
        Files.setLastModifiedTime(oldDat, FileTime.fromMillis(oldTime));

        // 3. Eligible player who also holds a world-first record (PlayerData pruned, world-first kept)
        MilestoneData.PlayerData pd3 = data.player(eligibleWorldFirstHolder);
        pd3.lastKnownName = "FormerChampion";
        pd3.lastSeen = oldTime;
        TargetKey diamond = TargetKey.of("minecraft:diamond");
        assertTrue(data.recordWorldFirstIfAbsent(diamond, eligibleWorldFirstHolder, "FormerChampion",
                new MilestoneRecord(1000, 1L, oldTime)));

        // 4..10. Ineligible due to records, personal targets, creative carry, or personal settings
        MilestoneData.PlayerData pdRec = data.player(hasRecord);
        pdRec.lastSeen = oldTime;
        pdRec.records.put(diamond, new MilestoneRecord(2000, 2L, oldTime));

        MilestoneData.PlayerData pdTgt = data.player(hasTarget);
        pdTgt.lastSeen = oldTime;
        pdTgt.targets.add(diamond);

        MilestoneData.PlayerData pdCarry = data.player(hasCreativeCarry);
        pdCarry.lastSeen = oldTime;
        pdCarry.carriedFromCreative.add("minecraft:diamond");

        MilestoneData.PlayerData pdMuteB = data.player(hasMuteBroadcast);
        pdMuteB.lastSeen = oldTime;
        pdMuteB.muteBroadcasts = true;

        MilestoneData.PlayerData pdHide = data.player(hasHideFromLookup);
        pdHide.lastSeen = oldTime;
        pdHide.hideFromLookup = true;

        MilestoneData.PlayerData pdMuteP = data.player(hasMuteProgress);
        pdMuteP.lastSeen = oldTime;
        pdMuteP.muteProgressAlerts = true;

        MilestoneData.PlayerData pdExcl = data.player(hasExcludeDiscord);
        pdExcl.lastSeen = oldTime;
        pdExcl.excludeFromDiscord = true;

        // 11. Ineligible: currently online
        MilestoneData.PlayerData pdOnline = data.player(currentlyOnline);
        pdOnline.lastSeen = oldTime;

        // 12. Ineligible: positive recent lastSeen wins even if .dat is old
        MilestoneData.PlayerData pdRecent = data.player(recentLastSeenWithOldDat);
        pdRecent.lastSeen = recentTime;
        Path oldDatIgnored = playerDataDir.resolve(recentLastSeenWithOldDat + ".dat");
        Files.writeString(oldDatIgnored, "nbt");
        Files.setLastModifiedTime(oldDatIgnored, FileTime.fromMillis(oldTime));

        // 13. Ineligible: lastSeen == 0 and no .dat file (unknown)
        data.player(unknownLastSeenNoDat).lastKnownName = "Unknown";

        // 14. Ineligible: lastSeen == 0 and .dat file is recent
        data.player(recentDatOnly).lastKnownName = "RecentLegacy";
        Path recentDat = playerDataDir.resolve(recentDatOnly + ".dat");
        Files.writeString(recentDat, "nbt");
        Files.setLastModifiedTime(recentDat, FileTime.fromMillis(recentTime));

        int totalBefore = data.allPlayers().size();
        assertEquals(14, totalBefore);

        // Preview (confirm = false): reports 3 targets and changes nothing
        int previewCount = MilestoneService.pruneInactivePlayers(
                data, currentlyOnline::equals, playerDataDir, now, days, false);
        assertEquals(3, previewCount);
        assertEquals(14, data.allPlayers().size());
        assertNotNull(data.playerOrNull(eligibleByLastSeen));
        assertNotNull(data.playerOrNull(eligibleByDatFile));
        assertNotNull(data.playerOrNull(eligibleWorldFirstHolder));

        // Confirm (confirm = true): deletes only the 3 eligible players, keeps everyone else and world firsts
        int deletedCount = MilestoneService.pruneInactivePlayers(
                data, currentlyOnline::equals, playerDataDir, now, days, true);
        assertEquals(3, deletedCount);
        assertEquals(11, data.allPlayers().size());
        assertNull(data.playerOrNull(eligibleByLastSeen));
        assertNull(data.playerOrNull(eligibleByDatFile));
        assertNull(data.playerOrNull(eligibleWorldFirstHolder));
        assertNotNull(data.playerOrNull(hasRecord));
        assertNotNull(data.playerOrNull(hasTarget));
        assertNotNull(data.playerOrNull(hasCreativeCarry));
        assertNotNull(data.playerOrNull(hasMuteBroadcast));
        assertNotNull(data.playerOrNull(hasHideFromLookup));
        assertNotNull(data.playerOrNull(hasMuteProgress));
        assertNotNull(data.playerOrNull(hasExcludeDiscord));
        assertNotNull(data.playerOrNull(currentlyOnline));
        assertNotNull(data.playerOrNull(recentLastSeenWithOldDat));
        assertNotNull(data.playerOrNull(unknownLastSeenNoDat));
        assertNotNull(data.playerOrNull(recentDatOnly));
        assertNotNull(data.worldFirstOrNull(diamond));
        assertEquals(eligibleWorldFirstHolder, data.worldFirstOrNull(diamond).playerUuid());

        // Verify en_us and ja_jp messages match spec verbatim
        assertEquals(
                "3 player(s) have no records, no personal targets and no personal settings, and were last seen more than 30 day(s) ago. Add 'confirm' to delete their data.",
                ServerLang.trForLang("en_us", "message.itemmilestonelog.prunedata_preview", 3, 30).getString());
        assertEquals(
                "3 人分のデータが対象です（記録・個人の目標・個人の設定が無く、最後に来てから 30 日を超えている）。消すには末尾に confirm を付けてください。",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.prunedata_preview", 3, 30).getString());
        assertEquals(
                "Deleted the data of 3 player(s).",
                ServerLang.trForLang("en_us", "message.itemmilestonelog.prunedata_done", 3).getString());
        assertEquals(
                "3 人分のデータを消しました。",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.prunedata_done", 3).getString());
    }

    @Test
    void tagCommandHelpersBuildTagKeysAndPickTheTagErrorMessage() {
        assertEquals("#c:ingots/iron", MilestoneService.tagKeyId("c:ingots/iron"));
        assertEquals("message.itemmilestonelog.invalid_tag", MilestoneService.invalidTargetKey("#c:ingots/iron"));
        assertEquals("message.itemmilestonelog.invalid_item", MilestoneService.invalidTargetKey("minecraft:diamond"));
        assertEquals("message.itemmilestonelog.invalid_item", MilestoneService.invalidTargetKey(null));
        assertEquals(
                "Invalid, unknown or empty item tag: #c:none",
                ServerLang.trForLang("en_us", "message.itemmilestonelog.invalid_tag", "#c:none").getString());
    }

    // ---- F24: renameitem ----

    @Test
    void renameItemInDataAppliesAllCollisionRulesPreservesCountsAndLeavesTagsUntouched() {
        String oldId = "oldmod:gem";
        String newId = "minecraft:diamond";
        String otherId = "minecraft:emerald";
        UUID playerB = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID playerC = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID playerD = UUID.fromString("44444444-4444-4444-4444-444444444444");

        MilestoneData data = new MilestoneData();

        // Player A: collisions on targets, records (old smaller & dest smaller & tie),
        // progressNotified (larger wins + recorded keys dropped), carriedFromCreative, tags untouched
        MilestoneData.PlayerData pdA = data.player(PLAYER_A);
        pdA.targets.add(TargetKey.of(oldId));
        pdA.targets.add(TargetKey.of(newId)); // collides with old#1 -> dedupe
        pdA.targets.add(TargetKey.of(oldId, 64)); // no collision -> new#64
        pdA.targets.add(TargetKey.of(otherId, 16));
        pdA.targets.add(TargetKey.of("#c:gems")); // tag untouched

        MilestoneRecord oldSmaller = new MilestoneRecord(100, 1L, 1_000L);
        MilestoneRecord destLarger = new MilestoneRecord(300, 3L, 3_000L);
        MilestoneRecord oldLarger = new MilestoneRecord(500, 5L, 5_000L);
        MilestoneRecord destSmaller = new MilestoneRecord(200, 2L, 2_000L);
        MilestoneRecord oldTie = new MilestoneRecord(400, 4L, 4_000L);
        MilestoneRecord destTie = new MilestoneRecord(400, 9L, 9_000L);
        MilestoneRecord oldOnlyRec = new MilestoneRecord(700, 7L, 7_000L);

        pdA.records.put(TargetKey.of(oldId, 1), oldSmaller);
        pdA.records.put(TargetKey.of(newId, 1), destLarger);
        pdA.records.put(TargetKey.of(oldId, 10), oldLarger);
        pdA.records.put(TargetKey.of(newId, 10), destSmaller);
        pdA.records.put(TargetKey.of(oldId, 20), oldTie);
        pdA.records.put(TargetKey.of(newId, 20), destTie);
        pdA.records.put(TargetKey.of(oldId, 50), oldOnlyRec); // becomes new#50 -> drops new#50 progressNotified

        pdA.progressNotified.put(TargetKey.of(oldId, 100), 75);
        pdA.progressNotified.put(TargetKey.of(newId, 100), 25); // old > new -> 75
        pdA.progressNotified.put(TargetKey.of(oldId, 200), 25);
        pdA.progressNotified.put(TargetKey.of(newId, 200), 90); // new > old -> 90
        pdA.progressNotified.put(TargetKey.of(oldId, 10), 50); // dest#10 recorded -> dropped
        pdA.progressNotified.put(TargetKey.of(newId, 50), 50); // old#50 recorded (now new#50) -> dropped
        pdA.progressNotified.put(TargetKey.of(otherId, 100), 50); // unrelated -> preserved

        pdA.carriedFromCreative.add(oldId);
        pdA.carriedFromCreative.add(newId); // deduped
        pdA.carriedFromCreative.add(otherId);

        // Player B: only carriedFromCreative matches oldId (still counts as 1 affected player)
        MilestoneData.PlayerData pdB = data.player(playerB);
        pdB.carriedFromCreative.add(oldId);

        // Player C: unrelated player data (not counted as affected player),
        // but holds a colliding destination world first so scoreboardPlayers must still include playerC
        MilestoneData.PlayerData pdC = data.player(playerC);
        pdC.targets.add(TargetKey.of(otherId));

        // Player D: completely unaffected
        MilestoneData.PlayerData pdD = data.player(playerD);
        pdD.targets.add(TargetKey.of(otherId));

        // World firsts covering all collision cases:
        // #1: both known, old earlier -> old wins (itemId updated to newId)
        data.recordWorldFirstIfAbsent(TargetKey.of(oldId, 1), PLAYER_A, "Alice", new MilestoneRecord(100, 1L, 1_000L));
        data.recordWorldFirstIfAbsent(TargetKey.of(newId, 1), playerC, "Carol", new MilestoneRecord(200, 2L, 2_000L));
        // #2: both known, dest earlier -> dest wins
        data.recordWorldFirstIfAbsent(TargetKey.of(oldId, 2), PLAYER_A, "Alice", new MilestoneRecord(100, 1L, 3_000L));
        data.recordWorldFirstIfAbsent(TargetKey.of(newId, 2), playerC, "Carol", new MilestoneRecord(200, 2L, 1_500L));
        // #3: old known, dest unknown (0) -> dest wins
        data.recordWorldFirstIfAbsent(TargetKey.of(oldId, 3), PLAYER_A, "Alice", new MilestoneRecord(100, 1L, 1_000L));
        data.recordWorldFirstIfAbsent(TargetKey.of(newId, 3), playerC, "Carol", new MilestoneRecord(200, 2L, 0L));
        // #4: old unknown (0), dest known -> dest wins
        data.recordWorldFirstIfAbsent(TargetKey.of(oldId, 4), PLAYER_A, "Alice", new MilestoneRecord(100, 1L, 0L));
        data.recordWorldFirstIfAbsent(TargetKey.of(newId, 4), playerC, "Carol", new MilestoneRecord(200, 2L, 5_000L));
        // #5: both unknown (0) -> dest wins
        data.recordWorldFirstIfAbsent(TargetKey.of(oldId, 5), PLAYER_A, "Alice", new MilestoneRecord(100, 1L, 0L));
        data.recordWorldFirstIfAbsent(TargetKey.of(newId, 5), playerC, "Carol", new MilestoneRecord(200, 2L, 0L));
        // #64: no collision -> moved with itemId updated to newId
        data.recordWorldFirstIfAbsent(TargetKey.of(oldId, 64), PLAYER_A, "Alice", new MilestoneRecord(700, 7L, 7_000L));

        // Preview (apply = false): returns exact counts and changes NOTHING
        MilestoneService.RenameCounts preview = MilestoneService.renameItemInData(data, oldId, newId, false);
        assertEquals(2, preview.players());
        assertEquals(2, preview.targets());
        assertEquals(4, preview.records());
        assertEquals(6, preview.worldFirsts());
        assertEquals(Set.of(PLAYER_A, playerB), preview.affectedPlayers());
        assertEquals(Set.of(PLAYER_A, playerB, playerC), preview.scoreboardPlayers());
        assertTrue(pdA.targets.contains(TargetKey.of(oldId)));
        assertTrue(pdA.records.containsKey(TargetKey.of(oldId, 1)));
        assertTrue(pdB.carriedFromCreative.contains(oldId));
        assertNotNull(data.worldFirstOrNull(TargetKey.of(oldId, 1)));

        // Apply (apply = true): commits all rewrites with exact collision winners
        MilestoneService.RenameCounts applied = MilestoneService.renameItemInData(data, oldId, newId);
        assertEquals(preview, applied);

        assertEquals(Set.of(
                TargetKey.of(newId, 1),
                TargetKey.of(newId, 64),
                TargetKey.of(otherId, 16),
                TargetKey.of("#c:gems")
        ), pdA.targets);

        assertEquals(Map.of(
                TargetKey.of(newId, 1), oldSmaller,
                TargetKey.of(newId, 10), destSmaller,
                TargetKey.of(newId, 20), destTie,
                TargetKey.of(newId, 50), oldOnlyRec
        ), pdA.records);

        assertEquals(Map.of(
                TargetKey.of(newId, 100), 75,
                TargetKey.of(newId, 200), 90,
                TargetKey.of(otherId, 100), 50
        ), pdA.progressNotified);

        assertEquals(Set.of(newId, otherId), pdA.carriedFromCreative);
        assertEquals(Set.of(newId), pdB.carriedFromCreative);
        assertEquals(Set.of(TargetKey.of(otherId)), pdC.targets);
        assertEquals(Set.of(TargetKey.of(otherId)), pdD.targets);

        // Verify world firsts & updated WorldFirstRecord.itemId()
        assertNull(data.worldFirstOrNull(TargetKey.of(oldId, 1)));
        assertNull(data.worldFirstOrNull(TargetKey.of(oldId, 64)));

        WorldFirstRecord wf1 = data.worldFirstOrNull(TargetKey.of(newId, 1));
        assertNotNull(wf1);
        assertEquals(newId, wf1.itemId());
        assertEquals(PLAYER_A, wf1.playerUuid());
        assertEquals(1_000L, wf1.epochMillis());

        assertEquals(playerC, data.worldFirstOrNull(TargetKey.of(newId, 2)).playerUuid());
        assertEquals(playerC, data.worldFirstOrNull(TargetKey.of(newId, 3)).playerUuid());
        assertEquals(playerC, data.worldFirstOrNull(TargetKey.of(newId, 4)).playerUuid());
        assertEquals(playerC, data.worldFirstOrNull(TargetKey.of(newId, 5)).playerUuid());

        WorldFirstRecord wf64 = data.worldFirstOrNull(TargetKey.of(newId, 64));
        assertNotNull(wf64);
        assertEquals(newId, wf64.itemId());
        assertEquals(64, wf64.count());
        assertEquals(PLAYER_A, wf64.playerUuid());
    }

    @Test
    void executeRenameItemValidatesArgsBacksUpFirstAbortsOnBackupFailureAndAppendsConfigNote() {
        String oldId = "oldmod:gem";
        String newId = "minecraft:diamond";
        Set<String> registered = Set.of(newId, "minecraft:emerald");

        MilestoneData data = new MilestoneData();
        MilestoneData.PlayerData pd = data.player(PLAYER_A);
        pd.targets.add(TargetKey.of(oldId));
        pd.records.put(TargetKey.of(oldId), new MilestoneRecord(120, 1L, 1_000L));
        data.recordWorldFirstIfAbsent(TargetKey.of(oldId), PLAYER_A, "Alice", new MilestoneRecord(120, 1L, 1_000L));

        AtomicInteger backupCalls = new AtomicInteger();

        // Same ID (including implicit minecraft: normalization) -> renameitem_same
        MilestoneService.ActionResult same = MilestoneService.executeRenameItem(
                data, null, "diamond", newId, false, registered::contains,
                () -> { backupCalls.incrementAndGet(); return true; }, Set.of(), List.of()).result();
        assertFalse(same.success());
        assertEquals("The old and new IDs are the same.", same.message().getString());

        // Either side minecraft:air or unregistered newId or tag -> invalid_item
        assertEquals("Invalid or unknown item ID: minecraft:air", MilestoneService.executeRenameItem(
                data, null, "minecraft:air", newId, false, registered::contains, () -> true, Set.of(), List.of())
                .result().message().getString());
        assertEquals("Invalid or unknown item ID: minecraft:air", MilestoneService.executeRenameItem(
                data, null, oldId, "minecraft:air", false, registered::contains, () -> true, Set.of(), List.of())
                .result().message().getString());
        assertEquals("Invalid or unknown item ID: othermod:missing", MilestoneService.executeRenameItem(
                data, null, oldId, "othermod:missing", false, registered::contains, () -> true, Set.of(), List.of())
                .result().message().getString());
        assertFalse(MilestoneService.executeRenameItem(
                data, null, "#c:gems", newId, false, registered::contains, () -> true, Set.of(), List.of())
                .result().success());
        assertEquals(0, backupCalls.get());

        // Preview with defaultTargets containing oldId: changes nothing, skips backup, appends config note
        Set<TargetKey> defaultTargets = Set.of(TargetKey.of(oldId, 16));
        MilestoneService.ActionResult previewWithNote = MilestoneService.executeRenameItem(
                data, null, oldId, newId, false, registered::contains,
                () -> { backupCalls.incrementAndGet(); return true; }, defaultTargets, List.of()).result();
        assertTrue(previewWithNote.success());
        assertEquals(0, backupCalls.get());
        assertTrue(pd.targets.contains(TargetKey.of(oldId)));
        assertEquals(
                "Renaming oldmod:gem to minecraft:diamond would change 1 player(s): 1 target(s), 1 record(s), 1 world first(s). "
                        + "Add 'confirm' to apply (a backup is written first).\n"
                        + "defaultTargets or a preset still contains oldmod:gem. Edit them by hand.",
                previewWithNote.message().getString());

        // Confirm when backup fails -> renameitem_backup_failed and changes NOTHING
        MilestoneService.ActionResult backupFailed = MilestoneService.executeRenameItem(
                data, null, oldId, newId, true, registered::contains,
                () -> { backupCalls.incrementAndGet(); return false; }, defaultTargets, List.of()).result();
        assertFalse(backupFailed.success());
        assertEquals(1, backupCalls.get());
        assertEquals("Could not write a backup, so nothing was changed.", backupFailed.message().getString());
        assertTrue(pd.targets.contains(TargetKey.of(oldId)));
        assertTrue(pd.records.containsKey(TargetKey.of(oldId)));
        assertNotNull(data.worldFirstOrNull(TargetKey.of(oldId)));

        // Confirm when backup succeeds -> backup runs BEFORE mutation, preset triggers config note, preset itself untouched
        Presets.PresetDefinition presetWithOld = new Presets.PresetDefinition(
                "custom_pack", "Custom Pack", List.of(TargetKey.of(oldId)), false);
        MilestoneService.ActionResult applied = MilestoneService.executeRenameItem(
                data, null, oldId, newId, true, registered::contains,
                () -> {
                    // Backup must see pre-rename state
                    assertTrue(pd.targets.contains(TargetKey.of(oldId)), "Backup must run before mutating data");
                    backupCalls.incrementAndGet();
                    return true;
                },
                Set.of(), List.of(presetWithOld)).result();
        assertTrue(applied.success());
        assertEquals(2, backupCalls.get());
        assertEquals(
                "Renamed oldmod:gem to minecraft:diamond for 1 player(s): 1 target(s), 1 record(s), 1 world first(s).\n"
                        + "defaultTargets or a preset still contains oldmod:gem. Edit them by hand.",
                applied.message().getString());
        assertTrue(pd.targets.contains(TargetKey.of(newId)));
        assertFalse(pd.targets.contains(TargetKey.of(oldId)));
        // Preset not rewritten
        assertEquals(List.of(TargetKey.of(oldId)), presetWithOld.items());

        // Confirm on empty MilestoneData succeeds with 0s without failing on empty-backup refusal
        MilestoneData empty = new MilestoneData();
        MilestoneService.ActionResult emptyRes = MilestoneService.executeRenameItem(
                empty, null, oldId, newId, true, registered::contains, () -> false, Set.of(), List.of()).result();
        assertTrue(emptyRes.success());
        assertFalse(empty.isDirty());
        assertEquals("Renamed oldmod:gem to minecraft:diamond for 0 player(s): 0 target(s), 0 record(s), 0 world first(s).",
                emptyRes.message().getString());

        // Verify Japanese translations match spec verbatim
        assertEquals(
                "oldmod:gem を minecraft:diamond に付け替えると、2 人分（目標 3 件、記録 4 件、世界初 1 件）が変わります。実行するには末尾に confirm を付けてください（先にバックアップを書きます）。",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.renameitem_preview",
                        "oldmod:gem", "minecraft:diamond", 2, 3, 4, 1).getString());
        assertEquals(
                "oldmod:gem を minecraft:diamond に付け替えました: 2 人分（目標 3 件、記録 4 件、世界初 1 件）。",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.renameitem_done",
                        "oldmod:gem", "minecraft:diamond", 2, 3, 4, 1).getString());
        assertEquals(
                "付け替え前と後の ID が同じです。",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.renameitem_same").getString());
        assertEquals(
                "バックアップを書けなかったため、何も変更していません。",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.renameitem_backup_failed").getString());
        assertEquals(
                "defaultTargets かプリセットに oldmod:gem が残っています。手で直してください。",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.renameitem_config_note", "oldmod:gem").getString());
    }
}
