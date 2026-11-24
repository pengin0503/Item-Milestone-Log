package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for F17 {@link ScoreboardSync} and {@link Config} scoreboard validation. */
class ScoreboardSyncTest {
    private static final UUID PLAYER_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAYER_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static Integer scoreOf(Scoreboard scoreboard, String holderName, String objectiveName) {
        Objective obj = scoreboard.getObjective(objectiveName);
        if (obj == null) {
            return null;
        }
        ReadOnlyScoreInfo info = scoreboard.getPlayerScoreInfo(ScoreHolder.forNameOnly(holderName), obj);
        return info != null ? info.value() : null;
    }

    @Test
    void configValidatorAndUnloadedFallback() {
        assertEquals("", Config.SCOREBOARD_OBJECTIVE.getDefault());
        assertEquals("", Config.SCOREBOARD_WORLD_FIRST_OBJECTIVE.getDefault());
        assertTrue(Config.validateScoreboardObjective(""));
        assertTrue(Config.validateScoreboardObjective("iml_records"));
        assertTrue(Config.validateScoreboardObjective("A-z.0+9_"));
        assertFalse(Config.validateScoreboardObjective("bad name"));
        assertFalse(Config.validateScoreboardObjective("ns:obj"));
        assertFalse(Config.validateScoreboardObjective("a".repeat(33)));
        assertFalse(Config.validateScoreboardObjective(123));
        assertFalse(Config.validateScoreboardObjective(null));

        // When ModConfigSpec is not loaded (plain unit tests), scoreboardObjectives() returns disabled cleanly.
        ScoreboardLogic.Objectives unloaded = Config.scoreboardObjectives();
        assertFalse(unloaded.anyEnabled());
    }

    @Test
    void refreshScoresCreatesDummyObjectivesLazilyWithoutChangingDisplaySlots() {
        Scoreboard scoreboard = new Scoreboard();
        MilestoneData data = new MilestoneData();
        ScoreboardLogic.Objectives objectives = ScoreboardLogic.resolve("iml_records", "iml_firsts");

        // No player data yet -> nothing written, objectives not created.
        ScoreboardSync.refreshScores(scoreboard, data, PLAYER_A, objectives, null);
        assertNull(scoreboard.getObjective("iml_records"));
        assertNull(scoreboard.getObjective("iml_firsts"));

        // Player with empty name -> skipped, objectives still not created.
        MilestoneData.PlayerData pd = data.player(PLAYER_A);
        pd.records.put(TargetKey.of("minecraft:diamond"), MilestoneRecord.legacy(100));
        ScoreboardSync.refreshScores(scoreboard, data, PLAYER_A, objectives, null);
        assertNull(scoreboard.getObjective("iml_records"));
        assertNull(scoreboard.getObjective("iml_firsts"));

        // Named player with 2 records and 1 world-first -> both objectives created on first write.
        pd.lastKnownName = "Alice";
        pd.records.put(TargetKey.of("minecraft:emerald"), MilestoneRecord.legacy(200));
        data.recordWorldFirstIfAbsent(TargetKey.of("minecraft:diamond"), PLAYER_A, "Alice", MilestoneRecord.legacy(100));

        ScoreboardSync.refreshScores(scoreboard, data, PLAYER_A, objectives, null);

        Objective recObj = scoreboard.getObjective("iml_records");
        Objective wfObj = scoreboard.getObjective("iml_firsts");
        assertNotNull(recObj);
        assertNotNull(wfObj);
        assertEquals(ObjectiveCriteria.DUMMY, recObj.getCriteria());
        assertEquals(ObjectiveCriteria.DUMMY, wfObj.getCriteria());
        assertEquals("Milestones", recObj.getDisplayName().getString());
        assertEquals("World Firsts", wfObj.getDisplayName().getString());
        for (DisplaySlot slot : DisplaySlot.values()) {
            assertNull(scoreboard.getDisplayObjective(slot), "display slot " + slot + " must stay untouched");
        }
        assertEquals(2, scoreOf(scoreboard, "Alice", "iml_records"));
        assertEquals(1, scoreOf(scoreboard, "Alice", "iml_firsts"));
    }

    @Test
    void existingObjectiveIsReusedRegardlessOfReadOnlyCriterion() {
        Scoreboard scoreboard = new Scoreboard();
        Objective existing = scoreboard.addObjective(
                "custom_obj",
                ObjectiveCriteria.HEALTH,
                Component.literal("Custom Title"),
                ObjectiveCriteria.HEALTH.getDefaultRenderType(),
                false,
                null);

        MilestoneData data = new MilestoneData();
        MilestoneData.PlayerData pd = data.player(PLAYER_A);
        pd.lastKnownName = "Alice";
        pd.records.put(TargetKey.of("minecraft:diamond"), MilestoneRecord.legacy(10));

        ScoreboardLogic.Objectives objectives = ScoreboardLogic.resolve("custom_obj", "");
        ScoreboardSync.refreshScores(scoreboard, data, PLAYER_A, objectives, null);

        assertSame(existing, scoreboard.getObjective("custom_obj"));
        assertEquals(ObjectiveCriteria.HEALTH, existing.getCriteria());
        assertEquals("Custom Title", existing.getDisplayName().getString());
        assertEquals(1, scoreOf(scoreboard, "Alice", "custom_obj"));
    }

    @Test
    void refreshAllWritesAllNamedPlayersResolvesNameCollisionAndRespectsDuplicateObjective() {
        Scoreboard scoreboard = new Scoreboard();
        MilestoneData data = new MilestoneData();

        // Empty world -> refreshAll does not create objectives.
        ScoreboardLogic.Objectives both = ScoreboardLogic.resolve("iml_records", "iml_firsts");
        ScoreboardSync.refreshAll(scoreboard, data, both, null);
        assertNull(scoreboard.getObjective("iml_records"));
        assertNull(scoreboard.getObjective("iml_firsts"));

        MilestoneData.PlayerData pdA = data.player(PLAYER_A);
        pdA.lastKnownName = "Shared";
        pdA.lastSeen = 100L;
        pdA.records.put(TargetKey.of("minecraft:diamond"), MilestoneRecord.legacy(10));
        data.recordWorldFirstIfAbsent(TargetKey.of("minecraft:diamond"), PLAYER_A, "Shared", MilestoneRecord.legacy(10));

        MilestoneData.PlayerData pdB = data.player(PLAYER_B);
        pdB.lastKnownName = "Shared";
        pdB.lastSeen = 200L; // more recently seen -> wins collision
        pdB.records.put(TargetKey.of("minecraft:diamond"), MilestoneRecord.legacy(20));
        pdB.records.put(TargetKey.of("minecraft:emerald"), MilestoneRecord.legacy(30));

        ScoreboardSync.refreshAll(scoreboard, data, both, null);
        assertEquals(2, scoreOf(scoreboard, "Shared", "iml_records"));
        assertEquals(0, scoreOf(scoreboard, "Shared", "iml_firsts"));

        // Duplicate objective names: world-first objective is disabled (records wins).
        Scoreboard scoreboardDup = new Scoreboard();
        ScoreboardLogic.Objectives dup = ScoreboardLogic.resolve("iml_same", "iml_same");
        assertTrue(dup.duplicateIgnored());
        ScoreboardSync.warnIfDuplicate(dup);
        ScoreboardSync.refreshAll(scoreboardDup, data, dup, null);
        assertEquals(2, scoreOf(scoreboardDup, "Shared", "iml_same"));
        ScoreboardSync.resetWarningState();
    }

    @Test
    void deleteScoresRemovesScoresWithoutCreatingObjectivesAndPreservesOtherObjectives() {
        Scoreboard scoreboard = new Scoreboard();
        MilestoneData data = new MilestoneData();
        MilestoneData.PlayerData pd = data.player(PLAYER_A);
        pd.lastKnownName = "Alice";
        pd.records.put(TargetKey.of("minecraft:diamond"), MilestoneRecord.legacy(10));
        data.recordWorldFirstIfAbsent(TargetKey.of("minecraft:diamond"), PLAYER_A, "Alice", MilestoneRecord.legacy(10));

        ScoreboardLogic.Objectives objectives = ScoreboardLogic.resolve("iml_records", "iml_firsts");

        // Deleting before objectives exist must NOT create them.
        ScoreboardSync.deleteScores(scoreboard, data, PLAYER_A, objectives, null);
        assertNull(scoreboard.getObjective("iml_records"));
        assertNull(scoreboard.getObjective("iml_firsts"));

        // Write scores plus an unrelated objective score.
        ScoreboardSync.refreshScores(scoreboard, data, PLAYER_A, objectives, null);
        Objective unrelated = scoreboard.addObjective(
                "other_obj", ObjectiveCriteria.DUMMY, Component.literal("Other"),
                ObjectiveCriteria.RenderType.INTEGER, false, null);
        scoreboard.getOrCreatePlayerScore(ScoreHolder.forNameOnly("Alice"), unrelated, true).set(99);

        // Delete removes only our two objectives' scores for Alice.
        ScoreboardSync.deleteScores(scoreboard, data, PLAYER_A, objectives, null);
        assertNull(scoreOf(scoreboard, "Alice", "iml_records"));
        assertNull(scoreOf(scoreboard, "Alice", "iml_firsts"));
        assertEquals(99, scoreOf(scoreboard, "Alice", "other_obj"));
    }

    @Test
    void deleteScoresRefreshesSurvivingPlayerOnNameCollision() {
        Scoreboard scoreboard = new Scoreboard();
        MilestoneData data = new MilestoneData();
        ScoreboardLogic.Objectives objectives = ScoreboardLogic.resolve("iml_records", "iml_firsts");

        MilestoneData.PlayerData pdA = data.player(PLAYER_A);
        pdA.lastKnownName = "Same";
        pdA.lastSeen = 500L;
        pdA.records.put(TargetKey.of("minecraft:diamond"), MilestoneRecord.legacy(10));

        MilestoneData.PlayerData pdB = data.player(PLAYER_B);
        pdB.lastKnownName = "Same";
        pdB.lastSeen = 100L;
        pdB.records.put(TargetKey.of("minecraft:diamond"), MilestoneRecord.legacy(20));
        pdB.records.put(TargetKey.of("minecraft:emerald"), MilestoneRecord.legacy(30));

        ScoreboardSync.refreshAll(scoreboard, data, objectives, null);
        assertEquals(1, scoreOf(scoreboard, "Same", "iml_records"));

        // Purging A must not wipe "Same"; instead B's score (2) becomes active.
        ScoreboardSync.deleteScores(scoreboard, data, PLAYER_A, objectives, null);
        data.purgePlayer(PLAYER_A);
        assertEquals(2, scoreOf(scoreboard, "Same", "iml_records"));
        assertEquals(0, scoreOf(scoreboard, "Same", "iml_firsts"));
    }

    @Test
    void removeStaleNameScoresOnLoginDeletesOldNameAndRefreshesNewName() {
        Scoreboard scoreboard = new Scoreboard();
        MilestoneData data = new MilestoneData();
        ScoreboardLogic.Objectives objectives = ScoreboardLogic.resolve("iml_records", "iml_firsts");

        MilestoneData.PlayerData pd = data.player(PLAYER_A);
        pd.lastKnownName = "OldAlice";
        pd.records.put(TargetKey.of("minecraft:diamond"), MilestoneRecord.legacy(10));
        data.recordWorldFirstIfAbsent(TargetKey.of("minecraft:diamond"), PLAYER_A, "OldAlice", MilestoneRecord.legacy(10));
        ScoreboardSync.refreshScores(scoreboard, data, PLAYER_A, objectives, null);
        assertEquals(1, scoreOf(scoreboard, "OldAlice", "iml_records"));
        assertEquals(1, scoreOf(scoreboard, "OldAlice", "iml_firsts"));

        // Same-name login keeps scores intact.
        ScoreboardSync.removeStaleNameScores(scoreboard, data, PLAYER_A, "OldAlice", objectives, null);
        assertEquals(1, scoreOf(scoreboard, "OldAlice", "iml_records"));

        // Player renames to NewAlice on login.
        String previous = pd.lastKnownName;
        pd.lastKnownName = "NewAlice";
        ScoreboardSync.removeStaleNameScores(scoreboard, data, PLAYER_A, previous, objectives, null);
        ScoreboardSync.refreshScores(scoreboard, data, PLAYER_A, objectives, null);

        assertNull(scoreOf(scoreboard, "OldAlice", "iml_records"));
        assertNull(scoreOf(scoreboard, "OldAlice", "iml_firsts"));
        assertEquals(1, scoreOf(scoreboard, "NewAlice", "iml_records"));
        assertEquals(1, scoreOf(scoreboard, "NewAlice", "iml_firsts"));
    }

    @Test
    void removeStaleNameScoresPreservesOldNameWhenAnotherPlayerUsesIt() {
        Scoreboard scoreboard = new Scoreboard();
        MilestoneData data = new MilestoneData();
        ScoreboardLogic.Objectives objectives = ScoreboardLogic.resolve("iml_records", "iml_firsts");

        MilestoneData.PlayerData pdA = data.player(PLAYER_A);
        pdA.lastKnownName = "Taken";
        pdA.lastSeen = 500L;
        pdA.records.put(TargetKey.of("minecraft:diamond"), MilestoneRecord.legacy(10));

        MilestoneData.PlayerData pdB = data.player(PLAYER_B);
        pdB.lastKnownName = "Taken";
        pdB.lastSeen = 100L;
        pdB.records.put(TargetKey.of("minecraft:diamond"), MilestoneRecord.legacy(20));
        pdB.records.put(TargetKey.of("minecraft:emerald"), MilestoneRecord.legacy(30));

        ScoreboardSync.refreshAll(scoreboard, data, objectives, null);
        assertEquals(1, scoreOf(scoreboard, "Taken", "iml_records"));

        // A renames to FreshName while B still has "Taken".
        pdA.lastKnownName = "FreshName";
        ScoreboardSync.removeStaleNameScores(scoreboard, data, PLAYER_A, "Taken", objectives, null);
        ScoreboardSync.refreshScores(scoreboard, data, PLAYER_A, objectives, null);

        assertEquals(2, scoreOf(scoreboard, "Taken", "iml_records"));
        assertEquals(1, scoreOf(scoreboard, "FreshName", "iml_records"));
    }

    @Test
    void emptyingConfigDoesNotDeleteExistingObjectivesOrScores() {
        Scoreboard scoreboard = new Scoreboard();
        MilestoneData data = new MilestoneData();
        MilestoneData.PlayerData pd = data.player(PLAYER_A);
        pd.lastKnownName = "Alice";
        pd.records.put(TargetKey.of("minecraft:diamond"), MilestoneRecord.legacy(10));

        ScoreboardLogic.Objectives enabled = ScoreboardLogic.resolve("iml_records", "iml_firsts");
        ScoreboardSync.refreshScores(scoreboard, data, PLAYER_A, enabled, null);
        assertEquals(1, scoreOf(scoreboard, "Alice", "iml_records"));

        // Config cleared -> subsequent refreshes/deletes are no-ops and leave existing objectives/scores intact.
        ScoreboardLogic.Objectives disabled = ScoreboardLogic.resolve("", "");
        ScoreboardSync.refreshAll(scoreboard, data, disabled, null);
        ScoreboardSync.refreshScores(scoreboard, data, PLAYER_A, disabled, null);
        ScoreboardSync.deleteScores(scoreboard, data, PLAYER_A, disabled, null);

        assertNotNull(scoreboard.getObjective("iml_records"));
        assertNotNull(scoreboard.getObjective("iml_firsts"));
        assertEquals(1, scoreOf(scoreboard, "Alice", "iml_records"));
        assertEquals(0, scoreOf(scoreboard, "Alice", "iml_firsts"));
    }
}
