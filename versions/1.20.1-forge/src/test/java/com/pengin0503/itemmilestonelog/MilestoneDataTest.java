package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.api.WorldFirstRecord;
import com.pengin0503.itemmilestonelog.compat.McCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
    private static final UUID PLAYER_A = UUID.fromString("11111111-1111-1111-1111-111111111111");

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

        CompoundTag saved = original.save(new CompoundTag());
        MilestoneData loaded = MilestoneData.load(saved);

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

        MilestoneData loaded = MilestoneData.load(root);
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

        MilestoneData loaded = MilestoneData.load(root);
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

        MilestoneData loaded = MilestoneData.load(root);
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

        MilestoneData loaded = MilestoneData.load(root);
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

        CompoundTag resaved = loaded.save(new CompoundTag());
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

        MilestoneData loaded = MilestoneData.load(root);
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
        CompoundTag resaved = loaded.save(new CompoundTag());
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

        MilestoneData loaded = MilestoneData.load(root);
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
        CompoundTag resaved = loaded.save(new CompoundTag());
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

        MilestoneData loaded = MilestoneData.load(root);
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
        CompoundTag resaved = loaded.save(new CompoundTag());
        CompoundTag savedPlayers = McCompat.nbt().getCompound(resaved, "players");
        assertTrue(McCompat.nbt().getBoolean(McCompat.nbt().getCompound(savedPlayers, playerA.toString()), "hideFromLookup"));
        assertFalse(McCompat.nbt().getCompound(savedPlayers, playerB.toString()).contains("hideFromLookup"));
        assertFalse(McCompat.nbt().getCompound(savedPlayers, playerC.toString()).contains("hideFromLookup"));

        // Round trip: save -> load keeps the flag
        MilestoneData reloaded = MilestoneData.load(resaved);
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

        MilestoneData loaded = MilestoneData.load(root);
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
        CompoundTag resaved = loaded.save(new CompoundTag());
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
        CompoundTag afterResetAll = loaded.save(new CompoundTag());
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

        MilestoneData loaded = MilestoneData.load(root);
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
        CompoundTag resaved = loaded.save(new CompoundTag());
        CompoundTag savedPlayers = McCompat.nbt().getCompound(resaved, "players");
        assertEquals(1795800000000L, McCompat.nbt().getLong(McCompat.nbt().getCompound(savedPlayers, playerA.toString()), "lastSeen"));
        assertFalse(McCompat.nbt().getCompound(savedPlayers, playerB.toString()).contains("lastSeen"));
        assertFalse(McCompat.nbt().getCompound(savedPlayers, playerC.toString()).contains("lastSeen"));
        assertFalse(McCompat.nbt().getCompound(savedPlayers, playerD.toString()).contains("lastSeen"));

        // Round trip: save -> load preserves lastSeen
        MilestoneData reloaded = MilestoneData.load(resaved);
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

        CompoundTag saved = original.save(new CompoundTag());
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

        MilestoneData loaded = MilestoneData.load(saved);
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
        assertFalse(original.save(new CompoundTag()).contains("lastDigestEpoch"));

        // Setting a positive timestamp marks dirty, writes NBT key "lastDigestEpoch", and round-trips.
        original.setLastDigestEpochMillis(1795800000000L);
        assertTrue(original.isDirty());
        CompoundTag saved = original.save(new CompoundTag());
        assertEquals(1795800000000L, McCompat.nbt().getLong(saved, "lastDigestEpoch"));
        MilestoneData loaded = MilestoneData.load(saved);
        assertEquals(1795800000000L, loaded.lastDigestEpochMillis());

        // Setting negative clamps to 0 and omits key on save.
        original.setLastDigestEpochMillis(-50L);
        assertEquals(0L, original.lastDigestEpochMillis());
        assertFalse(original.save(new CompoundTag()).contains("lastDigestEpoch"));

        // Absent key defaults to 0.
        assertEquals(0L, MilestoneData.load(new CompoundTag()).lastDigestEpochMillis());

        // Malformed tag (non-numeric) is skipped leniently without breaking player data.
        UUID playerA = UUID.fromString("11111111-1111-1111-1111-111111111111");
        CompoundTag playerATag = new CompoundTag();
        playerATag.putString("lastKnownName", "Alice");
        CompoundTag players = new CompoundTag();
        players.put(playerA.toString(), playerATag);

        CompoundTag malformedRoot = new CompoundTag();
        malformedRoot.put("players", players.copy());
        malformedRoot.put("lastDigestEpoch", StringTag.valueOf("garbage"));
        MilestoneData loadedMalformed = MilestoneData.load(malformedRoot);
        assertEquals(0L, loadedMalformed.lastDigestEpochMillis());
        assertEquals("Alice", loadedMalformed.playerOrNull(playerA).lastKnownName);

        // Negative value is skipped leniently and defaults to 0.
        CompoundTag negativeRoot = new CompoundTag();
        negativeRoot.put("players", players.copy());
        negativeRoot.putLong("lastDigestEpoch", -100L);
        MilestoneData loadedNegative = MilestoneData.load(negativeRoot);
        assertEquals(0L, loadedNegative.lastDigestEpochMillis());
        assertEquals("Alice", loadedNegative.playerOrNull(playerA).lastKnownName);
    }

    @Test
    void writesSchemaVersionOneForNewDataAndRoundTrips() {
        UUID player = UUID.fromString("11111111-1111-1111-1111-111111111111");
        MilestoneData original = new MilestoneData();
        original.player(player).targets.add(TargetKey.of("minecraft:diamond"));

        CompoundTag saved = original.save(new CompoundTag());
        assertEquals(SchemaVersionLogic.CURRENT_SCHEMA_VERSION, McCompat.nbt().getInt(saved, "schemaVersion"));

        MilestoneData loaded = MilestoneData.load(saved);
        assertEquals(SchemaVersionLogic.CURRENT_SCHEMA_VERSION, loaded.retainedSchemaVersion());
        assertEquals(SchemaVersionLogic.CURRENT_SCHEMA_VERSION, loaded.schemaVersionForSave());

        CompoundTag resaved = loaded.save(new CompoundTag());
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

        CompoundTag legacy = original.save(new CompoundTag());
        legacy.remove("schemaVersion");
        assertFalse(McCompat.nbt().contains(legacy, "schemaVersion"));

        MilestoneData loaded = MilestoneData.load(legacy);
        assertEquals(0, loaded.retainedSchemaVersion());
        assertEquals(SchemaVersionLogic.CURRENT_SCHEMA_VERSION, loaded.schemaVersionForSave());

        MilestoneData.PlayerData loadedPd = loaded.playerOrNull(player);
        assertNotNull(loadedPd);
        assertEquals(Set.of(TargetKey.of("minecraft:diamond", 100)), loadedPd.targets);
        assertEquals(record, loadedPd.records.get(TargetKey.of("minecraft:diamond", 100)));
        assertEquals("Alice", loadedPd.lastKnownName);
        assertEquals(original.worldFirsts(), loaded.worldFirsts());

        CompoundTag resaved = loaded.save(new CompoundTag());
        assertEquals(SchemaVersionLogic.CURRENT_SCHEMA_VERSION, McCompat.nbt().getInt(resaved, "schemaVersion"));
        assertEquals(McCompat.nbt().getCompound(legacy, "players").toString(),
                McCompat.nbt().getCompound(resaved, "players").toString());
    }

    @Test
    void futureSchemaVersion99IsPreservedOnSaveAndInvalidValuesAreTreatedAsZero() {
        UUID player = UUID.fromString("11111111-1111-1111-1111-111111111111");
        MilestoneData original = new MilestoneData();
        original.player(player).targets.add(TargetKey.of("minecraft:diamond"));
        CompoundTag tag = original.save(new CompoundTag());

        tag.putInt("schemaVersion", 99);
        MilestoneData future = MilestoneData.load(tag);
        assertEquals(99, future.retainedSchemaVersion());
        assertEquals(99, future.schemaVersionForSave());
        assertEquals(99, McCompat.nbt().getInt(future.save(new CompoundTag()), "schemaVersion"));

        tag.putInt("schemaVersion", -3);
        MilestoneData negative = MilestoneData.load(tag);
        assertEquals(0, negative.retainedSchemaVersion());
        assertEquals(SchemaVersionLogic.CURRENT_SCHEMA_VERSION,
                McCompat.nbt().getInt(negative.save(new CompoundTag()), "schemaVersion"));

        tag.putString("schemaVersion", "not-a-number");
        MilestoneData malformed = MilestoneData.load(tag);
        assertEquals(0, malformed.retainedSchemaVersion());
        assertEquals(SchemaVersionLogic.CURRENT_SCHEMA_VERSION,
                McCompat.nbt().getInt(malformed.save(new CompoundTag()), "schemaVersion"));
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

        MilestoneData loaded = MilestoneData.load(corrupt);
        assertNotNull(loaded.loadReport());
        assertEquals(1, loaded.loadReport().elementSkipCount());
        assertEquals(1, loaded.loadReport().loadedSchemaVersion());
        assertFalse(loaded.loadReport().totalFailure());
        assertEquals(1, loaded.playerOrNull(player).targets.size());

        CompoundTag clean = loaded.save(new CompoundTag());
        MilestoneData cleanLoaded = MilestoneData.load(clean);
        assertNotNull(cleanLoaded.loadReport());
        assertEquals(0, cleanLoaded.loadReport().elementSkipCount());
        assertEquals(1, cleanLoaded.loadReport().loadedSchemaVersion());
    }

    @Test
    void loadReportCountsMalformedSchemaVersionButNotFutureVersion() {
        UUID player = UUID.fromString("11111111-1111-1111-1111-111111111111");
        MilestoneData original = new MilestoneData();
        original.player(player).targets.add(TargetKey.of("minecraft:diamond"));
        CompoundTag tag = original.save(new CompoundTag());

        tag.putInt("schemaVersion", 99);
        MilestoneData future = MilestoneData.load(tag);
        assertNotNull(future.loadReport());
        assertEquals(0, future.loadReport().elementSkipCount());
        assertEquals(99, future.loadReport().loadedSchemaVersion());

        tag.putString("schemaVersion", "not-a-number");
        MilestoneData malformed = MilestoneData.load(tag);
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

        CompoundTag saved = original.save(new CompoundTag());
        CompoundTag savedPd = McCompat.nbt().getCompound(
                McCompat.nbt().getCompound(saved, "players"), player.toString());
        assertTrue(savedPd.contains("presetState"));

        MilestoneData loaded = MilestoneData.load(saved);
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
        MilestoneData legacyLoaded = MilestoneData.load(legacy);
        assertNotNull(legacyLoaded.playerOrNull(player));
        assertTrue(legacyLoaded.playerOrNull(player).presetState.isEmpty());
    }

    @Test
    void presetStateLoaderSkipsInvalidElementsAndCountsThemInTheLoadReport() {
        UUID player = UUID.fromString("11111111-1111-1111-1111-111111111111");
        MilestoneData original = new MilestoneData();
        original.player(player);
        CompoundTag saved = original.save(new CompoundTag());
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

        MilestoneData loaded = MilestoneData.load(saved);
        MilestoneData.PlayerData loadedPd = loaded.playerOrNull(player);
        assertNotNull(loadedPd);
        assertEquals(Map.of("good_pack", new PresetState(true, 2)), loadedPd.presetState);
        assertNotNull(loaded.loadReport());
        assertEquals(4, loaded.loadReport().elementSkipCount());
    }
    @Test
    void loadUnwrapsDoubleWrappedPayloadWrittenByBuggy1215Build() {
        MilestoneData original = new MilestoneData();
        UUID player = UUID.fromString("11111111-1111-1111-1111-111111111111");
        TargetKey diamond = TargetKey.of("minecraft:diamond", 3);
        MilestoneData.PlayerData pd = original.player(player);
        pd.targets.add(diamond);
        MilestoneRecord record = new MilestoneRecord(4567, 5L, 1795600000000L);
        pd.records.put(diamond, record);
        pd.carriedFromCreative.add("minecraft:emerald");
        pd.lastKnownName = "Alice";
        original.recordWorldFirstIfAbsent(diamond, player, "Alice", record);

        // Vanilla strips the SavedData wrapper, so a buggy #99 file reaches load() as
        // {"data": payload}. It must be recovered, not read as an empty world.
        CompoundTag payload = original.save(new CompoundTag());
        CompoundTag wrapped = new CompoundTag();
        wrapped.put("data", payload.copy());

        MilestoneData loaded = MilestoneData.load(wrapped);
        MilestoneData.PlayerData loadedPd = loaded.playerOrNull(player);
        assertNotNull(loadedPd);
        assertEquals(List.of(diamond), List.copyOf(loadedPd.targets));
        assertEquals(record, loadedPd.records.get(diamond));
        assertEquals(Set.of("minecraft:emerald"), loadedPd.carriedFromCreative);
        assertEquals("Alice", loadedPd.lastKnownName);
        assertNotNull(loaded.worldFirstOrNull(diamond));
        assertNotNull(loaded.loadReport());
        assertEquals(0, loaded.loadReport().elementSkipCount());

        // CorruptionEvacuation uses this helper to read schemaVersion through the wrapper.
        CompoundTag unwrapped = MilestoneData.unwrapDoubleWrappedData(wrapped.copy());
        assertEquals(SchemaVersionLogic.CURRENT_SCHEMA_VERSION, unwrapped.getInt("schemaVersion"));
    }

    private static CompoundTag validParityPlayerTag() {
        CompoundTag player = new CompoundTag();
        ListTag targets = new ListTag();
        targets.add(StringTag.valueOf("minecraft:diamond"));
        player.put("targets", targets);
        CompoundTag records = new CompoundTag();
        records.putInt("minecraft:coal", 123);
        player.put("records", records);
        ListTag carried = new ListTag();
        carried.add(StringTag.valueOf("minecraft:emerald"));
        player.put("carriedFromCreative", carried);
        player.putString("lastKnownName", "Alice");
        player.putBoolean("firstJoinHintShown", true);
        player.putBoolean("muteBroadcasts", true);
        return player;
    }

    /** Loads one corrupted player, asserting exactly one element skip and an evacuation decision. */
    private static MilestoneData.PlayerData loadOneSkip(UUID playerId, CompoundTag playerTag) {
        CompoundTag players = new CompoundTag();
        players.put(playerId.toString(), playerTag);
        CompoundTag root = new CompoundTag();
        root.put("players", players);
        root.putInt("schemaVersion", 1);

        MilestoneData loaded = MilestoneData.load(root);
        assertNotNull(loaded.loadReport());
        assertEquals(1, loaded.loadReport().elementSkipCount(),
                "one corrupted field must be counted so F-140-2 can evacuate");
        assertTrue(loaded.loadReport().toOutcome().requiresEvacuation());
        MilestoneData.PlayerData pd = loaded.playerOrNull(playerId);
        assertNotNull(pd);
        assertEquals(List.of(TargetKey.of("minecraft:diamond")), List.copyOf(pd.targets),
                "valid sibling targets must survive");
        return pd;
    }

    @Test
    void loadInternalCorruptParityFieldsCountSkipsAndKeepValidSiblings() {
        UUID player = UUID.fromString("11111111-1111-1111-1111-111111111111");

        // 1. records[itemId] value has an invalid type (not a compound, not a legacy int).
        CompoundTag badRecordValue = validParityPlayerTag();
        badRecordValue.getCompound("records").put("minecraft:coal", StringTag.valueOf("garbage"));
        MilestoneData.PlayerData pd1 = loadOneSkip(player, badRecordValue);
        assertTrue(pd1.records.isEmpty());
        assertEquals(Set.of("minecraft:emerald"), pd1.carriedFromCreative);
        assertEquals("Alice", pd1.lastKnownName);
        assertTrue(pd1.firstJoinHintShown);
        assertTrue(pd1.muteBroadcasts);

        // 2. one invalid carriedFromCreative id is dropped and counted, the valid one is kept.
        CompoundTag badCarried = validParityPlayerTag();
        ListTag carried = new ListTag();
        carried.add(StringTag.valueOf("minecraft:emerald"));
        carried.add(StringTag.valueOf("garbage id")); // whitespace makes it an invalid item id
        badCarried.put("carriedFromCreative", carried);
        MilestoneData.PlayerData pd2 = loadOneSkip(player, badCarried);
        assertEquals(Set.of("minecraft:emerald"), pd2.carriedFromCreative);
        assertEquals(1, pd2.records.size());
        assertEquals("Alice", pd2.lastKnownName);
        assertTrue(pd2.firstJoinHintShown);
        assertTrue(pd2.muteBroadcasts);

        // 3. lastKnownName has an invalid type -> default, other fields survive.
        CompoundTag badName = validParityPlayerTag();
        badName.putInt("lastKnownName", 7);
        MilestoneData.PlayerData pd3 = loadOneSkip(player, badName);
        assertEquals("", pd3.lastKnownName);
        assertEquals(1, pd3.records.size());
        assertEquals(Set.of("minecraft:emerald"), pd3.carriedFromCreative);
        assertTrue(pd3.firstJoinHintShown);
        assertTrue(pd3.muteBroadcasts);

        // 4. firstJoinHintShown has an invalid type -> default false.
        CompoundTag badFirstJoin = validParityPlayerTag();
        badFirstJoin.putString("firstJoinHintShown", "yes");
        MilestoneData.PlayerData pd4 = loadOneSkip(player, badFirstJoin);
        assertFalse(pd4.firstJoinHintShown);
        assertTrue(pd4.muteBroadcasts);
        assertEquals("Alice", pd4.lastKnownName);
        assertEquals(1, pd4.records.size());

        // 5. muteBroadcasts has an invalid type -> default false.
        CompoundTag badMute = validParityPlayerTag();
        badMute.putString("muteBroadcasts", "yes");
        MilestoneData.PlayerData pd5 = loadOneSkip(player, badMute);
        assertFalse(pd5.muteBroadcasts);
        assertTrue(pd5.firstJoinHintShown);
        assertEquals("Alice", pd5.lastKnownName);
        assertEquals(1, pd5.records.size());
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

        CompoundTag saved = original.save(new CompoundTag());
        CompoundTag players = McCompat.nbt().getCompound(saved, "players");
        CompoundTag playerTag = McCompat.nbt().getCompound(players, PLAYER_A.toString());
        assertFalse(playerTag.contains("progressAlertDestination"), "the default is never written");
        playerTag.remove("progressAlertDestination");
        players.put(PLAYER_A.toString(), playerTag);
        saved.put("players", players);

        MilestoneData loaded = MilestoneData.load(saved);
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
        CompoundTag saved = original.save(new CompoundTag());
        assertEquals("BOTH", McCompat.nbt().getString(
                McCompat.nbt().getCompound(McCompat.nbt().getCompound(saved, "players"), PLAYER_A.toString()),
                "progressAlertDestination"));
        MilestoneData loaded = MilestoneData.load(saved);
        assertEquals("BOTH", loaded.playerOrNull(PLAYER_A).progressAlertDestination);

        pd.progressAlertDestination = "FOLLOW_SERVER";
        CompoundTag defaultSaved = original.save(new CompoundTag());
        assertFalse(McCompat.nbt().getCompound(
                McCompat.nbt().getCompound(defaultSaved, "players"), PLAYER_A.toString())
                .contains("progressAlertDestination"));
        assertEquals("FOLLOW_SERVER", MilestoneData.load(defaultSaved)
                .playerOrNull(PLAYER_A).progressAlertDestination);
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

        CompoundTag saved = original.save(new CompoundTag());
        CompoundTag players = McCompat.nbt().getCompound(saved, "players");
        CompoundTag playerTag = McCompat.nbt().getCompound(players, PLAYER_A.toString());
        playerTag.put("progressAlertDestination", IntTag.valueOf(7));
        players.put(PLAYER_A.toString(), playerTag);
        saved.put("players", players);
        MilestoneData loaded = MilestoneData.load(saved);
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
        MilestoneData invalidName = MilestoneData.load(saved);
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

        CompoundTag saved = original.save(new CompoundTag());
        MilestoneData before = MilestoneData.load(saved);
        CompoundTag downgraded = saved.copy();
        CompoundTag players = McCompat.nbt().getCompound(downgraded, "players");
        CompoundTag playerTag = McCompat.nbt().getCompound(players, PLAYER_A.toString());
        assertTrue(playerTag.contains("progressAlertDestination"));
        playerTag.remove("progressAlertDestination");
        players.put(PLAYER_A.toString(), playerTag);
        downgraded.put("players", players);

        MilestoneData after = MilestoneData.load(downgraded);
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
        UUID player = PLAYER_A;
        MilestoneData original = new MilestoneData();
        MilestoneData.PlayerData pd = original.player(player);
        TargetKey diamond = TargetKey.of("minecraft:diamond", 3);
        MilestoneRecord record = new MilestoneRecord(1234, 5L, 1795600000000L);
        pd.targets.add(diamond);
        pd.records.put(diamond, record);
        pd.lastKnownName = "Alice";
        pd.completedPresets.add("vanilla_endgame");
        pd.presetState.put("vanilla_endgame", new PresetState(true, 2));
        original.recordWorldFirstIfAbsent(diamond, player, "Alice", record);

        // A 1.4.0 file has no targetMeta key in its player compound.
        CompoundTag saved = original.save(new CompoundTag());
        CompoundTag savedPlayers = McCompat.nbt().getCompound(saved, "players");
        CompoundTag savedPd = McCompat.nbt().getCompound(savedPlayers, player.toString());
        savedPd.remove("targetMeta");
        savedPlayers.put(player.toString(), savedPd);
        saved.put("players", savedPlayers);

        MilestoneData loaded = MilestoneData.load(saved);
        MilestoneData.PlayerData loadedPd = loaded.playerOrNull(player);
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
        UUID player = PLAYER_A;
        MilestoneData original = new MilestoneData();
        MilestoneData.PlayerData pd = original.player(player);
        pd.targets.add(TargetKey.of("minecraft:diamond", 3));
        pd.targets.add(TargetKey.of("minecraft:emerald"));
        TargetMeta pinned = new TargetMeta(7, "star pick", true);
        TargetMeta plain = new TargetMeta(2, "", false);
        pd.targetMeta.put("minecraft:diamond#3", pinned);
        pd.targetMeta.put("minecraft:emerald", plain);

        CompoundTag saved = original.save(new CompoundTag());
        CompoundTag metas = McCompat.nbt().getCompound(
                McCompat.nbt().getCompound(McCompat.nbt().getCompound(saved, "players"), player.toString()),
                "targetMeta");
        assertTrue(metas.contains("minecraft:diamond#3"));
        CompoundTag first = McCompat.nbt().getCompound(metas, "minecraft:diamond#3");
        assertEquals(7, McCompat.nbt().getInt(first, "priority"));
        assertEquals("star pick", McCompat.nbt().getString(first, "label"));
        assertTrue(McCompat.nbt().getBoolean(first, "pinned"));
        assertTrue(metas.contains("minecraft:emerald"));
        assertEquals(2, McCompat.nbt().getInt(
                McCompat.nbt().getCompound(metas, "minecraft:emerald"), "priority"));

        MilestoneData loaded = MilestoneData.load(saved);
        MilestoneData.PlayerData loadedPd = loaded.playerOrNull(player);
        assertNotNull(loadedPd);
        assertEquals(Map.of("minecraft:diamond#3", pinned, "minecraft:emerald", plain), loadedPd.targetMeta);
    }

    @Test
    void targetMetaLoaderSkipsMalformedElementsAndKeepsValidSiblings() {
        UUID player = PLAYER_A;
        MilestoneData original = new MilestoneData();
        MilestoneData.PlayerData pd = original.player(player);
        TargetKey diamond = TargetKey.of("minecraft:diamond", 3);
        MilestoneRecord record = new MilestoneRecord(4321, 7L, 1795600000000L);
        pd.targets.add(diamond);
        pd.records.put(diamond, record);
        pd.lastKnownName = "Alice";
        pd.presetState.put("vanilla_endgame", new PresetState(true, 4));

        CompoundTag saved = original.save(new CompoundTag());
        CompoundTag savedPd = McCompat.nbt().getCompound(
                McCompat.nbt().getCompound(saved, "players"), player.toString());

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
        metas.put("missing_fields", new CompoundTag());
        metas.put("scalar", StringTag.valueOf("nope"));
        savedPd.put("targetMeta", metas);

        MilestoneData loaded = MilestoneData.load(saved);
        MilestoneData.PlayerData loadedPd = loaded.playerOrNull(player);
        assertNotNull(loadedPd);
        assertEquals(Map.of(
                "minecraft:diamond#3", new TargetMeta(4, "valid", true),
                "minecraft:emerald", new TargetMeta(0, "", false)), loadedPd.targetMeta);
        assertNotNull(loaded.loadReport());
        assertEquals(5, loaded.loadReport().elementSkipCount());
        assertFalse(loaded.loadReport().totalFailure());
        assertEquals(pd.targets, loadedPd.targets);
        assertEquals(pd.records, loadedPd.records);
        assertEquals("Alice", loadedPd.lastKnownName);
        assertEquals(pd.presetState, loadedPd.presetState);
    }

    @Test
    void removingTargetMetaFromSavedTagIsANonDestructiveDowngrade() {
        UUID player = PLAYER_A;
        MilestoneData original = new MilestoneData();
        MilestoneData.PlayerData pd = original.player(player);
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
        original.recordWorldFirstIfAbsent(diamond, player, "Alice", record);

        CompoundTag saved = original.save(new CompoundTag());
        MilestoneData before = MilestoneData.load(saved.copy());

        // Simulate a 1.4.0 read: the unknown targetMeta key is dropped, nothing else changes.
        CompoundTag downgraded = saved.copy();
        CompoundTag players = McCompat.nbt().getCompound(downgraded, "players");
        CompoundTag playerTag = McCompat.nbt().getCompound(players, player.toString());
        assertTrue(playerTag.contains("targetMeta"));
        playerTag.remove("targetMeta");
        players.put(player.toString(), playerTag);
        downgraded.put("players", players);

        MilestoneData after = MilestoneData.load(downgraded);
        MilestoneData.PlayerData beforePd = before.playerOrNull(player);
        MilestoneData.PlayerData afterPd = after.playerOrNull(player);
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
    /** Loads one corrupted player beside a valid one, asserting exactly one recorded skip. */
    private static MilestoneData loadWithValidSibling(UUID badPlayer, Tag badEntry,
                                                      UUID goodPlayer, CompoundTag goodEntry) {
        CompoundTag players = new CompoundTag();
        players.put(badPlayer.toString(), badEntry);
        players.put(goodPlayer.toString(), goodEntry);
        CompoundTag root = new CompoundTag();
        root.put("players", players);
        root.putInt("schemaVersion", 1);

        MilestoneData loaded = MilestoneData.load(root);
        assertNotNull(loaded.loadReport());
        assertEquals(1, loaded.loadReport().elementSkipCount(),
                "one wrong-typed container must be counted so RB-1/F-140-2 can evacuate");
        assertTrue(loaded.loadReport().toOutcome().requiresEvacuation());
        return loaded;
    }

    @Test
    void loadInternalNonListTargetsRecordSkipAndKeepValidSiblings() {
        UUID badPlayer = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID goodPlayer = UUID.fromString("44444444-4444-4444-4444-444444444444");

        CompoundTag badEntry = validParityPlayerTag();
        badEntry.putString("targets", "minecraft:diamond"); // string instead of list
        MilestoneData loaded = loadWithValidSibling(badPlayer, badEntry, goodPlayer, validParityPlayerTag());

        MilestoneData.PlayerData badPd = loaded.playerOrNull(badPlayer);
        assertNotNull(badPd);
        assertTrue(badPd.targets.isEmpty(), "wrong-typed targets must not be read");
        assertEquals(Map.of(TargetKey.of("minecraft:coal"), MilestoneRecord.legacy(123)), badPd.records);
        assertEquals(Set.of("minecraft:emerald"), badPd.carriedFromCreative);
        assertEquals("Alice", badPd.lastKnownName);
        assertTrue(badPd.firstJoinHintShown);
        assertTrue(badPd.muteBroadcasts);

        MilestoneData.PlayerData goodPd = loaded.playerOrNull(goodPlayer);
        assertNotNull(goodPd);
        assertEquals(List.of(TargetKey.of("minecraft:diamond")), List.copyOf(goodPd.targets));
        assertEquals(Map.of(TargetKey.of("minecraft:coal"), MilestoneRecord.legacy(123)), goodPd.records);
    }

    @Test
    void loadInternalNonCompoundRecordsRecordSkipAndKeepValidSiblings() {
        UUID badPlayer = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID goodPlayer = UUID.fromString("44444444-4444-4444-4444-444444444444");

        CompoundTag badEntry = validParityPlayerTag();
        badEntry.putString("records", "garbage"); // string instead of compound
        MilestoneData loaded = loadWithValidSibling(badPlayer, badEntry, goodPlayer, validParityPlayerTag());

        MilestoneData.PlayerData badPd = loaded.playerOrNull(badPlayer);
        assertNotNull(badPd);
        assertEquals(List.of(TargetKey.of("minecraft:diamond")), List.copyOf(badPd.targets));
        assertTrue(badPd.records.isEmpty(), "wrong-typed records must not be read");
        assertEquals(Set.of("minecraft:emerald"), badPd.carriedFromCreative);
        assertEquals("Alice", badPd.lastKnownName);
        assertTrue(badPd.firstJoinHintShown);
        assertTrue(badPd.muteBroadcasts);

        MilestoneData.PlayerData goodPd = loaded.playerOrNull(goodPlayer);
        assertNotNull(goodPd);
        assertEquals(List.of(TargetKey.of("minecraft:diamond")), List.copyOf(goodPd.targets));
        assertEquals(Map.of(TargetKey.of("minecraft:coal"), MilestoneRecord.legacy(123)), goodPd.records);
    }

    @Test
    void loadInternalNonCompoundPlayerEntryRecordSkipAndKeepValidSiblingPlayer() {
        UUID badPlayer = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID goodPlayer = UUID.fromString("44444444-4444-4444-4444-444444444444");

        MilestoneData loaded = loadWithValidSibling(badPlayer, IntTag.valueOf(7),
                goodPlayer, validParityPlayerTag());

        assertNull(loaded.playerOrNull(badPlayer), "non-compound player entry must be skipped");

        MilestoneData.PlayerData goodPd = loaded.playerOrNull(goodPlayer);
        assertNotNull(goodPd);
        assertEquals(List.of(TargetKey.of("minecraft:diamond")), List.copyOf(goodPd.targets));
        assertEquals(Map.of(TargetKey.of("minecraft:coal"), MilestoneRecord.legacy(123)), goodPd.records);
        assertEquals(Set.of("minecraft:emerald"), goodPd.carriedFromCreative);
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
        CompoundTag saved = original.save(new CompoundTag());
        int savedSchemaVersion = McCompat.nbt().getInt(saved, "schemaVersion");
        saved.remove("firstsHolderHistory");

        MilestoneData loaded = MilestoneData.load(saved);
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

        CompoundTag saved = original.save(new CompoundTag());
        CompoundTag history = McCompat.nbt().getCompound(saved, "firstsHolderHistory");
        assertTrue(history.contains(encodedKey));
        ListTag encodedList = McCompat.nbt().getList(history, encodedKey);
        assertEquals(2, encodedList.size());
        CompoundTag encodedFirst = (CompoundTag) encodedList.get(0);
        assertEquals(PLAYER_A.toString(), McCompat.nbt().getString(encodedFirst, "uuid"));
        assertEquals("Alice", McCompat.nbt().getString(encodedFirst, "name"));
        assertEquals(111, McCompat.nbt().getInt(encodedFirst, "playTicks"));
        assertEquals(1795600000000L, McCompat.nbt().getLong(encodedFirst, "epochMillis"));
        CompoundTag encodedSecond = (CompoundTag) encodedList.get(1);
        assertEquals(playerB.toString(), McCompat.nbt().getString(encodedSecond, "uuid"));
        assertEquals("Bob", McCompat.nbt().getString(encodedSecond, "name"));
        assertEquals(222, McCompat.nbt().getInt(encodedSecond, "playTicks"));
        assertEquals(1795600001000L, McCompat.nbt().getLong(encodedSecond, "epochMillis"));
        assertEquals(FirstsHolderHistory.HISTORY_LIMIT,
                McCompat.nbt().getList(history, rotating.toEncodedString()).size());

        MilestoneData loaded = MilestoneData.load(saved);
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

        CompoundTag saved = original.save(new CompoundTag());
        int savedSchemaVersion = McCompat.nbt().getInt(saved, "schemaVersion");
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
        CompoundTag wrongType = goodB.copy();
        wrongType.putString("playTicks", "not an int");

        ListTag diamondList = new ListTag();
        diamondList.add(goodA);
        diamondList.add(badUuid);
        diamondList.add(negativeTicks);
        diamondList.add(wrongType);
        ListTag emeraldList = new ListTag();
        emeraldList.add(goodB);
        ListTag badKeyList = new ListTag();
        badKeyList.add(goodB.copy());
        CompoundTag history = new CompoundTag();
        history.put(diamond.toEncodedString(), diamondList);
        history.put("minecraft:emerald", emeraldList);
        history.put("not a valid key", badKeyList);
        saved.put("firstsHolderHistory", history);

        MilestoneData loaded = MilestoneData.load(saved);
        assertEquals(List.of(validA), loaded.holderHistory(diamond.toEncodedString()));
        assertEquals(Map.of(
                diamond.toEncodedString(), List.of(validA),
                "minecraft:emerald", List.of(validB)), loaded.firstsHolderHistory());
        assertNotNull(loaded.loadReport());
        // bad UUID + negative playTicks + wrong-typed ticks + bad encoded key
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

        CompoundTag saved = original.save(new CompoundTag());
        MilestoneData before = MilestoneData.load(saved);

        // Simulate a 1.4.0 read: only the unknown root key is dropped.
        CompoundTag downgraded = saved.copy();
        assertTrue(downgraded.contains("firstsHolderHistory"));
        downgraded.remove("firstsHolderHistory");

        MilestoneData after = MilestoneData.load(downgraded);
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

}
