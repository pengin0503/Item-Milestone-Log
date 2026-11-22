package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.WorldFirstRecord;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
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
    void savesAndLoadsTargetsRecordsWorldFirstsAndFlagsRoundTrip() {
        MilestoneData original = new MilestoneData();
        UUID playerA = UUID.fromString("11111111-1111-1111-1111-111111111111");
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

        CompoundTag saved = original.save(new CompoundTag(), null);
        MilestoneData loaded = MilestoneData.load(saved, null);

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
        assertFalse(saved.getCompound("players").getCompound(playerB.toString()).contains("carriedFromCreative"));

        WorldFirstRecord wf = loaded.worldFirstOrNull("minecraft:diamond");
        assertNotNull(wf);
        assertEquals(playerA, wf.playerUuid());
        assertEquals("Alice", wf.playerName());
        assertEquals(12340, wf.playTicks());
        assertEquals(11L, wf.day());
        assertEquals(1795600000000L, wf.epochMillis());
    }

    @Test
    void loadsLegacy100IntRecordsSafely() {
        UUID playerA = UUID.fromString("11111111-1111-1111-1111-111111111111");
        CompoundTag playerTag = new CompoundTag();
        ListTag targets = new ListTag();
        targets.add(StringTag.valueOf("minecraft:diamond"));
        playerTag.put("targets", targets);
        CompoundTag records = new CompoundTag();
        records.putInt("minecraft:diamond", 12340);
        playerTag.put("records", records);

        CompoundTag players = new CompoundTag();
        players.put(playerA.toString(), playerTag);
        CompoundTag root = new CompoundTag();
        root.put("players", players);

        MilestoneData loaded = MilestoneData.load(root, null);
        MilestoneData.PlayerData pd = loaded.playerOrNull(playerA);
        assertNotNull(pd);
        assertEquals(MilestoneRecord.legacy(12340), pd.records.get("minecraft:diamond"));
        assertEquals("", pd.lastKnownName);
        assertFalse(pd.firstJoinHintShown);
        assertFalse(pd.muteBroadcasts);
        assertTrue(loaded.worldFirsts().isEmpty());
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
