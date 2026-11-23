package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.api.WorldFirstRecord;
import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackerTest {
    private static final UUID PLAYER_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAYER_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void parseDefaultTargetsAcceptsValidEntriesAndIgnoresInvalidOrAir() {
        List<String> raw = Arrays.asList(
                "minecraft:diamond",
                "minecraft:diamond#100",
                "minecraft:diamond", // duplicate
                "othermod:custom_ingot#16",
                "minecraft:air",
                "minecraft:air#5",
                "diamond", // missing namespace
                "minecraft:stone#0",
                "minecraft:stone#1000001",
                "minecraft:bad id",
                "#c:ingots/iron",
                "#c:ingots/iron#10",
                "",
                null
        );

        Set<TargetKey> parsed = Tracker.parseDefaultTargets(raw);
        assertEquals(List.of(
                TargetKey.of("minecraft:diamond"),
                TargetKey.of("minecraft:diamond", 100),
                TargetKey.of("othermod:custom_ingot", 16)
        ), List.copyOf(parsed));
    }

    @Test
    void effectiveTargetsCombinesDefaultsAndPersonalTargetsWithoutDuplicates() {
        Set<TargetKey> defaults = new LinkedHashSet<>(List.of(
                TargetKey.of("minecraft:diamond"),
                TargetKey.of("minecraft:netherite_ingot")));
        assertEquals(defaults, Tracker.effectiveTargets(defaults, null));

        MilestoneData.PlayerData pd = new MilestoneData.PlayerData();
        pd.targets.add(TargetKey.of("minecraft:netherite_ingot"));
        pd.targets.add(TargetKey.of("minecraft:diamond", 64));

        Set<TargetKey> effective = Tracker.effectiveTargets(defaults, pd);
        assertEquals(List.of(
                TargetKey.of("minecraft:diamond"),
                TargetKey.of("minecraft:netherite_ingot"),
                TargetKey.of("minecraft:diamond", 64)
        ), List.copyOf(effective));
    }

    @Test
    void shouldScanOnTickFiresExactlyOncePerInterval() {
        for (int interval : new int[]{1, 5, 20}) {
            for (UUID playerId : List.of(PLAYER_A, PLAYER_B, UUID.randomUUID())) {
                int hits = 0;
                for (int tick = 0; tick < interval; tick++) {
                    if (Tracker.shouldScanOnTick(playerId, tick, interval)) {
                        hits++;
                    }
                }
                assertEquals(1, hits, "expected exactly one scan in interval " + interval + " for " + playerId);
            }
        }
    }

    @Test
    void recordingPausedAndLeavingCreativeOrSpectatorTransitions() {
        assertFalse(Tracker.isRecordingPaused(false, true, false));
        assertFalse(Tracker.isRecordingPaused(false, false, true));
        assertFalse(Tracker.isRecordingPaused(true, false, false));
        assertTrue(Tracker.isRecordingPaused(true, true, false));
        assertTrue(Tracker.isRecordingPaused(true, false, true));

        assertTrue(Tracker.isLeavingCreativeOrSpectator(GameType.CREATIVE, GameType.SURVIVAL));
        assertTrue(Tracker.isLeavingCreativeOrSpectator(GameType.CREATIVE, GameType.ADVENTURE));
        assertTrue(Tracker.isLeavingCreativeOrSpectator(GameType.SPECTATOR, GameType.SURVIVAL));
        assertTrue(Tracker.isLeavingCreativeOrSpectator(GameType.SPECTATOR, GameType.ADVENTURE));

        assertFalse(Tracker.isLeavingCreativeOrSpectator(GameType.CREATIVE, GameType.SPECTATOR));
        assertFalse(Tracker.isLeavingCreativeOrSpectator(GameType.SPECTATOR, GameType.CREATIVE));
        assertFalse(Tracker.isLeavingCreativeOrSpectator(GameType.SURVIVAL, GameType.CREATIVE));
        assertFalse(Tracker.isLeavingCreativeOrSpectator(GameType.SURVIVAL, GameType.ADVENTURE));
        assertFalse(Tracker.isLeavingCreativeOrSpectator(GameType.SURVIVAL, GameType.SURVIVAL));
    }

    @Test
    void carriedFromCreativeReturnsEmptyWhenDisabledOrMissing() {
        assertTrue(Tracker.carriedFromCreative(null, true).isEmpty());
        MilestoneData.PlayerData pd = new MilestoneData.PlayerData();
        pd.carriedFromCreative.add("minecraft:diamond");
        assertTrue(Tracker.carriedFromCreative(pd, false).isEmpty());
        assertEquals(Set.of("minecraft:diamond"), Tracker.carriedFromCreative(pd, true));
    }

    @Test
    void pendingCraftChecksMergeMaxCountPerItemAndDrainOnTake() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        try {
            Tracker.queueCraftCheck(a, "minecraft:diamond", 2);
            Tracker.queueCraftCheck(a, "minecraft:diamond", 5);
            Tracker.queueCraftCheck(a, "minecraft:diamond", 3);
            Tracker.queueCraftCheck(a, "minecraft:iron_ingot", 1);
            Tracker.queueCraftCheck(b, "minecraft:gold_ingot", 4);

            Map<String, Integer> drainedA = Tracker.takePendingCraftChecks(a);
            assertEquals(Map.of("minecraft:diamond", 5, "minecraft:iron_ingot", 1), drainedA);
            assertNull(Tracker.takePendingCraftChecks(a), "subsequent take must return null");

            Map<String, Integer> drainedB = Tracker.takePendingCraftChecks(b);
            assertEquals(Map.of("minecraft:gold_ingot", 4), drainedB);
        } finally {
            Tracker.takePendingCraftChecks(a);
            Tracker.takePendingCraftChecks(b);
        }
    }

    @Test
    void inventoryScanAccumulatesNeededStacksIgnoresCarriedAndFindsAchievedInOrder() {
        TargetKey diamond1 = TargetKey.of("minecraft:diamond", 1);
        TargetKey diamond10 = TargetKey.of("minecraft:diamond", 10);
        TargetKey diamond64 = TargetKey.of("minecraft:diamond", 64);
        TargetKey emerald1 = TargetKey.of("minecraft:emerald", 1);
        TargetKey iron1 = TargetKey.of("minecraft:iron_ingot", 1);

        Set<TargetKey> pending = Set.of(diamond64, diamond10, diamond1, emerald1, iron1);
        Set<String> carried = new LinkedHashSet<>(List.of("minecraft:emerald", "minecraft:gold_ingot"));

        Set<String> needed = Tracker.pendingItemIdsToCount(pending, carried);
        assertEquals(Set.of("minecraft:diamond", "minecraft:iron_ingot"), needed);

        Map<String, Integer> counts = new HashMap<>();
        Set<String> stillCarried = new HashSet<>();
        Tracker.accumulateItem("minecraft:diamond", 4, carried, stillCarried, needed, counts);
        Tracker.accumulateItem("minecraft:diamond", 8, carried, stillCarried, needed, counts);
        Tracker.accumulateItem("minecraft:emerald", 10, carried, stillCarried, needed, counts); // carried -> excluded from counts
        Tracker.accumulateItem("minecraft:dirt", 64, carried, stillCarried, needed, counts); // unneeded -> ignored

        assertEquals(Map.of("minecraft:diamond", 12), counts);
        assertEquals(Set.of("minecraft:emerald"), stillCarried);

        List<TargetKey> achieved = Tracker.findAchievedTargets(pending, carried, counts);
        assertEquals(List.of(diamond1, diamond10), achieved);

        // Creative-carried items no longer in inventory (gold_ingot) are forgotten; emerald stays carried
        assertTrue(carried.retainAll(stillCarried));
        assertEquals(Set.of("minecraft:emerald"), carried);
    }

    @Test
    void evaluateItemAcquisitionClearsCreativeCarryOnlyWhenPriorCountIsZero() {
        Set<TargetKey> effective = Set.of(
                TargetKey.of("minecraft:diamond", 1),
                TargetKey.of("minecraft:diamond", 4),
                TargetKey.of("minecraft:diamond", 10));
        Set<String> carried = Set.of("minecraft:diamond", "minecraft:stone");

        // Pickup of 5 diamonds when player held 0 beforehand (totalInPossession == 5, priorCount == 0):
        // clears creative carry and records targets <= 5.
        Tracker.ItemAcquisitionOutcome cleared = Tracker.evaluateItemAcquisition(
                "minecraft:diamond", 5, 5, true, effective, null, carried);
        assertTrue(cleared.clearedCreativeCarry());
        assertEquals(List.of(TargetKey.of("minecraft:diamond", 1), TargetKey.of("minecraft:diamond", 4)),
                cleared.achieved());

        // Pickup of 5 diamonds when player still held 1 creative diamond beforehand (totalInPossession == 6, priorCount == 1):
        // does not clear creative carry and records nothing.
        Tracker.ItemAcquisitionOutcome blocked = Tracker.evaluateItemAcquisition(
                "minecraft:diamond", 6, 5, true, effective, null, carried);
        assertFalse(blocked.clearedCreativeCarry());
        assertTrue(blocked.achieved().isEmpty());

        // Non-target item carried from creative still clears when priorCount == 0
        Tracker.ItemAcquisitionOutcome clearedNonTarget = Tracker.evaluateItemAcquisition(
                "minecraft:stone", 3, 3, true, effective, null, carried);
        assertTrue(clearedNonTarget.clearedCreativeCarry());
        assertTrue(clearedNonTarget.achieved().isEmpty());

        // alreadyAddedToInventory == false with existing creative stack (priorCount == totalInPossession == 1): blocked
        Tracker.ItemAcquisitionOutcome blockedPreAdd = Tracker.evaluateItemAcquisition(
                "minecraft:diamond", 1, 1, false, effective, null, carried);
        assertFalse(blockedPreAdd.clearedCreativeCarry());
        assertTrue(blockedPreAdd.achieved().isEmpty());
    }

    @Test
    void evaluateItemAcquisitionRespectsAlreadyAddedFlagAndSkipsRecordedTargets() {
        TargetKey diamond1 = TargetKey.of("minecraft:diamond", 1);
        TargetKey diamond10 = TargetKey.of("minecraft:diamond", 10);
        TargetKey diamond64 = TargetKey.of("minecraft:diamond", 64);
        Set<TargetKey> effective = Set.of(diamond64, diamond10, diamond1);

        // Deferred craft/smelt check where result stack was Q-dropped (alreadyAddedToInventory=true, possession=0):
        // must not count minCountFromEvent.
        Tracker.ItemAcquisitionOutcome dropped = Tracker.evaluateItemAcquisition(
                "minecraft:diamond", 0, 64, true, effective, null, Set.of());
        assertFalse(dropped.clearedCreativeCarry());
        assertTrue(dropped.achieved().isEmpty());

        // alreadyAddedToInventory == false uses max(totalInPossession, minCountFromEvent) and skips recorded targets
        MilestoneData.PlayerData pd = new MilestoneData.PlayerData();
        pd.records.put(diamond1, new MilestoneRecord(100, 1L, 1000L));
        Tracker.ItemAcquisitionOutcome recorded = Tracker.evaluateItemAcquisition(
                "minecraft:diamond", 0, 10, false, effective, pd, Set.of());
        assertEquals(List.of(diamond10), recorded.achieved());
    }

    @Test
    void applyRecordRecordsOnceUpdatesLastKnownNameAndTracksWorldFirstPerKey() {
        MilestoneData data = new MilestoneData();
        TargetKey diamond1 = TargetKey.of("minecraft:diamond", 1);
        TargetKey diamond64 = TargetKey.of("minecraft:diamond", 64);
        MilestoneRecord recA = new MilestoneRecord(1200, 5L, 1_700_000_000_000L);
        MilestoneRecord recB = new MilestoneRecord(2400, 8L, 1_700_000_100_000L);

        // First player records diamond#1 -> world first
        assertEquals(Tracker.RecordOutcome.RECORDED_WORLD_FIRST,
                Tracker.applyRecord(data, PLAYER_A, "Alice", diamond1, recA));
        MilestoneData.PlayerData pdA = data.playerOrNull(PLAYER_A);
        assertNotNull(pdA);
        assertEquals("Alice", pdA.lastKnownName);
        assertEquals(recA, pdA.records.get(diamond1));
        WorldFirstRecord wf1 = data.worldFirstOrNull(diamond1);
        assertNotNull(wf1);
        assertEquals(PLAYER_A, wf1.playerUuid());
        assertEquals("Alice", wf1.playerName());

        // Duplicate record for same player & key is a no-op
        assertEquals(Tracker.RecordOutcome.ALREADY_RECORDED,
                Tracker.applyRecord(data, PLAYER_A, "AliceRenamed", diamond1, recB));
        assertEquals(recA, pdA.records.get(diamond1));
        assertEquals("Alice", pdA.lastKnownName);

        // Second player records diamond#1 -> recorded, not world first
        assertEquals(Tracker.RecordOutcome.RECORDED,
                Tracker.applyRecord(data, PLAYER_B, "Bob", diamond1, recB));
        assertEquals(recB, data.playerOrNull(PLAYER_B).records.get(diamond1));
        assertEquals(PLAYER_A, data.worldFirstOrNull(diamond1).playerUuid());

        // Second player records diamond#64 -> independent world first
        assertEquals(Tracker.RecordOutcome.RECORDED_WORLD_FIRST,
                Tracker.applyRecord(data, PLAYER_B, "Bob", diamond64, recB));
        assertEquals(PLAYER_B, data.worldFirstOrNull(diamond64).playerUuid());
    }

    @Test
    void applyRecordSkipsNewWorldFirstWhenWorldFirstDisabledWhilePreservingExisting() {
        MilestoneData data = new MilestoneData();
        TargetKey emerald = TargetKey.of("minecraft:emerald", 1);
        TargetKey diamond = TargetKey.of("minecraft:diamond", 1);
        MilestoneRecord recA = new MilestoneRecord(1000, 2L, 1_700_000_000_000L);
        MilestoneRecord recB = new MilestoneRecord(2000, 4L, 1_700_000_100_000L);

        // Pre-existing world first recorded while enabled
        assertEquals(Tracker.RecordOutcome.RECORDED_WORLD_FIRST,
                Tracker.applyRecord(data, PLAYER_A, "Alice", emerald, recA, true));
        assertNotNull(data.worldFirstOrNull(emerald));

        // Recording a new item while worldFirstEnabled == false records for player without creating world first
        assertEquals(Tracker.RecordOutcome.RECORDED,
                Tracker.applyRecord(data, PLAYER_B, "Bob", diamond, recB, false));
        assertEquals(recB, data.playerOrNull(PLAYER_B).records.get(diamond));
        assertNull(data.worldFirstOrNull(diamond));
        assertNotNull(data.worldFirstOrNull(emerald));
    }
}
