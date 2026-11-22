package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
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

        MilestoneData.PlayerData pdA = original.player(playerA);
        pdA.targets.add("minecraft:diamond");
        pdA.targets.add("minecraft:netherite_ingot");
        MilestoneRecord recA = new MilestoneRecord(12340, 11L, 1795600000000L);
        pdA.records.put("minecraft:diamond", recA);
        pdA.carriedFromCreative.add("minecraft:netherite_ingot");
        pdA.carriedFromCreative.add("minecraft:stone");
        pdA.lastKnownName = "Alice";
        pdA.firstJoinHintShown = true;
        pdA.muteBroadcasts = true;

        MilestoneData.PlayerData pdB = original.player(playerB);
        pdB.targets.add("allthetweaks:atm_star");
        MilestoneRecord recB = new MilestoneRecord(500000, 42L, 1795700000000L);
        pdB.records.put("allthetweaks:atm_star", recB);

        assertTrue(original.recordWorldFirstIfAbsent("minecraft:diamond", playerA, "Alice", recA));
        assertFalse(original.recordWorldFirstIfAbsent("minecraft:diamond", playerB, "Bob", recB));

        Tag saved = MilestoneData.CODEC.encodeStart(NbtOps.INSTANCE, original).getOrThrow();
        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();

        MilestoneData.PlayerData loadedA = loaded.playerOrNull(playerA);
        assertNotNull(loadedA);
        assertEquals(List.of("minecraft:diamond", "minecraft:netherite_ingot"), List.copyOf(loadedA.targets));
        assertEquals(recA, loadedA.records.get("minecraft:diamond"));
        assertNull(loadedA.records.get("minecraft:netherite_ingot"));
        assertEquals(List.of("minecraft:netherite_ingot", "minecraft:stone"), List.copyOf(loadedA.carriedFromCreative));
        assertEquals("Alice", loadedA.lastKnownName);
        assertTrue(loadedA.firstJoinHintShown);
        assertTrue(loadedA.muteBroadcasts);

        MilestoneData.PlayerData loadedB = loaded.playerOrNull(playerB);
        assertNotNull(loadedB);
        assertEquals(List.of("allthetweaks:atm_star"), List.copyOf(loadedB.targets));
        assertEquals(recB, loadedB.records.get("allthetweaks:atm_star"));
        assertTrue(loadedB.carriedFromCreative.isEmpty());
        assertEquals("", loadedB.lastKnownName);
        assertFalse(loadedB.firstJoinHintShown);
        assertFalse(loadedB.muteBroadcasts);

        WorldFirstRecord wf = loaded.worldFirstOrNull("minecraft:diamond");
        assertNotNull(wf);
        assertEquals(playerA, wf.playerUuid());
        assertEquals("Alice", wf.playerName());
        assertEquals(12340, wf.playTicks());
        assertEquals(11L, wf.day());
        assertEquals(1795600000000L, wf.epochMillis());
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
        assertEquals(List.of("minecraft:diamond", "allthetweaks:atm_star"), List.copyOf(pd.targets));
        assertEquals(Map.of("minecraft:diamond", MilestoneRecord.legacy(12340)), pd.records);
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
        assertEquals(new MilestoneRecord(12340, 11L, 1795600000000L), pd.records.get("minecraft:diamond"));
        WorldFirstRecord wf = loaded.worldFirstOrNull("minecraft:diamond");
        assertNotNull(wf);
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
        Path file = dir.resolve("itemmilestonelog_data.dat");
        Files.writeString(file, "not an NBT file");
        assertNull(MilestoneData.readLegacy(file));
    }

    @Test
    void ignoresMalformedUuidEntriesOnLoad() {
        CompoundTag root = new CompoundTag();
        CompoundTag players = new CompoundTag();
        players.put("not-a-valid-uuid", new CompoundTag());
        root.put("players", players);

        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, root).getOrThrow();
        assertNull(loaded.playerOrNull(UUID.randomUUID()));
    }

    @Test
    void emptyOptionalFieldsAreNotWritten() {
        MilestoneData data = new MilestoneData();
        data.player(PLAYER_A).targets.add("minecraft:diamond");

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
