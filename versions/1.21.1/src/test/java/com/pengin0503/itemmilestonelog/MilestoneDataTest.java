package com.pengin0503.itemmilestonelog;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MilestoneDataTest {
    @Test
    void savesAndLoadsTargetsAndRecordsRoundTrip() {
        MilestoneData original = new MilestoneData();
        UUID playerA = UUID.fromString("11111111-1111-1111-1111-111111111111");
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

        CompoundTag saved = original.save(new CompoundTag(), null);
        MilestoneData loaded = MilestoneData.load(saved, null);

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
        // Only written when there is something to remember.
        assertFalse(saved.getCompound("players").getCompound(playerB.toString()).contains("carriedFromCreative"));
    }

    @Test
    void ignoresMalformedUuidEntriesOnLoad() {
        CompoundTag root = new CompoundTag();
        CompoundTag players = new CompoundTag();
        players.put("not-a-valid-uuid", new CompoundTag());
        root.put("players", players);

        MilestoneData loaded = MilestoneData.load(root, null);
        assertNull(loaded.playerOrNull(UUID.randomUUID()));
    }
}
