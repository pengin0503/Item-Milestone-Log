package com.pengin0503.itemmilestonelog;

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
    void savesAndLoadsTargetsAndRecordsRoundTrip() {
        MilestoneData original = new MilestoneData();
        UUID playerA = PLAYER_A;
        UUID playerB = UUID.fromString("22222222-2222-2222-2222-222222222222");

        MilestoneData.PlayerData pdA = original.player(playerA);
        pdA.targets.add("minecraft:diamond");
        pdA.targets.add("minecraft:netherite_ingot");
        pdA.records.put("minecraft:diamond", 12340);
        pdA.carriedFromCreative.add("minecraft:netherite_ingot");
        pdA.carriedFromCreative.add("minecraft:stone");

        MilestoneData.PlayerData pdB = original.player(playerB);
        pdB.targets.add("allthetweaks:atm_star");
        pdB.records.put("allthetweaks:atm_star", 500000);

        Tag saved = MilestoneData.CODEC.encodeStart(NbtOps.INSTANCE, original).getOrThrow();
        MilestoneData loaded = MilestoneData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();

        MilestoneData.PlayerData loadedA = loaded.playerOrNull(playerA);
        assertNotNull(loadedA);
        assertEquals(List.of("minecraft:diamond", "minecraft:netherite_ingot"), List.copyOf(loadedA.targets));
        assertEquals(12340, loadedA.records.get("minecraft:diamond"));
        assertNull(loadedA.records.get("minecraft:netherite_ingot"));
        assertEquals(List.of("minecraft:netherite_ingot", "minecraft:stone"), List.copyOf(loadedA.carriedFromCreative));

        MilestoneData.PlayerData loadedB = loaded.playerOrNull(playerB);
        assertNotNull(loadedB);
        assertEquals(List.of("allthetweaks:atm_star"), List.copyOf(loadedB.targets));
        assertEquals(500000, loadedB.records.get("allthetweaks:atm_star"));
        assertTrue(loadedB.carriedFromCreative.isEmpty());
    }

    /** The 1.21.1 version's file: what its SavedData.save writes, wrapped in "data" and compressed. */
    private static CompoundTag legacyFileContents() {
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

    @Test
    void readsTheFileOfThe1211Version(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(MilestoneData.LEGACY_FILE);
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(legacyFileContents(), file);

        MilestoneData loaded = MilestoneData.readLegacy(file);

        assertNotNull(loaded);
        MilestoneData.PlayerData pd = loaded.playerOrNull(PLAYER_A);
        assertNotNull(pd);
        assertEquals(List.of("minecraft:diamond", "allthetweaks:atm_star"), List.copyOf(pd.targets));
        assertEquals(Map.of("minecraft:diamond", 12340), pd.records);
        assertTrue(pd.carriedFromCreative.isEmpty());
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
    void emptyCarriedFromCreativeIsNotWritten() {
        MilestoneData data = new MilestoneData();
        data.player(PLAYER_A).targets.add("minecraft:diamond");

        CompoundTag saved = (CompoundTag) MilestoneData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
        CompoundTag player = saved.getCompoundOrEmpty("players").getCompoundOrEmpty(PLAYER_A.toString());
        assertTrue(player.contains("targets"));
        assertFalse(player.contains("carriedFromCreative"), player::toString);
    }
}
