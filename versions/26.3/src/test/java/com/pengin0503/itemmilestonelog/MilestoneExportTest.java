package com.pengin0503.itemmilestonelog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.api.WorldFirstRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void playerExportNamesAreStableAndUniqueForCollidingSanitizedNames(@TempDir Path dir) throws Exception {
        UUID alice = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID bob = UUID.fromString("22222222-2222-2222-2222-222222222222");
        String aliceName = "Bob!";
        String bobName = "Bob?";

        // #137: the sanitizer alone maps both names to the same base, so later exports overwrite.
        String legacyBase = MilestoneExport.sanitizePlayerFileBaseName(aliceName);
        assertEquals(legacyBase, MilestoneExport.sanitizePlayerFileBaseName(bobName));

        String aliceJson = MilestoneExport.sanitizePlayerFileName(aliceName, alice);
        String bobJson = MilestoneExport.sanitizePlayerFileName(bobName, bob);
        assertNotEquals(aliceJson, bobJson);
        assertEquals(aliceJson, MilestoneExport.sanitizePlayerFileName(aliceName, alice));
        assertTrue(aliceJson.startsWith(legacyBase + "-"));
        assertTrue(MilestoneExport.isSafeFileName(aliceJson));
        assertNotEquals(aliceJson, MilestoneExport.sanitizePlayerFileName(aliceName, bob));
        assertNotEquals(MilestoneExport.sanitizePlayerHtmlFileName(aliceName, alice),
                MilestoneExport.sanitizePlayerHtmlFileName(bobName, bob));
        assertNotEquals(MilestoneExport.sanitizePlayerCsvFileName(aliceName, alice),
                MilestoneExport.sanitizePlayerCsvFileName(bobName, bob));

        MilestoneData data = new MilestoneData();
        MilestoneData.PlayerData alicePd = data.player(alice);
        alicePd.lastKnownName = aliceName;
        MilestoneData.PlayerData bobPd = data.player(bob);
        bobPd.lastKnownName = bobName;

        String aliceJsonRel = MilestoneExport.exportPlayer(dir, alice, aliceName, alicePd);
        String bobJsonRel = MilestoneExport.exportPlayer(dir, bob, bobName, bobPd);
        assertNotEquals(aliceJsonRel, bobJsonRel);
        assertEquals("itemmilestonelog/exports/" + aliceJson, aliceJsonRel);
        assertEquals("itemmilestonelog/exports/" + bobJson, bobJsonRel);
        assertEquals(alice, MilestoneExport.parseSinglePlayerExport(dir.resolve(aliceJson)).ownerUuid());
        assertEquals(bob, MilestoneExport.parseSinglePlayerExport(dir.resolve(bobJson)).ownerUuid());

        String aliceHtml = MilestoneExport.sanitizePlayerHtmlFileName(aliceName, alice);
        String bobHtml = MilestoneExport.sanitizePlayerHtmlFileName(bobName, bob);
        String aliceHtmlRel = MilestoneExport.exportPlayerHtml(dir, alice, aliceName, alicePd,
                Set.of(), Map.of(), id -> id, Instant.EPOCH, ZoneId.of("UTC"));
        String bobHtmlRel = MilestoneExport.exportPlayerHtml(dir, bob, bobName, bobPd,
                Set.of(), Map.of(), id -> id, Instant.EPOCH, ZoneId.of("UTC"));
        assertNotEquals(aliceHtmlRel, bobHtmlRel);
        assertTrue(Files.isRegularFile(dir.resolve(aliceHtml)));
        assertTrue(Files.isRegularFile(dir.resolve(bobHtml)));

        String aliceCsv = MilestoneExport.sanitizePlayerCsvFileName(aliceName, alice);
        String bobCsv = MilestoneExport.sanitizePlayerCsvFileName(bobName, bob);
        String aliceCsvRel = MilestoneExport.exportPlayerCsv(dir, alice, aliceName, alicePd,
                Map.of(), id -> id, ZoneId.of("UTC"));
        String bobCsvRel = MilestoneExport.exportPlayerCsv(dir, bob, bobName, bobPd,
                Map.of(), id -> id, ZoneId.of("UTC"));
        assertNotEquals(aliceCsvRel, bobCsvRel);
        assertTrue(Files.isRegularFile(dir.resolve(aliceCsv)));
        assertTrue(Files.isRegularFile(dir.resolve(bobCsv)));
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
        String exportedName = MilestoneExport.sanitizePlayerFileName("Alice", uuid);
        assertEquals("itemmilestonelog/exports/" + exportedName, rel);
        Path exportedFile = dir.resolve(exportedName);
        assertTrue(Files.isRegularFile(exportedFile));

        MilestoneData targetData = new MilestoneData();
        MilestoneData.PlayerData dstPd = targetData.player(uuid);
        dstPd.targets.add(diamond1);
        dstPd.targets.add(iron1);
        dstPd.records.put(diamond1, new MilestoneRecord(5000, 5L, 1795700000000L));
        MilestoneRecord fasterEmerald = new MilestoneRecord(2000, 2L, 1795400000000L);
        dstPd.records.put(emerald1, fasterEmerald);
        dstPd.completedPresets.add("vanilla_endgame");

        MilestoneExport.ImportPayload payload = MilestoneExport.parseSinglePlayerExport(exportedFile);
        MilestoneExport.ImportMergeStats stats = MilestoneExport.mergeInto(targetData, uuid, payload);

        assertEquals(2, stats.addedTargets());
        assertEquals(2, stats.updatedRecords());
        assertEquals(List.of(diamond1, iron1, diamond100, emerald1), List.copyOf(dstPd.targets));
        assertEquals(fasterDiamond, dstPd.records.get(diamond1));
        assertEquals(diamond100Rec, dstPd.records.get(diamond100));
        assertEquals(fasterEmerald, dstPd.records.get(emerald1));
        assertEquals(List.of("vanilla_endgame"), List.copyOf(dstPd.completedPresets));
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
        MilestoneExport.ImportPayload alicePayload = MilestoneExport.parseSinglePlayerExport(
                dir.resolve(MilestoneExport.sanitizePlayerFileName("Alice", alice)));

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

    @Test
    void normalizesImplicitNamespacesAndAcceptsTagSyntaxDuringImport(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("TagsAndShortIds.json");
        Files.writeString(file, """
                {
                  "format": 1,
                  "targets": [
                    "diamond",
                    { "item": "emerald", "count": 5 },
                    "#c:ingots/iron",
                    "#c:ingots/iron#10",
                    { "item": "#minecraft:logs", "count": 64 },
                    "#",
                    "##c:x",
                    "#c:x#0",
                    { "item": "#c:x#", "count": 2 }
                  ],
                  "records": {
                    "diamond": { "ticks": 120, "day": 1, "epoch": 1795600000000 },
                    "#c:ingots/iron": { "ticks": 240, "day": 1, "epoch": 1795600000000 },
                    "#c:ingots/iron#10": { "ticks": 360, "day": 2, "epoch": 1795600000000 },
                    "#c:ingots/gold": { "ticks": 480, "day": 3, "epoch": 1795600000000, "count": 20 },
                    "#c:ingots/copper#10": { "ticks": 500, "day": 3, "epoch": 1795600000000, "count": 20 },
                    "##c:x": { "ticks": 600, "day": 3, "epoch": 1795600000000 }
                  }
                }
                """);
        MilestoneExport.ImportPayload payload = MilestoneExport.parseSinglePlayerExport(file);
        assertEquals(List.of(
                TargetKey.of("minecraft:diamond"),
                TargetKey.of("minecraft:emerald", 5),
                TargetKey.of("#c:ingots/iron"),
                TargetKey.of("#c:ingots/iron", 10),
                TargetKey.of("#minecraft:logs", 64)
        ), payload.targets());
        assertEquals(4, payload.records().size());
        assertEquals(120, payload.records().get(TargetKey.of("minecraft:diamond")).playTicks());
        assertEquals(240, payload.records().get(TargetKey.of("#c:ingots/iron")).playTicks());
        assertEquals(360, payload.records().get(TargetKey.of("#c:ingots/iron", 10)).playTicks());
        assertEquals(480, payload.records().get(TargetKey.of("#c:ingots/gold", 20)).playTicks());
    }

    @Test
    void sanitizesPlayerFileNamesForJsonAndHtml() {
        assertEquals("Steve.json", MilestoneExport.sanitizePlayerFileName("Steve"));
        assertEquals("Steve.html", MilestoneExport.sanitizePlayerHtmlFileName("Steve"));
        assertEquals("Steve.html", MilestoneExport.sanitizePlayerFileName("Steve", "html"));
        assertEquals("Steve.html", MilestoneExport.sanitizePlayerFileName("Steve", ".html"));

        assertEquals("player.json", MilestoneExport.sanitizePlayerFileName(null));
        assertEquals("player.html", MilestoneExport.sanitizePlayerHtmlFileName(null));
        assertEquals("player.html", MilestoneExport.sanitizePlayerHtmlFileName(""));
        assertEquals("player.html", MilestoneExport.sanitizePlayerHtmlFileName("   "));
        assertEquals("player.html", MilestoneExport.sanitizePlayerHtmlFileName("."));
        assertEquals("_.html", MilestoneExport.sanitizePlayerHtmlFileName("..."));

        // all-players collision rule (case-insensitive, including trailing dots stripped first).
        assertEquals("all-players-player.json", MilestoneExport.sanitizePlayerFileName("all-players"));
        assertEquals("all-players-player.html", MilestoneExport.sanitizePlayerHtmlFileName("all-players"));
        assertEquals("all-players-player.html", MilestoneExport.sanitizePlayerHtmlFileName("ALL-PLAYERS"));
        assertEquals("all-players-player.html", MilestoneExport.sanitizePlayerHtmlFileName("All-Players."));

        // Path traversal, repeated dots, trailing dots, and special characters.
        String traversal = MilestoneExport.sanitizePlayerHtmlFileName("../secret");
        assertTrue(traversal.endsWith(".html"));
        assertTrue(MilestoneExport.isSafeFileName(traversal));
        assertFalse(traversal.contains(".."));

        String special = MilestoneExport.sanitizePlayerHtmlFileName("<Alice & \"Bob\">.");
        assertTrue(special.endsWith(".html"));
        assertTrue(MilestoneExport.isSafeFileName(special));
        assertFalse(special.contains("<"));
    }

    @Test
    void exportsSinglePlayerHtmlWithCompletionWorldFirstTimezoneAndEscaping(@TempDir Path dir) throws Exception {
        UUID alice = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID bob = UUID.fromString("22222222-2222-2222-2222-222222222222");
        TargetKey diamond1 = TargetKey.of("minecraft:diamond");
        TargetKey diamond64 = TargetKey.of("minecraft:diamond", 64);
        TargetKey emerald1 = TargetKey.of("minecraft:emerald");
        TargetKey removedModItem = TargetKey.of("removedmod:ancient_gem");

        MilestoneData data = new MilestoneData();
        MilestoneData.PlayerData pd = data.player(alice);
        pd.lastKnownName = "<Alice & 'A'>";
        // Personal targets: diamond1, diamond64. Default targets: diamond1 (overlap), emerald1.
        // Effective targets = {diamond1, diamond64, emerald1} (3 total).
        pd.targets.add(diamond1);
        pd.targets.add(diamond64);

        // 2026-11-23T23:30:00Z -> 2026-11-24 in Asia/Tokyo (+09:00), 2026-11-23 in UTC.
        long nearMidnightEpoch = Instant.parse("2026-11-23T23:30:00Z").toEpochMilli();
        MilestoneRecord recDiamond1 = new MilestoneRecord(20 * 60, 0L, nearMidnightEpoch);
        MilestoneRecord recRemoved = MilestoneRecord.legacy(20 * 120);
        MilestoneRecord recEmerald = new MilestoneRecord(20 * 3661, 4L, nearMidnightEpoch);
        pd.records.put(emerald1, recEmerald);
        pd.records.put(removedModItem, recRemoved);
        pd.records.put(diamond1, recDiamond1);

        // Alice holds world-first for diamond1; Bob holds world-first for emerald1.
        data.recordWorldFirstIfAbsent(diamond1, alice, "<Alice & 'A'>", recDiamond1);
        data.recordWorldFirstIfAbsent(emerald1, bob, "Bob", recEmerald);

        Set<TargetKey> defaultTargets = Set.of(diamond1, emerald1);
        Map<String, String> knownNames = Map.of(
                "minecraft:diamond", "Diamond <Shiny> & '1'",
                "minecraft:emerald", "Emerald"
        );
        Instant generatedAt = Instant.parse("2026-11-23T23:45:00Z");

        String rel = MilestoneExport.exportPlayerHtml(
                dir,
                alice,
                "<Alice & 'A'>",
                pd,
                defaultTargets,
                data.worldFirsts(),
                id -> knownNames.getOrDefault(id, ""),
                generatedAt,
                ZoneId.of("Asia/Tokyo"));

        String expectedFile = MilestoneExport.sanitizePlayerHtmlFileName("<Alice & 'A'>", alice);
        assertEquals("itemmilestonelog/exports/" + expectedFile, rel);
        Path htmlFile = dir.resolve(expectedFile);
        assertTrue(Files.isRegularFile(htmlFile));

        String html = Files.readString(htmlFile);
        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains("<meta charset=\"utf-8\">"));
        assertTrue(html.contains("<style>"));
        assertFalse(html.toLowerCase().contains("<script"));
        assertFalse(html.contains("http://"));
        assertFalse(html.contains("https://"));

        // Escaped player name and item name.
        assertTrue(html.contains("&lt;Alice &amp; &#39;A&#39;&gt;"));
        assertFalse(html.contains("<Alice"));
        assertTrue(html.contains("Diamond &lt;Shiny&gt; &amp; &#39;1&#39;"));

        // Completion: 2 of 3 effective targets (diamond1, emerald1); total records = 3.
        assertTrue(html.contains("2 / 3 (66.7%)"));
        // Timezone Asia/Tokyo applied to both generatedAt and record dates.
        assertTrue(html.contains("2026-11-24 08:45:00 Asia/Tokyo"));
        assertTrue(html.contains("2026-11-24"));
        assertFalse(html.contains("2026-11-23"));
        // Playtime and 1-based Day.
        assertTrue(html.contains("0h 01m 00s"));
        assertTrue(html.contains("Day 1"));
        assertTrue(html.contains("1h 01m 01s"));
        assertTrue(html.contains("Day 5"));
        // Record order: diamond1 (60s) -> removedModItem (120s) -> emerald1 (3661s).
        int diamondIdx = html.indexOf("minecraft:diamond");
        int removedIdx = html.indexOf("removedmod:ancient_gem");
        int emeraldIdx = html.indexOf("minecraft:emerald");
        assertTrue(diamondIdx > 0 && diamondIdx < removedIdx && removedIdx < emeraldIdx);
        // World-first mark appears once (only for Alice's diamond1, not Bob's emerald1).
        int firstWf = html.indexOf("&#9733; World First");
        assertTrue(firstWf > 0);
        assertEquals(-1, html.indexOf("&#9733; World First", firstWf + 1));
        // Unresolvable item appears only once (in the Item ID cell, not duplicated as an item name).
        assertEquals(-1, html.indexOf("removedmod:ancient_gem", removedIdx + 1));
    }

    @Test
    void exportsAllPlayersHtmlSortedByNameAndHandlesEmptyData(@TempDir Path dir) throws Exception {
        MilestoneData empty = new MilestoneData();
        String emptyRel = MilestoneExport.exportAllHtml(
                dir, empty, Set.of(), id -> "", Instant.parse("2026-11-24T00:00:00Z"), ZoneId.of("UTC"));
        assertEquals("itemmilestonelog/exports/all-players.html", emptyRel);
        Path allFile = dir.resolve("all-players.html");
        assertTrue(Files.isRegularFile(allFile));
        String emptyHtml = Files.readString(allFile);
        assertTrue(emptyHtml.contains("No player milestone data recorded yet."));

        MilestoneData data = new MilestoneData();
        UUID zId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID aId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID namelessId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        TargetKey diamond = TargetKey.of("minecraft:diamond");

        MilestoneData.PlayerData pdZ = data.player(zId);
        pdZ.lastKnownName = "zeta";
        pdZ.targets.add(diamond);
        pdZ.records.put(diamond, new MilestoneRecord(200, 1L, Instant.parse("2026-11-24T01:00:00Z").toEpochMilli()));

        MilestoneData.PlayerData pdA = data.player(aId);
        pdA.lastKnownName = "Alice";
        pdA.targets.add(diamond);
        MilestoneRecord aRec = new MilestoneRecord(100, 0L, Instant.parse("2026-11-24T00:30:00Z").toEpochMilli());
        pdA.records.put(diamond, aRec);
        data.recordWorldFirstIfAbsent(diamond, aId, "Alice", aRec);

        MilestoneData.PlayerData pdNameless = data.player(namelessId);
        pdNameless.lastKnownName = "";

        String rel = MilestoneExport.exportAllHtml(
                dir,
                data,
                Set.of(diamond),
                id -> "minecraft:diamond".equals(id) ? "Diamond" : "",
                Instant.parse("2026-11-24T05:00:00Z"),
                ZoneId.of("UTC"));
        assertEquals("itemmilestonelog/exports/all-players.html", rel);

        String html = Files.readString(allFile);
        assertTrue(html.contains("Players: 3"));
        assertTrue(html.contains("2026-11-24 05:00:00 UTC"));
        // Sorted case-insensitively: "22222222-..." (digits) < "Alice" < "zeta".
        int namelessPos = html.indexOf("<h2>" + namelessId + "</h2>");
        int alicePos = html.indexOf("<h2>Alice</h2>");
        int zetaPos = html.indexOf("<h2>zeta</h2>");
        assertTrue(namelessPos > 0 && namelessPos < alicePos && alicePos < zetaPos);
        // Only Alice gets the world-first badge.
        int firstWf = html.indexOf("&#9733; World First");
        assertTrue(firstWf > alicePos && firstWf < zetaPos);
        assertEquals(-1, html.indexOf("&#9733; World First", firstWf + 1));
    }

    @Test
    void sanitizesPlayerFileNamesForCsv() {
        assertEquals("Steve.csv", MilestoneExport.sanitizePlayerCsvFileName("Steve"));
        assertEquals("Steve.csv", MilestoneExport.sanitizePlayerFileName("Steve", "csv"));
        assertEquals("Steve.csv", MilestoneExport.sanitizePlayerFileName("Steve", ".csv"));
        assertEquals("player.csv", MilestoneExport.sanitizePlayerCsvFileName(null));
        assertEquals("player.csv", MilestoneExport.sanitizePlayerCsvFileName("   "));
        assertEquals("all-players-player.csv", MilestoneExport.sanitizePlayerCsvFileName("all-players"));
        assertEquals("all-players-player.csv", MilestoneExport.sanitizePlayerCsvFileName("ALL-PLAYERS."));

        String traversal = MilestoneExport.sanitizePlayerCsvFileName("../secret");
        assertTrue(traversal.endsWith(".csv"));
        assertTrue(MilestoneExport.isSafeFileName(traversal));
        assertFalse(traversal.contains(".."));
    }

    @Test
    void exportsSinglePlayerCsvWithBomCrlfTimezoneWorldFirstFormulaGuardAndExcludesUnrecordedTargets(@TempDir Path dir) throws Exception {
        UUID alice = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID bob = UUID.fromString("22222222-2222-2222-2222-222222222222");
        TargetKey diamond1 = TargetKey.of("minecraft:diamond");
        TargetKey diamond64 = TargetKey.of("minecraft:diamond", 64);
        TargetKey emerald1 = TargetKey.of("minecraft:emerald");
        TargetKey removedModItem = TargetKey.of("removedmod:ancient_gem");

        MilestoneData data = new MilestoneData();
        MilestoneData.PlayerData pd = data.player(alice);
        String playerName = "=Alice, \"A\"";
        pd.lastKnownName = playerName;
        // diamond64 is an unrecorded personal target and must NOT appear in the CSV output.
        pd.targets.add(diamond1);
        pd.targets.add(diamond64);

        // 2026-11-23T23:30:00Z -> 2026-11-24 in Asia/Tokyo (+09:00), 2026-11-23 in UTC.
        long nearMidnightEpoch = Instant.parse("2026-11-23T23:30:00Z").toEpochMilli();
        MilestoneRecord recDiamond1 = new MilestoneRecord(20 * 60, 0L, nearMidnightEpoch);
        MilestoneRecord recRemoved = MilestoneRecord.legacy(20 * 120);
        MilestoneRecord recEmerald = new MilestoneRecord(20 * 3661, 4L, nearMidnightEpoch);
        pd.records.put(emerald1, recEmerald);
        pd.records.put(removedModItem, recRemoved);
        pd.records.put(diamond1, recDiamond1);

        // Alice holds world-first for diamond1; Bob holds world-first for emerald1.
        data.recordWorldFirstIfAbsent(diamond1, alice, playerName, recDiamond1);
        data.recordWorldFirstIfAbsent(emerald1, bob, "Bob", recEmerald);

        Map<String, String> knownNames = Map.of(
                "minecraft:diamond", "+Diamond \"Gem\"",
                "minecraft:emerald", "エメラルド"
        );

        String rel = MilestoneExport.exportPlayerCsv(
                dir,
                alice,
                playerName,
                pd,
                data.worldFirsts(),
                id -> knownNames.getOrDefault(id, ""),
                ZoneId.of("Asia/Tokyo"));

        String expectedFile = MilestoneExport.sanitizePlayerCsvFileName(playerName, alice);
        assertEquals("itemmilestonelog/exports/" + expectedFile, rel);
        Path csvFile = dir.resolve(expectedFile);
        assertTrue(Files.isRegularFile(csvFile));

        byte[] rawBytes = Files.readAllBytes(csvFile);
        assertTrue(rawBytes.length >= 3);
        assertEquals((byte) 0xEF, rawBytes[0]);
        assertEquals((byte) 0xBB, rawBytes[1]);
        assertEquals((byte) 0xBF, rawBytes[2]);

        String csv = new String(rawBytes, StandardCharsets.UTF_8);
        String expected = "\uFEFF"
                + "player_uuid,player_name,item_id,count,item_name,play_ticks,playtime,day,epoch_millis,date,world_first\r\n"
                + alice + ",\"'=Alice, \"\"A\"\"\",minecraft:diamond,1,\"'+Diamond \"\"Gem\"\"\",1200,0h 01m 00s,1,"
                + nearMidnightEpoch + ",2026-11-24,true\r\n"
                + alice + ",\"'=Alice, \"\"A\"\"\",removedmod:ancient_gem,1,,2400,0h 02m 00s,,,,false\r\n"
                + alice + ",\"'=Alice, \"\"A\"\"\",minecraft:emerald,1,エメラルド,73220,1h 01m 01s,5,"
                + nearMidnightEpoch + ",2026-11-24,false\r\n";
        assertEquals(expected, csv);
        assertFalse(csv.contains(",64,"));
    }

    @Test
    void exportsAllPlayersCsvSortedByNameThenPlayTicksAndHandlesEmptyData(@TempDir Path dir) throws Exception {
        MilestoneData empty = new MilestoneData();
        String emptyRel = MilestoneExport.exportAllCsv(dir, empty, id -> "", ZoneId.of("UTC"));
        assertEquals("itemmilestonelog/exports/all-players.csv", emptyRel);
        Path allFile = dir.resolve("all-players.csv");
        assertTrue(Files.isRegularFile(allFile));
        byte[] emptyBytes = Files.readAllBytes(allFile);
        assertEquals((byte) 0xEF, emptyBytes[0]);
        assertEquals((byte) 0xBB, emptyBytes[1]);
        assertEquals((byte) 0xBF, emptyBytes[2]);
        assertEquals(
                "\uFEFFplayer_uuid,player_name,item_id,count,item_name,play_ticks,playtime,day,epoch_millis,date,world_first\r\n",
                new String(emptyBytes, StandardCharsets.UTF_8));

        MilestoneData data = new MilestoneData();
        UUID zId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID aId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID bId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID unrecordedId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        TargetKey diamond = TargetKey.of("minecraft:diamond");
        TargetKey iron64 = TargetKey.of("minecraft:iron_ingot", 64);

        long epoch = Instant.parse("2026-11-24T01:00:00Z").toEpochMilli();

        MilestoneData.PlayerData pdZ = data.player(zId);
        pdZ.lastKnownName = "zeta";
        pdZ.records.put(diamond, new MilestoneRecord(200, 1L, epoch));

        MilestoneData.PlayerData pdA = data.player(aId);
        pdA.lastKnownName = "Alice";
        MilestoneRecord aLate = new MilestoneRecord(500, 2L, epoch);
        MilestoneRecord aEarly = new MilestoneRecord(100, 0L, epoch);
        pdA.records.put(iron64, aLate);
        pdA.records.put(diamond, aEarly);
        data.recordWorldFirstIfAbsent(diamond, aId, "Alice", aEarly);

        MilestoneData.PlayerData pdB = data.player(bId);
        pdB.lastKnownName = "bob";
        pdB.records.put(diamond, new MilestoneRecord(300, 1L, epoch));

        // Player with only unrecorded targets contributes zero CSV rows.
        MilestoneData.PlayerData pdUnrecorded = data.player(unrecordedId);
        pdUnrecorded.lastKnownName = "AAA_NoRecords";
        pdUnrecorded.targets.add(diamond);

        Map<String, String> names = Map.of(
                "minecraft:diamond", "Diamond",
                "minecraft:iron_ingot", "Iron Ingot"
        );
        String rel = MilestoneExport.exportAllCsv(dir, data, id -> names.getOrDefault(id, ""), ZoneId.of("UTC"));
        assertEquals("itemmilestonelog/exports/all-players.csv", rel);

        String csv = Files.readString(allFile, StandardCharsets.UTF_8);
        String expected = "\uFEFF"
                + "player_uuid,player_name,item_id,count,item_name,play_ticks,playtime,day,epoch_millis,date,world_first\r\n"
                + aId + ",Alice,minecraft:diamond,1,Diamond,100,0h 00m 05s,1," + epoch + ",2026-11-24,true\r\n"
                + aId + ",Alice,minecraft:iron_ingot,64,Iron Ingot,500,0h 00m 25s,3," + epoch + ",2026-11-24,false\r\n"
                + bId + ",bob,minecraft:diamond,1,Diamond,300,0h 00m 15s,2," + epoch + ",2026-11-24,false\r\n"
                + zId + ",zeta,minecraft:diamond,1,Diamond,200,0h 00m 10s,2," + epoch + ",2026-11-24,false\r\n";
        assertEquals(expected, csv);
        assertFalse(csv.contains("AAA_NoRecords"));
    }

    @Test
    void exportAllAndImportAllRoundTripCreatesMissingPlayersAndRestoresWorldFirsts(@TempDir Path dir) throws Exception {
        UUID alice = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID bob = UUID.fromString("22222222-2222-2222-2222-222222222222");
        TargetKey diamond1 = TargetKey.of("minecraft:diamond");
        TargetKey diamond100 = TargetKey.of("minecraft:diamond", 100);
        TargetKey emerald1 = TargetKey.of("minecraft:emerald");

        MilestoneData sourceData = new MilestoneData();
        MilestoneData.PlayerData srcAlice = sourceData.player(alice);
        srcAlice.lastKnownName = "AliceRenamed";
        srcAlice.targets.add(diamond1);
        srcAlice.targets.add(diamond100);
        MilestoneRecord aliceDiamond = new MilestoneRecord(1200, 1L, 1795500000000L);
        MilestoneRecord aliceDiamond100 = new MilestoneRecord(24000, 10L, 1795650000000L);
        srcAlice.records.put(diamond1, aliceDiamond);
        srcAlice.records.put(diamond100, aliceDiamond100);
        // Record-time name ("AliceAtRecord") intentionally differs from current lastKnownName ("AliceRenamed").
        sourceData.recordWorldFirstIfAbsent(diamond1, alice, "AliceAtRecord", aliceDiamond);
        sourceData.recordWorldFirstIfAbsent(diamond100, alice, "AliceAtRecord", aliceDiamond100);

        MilestoneData.PlayerData srcBob = sourceData.player(bob);
        srcBob.lastKnownName = "Bob";
        srcBob.targets.add(emerald1);
        MilestoneRecord bobEmerald = new MilestoneRecord(3600, 3L, 1795600000000L);
        srcBob.records.put(emerald1, bobEmerald);
        // Note: emerald1 has a player record but NO world-first entry in sourceData.

        String rel = MilestoneExport.exportAll(dir, sourceData);
        assertEquals("itemmilestonelog/exports/all-players.json", rel);
        Path allFile = dir.resolve("all-players.json");
        assertTrue(Files.isRegularFile(allFile));

        // Verify F31 JSON schema: format unchanged (1), worldFirsts array with encoded keys & record fields.
        JsonObject allJson = JsonParser.parseString(Files.readString(allFile)).getAsJsonObject();
        assertEquals(MilestoneExport.FORMAT_VERSION, allJson.get("format").getAsInt());
        JsonArray wfArr = allJson.getAsJsonArray("worldFirsts");
        assertNotNull(wfArr);
        assertEquals(2, wfArr.size());
        JsonObject wf0 = wfArr.get(0).getAsJsonObject();
        assertEquals("minecraft:diamond", wf0.get("key").getAsString());
        assertEquals(alice.toString(), wf0.get("uuid").getAsString());
        assertEquals("AliceAtRecord", wf0.get("name").getAsString());
        assertEquals(1200, wf0.get("ticks").getAsInt());
        assertEquals(1L, wf0.get("day").getAsLong());
        assertEquals(1795500000000L, wf0.get("epoch").getAsLong());
        JsonObject wf1 = wfArr.get(1).getAsJsonObject();
        assertEquals("minecraft:diamond#100", wf1.get("key").getAsString());
        assertEquals(alice.toString(), wf1.get("uuid").getAsString());
        assertEquals("AliceAtRecord", wf1.get("name").getAsString());
        assertEquals(24000, wf1.get("ticks").getAsInt());
        assertEquals(10L, wf1.get("day").getAsLong());
        assertEquals(1795650000000L, wf1.get("epoch").getAsLong());

        // Single-player import must still reject exportall files containing worldFirsts.
        assertThrows(IllegalArgumentException.class, () -> MilestoneExport.parseSinglePlayerExport(allFile));

        MilestoneExport.AllPlayersImportPayload payload = MilestoneExport.parseAllPlayersExport(allFile);
        assertEquals(2, payload.players().size());
        assertEquals(0, payload.skippedEntries());

        MilestoneData targetData = new MilestoneData();
        MilestoneExport.ImportAllMergeStats stats = MilestoneExport.mergeAllInto(targetData, payload);
        assertEquals(2, stats.importedPlayers());
        assertEquals(3, stats.addedTargets());
        assertEquals(3, stats.updatedRecords());
        assertEquals(2, stats.updatedWorldFirsts());
        assertEquals(0, stats.skippedEntries());

        MilestoneData.PlayerData dstAlice = targetData.playerOrNull(alice);
        MilestoneData.PlayerData dstBob = targetData.playerOrNull(bob);
        assertTrue(dstAlice != null && dstBob != null);
        assertEquals("AliceRenamed", dstAlice.lastKnownName);
        assertEquals("Bob", dstBob.lastKnownName);
        assertEquals(List.of(diamond1, diamond100), List.copyOf(dstAlice.targets));
        assertEquals(aliceDiamond, dstAlice.records.get(diamond1));
        assertEquals(aliceDiamond100, dstAlice.records.get(diamond100));
        assertEquals(List.of(emerald1), List.copyOf(dstBob.targets));
        assertEquals(bobEmerald, dstBob.records.get(emerald1));

        // World firsts from the file were restored with record-time names; player records without a
        // worldFirsts entry (emerald1) must NOT create new world firsts.
        assertEquals(2, targetData.worldFirsts().size());
        assertEquals(
                new WorldFirstRecord("minecraft:diamond", 1, alice, "AliceAtRecord", 1200, 1L, 1795500000000L),
                targetData.worldFirstOrNull(diamond1));
        assertEquals(
                new WorldFirstRecord("minecraft:diamond", 100, alice, "AliceAtRecord", 24000, 10L, 1795650000000L),
                targetData.worldFirstOrNull(diamond100));
        assertNull(targetData.worldFirstOrNull(emerald1));

        // Re-importing the exact same file updates 0 world firsts.
        MilestoneExport.ImportAllMergeStats repeat = MilestoneExport.mergeAllInto(targetData, payload);
        assertEquals(2, repeat.importedPlayers());
        assertEquals(0, repeat.addedTargets());
        assertEquals(0, repeat.updatedRecords());
        assertEquals(0, repeat.updatedWorldFirsts());
        assertEquals(0, repeat.skippedEntries());

        // Empty exportall file writes an empty worldFirsts array and round-trips cleanly with zero counts.
        Path emptyDir = dir.resolve("empty");
        MilestoneExport.exportAll(emptyDir, new MilestoneData());
        Path emptyAllFile = emptyDir.resolve("all-players.json");
        JsonObject emptyJson = JsonParser.parseString(Files.readString(emptyAllFile)).getAsJsonObject();
        assertTrue(emptyJson.get("worldFirsts").isJsonArray());
        assertEquals(0, emptyJson.getAsJsonArray("worldFirsts").size());
        MilestoneExport.AllPlayersImportPayload emptyPayload =
                MilestoneExport.parseAllPlayersExport(emptyAllFile);
        MilestoneExport.ImportAllMergeStats emptyStats =
                MilestoneExport.mergeAllInto(new MilestoneData(), emptyPayload);
        assertEquals(new MilestoneExport.ImportAllMergeStats(0, 0, 0, 0, 0), emptyStats);
    }

    @Test
    void importAllSkipsElementsWithMissingOrInvalidUuidAndMalformedEntries(@TempDir Path dir) throws Exception {
        UUID alice = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID bob = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Path mixedFile = dir.resolve("mixed-all.json");
        Files.writeString(mixedFile, """
                {
                  "format": 1,
                  "players": [
                    {
                      "player": { "uuid": "11111111-1111-1111-1111-111111111111", "name": "Alice" },
                      "targets": ["minecraft:diamond"],
                      "records": { "minecraft:diamond": { "ticks": 100, "day": 1, "epoch": 1795600000000 } }
                    },
                    {
                      "targets": ["minecraft:emerald"],
                      "records": {}
                    },
                    {
                      "player": { "name": "NoUuid" },
                      "targets": ["minecraft:emerald"],
                      "records": {}
                    },
                    {
                      "player": { "uuid": "   ", "name": "BlankUuid" },
                      "targets": ["minecraft:emerald"],
                      "records": {}
                    },
                    {
                      "player": { "uuid": "not-a-uuid", "name": "BadUuid" },
                      "targets": ["minecraft:emerald"],
                      "records": {}
                    },
                    {
                      "player": { "uuid": 12345, "name": "NumericUuid" },
                      "targets": [],
                      "records": {}
                    },
                    "not-an-object",
                    null,
                    {
                      "player": { "uuid": "33333333-3333-3333-3333-333333333333", "name": "BadTargets" },
                      "targets": "not-an-array",
                      "records": {}
                    },
                    {
                      "player": { "uuid": "22222222-2222-2222-2222-222222222222", "name": "Bob" },
                      "targets": ["minecraft:iron_ingot"],
                      "records": { "minecraft:iron_ingot": { "ticks": 250, "day": 2, "epoch": 1795700000000 } }
                    }
                  ]
                }
                """);

        MilestoneExport.AllPlayersImportPayload payload = MilestoneExport.parseAllPlayersExport(mixedFile);
        assertEquals(2, payload.players().size());
        assertEquals(8, payload.skippedEntries());

        MilestoneData data = new MilestoneData();
        MilestoneExport.ImportAllMergeStats stats = MilestoneExport.mergeAllInto(data, payload);
        assertEquals(2, stats.importedPlayers());
        assertEquals(2, stats.addedTargets());
        assertEquals(2, stats.updatedRecords());
        assertEquals(0, stats.updatedWorldFirsts());
        assertEquals(8, stats.skippedEntries());
        assertEquals(Set.of(alice, bob), data.allPlayers().keySet());
        assertEquals("Alice", data.playerOrNull(alice).lastKnownName);
        assertEquals("Bob", data.playerOrNull(bob).lastKnownName);
    }

    @Test
    void importAllRejectsSinglePlayerFormatBadSchemaAndOversizedFiles(@TempDir Path dir) throws Exception {
        UUID alice = UUID.fromString("11111111-1111-1111-1111-111111111111");
        MilestoneData data = new MilestoneData();
        MilestoneExport.exportPlayer(dir, alice, "Alice", data.player(alice));
        Path singleFile = dir.resolve(MilestoneExport.sanitizePlayerFileName("Alice", alice));
        assertThrows(IllegalArgumentException.class, () -> MilestoneExport.parseAllPlayersExport(singleFile));

        Path badFormat = dir.resolve("bad-format.json");
        Files.writeString(badFormat, """
                { "format": 2, "players": [] }
                """);
        assertThrows(IllegalArgumentException.class, () -> MilestoneExport.parseAllPlayersExport(badFormat));

        Path nonArrayPlayers = dir.resolve("bad-players.json");
        Files.writeString(nonArrayPlayers, """
                { "format": 1, "players": {} }
                """);
        assertThrows(IllegalArgumentException.class, () -> MilestoneExport.parseAllPlayersExport(nonArrayPlayers));

        Path validAll = dir.resolve("valid-all.json");
        Files.writeString(validAll, """
                { "format": 1, "players": [] }
                """);
        assertEquals(32L * 1024 * 1024, MilestoneExport.MAX_IMPORT_ALL_BYTES);
        assertThrows(IOException.class, () -> MilestoneExport.parseAllPlayersExport(validAll, 8L));
    }

    @Test
    void importAllMatchesSinglePlayerMergeRulesCapExistingNamesAndPreservedFields(@TempDir Path dir) throws Exception {
        UUID alice = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID unnamed = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID capped = UUID.fromString("33333333-3333-3333-3333-333333333333");
        TargetKey diamond1 = TargetKey.of("minecraft:diamond");
        TargetKey diamond100 = TargetKey.of("minecraft:diamond", 100);
        TargetKey emerald1 = TargetKey.of("minecraft:emerald");
        TargetKey iron1 = TargetKey.of("minecraft:iron_ingot");

        MilestoneData data = new MilestoneData();
        MilestoneData.PlayerData alicePd = data.player(alice);
        alicePd.lastKnownName = "AliceCurrent";
        alicePd.targets.add(diamond1);
        alicePd.targets.add(iron1);
        MilestoneRecord fasterEmerald = new MilestoneRecord(2000, 2L, 1795400000000L);
        alicePd.records.put(diamond1, new MilestoneRecord(5000, 5L, 1795700000000L));
        alicePd.records.put(emerald1, fasterEmerald);
        alicePd.completedPresets.add("vanilla_endgame");
        alicePd.progressNotified.put(diamond100, 50);
        alicePd.hideFromLookup = true;
        alicePd.muteProgressAlerts = true;
        alicePd.excludeFromDiscord = true;

        // Existing PlayerData with blank lastKnownName receives the name from the import file.
        MilestoneData.PlayerData unnamedPd = data.player(unnamed);
        unnamedPd.lastKnownName = "";

        // Player one slot below MAX_PERSONAL_TARGETS can only add one target from the import file.
        MilestoneData.PlayerData cappedPd = data.player(capped);
        cappedPd.lastKnownName = "Capped";
        for (int i = 0; i < MilestoneService.MAX_PERSONAL_TARGETS - 1; i++) {
            cappedPd.targets.add(TargetKey.of("test:item_" + i));
        }

        Path importFile = dir.resolve("merge-all.json");
        Files.writeString(importFile, """
                {
                  "format": 1,
                  "players": [
                    {
                      "player": { "uuid": "11111111-1111-1111-1111-111111111111", "name": "AliceOld" },
                      "targets": [
                        "minecraft:diamond",
                        { "item": "minecraft:diamond", "count": 100 },
                        "minecraft:emerald"
                      ],
                      "records": {
                        "minecraft:diamond": { "ticks": 1000, "day": 3, "epoch": 1795500000000 },
                        "minecraft:emerald": { "ticks": 9000, "day": 8, "epoch": 1795600000000 },
                        "minecraft:iron_ingot": { "ticks": 3000, "day": 4, "epoch": 1795550000000 }
                      }
                    },
                    {
                      "player": { "uuid": "22222222-2222-2222-2222-222222222222", "name": "RecoveredName" },
                      "targets": ["minecraft:diamond"],
                      "records": {}
                    },
                    {
                      "player": { "uuid": "33333333-3333-3333-3333-333333333333", "name": "Capped" },
                      "targets": ["minecraft:diamond", "minecraft:emerald", "minecraft:iron_ingot"],
                      "records": {}
                    }
                  ]
                }
                """);

        MilestoneExport.AllPlayersImportPayload payload = MilestoneExport.parseAllPlayersExport(importFile);
        MilestoneExport.ImportAllMergeStats stats = MilestoneExport.mergeAllInto(data, payload);

        // Alice: +2 targets (diamond#100, emerald), +2 records (diamond faster, iron new).
        // Unnamed: +1 target (diamond).
        // Capped: +1 target (diamond; emerald and iron exceed MAX_PERSONAL_TARGETS).
        assertEquals(3, stats.importedPlayers());
        assertEquals(4, stats.addedTargets());
        assertEquals(2, stats.updatedRecords());
        assertEquals(0, stats.updatedWorldFirsts());
        assertEquals(0, stats.skippedEntries());

        assertEquals("AliceCurrent", alicePd.lastKnownName);
        assertEquals(List.of(diamond1, iron1, diamond100, emerald1), List.copyOf(alicePd.targets));
        assertEquals(new MilestoneRecord(1000, 3L, 1795500000000L), alicePd.records.get(diamond1));
        assertEquals(fasterEmerald, alicePd.records.get(emerald1));
        assertEquals(new MilestoneRecord(3000, 4L, 1795550000000L), alicePd.records.get(iron1));
        assertEquals(List.of("vanilla_endgame"), List.copyOf(alicePd.completedPresets));
        assertEquals(Map.of(diamond100, 50), alicePd.progressNotified);
        assertTrue(alicePd.hideFromLookup);
        assertTrue(alicePd.muteProgressAlerts);
        assertTrue(alicePd.excludeFromDiscord);

        assertEquals("RecoveredName", unnamedPd.lastKnownName);
        assertEquals(MilestoneService.MAX_PERSONAL_TARGETS, cappedPd.targets.size());
        assertTrue(cappedPd.targets.contains(diamond1));
        assertFalse(cappedPd.targets.contains(emerald1));

        // Re-importing the exact same payload changes nothing (equal playTicks must not overwrite).
        MilestoneExport.ImportAllMergeStats repeat = MilestoneExport.mergeAllInto(data, payload);
        assertEquals(3, repeat.importedPlayers());
        assertEquals(0, repeat.addedTargets());
        assertEquals(0, repeat.updatedRecords());
        assertEquals(0, repeat.updatedWorldFirsts());
        assertEquals(0, repeat.skippedEntries());

        // Verify the F22/F31 completion message templates in both en_us and ja_jp.
        assertEquals(
                "Imported all-players.json: 3 player(s), 4 target(s) added, 2 record(s) updated, 5 world first(s) added or updated, 1 entr(ies) skipped.",
                ServerLang.formatForLang("en_us", "message.itemmilestonelog.importall_done",
                        "all-players.json", 3, 4, 2, 5, 1));
        assertEquals(
                "all-players.json から 3 人分を読み込みました: 目標 4 件を追加、記録 2 件を更新、世界初 5 件を追加・更新、1 件をスキップ。",
                ServerLang.formatForLang("ja_jp", "message.itemmilestonelog.importall_done",
                        "all-players.json", 3, 4, 2, 5, 1));
    }

    @Test
    void parseAndMergeWorldFirstsEnforcesEarlierWinsUnknownEpochAndSkipsBrokenElements() {
        UUID alice = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID bob = UUID.fromString("22222222-2222-2222-2222-222222222222");

        TargetKey diamond = TargetKey.of("minecraft:diamond");
        TargetKey emerald = TargetKey.of("minecraft:emerald");
        TargetKey gold = TargetKey.of("minecraft:gold_ingot");
        TargetKey iron = TargetKey.of("minecraft:iron_ingot");
        TargetKey coal = TargetKey.of("minecraft:coal");
        TargetKey redstone = TargetKey.of("minecraft:redstone");
        TargetKey lapis10 = TargetKey.of("minecraft:lapis_lazuli", 10);
        TargetKey copper = TargetKey.of("minecraft:copper_ingot");
        TargetKey ironTag = TargetKey.of("#c:ingots/iron");

        WorldFirstRecord existingDiamond = new WorldFirstRecord("minecraft:diamond", 1, alice, "Alice", 500, 2L, 2000L);
        WorldFirstRecord existingEmerald = new WorldFirstRecord("minecraft:emerald", 1, alice, "Alice", 500, 2L, 1000L);
        WorldFirstRecord existingGold = new WorldFirstRecord("minecraft:gold_ingot", 1, alice, "Alice", 500, 2L, 3000L);
        WorldFirstRecord existingIronUnknown = new WorldFirstRecord("minecraft:iron_ingot", 1, alice, "Alice", 500, -1L, 0L);
        WorldFirstRecord existingCoalKnown = new WorldFirstRecord("minecraft:coal", 1, alice, "Alice", 500, 2L, 4000L);
        WorldFirstRecord existingRedstoneUnknown = new WorldFirstRecord("minecraft:redstone", 1, alice, "Alice", 500, -1L, 0L);

        Map<TargetKey, WorldFirstRecord> current = new LinkedHashMap<>();
        current.put(diamond, existingDiamond);
        current.put(emerald, existingEmerald);
        current.put(gold, existingGold);
        current.put(iron, existingIronUnknown);
        current.put(coal, existingCoalKnown);
        current.put(redstone, existingRedstoneUnknown);

        JsonArray raw = JsonParser.parseString("""
                [
                  { "key": "minecraft:diamond", "uuid": "22222222-2222-2222-2222-222222222222", "name": "Bob", "ticks": 900, "day": 1, "epoch": 1500 },
                  { "key": "minecraft:emerald", "uuid": "22222222-2222-2222-2222-222222222222", "name": "Bob", "ticks": 100, "day": 1, "epoch": 2500 },
                  { "key": "minecraft:gold_ingot", "uuid": "22222222-2222-2222-2222-222222222222", "name": "Bob", "ticks": 100, "day": 1, "epoch": 3000 },
                  { "key": "minecraft:iron_ingot", "uuid": "22222222-2222-2222-2222-222222222222", "name": "Bob", "ticks": 100, "day": 1, "epoch": 500 },
                  { "key": "minecraft:coal", "uuid": "22222222-2222-2222-2222-222222222222", "name": "Bob", "ticks": 100, "day": 1, "epoch": 0 },
                  { "key": "minecraft:redstone", "uuid": "22222222-2222-2222-2222-222222222222", "name": "Bob", "ticks": 100, "day": -1, "epoch": 0 },
                  { "key": "lapis_lazuli#10", "uuid": "22222222-2222-2222-2222-222222222222", "name": "Bob", "ticks": 420, "day": 5, "epoch": 1795600000000 },
                  { "key": "minecraft:copper_ingot", "uuid": "22222222-2222-2222-2222-222222222222", "ticks": 0 },
                  { "key": "#c:ingots/iron", "uuid": "22222222-2222-2222-2222-222222222222", "ticks": 10 },
                  "not-an-object",
                  null,
                  { "uuid": "22222222-2222-2222-2222-222222222222", "ticks": 10 },
                  { "key": "##c:x", "uuid": "22222222-2222-2222-2222-222222222222", "ticks": 10 },
                  { "key": "minecraft:air", "uuid": "22222222-2222-2222-2222-222222222222", "ticks": 10 },
                  { "key": "minecraft:stone#0", "uuid": "22222222-2222-2222-2222-222222222222", "ticks": 10 },
                  { "key": "minecraft:stone", "uuid": "not-a-uuid", "ticks": 10 },
                  { "key": "minecraft:stone", "ticks": 10 },
                  { "key": "minecraft:stone", "uuid": "22222222-2222-2222-2222-222222222222", "name": 123, "ticks": 10 },
                  { "key": "minecraft:stone", "uuid": "22222222-2222-2222-2222-222222222222" },
                  { "key": "minecraft:stone", "uuid": "22222222-2222-2222-2222-222222222222", "ticks": -1 },
                  { "key": "minecraft:stone", "uuid": "22222222-2222-2222-2222-222222222222", "ticks": 12.5 },
                  { "key": "minecraft:stone", "uuid": "22222222-2222-2222-2222-222222222222", "ticks": 10, "day": -2 },
                  { "key": "minecraft:stone", "uuid": "22222222-2222-2222-2222-222222222222", "ticks": 10, "epoch": -5 }
                ]
                """).getAsJsonArray();

        WorldFirstImportLogic.MergeResult<TargetKey, WorldFirstRecord> result =
                MilestoneExport.parseAndMergeWorldFirsts(current, raw);

        // Updated: diamond (1500 < 2000), lapis_lazuli#10 (new, implicit namespace normalized),
        // copper_ingot (new, omitted optional fields), and #c:ingots/iron (new tag key).
        assertEquals(4, result.updatedCount());
        assertEquals(14, result.skippedCount());
        assertEquals(9, result.worldFirsts().size());

        // Earlier epoch wins even when incoming playTicks is higher.
        assertEquals(new WorldFirstRecord("minecraft:diamond", 1, bob, "Bob", 900, 1L, 1500L),
                result.worldFirsts().get(diamond));
        // Later epoch loses; equal epoch keeps existing.
        assertEquals(existingEmerald, result.worldFirsts().get(emerald));
        assertEquals(existingGold, result.worldFirsts().get(gold));
        // Unknown epoch on either side keeps existing.
        assertEquals(existingIronUnknown, result.worldFirsts().get(iron));
        assertEquals(existingCoalKnown, result.worldFirsts().get(coal));
        assertEquals(existingRedstoneUnknown, result.worldFirsts().get(redstone));
        // New keys added cleanly, including omitted name/day/epoch defaulting to "", UNKNOWN_DAY, UNKNOWN_EPOCH.
        assertEquals(new WorldFirstRecord("minecraft:lapis_lazuli", 10, bob, "Bob", 420, 5L, 1795600000000L),
                result.worldFirsts().get(lapis10));
        assertEquals(new WorldFirstRecord("minecraft:copper_ingot", 1, bob, "", 0, MilestoneRecord.UNKNOWN_DAY, MilestoneRecord.UNKNOWN_EPOCH),
                result.worldFirsts().get(copper));
        assertEquals(new WorldFirstRecord("#c:ingots/iron", 1, bob, "", 10, MilestoneRecord.UNKNOWN_DAY, MilestoneRecord.UNKNOWN_EPOCH),
                result.worldFirsts().get(ironTag));

        // Pure function must not mutate the input map.
        assertEquals(6, current.size());
        assertEquals(existingDiamond, current.get(diamond));
    }

    @Test
    void importAllMergesWorldFirstsCountsCombinedSkipsAndHandlesLegacyFilesWithoutWorldFirsts(@TempDir Path dir) throws Exception {
        UUID alice = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID bob = UUID.fromString("22222222-2222-2222-2222-222222222222");
        TargetKey diamond = TargetKey.of("minecraft:diamond");
        TargetKey emerald = TargetKey.of("minecraft:emerald");
        TargetKey iron = TargetKey.of("minecraft:iron_ingot");
        TargetKey diamond100 = TargetKey.of("minecraft:diamond", 100);

        MilestoneData data = new MilestoneData();
        data.recordWorldFirstIfAbsent(diamond, alice, "Alice", new MilestoneRecord(1000, 2L, 5000L));
        data.recordWorldFirstIfAbsent(emerald, alice, "Alice", new MilestoneRecord(1000, -1L, 0L));

        // 1. Pre-F31 file (no worldFirsts key) keeps existing world firsts intact and reports 0 world-first updates.
        Path legacyAll = dir.resolve("legacy-all.json");
        Files.writeString(legacyAll, """
                {
                  "format": 1,
                  "players": [
                    {
                      "player": { "uuid": "22222222-2222-2222-2222-222222222222", "name": "Bob" },
                      "targets": ["minecraft:iron_ingot"],
                      "records": { "minecraft:iron_ingot": { "ticks": 300, "day": 1, "epoch": 2000 } }
                    }
                  ]
                }
                """);
        MilestoneExport.ImportAllMergeStats legacyStats =
                MilestoneExport.mergeAllInto(data, MilestoneExport.parseAllPlayersExport(legacyAll));
        assertEquals(new MilestoneExport.ImportAllMergeStats(1, 1, 1, 0, 0), legacyStats);
        assertEquals(2, data.worldFirsts().size());
        assertNull(data.worldFirstOrNull(iron));

        // 2. File with broken player entries AND broken worldFirsts entries combines skipped counts.
        Path mixedAll = dir.resolve("mixed-world-firsts.json");
        Files.writeString(mixedAll, """
                {
                  "format": 1,
                  "players": [
                    { "player": { "name": "NoUuid" }, "targets": [], "records": {} }
                  ],
                  "worldFirsts": [
                    { "key": "minecraft:diamond", "uuid": "22222222-2222-2222-2222-222222222222", "name": "Bob", "ticks": 2000, "day": 1, "epoch": 1000 },
                    { "key": "minecraft:emerald", "uuid": "22222222-2222-2222-2222-222222222222", "name": "Bob", "ticks": 100, "day": 1, "epoch": 500 },
                    { "key": "minecraft:diamond#100", "uuid": "22222222-2222-2222-2222-222222222222", "name": "Bob", "ticks": 5000, "day": 4, "epoch": 9000 },
                    { "key": "minecraft:iron_ingot", "uuid": "bad-uuid", "ticks": 100 },
                    "broken-element"
                  ]
                }
                """);
        MilestoneExport.ImportAllMergeStats mixedStats =
                MilestoneExport.mergeAllInto(data, MilestoneExport.parseAllPlayersExport(mixedAll));
        // 1 skipped player + 2 skipped worldFirsts = 3 skipped; diamond replaced + diamond#100 added = 2 updated.
        assertEquals(0, mixedStats.importedPlayers());
        assertEquals(0, mixedStats.addedTargets());
        assertEquals(0, mixedStats.updatedRecords());
        assertEquals(2, mixedStats.updatedWorldFirsts());
        assertEquals(3, mixedStats.skippedEntries());

        assertEquals(bob, data.worldFirstOrNull(diamond).playerUuid());
        assertEquals(1000L, data.worldFirstOrNull(diamond).epochMillis());
        assertEquals(alice, data.worldFirstOrNull(emerald).playerUuid()); // existing epoch was 0 -> kept
        assertEquals(bob, data.worldFirstOrNull(diamond100).playerUuid());
        assertEquals(100, data.worldFirstOrNull(diamond100).count());

        // 3. Non-array worldFirsts field is counted as skipped without breaking valid players.
        Path badWfField = dir.resolve("bad-wf-field.json");
        Files.writeString(badWfField, """
                {
                  "format": 1,
                  "players": [],
                  "worldFirsts": { "not": "an array" }
                }
                """);
        MilestoneExport.ImportAllMergeStats badFieldStats =
                MilestoneExport.mergeAllInto(data, MilestoneExport.parseAllPlayersExport(badWfField));
        assertEquals(new MilestoneExport.ImportAllMergeStats(0, 0, 0, 0, 1), badFieldStats);
    }
}
