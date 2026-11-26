package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.api.WorldFirstRecord;
import com.pengin0503.itemmilestonelog.compat.McCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
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
        assertTrue(McCompat.nbt().getCompound(McCompat.nbt().getCompound(saved, "players"), playerA.toString()).contains("muteProgressAlerts"));
        assertTrue(McCompat.nbt().getCompound(McCompat.nbt().getCompound(saved, "players"), playerA.toString()).contains("excludeFromDiscord"));
        assertTrue(McCompat.nbt().getCompound(McCompat.nbt().getCompound(saved, "players"), playerA.toString()).contains("lastSeen"));
        assertTrue(McCompat.nbt().getCompound(McCompat.nbt().getCompound(saved, "players"), playerA.toString()).contains("completedPresets"));
        assertFalse(McCompat.nbt().getCompound(McCompat.nbt().getCompound(saved, "players"), playerB.toString()).contains("carriedFromCreative"));
        assertFalse(McCompat.nbt().getCompound(McCompat.nbt().getCompound(saved, "players"), playerB.toString()).contains("muteProgressAlerts"));
        assertFalse(McCompat.nbt().getCompound(McCompat.nbt().getCompound(saved, "players"), playerB.toString()).contains("excludeFromDiscord"));
        assertFalse(McCompat.nbt().getCompound(McCompat.nbt().getCompound(saved, "players"), playerB.toString()).contains("lastSeen"));
        assertFalse(McCompat.nbt().getCompound(McCompat.nbt().getCompound(saved, "players"), playerB.toString()).contains("progressNotified"));
        assertFalse(McCompat.nbt().getCompound(McCompat.nbt().getCompound(saved, "players"), playerB.toString()).contains("completedPresets"));

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
        assertFalse(pd.muteProgressAlerts);
        assertFalse(pd.excludeFromDiscord);
        assertEquals(0L, pd.lastSeen);
        assertTrue(pd.progressNotified.isEmpty());
        assertTrue(pd.completedPresets.isEmpty());
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
        CompoundTag wfNegTicks = new CompoundTag();
        wfNegTicks.putString("uuid", playerA.toString());
        wfNegTicks.putInt("ticks", -5);
        CompoundTag wfBadUuid = new CompoundTag();
        wfBadUuid.putString("uuid", "not-a-uuid");
        wfBadUuid.putInt("ticks", 100);
        CompoundTag worldFirsts = new CompoundTag();
        worldFirsts.put("minecraft:diamond", wf);
        worldFirsts.put("minecraft:gold_ingot", wfNoTicks);
        worldFirsts.put("minecraft:iron_ingot", wfNegTicks);
        worldFirsts.put("minecraft:lapis_lazuli", wfBadUuid);

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

    @Test
    void persistsProgressNotifiedAndSkipsInvalidOrOutOfRangeEntries() {
        UUID playerA = UUID.fromString("11111111-1111-1111-1111-111111111111");
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
        playerBTag.put("progressNotified", StringTag.valueOf("garbage")); // wrong tag type

        CompoundTag players = new CompoundTag();
        players.put(playerA.toString(), playerATag);
        players.put(playerB.toString(), playerBTag);
        CompoundTag root = new CompoundTag();
        root.put("players", players);

        MilestoneData loaded = MilestoneData.load(root, null);
        MilestoneData.PlayerData loadedA = loaded.playerOrNull(playerA);
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

        CompoundTag resaved = loaded.save(new CompoundTag(), null);
        CompoundTag savedATag = McCompat.nbt().getCompound(McCompat.nbt().getCompound(resaved, "players"), playerA.toString());
        assertTrue(savedATag.contains("progressNotified"));
        assertEquals(50, McCompat.nbt().getInt(McCompat.nbt().getCompound(savedATag, "progressNotified"), "minecraft:diamond#1000"));
        assertEquals(0, McCompat.nbt().getInt(McCompat.nbt().getCompound(savedATag, "progressNotified"), "minecraft:iron_ingot#64"));
        assertFalse(McCompat.nbt().getCompound(McCompat.nbt().getCompound(resaved, "players"), playerB.toString()).contains("progressNotified"));
    }

    @Test
    void persistsMuteProgressAlertsLenientlyOmitsWhenFalseAndSurvivesResetAll() {
        UUID playerA = UUID.fromString("11111111-1111-1111-1111-111111111111");
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
        playerCTag.put("muteProgressAlerts", StringTag.valueOf("garbage")); // malformed tag -> skipped

        CompoundTag players = new CompoundTag();
        players.put(playerA.toString(), playerATag);
        players.put(playerB.toString(), playerBTag);
        players.put(playerC.toString(), playerCTag);
        CompoundTag root = new CompoundTag();
        root.put("players", players);

        MilestoneData loaded = MilestoneData.load(root, null);
        MilestoneData.PlayerData loadedA = loaded.playerOrNull(playerA);
        MilestoneData.PlayerData loadedB = loaded.playerOrNull(playerB);
        MilestoneData.PlayerData loadedC = loaded.playerOrNull(playerC);
        assertNotNull(loadedA);
        assertNotNull(loadedB);
        assertNotNull(loadedC);
        assertTrue(loadedA.muteProgressAlerts);
        assertFalse(loadedB.muteProgressAlerts);
        assertFalse(loadedC.muteProgressAlerts);
        assertEquals("Charlie", loadedC.lastKnownName);

        // Write only when true
        CompoundTag resaved = loaded.save(new CompoundTag(), null);
        CompoundTag savedPlayers = McCompat.nbt().getCompound(resaved, "players");
        assertTrue(McCompat.nbt().getBoolean(McCompat.nbt().getCompound(savedPlayers, playerA.toString()), "muteProgressAlerts"));
        assertFalse(McCompat.nbt().getCompound(savedPlayers, playerB.toString()).contains("muteProgressAlerts"));
        assertFalse(McCompat.nbt().getCompound(savedPlayers, playerC.toString()).contains("muteProgressAlerts"));

        // ResetAll must NOT clear personal setting muteProgressAlerts, while purge removes PlayerData
        TargetKey diamond100 = TargetKey.of("minecraft:diamond", 100);
        loadedA.records.put(diamond100, new MilestoneRecord(100, 1L, 1000L));
        loadedA.progressNotified.put(diamond100, 50);
        assertTrue(MilestoneService.resetAllRecords(loaded, playerA, null).success());
        assertTrue(loadedA.records.isEmpty());
        assertTrue(loadedA.progressNotified.isEmpty());
        assertTrue(loadedA.muteProgressAlerts);

        assertTrue(loaded.purgePlayer(playerA));
        assertNull(loaded.playerOrNull(playerA));
    }

    @Test
    void persistsExcludeFromDiscordLenientlyOmitsWhenFalseAndSurvivesResetAll() {
        UUID playerA = UUID.fromString("11111111-1111-1111-1111-111111111111");
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
        playerCTag.put("excludeFromDiscord", StringTag.valueOf("garbage")); // malformed tag -> skipped

        CompoundTag players = new CompoundTag();
        players.put(playerA.toString(), playerATag);
        players.put(playerB.toString(), playerBTag);
        players.put(playerC.toString(), playerCTag);
        CompoundTag root = new CompoundTag();
        root.put("players", players);

        MilestoneData loaded = MilestoneData.load(root, null);
        MilestoneData.PlayerData loadedA = loaded.playerOrNull(playerA);
        MilestoneData.PlayerData loadedB = loaded.playerOrNull(playerB);
        MilestoneData.PlayerData loadedC = loaded.playerOrNull(playerC);
        assertNotNull(loadedA);
        assertNotNull(loadedB);
        assertNotNull(loadedC);
        assertTrue(loadedA.excludeFromDiscord);
        assertFalse(loadedB.excludeFromDiscord);
        assertFalse(loadedC.excludeFromDiscord);
        assertEquals("Charlie", loadedC.lastKnownName);

        // Write only when true
        CompoundTag resaved = loaded.save(new CompoundTag(), null);
        CompoundTag savedPlayers = McCompat.nbt().getCompound(resaved, "players");
        assertTrue(McCompat.nbt().getBoolean(McCompat.nbt().getCompound(savedPlayers, playerA.toString()), "excludeFromDiscord"));
        assertFalse(McCompat.nbt().getCompound(savedPlayers, playerB.toString()).contains("excludeFromDiscord"));
        assertFalse(McCompat.nbt().getCompound(savedPlayers, playerC.toString()).contains("excludeFromDiscord"));

        // ResetAll must NOT clear personal setting excludeFromDiscord, while purge removes PlayerData
        TargetKey diamond100 = TargetKey.of("minecraft:diamond", 100);
        loadedA.records.put(diamond100, new MilestoneRecord(100, 1L, 1000L));
        loadedA.progressNotified.put(diamond100, 50);
        assertTrue(MilestoneService.resetAllRecords(loaded, playerA, null).success());
        assertTrue(loadedA.records.isEmpty());
        assertTrue(loadedA.progressNotified.isEmpty());
        assertTrue(loadedA.excludeFromDiscord);

        assertTrue(loaded.purgePlayer(playerA));
        assertNull(loaded.playerOrNull(playerA));
    }

    @Test
    void persistsHideFromLookupLenientlyOmitsWhenFalseRoundTripsAndSurvivesResetAll() {
        UUID playerA = UUID.fromString("11111111-1111-1111-1111-111111111111");
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
        playerCTag.put("hideFromLookup", StringTag.valueOf("garbage")); // malformed tag -> skipped

        CompoundTag players = new CompoundTag();
        players.put(playerA.toString(), playerATag);
        players.put(playerB.toString(), playerBTag);
        players.put(playerC.toString(), playerCTag);
        CompoundTag root = new CompoundTag();
        root.put("players", players);

        MilestoneData loaded = MilestoneData.load(root, null);
        MilestoneData.PlayerData loadedA = loaded.playerOrNull(playerA);
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

        // Write only when true
        CompoundTag resaved = loaded.save(new CompoundTag(), null);
        CompoundTag savedPlayers = McCompat.nbt().getCompound(resaved, "players");
        assertTrue(McCompat.nbt().getBoolean(McCompat.nbt().getCompound(savedPlayers, playerA.toString()), "hideFromLookup"));
        assertFalse(McCompat.nbt().getCompound(savedPlayers, playerB.toString()).contains("hideFromLookup"));
        assertFalse(McCompat.nbt().getCompound(savedPlayers, playerC.toString()).contains("hideFromLookup"));

        // Round trip: save -> load keeps the flag
        MilestoneData reloaded = MilestoneData.load(resaved, null);
        assertTrue(reloaded.playerOrNull(playerA).isHiddenFromLookup());
        assertFalse(reloaded.playerOrNull(playerB).isHiddenFromLookup());

        // ResetAll must NOT clear personal setting hideFromLookup
        TargetKey diamond100 = TargetKey.of("minecraft:diamond", 100);
        loadedA.records.put(diamond100, new MilestoneRecord(100, 1L, 1000L));
        assertTrue(MilestoneService.resetAllRecords(loaded, playerA, null).success());
        assertTrue(loadedA.records.isEmpty());
        assertTrue(loadedA.isHiddenFromLookup());
    }

    @Test
    void persistsCompletedPresetsSkipsInvalidEntriesOmitsWhenEmptyAndClearsOnResetAll() {
        UUID playerA = UUID.fromString("11111111-1111-1111-1111-111111111111");
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
        playerCTag.put("completedPresets", StringTag.valueOf("not-a-list")); // wrong tag type

        ListTag intList = new ListTag();
        intList.add(IntTag.valueOf(42));
        CompoundTag playerDTag = new CompoundTag();
        playerDTag.putString("lastKnownName", "Dana");
        playerDTag.put("completedPresets", intList); // non-string list elements

        CompoundTag players = new CompoundTag();
        players.put(playerA.toString(), playerATag);
        players.put(playerB.toString(), playerBTag);
        players.put(playerC.toString(), playerCTag);
        players.put(playerD.toString(), playerDTag);
        CompoundTag root = new CompoundTag();
        root.put("players", players);

        MilestoneData loaded = MilestoneData.load(root, null);
        MilestoneData.PlayerData loadedA = loaded.playerOrNull(playerA);
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

        // Save back: written only when non-empty
        CompoundTag resaved = loaded.save(new CompoundTag(), null);
        CompoundTag savedPlayers = McCompat.nbt().getCompound(resaved, "players");
        assertTrue(McCompat.nbt().getCompound(savedPlayers, playerA.toString()).contains("completedPresets"));
        assertEquals(3, McCompat.nbt().getStringList(McCompat.nbt().getCompound(savedPlayers, playerA.toString()), "completedPresets").size());
        assertFalse(McCompat.nbt().getCompound(savedPlayers, playerB.toString()).contains("completedPresets"));
        assertFalse(McCompat.nbt().getCompound(savedPlayers, playerC.toString()).contains("completedPresets"));
        assertFalse(McCompat.nbt().getCompound(savedPlayers, playerD.toString()).contains("completedPresets"));

        // Single reset keeps completedPresets; resetAll clears completedPresets
        TargetKey diamond = TargetKey.of("minecraft:diamond");
        loadedA.records.put(diamond, new MilestoneRecord(100, 1L, 1000L));
        assertTrue(MilestoneService.resetRecord(
                loaded, playerA, null, "minecraft:diamond", 1, Set.of("minecraft:diamond")::contains,
                k -> net.minecraft.network.chat.Component.literal(k.formatDisplayId())).success());
        assertEquals(Set.of("vanilla_endgame", "atm_star", "my_custom-1"), loadedA.completedPresets);

        assertTrue(MilestoneService.resetAllRecords(loaded, playerA, null).success());
        assertTrue(loadedA.completedPresets.isEmpty());
        CompoundTag afterResetAll = loaded.save(new CompoundTag(), null);
        assertFalse(McCompat.nbt().getCompound(McCompat.nbt().getCompound(afterResetAll, "players"), playerA.toString()).contains("completedPresets"));
    }

    @Test
    void persistsLastSeenLenientlyOmitsWhenZeroRoundTripsAndSurvivesResetAll() {
        UUID playerA = UUID.fromString("11111111-1111-1111-1111-111111111111");
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
        playerCTag.put("lastSeen", StringTag.valueOf("garbage")); // malformed tag -> skipped

        CompoundTag playerDTag = new CompoundTag();
        playerDTag.putString("lastKnownName", "Dana");
        playerDTag.putLong("lastSeen", -100L); // negative -> skipped

        CompoundTag players = new CompoundTag();
        players.put(playerA.toString(), playerATag);
        players.put(playerB.toString(), playerBTag);
        players.put(playerC.toString(), playerCTag);
        players.put(playerD.toString(), playerDTag);
        CompoundTag root = new CompoundTag();
        root.put("players", players);

        MilestoneData loaded = MilestoneData.load(root, null);
        MilestoneData.PlayerData loadedA = loaded.playerOrNull(playerA);
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

        // Write only when > 0
        CompoundTag resaved = loaded.save(new CompoundTag(), null);
        CompoundTag savedPlayers = McCompat.nbt().getCompound(resaved, "players");
        assertEquals(1795800000000L, McCompat.nbt().getLong(McCompat.nbt().getCompound(savedPlayers, playerA.toString()), "lastSeen"));
        assertFalse(McCompat.nbt().getCompound(savedPlayers, playerB.toString()).contains("lastSeen"));
        assertFalse(McCompat.nbt().getCompound(savedPlayers, playerC.toString()).contains("lastSeen"));
        assertFalse(McCompat.nbt().getCompound(savedPlayers, playerD.toString()).contains("lastSeen"));

        // Round trip: save -> load preserves lastSeen
        MilestoneData reloaded = MilestoneData.load(resaved, null);
        assertEquals(1795800000000L, reloaded.playerOrNull(playerA).lastSeen);
        assertEquals(0L, reloaded.playerOrNull(playerB).lastSeen);

        // ResetAll must NOT modify lastSeen
        TargetKey diamond = TargetKey.of("minecraft:diamond");
        loadedA.records.put(diamond, new MilestoneRecord(100, 1L, 1000L));
        assertTrue(MilestoneService.resetAllRecords(loaded, playerA, null).success());
        assertTrue(loadedA.records.isEmpty());
        assertEquals(1795800000000L, loadedA.lastSeen);
    }

    @Test
    void roundTripsTagKeysAcrossTargetsRecordsWorldFirstsAndProgressNotified() {
        UUID playerA = UUID.fromString("11111111-1111-1111-1111-111111111111");
        TargetKey ironTag1 = TargetKey.of("#c:ingots/iron");
        TargetKey ironTag100 = TargetKey.of("#c:ingots/iron", 100);
        TargetKey logsTag64 = TargetKey.of("#minecraft:logs", 64);
        MilestoneRecord rec1 = new MilestoneRecord(1200, 2L, 1795600000000L);
        MilestoneRecord rec100 = new MilestoneRecord(48000, 15L, 1795700000000L);

        MilestoneData original = new MilestoneData();
        MilestoneData.PlayerData pd = original.player(playerA);
        pd.lastKnownName = "Alice";
        pd.targets.add(ironTag1);
        pd.targets.add(ironTag100);
        pd.targets.add(logsTag64);
        pd.records.put(ironTag1, rec1);
        pd.records.put(ironTag100, rec100);
        pd.progressNotified.put(logsTag64, 50);
        assertTrue(original.recordWorldFirstIfAbsent(ironTag1, playerA, "Alice", rec1));
        assertTrue(original.recordWorldFirstIfAbsent(ironTag100, playerA, "Alice", rec100));

        CompoundTag saved = original.save(new CompoundTag(), null);
        // Verify on-disk NBT format matches F8a storage spec
        CompoundTag savedPlayer = McCompat.nbt().getCompound(McCompat.nbt().getCompound(saved, "players"), playerA.toString());
        ListTag savedTargets = McCompat.nbt().getStringList(savedPlayer, "targets");
        assertEquals(List.of("#c:ingots/iron", "#c:ingots/iron#100", "#minecraft:logs#64"),
                List.of(McCompat.nbt().listString(savedTargets, 0), McCompat.nbt().listString(savedTargets, 1), McCompat.nbt().listString(savedTargets, 2)));
        assertTrue(McCompat.nbt().getCompound(savedPlayer, "records").contains("#c:ingots/iron"));
        assertEquals(50, McCompat.nbt().getInt(McCompat.nbt().getCompound(savedPlayer, "progressNotified"), "#minecraft:logs#64"));
        assertTrue(McCompat.nbt().getCompound(saved, "worldFirsts").contains("#c:ingots/iron"));

        // Inject malformed tag entries to confirm lenient per-entry skipping
        savedTargets.add(StringTag.valueOf("#"));
        savedTargets.add(StringTag.valueOf("##c:x"));
        savedTargets.add(StringTag.valueOf("#c:x#0"));
        savedTargets.add(StringTag.valueOf("#:x"));
        savedTargets.add(StringTag.valueOf("#c:"));
        McCompat.nbt().getCompound(savedPlayer, "progressNotified").putInt("#c:x#abc", 50);

        MilestoneData loaded = MilestoneData.load(saved, null);
        MilestoneData.PlayerData loadedPd = loaded.playerOrNull(playerA);
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
    }

    @Test
    void persistsLastDigestEpochLenientlyOmitsWhenZeroAndRoundTrips() {
        MilestoneData original = new MilestoneData();
        assertEquals(0L, original.lastDigestEpochMillis());
        assertFalse(original.save(new CompoundTag(), null).contains("lastDigestEpoch"));

        // Setting a positive timestamp marks dirty, writes NBT key "lastDigestEpoch", and round-trips.
        original.setLastDigestEpochMillis(1795800000000L);
        assertTrue(original.isDirty());
        CompoundTag saved = original.save(new CompoundTag(), null);
        assertEquals(1795800000000L, McCompat.nbt().getLong(saved, "lastDigestEpoch"));
        MilestoneData loaded = MilestoneData.load(saved, null);
        assertEquals(1795800000000L, loaded.lastDigestEpochMillis());

        // Setting negative clamps to 0 and omits key on save.
        original.setLastDigestEpochMillis(-50L);
        assertEquals(0L, original.lastDigestEpochMillis());
        assertFalse(original.save(new CompoundTag(), null).contains("lastDigestEpoch"));

        // Absent key defaults to 0.
        assertEquals(0L, MilestoneData.load(new CompoundTag(), null).lastDigestEpochMillis());

        // Malformed tag (non-numeric) is skipped leniently without breaking player data.
        UUID playerA = UUID.fromString("11111111-1111-1111-1111-111111111111");
        CompoundTag playerATag = new CompoundTag();
        playerATag.putString("lastKnownName", "Alice");
        CompoundTag players = new CompoundTag();
        players.put(playerA.toString(), playerATag);

        CompoundTag malformedRoot = new CompoundTag();
        malformedRoot.put("players", players.copy());
        malformedRoot.put("lastDigestEpoch", StringTag.valueOf("garbage"));
        MilestoneData loadedMalformed = MilestoneData.load(malformedRoot, null);
        assertEquals(0L, loadedMalformed.lastDigestEpochMillis());
        assertEquals("Alice", loadedMalformed.playerOrNull(playerA).lastKnownName);

        // Negative value is skipped leniently and defaults to 0.
        CompoundTag negativeRoot = new CompoundTag();
        negativeRoot.put("players", players.copy());
        negativeRoot.putLong("lastDigestEpoch", -100L);
        MilestoneData loadedNegative = MilestoneData.load(negativeRoot, null);
        assertEquals(0L, loadedNegative.lastDigestEpochMillis());
        assertEquals("Alice", loadedNegative.playerOrNull(playerA).lastKnownName);
    }

    @Test
    void writesSchemaVersionOneForNewDataAndRoundTrips() {
        UUID player = UUID.fromString("11111111-1111-1111-1111-111111111111");
        MilestoneData original = new MilestoneData();
        original.player(player).targets.add(TargetKey.of("minecraft:diamond"));

        CompoundTag saved = original.save(new CompoundTag(), null);
        assertEquals(SchemaVersionLogic.CURRENT_SCHEMA_VERSION, McCompat.nbt().getInt(saved, "schemaVersion"));

        MilestoneData loaded = MilestoneData.load(saved, null);
        assertEquals(SchemaVersionLogic.CURRENT_SCHEMA_VERSION, loaded.retainedSchemaVersion());
        assertEquals(SchemaVersionLogic.CURRENT_SCHEMA_VERSION, loaded.schemaVersionForSave());

        CompoundTag resaved = loaded.save(new CompoundTag(), null);
        assertEquals(SchemaVersionLogic.CURRENT_SCHEMA_VERSION, McCompat.nbt().getInt(resaved, "schemaVersion"));
    }

    @Test
    void legacyFixtureWithoutSchemaVersionLoadsAndSavesBackWithOneAndBodyUnchanged() {
        UUID player = UUID.fromString("11111111-1111-1111-1111-111111111111");
        MilestoneData original = new MilestoneData();
        MilestoneData.PlayerData pd = original.player(player);
        pd.targets.add(TargetKey.of("minecraft:diamond", 100));
        MilestoneRecord record = new MilestoneRecord(12340, 11L, 1795600000000L);
        pd.records.put(TargetKey.of("minecraft:diamond", 100), record);
        pd.lastKnownName = "Alice";
        original.recordWorldFirstIfAbsent(TargetKey.of("minecraft:diamond", 100), player, "Alice", record);

        // 1.3.1 fixture: identical body, no schemaVersion field.
        CompoundTag legacy = original.save(new CompoundTag(), null);
        legacy.remove("schemaVersion");
        assertFalse(McCompat.nbt().contains(legacy, "schemaVersion"));

        MilestoneData loaded = MilestoneData.load(legacy, null);
        assertEquals(0, loaded.retainedSchemaVersion());
        assertEquals(SchemaVersionLogic.CURRENT_SCHEMA_VERSION, loaded.schemaVersionForSave());

        MilestoneData.PlayerData loadedPd = loaded.playerOrNull(player);
        assertNotNull(loadedPd);
        assertEquals(Set.of(TargetKey.of("minecraft:diamond", 100)), loadedPd.targets);
        assertEquals(record, loadedPd.records.get(TargetKey.of("minecraft:diamond", 100)));
        assertEquals("Alice", loadedPd.lastKnownName);
        assertEquals(original.worldFirsts(), loaded.worldFirsts());

        CompoundTag resaved = loaded.save(new CompoundTag(), null);
        assertEquals(SchemaVersionLogic.CURRENT_SCHEMA_VERSION, McCompat.nbt().getInt(resaved, "schemaVersion"));
        assertEquals(McCompat.nbt().getCompound(legacy, "players").toString(),
                McCompat.nbt().getCompound(resaved, "players").toString());
    }

    @Test
    void futureSchemaVersion99IsPreservedOnSaveAndInvalidValuesAreTreatedAsZero() {
        UUID player = UUID.fromString("11111111-1111-1111-1111-111111111111");
        MilestoneData original = new MilestoneData();
        original.player(player).targets.add(TargetKey.of("minecraft:diamond"));
        CompoundTag tag = original.save(new CompoundTag(), null);

        tag.putInt("schemaVersion", 99);
        MilestoneData future = MilestoneData.load(tag, null);
        assertEquals(99, future.retainedSchemaVersion());
        assertEquals(99, future.schemaVersionForSave());
        assertEquals(99, McCompat.nbt().getInt(future.save(new CompoundTag(), null), "schemaVersion"));

        tag.putInt("schemaVersion", -3);
        MilestoneData negative = MilestoneData.load(tag, null);
        assertEquals(0, negative.retainedSchemaVersion());
        assertEquals(SchemaVersionLogic.CURRENT_SCHEMA_VERSION,
                McCompat.nbt().getInt(negative.save(new CompoundTag(), null), "schemaVersion"));

        tag.putString("schemaVersion", "not-a-number");
        MilestoneData malformed = MilestoneData.load(tag, null);
        assertEquals(0, malformed.retainedSchemaVersion());
        assertEquals(SchemaVersionLogic.CURRENT_SCHEMA_VERSION,
                McCompat.nbt().getInt(malformed.save(new CompoundTag(), null), "schemaVersion"));
        assertEquals(Set.of(TargetKey.of("minecraft:diamond")),
                malformed.playerOrNull(player).targets);
    }
    @Test
    void loadReportCountsElementSkipsAndCleanLoadYieldsZero() {
        UUID player = UUID.fromString("11111111-1111-1111-1111-111111111111");
        CompoundTag playerTag = new CompoundTag();
        ListTag targets = new ListTag();
        targets.add(StringTag.valueOf("minecraft:diamond"));
        targets.add(StringTag.valueOf("minecraft:emerald#0")); // invalid count -> one element skip
        playerTag.put("targets", targets);
        CompoundTag players = new CompoundTag();
        players.put(player.toString(), playerTag);
        CompoundTag corrupt = new CompoundTag();
        corrupt.put("players", players);
        corrupt.putInt("schemaVersion", 1);

        MilestoneData loaded = MilestoneData.load(corrupt, null);
        assertNotNull(loaded.loadReport());
        assertEquals(1, loaded.loadReport().elementSkipCount());
        assertEquals(1, loaded.loadReport().loadedSchemaVersion());
        assertFalse(loaded.loadReport().totalFailure());
        assertEquals(1, loaded.playerOrNull(player).targets.size());

        CompoundTag clean = loaded.save(new CompoundTag(), null);
        MilestoneData cleanLoaded = MilestoneData.load(clean, null);
        assertNotNull(cleanLoaded.loadReport());
        assertEquals(0, cleanLoaded.loadReport().elementSkipCount());
        assertEquals(1, cleanLoaded.loadReport().loadedSchemaVersion());
    }

    @Test
    void loadReportCountsMalformedSchemaVersionButNotFutureVersion() {
        UUID player = UUID.fromString("11111111-1111-1111-1111-111111111111");
        MilestoneData original = new MilestoneData();
        original.player(player).targets.add(TargetKey.of("minecraft:diamond"));
        CompoundTag tag = original.save(new CompoundTag(), null);

        tag.putInt("schemaVersion", 99);
        MilestoneData future = MilestoneData.load(tag, null);
        assertNotNull(future.loadReport());
        assertEquals(0, future.loadReport().elementSkipCount());
        assertEquals(99, future.loadReport().loadedSchemaVersion());

        tag.putString("schemaVersion", "not-a-number");
        MilestoneData malformed = MilestoneData.load(tag, null);
        assertNotNull(malformed.loadReport());
        assertEquals(1, malformed.loadReport().elementSkipCount());
        assertEquals(0, malformed.loadReport().loadedSchemaVersion());
    }

    @Test
    void presetStateRoundTripsAndLegacyDataWithoutItLoadsEmpty() {
        UUID player = UUID.fromString("11111111-1111-1111-1111-111111111111");
        MilestoneData original = new MilestoneData();
        MilestoneData.PlayerData pd = original.player(player);
        pd.presetState.put("vanilla_endgame", new PresetState(true, 3));
        pd.presetState.put("my_custom_pack", new PresetState(false, 1));

        CompoundTag saved = original.save(new CompoundTag(), null);
        CompoundTag savedPd = McCompat.nbt().getCompound(
                McCompat.nbt().getCompound(saved, "players"), player.toString());
        assertTrue(savedPd.contains("presetState"));

        MilestoneData loaded = MilestoneData.load(saved, null);
        MilestoneData.PlayerData loadedPd = loaded.playerOrNull(player);
        assertNotNull(loadedPd);
        assertEquals(Map.of(
                "vanilla_endgame", new PresetState(true, 3),
                "my_custom_pack", new PresetState(false, 1)
        ), loadedPd.presetState);

        // Legacy data (no presetState key) loads as an empty map.
        CompoundTag legacy = new CompoundTag();
        CompoundTag players = new CompoundTag();
        players.put(player.toString(), new CompoundTag());
        legacy.put("players", players);
        MilestoneData legacyLoaded = MilestoneData.load(legacy, null);
        assertNotNull(legacyLoaded.playerOrNull(player));
        assertTrue(legacyLoaded.playerOrNull(player).presetState.isEmpty());
    }

    @Test
    void presetStateLoaderSkipsInvalidElementsAndCountsThemInTheLoadReport() {
        UUID player = UUID.fromString("11111111-1111-1111-1111-111111111111");
        MilestoneData original = new MilestoneData();
        original.player(player);
        CompoundTag saved = original.save(new CompoundTag(), null);
        CompoundTag savedPd = McCompat.nbt().getCompound(
                McCompat.nbt().getCompound(saved, "players"), player.toString());

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

        MilestoneData loaded = MilestoneData.load(saved, null);
        MilestoneData.PlayerData loadedPd = loaded.playerOrNull(player);
        assertNotNull(loadedPd);
        assertEquals(Map.of("good_pack", new PresetState(true, 2)), loadedPd.presetState);
        assertNotNull(loaded.loadReport());
        assertEquals(4, loaded.loadReport().elementSkipCount());
    }
}
