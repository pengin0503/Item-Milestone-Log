package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
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
        MilestoneData sourceData = new MilestoneData();
        MilestoneData.PlayerData srcPd = sourceData.player(uuid);
        srcPd.lastKnownName = "Alice";
        srcPd.targets.add("minecraft:diamond");
        srcPd.targets.add("minecraft:emerald");
        MilestoneRecord fasterDiamond = new MilestoneRecord(1000, 3L, 1795500000000L);
        MilestoneRecord slowerEmerald = new MilestoneRecord(9000, 8L, 1795600000000L);
        srcPd.records.put("minecraft:diamond", fasterDiamond);
        srcPd.records.put("minecraft:emerald", slowerEmerald);

        String rel = MilestoneExport.exportPlayer(dir, uuid, "Alice", srcPd);
        assertEquals("itemmilestonelog/exports/Alice.json", rel);
        Path exportedFile = dir.resolve("Alice.json");
        assertTrue(Files.isRegularFile(exportedFile));

        MilestoneData targetData = new MilestoneData();
        MilestoneData.PlayerData dstPd = targetData.player(uuid);
        dstPd.targets.add("minecraft:diamond");
        dstPd.targets.add("minecraft:iron_ingot");
        dstPd.records.put("minecraft:diamond", new MilestoneRecord(5000, 5L, 1795700000000L));
        MilestoneRecord fasterEmerald = new MilestoneRecord(2000, 2L, 1795400000000L);
        dstPd.records.put("minecraft:emerald", fasterEmerald);

        MilestoneExport.ImportPayload payload = MilestoneExport.parseSinglePlayerExport(exportedFile);
        MilestoneExport.ImportMergeStats stats = MilestoneExport.mergeInto(targetData, uuid, payload);

        assertEquals(1, stats.addedTargets());
        assertEquals(1, stats.updatedRecords());
        assertEquals(List.of("minecraft:diamond", "minecraft:iron_ingot", "minecraft:emerald"), List.copyOf(dstPd.targets));
        assertEquals(fasterDiamond, dstPd.records.get("minecraft:diamond"));
        assertEquals(fasterEmerald, dstPd.records.get("minecraft:emerald"));
    }

    @Test
    void rejectsUnsupportedFormatAndExportAllSchema(@TempDir Path dir) throws Exception {
        Path badFormat = dir.resolve("v2.json");
        Files.writeString(badFormat, """
                { "format": 2, "targets": [], "records": {} }
                """);
        assertThrows(IllegalArgumentException.class, () -> MilestoneExport.parseSinglePlayerExport(badFormat));

        MilestoneData data = new MilestoneData();
        data.player(UUID.randomUUID()).targets.add("minecraft:diamond");
        MilestoneExport.exportAll(dir, data);
        Path allFile = dir.resolve("all-players.json");
        assertTrue(Files.isRegularFile(allFile));
        assertThrows(IllegalArgumentException.class, () -> MilestoneExport.parseSinglePlayerExport(allFile));
    }
}
