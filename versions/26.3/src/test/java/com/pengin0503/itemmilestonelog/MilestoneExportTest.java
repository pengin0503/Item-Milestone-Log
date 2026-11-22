package com.pengin0503.itemmilestonelog;

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
    void rejectsUnsupportedFormatAndExportAllSchema(@TempDir Path dir) throws Exception {
        Path badFormat = dir.resolve("v2.json");
        Files.writeString(badFormat, """
                { "format": 2, "targets": [], "records": {} }
                """);
        assertThrows(IllegalArgumentException.class, () -> MilestoneExport.parseSinglePlayerExport(badFormat));

        MilestoneData data = new MilestoneData();
        data.player(UUID.randomUUID()).targets.add(TargetKey.of("minecraft:diamond"));
        MilestoneExport.exportAll(dir, data);
        Path allFile = dir.resolve("all-players.json");
        assertTrue(Files.isRegularFile(allFile));
        assertThrows(IllegalArgumentException.class, () -> MilestoneExport.parseSinglePlayerExport(allFile));
    }
}
