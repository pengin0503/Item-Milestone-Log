package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.api.WorldFirstRecord;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

        MilestoneData.PlayerData loadedB = loaded.playerOrNull(playerB);
        assertNotNull(loadedB);
        assertEquals(List.of(atmStar), List.copyOf(loadedB.targets));
        assertEquals(recB, loadedB.records.get(atmStar));
        assertTrue(loadedB.carriedFromCreative.isEmpty());
        assertEquals("", loadedB.lastKnownName);
        assertFalse(loadedB.firstJoinHintShown);
        assertFalse(loadedB.muteBroadcasts);

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
        assertFalse(saved.contains("worldFirsts"), saved::toString);
    }
}
