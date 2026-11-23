package com.pengin0503.itemmilestonelog;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.api.WorldFirstRecord;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
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
            assertEquals(b, drained.get(1).playerId);
            assertEquals(1, drained.get(1).records.size());
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
        assertEquals("- ★ [World First] minecraft:item_1", lines[1]);
        assertEquals("- minecraft:item_20", lines[20]);
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
}
