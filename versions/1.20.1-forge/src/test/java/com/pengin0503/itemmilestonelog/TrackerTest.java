package com.pengin0503.itemmilestonelog;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pengin0503.itemmilestonelog.api.ItemMilestoneLogApi;
import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.MilestoneRecordedEvent;
import com.pengin0503.itemmilestonelog.api.PresetCompletedEvent;
import com.pengin0503.itemmilestonelog.api.RecordCause;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.api.WorldFirstRecord;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import net.minecraftforge.eventbus.api.Event;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackerTest {
    private static final UUID PLAYER_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAYER_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void parseDefaultTargetsAcceptsValidItemAndTagEntriesAndIgnoresInvalidOrAir() {
        List<String> raw = Arrays.asList(
                "minecraft:diamond",
                "minecraft:diamond#100",
                "minecraft:diamond", // duplicate
                "othermod:custom_ingot#16",
                "#c:ingots/iron",
                "#c:ingots/iron#10",
                "#nonexistent:future_tag",
                "minecraft:air",
                "minecraft:air#5",
                "diamond", // missing namespace
                "minecraft:stone#0",
                "minecraft:stone#1000001",
                "minecraft:bad id",
                "#",
                "##c:x",
                "#c:x#",
                "#c:x#0",
                "#c:x#abc",
                "#:x",
                "#c:",
                "c:x#1#2",
                "",
                null
        );

        Set<TargetKey> parsed = Tracker.parseDefaultTargets(raw);
        assertEquals(List.of(
                TargetKey.of("minecraft:diamond"),
                TargetKey.of("minecraft:diamond", 100),
                TargetKey.of("othermod:custom_ingot", 16),
                TargetKey.of("#c:ingots/iron"),
                TargetKey.of("#c:ingots/iron", 10),
                TargetKey.of("#nonexistent:future_tag")
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
    void evaluateItemAcquisitionSkipsCountingForNonTargetOrAlreadyRecordedItems() {
        TargetKey diamond1 = TargetKey.of("minecraft:diamond", 1);
        TargetKey diamond10 = TargetKey.of("minecraft:diamond", 10);
        TargetKey gold1 = TargetKey.of("minecraft:gold_ingot", 1);
        TargetKey emerald16 = TargetKey.of("minecraft:emerald", 16);

        Set<TargetKey> defaults = Set.of(diamond1, diamond10, gold1);
        MilestoneData.PlayerData pd = new MilestoneData.PlayerData();
        pd.targets.add(emerald16);
        pd.targets.add(diamond10); // overlaps with defaults
        pd.targets.add(gold1);
        pd.records.put(diamond1, new MilestoneRecord(100, 1L, 1000L));
        pd.records.put(gold1, new MilestoneRecord(200, 1L, 2000L));

        AtomicInteger countCalls = new AtomicInteger();

        // Non-target item not in carriedFromCreative must not trigger possession counting
        Tracker.ItemAcquisitionOutcome nonTarget = Tracker.evaluateItemAcquisition(
                "minecraft:dirt",
                () -> {
                    countCalls.incrementAndGet();
                    return 64;
                },
                1, true, defaults, pd, Set.of());
        assertEquals(0, countCalls.get());
        assertFalse(nonTarget.clearedCreativeCarry());
        assertTrue(nonTarget.achieved().isEmpty());
        assertTrue(nonTarget.pendingForItem().isEmpty());

        // Item whose targets are all already recorded must also skip possession counting
        Tracker.ItemAcquisitionOutcome alreadyRecorded = Tracker.evaluateItemAcquisition(
                "minecraft:gold_ingot",
                () -> {
                    countCalls.incrementAndGet();
                    return 64;
                },
                1, true, defaults, pd, Set.of());
        assertEquals(0, countCalls.get());
        assertFalse(alreadyRecorded.clearedCreativeCarry());
        assertTrue(alreadyRecorded.achieved().isEmpty());
        assertTrue(alreadyRecorded.pendingForItem().isEmpty());

        // Personal-only target triggers counting and records once achieved
        Tracker.ItemAcquisitionOutcome personal = Tracker.evaluateItemAcquisition(
                "minecraft:emerald",
                () -> {
                    countCalls.incrementAndGet();
                    return 16;
                },
                16, true, defaults, pd, Set.of());
        assertEquals(1, countCalls.get());
        assertEquals(List.of(emerald16), personal.achieved());
        assertEquals(List.of(emerald16), personal.pendingForItem());

        // Overlapping target in defaults and personal targets is not duplicated and skips recorded keys
        Tracker.ItemAcquisitionOutcome overlapping = Tracker.evaluateItemAcquisition(
                "minecraft:diamond",
                () -> {
                    countCalls.incrementAndGet();
                    return 12;
                },
                12, true, defaults, pd, Set.of());
        assertEquals(2, countCalls.get());
        assertEquals(List.of(diamond10), overlapping.achieved());
        assertEquals(List.of(diamond10), overlapping.pendingForItem());
    }

    @Test
    void evaluateItemAcquisitionStillCountsAndClearsCreativeCarryForNonTargetItem() {
        Set<TargetKey> defaults = Set.of(TargetKey.of("minecraft:diamond", 1));
        MilestoneData.PlayerData pd = new MilestoneData.PlayerData();
        pd.targets.add(TargetKey.of("minecraft:emerald", 1));
        pd.carriedFromCreative.add("minecraft:cobblestone");

        AtomicInteger countCalls = new AtomicInteger();

        // Non-target item in carriedFromCreative with pre-pickup count == 0 must still count and clear the mark
        Tracker.ItemAcquisitionOutcome cleared = Tracker.evaluateItemAcquisition(
                "minecraft:cobblestone",
                () -> {
                    countCalls.incrementAndGet();
                    return 4;
                },
                4, true, defaults, pd, pd.carriedFromCreative);
        assertEquals(1, countCalls.get());
        assertTrue(cleared.clearedCreativeCarry());
        assertTrue(cleared.achieved().isEmpty());
        assertTrue(cleared.pendingForItem().isEmpty());

        // Non-target item in carriedFromCreative with pre-pickup count > 0 counts and keeps the mark
        Tracker.ItemAcquisitionOutcome kept = Tracker.evaluateItemAcquisition(
                "minecraft:cobblestone",
                () -> {
                    countCalls.incrementAndGet();
                    return 5;
                },
                4, true, defaults, pd, pd.carriedFromCreative);
        assertEquals(2, countCalls.get());
        assertFalse(kept.clearedCreativeCarry());
        assertTrue(kept.achieved().isEmpty());
        assertTrue(kept.pendingForItem().isEmpty());
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

    @Test
    void evaluateProgressAlertsHandlesBaselineThresholdsCreativeCarryAndDisabledSwitch() {
        MilestoneData data = new MilestoneData();
        TargetKey diamond100 = TargetKey.of("minecraft:diamond", 100);
        TargetKey emerald50 = TargetKey.of("minecraft:emerald", 50);
        TargetKey gold5 = TargetKey.of("minecraft:gold_ingot", 5); // below minCount 10
        TargetKey iron1 = TargetKey.of("minecraft:iron_ingot", 1); // single item
        List<TargetKey> candidates = List.of(diamond100, emerald50, gold5, iron1);
        List<Integer> thresholds = List.of(50, 90);

        // Disabled switch does nothing and writes no baseline
        List<Tracker.ProgressAlertNotification> disabled = Tracker.evaluateProgressAlerts(
                data, PLAYER_A, candidates, Set.of(), Map.of("minecraft:diamond", 60),
                false, thresholds, 10);
        assertTrue(disabled.isEmpty());
        assertNull(data.playerOrNull(PLAYER_A));

        // First evaluation establishes baseline silently (diamond=60 -> 50, emerald carried -> 0)
        List<Tracker.ProgressAlertNotification> baseline = Tracker.evaluateProgressAlerts(
                data, PLAYER_A, candidates, Set.of("minecraft:emerald"),
                Map.of("minecraft:diamond", 60, "minecraft:emerald", 45, "minecraft:gold_ingot", 3),
                true, thresholds, 10);
        assertTrue(baseline.isEmpty());
        MilestoneData.PlayerData pd = data.playerOrNull(PLAYER_A);
        assertNotNull(pd);
        assertEquals(Map.of(diamond100, 50, emerald50, 0), pd.progressNotified);

        // Creative-carried emerald stays at 0 (no alert even though raw map says 45); diamond hits 92 -> alerts at 90%
        List<Tracker.ProgressAlertNotification> firstAlert = Tracker.evaluateProgressAlerts(
                data, PLAYER_A, candidates, Set.of("minecraft:emerald"),
                Map.of("minecraft:diamond", 92, "minecraft:emerald", 45),
                true, thresholds, 10);
        assertEquals(List.of(new Tracker.ProgressAlertNotification(diamond100, 92, 100, 92)), firstAlert);
        assertEquals(90, pd.progressNotified.get(diamond100));
        assertEquals(0, pd.progressNotified.get(emerald50));

        // Once emerald leaves creative carry and player reaches 45/50 (90%): jumps past 50 and 90 -> 1 alert for 90%
        List<Tracker.ProgressAlertNotification> multiCross = Tracker.evaluateProgressAlerts(
                data, PLAYER_A, candidates, Set.of(),
                Map.of("minecraft:diamond", 100, "minecraft:emerald", 45),
                true, thresholds, 10);
        assertEquals(List.of(new Tracker.ProgressAlertNotification(emerald50, 45, 50, 90)), multiCross);
        assertEquals(90, pd.progressNotified.get(emerald50));

        // Confirming record removes progressNotified for that key only
        Tracker.applyRecord(data, PLAYER_A, "Alice", diamond100, new MilestoneRecord(1000, 1L, 1000L));
        assertFalse(pd.progressNotified.containsKey(diamond100));
        assertEquals(90, pd.progressNotified.get(emerald50));
    }

    @Test
    void cleanupProgressNotifiedRemovesRecordedAndNonEffectiveKeysOnLogin() {
        MilestoneData.PlayerData pd = new MilestoneData.PlayerData();
        TargetKey keepPersonal = TargetKey.of("minecraft:diamond", 100);
        TargetKey keepDefault = TargetKey.of("minecraft:emerald", 100);
        TargetKey alreadyRecorded = TargetKey.of("minecraft:iron_ingot", 100);
        TargetKey staleRemoved = TargetKey.of("minecraft:coal", 100);

        pd.targets.add(keepPersonal);
        pd.targets.add(alreadyRecorded);
        pd.records.put(alreadyRecorded, new MilestoneRecord(500, 1L, 1000L));

        pd.progressNotified.put(keepPersonal, 50);
        pd.progressNotified.put(keepDefault, 0);
        pd.progressNotified.put(alreadyRecorded, 90);
        pd.progressNotified.put(staleRemoved, 50);

        Set<TargetKey> effective = Tracker.effectiveTargets(Set.of(keepDefault), pd);
        assertTrue(Tracker.cleanupProgressNotified(pd, effective));
        assertEquals(Map.of(keepPersonal, 50, keepDefault, 0), pd.progressNotified);
        assertFalse(Tracker.cleanupProgressNotified(pd, effective));
    }

    @Test
    void evaluateProgressAlertsSuppressesNotificationsWhileMutedButKeepsUpdatingProgressNotified() {
        MilestoneData data = new MilestoneData();
        MilestoneData.PlayerData pd = data.player(PLAYER_A);
        pd.muteProgressAlerts = true;

        TargetKey diamond100 = TargetKey.of("minecraft:diamond", 100);
        List<TargetKey> candidates = List.of(diamond100);
        List<Integer> thresholds = List.of(50, 90);

        // Baseline while muted stores 0 silently
        assertTrue(Tracker.evaluateProgressAlerts(
                data, PLAYER_A, candidates, Set.of(), Map.of("minecraft:diamond", 0),
                true, thresholds, 10).isEmpty());
        assertEquals(0, pd.progressNotified.get(diamond100));

        // Crossing 50% while muted suppresses the alert but still advances progressNotified to 50
        assertTrue(Tracker.evaluateProgressAlerts(
                data, PLAYER_A, candidates, Set.of(), Map.of("minecraft:diamond", 55),
                true, thresholds, 10).isEmpty());
        assertEquals(50, pd.progressNotified.get(diamond100));

        // Unmuting does not dump the 50% alert crossed while muted
        pd.muteProgressAlerts = false;
        assertTrue(Tracker.evaluateProgressAlerts(
                data, PLAYER_A, candidates, Set.of(), Map.of("minecraft:diamond", 55),
                true, thresholds, 10).isEmpty());
        assertEquals(50, pd.progressNotified.get(diamond100));

        // Subsequent threshold (90%) alerts normally once unmuted
        List<Tracker.ProgressAlertNotification> unmutedAlert = Tracker.evaluateProgressAlerts(
                data, PLAYER_A, candidates, Set.of(), Map.of("minecraft:diamond", 90),
                true, thresholds, 10);
        assertEquals(List.of(new Tracker.ProgressAlertNotification(diamond100, 90, 100, 90)), unmutedAlert);
        assertEquals(90, pd.progressNotified.get(diamond100));

        // Config progressAlerts == false sends nothing regardless of mute state
        TargetKey emerald100 = TargetKey.of("minecraft:emerald", 100);
        pd.progressNotified.put(emerald100, 0);
        pd.muteProgressAlerts = false;
        assertTrue(Tracker.evaluateProgressAlerts(
                data, PLAYER_A, List.of(emerald100), Set.of(), Map.of("minecraft:emerald", 60),
                false, thresholds, 10).isEmpty());
        pd.muteProgressAlerts = true;
        assertTrue(Tracker.evaluateProgressAlerts(
                data, PLAYER_A, List.of(emerald100), Set.of(), Map.of("minecraft:emerald", 60),
                false, thresholds, 10).isEmpty());
        assertEquals(0, pd.progressNotified.get(emerald100));
    }

    @Test
    void pendingNotificationsPreservePlayerInsertionOrderDrainAndClearWithoutSending() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        MilestoneRecord rec = new MilestoneRecord(1200, 1L, 1_700_000_000_000L);
        Tracker.clearPendingNotifications();
        try {
            Tracker.queuePendingNotification(a, "Alice", Component.literal("Alice"),
                    TargetKey.of("minecraft:diamond"), rec, true);
            Tracker.queuePendingNotification(b, "Bob", Component.literal("Bob"),
                    TargetKey.of("minecraft:coal"), rec, false);
            Tracker.queuePendingNotification(a, "Alice", Component.literal("Alice"),
                    TargetKey.of("minecraft:emerald"), rec, false);

            List<Tracker.PendingNotifications> drained = Tracker.drainPendingNotifications();
            assertEquals(2, drained.size());
            assertEquals(a, drained.get(0).playerId);
            assertEquals(2, drained.get(0).records.size());
            assertEquals(2, drained.get(0).discordRecords.size());
            assertEquals(b, drained.get(1).playerId);
            assertEquals(1, drained.get(1).records.size());
            assertEquals(1, drained.get(1).discordRecords.size());
            assertTrue(Tracker.drainPendingNotifications().isEmpty(), "drain must empty the queue");

            // clearPendingNotifications (called on ServerStoppingEvent) discards queued entries
            Tracker.queuePendingNotification(a, "Alice", null,
                    TargetKey.of("minecraft:iron_ingot"), rec, false);
            Tracker.clearPendingNotifications();
            assertTrue(Tracker.drainPendingNotifications().isEmpty());
        } finally {
            Tracker.clearPendingNotifications();
        }
    }

    @Test
    void flushPendingOnStopWithoutAServerEmptiesTheQueueInsteadOfLeavingItForTheNextSession() {
        UUID a = UUID.randomUUID();
        MilestoneRecord rec = new MilestoneRecord(1200, 1L, 1_700_000_000_000L);
        Tracker.clearPendingNotifications();
        try {
            Tracker.queuePendingNotification(a, "Alice", Component.literal("Alice"),
                    TargetKey.of("minecraft:diamond"), rec, false);
            Tracker.flushPendingOnStop(null);
            assertTrue(Tracker.drainPendingNotifications().isEmpty());
        } finally {
            Tracker.clearPendingNotifications();
        }
    }

    @Test
    void pendingNotificationsExcludeOptedOutPlayersFromDiscordQueueWhilePreservingInGameChannelsAndWorldFirsts() {
        MilestoneData data = new MilestoneData();
        MilestoneData.PlayerData optedOutPd = data.player(PLAYER_A);
        optedOutPd.excludeFromDiscord = true;
        MilestoneData.PlayerData optedInPd = data.player(PLAYER_B);

        MilestoneRecord rec = new MilestoneRecord(1200, 1L, 1_700_000_000_000L);
        List<TargetKey> keys = List.of(
                TargetKey.of("minecraft:emerald"),
                TargetKey.of("minecraft:coal"),
                TargetKey.of("minecraft:diamond"));

        Tracker.clearPendingNotifications();
        try {
            // Opted-out player (allowDiscordOptOut = true): world-firsts still recorded, in-game queued, Discord NOT queued
            for (TargetKey key : keys) {
                Tracker.RecordOutcome outcome = Tracker.applyRecord(data, PLAYER_A, "Alice", key, rec, true);
                assertEquals(Tracker.RecordOutcome.RECORDED_WORLD_FIRST, outcome);
                Tracker.queuePendingNotification(
                        PLAYER_A, "Alice", Component.literal("Alice"), key, rec, true,
                        DiscordNotifier.shouldPostToDiscord(true, optedOutPd));
            }
            // Opted-in player in the same tick: queued for both in-game and Discord
            for (TargetKey key : keys) {
                Tracker.queuePendingNotification(
                        PLAYER_B, "Bob", Component.literal("Bob"), key, rec, false,
                        DiscordNotifier.shouldPostToDiscord(true, optedInPd));
            }

            List<Tracker.PendingNotifications> drained = Tracker.drainPendingNotifications();
            assertEquals(2, drained.size());

            Tracker.PendingNotifications pendingA = drained.get(0);
            assertEquals(PLAYER_A, pendingA.playerId);
            assertEquals(3, pendingA.records.size());
            assertTrue(pendingA.discordRecords.isEmpty(), "opted-out player must not be queued for Discord");

            NotificationGrouping.FlushPlan<Tracker.QueuedRecord> planA = NotificationGrouping.planFlush(
                    pendingA.records,
                    pendingA.discordRecords,
                    Tracker.QueuedRecord::worldFirst,
                    Comparator.comparing(Tracker.QueuedRecord::key),
                    true, true, true, true,
                    DiscordNotifier.shouldPostToDiscord(true, data, PLAYER_A),
                    "all");
            assertEquals(NotificationGrouping.SendMode.GROUPED, planA.playerChat().mode());
            assertEquals(NotificationGrouping.SendMode.GROUPED, planA.broadcastChat().mode());
            assertEquals(NotificationGrouping.SendMode.GROUPED, planA.worldFirstChat().mode());
            assertTrue(planA.playSound());
            assertEquals(NotificationGrouping.SendMode.NONE, planA.discord().mode());
            assertTrue(planA.discord().items().isEmpty());

            Tracker.PendingNotifications pendingB = drained.get(1);
            assertEquals(PLAYER_B, pendingB.playerId);
            assertEquals(3, pendingB.records.size());
            assertEquals(3, pendingB.discordRecords.size());

            NotificationGrouping.FlushPlan<Tracker.QueuedRecord> planB = NotificationGrouping.planFlush(
                    pendingB.records,
                    pendingB.discordRecords,
                    Tracker.QueuedRecord::worldFirst,
                    Comparator.comparing(Tracker.QueuedRecord::key),
                    true, true, true, true,
                    DiscordNotifier.shouldPostToDiscord(true, data, PLAYER_B),
                    "all");
            assertEquals(NotificationGrouping.SendMode.GROUPED, planB.discord().mode());
            assertEquals(3, planB.discord().items().size());

            // When allowDiscordOptOut = false, saved excludeFromDiscord is ignored so Discord IS queued and planned
            for (TargetKey key : keys) {
                Tracker.queuePendingNotification(
                        PLAYER_A, "Alice", Component.literal("Alice"), key, rec, true,
                        DiscordNotifier.shouldPostToDiscord(false, optedOutPd));
            }
            List<Tracker.PendingNotifications> drainedOverride = Tracker.drainPendingNotifications();
            assertEquals(1, drainedOverride.size());
            assertEquals(3, drainedOverride.get(0).discordRecords.size());
            NotificationGrouping.FlushPlan<Tracker.QueuedRecord> planOverride = NotificationGrouping.planFlush(
                    drainedOverride.get(0).records,
                    drainedOverride.get(0).discordRecords,
                    Tracker.QueuedRecord::worldFirst,
                    Comparator.comparing(Tracker.QueuedRecord::key),
                    true, true, true, true,
                    DiscordNotifier.shouldPostToDiscord(false, data, PLAYER_A),
                    "world_first");
            assertEquals(NotificationGrouping.SendMode.GROUPED, planOverride.discord().mode());
            assertEquals(3, planOverride.discord().items().size());
        } finally {
            Tracker.clearPendingNotifications();
        }
    }

    @Test
    void buildTargetListComponentJoinsFirstFiveWithLocalizedSeparatorAndMoreAndKeepsLiteralsSafe() {
        Function<TargetKey, Component> literalName = key -> Component.literal(key.formatDisplayId());
        List<TargetKey> sixSorted = List.of(
                TargetKey.of("minecraft:a"),
                TargetKey.of("minecraft:b", 10),
                TargetKey.of("minecraft:c"),
                TargetKey.of("minecraft:d"),
                TargetKey.of("minecraft:e"),
                TargetKey.of("minecraft:f"));

        Component enList = Tracker.buildTargetListComponent("en_us", sixSorted, literalName);
        assertEquals("minecraft:a, minecraft:b ×10, minecraft:c, minecraft:d, minecraft:e, and 1 more",
                enList.getString());

        Component jaList = Tracker.buildTargetListComponent("ja_jp", sixSorted, literalName);
        assertEquals("minecraft:a、minecraft:b ×10、minecraft:c、minecraft:d、minecraft:e ほか 1 件",
                jaList.getString());

        // External player names containing format characters must be embedded via Component.literal
        Component safeName = Component.literal("Player%sName");
        String broadcast = ServerLang.trForLang("en_us", "message.itemmilestonelog.recorded_group_broadcast",
                safeName, 6, "0h 01m 00s", enList).getString();
        assertEquals("★ Player%sName recorded 6 milestones at playtime 0h 01m 00s: "
                + "minecraft:a, minecraft:b ×10, minecraft:c, minecraft:d, minecraft:e, and 1 more", broadcast);

        String worldFirstJa = ServerLang.trForLang("ja_jp", "message.itemmilestonelog.world_first_group",
                safeName, 6, "0h 01m 00s (Day 2, 2023-11-14)", jaList).getString();
        assertEquals("★ 世界初！Player%sName が 6 件を 0h 01m 00s (Day 2, 2023-11-14) にワールド初取得しました: "
                + "minecraft:a、minecraft:b ×10、minecraft:c、minecraft:d、minecraft:e ほか 1 件", worldFirstJa);
    }

    @Test
    void discordNotifierBuildsGroupedPlainTextAndJsonPayloadFromLangFiles() {
        MilestoneRecord rec = new MilestoneRecord(72_000, 1L, 1_700_000_000_000L);
        List<DiscordNotifier.MilestoneEntry> entries = List.of(
                new DiscordNotifier.MilestoneEntry(TargetKey.of("minecraft:coal"), rec, false),
                new DiscordNotifier.MilestoneEntry(TargetKey.of("minecraft:diamond", 64), rec, true),
                new DiscordNotifier.MilestoneEntry(TargetKey.of("minecraft:emerald"), rec, false));

        String enContent = DiscordNotifier.buildGroupedPlainTextContent("Alice", entries, ZoneOffset.UTC, "en_us");
        assertEquals(String.join("\n",
                "★ Milestones recorded: Alice obtained 3 targets at 1h 00m 00s (Day 2, 2023-11-14)",
                "- minecraft:coal",
                "- ★ [World First] minecraft:diamond ×64",
                "- minecraft:emerald"), enContent);

        String jaContent = DiscordNotifier.buildGroupedPlainTextContent("Alice", entries, ZoneOffset.UTC, "ja_jp");
        assertEquals(String.join("\n",
                "★ マイルストーン記録: Alice が 3 件を入手（1h 00m 00s (Day 2, 2023-11-14)）",
                "- minecraft:coal",
                "- ★ [世界初] minecraft:diamond ×64",
                "- minecraft:emerald"), jaContent);

        String json = DiscordNotifier.buildGroupedPayloadJson("Alice", entries, ZoneOffset.UTC);
        JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(enContent, parsed.get("content").getAsString());
        assertEquals(0, parsed.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());

        // 25 entries cap at 20 lines + "...and 5 more" and stay within 2000 chars
        List<DiscordNotifier.MilestoneEntry> many = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            many.add(new DiscordNotifier.MilestoneEntry(TargetKey.of("minecraft:item_" + i), rec, i == 1));
        }
        String manyContent = DiscordNotifier.buildGroupedPlainTextContent("Alice", many, ZoneOffset.UTC, "en_us");
        String[] lines = manyContent.split("\n");
        assertEquals(22, lines.length);
        assertEquals("- ★ [World First] minecraft:item\\_1", lines[1]);
        assertEquals("- minecraft:item\\_20", lines[20]);
        assertEquals("...and 5 more", lines[21]);
        assertTrue(manyContent.length() <= NotificationGrouping.DISCORD_PLAIN_MAX_CHARS);
    }

    @Test
    void countCreativeCarriedTargetItemsDeduplicatesQuantityKeysAndExcludesRecorded() {
        TargetKey diamond1 = TargetKey.of("minecraft:diamond", 1);
        TargetKey diamond10 = TargetKey.of("minecraft:diamond", 10);
        TargetKey diamond64 = TargetKey.of("minecraft:diamond", 64);
        TargetKey emerald5 = TargetKey.of("minecraft:emerald", 5);
        TargetKey gold1 = TargetKey.of("minecraft:gold_ingot", 1);
        TargetKey iron1 = TargetKey.of("minecraft:iron_ingot", 1);

        Set<TargetKey> defaults = Set.of(diamond1, gold1);
        MilestoneData.PlayerData pd = new MilestoneData.PlayerData();
        pd.targets.add(diamond10);
        pd.targets.add(diamond64);
        pd.targets.add(emerald5);
        pd.targets.add(iron1);
        Set<TargetKey> effective = Tracker.effectiveTargets(defaults, pd);

        // Empty or null inputs yield 0
        assertEquals(0, Tracker.countCreativeCarriedTargetItems(Set.of(), effective, Set.of()));
        assertEquals(0, Tracker.countCreativeCarriedTargetItems(null, effective, Set.of()));
        assertEquals(0, Tracker.countCreativeCarriedTargetItems(Set.of("minecraft:diamond"), Set.of(), Set.of()));
        assertEquals(0, Tracker.countCreativeCarriedTargetItems(Set.of("minecraft:diamond"), null, Set.of()));

        // Non-target items only yield 0
        assertEquals(0, Tracker.countCreativeCarriedTargetItems(
                Set.of("minecraft:dirt", "minecraft:cobblestone"), effective, Set.of()));

        // Multiple quantity targets for the same item count once (deduplicated)
        assertEquals(1, Tracker.countCreativeCarriedTargetItems(
                Set.of("minecraft:diamond"), effective, Set.of()));

        // Partially recorded item still counts if at least one quantity target remains unrecorded
        assertEquals(1, Tracker.countCreativeCarriedTargetItems(
                Set.of("minecraft:diamond"), effective, Set.of(diamond1, diamond10)));

        // Fully recorded item is excluded
        assertEquals(0, Tracker.countCreativeCarriedTargetItems(
                Set.of("minecraft:diamond"), effective, Set.of(diamond1, diamond10, diamond64)));

        // Mix of default + personal unrecorded targets, recorded targets, and non-targets
        Set<String> marked = Set.of("minecraft:diamond", "minecraft:emerald", "minecraft:gold_ingot", "minecraft:dirt");
        Set<TargetKey> recorded = Set.of(diamond1, gold1);
        assertEquals(2, Tracker.countCreativeCarriedTargetItems(marked, effective, recorded));

        // Null recordedKeys is treated as empty
        assertEquals(3, Tracker.countCreativeCarriedTargetItems(marked, effective, null));
    }

    @Test
    void creativeCarriedMessagesRenderInEnglishAndJapanese() {
        assertEquals(
                "2 target item(s) you carried out of creative mode will not be recorded until they leave your inventory.",
                ServerLang.trForLang("en_us", "message.itemmilestonelog.creative_carried_notice", 2).getString());
        assertEquals(
                "クリエイティブから持ち出した目標のアイテム（2 種類）は、一度インベントリから無くなるまで記録されません。",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.creative_carried_notice", 2).getString());

        Component enPending = ServerLang.trForLang("en_us", "message.itemmilestonelog.target_pending", "Diamond")
                .append(ServerLang.trForLang("en_us", "message.itemmilestonelog.target_creative_carried"));
        assertEquals("… Diamond — not yet obtained (carried from creative)", enPending.getString());

        Component jaPending = ServerLang.trForLang("ja_jp", "message.itemmilestonelog.target_pending", "ダイヤモンド")
                .append(ServerLang.trForLang("ja_jp", "message.itemmilestonelog.target_creative_carried"));
        assertEquals("… ダイヤモンド — 未取得（クリエイティブから持ち出し中）", jaPending.getString());

        assertEquals(
                "Recording is paused while in Creative or Spectator mode.",
                ServerLang.trForLang("en_us", "message.itemmilestonelog.summary_creative_paused").getString());
        assertEquals(
                "クリエイティブ・スペクテイターの間は記録されません。",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.summary_creative_paused").getString());
    }

    @Test
    void evaluatePresetCompletionsUpdatesCompletedPresetsOnceAndHonorsJudgmentRules() {
        MilestoneData data = new MilestoneData();
        TargetKey diamond1 = TargetKey.of("minecraft:diamond", 1);
        TargetKey diamond64 = TargetKey.of("minecraft:diamond", 64);
        TargetKey netherite1 = TargetKey.of("minecraft:netherite_ingot", 1);
        TargetKey missingStar = TargetKey.of("allthetweaks:atm_star", 1);
        TargetKey coal1 = TargetKey.of("minecraft:coal", 1);
        MilestoneRecord rec = new MilestoneRecord(1200, 2L, 1_700_000_000_000L);

        Presets.PresetDefinition allMissing = new Presets.PresetDefinition(
                "atm_star", "atm_star", List.of(missingStar), true);
        Presets.PresetDefinition mixedCustom = new Presets.PresetDefinition(
                "mixed_endgame", "Mixed Endgame", List.of(diamond1, netherite1, missingStar), false);
        Presets.PresetDefinition exactQtyCustom = new Presets.PresetDefinition(
                "stack_diamond", "Stack Diamond", List.of(diamond64), false);
        List<Presets.PresetDefinition> allPresets = List.of(allMissing, mixedCustom, exactQtyCustom);
        Set<String> knownItems = Set.of("minecraft:diamond", "minecraft:netherite_ingot", "minecraft:coal");

        // Record diamond#1 (also world-first) -> mixed_endgame still needs netherite#1, stack_diamond needs diamond#64, atm_star has empty R(S)
        assertEquals(Tracker.RecordOutcome.RECORDED_WORLD_FIRST,
                Tracker.applyRecord(data, PLAYER_A, "Alice", diamond1, rec, true));
        assertTrue(Tracker.evaluatePresetCompletions(
                data, PLAYER_A, diamond1, allPresets, knownItems::contains, true).isEmpty());
        assertTrue(data.playerOrNull(PLAYER_A).completedPresets.isEmpty());

        // Count mismatch: recording diamond#1 did not complete stack_diamond; recording diamond#64 completes stack_diamond
        Tracker.applyRecord(data, PLAYER_A, "Alice", diamond64, rec, true);
        // When presetsEnabled == false, no judgment occurs and completedPresets stays empty
        assertTrue(Tracker.evaluatePresetCompletions(
                data, PLAYER_A, diamond64, allPresets, knownItems::contains, false).isEmpty());
        assertTrue(data.playerOrNull(PLAYER_A).completedPresets.isEmpty());

        // With presetsEnabled == true, stack_diamond completes and is persisted in completedPresets
        List<Presets.PresetDefinition> completedStack = Tracker.evaluatePresetCompletions(
                data, PLAYER_A, diamond64, allPresets, knownItems::contains, true);
        assertEquals(List.of(exactQtyCustom), completedStack);
        assertEquals(Set.of("stack_diamond"), data.playerOrNull(PLAYER_A).completedPresets);

        // Already completed: re-evaluating diamond#64 does not fire stack_diamond again
        assertTrue(Tracker.evaluatePresetCompletions(
                data, PLAYER_A, diamond64, allPresets, knownItems::contains, true).isEmpty());

        // K not in preset: even if netherite#1 is already in records (e.g. from pre-1.1.0 or import),
        // recording an unrelated item coal#1 must NOT complete mixed_endgame
        MilestoneData.PlayerData pd = data.player(PLAYER_A);
        pd.records.put(netherite1, rec);
        Tracker.applyRecord(data, PLAYER_A, "Alice", coal1, rec, true);
        assertTrue(Tracker.evaluatePresetCompletions(
                data, PLAYER_A, coal1, allPresets, knownItems::contains, true).isEmpty());
        assertFalse(pd.completedPresets.contains("mixed_endgame"));

        // Completing the final key in R(mixed_endgame) (with missingStar excluded from R(S) and even with pd.targets empty)
        // completes mixed_endgame and coexists with world-first on the same record
        pd.records.remove(netherite1);
        assertEquals(Tracker.RecordOutcome.RECORDED_WORLD_FIRST,
                Tracker.applyRecord(data, PLAYER_A, "Alice", netherite1, rec, true));
        List<Presets.PresetDefinition> completedMixed = Tracker.evaluatePresetCompletions(
                data, PLAYER_A, netherite1, allPresets, knownItems::contains, true);
        assertEquals(List.of(mixedCustom), completedMixed);
        assertEquals(Set.of("stack_diamond", "mixed_endgame"), pd.completedPresets);
    }

    @Test
    void presetCompletionRecipientRoutingQueueingAndLocalizedMessages() {
        // 1. announcePresetCompletion = true: completing player always receives broadcast regardless of
        //    muteBroadcasts or notifyPlayer; other players respect muteBroadcasts
        assertTrue(Tracker.shouldReceivePresetCompletionChat(PLAYER_A, PLAYER_A, false, true, false));
        assertTrue(Tracker.shouldReceivePresetCompletionChat(PLAYER_A, PLAYER_A, true, true, false));
        assertTrue(Tracker.shouldReceivePresetCompletionChat(PLAYER_A, PLAYER_A, true, true, true));
        assertTrue(Tracker.shouldReceivePresetCompletionChat(PLAYER_A, PLAYER_B, false, true, false));
        assertFalse(Tracker.shouldReceivePresetCompletionChat(PLAYER_A, PLAYER_B, true, true, true));

        // 2. announcePresetCompletion = false: only completing player receives personal message when notifyPlayer = true
        assertTrue(Tracker.shouldReceivePresetCompletionChat(PLAYER_A, PLAYER_A, false, false, true));
        assertTrue(Tracker.shouldReceivePresetCompletionChat(PLAYER_A, PLAYER_A, true, false, true));
        assertFalse(Tracker.shouldReceivePresetCompletionChat(PLAYER_A, PLAYER_A, false, false, false));
        assertFalse(Tracker.shouldReceivePresetCompletionChat(PLAYER_A, PLAYER_B, false, false, true));
        assertFalse(Tracker.shouldReceivePresetCompletionChat(PLAYER_A, PLAYER_B, false, false, false));

        // 3. F27 queuePendingPresetCompletions queues chat completions and respects Discord opt-out
        Presets.PresetDefinition builtin = Presets.get("vanilla_endgame");
        Presets.PresetDefinition custom = new Presets.PresetDefinition(
                "my_pack", "My %s Pack", List.of(TargetKey.of("minecraft:diamond")), false);
        assertNotNull(builtin);
        Tracker.clearPendingNotifications();
        try {
            Tracker.queuePendingPresetCompletions(
                    PLAYER_A, "Alice", Component.literal("Alice"), List.of(builtin), 1_700_000_000_000L, true);
            Tracker.queuePendingPresetCompletions(
                    PLAYER_B, "Bob", Component.literal("Bob"), List.of(custom), 1_700_000_000_000L, false);

            List<Tracker.PendingNotifications> drained = Tracker.drainPendingNotifications();
            assertEquals(2, drained.size());
            assertEquals(1, drained.get(0).presetCompletions.size());
            assertEquals(1, drained.get(0).discordPresetCompletions.size());
            assertEquals("vanilla_endgame", drained.get(0).presetCompletions.get(0).preset().id());

            assertEquals(1, drained.get(1).presetCompletions.size());
            assertTrue(drained.get(1).discordPresetCompletions.isEmpty());
        } finally {
            Tracker.clearPendingNotifications();
        }

        // 4. Localized message rendering for builtin and custom presets (with literal %s safety)
        Component enBuiltinName = MilestoneService.presetDisplayNameForLang("en_us", builtin);
        Component jaBuiltinName = MilestoneService.presetDisplayNameForLang("ja_jp", builtin);
        assertEquals("★ All targets of Preset: Vanilla Endgame are complete!",
                ServerLang.trForLang("en_us", "message.itemmilestonelog.preset_completed", enBuiltinName).getString());
        assertEquals("★ プリセット: Vanilla Endgame の目標をすべて達成しました！",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.preset_completed", jaBuiltinName).getString());
        assertEquals("★ Alice completed all targets of Preset: Vanilla Endgame!",
                ServerLang.trForLang("en_us", "message.itemmilestonelog.preset_completed_broadcast",
                        Component.literal("Alice"), enBuiltinName).getString());
        assertEquals("★ Alice が プリセット: Vanilla Endgame の目標をすべて達成しました！",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.preset_completed_broadcast",
                        Component.literal("Alice"), jaBuiltinName).getString());

        Component customName = MilestoneService.presetDisplayNameForLang("en_us", custom);
        assertEquals("★ Player%s completed all targets of My %s Pack!",
                ServerLang.trForLang("en_us", "message.itemmilestonelog.preset_completed_broadcast",
                        Component.literal("Player%s"), customName).getString());
        assertEquals("★ Player%s が My %s Pack の目標をすべて達成しました！",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.preset_completed_broadcast",
                        Component.literal("Player%s"), customName).getString());

        // 5. F54: an object-form custom name resolves per language for F5 completion messages
        Presets.PresetDefinition localizedCustom = new Presets.PresetDefinition(
                "loc_pack",
                PresetName.ofLocalized(Map.of("en_us", "My %s Pack", "ja_jp", "私の%sパック")),
                List.of(TargetKey.of("minecraft:diamond")),
                Presets.PresetKind.FILE);
        assertEquals("★ Player%s completed all targets of My %s Pack!",
                ServerLang.trForLang("en_us", "message.itemmilestonelog.preset_completed_broadcast",
                        Component.literal("Player%s"),
                        MilestoneService.presetDisplayNameForLang("en_us", localizedCustom)).getString());
        assertEquals("★ Player%s が 私の%sパック の目標をすべて達成しました！",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.preset_completed_broadcast",
                        Component.literal("Player%s"),
                        MilestoneService.presetDisplayNameForLang("ja_jp", localizedCustom)).getString());
        // Unmatched / null languages resolve with en_us priority (console / RCON path)
        assertEquals("My %s Pack", MilestoneService.presetDisplayNameForLang("fr_fr", localizedCustom).getString());
        assertEquals("My %s Pack", MilestoneService.presetDisplayNameForLang(null, localizedCustom).getString());
    }

    @Test
    void milestoneRecordedEventConstructorsPreserveCompatibilityAndCarryWorldFirstFlag() {
        MilestoneRecordedEvent legacySingle = new MilestoneRecordedEvent(
                null, "minecraft:diamond", 1200, 3L, 1_700_000_000_000L);
        assertNull(legacySingle.getPlayer());
        assertEquals("minecraft:diamond", legacySingle.getItemId());
        assertEquals(TargetKey.MIN_COUNT, legacySingle.getCount());
        assertEquals(TargetKey.of("minecraft:diamond"), legacySingle.getTargetKey());
        assertEquals(1200, legacySingle.getPlayTicks());
        assertEquals(3L, legacySingle.getDay());
        assertEquals(1_700_000_000_000L, legacySingle.getEpochMillis());
        assertFalse(legacySingle.isWorldFirst());
        assertFalse(legacySingle.isCancelable());

        MilestoneRecordedEvent legacyCount = new MilestoneRecordedEvent(
                null, "minecraft:diamond", 64, 2400, 5L, 1_700_000_100_000L);
        assertEquals("minecraft:diamond", legacyCount.getItemId());
        assertEquals(64, legacyCount.getCount());
        assertEquals(TargetKey.of("minecraft:diamond", 64), legacyCount.getTargetKey());
        assertEquals(2400, legacyCount.getPlayTicks());
        assertEquals(5L, legacyCount.getDay());
        assertEquals(1_700_000_100_000L, legacyCount.getEpochMillis());
        assertFalse(legacyCount.isWorldFirst());

        MilestoneRecordedEvent worldFirstTrue = new MilestoneRecordedEvent(
                null, "minecraft:nether_star", 2, 3600, 7L, 1_700_000_200_000L, true);
        assertEquals("minecraft:nether_star", worldFirstTrue.getItemId());
        assertEquals(2, worldFirstTrue.getCount());
        assertEquals(TargetKey.of("minecraft:nether_star", 2), worldFirstTrue.getTargetKey());
        assertEquals(3600, worldFirstTrue.getPlayTicks());
        assertEquals(7L, worldFirstTrue.getDay());
        assertEquals(1_700_000_200_000L, worldFirstTrue.getEpochMillis());
        assertTrue(worldFirstTrue.isWorldFirst());

        MilestoneRecordedEvent worldFirstFalse = new MilestoneRecordedEvent(
                null, "minecraft:nether_star", 2, 3600, 7L, 1_700_000_200_000L, false);
        assertFalse(worldFirstFalse.isWorldFirst());
    }

    @Test
    void presetCompletedEventExposesFieldsAndIsNotCancellable() {
        PresetCompletedEvent builtinEvent = new PresetCompletedEvent(null, "vanilla_endgame", true);
        assertNull(builtinEvent.getPlayer());
        assertEquals("vanilla_endgame", builtinEvent.getPresetId());
        assertTrue(builtinEvent.isBuiltin());
        assertFalse(builtinEvent.isCancelable());

        PresetCompletedEvent customEvent = new PresetCompletedEvent(null, "my_pack", false);
        assertNull(customEvent.getPlayer());
        assertEquals("my_pack", customEvent.getPresetId());
        assertFalse(customEvent.isBuiltin());
        assertFalse(customEvent.isCancelable());
    }

    @Test
    void postRecordEventsWiresWorldFirstFlagAndOrdersEventsAfterCompletedPresetsUpdate() {
        MilestoneData data = new MilestoneData();
        TargetKey diamond = TargetKey.of("minecraft:diamond");
        TargetKey emerald = TargetKey.of("minecraft:emerald", 8);
        TargetKey gold = TargetKey.of("minecraft:gold_ingot");
        MilestoneRecord recA = new MilestoneRecord(1200, 4L, 1_700_000_000_000L);
        MilestoneRecord recB = new MilestoneRecord(2400, 6L, 1_700_000_050_000L);

        Presets.PresetDefinition builtinPreset = new Presets.PresetDefinition(
                "builtin_pack", "builtin_pack", List.of(diamond), true);
        Presets.PresetDefinition customPreset = new Presets.PresetDefinition(
                "custom_pack", "Custom Pack", List.of(diamond), false);
        Presets.PresetDefinition emeraldPreset = new Presets.PresetDefinition(
                "emerald_pack", "Emerald Pack", List.of(emerald), false);
        List<Presets.PresetDefinition> presets = List.of(builtinPreset, customPreset, emeraldPreset);
        Set<String> knownItems = Set.of("minecraft:diamond", "minecraft:emerald", "minecraft:gold_ingot");

        // 1. World-first record that also completes built-in + custom presets:
        //    MilestoneRecordedEvent(worldFirst=true) fires first, followed by PresetCompletedEvents
        //    with completedPresets already persisted.
        Tracker.RecordOutcome outcomeA = Tracker.applyRecord(data, PLAYER_A, "Alice", diamond, recA, true);
        assertEquals(Tracker.RecordOutcome.RECORDED_WORLD_FIRST, outcomeA);
        List<Presets.PresetDefinition> completedA = Tracker.evaluatePresetCompletions(
                data, PLAYER_A, diamond, presets, knownItems::contains, true);
        List<Event> eventsA = new ArrayList<>();
        Tracker.postRecordEvents(null, diamond, recA, outcomeA, completedA, event -> {
            if (event instanceof PresetCompletedEvent presetEvent) {
                assertTrue(data.playerOrNull(PLAYER_A).completedPresets.contains(presetEvent.getPresetId()),
                        "completedPresets must be updated before PresetCompletedEvent is posted");
            }
            eventsA.add(event);
        });
        assertEquals(3, eventsA.size());
        MilestoneRecordedEvent recordedA = assertInstanceOf(MilestoneRecordedEvent.class, eventsA.get(0));
        assertEquals("minecraft:diamond", recordedA.getItemId());
        assertEquals(1, recordedA.getCount());
        assertEquals(1200, recordedA.getPlayTicks());
        assertEquals(4L, recordedA.getDay());
        assertEquals(1_700_000_000_000L, recordedA.getEpochMillis());
        assertTrue(recordedA.isWorldFirst());

        PresetCompletedEvent presetEvent1 = assertInstanceOf(PresetCompletedEvent.class, eventsA.get(1));
        assertEquals("builtin_pack", presetEvent1.getPresetId());
        assertTrue(presetEvent1.isBuiltin());

        PresetCompletedEvent presetEvent2 = assertInstanceOf(PresetCompletedEvent.class, eventsA.get(2));
        assertEquals("custom_pack", presetEvent2.getPresetId());
        assertFalse(presetEvent2.isBuiltin());

        // 2. Second player recording the same key is not world-first -> isWorldFirst() == false
        Tracker.RecordOutcome outcomeB = Tracker.applyRecord(data, PLAYER_B, "Bob", diamond, recB, true);
        assertEquals(Tracker.RecordOutcome.RECORDED, outcomeB);
        List<Event> eventsB = new ArrayList<>();
        Tracker.postRecordEvents(null, diamond, recB, outcomeB, List.of(), eventsB::add);
        assertEquals(1, eventsB.size());
        assertFalse(assertInstanceOf(MilestoneRecordedEvent.class, eventsB.get(0)).isWorldFirst());

        // 3. When worldFirstEnabled == false, a brand-new key produces isWorldFirst() == false
        Tracker.RecordOutcome outcomeDisabledWf = Tracker.applyRecord(data, PLAYER_A, "Alice", gold, recA, false);
        assertEquals(Tracker.RecordOutcome.RECORDED, outcomeDisabledWf);
        List<Event> eventsDisabledWf = new ArrayList<>();
        Tracker.postRecordEvents(null, gold, recA, outcomeDisabledWf, List.of(), eventsDisabledWf::add);
        assertEquals(1, eventsDisabledWf.size());
        assertFalse(assertInstanceOf(MilestoneRecordedEvent.class, eventsDisabledWf.get(0)).isWorldFirst());

        // 4. When presetsEnabled == false, no PresetCompletedEvent is posted even if all preset targets are met
        Tracker.RecordOutcome outcomeDisabledPresets = Tracker.applyRecord(data, PLAYER_A, "Alice", emerald, recA, true);
        assertEquals(Tracker.RecordOutcome.RECORDED_WORLD_FIRST, outcomeDisabledPresets);
        List<Presets.PresetDefinition> completedDisabled = Tracker.evaluatePresetCompletions(
                data, PLAYER_A, emerald, presets, knownItems::contains, false);
        assertTrue(completedDisabled.isEmpty());
        List<Event> eventsDisabledPresets = new ArrayList<>();
        Tracker.postRecordEvents(null, emerald, recA, outcomeDisabledPresets, completedDisabled, eventsDisabledPresets::add);
        assertEquals(1, eventsDisabledPresets.size());
        MilestoneRecordedEvent emeraldEvent = assertInstanceOf(MilestoneRecordedEvent.class, eventsDisabledPresets.get(0));
        assertEquals(TargetKey.of("minecraft:emerald", 8), emeraldEvent.getTargetKey());
        assertTrue(emeraldEvent.isWorldFirst());

        // 5. Duplicate record (ALREADY_RECORDED) posts no events
        Tracker.RecordOutcome duplicate = Tracker.applyRecord(data, PLAYER_A, "Alice", diamond, recB, true);
        assertEquals(Tracker.RecordOutcome.ALREADY_RECORDED, duplicate);
        List<Event> eventsDup = new ArrayList<>();
        Tracker.postRecordEvents(null, diamond, recB, duplicate, List.of(builtinPreset), eventsDup::add);
        assertTrue(eventsDup.isEmpty());
    }

    // ---- F32: write API (recordNow) ----

    @Test
    void canRecordNowRejectsFakePlayerPausedNonTargetAndAlreadyRecorded() {
        MilestoneData data = new MilestoneData();
        TargetKey diamond = TargetKey.of("minecraft:diamond");
        TargetKey defaultKey = TargetKey.of("minecraft:iron_ingot", 64);
        TargetKey other = TargetKey.of("minecraft:emerald");
        Set<TargetKey> defaults = Set.of(defaultKey);

        // No PlayerData yet: default targets are still effective, personal ones are not
        assertTrue(Tracker.canRecordNow(false, false, defaults, null, defaultKey));
        assertFalse(Tracker.canRecordNow(false, false, defaults, null, diamond));

        MilestoneData.PlayerData pd = data.player(PLAYER_A);
        pd.targets.add(diamond);
        assertTrue(Tracker.canRecordNow(false, false, defaults, pd, diamond), "personal target");
        assertTrue(Tracker.canRecordNow(false, false, defaults, pd, defaultKey), "default target");
        assertFalse(Tracker.canRecordNow(false, false, defaults, pd, other), "not an effective target");
        assertFalse(Tracker.canRecordNow(false, false, defaults, pd, TargetKey.of("minecraft:diamond", 2)),
                "quantity is part of the key");

        assertFalse(Tracker.canRecordNow(true, false, defaults, pd, diamond), "FakePlayer");
        assertFalse(Tracker.canRecordNow(false, true, defaults, pd, diamond), "recordingPaused");

        pd.records.put(diamond, new MilestoneRecord(100, 1L, 1_700_000_000_000L));
        assertFalse(Tracker.canRecordNow(false, false, defaults, pd, diamond), "already recorded");
        assertTrue(Tracker.canRecordNow(false, false, defaults, pd, defaultKey));
    }

    @Test
    void recordNowNestingDepthIsCappedAtSixteen() {
        assertEquals(16, Tracker.MAX_RECORD_NOW_DEPTH);
        assertEquals(16, ItemMilestoneLogApi.MAX_RECORD_NOW_DEPTH);

        Tracker.NestingGuard guard = new Tracker.NestingGuard(Tracker.MAX_RECORD_NOW_DEPTH);
        AtomicInteger deepest = new AtomicInteger();
        AtomicInteger overflows = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();

        // Endless chain: every level tries to nest one more call (like recordNow from a listener).
        class Chain {
            boolean call() {
                attempts.incrementAndGet();
                return guard.run(() -> {
                    deepest.set(Math.max(deepest.get(), guard.depth()));
                    call();
                    return true;
                }, overflows::incrementAndGet);
            }
        }
        assertTrue(new Chain().call(), "the outermost call succeeds");
        assertEquals(16, deepest.get(), "16 nested levels run");
        assertEquals(17, attempts.get(), "the 17th nested call is attempted");
        assertEquals(1, overflows.get(), "the 17th call returns false and warns once");
        assertEquals(0, guard.depth(), "depth unwinds after the chain");

        // The guard is reusable after an overflow, and the depth is restored even when the action throws.
        assertTrue(guard.run(() -> true, overflows::incrementAndGet));
        assertThrows(IllegalStateException.class, () -> guard.run(() -> {
            throw new IllegalStateException("boom");
        }, overflows::incrementAndGet));
        assertEquals(0, guard.depth());
        assertEquals(1, overflows.get());
    }

    @Test
    void postRecordEventsPropagatesRecordCause() {
        TargetKey diamond = TargetKey.of("minecraft:diamond");
        MilestoneRecord rec = new MilestoneRecord(1200, 4L, 1_700_000_000_000L);
        Presets.PresetDefinition preset = new Presets.PresetDefinition(
                "pack", "Pack", List.of(diamond), false);

        for (RecordCause cause : RecordCause.values()) {
            List<Event> events = new ArrayList<>();
            Tracker.postRecordEvents(null, diamond, rec, Tracker.RecordOutcome.RECORDED_WORLD_FIRST,
                    List.of(preset), cause, events::add);
            assertEquals(2, events.size());
            MilestoneRecordedEvent recorded = assertInstanceOf(MilestoneRecordedEvent.class, events.get(0));
            assertEquals(cause, recorded.getCause());
            assertTrue(recorded.isWorldFirst());
            assertInstanceOf(PresetCompletedEvent.class, events.get(1));
        }

        // The overload without a cause is the gameplay path
        List<Event> gameplay = new ArrayList<>();
        Tracker.postRecordEvents(null, diamond, rec, Tracker.RecordOutcome.RECORDED, List.of(), gameplay::add);
        assertEquals(RecordCause.GAMEPLAY, assertInstanceOf(MilestoneRecordedEvent.class, gameplay.get(0)).getCause());

        // ALREADY_RECORDED posts nothing regardless of cause
        List<Event> none = new ArrayList<>();
        Tracker.postRecordEvents(null, diamond, rec, Tracker.RecordOutcome.ALREADY_RECORDED, List.of(),
                RecordCause.API, none::add);
        assertTrue(none.isEmpty());
    }

    @Test
    void milestoneRecordedEventConstructorsDefaultCauseToGameplay() {
        assertEquals(RecordCause.GAMEPLAY,
                new MilestoneRecordedEvent(null, "minecraft:diamond", 10, 1L, 2L).getCause());
        assertEquals(RecordCause.GAMEPLAY,
                new MilestoneRecordedEvent(null, "minecraft:diamond", 5, 10, 1L, 2L).getCause());
        assertEquals(RecordCause.GAMEPLAY,
                new MilestoneRecordedEvent(null, "minecraft:diamond", 5, 10, 1L, 2L, true).getCause());
        assertEquals(RecordCause.GAMEPLAY,
                new MilestoneRecordedEvent(null, "minecraft:diamond", 5, 10, 1L, 2L, true, null).getCause());
        MilestoneRecordedEvent api = new MilestoneRecordedEvent(null, "minecraft:diamond", 5, 10, 1L, 2L, false, RecordCause.API);
        assertEquals(RecordCause.API, api.getCause());
        assertFalse(api.isWorldFirst());
        assertEquals(RecordCause.ADMIN_GRANT,
                new MilestoneRecordedEvent(null, "minecraft:diamond", 1, 10, 1L, 2L, false, RecordCause.ADMIN_GRANT).getCause());
    }

    // ---- F33: admin grant ----

    @Test
    void grantMilestoneEnforcesOfflineAlreadyRecordedAutoAddAndCapPreconditions() {
        Function<TargetKey, Component> literalName = key -> Component.literal(key.formatDisplayId());
        Set<String> knownItems = Set.of("minecraft:diamond", "minecraft:iron_ingot", "minecraft:emerald");
        TargetKey diamond = TargetKey.of("minecraft:diamond");
        TargetKey defaultIron = TargetKey.of("minecraft:iron_ingot");
        TargetKey emerald = TargetKey.of("minecraft:emerald", 64);
        MilestoneRecord rec = new MilestoneRecord(72_000, 5L, 1_700_000_000_000L);

        // 1. Offline target -> admin_grant_offline; no data mutation, no record supplier call, no messages/events/logs
        MilestoneData data = new MilestoneData();
        AtomicInteger supplierCalls = new AtomicInteger();
        List<Component> targetMessages = new ArrayList<>();
        List<Event> postedEvents = new ArrayList<>();
        AtomicInteger logCalls = new AtomicInteger();

        Tracker.GrantResult offline = Tracker.grantMilestone(
                data, PLAYER_A, "Alice%s", false, null, null,
                "minecraft:diamond", 1, Set.of(),
                () -> {
                    supplierCalls.incrementAndGet();
                    return rec;
                },
                List.of(), knownItems::contains, literalName, true, true,
                (k, r) -> logCalls.incrementAndGet(),
                targetMessages::add, postedEvents::add);
        assertFalse(offline.success());
        assertEquals(Tracker.GrantStatus.OFFLINE, offline.status());
        assertEquals("Alice%s must be online to be granted a milestone.", offline.executorMessage().getString());
        assertNull(data.playerOrNull(PLAYER_A));
        assertEquals(0, supplierCalls.get());
        assertTrue(targetMessages.isEmpty());
        assertTrue(postedEvents.isEmpty());
        assertEquals(0, logCalls.get());

        // 2. Invalid count and unknown/air item rejected without mutating PlayerData
        Tracker.GrantResult badCount = Tracker.grantMilestone(
                data, PLAYER_A, "Alice", true, null, null,
                "minecraft:diamond", 0, Set.of(), () -> rec,
                List.of(), knownItems::contains, literalName, true, true,
                (k, r) -> logCalls.incrementAndGet(), targetMessages::add, postedEvents::add);
        assertEquals(Tracker.GrantStatus.INVALID_COUNT, badCount.status());
        Tracker.GrantResult badAir = Tracker.grantMilestone(
                data, PLAYER_A, "Alice", true, null, null,
                "minecraft:air", 1, Set.of(), () -> rec,
                List.of(), knownItems::contains, literalName, true, true,
                (k, r) -> logCalls.incrementAndGet(), targetMessages::add, postedEvents::add);
        assertEquals(Tracker.GrantStatus.INVALID_ITEM, badAir.status());
        assertNull(data.playerOrNull(PLAYER_A));

        // 3. Auto-add personal target when missing from effective targets (un-namespaced input normalized)
        Tracker.GrantResult autoAdded = Tracker.grantMilestone(
                data, PLAYER_A, "Alice", true, null, null,
                "diamond", 1, Set.of(), () -> rec,
                List.of(), knownItems::contains, literalName, true, true,
                (k, r) -> logCalls.incrementAndGet(), targetMessages::add, postedEvents::add);
        assertTrue(autoAdded.success());
        assertEquals(Tracker.GrantStatus.GRANTED, autoAdded.status());
        assertTrue(autoAdded.autoAddedTarget());
        assertEquals("Granted to Alice: minecraft:diamond", autoAdded.executorMessage().getString());
        MilestoneData.PlayerData pd = data.playerOrNull(PLAYER_A);
        assertNotNull(pd);
        assertTrue(pd.targets.contains(diamond), "missing target must be auto-added to personal targets");
        assertEquals(rec, pd.records.get(diamond));
        assertNull(data.worldFirstOrNull(diamond), "grant must never register world-first");
        assertEquals(1, logCalls.get());

        // 4. Already recorded -> admin_grant_already even if target was removed from personal targets and cap is full
        pd.targets.remove(diamond);
        for (int i = 0; i < MilestoneService.MAX_PERSONAL_TARGETS; i++) {
            pd.targets.add(TargetKey.of("minecraft:filler_" + i));
        }
        targetMessages.clear();
        postedEvents.clear();
        Tracker.GrantResult already = Tracker.grantMilestone(
                data, PLAYER_A, "Alice", true, null, null,
                "minecraft:diamond", 1, Set.of(), () -> rec,
                List.of(), knownItems::contains, literalName, true, true,
                (k, r) -> logCalls.incrementAndGet(), targetMessages::add, postedEvents::add);
        assertFalse(already.success());
        assertEquals(Tracker.GrantStatus.ALREADY_RECORDED, already.status());
        assertEquals("Alice has already recorded minecraft:diamond.", already.executorMessage().getString());
        assertFalse(pd.targets.contains(diamond), "already-recorded grant must not mutate targets");
        assertTrue(targetMessages.isEmpty());
        assertTrue(postedEvents.isEmpty());

        // 5. Personal target cap failure when key is NOT in effective targets
        Tracker.GrantResult capFailed = Tracker.grantMilestone(
                data, PLAYER_A, "Alice", true, null, null,
                "minecraft:emerald", 64, Set.of(defaultIron), () -> rec,
                List.of(), knownItems::contains, literalName, true, true,
                (k, r) -> logCalls.incrementAndGet(), targetMessages::add, postedEvents::add);
        assertFalse(capFailed.success());
        assertEquals(Tracker.GrantStatus.TARGETS_LIMIT_REACHED, capFailed.status());
        assertEquals("Personal target limit reached (1024).", capFailed.executorMessage().getString());
        assertFalse(pd.targets.contains(emerald));
        assertFalse(pd.records.containsKey(emerald));
        assertTrue(targetMessages.isEmpty());
        assertTrue(postedEvents.isEmpty());

        // 6. Key already in defaultTargets succeeds even when personal targets are at cap (does not add personal target)
        Tracker.GrantResult defaultTargetGrant = Tracker.grantMilestone(
                data, PLAYER_A, "Alice", true, null, null,
                "minecraft:iron_ingot", 1, Set.of(defaultIron), () -> rec,
                List.of(), knownItems::contains, literalName, true, true,
                (k, r) -> logCalls.incrementAndGet(), targetMessages::add, postedEvents::add);
        assertTrue(defaultTargetGrant.success());
        assertFalse(defaultTargetGrant.autoAddedTarget());
        assertFalse(pd.targets.contains(defaultIron));
        assertEquals(rec, pd.records.get(defaultIron));
        assertNull(data.worldFirstOrNull(defaultIron));
    }

    @Test
    void grantMilestonePostsAdminGrantCauseWithoutWorldFirstCleansProgressHandlesF5AndSkipsBroadcastQueue() {
        Function<TargetKey, Component> literalName = key -> Component.literal(key.formatDisplayId());
        Set<String> knownItems = Set.of("minecraft:diamond", "minecraft:emerald", "minecraft:gold_ingot");
        TargetKey diamond100 = TargetKey.of("minecraft:diamond", 100);
        TargetKey emerald = TargetKey.of("minecraft:emerald");
        TargetKey gold = TargetKey.of("minecraft:gold_ingot");
        MilestoneRecord rec = new MilestoneRecord(72_000, 3L, 1_700_000_000_000L); // 1h 00m 00s

        Presets.PresetDefinition diamondPreset = new Presets.PresetDefinition(
                "diamond_pack", "Diamond Pack", List.of(diamond100), false);
        Presets.PresetDefinition emeraldPreset = new Presets.PresetDefinition(
                "emerald_pack", "Emerald Pack", List.of(emerald), true);
        Presets.PresetDefinition goldPreset = new Presets.PresetDefinition(
                "gold_pack", "Gold Pack", List.of(gold), false);
        List<Presets.PresetDefinition> presets = List.of(diamondPreset, emeraldPreset, goldPreset);

        MilestoneData data = new MilestoneData();
        MilestoneData.PlayerData pd = data.player(PLAYER_A);
        pd.targets.add(diamond100);
        pd.targets.add(emerald);
        pd.targets.add(gold);
        pd.progressNotified.put(diamond100, 90);
        pd.progressNotified.put(gold, 50);

        Tracker.clearPendingNotifications();
        try {
            // 1. notifyPlayer = true, presetsEnabled = true:
            //    cleans F1 progressNotified, updates completedPresets before PresetCompletedEvent,
            //    sends granted_notice + preset_completed to target, posts ADMIN_GRANT with worldFirst=false,
            //    never registers world-first, never queues to PENDING_NOTIFICATIONS.
            List<Component> messages1 = new ArrayList<>();
            List<Event> events1 = new ArrayList<>();
            List<String> logged1 = new ArrayList<>();
            Tracker.GrantResult res1 = Tracker.grantMilestone(
                    data, PLAYER_A, "Alice", true, null, null,
                    "minecraft:diamond", 100, Set.of(), () -> rec,
                    presets, knownItems::contains, literalName, true, true,
                    (k, r) -> logged1.add(k.formatDisplayId() + "@" + r.playTicks()),
                    messages1::add,
                    event -> {
                        if (event instanceof PresetCompletedEvent pce) {
                            assertTrue(pd.completedPresets.contains(pce.getPresetId()),
                                    "completedPresets must be persisted before PresetCompletedEvent fires");
                        }
                        events1.add(event);
                    });
            assertTrue(res1.success());
            assertEquals(List.of("minecraft:diamond ×100@72000"), logged1);
            assertFalse(pd.progressNotified.containsKey(diamond100), "F1 progressNotified must be cleaned");
            assertEquals(50, pd.progressNotified.get(gold), "other progressNotified entries stay intact");
            assertNull(data.worldFirstOrNull(diamond100), "grant must never register world-first");
            assertTrue(pd.completedPresets.contains("diamond_pack"));

            assertEquals(2, messages1.size());
            assertEquals("An administrator recorded minecraft:diamond ×100 for you at playtime 1h 00m 00s.",
                    messages1.get(0).getString());
            assertEquals("★ All targets of Diamond Pack are complete!", messages1.get(1).getString());

            assertEquals(2, events1.size());
            MilestoneRecordedEvent recorded1 = assertInstanceOf(MilestoneRecordedEvent.class, events1.get(0));
            assertEquals(RecordCause.ADMIN_GRANT, recorded1.getCause());
            assertFalse(recorded1.isWorldFirst());
            assertEquals(diamond100, recorded1.getTargetKey());
            assertEquals(72_000, recorded1.getPlayTicks());
            assertEquals(3L, recorded1.getDay());
            assertEquals(1_700_000_000_000L, recorded1.getEpochMillis());
            PresetCompletedEvent presetEvt1 = assertInstanceOf(PresetCompletedEvent.class, events1.get(1));
            assertEquals("diamond_pack", presetEvt1.getPresetId());
            assertFalse(presetEvt1.isBuiltin());
            assertTrue(Tracker.drainPendingNotifications().isEmpty(),
                    "grant must not enqueue broadcast/sound/Discord/F19 notifications");

            // 2. notifyPlayer = false, presetsEnabled = true:
            //    granted_notice is STILL sent to target; preset_completed chat is suppressed;
            //    completedPresets and PresetCompletedEvent still fire.
            List<Component> messages2 = new ArrayList<>();
            List<Event> events2 = new ArrayList<>();
            Tracker.GrantResult res2 = Tracker.grantMilestone(
                    data, PLAYER_A, "Alice", true, null, null,
                    "minecraft:emerald", 1, Set.of(), () -> rec,
                    presets, knownItems::contains, literalName, true, false,
                    (k, r) -> {}, messages2::add, events2::add);
            assertTrue(res2.success());
            assertTrue(pd.completedPresets.contains("emerald_pack"));
            assertEquals(1, messages2.size(), "granted_notice sent even when notifyPlayer=false, preset_completed omitted");
            assertEquals("An administrator recorded minecraft:emerald for you at playtime 1h 00m 00s.",
                    messages2.get(0).getString());
            assertEquals(2, events2.size());
            assertEquals(RecordCause.ADMIN_GRANT, assertInstanceOf(MilestoneRecordedEvent.class, events2.get(0)).getCause());
            assertFalse(assertInstanceOf(MilestoneRecordedEvent.class, events2.get(0)).isWorldFirst());
            PresetCompletedEvent presetEvt2 = assertInstanceOf(PresetCompletedEvent.class, events2.get(1));
            assertEquals("emerald_pack", presetEvt2.getPresetId());
            assertTrue(presetEvt2.isBuiltin());

            // 3. presetsEnabled = false: no F5 completion update, no preset_completed notice, no PresetCompletedEvent
            List<Component> messages3 = new ArrayList<>();
            List<Event> events3 = new ArrayList<>();
            Tracker.GrantResult res3 = Tracker.grantMilestone(
                    data, PLAYER_A, "Alice", true, null, null,
                    "minecraft:gold_ingot", 1, Set.of(), () -> rec,
                    presets, knownItems::contains, literalName, false, true,
                    (k, r) -> {}, messages3::add, events3::add);
            assertTrue(res3.success());
            assertFalse(pd.completedPresets.contains("gold_pack"));
            assertEquals(1, messages3.size());
            assertEquals(1, events3.size());
            assertEquals(RecordCause.ADMIN_GRANT, assertInstanceOf(MilestoneRecordedEvent.class, events3.get(0)).getCause());
            assertTrue(Tracker.drainPendingNotifications().isEmpty());
        } finally {
            Tracker.clearPendingNotifications();
        }

        // Verify Japanese translations for all F33 grant messages match spec verbatim
        assertEquals("Alice に記録を付与しました: Diamond",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.admin_granted",
                        Component.literal("Alice"), Component.literal("Diamond")).getString());
        assertEquals("Alice はすでに Diamond を記録しています。",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.admin_grant_already",
                        Component.literal("Alice"), Component.literal("Diamond")).getString());
        assertEquals("記録を付与するには Alice がオンラインである必要があります。",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.admin_grant_offline",
                        Component.literal("Alice")).getString());
        assertEquals("管理者があなたに Diamond の記録を付与しました（累計プレイ時間 1h 00m 00s）。",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.granted_notice",
                        Component.literal("Diamond"), "1h 00m 00s").getString());
    }

    @Test
    void processPlayerLoginAppliesFirstJoinPresetsOnceBeforeJoinHintAndRespectsExistingDataAndSwitches() {
        Set<String> knownItems = Set.of(
                "minecraft:diamond",
                "minecraft:netherite_ingot",
                "minecraft:elytra");

        // 1. First join: applies presets (skipping unknown IDs & missing items) and sends notice BEFORE join hint
        MilestoneData data = new MilestoneData();
        List<Component> firstJoinMsgs = Tracker.processPlayerLogin(
                data, PLAYER_A, "Alice", Set.of(),
                List.of("unknown_preset", "vanilla_endgame"), true,
                knownItems::contains, true, false, null);
        assertEquals(2, firstJoinMsgs.size());
        assertEquals(
                "This server added 3 starter targets to your list. Use /milestonelog remove to drop any you don't want.",
                firstJoinMsgs.get(0).getString());
        assertTrue(firstJoinMsgs.get(1).getString().startsWith("Tip: Use /milestonelog"),
                () -> "expected join hint second, got: " + firstJoinMsgs.get(1).getString());
        MilestoneData.PlayerData pdA = data.playerOrNull(PLAYER_A);
        assertNotNull(pdA);
        assertEquals("Alice", pdA.lastKnownName);
        assertTrue(pdA.firstJoinHintShown);
        assertEquals(Set.of(
                TargetKey.of("minecraft:diamond"),
                TargetKey.of("minecraft:netherite_ingot"),
                TargetKey.of("minecraft:elytra")
        ), pdA.targets);

        // 2. Returning player who removed a target does NOT receive starter targets again
        pdA.targets.remove(TargetKey.of("minecraft:elytra"));
        List<Component> rejoinMsgs = Tracker.processPlayerLogin(
                data, PLAYER_A, "Alice", Set.of(),
                List.of("vanilla_endgame"), true,
                knownItems::contains, true, false, null);
        assertTrue(rejoinMsgs.isEmpty());
        assertFalse(pdA.targets.contains(TargetKey.of("minecraft:elytra")));

        // 3. Existing PlayerData (e.g. created by importall before first login, firstJoinHintShown=false):
        //    no starter presets applied, no first_join_presets notice, only join hint shown
        MilestoneData.PlayerData pdB = data.player(PLAYER_B);
        pdB.lastKnownName = "Bob";
        List<Component> existingMsgs = Tracker.processPlayerLogin(
                data, PLAYER_B, "Bob", Set.of(),
                List.of("vanilla_endgame"), true,
                knownItems::contains, true, true, null);
        assertEquals(1, existingMsgs.size());
        assertTrue(existingMsgs.get(0).getString().startsWith("Tip: Bind a key"),
                () -> "expected mod join hint only, got: " + existingMsgs.get(0).getString());
        assertTrue(pdB.targets.isEmpty());

        // 4. Purged player rejoining with showFirstJoinHint=false: treated as first join again, notice still sent
        assertTrue(data.purgePlayer(PLAYER_A));
        List<Component> afterPurgeMsgs = Tracker.processPlayerLogin(
                data, PLAYER_A, "Alice", Set.of(),
                List.of("vanilla_endgame"), true,
                knownItems::contains, false, false, null);
        assertEquals(1, afterPurgeMsgs.size());
        assertEquals(
                "This server added 3 starter targets to your list. Use /milestonelog remove to drop any you don't want.",
                afterPurgeMsgs.get(0).getString());
        assertEquals(3, data.playerOrNull(PLAYER_A).targets.size());

        // 5. First join with 0 added (presetsEnabled=false, unknown IDs, or no known items): no first_join_presets notice
        UUID playerC = UUID.fromString("33333333-3333-3333-3333-333333333333");
        List<Component> disabledMsgs = Tracker.processPlayerLogin(
                data, playerC, "Charlie", Set.of(),
                List.of("vanilla_endgame"), false,
                knownItems::contains, true, false, null);
        assertEquals(1, disabledMsgs.size());
        assertTrue(disabledMsgs.get(0).getString().startsWith("Tip:"));
        assertTrue(data.playerOrNull(playerC).targets.isEmpty());

        UUID playerD = UUID.fromString("44444444-4444-4444-4444-444444444444");
        List<Component> zeroAddedMsgs = Tracker.processPlayerLogin(
                data, playerD, "Dave", Set.of(),
                List.of("unknown_only", "vanilla_endgame"), true,
                id -> false, false, false, null);
        assertTrue(zeroAddedMsgs.isEmpty());
        assertTrue(data.playerOrNull(playerD).targets.isEmpty());

        // 6. Verify Japanese translation matches spec verbatim
        assertEquals(
                "このサーバーが用意した目標を 3 件、あなたの目標に追加しました。要らないものは /milestonelog remove で外せます。",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.first_join_presets", 3).getString());
    }

    @Test
    void processPlayerLoginAndLogoutUpdateLastSeenWithoutRecreatingPurgedPlayer() {
        MilestoneData data = new MilestoneData();
        long firstLogin = 1_795_000_000_000L;
        long secondLogin = 1_795_100_000_000L;
        long logoutTime = 1_795_200_000_000L;

        Tracker.processPlayerLogin(
                data, PLAYER_A, "Alice", Set.of(), List.of(), true,
                id -> true, false, false, null, firstLogin);
        MilestoneData.PlayerData pdA = data.playerOrNull(PLAYER_A);
        assertNotNull(pdA);
        assertEquals(firstLogin, pdA.lastSeen);

        Tracker.processPlayerLogin(
                data, PLAYER_A, "Alice", Set.of(), List.of(), true,
                id -> true, false, false, null, secondLogin);
        assertEquals(secondLogin, pdA.lastSeen);

        Tracker.processPlayerLogout(data, PLAYER_A, logoutTime);
        assertEquals(logoutTime, pdA.lastSeen);

        // Purged / absent player logging out must not resurrect PlayerData
        assertTrue(data.purgePlayer(PLAYER_A));
        Tracker.processPlayerLogout(data, PLAYER_A, logoutTime + 10_000L);
        assertNull(data.playerOrNull(PLAYER_A));
    }

    // ------------------------------------------------------------------ F8b tag targets

    private static final TagIndex IRON_TAGS = TagIndex.of(Map.of(
            "c:ingots/iron", List.of("minecraft:iron_ingot", "othermod:iron_ingot"),
            "c:ingots", List.of("minecraft:iron_ingot", "minecraft:gold_ingot")));

    @Test
    void countPendingPossessionSumsTagMembersAndExcludesCreativeCarried() {
        TargetKey ironTag = TargetKey.of("#c:ingots/iron", 10);
        TargetKey diamond = TargetKey.of("minecraft:diamond", 5);
        List<Tracker.StackCount> stacks = List.of(
                new Tracker.StackCount("minecraft:iron_ingot", 4),
                new Tracker.StackCount("othermod:iron_ingot", 3),
                new Tracker.StackCount("minecraft:iron_ingot", 2),
                new Tracker.StackCount("minecraft:diamond", 5),
                new Tracker.StackCount("minecraft:dirt", 64));

        Set<String> stillCarried = new HashSet<>();
        Map<String, Integer> counts = Tracker.countPendingPossession(
                stacks, Set.of(ironTag, diamond), Set.of(), stillCarried, IRON_TAGS);
        assertEquals(9, counts.get("#c:ingots/iron"));
        assertEquals(5, counts.get("minecraft:diamond"));
        assertNull(counts.get("minecraft:dirt"));
        assertTrue(stillCarried.isEmpty());

        // A creative-carried member counts neither for itself nor toward the tag.
        Set<String> stillCarried2 = new HashSet<>();
        Map<String, Integer> carriedCounts = Tracker.countPendingPossession(
                stacks, Set.of(ironTag), Set.of("othermod:iron_ingot"), stillCarried2, IRON_TAGS);
        assertEquals(6, carriedCounts.get("#c:ingots/iron"));
        assertEquals(Set.of("othermod:iron_ingot"), stillCarried2);

        // Unknown / empty tags count as 0 (absent from the map).
        Map<String, Integer> unknown = Tracker.countPendingPossession(
                stacks, Set.of(TargetKey.of("#c:gems/ruby")), Set.of(), new HashSet<>(), IRON_TAGS);
        assertEquals(0, unknown.getOrDefault("#c:gems/ruby", 0));
    }

    @Test
    void countRequestedPossessionExpandsTagsAndKeepsItemIds() {
        List<Tracker.StackCount> stacks = List.of(
                new Tracker.StackCount("minecraft:iron_ingot", 4),
                new Tracker.StackCount("minecraft:gold_ingot", 7));
        Map<String, Integer> counts = Tracker.countRequestedPossession(
                stacks, Set.of("#c:ingots", "minecraft:gold_ingot"), Set.of("minecraft:iron_ingot"), IRON_TAGS);
        // iron is creative-carried: excluded from the tag total; requested item IDs are counted as-is.
        assertEquals(7, counts.get("#c:ingots"));
        assertEquals(7, counts.get("minecraft:gold_ingot"));
        assertEquals(Map.of(), Tracker.countRequestedPossession(stacks, Set.of(), Set.of(), IRON_TAGS));
    }

    @Test
    void evaluateTagAcquisitionChecksOnlyUnrecordedTagsContainingTheItem() {
        TargetKey iron1 = TargetKey.of("#c:ingots/iron");
        TargetKey iron10 = TargetKey.of("#c:ingots/iron", 10);
        TargetKey ingots = TargetKey.of("#c:ingots");
        TargetKey unrelated = TargetKey.of("#c:gems");
        MilestoneData.PlayerData pd = new MilestoneData.PlayerData();
        pd.targets.add(iron10);
        pd.targets.add(unrelated);
        pd.targets.add(ingots);
        pd.records.put(ingots, new MilestoneRecord(100, 0L, 0L));

        List<TargetKey> pending = Tracker.collectUnrecordedTagTargetsForItem(
                "minecraft:iron_ingot", Set.of(iron1), pd, IRON_TAGS);
        assertEquals(List.of(iron1, iron10), pending);

        List<Set<String>> requested = new ArrayList<>();
        Tracker.TagAcquisitionOutcome outcome = Tracker.evaluateTagAcquisition(
                "minecraft:iron_ingot", Set.of(iron1), pd, IRON_TAGS, ids -> {
                    requested.add(ids);
                    return Map.of("#c:ingots/iron", 3);
                });
        assertEquals(List.of(Set.of("#c:ingots/iron")), requested);
        assertEquals(List.of(iron1), outcome.achieved());
        assertEquals(List.of(iron1, iron10), outcome.pending());

        // An item that belongs to no pending tag never triggers a possession scan.
        Tracker.TagAcquisitionOutcome none = Tracker.evaluateTagAcquisition(
                "minecraft:dirt", Set.of(iron1), pd, IRON_TAGS, ids -> {
                    throw new AssertionError("should not count");
                });
        assertEquals(Tracker.TagAcquisitionOutcome.EMPTY, none);
    }

    @Test
    void missingOrEmptyDefaultTagsListsEachUnknownTagOnce() {
        Set<TargetKey> defaults = new LinkedHashSet<>(List.of(
                TargetKey.of("#c:ingots/iron"),
                TargetKey.of("#nonexistent:future_tag"),
                TargetKey.of("#nonexistent:future_tag", 10),
                TargetKey.of("minecraft:diamond")));
        assertEquals(List.of("#nonexistent:future_tag"), Tracker.missingOrEmptyDefaultTags(defaults, IRON_TAGS));
        assertEquals(List.of("#c:ingots/iron", "#nonexistent:future_tag"),
                Tracker.missingOrEmptyDefaultTags(defaults, TagIndex.EMPTY));
    }

    @Test
    void creativeCarriedNotesAndCountsIncludeTagTargets() {
        TargetKey ironTag = TargetKey.of("#c:ingots/iron", 10);
        TargetKey diamond = TargetKey.of("minecraft:diamond");
        Set<String> carried = Set.of("othermod:iron_ingot");

        // F50 (2): targets row note for a tag with a carried member.
        assertTrue(Tracker.isCreativeCarriedTarget(ironTag, carried, IRON_TAGS));
        assertFalse(Tracker.isCreativeCarriedTarget(TargetKey.of("#c:ingots"), carried, IRON_TAGS));
        assertFalse(Tracker.isCreativeCarriedTarget(diamond, carried, IRON_TAGS));
        assertTrue(Tracker.isCreativeCarriedTarget(diamond, Set.of("minecraft:diamond"), IRON_TAGS));
        assertFalse(Tracker.isCreativeCarriedTarget(ironTag, Set.of(), IRON_TAGS));

        // F50 (1): marked items in unrecorded tag targets are counted once per item ID.
        Set<TargetKey> effective = Set.of(ironTag, TargetKey.of("#c:ingots"), diamond);
        assertEquals(2, Tracker.countCreativeCarriedTargetItems(
                Set.of("minecraft:iron_ingot", "othermod:iron_ingot"), effective, Set.of(), IRON_TAGS));
        assertEquals(1, Tracker.countCreativeCarriedTargetItems(
                Set.of("minecraft:iron_ingot", "othermod:iron_ingot"), effective,
                Set.of(ironTag), IRON_TAGS));
        assertEquals(0, Tracker.countCreativeCarriedTargetItems(
                Set.of("othermod:iron_ingot"), effective, Set.of(ironTag), IRON_TAGS));
    }

    @Test
    void collectRetroactiveTriggersCoversAllExistingRecordsAndRespectsWorldFirstEnabled() {
        MilestoneData data = new MilestoneData();
        assertEquals(List.of(), Tracker.collectRetroactiveTriggers(data, PLAYER_A, true));

        TargetKey diamond = TargetKey.of("minecraft:diamond");
        TargetKey ironTag = TargetKey.of("#c:ingots/iron", 10);
        TargetKey emerald = TargetKey.of("minecraft:emerald", 5);
        TargetKey removedTarget = TargetKey.of("minecraft:nether_star");
        MilestoneRecord rec = new MilestoneRecord(1000, 1L, 1_000_000L);

        MilestoneData.PlayerData pd = data.player(PLAYER_A);
        pd.targets.add(diamond);
        pd.targets.add(ironTag);
        pd.targets.add(emerald);
        // Empty records -> nothing to trigger retroactively
        assertEquals(List.of(), Tracker.collectRetroactiveTriggers(data, PLAYER_A, true));

        // Player A records all four (including removedTarget, which is no longer in pd.targets)
        pd.records.put(diamond, rec);
        pd.records.put(ironTag, rec);
        pd.records.put(emerald, rec);
        pd.records.put(removedTarget, rec);

        // Player A holds world first for diamond and ironTag; Player B holds emerald; nobody holds removedTarget
        data.recordWorldFirstIfAbsent(diamond, PLAYER_A, "Alice", rec);
        data.recordWorldFirstIfAbsent(ironTag, PLAYER_A, "Alice", rec);
        data.recordWorldFirstIfAbsent(emerald, PLAYER_B, "Bob", rec);

        // worldFirstEnabled = true: player's own world firsts are true, others are false
        assertEquals(List.of(
                new Tracker.RetroactiveTriggerEntry(diamond, true),
                new Tracker.RetroactiveTriggerEntry(ironTag, true),
                new Tracker.RetroactiveTriggerEntry(emerald, false),
                new Tracker.RetroactiveTriggerEntry(removedTarget, false)
        ), Tracker.collectRetroactiveTriggers(data, PLAYER_A, true));

        // worldFirstEnabled = false: world_first is always false even for keys the player holds world first on
        assertEquals(List.of(
                new Tracker.RetroactiveTriggerEntry(diamond, false),
                new Tracker.RetroactiveTriggerEntry(ironTag, false),
                new Tracker.RetroactiveTriggerEntry(emerald, false),
                new Tracker.RetroactiveTriggerEntry(removedTarget, false)
        ), Tracker.collectRetroactiveTriggers(data, PLAYER_A, false));
    }
}
