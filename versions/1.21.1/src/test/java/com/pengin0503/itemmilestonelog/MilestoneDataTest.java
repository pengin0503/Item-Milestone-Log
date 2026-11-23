package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
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

        CompoundTag saved = original.save(new CompoundTag(), null);
        MilestoneData loaded = MilestoneData.load(saved, null);

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
        assertFalse(saved.getCompound("players").getCompound(playerB.toString()).contains("carriedFromCreative"));

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

    @Test
    void loadsLegacy100IntRecordsSafely() {
        UUID playerA = UUID.fromString("11111111-1111-1111-1111-111111111111");
        CompoundTag playerTag = new CompoundTag();
        ListTag targets = new ListTag();
        targets.add(StringTag.valueOf("minecraft:diamond"));
        playerTag.put("targets", targets);
        CompoundTag records = new CompoundTag();
        records.putInt("minecraft:diamond", 12340);
        records.putInt("minecraft:dirt", -10); // negative ticks must be skipped
        playerTag.put("records", records);

        CompoundTag players = new CompoundTag();
        players.put(playerA.toString(), playerTag);
        CompoundTag root = new CompoundTag();
        root.put("players", players);

        MilestoneData loaded = MilestoneData.load(root, null);
        MilestoneData.PlayerData pd = loaded.playerOrNull(playerA);
        assertNotNull(pd);
        assertEquals(List.of(TargetKey.of("minecraft:diamond")), List.copyOf(pd.targets));
        assertEquals(1, pd.records.size());
        assertEquals(MilestoneRecord.legacy(12340), pd.records.get(TargetKey.of("minecraft:diamond")));
        assertEquals("", pd.lastKnownName);
        assertFalse(pd.firstJoinHintShown);
        assertFalse(pd.muteBroadcasts);
        assertTrue(loaded.worldFirsts().isEmpty());
    }

    @Test
    void loadsLegacy101CompoundRecordsAndWorldFirstsAsCountOneAndSkipsMissingOrNegativeTicks() {
        UUID playerA = UUID.fromString("11111111-1111-1111-1111-111111111111");
        CompoundTag playerTag = new CompoundTag();
        ListTag targets = new ListTag();
        targets.add(StringTag.valueOf("minecraft:diamond"));
        targets.add(StringTag.valueOf("minecraft:emerald#0")); // invalid, must be skipped
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
        CompoundTag records = new CompoundTag();
        records.put("minecraft:diamond", rec);
        records.put("minecraft:gold_ingot", missingTicksRec);
        records.put("minecraft:iron_ingot", negTicksRec);
        records.put("minecraft:coal", qtyMissingTicks);
        playerTag.put("records", records);

        CompoundTag players = new CompoundTag();
        players.put(playerA.toString(), playerTag);

        CompoundTag wf = new CompoundTag();
        wf.putString("uuid", playerA.toString());
        wf.putString("name", "Alice");
        wf.putInt("ticks", 4567);
        wf.putLong("day", 5L);
        wf.putLong("epoch", 1795600000000L);
        CompoundTag wfNoTicks = new CompoundTag();
        wfNoTicks.putString("uuid", playerA.toString());
        CompoundTag worldFirsts = new CompoundTag();
        worldFirsts.put("minecraft:diamond", wf);
        worldFirsts.put("minecraft:gold_ingot", wfNoTicks);

        CompoundTag root = new CompoundTag();
        root.put("players", players);
        root.put("worldFirsts", worldFirsts);

        MilestoneData loaded = MilestoneData.load(root, null);
        MilestoneData.PlayerData pd = loaded.playerOrNull(playerA);
        assertNotNull(pd);
        assertEquals(List.of(TargetKey.of("minecraft:diamond")), List.copyOf(pd.targets));
        assertEquals(1, pd.records.size());
        assertEquals(new MilestoneRecord(4567, 5L, 1795600000000L), pd.records.get(TargetKey.of("minecraft:diamond")));
        assertEquals(1, loaded.worldFirsts().size());
        WorldFirstRecord loadedWf = loaded.worldFirstOrNull(TargetKey.of("minecraft:diamond"));
        assertNotNull(loadedWf);
        assertEquals(1, loadedWf.count());
        assertEquals("Alice", loadedWf.playerName());
    }

    @Test
    void ignoresMalformedUuidEntriesOnLoadWhileKeepingValidEntries() {
        UUID validUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
        CompoundTag validPlayer = new CompoundTag();
        validPlayer.putString("lastKnownName", "ValidAlice");

        CompoundTag root = new CompoundTag();
        CompoundTag players = new CompoundTag();
        players.put("not-a-valid-uuid", new CompoundTag());
        players.put(validUuid.toString(), validPlayer);
        root.put("players", players);

        MilestoneData loaded = MilestoneData.load(root, null);
        assertEquals(1, loaded.allPlayers().size());
        assertNotNull(loaded.playerOrNull(validUuid));
        assertEquals("ValidAlice", loaded.playerOrNull(validUuid).lastKnownName);
    }
}
