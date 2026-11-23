package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
}
