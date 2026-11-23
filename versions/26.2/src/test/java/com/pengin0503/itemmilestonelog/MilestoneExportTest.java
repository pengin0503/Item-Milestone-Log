package com.pengin0503.itemmilestonelog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MilestoneExportTest {
    @Test
    void validatesSafeExportFileNames() {
        assertTrue(MilestoneExport.isSafeFileName("Steve.json"));
        assertTrue(MilestoneExport.isSafeFileName("player_1-test.json"));
        assertFalse(MilestoneExport.isSafeFileName(""));
        assertFalse(MilestoneExport.isSafeFileName("../secret.json"));
        assertFalse(MilestoneExport.isSafeFileName("sub/file.json"));
        assertFalse(MilestoneExport.isSafeFileName("sub\\file.json"));
        assertFalse(MilestoneExport.isSafeFileName("bad name.json"));
    }

    @Test
    void exportsAndMergesKeepingSmallerPlayTicks(@TempDir Path dir) throws Exception {
        UUID uuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
        TargetKey diamond1 = TargetKey.of("minecraft:diamond");
        TargetKey diamond100 = TargetKey.of("minecraft:diamond", 100);
        TargetKey emerald1 = TargetKey.of("minecraft:emerald");
        TargetKey iron1 = TargetKey.of("minecraft:iron_ingot");

        MilestoneData sourceData = new MilestoneData();
        MilestoneData.PlayerData srcPd = sourceData.player(uuid);
        srcPd.lastKnownName = "Alice";
        srcPd.targets.add(diamond1);
        srcPd.targets.add(diamond100);
        srcPd.targets.add(emerald1);
        MilestoneRecord fasterDiamond = new MilestoneRecord(1000, 3L, 1795500000000L);
        MilestoneRecord diamond100Rec = new MilestoneRecord(25000, 12L, 1795650000000L);
        MilestoneRecord slowerEmerald = new MilestoneRecord(9000, 8L, 1795600000000L);
        srcPd.records.put(diamond1, fasterDiamond);
        srcPd.records.put(diamond100, diamond100Rec);
        srcPd.records.put(emerald1, slowerEmerald);

        String rel = MilestoneExport.exportPlayer(dir, uuid, "Alice", srcPd);
        assertEquals("itemmilestonelog/exports/Alice.json", rel);
        Path exportedFile = dir.resolve("Alice.json");
        assertTrue(Files.isRegularFile(exportedFile));

        MilestoneData targetData = new MilestoneData();
        MilestoneData.PlayerData dstPd = targetData.player(uuid);
        dstPd.targets.add(diamond1);
        dstPd.targets.add(iron1);
        dstPd.records.put(diamond1, new MilestoneRecord(5000, 5L, 1795700000000L));
        MilestoneRecord fasterEmerald = new MilestoneRecord(2000, 2L, 1795400000000L);
        dstPd.records.put(emerald1, fasterEmerald);

        MilestoneExport.ImportPayload payload = MilestoneExport.parseSinglePlayerExport(exportedFile);
        MilestoneExport.ImportMergeStats stats = MilestoneExport.mergeInto(targetData, uuid, payload);

        assertEquals(2, stats.addedTargets());
        assertEquals(2, stats.updatedRecords());
        assertEquals(List.of(diamond1, iron1, diamond100, emerald1), List.copyOf(dstPd.targets));
        assertEquals(fasterDiamond, dstPd.records.get(diamond1));
        assertEquals(diamond100Rec, dstPd.records.get(diamond100));
        assertEquals(fasterEmerald, dstPd.records.get(emerald1));
    }

    @Test
    void importsLegacyCountlessExportJsonAsCountOne(@TempDir Path dir) throws Exception {
        Path legacyFile = dir.resolve("Legacy.json");
        Files.writeString(legacyFile, """
                {
                  "format": 1,
                  "player": { "uuid": "11111111-1111-1111-1111-111111111111", "name": "Legacy" },
                  "targets": ["minecraft:diamond"],
                  "records": {
                    "minecraft:diamond": { "ticks": 1234, "day": 2, "epoch": 1795600000000 }
                  }
                }
                """);
        MilestoneExport.ImportPayload payload = MilestoneExport.parseSinglePlayerExport(legacyFile);
        assertEquals(List.of(TargetKey.of("minecraft:diamond")), payload.targets());
        assertEquals(new MilestoneRecord(1234, 2L, 1795600000000L), payload.records().get(TargetKey.of("minecraft:diamond")));
    }

    @Test
    void skipsInvalidCountsDuringImportWithoutFailingValidEntries(@TempDir Path dir) throws Exception {
        Path mixedFile = dir.resolve("Mixed.json");
        Files.writeString(mixedFile, """
                {
                  "format": 1,
                  "player": { "uuid": "11111111-1111-1111-1111-111111111111", "name": "Mixed" },
                  "targets": [
                    "minecraft:diamond",
                    "minecraft:gold_ingot#0",
                    { "item": "minecraft:iron_ingot", "count": 1000001 },
                    { "item": "minecraft:emerald", "count": 50 }
                  ],
                  "records": {
                    "minecraft:diamond": { "ticks": 100, "day": 1, "epoch": 1795600000000 },
                    "minecraft:gold_ingot#0": { "ticks": 200, "day": 1, "epoch": 1795600000000 },
                    "minecraft:iron_ingot#10": { "ticks": 300, "day": 1, "epoch": 1795600000000, "count": -5 },
                    "minecraft:emerald#50": { "ticks": 400, "day": 2, "epoch": 1795600000000, "count": 50 }
                  }
                }
                """);
        MilestoneExport.ImportPayload payload = MilestoneExport.parseSinglePlayerExport(mixedFile);
        assertEquals(List.of(TargetKey.of("minecraft:diamond"), TargetKey.of("minecraft:emerald", 50)), payload.targets());
        assertEquals(2, payload.records().size());
        assertEquals(100, payload.records().get(TargetKey.of("minecraft:diamond")).playTicks());
        assertEquals(400, payload.records().get(TargetKey.of("minecraft:emerald", 50)).playTicks());
    }

    @Test
    void rejectsUnsupportedFormatAndExportAllSchema(@TempDir Path dir) throws Exception {
        Path badFormat = dir.resolve("v2.json");
        Files.writeString(badFormat, """
                { "format": 2, "targets": [], "records": {} }
                """);
        assertThrows(IllegalArgumentException.class, () -> MilestoneExport.parseSinglePlayerExport(badFormat));

        MilestoneData data = new MilestoneData();
        UUID playerUuid = UUID.fromString("33333333-3333-3333-3333-333333333333");
        MilestoneData.PlayerData pd = data.player(playerUuid);
        pd.lastKnownName = "Charlie";
        pd.targets.add(TargetKey.of("minecraft:diamond"));
        pd.records.put(TargetKey.of("minecraft:diamond"), new MilestoneRecord(777, 4L, 1795600000000L));
        MilestoneExport.exportAll(dir, data);
        Path allFile = dir.resolve("all-players.json");
        assertTrue(Files.isRegularFile(allFile));
        assertThrows(IllegalArgumentException.class, () -> MilestoneExport.parseSinglePlayerExport(allFile));

        JsonObject allJson = JsonParser.parseString(Files.readString(allFile)).getAsJsonObject();
        assertEquals(MilestoneExport.FORMAT_VERSION, allJson.get("format").getAsInt());
        JsonArray playersArr = allJson.getAsJsonArray("players");
        assertEquals(1, playersArr.size());
        JsonObject p0 = playersArr.get(0).getAsJsonObject();
        assertEquals(playerUuid.toString(), p0.getAsJsonObject("player").get("uuid").getAsString());
        assertEquals("Charlie", p0.getAsJsonObject("player").get("name").getAsString());
        assertEquals("minecraft:diamond", p0.getAsJsonArray("targets").get(0).getAsString());
        assertEquals(777, p0.getAsJsonObject("records").getAsJsonObject("minecraft:diamond").get("ticks").getAsInt());
    }

    @Test
    void enforcesOwnerUuidCheckUnlessPrivileged(@TempDir Path dir) throws Exception {
        UUID alice = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID bob = UUID.fromString("22222222-2222-2222-2222-222222222222");

        MilestoneData data = new MilestoneData();
        MilestoneExport.exportPlayer(dir, alice, "Alice", data.player(alice));
        MilestoneExport.ImportPayload alicePayload = MilestoneExport.parseSinglePlayerExport(dir.resolve("Alice.json"));

        assertEquals(alice, alicePayload.ownerUuid());
        assertTrue(MilestoneExport.canImport(alicePayload, alice, false));
        assertFalse(MilestoneExport.canImport(alicePayload, bob, false));
        assertTrue(MilestoneExport.canImport(alicePayload, bob, true));

        Path noOwnerFile = dir.resolve("NoOwner.json");
        Files.writeString(noOwnerFile, """
                { "format": 1, "targets": ["minecraft:diamond"], "records": {} }
                """);
        MilestoneExport.ImportPayload noOwnerPayload = MilestoneExport.parseSinglePlayerExport(noOwnerFile);
        assertFalse(MilestoneExport.canImport(noOwnerPayload, alice, false));
        assertTrue(MilestoneExport.canImport(noOwnerPayload, alice, true));

        Path badUuidFile = dir.resolve("BadUuid.json");
        Files.writeString(badUuidFile, """
                { "format": 1, "player": { "uuid": "not-a-uuid" }, "targets": [], "records": {} }
                """);
        assertThrows(IllegalArgumentException.class, () -> MilestoneExport.parseSinglePlayerExport(badUuidFile));
    }

    @Test
    void handlesMergeBoundariesEqualPlayTicksConflictingCountsAndStrictNumbers(@TempDir Path dir) throws Exception {
        Path fractionalFormat = dir.resolve("frac_format.json");
        Files.writeString(fractionalFormat, """
                { "format": 1.5, "targets": [], "records": {} }
                """);
        assertThrows(IllegalArgumentException.class, () -> MilestoneExport.parseSinglePlayerExport(fractionalFormat));

        Path edgeRecords = dir.resolve("edge_records.json");
        Files.writeString(edgeRecords, """
                {
                  "format": 1,
                  "targets": ["minecraft:diamond"],
                  "records": {
                    "minecraft:diamond": { "ticks": 500, "day": 2, "epoch": 1795600000000 },
                    "minecraft:emerald#10": { "ticks": 300, "day": 1, "epoch": 1795600000000, "count": 20 },
                    "minecraft:gold_ingot": { "ticks": 100.5, "day": 1, "epoch": 1795600000000 },
                    "minecraft:iron_ingot": { "ticks": 999999999999, "day": 1, "epoch": 1795600000000 }
                  }
                }
                """);
        MilestoneExport.ImportPayload payload = MilestoneExport.parseSinglePlayerExport(edgeRecords);
        assertEquals(1, payload.records().size());
        assertEquals(500, payload.records().get(TargetKey.of("minecraft:diamond")).playTicks());

        // Merging into an unregistered player creates PlayerData; equal playTicks does not overwrite existing record
        MilestoneData data = new MilestoneData();
        UUID newPlayer = UUID.fromString("44444444-4444-4444-4444-444444444444");
        MilestoneExport.ImportMergeStats firstStats = MilestoneExport.mergeInto(data, newPlayer, payload);
        assertEquals(1, firstStats.addedTargets());
        assertEquals(1, firstStats.updatedRecords());

        // Merging same playTicks (500 == 500) must not count as an update
        MilestoneExport.ImportMergeStats equalStats = MilestoneExport.mergeInto(data, newPlayer, payload);
        assertEquals(0, equalStats.addedTargets());
        assertEquals(0, equalStats.updatedRecords());
    }
}
