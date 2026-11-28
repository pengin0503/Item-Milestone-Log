package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.api.WorldFirstRecord;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MilestoneDataTest {
    private static final UUID PLAYER_A = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void savesAndLoadsTargetsRecordsWorldFirstsAndFlagsRoundTrip() {
        MilestoneData original = new MilestoneData();
        UUID playerA = PLAYER_A;
        UUID playerB = UUID.fromString("22222222-2222-2222-2222-222222222222");

        TargetKey diamond1 = TargetKey.of("minecraft:diamond");
        TargetKey diamond100 = TargetKey.of("minecraft:diamond", 100);
        TargetKey netherite = TargetKey.of("minecraft:netherite_ingot");
        TargetKey atmStar = TargetKey.of("allthetweaks:atm_star");

        MilestoneData.PlayerData pdA = original.player(playerA);
        pdA.targets.add(diamond1);
        pdA.targets.add(diamond100);
        pdA.targets.add(netherite);
        MilestoneRecord recA1 = new MilestoneRecord(12340, 11L, 1795600000000L);
        MilestoneRecord recA100 = new MilestoneRecord(99999, 25L, 1795650000000L);
        pdA.records.put(diamond1, recA1);
        pdA.records.put(diamond100, recA100);
        pdA.carriedFromCreative.add("minecraft:netherite_ingot");
        pdA.carriedFromCreative.add("minecraft:stone");
        pdA.lastKnownName = "Alice";
        pdA.firstJoinHintShown = true;
        pdA.muteBroadcasts = true;
        pdA.muteProgressAlerts = true;
        pdA.excludeFromDiscord = true;
        pdA.lastSeen = 1795800000000L;
        pdA.progressNotified.put(TargetKey.of("minecraft:iron_ingot", 100), 0);
        pdA.progressNotified.put(TargetKey.of("minecraft:gold_ingot", 500), 50);
        pdA.completedPresets.add("vanilla_endgame");
        pdA.completedPresets.add("my_custom_pack");

        MilestoneData.PlayerData pdB = original.player(playerB);
        pdB.targets.add(atmStar);
        MilestoneRecord recB = new MilestoneRecord(500000, 42L, 1795700000000L);
        pdB.records.put(atmStar, recB);

        assertTrue(original.recordWorldFirstIfAbsent(diamond1, playerA, "Alice", recA1));
        assertFalse(original.recordWorldFirstIfAbsent(diamond1, playerB, "Bob", recB));
        assertTrue(original.recordWorldFirstIfAbsent(diamond100, playerB, "Bob", recA100));

        Tag saved = MilestoneData.CODEC.encodeStart(NbtOps.INSTANCE, original).getOrThrow();
        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();

        MilestoneData.PlayerData loadedA = loaded.playerOrNull(playerA);
        assertNotNull(loadedA);
        assertEquals(List.of(diamond1, diamond100, netherite), List.copyOf(loadedA.targets));
        assertEquals(recA1, loadedA.records.get(diamond1));
        assertEquals(recA100, loadedA.records.get(diamond100));
        assertNull(loadedA.records.get(netherite));
        assertEquals(List.of("minecraft:netherite_ingot", "minecraft:stone"), List.copyOf(loadedA.carriedFromCreative));
        assertEquals("Alice", loadedA.lastKnownName);
        assertTrue(loadedA.firstJoinHintShown);
        assertTrue(loadedA.muteBroadcasts);
        assertTrue(loadedA.muteProgressAlerts);
        assertTrue(loadedA.excludeFromDiscord);
        assertEquals(1795800000000L, loadedA.lastSeen);
        assertEquals(Map.of(
                TargetKey.of("minecraft:iron_ingot", 100), 0,
                TargetKey.of("minecraft:gold_ingot", 500), 50
        ), loadedA.progressNotified);
        assertEquals(List.of("vanilla_endgame", "my_custom_pack"), List.copyOf(loadedA.completedPresets));

        MilestoneData.PlayerData loadedB = loaded.playerOrNull(playerB);
        assertNotNull(loadedB);
        assertEquals(List.of(atmStar), List.copyOf(loadedB.targets));
        assertEquals(recB, loadedB.records.get(atmStar));
        assertTrue(loadedB.carriedFromCreative.isEmpty());
        assertEquals("", loadedB.lastKnownName);
        assertFalse(loadedB.firstJoinHintShown);
        assertFalse(loadedB.muteBroadcasts);
        assertFalse(loadedB.muteProgressAlerts);
        assertFalse(loadedB.excludeFromDiscord);
        assertEquals(0L, loadedB.lastSeen);
        assertTrue(loadedB.progressNotified.isEmpty());
        assertTrue(loadedB.completedPresets.isEmpty());

        WorldFirstRecord wf1 = loaded.worldFirstOrNull(diamond1);
        assertNotNull(wf1);
        assertEquals(1, wf1.count());
        assertEquals(playerA, wf1.playerUuid());
        assertEquals("Alice", wf1.playerName());
        assertEquals(12340, wf1.playTicks());
        assertEquals(11L, wf1.day());
        assertEquals(1795600000000L, wf1.epochMillis());

        WorldFirstRecord wf100 = loaded.worldFirstOrNull(diamond100);
        assertNotNull(wf100);
        assertEquals(100, wf100.count());
        assertEquals(playerB, wf100.playerUuid());
        assertEquals("Bob", wf100.playerName());
        assertEquals(99999, wf100.playTicks());
        assertEquals(25L, wf100.day());
        assertEquals(1795650000000L, wf100.epochMillis());
        assertEquals(2, loaded.worldFirsts().size());
        assertNull(loaded.worldFirstOrNull(netherite));
    }

    /** The 1.21.1 1.0.0 file: plain int records and no new fields. */
    private static CompoundTag legacy100FileContents() {
        CompoundTag player = new CompoundTag();
        ListTag targets = new ListTag();
        targets.add(StringTag.valueOf("minecraft:diamond"));
        targets.add(StringTag.valueOf("allthetweaks:atm_star"));
        player.put("targets", targets);
        CompoundTag records = new CompoundTag();
        records.putInt("minecraft:diamond", 12340);
        player.put("records", records);

        CompoundTag players = new CompoundTag();
        players.put(PLAYER_A.toString(), player);
        CompoundTag data = new CompoundTag();
        data.put("players", players);

        CompoundTag root = new CompoundTag();
        root.put("data", data);
        root.putInt("DataVersion", 3955);
        return root;
    }

    /** The 1.21.1 1.0.1 file: compound records, lastKnownName, and worldFirsts. */
    private static CompoundTag legacy101FileContents() {
        CompoundTag player = new CompoundTag();
        ListTag targets = new ListTag();
        targets.add(StringTag.valueOf("minecraft:diamond"));
        player.put("targets", targets);
        CompoundTag rec = new CompoundTag();
        rec.putInt("ticks", 12340);
        rec.putLong("day", 11L);
        rec.putLong("epoch", 1795600000000L);
        CompoundTag records = new CompoundTag();
        records.put("minecraft:diamond", rec);
        player.put("records", records);
        player.putString("lastKnownName", "Alice");

        CompoundTag players = new CompoundTag();
        players.put(PLAYER_A.toString(), player);

        CompoundTag wf = new CompoundTag();
        wf.putString("uuid", PLAYER_A.toString());
        wf.putString("name", "Alice");
        wf.putInt("ticks", 12340);
        wf.putLong("day", 11L);
        wf.putLong("epoch", 1795600000000L);
        CompoundTag worldFirsts = new CompoundTag();
        worldFirsts.put("minecraft:diamond", wf);

        CompoundTag data = new CompoundTag();
        data.put("players", players);
        data.put("worldFirsts", worldFirsts);

        CompoundTag root = new CompoundTag();
        root.put("data", data);
        root.putInt("DataVersion", 3955);
        return root;
    }

    @Test
    void readsThe100LegacyFileOfThe1211Version(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(MilestoneData.LEGACY_FILE);
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(legacy100FileContents(), file);

        MilestoneData loaded = MilestoneData.readLegacy(file);

        assertNotNull(loaded);
        MilestoneData.PlayerData pd = loaded.playerOrNull(PLAYER_A);
        assertNotNull(pd);
        assertEquals(List.of(TargetKey.of("minecraft:diamond"), TargetKey.of("allthetweaks:atm_star")), List.copyOf(pd.targets));
        assertEquals(Map.of(TargetKey.of("minecraft:diamond"), MilestoneRecord.legacy(12340)), pd.records);
        assertTrue(pd.carriedFromCreative.isEmpty());
        assertFalse(pd.muteProgressAlerts);
        assertFalse(pd.excludeFromDiscord);
        assertEquals(0L, pd.lastSeen);
        assertTrue(pd.progressNotified.isEmpty());
        assertTrue(pd.completedPresets.isEmpty());
        assertTrue(loaded.worldFirsts().isEmpty());
    }

    @Test
    void readsThe101LegacyFileOfThe1211VersionWithWorldFirsts(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(MilestoneData.LEGACY_FILE);
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(legacy101FileContents(), file);

        MilestoneData loaded = MilestoneData.readLegacy(file);

        assertNotNull(loaded);
        MilestoneData.PlayerData pd = loaded.playerOrNull(PLAYER_A);
        assertNotNull(pd);
        assertEquals("Alice", pd.lastKnownName);
        assertEquals(new MilestoneRecord(12340, 11L, 1795600000000L), pd.records.get(TargetKey.of("minecraft:diamond")));
        WorldFirstRecord wf = loaded.worldFirstOrNull(TargetKey.of("minecraft:diamond"));
        assertNotNull(wf);
        assertEquals(1, wf.count());
        assertEquals(PLAYER_A, wf.playerUuid());
        assertEquals("Alice", wf.playerName());
        assertEquals(11L, wf.day());
    }

    @Test
    void noLegacyFileMeansNoImport(@TempDir Path dir) {
        assertNull(MilestoneData.readLegacy(dir.resolve(MilestoneData.LEGACY_FILE)));
    }

    @Test
    void unreadableLegacyFileIsSkipped(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(MilestoneData.LEGACY_FILE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "not an NBT file");
        assertNull(MilestoneData.readLegacy(file));

        // Legacy file without a "data" compound tag must also return null rather than empty migration
        Path noDataTagFile = dir.resolve("no_data.dat");
        CompoundTag noDataRoot = new CompoundTag();
        noDataRoot.putInt("DataVersion", 3955);
        NbtIo.writeCompressed(noDataRoot, noDataTagFile);
        assertNull(MilestoneData.readLegacy(noDataTagFile));
    }

    @Test
    void ignoresMalformedUuidEntriesAndInvalidCodecRecordsWhileKeepingValidEntries() {
        CompoundTag validPlayer = new CompoundTag();
        validPlayer.putString("lastKnownName", "ValidAlice");
        ListTag targets = new ListTag();
        targets.add(StringTag.valueOf("minecraft:diamond"));
        targets.add(StringTag.valueOf("minecraft:emerald#0"));
        targets.add(StringTag.valueOf("minecraft:stone #5"));
        validPlayer.put("targets", targets);

        CompoundTag records = new CompoundTag();
        records.putInt("minecraft:diamond", 100);
        records.putInt("minecraft:dirt", -50);
        validPlayer.put("records", records);

        CompoundTag root = new CompoundTag();
        CompoundTag players = new CompoundTag();
        players.put("not-a-valid-uuid", new CompoundTag());
        players.put(PLAYER_A.toString(), validPlayer);
        root.put("players", players);

        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, root).getOrThrow();
        assertEquals(1, loaded.allPlayers().size());
        MilestoneData.PlayerData pd = loaded.playerOrNull(PLAYER_A);
        assertNotNull(pd);
        assertEquals("ValidAlice", pd.lastKnownName);
        assertEquals(List.of(TargetKey.of("minecraft:diamond")), List.copyOf(pd.targets));
        assertEquals(1, pd.records.size());
        assertEquals(MilestoneRecord.legacy(100), pd.records.get(TargetKey.of("minecraft:diamond")));

        // Empty root tag decodes cleanly with all keys absent
        MilestoneData emptyLoaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, new CompoundTag()).getOrThrow();
        assertTrue(emptyLoaded.allPlayers().isEmpty());
        assertTrue(emptyLoaded.worldFirsts().isEmpty());
    }

    @Test
    void skipsMalformedRecordAndWorldFirstEntriesIndividuallyWhileKeepingValidSiblings() {
        CompoundTag playerTag = new CompoundTag();
        ListTag targets = new ListTag();
        targets.add(StringTag.valueOf("minecraft:diamond"));
        playerTag.put("targets", targets);

        CompoundTag rec = new CompoundTag();
        rec.putInt("ticks", 4567);
        rec.putLong("day", 5L);
        rec.putLong("epoch", 1795600000000L);
        CompoundTag missingTicksRec = new CompoundTag();
        missingTicksRec.putLong("day", 2L);
        CompoundTag negTicksRec = new CompoundTag();
        negTicksRec.putInt("ticks", -5);
        CompoundTag qtyMissingTicks = new CompoundTag();
        qtyMissingTicks.put("10", missingTicksRec.copy());
        // Count map with malformed (missing ticks, negative ticks, invalid count) and valid entries: only valid survives.
        CompoundTag qtyMixed = new CompoundTag();
        qtyMixed.put("10", missingTicksRec.copy());
        qtyMixed.put("30", negTicksRec.copy());
        qtyMixed.put("0", rec.copy());
        qtyMixed.put("abc", rec.copy());
        qtyMixed.put("20", rec.copy());
        // Value that is neither an int, a record object nor a count map.
        CompoundTag records = new CompoundTag();
        records.put("minecraft:diamond", rec);
        records.put("minecraft:gold_ingot", missingTicksRec);
        records.put("minecraft:iron_ingot", negTicksRec);
        records.put("minecraft:coal", qtyMissingTicks);
        records.put("minecraft:copper_ingot", qtyMixed);
        records.put("minecraft:emerald", StringTag.valueOf("garbage"));
        playerTag.put("records", records);

        CompoundTag players = new CompoundTag();
        players.put(PLAYER_A.toString(), playerTag);

        CompoundTag wf = new CompoundTag();
        wf.putString("uuid", PLAYER_A.toString());
        wf.putString("name", "Alice");
        wf.putInt("ticks", 4567);
        wf.putLong("day", 5L);
        wf.putLong("epoch", 1795600000000L);
        CompoundTag wfNoTicks = new CompoundTag();
        wfNoTicks.putString("uuid", PLAYER_A.toString());
        CompoundTag wfNegTicks = new CompoundTag();
        wfNegTicks.putString("uuid", PLAYER_A.toString());
        wfNegTicks.putInt("ticks", -1);
        CompoundTag wfBadUuid = new CompoundTag();
        wfBadUuid.putString("uuid", "not-a-uuid");
        wfBadUuid.putInt("ticks", 100);
        CompoundTag wfQtyMixed = new CompoundTag();
        wfQtyMixed.put("10", wfNoTicks.copy());
        wfQtyMixed.put("30", wfNegTicks.copy());
        wfQtyMixed.put("20", wf.copy());
        CompoundTag worldFirsts = new CompoundTag();
        worldFirsts.put("minecraft:diamond", wf);
        worldFirsts.put("minecraft:gold_ingot", wfNoTicks);
        worldFirsts.put("minecraft:iron_ingot", wfNegTicks);
        worldFirsts.put("minecraft:lapis_lazuli", wfBadUuid);
        worldFirsts.put("minecraft:coal", wfQtyMixed);
        worldFirsts.put("minecraft:emerald", StringTag.valueOf("garbage"));

        CompoundTag root = new CompoundTag();
        root.put("players", players);
        root.put("worldFirsts", worldFirsts);

        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, root).getOrThrow();
        MilestoneData.PlayerData pd = loaded.playerOrNull(PLAYER_A);
        assertNotNull(pd);
        MilestoneRecord expected = new MilestoneRecord(4567, 5L, 1795600000000L);
        assertEquals(2, pd.records.size(), pd.records::toString);
        assertEquals(expected, pd.records.get(TargetKey.of("minecraft:diamond")));
        assertEquals(expected, pd.records.get(TargetKey.of("minecraft:copper_ingot", 20)));

        assertEquals(2, loaded.worldFirsts().size(), () -> loaded.worldFirsts().toString());
        WorldFirstRecord loadedWf = loaded.worldFirstOrNull(TargetKey.of("minecraft:diamond"));
        assertNotNull(loadedWf);
        assertEquals("Alice", loadedWf.playerName());
        assertNotNull(loaded.worldFirstOrNull(TargetKey.of("minecraft:coal", 20)));
    }

    @Test
    void readLegacySkipsMissingAndNegativeTicksEntries(@TempDir Path dir) throws IOException {
        CompoundTag player = new CompoundTag();
        ListTag targets = new ListTag();
        targets.add(StringTag.valueOf("minecraft:diamond"));
        targets.add(StringTag.valueOf("minecraft:emerald#0"));
        player.put("targets", targets);

        CompoundTag validRec = new CompoundTag();
        validRec.putInt("ticks", 4567);
        validRec.putLong("day", 5L);
        validRec.putLong("epoch", 1795600000000L);
        CompoundTag missingTicks = new CompoundTag();
        missingTicks.putLong("day", 2L);
        CompoundTag negTicks = new CompoundTag();
        negTicks.putInt("ticks", -5);
        CompoundTag qtyMixed = new CompoundTag();
        qtyMixed.put("10", missingTicks.copy());
        qtyMixed.put("20", negTicks.copy());
        qtyMixed.put("30", validRec.copy());

        CompoundTag records = new CompoundTag();
        records.put("minecraft:diamond", validRec);
        records.putInt("minecraft:dirt", -10);
        records.put("minecraft:gold_ingot", missingTicks);
        records.put("minecraft:iron_ingot", negTicks);
        records.put("minecraft:coal", qtyMixed);
        player.put("records", records);

        CompoundTag players = new CompoundTag();
        players.put(PLAYER_A.toString(), player);

        CompoundTag validWf = new CompoundTag();
        validWf.putString("uuid", PLAYER_A.toString());
        validWf.putString("name", "Alice");
        validWf.putInt("ticks", 4567);
        validWf.putLong("day", 5L);
        validWf.putLong("epoch", 1795600000000L);
        CompoundTag wfMissingTicks = new CompoundTag();
        wfMissingTicks.putString("uuid", PLAYER_A.toString());
        CompoundTag wfNegTicks = new CompoundTag();
        wfNegTicks.putString("uuid", PLAYER_A.toString());
        wfNegTicks.putInt("ticks", -2);
        CompoundTag worldFirsts = new CompoundTag();
        worldFirsts.put("minecraft:diamond", validWf);
        worldFirsts.put("minecraft:gold_ingot", wfMissingTicks);
        worldFirsts.put("minecraft:iron_ingot", wfNegTicks);

        CompoundTag data = new CompoundTag();
        data.put("players", players);
        data.put("worldFirsts", worldFirsts);
        CompoundTag root = new CompoundTag();
        root.put("data", data);
        root.putInt("DataVersion", 3955);

        Path file = dir.resolve(MilestoneData.LEGACY_FILE);
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(root, file);

        MilestoneData loaded = MilestoneData.readLegacy(file);
        assertNotNull(loaded);
        MilestoneData.PlayerData pd = loaded.playerOrNull(PLAYER_A);
        assertNotNull(pd);
        assertEquals(List.of(TargetKey.of("minecraft:diamond")), List.copyOf(pd.targets));
        MilestoneRecord expected = new MilestoneRecord(4567, 5L, 1795600000000L);
        assertEquals(2, pd.records.size(), pd.records::toString);
        assertEquals(expected, pd.records.get(TargetKey.of("minecraft:diamond")));
        assertEquals(expected, pd.records.get(TargetKey.of("minecraft:coal", 30)));
        assertEquals(1, loaded.worldFirsts().size());
        assertNotNull(loaded.worldFirstOrNull(TargetKey.of("minecraft:diamond")));
    }

    @Test
    void emptyOptionalFieldsAreNotWritten() {
        MilestoneData data = new MilestoneData();
        data.player(PLAYER_A).targets.add(TargetKey.of("minecraft:diamond"));

        CompoundTag saved = (CompoundTag) MilestoneData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
        CompoundTag player = saved.getCompoundOrEmpty("players").getCompoundOrEmpty(PLAYER_A.toString());
        assertTrue(player.contains("targets"));
        assertFalse(player.contains("carriedFromCreative"), player::toString);
        assertFalse(player.contains("lastKnownName"), player::toString);
        assertFalse(player.contains("firstJoinHintShown"), player::toString);
        assertFalse(player.contains("muteBroadcasts"), player::toString);
        assertFalse(player.contains("muteProgressAlerts"), player::toString);
        assertFalse(player.contains("excludeFromDiscord"), player::toString);
        assertFalse(player.contains("lastSeen"), player::toString);
        assertFalse(player.contains("progressNotified"), player::toString);
        assertFalse(player.contains("completedPresets"), player::toString);
        assertFalse(saved.contains("worldFirsts"), saved::toString);
        assertFalse(saved.contains("lastDigestEpoch"), saved::toString);
    }

    @Test
    void persistsProgressNotifiedAndSkipsInvalidOrOutOfRangeEntries(@TempDir Path dir) throws IOException {
        UUID playerB = UUID.fromString("22222222-2222-2222-2222-222222222222");

        CompoundTag progressTag = new CompoundTag();
        progressTag.putInt("minecraft:diamond#1000", 50);
        progressTag.putInt("minecraft:iron_ingot#64", 0);
        progressTag.putInt("minecraft:emerald#0", 50); // invalid TargetKey count
        progressTag.putInt("bad key", 50); // invalid TargetKey syntax
        progressTag.putInt("minecraft:coal#100", -1); // out of range (< 0)
        progressTag.putInt("minecraft:gold_ingot#100", 100); // out of range (> 99)
        progressTag.putString("minecraft:redstone#100", "fifty"); // non-numeric value

        CompoundTag playerATag = new CompoundTag();
        playerATag.putString("lastKnownName", "Alice");
        playerATag.put("progressNotified", progressTag);

        CompoundTag playerBTag = new CompoundTag();
        playerBTag.putString("lastKnownName", "Bob");
        playerBTag.put("progressNotified", StringTag.valueOf("garbage")); // malformed compound

        CompoundTag players = new CompoundTag();
        players.put(PLAYER_A.toString(), playerATag);
        players.put(playerB.toString(), playerBTag);
        CompoundTag dataTag = new CompoundTag();
        dataTag.put("players", players);

        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, dataTag).getOrThrow();
        MilestoneData.PlayerData loadedA = loaded.playerOrNull(PLAYER_A);
        assertNotNull(loadedA);
        assertEquals("Alice", loadedA.lastKnownName);
        assertEquals(Map.of(
                TargetKey.of("minecraft:diamond", 1000), 50,
                TargetKey.of("minecraft:iron_ingot", 64), 0
        ), loadedA.progressNotified);

        MilestoneData.PlayerData loadedB = loaded.playerOrNull(playerB);
        assertNotNull(loadedB);
        assertEquals("Bob", loadedB.lastKnownName);
        assertTrue(loadedB.progressNotified.isEmpty());

        // Encode back and verify NBT key/value format and omission when empty
        CompoundTag encoded = (CompoundTag) MilestoneData.CODEC.encodeStart(NbtOps.INSTANCE, loaded).getOrThrow();
        CompoundTag encodedPlayers = encoded.getCompoundOrEmpty("players");
        CompoundTag encodedA = encodedPlayers.getCompoundOrEmpty(PLAYER_A.toString());
        assertTrue(encodedA.contains("progressNotified"));
        CompoundTag encodedProg = encodedA.getCompoundOrEmpty("progressNotified");
        assertEquals(50, encodedProg.getIntOr("minecraft:diamond#1000", -1));
        assertEquals(0, encodedProg.getIntOr("minecraft:iron_ingot#64", -1));
        assertFalse(encodedPlayers.getCompoundOrEmpty(playerB.toString()).contains("progressNotified"));

        // Verify 1.21.1 legacy file carrying progressNotified imports cleanly via readLegacy
        CompoundTag legacyRoot = new CompoundTag();
        legacyRoot.put("data", dataTag);
        legacyRoot.putInt("DataVersion", 3955);
        Path legacyFile = dir.resolve(MilestoneData.LEGACY_FILE);
        Files.createDirectories(legacyFile.getParent());
        NbtIo.writeCompressed(legacyRoot, legacyFile);

        MilestoneData migrated = MilestoneData.readLegacy(legacyFile);
        assertNotNull(migrated);
        assertEquals(Map.of(
                TargetKey.of("minecraft:diamond", 1000), 50,
                TargetKey.of("minecraft:iron_ingot", 64), 0
        ), migrated.playerOrNull(PLAYER_A).progressNotified);
        assertTrue(migrated.playerOrNull(playerB).progressNotified.isEmpty());
    }

    @Test
    void persistsMuteProgressAlertsLenientlyMigratesFromLegacyAndSurvivesResetAll(@TempDir Path dir) throws IOException {
        UUID playerB = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID playerC = UUID.fromString("33333333-3333-3333-3333-333333333333");

        CompoundTag playerATag = new CompoundTag();
        playerATag.putString("lastKnownName", "Alice");
        playerATag.putBoolean("muteProgressAlerts", true);

        CompoundTag playerBTag = new CompoundTag();
        playerBTag.putString("lastKnownName", "Bob");
        // muteProgressAlerts absent -> defaults to false

        CompoundTag playerCTag = new CompoundTag();
        playerCTag.putString("lastKnownName", "Charlie");
        playerCTag.put("muteProgressAlerts", StringTag.valueOf("garbage")); // malformed -> leniently defaults to false

        CompoundTag players = new CompoundTag();
        players.put(PLAYER_A.toString(), playerATag);
        players.put(playerB.toString(), playerBTag);
        players.put(playerC.toString(), playerCTag);
        CompoundTag dataTag = new CompoundTag();
        dataTag.put("players", players);

        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, dataTag).getOrThrow();
        MilestoneData.PlayerData loadedA = loaded.playerOrNull(PLAYER_A);
        MilestoneData.PlayerData loadedB = loaded.playerOrNull(playerB);
        MilestoneData.PlayerData loadedC = loaded.playerOrNull(playerC);
        assertNotNull(loadedA);
        assertNotNull(loadedB);
        assertNotNull(loadedC);
        assertTrue(loadedA.muteProgressAlerts);
        assertFalse(loadedB.muteProgressAlerts);
        assertFalse(loadedC.muteProgressAlerts);
        assertEquals("Charlie", loadedC.lastKnownName);

        // Encode back: write only when true
        CompoundTag encoded = (CompoundTag) MilestoneData.CODEC.encodeStart(NbtOps.INSTANCE, loaded).getOrThrow();
        CompoundTag encodedPlayers = encoded.getCompoundOrEmpty("players");
        assertTrue(encodedPlayers.getCompoundOrEmpty(PLAYER_A.toString()).getBooleanOr("muteProgressAlerts", false));
        assertFalse(encodedPlayers.getCompoundOrEmpty(playerB.toString()).contains("muteProgressAlerts"));
        assertFalse(encodedPlayers.getCompoundOrEmpty(playerC.toString()).contains("muteProgressAlerts"));

        // 1.21.1 legacy file carrying muteProgressAlerts migrates via readLegacy
        CompoundTag legacyRoot = new CompoundTag();
        legacyRoot.put("data", dataTag);
        legacyRoot.putInt("DataVersion", 3955);
        Path legacyFile = dir.resolve(MilestoneData.LEGACY_FILE);
        Files.createDirectories(legacyFile.getParent());
        NbtIo.writeCompressed(legacyRoot, legacyFile);

        MilestoneData migrated = MilestoneData.readLegacy(legacyFile);
        assertNotNull(migrated);
        assertTrue(migrated.playerOrNull(PLAYER_A).muteProgressAlerts);
        assertFalse(migrated.playerOrNull(playerB).muteProgressAlerts);
        assertFalse(migrated.playerOrNull(playerC).muteProgressAlerts);

        // ResetAll must NOT clear personal setting muteProgressAlerts, while purge removes PlayerData
        TargetKey diamond100 = TargetKey.of("minecraft:diamond", 100);
        loadedA.records.put(diamond100, new MilestoneRecord(100, 1L, 1000L));
        loadedA.progressNotified.put(diamond100, 50);
        assertTrue(MilestoneService.resetAllRecords(loaded, PLAYER_A, null).success());
        assertTrue(loadedA.records.isEmpty());
        assertTrue(loadedA.progressNotified.isEmpty());
        assertTrue(loadedA.muteProgressAlerts);

        assertTrue(loaded.purgePlayer(PLAYER_A));
        assertNull(loaded.playerOrNull(PLAYER_A));
    }

    @Test
    void persistsExcludeFromDiscordLenientlyMigratesFromLegacyAndSurvivesResetAll(@TempDir Path dir) throws IOException {
        UUID playerB = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID playerC = UUID.fromString("33333333-3333-3333-3333-333333333333");

        CompoundTag playerATag = new CompoundTag();
        playerATag.putString("lastKnownName", "Alice");
        playerATag.putBoolean("excludeFromDiscord", true);

        CompoundTag playerBTag = new CompoundTag();
        playerBTag.putString("lastKnownName", "Bob");
        // excludeFromDiscord absent -> defaults to false

        CompoundTag playerCTag = new CompoundTag();
        playerCTag.putString("lastKnownName", "Charlie");
        playerCTag.put("excludeFromDiscord", StringTag.valueOf("garbage")); // malformed -> leniently defaults to false

        CompoundTag players = new CompoundTag();
        players.put(PLAYER_A.toString(), playerATag);
        players.put(playerB.toString(), playerBTag);
        players.put(playerC.toString(), playerCTag);
        CompoundTag dataTag = new CompoundTag();
        dataTag.put("players", players);

        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, dataTag).getOrThrow();
        MilestoneData.PlayerData loadedA = loaded.playerOrNull(PLAYER_A);
        MilestoneData.PlayerData loadedB = loaded.playerOrNull(playerB);
        MilestoneData.PlayerData loadedC = loaded.playerOrNull(playerC);
        assertNotNull(loadedA);
        assertNotNull(loadedB);
        assertNotNull(loadedC);
        assertTrue(loadedA.excludeFromDiscord);
        assertFalse(loadedB.excludeFromDiscord);
        assertFalse(loadedC.excludeFromDiscord);
        assertEquals("Charlie", loadedC.lastKnownName);

        // Encode back: write only when true
        CompoundTag encoded = (CompoundTag) MilestoneData.CODEC.encodeStart(NbtOps.INSTANCE, loaded).getOrThrow();
        CompoundTag encodedPlayers = encoded.getCompoundOrEmpty("players");
        assertTrue(encodedPlayers.getCompoundOrEmpty(PLAYER_A.toString()).getBooleanOr("excludeFromDiscord", false));
        assertFalse(encodedPlayers.getCompoundOrEmpty(playerB.toString()).contains("excludeFromDiscord"));
        assertFalse(encodedPlayers.getCompoundOrEmpty(playerC.toString()).contains("excludeFromDiscord"));

        // 1.21.1 legacy file carrying excludeFromDiscord migrates via readLegacy
        CompoundTag legacyRoot = new CompoundTag();
        legacyRoot.put("data", dataTag);
        legacyRoot.putInt("DataVersion", 3955);
        Path legacyFile = dir.resolve(MilestoneData.LEGACY_FILE);
        Files.createDirectories(legacyFile.getParent());
        NbtIo.writeCompressed(legacyRoot, legacyFile);

        MilestoneData migrated = MilestoneData.readLegacy(legacyFile);
        assertNotNull(migrated);
        assertTrue(migrated.playerOrNull(PLAYER_A).excludeFromDiscord);
        assertFalse(migrated.playerOrNull(playerB).excludeFromDiscord);
        assertFalse(migrated.playerOrNull(playerC).excludeFromDiscord);

        // ResetAll must NOT clear personal setting excludeFromDiscord, while purge removes PlayerData
        TargetKey diamond100 = TargetKey.of("minecraft:diamond", 100);
        loadedA.records.put(diamond100, new MilestoneRecord(100, 1L, 1000L));
        loadedA.progressNotified.put(diamond100, 50);
        assertTrue(MilestoneService.resetAllRecords(loaded, PLAYER_A, null).success());
        assertTrue(loadedA.records.isEmpty());
        assertTrue(loadedA.progressNotified.isEmpty());
        assertTrue(loadedA.excludeFromDiscord);

        assertTrue(loaded.purgePlayer(PLAYER_A));
        assertNull(loaded.playerOrNull(PLAYER_A));
    }

    @Test
    void persistsHideFromLookupLenientlyRoundTripsMigratesFromLegacyAndSurvivesResetAll(@TempDir Path dir) throws IOException {
        UUID playerB = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID playerC = UUID.fromString("33333333-3333-3333-3333-333333333333");

        CompoundTag playerATag = new CompoundTag();
        playerATag.putString("lastKnownName", "Alice");
        playerATag.putBoolean("hideFromLookup", true);

        CompoundTag playerBTag = new CompoundTag();
        playerBTag.putString("lastKnownName", "Bob");
        // hideFromLookup absent (1.0.1 / pre-F14 data) -> defaults to false

        CompoundTag playerCTag = new CompoundTag();
        playerCTag.putString("lastKnownName", "Charlie");
        playerCTag.put("hideFromLookup", StringTag.valueOf("garbage")); // malformed -> leniently defaults to false

        CompoundTag players = new CompoundTag();
        players.put(PLAYER_A.toString(), playerATag);
        players.put(playerB.toString(), playerBTag);
        players.put(playerC.toString(), playerCTag);
        CompoundTag dataTag = new CompoundTag();
        dataTag.put("players", players);

        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, dataTag).getOrThrow();
        MilestoneData.PlayerData loadedA = loaded.playerOrNull(PLAYER_A);
        MilestoneData.PlayerData loadedB = loaded.playerOrNull(playerB);
        MilestoneData.PlayerData loadedC = loaded.playerOrNull(playerC);
        assertNotNull(loadedA);
        assertNotNull(loadedB);
        assertNotNull(loadedC);
        assertTrue(loadedA.hideFromLookup);
        assertTrue(loadedA.isHiddenFromLookup());
        assertFalse(loadedB.isHiddenFromLookup());
        assertFalse(loadedC.isHiddenFromLookup());
        assertEquals("Charlie", loadedC.lastKnownName);

        // Encode back: write only when true
        CompoundTag encoded = (CompoundTag) MilestoneData.CODEC.encodeStart(NbtOps.INSTANCE, loaded).getOrThrow();
        CompoundTag encodedPlayers = encoded.getCompoundOrEmpty("players");
        assertTrue(encodedPlayers.getCompoundOrEmpty(PLAYER_A.toString()).getBooleanOr("hideFromLookup", false));
        assertFalse(encodedPlayers.getCompoundOrEmpty(playerB.toString()).contains("hideFromLookup"));
        assertFalse(encodedPlayers.getCompoundOrEmpty(playerC.toString()).contains("hideFromLookup"));

        // Round trip: encode -> decode keeps the flag
        MilestoneData reloaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
        assertTrue(reloaded.playerOrNull(PLAYER_A).isHiddenFromLookup());
        assertFalse(reloaded.playerOrNull(playerB).isHiddenFromLookup());

        // 1.21.1 legacy file carrying hideFromLookup (same key/type) migrates via readLegacy
        CompoundTag legacyRoot = new CompoundTag();
        legacyRoot.put("data", dataTag);
        legacyRoot.putInt("DataVersion", 3955);
        Path legacyFile = dir.resolve(MilestoneData.LEGACY_FILE);
        Files.createDirectories(legacyFile.getParent());
        NbtIo.writeCompressed(legacyRoot, legacyFile);

        MilestoneData migrated = MilestoneData.readLegacy(legacyFile);
        assertNotNull(migrated);
        assertTrue(migrated.playerOrNull(PLAYER_A).isHiddenFromLookup());
        assertFalse(migrated.playerOrNull(playerB).isHiddenFromLookup());
        assertFalse(migrated.playerOrNull(playerC).isHiddenFromLookup());

        // ResetAll must NOT clear personal setting hideFromLookup
        TargetKey diamond100 = TargetKey.of("minecraft:diamond", 100);
        loadedA.records.put(diamond100, new MilestoneRecord(100, 1L, 1000L));
        assertTrue(MilestoneService.resetAllRecords(loaded, PLAYER_A, null).success());
        assertTrue(loadedA.records.isEmpty());
        assertTrue(loadedA.isHiddenFromLookup());
    }

    @Test
    void persistsCompletedPresetsSkipsInvalidEntriesMigratesFromLegacyAndClearsOnResetAll(@TempDir Path dir) throws IOException {
        UUID playerB = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID playerC = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID playerD = UUID.fromString("44444444-4444-4444-4444-444444444444");

        ListTag completedList = new ListTag();
        completedList.add(StringTag.valueOf("vanilla_endgame"));
        completedList.add(StringTag.valueOf("atm_star"));
        completedList.add(StringTag.valueOf("my_custom-1"));
        completedList.add(StringTag.valueOf("")); // invalid empty ID
        completedList.add(StringTag.valueOf("Bad Name")); // invalid characters
        completedList.add(StringTag.valueOf("list")); // reserved ID

        CompoundTag playerATag = new CompoundTag();
        playerATag.putString("lastKnownName", "Alice");
        playerATag.put("completedPresets", completedList);

        CompoundTag playerBTag = new CompoundTag();
        playerBTag.putString("lastKnownName", "Bob");
        // completedPresets absent -> defaults to empty

        CompoundTag playerCTag = new CompoundTag();
        playerCTag.putString("lastKnownName", "Charlie");
        playerCTag.put("completedPresets", StringTag.valueOf("not-a-list")); // malformed -> leniently defaults to empty

        ListTag intList = new ListTag();
        intList.add(IntTag.valueOf(42));
        CompoundTag playerDTag = new CompoundTag();
        playerDTag.putString("lastKnownName", "Dana");
        playerDTag.put("completedPresets", intList); // non-string list elements -> leniently skipped

        CompoundTag players = new CompoundTag();
        players.put(PLAYER_A.toString(), playerATag);
        players.put(playerB.toString(), playerBTag);
        players.put(playerC.toString(), playerCTag);
        players.put(playerD.toString(), playerDTag);
        CompoundTag dataTag = new CompoundTag();
        dataTag.put("players", players);

        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, dataTag).getOrThrow();
        MilestoneData.PlayerData loadedA = loaded.playerOrNull(PLAYER_A);
        MilestoneData.PlayerData loadedB = loaded.playerOrNull(playerB);
        MilestoneData.PlayerData loadedC = loaded.playerOrNull(playerC);
        MilestoneData.PlayerData loadedD = loaded.playerOrNull(playerD);
        assertNotNull(loadedA);
        assertNotNull(loadedB);
        assertNotNull(loadedC);
        assertNotNull(loadedD);
        assertEquals(List.of("vanilla_endgame", "atm_star", "my_custom-1"), List.copyOf(loadedA.completedPresets));
        assertTrue(loadedB.completedPresets.isEmpty());
        assertTrue(loadedC.completedPresets.isEmpty());
        assertEquals("Charlie", loadedC.lastKnownName);
        assertTrue(loadedD.completedPresets.isEmpty());
        assertEquals("Dana", loadedD.lastKnownName);

        // Encode back: write only when non-empty
        CompoundTag encoded = (CompoundTag) MilestoneData.CODEC.encodeStart(NbtOps.INSTANCE, loaded).getOrThrow();
        CompoundTag encodedPlayers = encoded.getCompoundOrEmpty("players");
        assertTrue(encodedPlayers.getCompoundOrEmpty(PLAYER_A.toString()).contains("completedPresets"));
        assertFalse(encodedPlayers.getCompoundOrEmpty(playerB.toString()).contains("completedPresets"));
        assertFalse(encodedPlayers.getCompoundOrEmpty(playerC.toString()).contains("completedPresets"));
        assertFalse(encodedPlayers.getCompoundOrEmpty(playerD.toString()).contains("completedPresets"));

        // 1.21.1 legacy file carrying completedPresets migrates cleanly via readLegacy
        CompoundTag legacyRoot = new CompoundTag();
        legacyRoot.put("data", dataTag);
        legacyRoot.putInt("DataVersion", 3955);
        Path legacyFile = dir.resolve(MilestoneData.LEGACY_FILE);
        Files.createDirectories(legacyFile.getParent());
        NbtIo.writeCompressed(legacyRoot, legacyFile);

        MilestoneData migrated = MilestoneData.readLegacy(legacyFile);
        assertNotNull(migrated);
        assertEquals(List.of("vanilla_endgame", "atm_star", "my_custom-1"),
                List.copyOf(migrated.playerOrNull(PLAYER_A).completedPresets));
        assertTrue(migrated.playerOrNull(playerB).completedPresets.isEmpty());
        assertTrue(migrated.playerOrNull(playerC).completedPresets.isEmpty());
        assertTrue(migrated.playerOrNull(playerD).completedPresets.isEmpty());

        // Single reset keeps completedPresets; resetAll clears completedPresets
        TargetKey diamond = TargetKey.of("minecraft:diamond");
        loadedA.records.put(diamond, new MilestoneRecord(100, 1L, 1000L));
        assertTrue(MilestoneService.resetRecord(
                loaded, PLAYER_A, null, "minecraft:diamond", 1, Set.of("minecraft:diamond")::contains,
                k -> net.minecraft.network.chat.Component.literal(k.formatDisplayId())).success());
        assertEquals(Set.of("vanilla_endgame", "atm_star", "my_custom-1"), loadedA.completedPresets);

        assertTrue(MilestoneService.resetAllRecords(loaded, PLAYER_A, null).success());
        assertTrue(loadedA.completedPresets.isEmpty());
    }

    @Test
    void persistsLastSeenLenientlyRoundTripsMigratesFromLegacyAndSurvivesResetAll(@TempDir Path dir) throws IOException {
        UUID playerB = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID playerC = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID playerD = UUID.fromString("44444444-4444-4444-4444-444444444444");

        CompoundTag playerATag = new CompoundTag();
        playerATag.putString("lastKnownName", "Alice");
        playerATag.putLong("lastSeen", 1795800000000L);

        CompoundTag playerBTag = new CompoundTag();
        playerBTag.putString("lastKnownName", "Bob");
        // lastSeen absent (1.0.1 / pre-F37 data) -> defaults to 0

        CompoundTag playerCTag = new CompoundTag();
        playerCTag.putString("lastKnownName", "Charlie");
        playerCTag.put("lastSeen", StringTag.valueOf("garbage")); // malformed -> leniently defaults to 0

        CompoundTag playerDTag = new CompoundTag();
        playerDTag.putString("lastKnownName", "Dana");
        playerDTag.putLong("lastSeen", -100L); // negative -> skipped, defaults to 0

        CompoundTag players = new CompoundTag();
        players.put(PLAYER_A.toString(), playerATag);
        players.put(playerB.toString(), playerBTag);
        players.put(playerC.toString(), playerCTag);
        players.put(playerD.toString(), playerDTag);
        CompoundTag dataTag = new CompoundTag();
        dataTag.put("players", players);

        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, dataTag).getOrThrow();
        MilestoneData.PlayerData loadedA = loaded.playerOrNull(PLAYER_A);
        MilestoneData.PlayerData loadedB = loaded.playerOrNull(playerB);
        MilestoneData.PlayerData loadedC = loaded.playerOrNull(playerC);
        MilestoneData.PlayerData loadedD = loaded.playerOrNull(playerD);
        assertNotNull(loadedA);
        assertNotNull(loadedB);
        assertNotNull(loadedC);
        assertNotNull(loadedD);
        assertEquals(1795800000000L, loadedA.lastSeen);
        assertEquals(0L, loadedB.lastSeen);
        assertEquals(0L, loadedC.lastSeen);
        assertEquals("Charlie", loadedC.lastKnownName);
        assertEquals(0L, loadedD.lastSeen);
        assertEquals("Dana", loadedD.lastKnownName);

        // Encode back: write only when > 0
        CompoundTag encoded = (CompoundTag) MilestoneData.CODEC.encodeStart(NbtOps.INSTANCE, loaded).getOrThrow();
        CompoundTag encodedPlayers = encoded.getCompoundOrEmpty("players");
        assertEquals(1795800000000L, encodedPlayers.getCompoundOrEmpty(PLAYER_A.toString()).getLongOr("lastSeen", 0L));
        assertFalse(encodedPlayers.getCompoundOrEmpty(playerB.toString()).contains("lastSeen"));
        assertFalse(encodedPlayers.getCompoundOrEmpty(playerC.toString()).contains("lastSeen"));
        assertFalse(encodedPlayers.getCompoundOrEmpty(playerD.toString()).contains("lastSeen"));

        // Round trip: encode -> decode preserves lastSeen
        MilestoneData reloaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
        assertEquals(1795800000000L, reloaded.playerOrNull(PLAYER_A).lastSeen);
        assertEquals(0L, reloaded.playerOrNull(playerB).lastSeen);

        // 1.21.1 legacy file carrying lastSeen (same key/type) migrates via readLegacy
        CompoundTag legacyRoot = new CompoundTag();
        legacyRoot.put("data", dataTag);
        legacyRoot.putInt("DataVersion", 3955);
        Path legacyFile = dir.resolve(MilestoneData.LEGACY_FILE);
        Files.createDirectories(legacyFile.getParent());
        NbtIo.writeCompressed(legacyRoot, legacyFile);

        MilestoneData migrated = MilestoneData.readLegacy(legacyFile);
        assertNotNull(migrated);
        assertEquals(1795800000000L, migrated.playerOrNull(PLAYER_A).lastSeen);
        assertEquals(0L, migrated.playerOrNull(playerB).lastSeen);
        assertEquals(0L, migrated.playerOrNull(playerC).lastSeen);
        assertEquals(0L, migrated.playerOrNull(playerD).lastSeen);

        // ResetAll must NOT modify lastSeen
        TargetKey diamond = TargetKey.of("minecraft:diamond");
        loadedA.records.put(diamond, new MilestoneRecord(100, 1L, 1000L));
        assertTrue(MilestoneService.resetAllRecords(loaded, PLAYER_A, null).success());
        assertTrue(loadedA.records.isEmpty());
        assertEquals(1795800000000L, loadedA.lastSeen);
    }

    @Test
    void roundTripsTagKeysAcrossTargetsRecordsWorldFirstsAndProgressNotified(@TempDir Path dir) throws IOException {
        TargetKey ironTag1 = TargetKey.of("#c:ingots/iron");
        TargetKey ironTag100 = TargetKey.of("#c:ingots/iron", 100);
        TargetKey logsTag64 = TargetKey.of("#minecraft:logs", 64);
        MilestoneRecord rec1 = new MilestoneRecord(1200, 2L, 1795600000000L);
        MilestoneRecord rec100 = new MilestoneRecord(48000, 15L, 1795700000000L);

        MilestoneData original = new MilestoneData();
        MilestoneData.PlayerData pd = original.player(PLAYER_A);
        pd.lastKnownName = "Alice";
        pd.targets.add(ironTag1);
        pd.targets.add(ironTag100);
        pd.targets.add(logsTag64);
        pd.records.put(ironTag1, rec1);
        pd.records.put(ironTag100, rec100);
        pd.progressNotified.put(logsTag64, 50);
        assertTrue(original.recordWorldFirstIfAbsent(ironTag1, PLAYER_A, "Alice", rec1));
        assertTrue(original.recordWorldFirstIfAbsent(ironTag100, PLAYER_A, "Alice", rec100));

        CompoundTag encoded = (CompoundTag) MilestoneData.CODEC.encodeStart(NbtOps.INSTANCE, original).getOrThrow();
        CompoundTag encodedPlayer = encoded.getCompoundOrEmpty("players").getCompoundOrEmpty(PLAYER_A.toString());
        ListTag encodedTargets = encodedPlayer.getListOrEmpty("targets");
        assertEquals(List.of("#c:ingots/iron", "#c:ingots/iron#100", "#minecraft:logs#64"),
                List.of(encodedTargets.getStringOr(0, ""), encodedTargets.getStringOr(1, ""), encodedTargets.getStringOr(2, "")));
        assertTrue(encodedPlayer.getCompoundOrEmpty("records").contains("#c:ingots/iron"));
        assertEquals(50, encodedPlayer.getCompoundOrEmpty("progressNotified").getIntOr("#minecraft:logs#64", -1));
        assertTrue(encoded.getCompoundOrEmpty("worldFirsts").contains("#c:ingots/iron"));

        // Inject malformed tag entries to confirm lenient per-entry skipping
        encodedTargets.add(StringTag.valueOf("#"));
        encodedTargets.add(StringTag.valueOf("##c:x"));
        encodedTargets.add(StringTag.valueOf("#c:x#0"));
        encodedTargets.add(StringTag.valueOf("#:x"));
        encodedTargets.add(StringTag.valueOf("#c:"));
        encodedPlayer.getCompoundOrEmpty("progressNotified").putInt("#c:x#abc", 50);

        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
        MilestoneData.PlayerData loadedPd = loaded.playerOrNull(PLAYER_A);
        assertNotNull(loadedPd);
        assertEquals(List.of(ironTag1, ironTag100, logsTag64), List.copyOf(loadedPd.targets));
        assertEquals(Map.of(ironTag1, rec1, ironTag100, rec100), loadedPd.records);
        assertEquals(Map.of(logsTag64, 50), loadedPd.progressNotified);

        WorldFirstRecord wf1 = loaded.worldFirstOrNull(ironTag1);
        assertNotNull(wf1);
        assertEquals(ironTag1, wf1.targetKey());
        assertTrue(wf1.targetKey().isTag());
        assertEquals("c:ingots/iron", wf1.targetKey().tagId());
        assertEquals(1200, wf1.playTicks());

        WorldFirstRecord wf100 = loaded.worldFirstOrNull(ironTag100);
        assertNotNull(wf100);
        assertEquals(ironTag100, wf100.targetKey());
        assertEquals(100, wf100.count());
        assertEquals(48000, wf100.playTicks());

        // 1.21.1 legacy file carrying tag keys migrates cleanly via readLegacy
        CompoundTag legacyRoot = new CompoundTag();
        legacyRoot.put("data", encoded);
        legacyRoot.putInt("DataVersion", 3955);
        Path legacyFile = dir.resolve(MilestoneData.LEGACY_FILE);
        Files.createDirectories(legacyFile.getParent());
        NbtIo.writeCompressed(legacyRoot, legacyFile);

        MilestoneData migrated = MilestoneData.readLegacy(legacyFile);
        assertNotNull(migrated);
        MilestoneData.PlayerData migratedPd = migrated.playerOrNull(PLAYER_A);
        assertNotNull(migratedPd);
        assertEquals(List.of(ironTag1, ironTag100, logsTag64), List.copyOf(migratedPd.targets));
        assertEquals(Map.of(ironTag1, rec1, ironTag100, rec100), migratedPd.records);
        assertEquals(Map.of(logsTag64, 50), migratedPd.progressNotified);
        assertEquals(wf1, migrated.worldFirstOrNull(ironTag1));
        assertEquals(wf100, migrated.worldFirstOrNull(ironTag100));
    }

    @Test
    void persistsLastDigestEpochLenientlyOmitsWhenZeroRoundTripsAndMigratesFromLegacy(@TempDir Path dir) throws IOException {
        MilestoneData original = new MilestoneData();
        assertEquals(0L, original.lastDigestEpochMillis());
        CompoundTag emptyEncoded = (CompoundTag) MilestoneData.CODEC.encodeStart(NbtOps.INSTANCE, original).getOrThrow();
        assertFalse(emptyEncoded.contains("lastDigestEpoch"), emptyEncoded::toString);

        // Positive timestamp marks dirty, encodes "lastDigestEpoch", and round-trips cleanly.
        original.setLastDigestEpochMillis(1795800000000L);
        assertTrue(original.isDirty());
        CompoundTag encoded = (CompoundTag) MilestoneData.CODEC.encodeStart(NbtOps.INSTANCE, original).getOrThrow();
        assertEquals(1795800000000L, encoded.getLongOr("lastDigestEpoch", 0L));
        MilestoneData reloaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
        assertEquals(1795800000000L, reloaded.lastDigestEpochMillis());

        // Setting negative clamps to 0 and omits key when encoded.
        original.setLastDigestEpochMillis(-50L);
        assertEquals(0L, original.lastDigestEpochMillis());
        CompoundTag zeroEncoded = (CompoundTag) MilestoneData.CODEC.encodeStart(NbtOps.INSTANCE, original).getOrThrow();
        assertFalse(zeroEncoded.contains("lastDigestEpoch"), zeroEncoded::toString);

        // Malformed tag (non-numeric) is skipped leniently without breaking player data.
        CompoundTag playerATag = new CompoundTag();
        playerATag.putString("lastKnownName", "Alice");
        CompoundTag players = new CompoundTag();
        players.put(PLAYER_A.toString(), playerATag);

        CompoundTag malformedTag = new CompoundTag();
        malformedTag.put("players", players.copy());
        malformedTag.put("lastDigestEpoch", StringTag.valueOf("garbage"));
        MilestoneData loadedMalformed = MilestoneData.CODEC.parse(NbtOps.INSTANCE, malformedTag).getOrThrow();
        assertEquals(0L, loadedMalformed.lastDigestEpochMillis());
        assertEquals("Alice", loadedMalformed.playerOrNull(PLAYER_A).lastKnownName);

        // Negative value is skipped leniently and defaults to 0.
        CompoundTag negativeTag = new CompoundTag();
        negativeTag.put("players", players.copy());
        negativeTag.putLong("lastDigestEpoch", -100L);
        MilestoneData loadedNegative = MilestoneData.CODEC.parse(NbtOps.INSTANCE, negativeTag).getOrThrow();
        assertEquals(0L, loadedNegative.lastDigestEpochMillis());
        assertEquals("Alice", loadedNegative.playerOrNull(PLAYER_A).lastKnownName);

        // 1.21.1 legacy file carrying "lastDigestEpoch" migrates cleanly via readLegacy.
        CompoundTag legacyData = new CompoundTag();
        legacyData.put("players", players.copy());
        legacyData.putLong("lastDigestEpoch", 1795800000000L);
        CompoundTag legacyRoot = new CompoundTag();
        legacyRoot.put("data", legacyData);
        legacyRoot.putInt("DataVersion", 3955);
        Path legacyFile = dir.resolve(MilestoneData.LEGACY_FILE);
        Files.createDirectories(legacyFile.getParent());
        NbtIo.writeCompressed(legacyRoot, legacyFile);

        MilestoneData migrated = MilestoneData.readLegacy(legacyFile);
        assertNotNull(migrated);
        assertEquals(1795800000000L, migrated.lastDigestEpochMillis());
        assertEquals("Alice", migrated.playerOrNull(PLAYER_A).lastKnownName);
    }

    private static CompoundTag encodeRoot(MilestoneData data) {
        return (CompoundTag) MilestoneData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
    }

    @Test
    void writesSchemaVersionOneForNewDataAndRoundTrips() {
        MilestoneData original = new MilestoneData();
        original.player(PLAYER_A).targets.add(TargetKey.of("minecraft:diamond"));

        CompoundTag saved = encodeRoot(original);
        assertEquals(SchemaVersionLogic.CURRENT_SCHEMA_VERSION, saved.getIntOr("schemaVersion", -1));

        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        assertEquals(SchemaVersionLogic.CURRENT_SCHEMA_VERSION, loaded.retainedSchemaVersion());
        assertEquals(SchemaVersionLogic.CURRENT_SCHEMA_VERSION, loaded.schemaVersionForSave());

        CompoundTag resaved = encodeRoot(loaded);
        assertEquals(SchemaVersionLogic.CURRENT_SCHEMA_VERSION, resaved.getIntOr("schemaVersion", -1));
    }

    @Test
    void legacyFixtureWithoutSchemaVersionLoadsAndSavesBackWithOneAndBodyUnchanged() {
        MilestoneData original = new MilestoneData();
        MilestoneData.PlayerData pd = original.player(PLAYER_A);
        pd.targets.add(TargetKey.of("minecraft:diamond", 100));
        MilestoneRecord record = new MilestoneRecord(12340, 11L, 1795600000000L);
        pd.records.put(TargetKey.of("minecraft:diamond", 100), record);
        pd.lastKnownName = "Alice";
        original.recordWorldFirstIfAbsent(TargetKey.of("minecraft:diamond", 100), PLAYER_A, "Alice", record);

        // 1.3.1 fixture: identical body, no schemaVersion field.
        CompoundTag legacy = encodeRoot(original);
        legacy.remove("schemaVersion");
        assertFalse(legacy.contains("schemaVersion"));

        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, legacy).getOrThrow();
        assertEquals(0, loaded.retainedSchemaVersion());
        assertEquals(SchemaVersionLogic.CURRENT_SCHEMA_VERSION, loaded.schemaVersionForSave());

        MilestoneData.PlayerData loadedPd = loaded.playerOrNull(PLAYER_A);
        assertNotNull(loadedPd);
        assertEquals(Set.of(TargetKey.of("minecraft:diamond", 100)), loadedPd.targets);
        assertEquals(record, loadedPd.records.get(TargetKey.of("minecraft:diamond", 100)));
        assertEquals("Alice", loadedPd.lastKnownName);
        assertEquals(original.worldFirsts(), loaded.worldFirsts());

        CompoundTag resaved = encodeRoot(loaded);
        assertEquals(SchemaVersionLogic.CURRENT_SCHEMA_VERSION, resaved.getIntOr("schemaVersion", -1));
        assertEquals(legacy.get("players").toString(), resaved.get("players").toString());
    }

    @Test
    void futureSchemaVersion99IsPreservedOnSaveAndInvalidValuesAreTreatedAsZero() {
        MilestoneData original = new MilestoneData();
        original.player(PLAYER_A).targets.add(TargetKey.of("minecraft:diamond"));
        CompoundTag tag = encodeRoot(original);

        tag.putInt("schemaVersion", 99);
        MilestoneData future = MilestoneData.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
        assertEquals(99, future.retainedSchemaVersion());
        assertEquals(99, future.schemaVersionForSave());
        assertEquals(99, encodeRoot(future).getIntOr("schemaVersion", -1));

        tag.putInt("schemaVersion", -3);
        MilestoneData negative = MilestoneData.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
        assertEquals(0, negative.retainedSchemaVersion());
        assertEquals(SchemaVersionLogic.CURRENT_SCHEMA_VERSION,
                encodeRoot(negative).getIntOr("schemaVersion", -1));

        tag.putString("schemaVersion", "not-a-number");
        MilestoneData malformed = MilestoneData.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
        assertEquals(0, malformed.retainedSchemaVersion());
        assertEquals(SchemaVersionLogic.CURRENT_SCHEMA_VERSION,
                encodeRoot(malformed).getIntOr("schemaVersion", -1));
        assertEquals(Set.of(TargetKey.of("minecraft:diamond")),
                malformed.playerOrNull(PLAYER_A).targets);
    }

    @Test
    void readLegacyPreservesSchemaVersionFromThe1211MigrationFile(@TempDir Path dir) throws IOException {
        CompoundTag player = new CompoundTag();
        ListTag targets = new ListTag();
        targets.add(StringTag.valueOf("minecraft:diamond"));
        player.put("targets", targets);
        CompoundTag players = new CompoundTag();
        players.put(PLAYER_A.toString(), player);

        // Future value: must survive the 1.21.1 -> 26.x migration and a re-save.
        CompoundTag data = new CompoundTag();
        data.put("players", players.copy());
        data.putInt("schemaVersion", 99);
        CompoundTag root = new CompoundTag();
        root.put("data", data);
        root.putInt("DataVersion", 3955);
        Path file = dir.resolve(MilestoneData.LEGACY_FILE);
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(root, file);

        MilestoneData migrated = MilestoneData.readLegacy(file);
        assertNotNull(migrated);
        assertEquals(99, migrated.retainedSchemaVersion());
        assertEquals(99, encodeRoot(migrated).getIntOr("schemaVersion", -1));
        assertEquals(Set.of(TargetKey.of("minecraft:diamond")),
                migrated.playerOrNull(PLAYER_A).targets);

        // Legacy file without the field: migration treats it as 0 and the next save writes 1.
        CompoundTag legacyData = new CompoundTag();
        legacyData.put("players", players.copy());
        CompoundTag legacyRoot = new CompoundTag();
        legacyRoot.put("data", legacyData);
        legacyRoot.putInt("DataVersion", 3955);
        Path legacyFile = dir.resolve("data/legacy-no-schema.dat");
        Files.createDirectories(legacyFile.getParent());
        NbtIo.writeCompressed(legacyRoot, legacyFile);

        MilestoneData legacyMigrated = MilestoneData.readLegacy(legacyFile);
        assertNotNull(legacyMigrated);
        assertEquals(0, legacyMigrated.retainedSchemaVersion());
        assertEquals(SchemaVersionLogic.CURRENT_SCHEMA_VERSION,
                encodeRoot(legacyMigrated).getIntOr("schemaVersion", -1));
    }
    @Test
    void loadReportCountsElementSkipsAndCleanLoadYieldsZero() {
        CompoundTag playerTag = new CompoundTag();
        ListTag targets = new ListTag();
        targets.add(StringTag.valueOf("minecraft:diamond"));
        targets.add(StringTag.valueOf("minecraft:emerald#0")); // invalid count -> one element skip
        playerTag.put("targets", targets);
        CompoundTag players = new CompoundTag();
        players.put(PLAYER_A.toString(), playerTag);
        CompoundTag corrupt = new CompoundTag();
        corrupt.put("players", players);
        corrupt.putInt("schemaVersion", 1);

        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, corrupt).getOrThrow();
        assertNotNull(loaded.loadReport());
        assertEquals(1, loaded.loadReport().elementSkipCount());
        assertEquals(1, loaded.loadReport().loadedSchemaVersion());
        assertFalse(loaded.loadReport().totalFailure());
        assertEquals(1, loaded.playerOrNull(PLAYER_A).targets.size());

        CompoundTag clean = encodeRoot(loaded);
        MilestoneData cleanLoaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, clean).getOrThrow();
        assertNotNull(cleanLoaded.loadReport());
        assertEquals(0, cleanLoaded.loadReport().elementSkipCount());
        assertEquals(1, cleanLoaded.loadReport().loadedSchemaVersion());
    }

    @Test
    void loadReportCountsMalformedSchemaVersionButNotFutureVersion() {
        MilestoneData original = new MilestoneData();
        original.player(PLAYER_A).targets.add(TargetKey.of("minecraft:diamond"));
        CompoundTag tag = encodeRoot(original);

        tag.putInt("schemaVersion", 99);
        MilestoneData future = MilestoneData.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
        assertNotNull(future.loadReport());
        assertEquals(0, future.loadReport().elementSkipCount());
        assertEquals(99, future.loadReport().loadedSchemaVersion());

        tag.putString("schemaVersion", "not-a-number");
        MilestoneData malformed = MilestoneData.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
        assertNotNull(malformed.loadReport());
        assertEquals(1, malformed.loadReport().elementSkipCount());
        assertEquals(0, malformed.loadReport().loadedSchemaVersion());
    }

    @Test
    void presetStateRoundTripsAndLegacyDataWithoutItLoadsEmpty() {
        MilestoneData original = new MilestoneData();
        MilestoneData.PlayerData pd = original.player(PLAYER_A);
        pd.presetState.put("vanilla_endgame", new PresetState(true, 3));
        pd.presetState.put("my_custom_pack", new PresetState(false, 1));

        CompoundTag saved = (CompoundTag) MilestoneData.CODEC.encodeStart(NbtOps.INSTANCE, original).getOrThrow();
        CompoundTag savedPd = saved.getCompoundOrEmpty("players").getCompoundOrEmpty(PLAYER_A.toString());
        assertTrue(savedPd.contains("presetState"));
        assertTrue(savedPd.getCompoundOrEmpty("presetState").contains("vanilla_endgame"));

        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        MilestoneData.PlayerData loadedPd = loaded.playerOrNull(PLAYER_A);
        assertNotNull(loadedPd);
        assertEquals(Map.of(
                "vanilla_endgame", new PresetState(true, 3),
                "my_custom_pack", new PresetState(false, 1)
        ), loadedPd.presetState);

        // Legacy data (no presetState key) loads as an empty map.
        CompoundTag legacy = new CompoundTag();
        CompoundTag players = new CompoundTag();
        players.put(PLAYER_A.toString(), new CompoundTag());
        legacy.put("players", players);
        MilestoneData legacyLoaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, legacy).getOrThrow();
        assertNotNull(legacyLoaded.playerOrNull(PLAYER_A));
        assertTrue(legacyLoaded.playerOrNull(PLAYER_A).presetState.isEmpty());
    }

    @Test
    void readLegacyMigratesPresetStateFromA121File(@TempDir Path dir) throws IOException {
        // #122: the file-based 1.21.1 -> 26.x migration path must carry presetState, not just
        // the Codec round trip above.
        CompoundTag stateA = new CompoundTag();
        stateA.putBoolean("autoApplied", true);
        stateA.putInt("lastSeenVersion", 3);
        CompoundTag stateB = new CompoundTag();
        stateB.putBoolean("autoApplied", false);
        stateB.putInt("lastSeenVersion", 1);
        CompoundTag presetState = new CompoundTag();
        presetState.put("vanilla_endgame", stateA);
        presetState.put("my_custom_pack", stateB);
        CompoundTag player = new CompoundTag();
        player.put("presetState", presetState);
        player.putString("lastKnownName", "Alice");
        CompoundTag players = new CompoundTag();
        players.put(PLAYER_A.toString(), player);
        CompoundTag dataTag = new CompoundTag();
        dataTag.put("players", players);
        CompoundTag root = new CompoundTag();
        root.put("data", dataTag);
        root.putInt("DataVersion", 3955);
        Path legacyFile = dir.resolve(MilestoneData.LEGACY_FILE);
        Files.createDirectories(legacyFile.getParent());
        NbtIo.writeCompressed(root, legacyFile);

        MilestoneData migrated = MilestoneData.readLegacy(legacyFile);
        assertNotNull(migrated);
        MilestoneData.PlayerData loadedA = migrated.playerOrNull(PLAYER_A);
        assertNotNull(loadedA);
        Map<String, PresetState> expected = Map.of(
                "vanilla_endgame", new PresetState(true, 3),
                "my_custom_pack", new PresetState(false, 1));
        assertEquals(expected, loadedA.presetState);
        assertEquals("Alice", loadedA.lastKnownName);

        // And the migrated field survives re-encoding into the 26.x save format.
        CompoundTag saved = (CompoundTag) MilestoneData.CODEC.encodeStart(NbtOps.INSTANCE, migrated).getOrThrow();
        MilestoneData roundTripped = MilestoneData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        assertEquals(expected, roundTripped.playerOrNull(PLAYER_A).presetState);
    }

    @Test
    void presetStateLoaderSkipsInvalidElementsAndCountsThemInTheLoadReport() {
        MilestoneData original = new MilestoneData();
        original.player(PLAYER_A);
        CompoundTag saved = (CompoundTag) MilestoneData.CODEC.encodeStart(NbtOps.INSTANCE, original).getOrThrow();
        CompoundTag savedPd = saved.getCompoundOrEmpty("players").getCompoundOrEmpty(PLAYER_A.toString());

        CompoundTag good = new CompoundTag();
        good.putBoolean("autoApplied", true);
        good.putInt("lastSeenVersion", 2);
        CompoundTag invalidId = new CompoundTag();
        invalidId.putBoolean("autoApplied", false);
        invalidId.putInt("lastSeenVersion", 1);
        CompoundTag negativeVersion = new CompoundTag();
        negativeVersion.putBoolean("autoApplied", true);
        negativeVersion.putInt("lastSeenVersion", -1);
        CompoundTag malformedAuto = new CompoundTag();
        malformedAuto.putString("autoApplied", "yes");
        malformedAuto.putInt("lastSeenVersion", 2);
        CompoundTag states = new CompoundTag();
        states.put("good_pack", good);
        states.put("Bad ID", invalidId);
        states.put("scalar_value", StringTag.valueOf("nope"));
        states.put("negative_version", negativeVersion);
        states.put("malformed_auto", malformedAuto);
        savedPd.put("presetState", states);

        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        MilestoneData.PlayerData loadedPd = loaded.playerOrNull(PLAYER_A);
        assertNotNull(loadedPd);
        assertEquals(Map.of("good_pack", new PresetState(true, 2)), loadedPd.presetState);
        assertNotNull(loaded.loadReport());
        assertEquals(4, loaded.loadReport().elementSkipCount());
    }
    @Test
    void readsDoubleWrapped1215PayloadFromLegacyFileAndStillWritesTheCorrectForm(@TempDir Path dir)
            throws IOException {
        MilestoneData original = new MilestoneData();
        MilestoneData.PlayerData pd = original.player(PLAYER_A);
        TargetKey diamond = TargetKey.of("minecraft:diamond", 3);
        pd.targets.add(diamond);
        MilestoneRecord record = new MilestoneRecord(4567, 5L, 1795600000000L);
        pd.records.put(diamond, record);
        pd.carriedFromCreative.add("minecraft:emerald");
        pd.lastKnownName = "Alice";
        original.recordWorldFirstIfAbsent(diamond, PLAYER_A, "Alice", record);

        // #99: the buggy 1.21.5-1.21.11 codec hand-wrapped its payload in "data", and vanilla
        // wrapped the codec output again. On disk: {"data": {"data": payload}, "DataVersion": ...}.
        CompoundTag payload = (CompoundTag) MilestoneData.CODEC.encodeStart(NbtOps.INSTANCE, original).getOrThrow();
        CompoundTag inner = new CompoundTag();
        inner.put("data", payload.copy());
        CompoundTag diskRoot = new CompoundTag();
        diskRoot.put("data", inner.copy());
        diskRoot.putInt("DataVersion", 3955);
        Path file = dir.resolve(MilestoneData.LEGACY_FILE);
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(diskRoot, file);

        MilestoneData migrated = MilestoneData.readLegacy(file);
        assertNotNull(migrated);
        MilestoneData.PlayerData loaded = migrated.playerOrNull(PLAYER_A);
        assertNotNull(loaded);
        assertEquals(List.of(diamond), List.copyOf(loaded.targets));
        assertEquals(record, loaded.records.get(diamond));
        assertEquals(Set.of("minecraft:emerald"), loaded.carriedFromCreative);
        assertEquals("Alice", loaded.lastKnownName);
        assertNotNull(migrated.worldFirstOrNull(diamond));
        assertNotNull(migrated.loadReport());
        assertEquals(0, migrated.loadReport().elementSkipCount());

        // Vanilla strips only the outer wrapper before calling the codec; the inner recovery
        // path must work there too (this is the direct new-format data storage path).
        MilestoneData direct = MilestoneData.CODEC.parse(NbtOps.INSTANCE, inner.copy()).getOrThrow();
        assertEquals(List.of(diamond), List.copyOf(direct.playerOrNull(PLAYER_A).targets));

        // The fixed encoder emits the payload itself; vanilla adds the one wrapper on disk.
        CompoundTag encoded = (CompoundTag) MilestoneData.CODEC.encodeStart(NbtOps.INSTANCE, original).getOrThrow();
        assertFalse(encoded.contains("data"), encoded::toString);

        // CorruptionEvacuation uses the same unwrap helper before reading schemaVersion.
        CompoundTag unwrapped = MilestoneData.unwrapDoubleWrappedData(inner.copy());
        assertEquals(SchemaVersionLogic.CURRENT_SCHEMA_VERSION, unwrapped.getIntOr("schemaVersion", -1));
        CompoundTag correctPayload = payload.copy();
        assertSame(correctPayload, MilestoneData.unwrapDoubleWrappedData(correctPayload));
    }

    @Test
    void lenientOldPlayerFieldsKeepValidSiblingsAndRecordEverySkip() {
        CompoundTag player = new CompoundTag();
        ListTag targets = new ListTag();
        targets.add(StringTag.valueOf("minecraft:diamond"));
        targets.add(IntTag.valueOf(5)); // malformed list element, not a whole-field failure
        player.put("targets", targets);
        ListTag carried = new ListTag();
        carried.add(StringTag.valueOf("minecraft:emerald"));
        carried.add(IntTag.valueOf(7));
        player.put("carriedFromCreative", carried);
        player.putString("lastKnownName", "Alice");
        player.putString("firstJoinHintShown", "yes");
        player.putString("muteBroadcasts", "yes");
        player.putString("lastSeen", "yesterday");
        player.putString("progressNotified", "not-a-compound");
        player.putString("completedPresets", "not-a-list");
        player.putString("presetState", "not-a-compound");
        CompoundTag records = new CompoundTag();
        records.put("minecraft:coal", StringTag.valueOf("garbage"));
        player.put("records", records);

        CompoundTag players = new CompoundTag();
        players.put(PLAYER_A.toString(), player);
        CompoundTag worldFirsts = new CompoundTag();
        worldFirsts.put("minecraft:emerald", StringTag.valueOf("garbage"));
        CompoundTag root = new CompoundTag();
        root.put("players", players);
        root.put("worldFirsts", worldFirsts);
        root.putInt("schemaVersion", 1);

        // getOrThrow(): the whole decode succeeds; only the malformed elements are dropped.
        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, root).getOrThrow();
        MilestoneData.PlayerData pd = loaded.playerOrNull(PLAYER_A);
        assertNotNull(pd);
        assertEquals(List.of(TargetKey.of("minecraft:diamond")), List.copyOf(pd.targets));
        assertEquals(Set.of("minecraft:emerald"), pd.carriedFromCreative);
        assertEquals("Alice", pd.lastKnownName);
        assertFalse(pd.firstJoinHintShown);
        assertFalse(pd.muteBroadcasts);
        assertEquals(0L, pd.lastSeen);
        assertTrue(pd.progressNotified.isEmpty());
        assertTrue(pd.completedPresets.isEmpty());
        assertTrue(pd.presetState.isEmpty());
        assertTrue(pd.records.isEmpty());
        assertTrue(loaded.worldFirsts().isEmpty());

        // targets element, carried element, three scalar fields, three container fallbacks,
        // records item fallback and worldFirst item fallback = 10.
        assertNotNull(loaded.loadReport());
        assertEquals(10, loaded.loadReport().elementSkipCount());
        assertFalse(loaded.loadReport().totalFailure());
    }

    @Test
    void partialDecodeAttachesLoadReportAndEvacuationPreservesTheOriginal(@TempDir Path dir)
            throws IOException {
        MilestoneData original = new MilestoneData();
        MilestoneData.PlayerData pd = original.player(PLAYER_A);
        TargetKey diamond = TargetKey.of("minecraft:diamond");
        pd.targets.add(diamond);
        pd.lastKnownName = "Alice";

        CompoundTag payload = (CompoundTag) MilestoneData.CODEC.encodeStart(NbtOps.INSTANCE, original).getOrThrow();
        CompoundTag players = payload.getCompoundOrEmpty("players");
        // #130: a wrong-typed player value makes the players map decode partially; vanilla keeps
        // that partial result (resultOrPartial(...).orElse(null)), so the report must be attached.
        players.put("22222222-2222-2222-2222-222222222222", StringTag.valueOf("garbage"));
        payload.put("players", players);

        CompoundTag diskRoot = new CompoundTag();
        diskRoot.put("data", payload);
        diskRoot.putInt("DataVersion", 3955);
        Path file = dir.resolve("data/itemmilestonelog/milestones.dat");
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(diskRoot, file);

        CompoundTag readBack = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, readBack.getCompoundOrEmpty("data"))
                .resultOrPartial(error -> {
                })
                .orElse(null);
        assertNotNull(loaded, "vanilla adopts the partial result, so the game must not start empty");
        assertEquals(1, loaded.allPlayers().size());
        assertNotNull(loaded.playerOrNull(PLAYER_A));
        assertNotNull(loaded.loadReport());
        assertEquals(1, loaded.loadReport().elementSkipCount());
        assertFalse(loaded.loadReport().totalFailure());

        CorruptionEvacuationLogic.LoadOutcome outcome = loaded.loadReport().toOutcome();
        assertTrue(outcome.requiresEvacuation(), "a partial decode must trigger F-140-2");
        CorruptionEvacuationIO.Result result = CorruptionEvacuationIO.evacuate(
                file, dir.resolve("itemmilestonelog/corrupt"), outcome,
                Instant.parse("2026-11-26T12:00:00Z"), ZoneId.of("UTC"));
        assertTrue(Files.isRegularFile(file), "the original file must never be deleted or renamed");
        assertTrue(Files.isRegularFile(result.dataFile()));
        assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(result.dataFile()));
    }

    @Test
    void wrongTypedRecordsContainerKeepsValidSiblingsAndTriggersEvacuation() {
        CompoundTag player = new CompoundTag();
        ListTag targets = new ListTag();
        targets.add(StringTag.valueOf("minecraft:diamond"));
        player.put("targets", targets);
        ListTag carried = new ListTag();
        carried.add(StringTag.valueOf("minecraft:emerald"));
        player.put("carriedFromCreative", carried);
        player.putString("lastKnownName", "Alice");
        player.putBoolean("firstJoinHintShown", true);
        player.putBoolean("muteBroadcasts", true);
        player.putLong("lastSeen", 42L);
        // #147: corrupt the records CONTAINER, not an item value inside it. The per-item
        // lenient wrapper cannot help here; the field itself must be lenient as well.
        player.put("records", StringTag.valueOf("not-a-compound"));

        CompoundTag players = new CompoundTag();
        players.put(PLAYER_A.toString(), player);
        CompoundTag root = new CompoundTag();
        root.put("players", players);
        root.putInt("schemaVersion", 1);

        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, root).getOrThrow();
        MilestoneData.PlayerData pd = loaded.playerOrNull(PLAYER_A);
        assertNotNull(pd);
        assertEquals(List.of(TargetKey.of("minecraft:diamond")), List.copyOf(pd.targets));
        assertEquals(Set.of("minecraft:emerald"), pd.carriedFromCreative);
        assertEquals("Alice", pd.lastKnownName);
        assertTrue(pd.firstJoinHintShown);
        assertTrue(pd.muteBroadcasts);
        assertEquals(42L, pd.lastSeen);
        assertTrue(pd.records.isEmpty());

        assertNotNull(loaded.loadReport());
        assertEquals(1, loaded.loadReport().elementSkipCount());
        assertTrue(loaded.loadReport().toOutcome().requiresEvacuation());
    }

    @Test
    void progressAlertDestinationMissingFromLegacyTagLoadsFollowServerAndKeepsOtherFields() {
        MilestoneData original = new MilestoneData();
        MilestoneData.PlayerData pd = original.player(PLAYER_A);
        TargetKey diamond = TargetKey.of("minecraft:diamond", 3);
        MilestoneRecord record = new MilestoneRecord(1234, 5L, 1795600000000L);
        pd.targets.add(diamond);
        pd.records.put(diamond, record);
        pd.lastKnownName = "Alice";
        pd.completedPresets.add("vanilla_endgame");
        pd.presetState.put("vanilla_endgame", new PresetState(true, 2));
        original.recordWorldFirstIfAbsent(diamond, PLAYER_A, "Alice", record);

        CompoundTag saved = encodeRoot(original);
        CompoundTag players = saved.getCompoundOrEmpty("players");
        CompoundTag playerTag = players.getCompoundOrEmpty(PLAYER_A.toString());
        assertFalse(playerTag.contains("progressAlertDestination"), "the default is never written");
        playerTag.remove("progressAlertDestination");
        players.put(PLAYER_A.toString(), playerTag);
        saved.put("players", players);

        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        MilestoneData.PlayerData loadedPd = loaded.playerOrNull(PLAYER_A);
        assertNotNull(loadedPd);
        assertEquals("FOLLOW_SERVER", loadedPd.progressAlertDestination);
        assertEquals(pd.targets, loadedPd.targets);
        assertEquals(pd.records, loadedPd.records);
        assertEquals("Alice", loadedPd.lastKnownName);
        assertEquals(pd.completedPresets, loadedPd.completedPresets);
        assertEquals(pd.presetState, loadedPd.presetState);
        assertEquals(original.worldFirsts(), loaded.worldFirsts());
        assertEquals(original.schemaVersionForSave(), loaded.schemaVersionForSave());
    }

    @Test
    void progressAlertDestinationRoundTripsAndOnlyWritesNonDefaults() {
        MilestoneData original = new MilestoneData();
        MilestoneData.PlayerData pd = original.player(PLAYER_A);
        pd.progressAlertDestination = "BOTH";
        CompoundTag saved = encodeRoot(original);
        assertEquals("BOTH", saved.getCompoundOrEmpty("players")
                .getCompoundOrEmpty(PLAYER_A.toString()).getStringOr("progressAlertDestination", "?"));
        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        assertEquals("BOTH", loaded.playerOrNull(PLAYER_A).progressAlertDestination);

        pd.progressAlertDestination = "FOLLOW_SERVER";
        CompoundTag defaultSaved = encodeRoot(original);
        assertFalse(defaultSaved.getCompoundOrEmpty("players")
                .getCompoundOrEmpty(PLAYER_A.toString()).contains("progressAlertDestination"));
        assertEquals("FOLLOW_SERVER", MilestoneData.CODEC.parse(NbtOps.INSTANCE, defaultSaved)
                .getOrThrow().playerOrNull(PLAYER_A).progressAlertDestination);
    }

    @Test
    void progressAlertDestinationLoaderSkipsMalformedValuesAndKeepsSiblings() {
        MilestoneData original = new MilestoneData();
        MilestoneData.PlayerData pd = original.player(PLAYER_A);
        TargetKey diamond = TargetKey.of("minecraft:diamond", 3);
        MilestoneRecord record = new MilestoneRecord(4321, 7L, 1795600000000L);
        pd.targets.add(diamond);
        pd.records.put(diamond, record);
        pd.lastKnownName = "Alice";
        pd.presetState.put("vanilla_endgame", new PresetState(true, 4));

        CompoundTag saved = encodeRoot(original);
        CompoundTag players = saved.getCompoundOrEmpty("players");
        CompoundTag playerTag = players.getCompoundOrEmpty(PLAYER_A.toString());
        playerTag.put("progressAlertDestination", IntTag.valueOf(7));
        players.put(PLAYER_A.toString(), playerTag);
        saved.put("players", players);
        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        assertEquals("FOLLOW_SERVER", loaded.playerOrNull(PLAYER_A).progressAlertDestination);
        assertNotNull(loaded.loadReport());
        assertEquals(1, loaded.loadReport().elementSkipCount());
        assertFalse(loaded.loadReport().totalFailure());
        assertEquals(pd.targets, loaded.playerOrNull(PLAYER_A).targets);
        assertEquals(pd.records, loaded.playerOrNull(PLAYER_A).records);
        assertEquals("Alice", loaded.playerOrNull(PLAYER_A).lastKnownName);
        assertEquals(pd.presetState, loaded.playerOrNull(PLAYER_A).presetState);

        playerTag.putString("progressAlertDestination", "NOT_A_DESTINATION");
        players.put(PLAYER_A.toString(), playerTag);
        saved.put("players", players);
        MilestoneData invalidName = MilestoneData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        assertEquals("FOLLOW_SERVER", invalidName.playerOrNull(PLAYER_A).progressAlertDestination);
        assertEquals(1, invalidName.loadReport().elementSkipCount());
        assertEquals(pd.targets, invalidName.playerOrNull(PLAYER_A).targets);
    }

    @Test
    void removingProgressAlertDestinationFromSavedTagIsANonDestructiveDowngrade() {
        MilestoneData original = new MilestoneData();
        MilestoneData.PlayerData pd = original.player(PLAYER_A);
        TargetKey diamond = TargetKey.of("minecraft:diamond", 3);
        MilestoneRecord record = new MilestoneRecord(5555, 9L, 1795600000000L);
        pd.targets.add(diamond);
        pd.records.put(diamond, record);
        pd.progressNotified.put(diamond, 25);
        pd.completedPresets.add("vanilla_endgame");
        pd.presetState.put("vanilla_endgame", new PresetState(true, 2));
        pd.lastKnownName = "Alice";
        pd.lastSeen = 1795800000000L;
        pd.progressAlertDestination = "ACTION_BAR";
        original.recordWorldFirstIfAbsent(diamond, PLAYER_A, "Alice", record);

        CompoundTag saved = encodeRoot(original);
        MilestoneData before = MilestoneData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        CompoundTag downgraded = saved.copy();
        CompoundTag players = downgraded.getCompoundOrEmpty("players");
        CompoundTag playerTag = players.getCompoundOrEmpty(PLAYER_A.toString());
        assertTrue(playerTag.contains("progressAlertDestination"));
        playerTag.remove("progressAlertDestination");
        players.put(PLAYER_A.toString(), playerTag);
        downgraded.put("players", players);

        MilestoneData after = MilestoneData.CODEC.parse(NbtOps.INSTANCE, downgraded).getOrThrow();
        MilestoneData.PlayerData beforePd = before.playerOrNull(PLAYER_A);
        MilestoneData.PlayerData afterPd = after.playerOrNull(PLAYER_A);
        assertNotNull(beforePd);
        assertNotNull(afterPd);
        assertEquals("ACTION_BAR", beforePd.progressAlertDestination);
        assertEquals("FOLLOW_SERVER", afterPd.progressAlertDestination);
        assertEquals(beforePd.targets, afterPd.targets);
        assertEquals(beforePd.records, afterPd.records);
        assertEquals(beforePd.progressNotified, afterPd.progressNotified);
        assertEquals(beforePd.completedPresets, afterPd.completedPresets);
        assertEquals(beforePd.presetState, afterPd.presetState);
        assertEquals(beforePd.lastKnownName, afterPd.lastKnownName);
        assertEquals(beforePd.lastSeen, afterPd.lastSeen);
        assertEquals(before.worldFirsts(), after.worldFirsts());
        assertEquals(before.retainedSchemaVersion(), after.retainedSchemaVersion());
        assertNotNull(after.loadReport());
        assertEquals(0, after.loadReport().elementSkipCount());
    }

    @Test
    void targetMetaMissingFromLegacyTagLoadsEmptyAndKeepsOtherFields() {
        MilestoneData original = new MilestoneData();
        MilestoneData.PlayerData pd = original.player(PLAYER_A);
        TargetKey diamond = TargetKey.of("minecraft:diamond", 3);
        MilestoneRecord record = new MilestoneRecord(1234, 5L, 1795600000000L);
        pd.targets.add(diamond);
        pd.records.put(diamond, record);
        pd.lastKnownName = "Alice";
        pd.completedPresets.add("vanilla_endgame");
        pd.presetState.put("vanilla_endgame", new PresetState(true, 2));
        original.recordWorldFirstIfAbsent(diamond, PLAYER_A, "Alice", record);

        // A 1.4.0 file has no targetMeta key in its player compound.
        CompoundTag saved = encodeRoot(original);
        CompoundTag savedPlayers = saved.getCompoundOrEmpty("players");
        CompoundTag savedPd = savedPlayers.getCompoundOrEmpty(PLAYER_A.toString());
        savedPd.remove("targetMeta");
        savedPlayers.put(PLAYER_A.toString(), savedPd);
        saved.put("players", savedPlayers);

        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        MilestoneData.PlayerData loadedPd = loaded.playerOrNull(PLAYER_A);
        assertNotNull(loadedPd);
        assertTrue(loadedPd.targetMeta.isEmpty());
        assertEquals(pd.targets, loadedPd.targets);
        assertEquals(pd.records, loadedPd.records);
        assertEquals("Alice", loadedPd.lastKnownName);
        assertEquals(pd.completedPresets, loadedPd.completedPresets);
        assertEquals(pd.presetState, loadedPd.presetState);
        assertEquals(original.worldFirsts(), loaded.worldFirsts());
    }

    @Test
    void targetMetaRoundTripsWithEncodedKeysAndExactFieldNames() {
        MilestoneData original = new MilestoneData();
        MilestoneData.PlayerData pd = original.player(PLAYER_A);
        pd.targets.add(TargetKey.of("minecraft:diamond", 3));
        pd.targets.add(TargetKey.of("minecraft:emerald"));
        TargetMeta pinned = new TargetMeta(7, "star pick", true);
        TargetMeta plain = new TargetMeta(2, "", false);
        pd.targetMeta.put("minecraft:diamond#3", pinned);
        pd.targetMeta.put("minecraft:emerald", plain);

        CompoundTag saved = encodeRoot(original);
        CompoundTag metas = saved.getCompoundOrEmpty("players")
                .getCompoundOrEmpty(PLAYER_A.toString()).getCompoundOrEmpty("targetMeta");
        assertTrue(metas.contains("minecraft:diamond#3"));
        CompoundTag first = metas.getCompoundOrEmpty("minecraft:diamond#3");
        assertEquals(7, first.getIntOr("priority", -1));
        assertEquals("star pick", first.getStringOr("label", "?"));
        assertTrue(first.getBooleanOr("pinned", false));
        assertTrue(metas.contains("minecraft:emerald"));
        assertEquals(2, metas.getCompoundOrEmpty("minecraft:emerald").getIntOr("priority", -1));

        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        MilestoneData.PlayerData loadedPd = loaded.playerOrNull(PLAYER_A);
        assertNotNull(loadedPd);
        assertEquals(Map.of("minecraft:diamond#3", pinned, "minecraft:emerald", plain), loadedPd.targetMeta);
    }

    @Test
    void targetMetaLoaderSkipsMalformedElementsAndKeepsValidSiblings() {
        MilestoneData original = new MilestoneData();
        MilestoneData.PlayerData pd = original.player(PLAYER_A);
        TargetKey diamond = TargetKey.of("minecraft:diamond", 3);
        MilestoneRecord record = new MilestoneRecord(4321, 7L, 1795600000000L);
        pd.targets.add(diamond);
        pd.records.put(diamond, record);
        pd.lastKnownName = "Alice";
        pd.presetState.put("vanilla_endgame", new PresetState(true, 4));

        CompoundTag saved = encodeRoot(original);
        CompoundTag savedPd = saved.getCompoundOrEmpty("players").getCompoundOrEmpty(PLAYER_A.toString());

        CompoundTag goodA = new CompoundTag();
        goodA.putInt("priority", 4);
        goodA.putString("label", "valid");
        goodA.putBoolean("pinned", true);
        CompoundTag goodB = new CompoundTag();
        goodB.putInt("priority", 0);
        goodB.putString("label", "");
        goodB.putBoolean("pinned", false);
        CompoundTag outOfRange = new CompoundTag();
        outOfRange.putInt("priority", 12);
        outOfRange.putString("label", "bad");
        outOfRange.putBoolean("pinned", false);
        CompoundTag overlong = new CompoundTag();
        overlong.putInt("priority", 3);
        overlong.putString("label", "x".repeat(33));
        overlong.putBoolean("pinned", false);
        CompoundTag wrongType = new CompoundTag();
        wrongType.putString("priority", "5");
        wrongType.putString("label", "bad");
        wrongType.putBoolean("pinned", false);
        CompoundTag metas = new CompoundTag();
        metas.put("minecraft:diamond#3", goodA);
        metas.put("minecraft:emerald", goodB);
        metas.put("out_of_range", outOfRange);
        metas.put("overlong", overlong);
        metas.put("wrong_type", wrongType);
        metas.put("scalar", StringTag.valueOf("nope"));
        savedPd.put("targetMeta", metas);

        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        MilestoneData.PlayerData loadedPd = loaded.playerOrNull(PLAYER_A);
        assertNotNull(loadedPd);
        assertEquals(Map.of(
                "minecraft:diamond#3", new TargetMeta(4, "valid", true),
                "minecraft:emerald", new TargetMeta(0, "", false)), loadedPd.targetMeta);
        assertNotNull(loaded.loadReport());
        assertEquals(4, loaded.loadReport().elementSkipCount());
        assertFalse(loaded.loadReport().totalFailure());
        assertEquals(pd.targets, loadedPd.targets);
        assertEquals(pd.records, loadedPd.records);
        assertEquals("Alice", loadedPd.lastKnownName);
        assertEquals(pd.presetState, loadedPd.presetState);
    }

    @Test
    void removingTargetMetaFromSavedTagIsANonDestructiveDowngrade() {
        MilestoneData original = new MilestoneData();
        MilestoneData.PlayerData pd = original.player(PLAYER_A);
        TargetKey diamond = TargetKey.of("minecraft:diamond", 3);
        MilestoneRecord record = new MilestoneRecord(5555, 9L, 1795600000000L);
        pd.targets.add(diamond);
        pd.records.put(diamond, record);
        pd.progressNotified.put(diamond, 25);
        pd.completedPresets.add("vanilla_endgame");
        pd.presetState.put("vanilla_endgame", new PresetState(true, 2));
        pd.lastKnownName = "Alice";
        pd.lastSeen = 1795800000000L;
        pd.targetMeta.put("minecraft:diamond#3", new TargetMeta(9, "keep", true));
        original.recordWorldFirstIfAbsent(diamond, PLAYER_A, "Alice", record);

        CompoundTag saved = encodeRoot(original);
        MilestoneData before = MilestoneData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();

        // Simulate a 1.4.0 read: the unknown targetMeta key is dropped, nothing else changes.
        CompoundTag downgraded = saved.copy();
        CompoundTag players = downgraded.getCompoundOrEmpty("players");
        CompoundTag playerTag = players.getCompoundOrEmpty(PLAYER_A.toString());
        assertTrue(playerTag.contains("targetMeta"));
        playerTag.remove("targetMeta");
        players.put(PLAYER_A.toString(), playerTag);
        downgraded.put("players", players);

        MilestoneData after = MilestoneData.CODEC.parse(NbtOps.INSTANCE, downgraded).getOrThrow();
        MilestoneData.PlayerData beforePd = before.playerOrNull(PLAYER_A);
        MilestoneData.PlayerData afterPd = after.playerOrNull(PLAYER_A);
        assertNotNull(beforePd);
        assertNotNull(afterPd);
        assertEquals(1, beforePd.targetMeta.size());
        assertTrue(afterPd.targetMeta.isEmpty());
        assertEquals(beforePd.targets, afterPd.targets);
        assertEquals(beforePd.records, afterPd.records);
        assertEquals(beforePd.progressNotified, afterPd.progressNotified);
        assertEquals(beforePd.completedPresets, afterPd.completedPresets);
        assertEquals(beforePd.presetState, afterPd.presetState);
        assertEquals(beforePd.lastKnownName, afterPd.lastKnownName);
        assertEquals(beforePd.lastSeen, afterPd.lastSeen);
        assertEquals(before.worldFirsts(), after.worldFirsts());
        assertEquals(before.retainedSchemaVersion(), after.retainedSchemaVersion());
        assertNotNull(after.loadReport());
        assertEquals(0, after.loadReport().elementSkipCount());
    }

    @Test
    void firstsHolderHistoryMissingFromLegacyTagLoadsEmptyAndKeepsOtherFields() {
        MilestoneData original = new MilestoneData();
        MilestoneData.PlayerData pd = original.player(PLAYER_A);
        TargetKey diamond = TargetKey.of("minecraft:diamond", 3);
        MilestoneRecord record = new MilestoneRecord(1234, 5L, 1795600000000L);
        pd.targets.add(diamond);
        pd.records.put(diamond, record);
        pd.lastKnownName = "Alice";
        assertTrue(original.recordWorldFirstIfAbsent(diamond, PLAYER_A, "Alice", record));

        // A 1.4.0 file has no root firstsHolderHistory compound.
        CompoundTag saved = encodeRoot(original);
        int savedSchemaVersion = saved.getIntOr("schemaVersion", -1);
        saved.remove("firstsHolderHistory");

        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        assertTrue(loaded.firstsHolderHistory().isEmpty());
        assertTrue(loaded.holderHistory(diamond.toEncodedString()).isEmpty());
        MilestoneData.PlayerData loadedPd = loaded.playerOrNull(PLAYER_A);
        assertNotNull(loadedPd);
        assertEquals(pd.targets, loadedPd.targets);
        assertEquals(pd.records, loadedPd.records);
        assertEquals("Alice", loadedPd.lastKnownName);
        assertEquals(original.worldFirsts(), loaded.worldFirsts());
        assertEquals(savedSchemaVersion, loaded.retainedSchemaVersion());
        assertEquals(savedSchemaVersion, loaded.schemaVersionForSave());
    }

    @Test
    void firstsHolderHistoryRoundTripsWithEncodedKeysAndExactFieldNames() {
        UUID playerB = UUID.fromString("22222222-2222-2222-2222-222222222222");
        MilestoneData original = new MilestoneData();
        TargetKey diamond = TargetKey.of("minecraft:diamond", 3);
        TargetKey emerald = TargetKey.of("minecraft:emerald");
        TargetKey rotating = TargetKey.of("minecraft:stone");
        assertTrue(original.recordWorldFirstIfAbsent(diamond, PLAYER_A, "Alice",
                new MilestoneRecord(111, 5L, 1795600000000L)));
        assertTrue(original.removeWorldFirst(diamond));
        assertTrue(original.recordWorldFirstIfAbsent(diamond, playerB, "Bob",
                new MilestoneRecord(222, 6L, 1795600001000L)));
        assertTrue(original.removeWorldFirst(diamond));
        assertTrue(original.recordWorldFirstIfAbsent(emerald, PLAYER_A, "Alice",
                new MilestoneRecord(333, 7L, 1795600002000L)));
        assertTrue(original.removeWorldFirst(emerald));

        // HISTORY_LIMIT rotations: the two oldest entries must be dropped oldest-first.
        List<HolderEntry> rotated = new ArrayList<>();
        for (int i = 0; i < FirstsHolderHistory.HISTORY_LIMIT + 2; i++) {
            assertTrue(original.recordWorldFirstIfAbsent(rotating, playerB, "Holder" + i,
                    new MilestoneRecord(1000 + i, 1L, 1800000000000L + i)));
            assertTrue(original.removeWorldFirst(rotating));
            if (i >= 2) {
                rotated.add(new HolderEntry(playerB.toString(), "Holder" + i,
                        1000 + i, 1800000000000L + i));
            }
        }

        String encodedKey = diamond.toEncodedString();
        List<HolderEntry> diamondHistory = List.of(
                new HolderEntry(PLAYER_A.toString(), "Alice", 111, 1795600000000L),
                new HolderEntry(playerB.toString(), "Bob", 222, 1795600001000L));
        Map<String, List<HolderEntry>> expectedHistory = Map.of(
                encodedKey, diamondHistory,
                emerald.toEncodedString(),
                List.of(new HolderEntry(PLAYER_A.toString(), "Alice", 333, 1795600002000L)),
                rotating.toEncodedString(), List.copyOf(rotated));
        assertEquals(diamondHistory, original.holderHistory(encodedKey));
        assertEquals(expectedHistory, original.firstsHolderHistory());

        CompoundTag saved = encodeRoot(original);
        CompoundTag history = saved.getCompoundOrEmpty("firstsHolderHistory");
        assertTrue(history.contains(encodedKey));
        ListTag encodedList = history.getListOrEmpty(encodedKey);
        assertEquals(2, encodedList.size());
        CompoundTag encodedFirst = (CompoundTag) encodedList.get(0);
        assertEquals(PLAYER_A.toString(), encodedFirst.getStringOr("uuid", "?"));
        assertEquals("Alice", encodedFirst.getStringOr("name", "?"));
        assertEquals(111, encodedFirst.getIntOr("playTicks", -1));
        assertEquals(1795600000000L, encodedFirst.getLongOr("epochMillis", -1L));
        CompoundTag encodedSecond = (CompoundTag) encodedList.get(1);
        assertEquals(playerB.toString(), encodedSecond.getStringOr("uuid", "?"));
        assertEquals("Bob", encodedSecond.getStringOr("name", "?"));
        assertEquals(222, encodedSecond.getIntOr("playTicks", -1));
        assertEquals(1795600001000L, encodedSecond.getLongOr("epochMillis", -1L));
        assertEquals(FirstsHolderHistory.HISTORY_LIMIT,
                history.getListOrEmpty(rotating.toEncodedString()).size());

        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        assertEquals(diamondHistory, loaded.holderHistory(encodedKey));
        assertEquals(expectedHistory, loaded.firstsHolderHistory());
    }

    @Test
    void firstsHolderHistoryLoaderSkipsMalformedElementsAndKeepsValidSiblings() {
        UUID playerB = UUID.fromString("22222222-2222-2222-2222-222222222222");
        MilestoneData original = new MilestoneData();
        MilestoneData.PlayerData pd = original.player(PLAYER_A);
        TargetKey diamond = TargetKey.of("minecraft:diamond", 3);
        MilestoneRecord record = new MilestoneRecord(4321, 7L, 1795600000000L);
        pd.targets.add(diamond);
        pd.records.put(diamond, record);
        pd.lastKnownName = "Alice";
        assertTrue(original.recordWorldFirstIfAbsent(diamond, PLAYER_A, "Alice", record));

        CompoundTag saved = encodeRoot(original);
        int savedSchemaVersion = saved.getIntOr("schemaVersion", -1);
        HolderEntry validA = new HolderEntry(PLAYER_A.toString(), "Alice", 11, 1795600000000L);
        HolderEntry validB = new HolderEntry(playerB.toString(), "Bob", 22, 1795600001000L);

        CompoundTag goodA = new CompoundTag();
        goodA.putString("uuid", validA.uuid());
        goodA.putString("name", validA.name());
        goodA.putInt("playTicks", validA.playTicks());
        goodA.putLong("epochMillis", validA.epochMillis());
        CompoundTag goodB = new CompoundTag();
        goodB.putString("uuid", validB.uuid());
        goodB.putString("name", validB.name());
        goodB.putInt("playTicks", validB.playTicks());
        goodB.putLong("epochMillis", validB.epochMillis());
        CompoundTag badUuid = goodB.copy();
        badUuid.putString("uuid", "not-a-uuid");
        CompoundTag negativeTicks = goodB.copy();
        negativeTicks.putInt("playTicks", -1);

        ListTag diamondList = new ListTag();
        diamondList.add(goodA);
        diamondList.add(badUuid);
        diamondList.add(negativeTicks);
        diamondList.add(StringTag.valueOf("not a compound"));
        ListTag emeraldList = new ListTag();
        emeraldList.add(goodB);
        ListTag badKeyList = new ListTag();
        badKeyList.add(goodB.copy());
        CompoundTag history = new CompoundTag();
        history.put(diamond.toEncodedString(), diamondList);
        history.put("minecraft:emerald", emeraldList);
        history.put("not a valid key", badKeyList);
        saved.put("firstsHolderHistory", history);

        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        assertEquals(List.of(validA), loaded.holderHistory(diamond.toEncodedString()));
        assertEquals(Map.of(
                diamond.toEncodedString(), List.of(validA),
                "minecraft:emerald", List.of(validB)), loaded.firstsHolderHistory());
        assertNotNull(loaded.loadReport());
        // bad UUID + negative playTicks + non-compound element + bad encoded key
        assertEquals(4, loaded.loadReport().elementSkipCount());
        assertFalse(loaded.loadReport().totalFailure());
        MilestoneData.PlayerData loadedPd = loaded.playerOrNull(PLAYER_A);
        assertNotNull(loadedPd);
        assertEquals(pd.targets, loadedPd.targets);
        assertEquals(pd.records, loadedPd.records);
        assertEquals("Alice", loadedPd.lastKnownName);
        assertEquals(original.worldFirsts(), loaded.worldFirsts());
        assertEquals(savedSchemaVersion, loaded.retainedSchemaVersion());
    }

    @Test
    void removingFirstsHolderHistoryFromSavedTagIsANonDestructiveDowngrade() {
        UUID playerB = UUID.fromString("22222222-2222-2222-2222-222222222222");
        MilestoneData original = new MilestoneData();
        MilestoneData.PlayerData pd = original.player(PLAYER_A);
        TargetKey diamond = TargetKey.of("minecraft:diamond", 3);
        MilestoneRecord record = new MilestoneRecord(5555, 9L, 1795600000000L);
        pd.targets.add(diamond);
        pd.records.put(diamond, record);
        pd.progressNotified.put(diamond, 25);
        pd.completedPresets.add("vanilla_endgame");
        pd.presetState.put("vanilla_endgame", new PresetState(true, 2));
        pd.lastKnownName = "Alice";
        pd.lastSeen = 1795800000000L;
        assertTrue(original.recordWorldFirstIfAbsent(diamond, PLAYER_A, "Alice", record));
        assertTrue(original.removeWorldFirst(diamond));
        assertTrue(original.recordWorldFirstIfAbsent(diamond, playerB, "Bob",
                new MilestoneRecord(6666, 10L, 1795600001000L)));
        assertTrue(original.removeWorldFirst(diamond));
        assertTrue(original.recordWorldFirstIfAbsent(diamond, PLAYER_A, "Alice",
                new MilestoneRecord(7777, 11L, 1795600002000L)));

        CompoundTag saved = encodeRoot(original);
        MilestoneData before = MilestoneData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();

        // Simulate a 1.4.0 read: only the unknown root key is dropped.
        CompoundTag downgraded = saved.copy();
        assertTrue(downgraded.contains("firstsHolderHistory"));
        downgraded.remove("firstsHolderHistory");

        MilestoneData after = MilestoneData.CODEC.parse(NbtOps.INSTANCE, downgraded).getOrThrow();
        String encodedKey = diamond.toEncodedString();
        assertFalse(before.firstsHolderHistory().isEmpty());
        assertEquals(2, before.holderHistory(encodedKey).size());
        assertTrue(after.firstsHolderHistory().isEmpty());
        assertTrue(after.holderHistory(encodedKey).isEmpty());
        MilestoneData.PlayerData beforePd = before.playerOrNull(PLAYER_A);
        MilestoneData.PlayerData afterPd = after.playerOrNull(PLAYER_A);
        assertNotNull(beforePd);
        assertNotNull(afterPd);
        assertEquals(beforePd.targets, afterPd.targets);
        assertEquals(beforePd.records, afterPd.records);
        assertEquals(beforePd.progressNotified, afterPd.progressNotified);
        assertEquals(beforePd.completedPresets, afterPd.completedPresets);
        assertEquals(beforePd.presetState, afterPd.presetState);
        assertEquals(beforePd.lastKnownName, afterPd.lastKnownName);
        assertEquals(beforePd.lastSeen, afterPd.lastSeen);
        assertEquals(before.worldFirsts(), after.worldFirsts());
        assertEquals(before.retainedSchemaVersion(), after.retainedSchemaVersion());
        assertNotNull(before.loadReport());
        assertNotNull(after.loadReport());
        assertEquals(0, before.loadReport().elementSkipCount());
        assertEquals(0, after.loadReport().elementSkipCount());
    }


    @Test
    void soundFieldsMissingFromLegacyTagLoadAsFollowServerAndUnmuted() {
        MilestoneData original = new MilestoneData();
        MilestoneData.PlayerData pd = original.player(PLAYER_A);
        TargetKey diamond = TargetKey.of("minecraft:diamond", 3);
        pd.targets.add(diamond);
        pd.lastKnownName = "Alice";
        CompoundTag saved = encodeRoot(original);
        CompoundTag playerTag = saved.getCompoundOrEmpty("players").getCompoundOrEmpty(PLAYER_A.toString());
        assertFalse(playerTag.contains("soundVolume"), "the -1 default is never written");
        assertFalse(playerTag.contains("muteNotificationSounds"), "the false default is never written");
        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        MilestoneData.PlayerData loadedPd = loaded.playerOrNull(PLAYER_A);
        assertNotNull(loadedPd);
        assertEquals(SoundVolumeLogic.FOLLOW_SERVER, loadedPd.soundVolume);
        assertFalse(loadedPd.muteNotificationSounds);
        assertEquals(pd.targets, loadedPd.targets);
        assertEquals("Alice", loadedPd.lastKnownName);
        assertEquals(original.schemaVersionForSave(), loaded.schemaVersionForSave());
    }

    @Test
    void soundFieldsRoundTripAndOnlyWriteNonDefaults() {
        MilestoneData original = new MilestoneData();
        MilestoneData.PlayerData pd = original.player(PLAYER_A);
        pd.soundVolume = 45;
        pd.muteNotificationSounds = true;
        CompoundTag saved = encodeRoot(original);
        CompoundTag playerTag = saved.getCompoundOrEmpty("players").getCompoundOrEmpty(PLAYER_A.toString());
        assertEquals(45, playerTag.getIntOr("soundVolume", -99));
        assertTrue(playerTag.getBooleanOr("muteNotificationSounds", false));
        MilestoneData.PlayerData loadedPd = MilestoneData.CODEC.parse(NbtOps.INSTANCE, saved)
                .getOrThrow().playerOrNull(PLAYER_A);
        assertNotNull(loadedPd);
        assertEquals(45, loadedPd.soundVolume);
        assertTrue(loadedPd.muteNotificationSounds);

        pd.soundVolume = SoundVolumeLogic.FOLLOW_SERVER;
        pd.muteNotificationSounds = false;
        CompoundTag defaults = encodeRoot(original);
        CompoundTag defaultTag = defaults.getCompoundOrEmpty("players").getCompoundOrEmpty(PLAYER_A.toString());
        assertFalse(defaultTag.contains("soundVolume"));
        assertFalse(defaultTag.contains("muteNotificationSounds"));
        MilestoneData.PlayerData after = MilestoneData.CODEC.parse(NbtOps.INSTANCE, defaults)
                .getOrThrow().playerOrNull(PLAYER_A);
        assertNotNull(after);
        assertEquals(SoundVolumeLogic.FOLLOW_SERVER, after.soundVolume);
        assertFalse(after.muteNotificationSounds);
    }

    @Test
    void soundFieldsLoaderSkipsMalformedValuesAndKeepsSiblings() {
        MilestoneData original = new MilestoneData();
        MilestoneData.PlayerData pd = original.player(PLAYER_A);
        TargetKey diamond = TargetKey.of("minecraft:diamond", 3);
        pd.targets.add(diamond);
        pd.lastKnownName = "Alice";
        CompoundTag saved = encodeRoot(original);
        CompoundTag players = saved.getCompoundOrEmpty("players");
        CompoundTag playerTag = players.getCompoundOrEmpty(PLAYER_A.toString());
        playerTag.put("soundVolume", StringTag.valueOf("garbage"));
        playerTag.put("muteNotificationSounds", StringTag.valueOf("garbage"));
        players.put(PLAYER_A.toString(), playerTag);
        saved.put("players", players);
        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        MilestoneData.PlayerData loadedPd = loaded.playerOrNull(PLAYER_A);
        assertNotNull(loadedPd);
        assertEquals(SoundVolumeLogic.FOLLOW_SERVER, loadedPd.soundVolume);
        assertFalse(loadedPd.muteNotificationSounds);
        assertEquals(pd.targets, loadedPd.targets);
        assertEquals("Alice", loadedPd.lastKnownName);
        assertNotNull(loaded.loadReport());
        assertTrue(loaded.loadReport().elementSkipCount() >= 2);

        playerTag.putInt("soundVolume", 500);
        players.put(PLAYER_A.toString(), playerTag);
        saved.put("players", players);
        MilestoneData outOfRange = MilestoneData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        assertEquals(SoundVolumeLogic.FOLLOW_SERVER, outOfRange.playerOrNull(PLAYER_A).soundVolume);
        assertEquals(pd.targets, outOfRange.playerOrNull(PLAYER_A).targets);
    }

    @Test
    void replaceWorldFirstsAppendsHistoryForReplacedAndRemovedKeys() {
        UUID playerB = UUID.fromString("22222222-2222-2222-2222-222222222222");
        MilestoneData data = new MilestoneData();
        TargetKey diamond = TargetKey.of("minecraft:diamond");
        TargetKey emerald = TargetKey.of("minecraft:emerald");
        assertTrue(data.recordWorldFirstIfAbsent(diamond, PLAYER_A, "Alice",
                new MilestoneRecord(100, 1L, 1000L)));
        assertTrue(data.recordWorldFirstIfAbsent(emerald, PLAYER_A, "Alice",
                new MilestoneRecord(200, 1L, 1000L)));

        WorldFirstRecord bob = new WorldFirstRecord("minecraft:diamond", 1, playerB, "Bob", 50, 1L, 500L);
        Map<TargetKey, WorldFirstRecord> updated = Map.of(diamond, bob);
        data.replaceWorldFirsts(updated);

        assertEquals(Map.of(diamond, bob), data.worldFirsts());
        assertEquals(List.of(new HolderEntry(PLAYER_A.toString(), "Alice", 100, 1000L)),
                data.holderHistory(diamond.toEncodedString()));
        assertEquals(List.of(new HolderEntry(PLAYER_A.toString(), "Alice", 200, 1000L)),
                data.holderHistory(emerald.toEncodedString()));

        // A no-change merge must not append the unchanged holder again.
        data.replaceWorldFirsts(updated);
        assertEquals(1, data.holderHistory(diamond.toEncodedString()).size());
        assertEquals(1, data.holderHistory(emerald.toEncodedString()).size());
    }

    @Test
    void isEmptyIsFalseWhenOnlyHolderHistoryRemains() {
        MilestoneData data = new MilestoneData();
        assertTrue(data.isEmpty());
        TargetKey diamond = TargetKey.of("minecraft:diamond");
        assertTrue(data.recordWorldFirstIfAbsent(diamond, PLAYER_A, "Alice",
                new MilestoneRecord(100, 1L, 1000L)));
        assertEquals(1, data.clearWorldFirsts());
        assertTrue(data.worldFirsts().isEmpty());
        assertTrue(data.allPlayers().isEmpty());
        assertFalse(data.isEmpty());
    }

    @Test
    void firstsHolderHistoryLoaderKeepsValidSiblingsWhenOneValueIsNotAList() {
        UUID playerB = UUID.fromString("22222222-2222-2222-2222-222222222222");
        HolderEntry validA = new HolderEntry(PLAYER_A.toString(), "Alice", 111, 1795600000000L);
        HolderEntry validB = new HolderEntry(playerB.toString(), "Bob", 222, 1795600001000L);
        CompoundTag goodA = new CompoundTag();
        goodA.putString("uuid", validA.uuid());
        goodA.putString("name", validA.name());
        goodA.putInt("playTicks", validA.playTicks());
        goodA.putLong("epochMillis", validA.epochMillis());
        CompoundTag goodB = new CompoundTag();
        goodB.putString("uuid", validB.uuid());
        goodB.putString("name", validB.name());
        goodB.putInt("playTicks", validB.playTicks());
        goodB.putLong("epochMillis", validB.epochMillis());
        ListTag listA = new ListTag();
        listA.add(goodA);
        ListTag listB = new ListTag();
        listB.add(goodB);

        CompoundTag saved = encodeRoot(new MilestoneData());
        CompoundTag history = new CompoundTag();
        history.put("minecraft:diamond", listA);
        history.put("minecraft:emerald", listB);
        history.put("minecraft:stone", StringTag.valueOf("not a list"));
        saved.put("firstsHolderHistory", history);

        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        assertEquals(List.of(validA), loaded.holderHistory("minecraft:diamond"));
        assertEquals(List.of(validB), loaded.holderHistory("minecraft:emerald"));
        assertEquals(Map.of(
                "minecraft:diamond", List.of(validA),
                "minecraft:emerald", List.of(validB)), loaded.firstsHolderHistory());
        assertNotNull(loaded.loadReport());
        assertEquals(1, loaded.loadReport().elementSkipCount());
        assertFalse(loaded.loadReport().totalFailure());
    }

    @Test
    void firstsHolderHistoryLoaderKeepsNewestTwentyEntries() {
        MilestoneData data = new MilestoneData();
        CompoundTag saved = encodeRoot(data);
        ListTag list = new ListTag();
        for (int i = 0; i < FirstsHolderHistory.HISTORY_LIMIT + 2; i++) {
            CompoundTag holder = new CompoundTag();
            holder.putString("uuid", PLAYER_A.toString());
            holder.putString("name", "Holder" + i);
            holder.putInt("playTicks", 1000 + i);
            holder.putLong("epochMillis", 1800000000000L + i);
            list.add(holder);
        }
        String encodedKey = TargetKey.of("minecraft:diamond").toEncodedString();
        CompoundTag history = new CompoundTag();
        history.put(encodedKey, list);
        saved.put("firstsHolderHistory", history);

        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        List<HolderEntry> expected = new ArrayList<>();
        for (int i = 2; i < FirstsHolderHistory.HISTORY_LIMIT + 2; i++) {
            expected.add(new HolderEntry(PLAYER_A.toString(), "Holder" + i,
                    1000 + i, 1800000000000L + i));
        }
        assertEquals(FirstsHolderHistory.HISTORY_LIMIT, loaded.holderHistory(encodedKey).size());
        assertEquals(expected, loaded.holderHistory(encodedKey));
        assertNotNull(loaded.loadReport());
        assertEquals(0, loaded.loadReport().elementSkipCount());
    }

    @Test
    void targetMetaLoaderSkipsInvalidEncodedKeysAndKeepsValidOnes() {
        MilestoneData data = new MilestoneData();
        data.player(PLAYER_A);
        CompoundTag saved = encodeRoot(data);
        CompoundTag valid = new CompoundTag();
        valid.putInt("priority", 5);
        valid.putString("label", "valid");
        valid.putBoolean("pinned", true);
        CompoundTag metas = new CompoundTag();
        metas.put("not a valid key", valid.copy());
        metas.put("minecraft:emerald", valid);
        CompoundTag savedPd = saved.getCompoundOrEmpty("players")
                .getCompoundOrEmpty(PLAYER_A.toString());
        savedPd.put("targetMeta", metas);

        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        MilestoneData.PlayerData loadedPd = loaded.playerOrNull(PLAYER_A);
        assertNotNull(loadedPd);
        assertEquals(Map.of("minecraft:emerald", new TargetMeta(5, "valid", true)), loadedPd.targetMeta);
        assertNotNull(loaded.loadReport());
        assertEquals(1, loaded.loadReport().elementSkipCount());
        assertFalse(loaded.loadReport().totalFailure());
    }

}
