package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for F17 {@link ScoreboardLogic}. */
class ScoreboardLogicTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @Test
    void validObjectiveNames() {
        assertTrue(ScoreboardLogic.isValidObjectiveName("iml_records"));
        assertTrue(ScoreboardLogic.isValidObjectiveName("A-z.0+9_"));
        assertTrue(ScoreboardLogic.isValidObjectiveName("x"));
        assertTrue(ScoreboardLogic.isValidObjectiveName("a".repeat(32)));
    }

    @Test
    void invalidObjectiveNames() {
        assertFalse(ScoreboardLogic.isValidObjectiveName(null));
        assertFalse(ScoreboardLogic.isValidObjectiveName(""));
        assertFalse(ScoreboardLogic.isValidObjectiveName("a".repeat(33)));
        assertFalse(ScoreboardLogic.isValidObjectiveName("has space"));
        assertFalse(ScoreboardLogic.isValidObjectiveName("colon:name"));
        assertFalse(ScoreboardLogic.isValidObjectiveName("quote\""));
        assertFalse(ScoreboardLogic.isValidObjectiveName("日本語"));
        assertFalse(ScoreboardLogic.isValidObjectiveName("trailing\n"));
    }

    @Test
    void configValueAllowsEmptyOrValidString() {
        assertTrue(ScoreboardLogic.isValidConfigValue(""));
        assertTrue(ScoreboardLogic.isValidConfigValue("iml_records"));
        assertFalse(ScoreboardLogic.isValidConfigValue(" "));
        assertFalse(ScoreboardLogic.isValidConfigValue("bad name"));
        assertFalse(ScoreboardLogic.isValidConfigValue(42));
        assertFalse(ScoreboardLogic.isValidConfigValue(null));
    }

    @Test
    void resolveDisablesEmptyAndInvalid() {
        ScoreboardLogic.Objectives none = ScoreboardLogic.resolve("", "");
        assertNull(none.records());
        assertNull(none.worldFirsts());
        assertFalse(none.anyEnabled());
        assertFalse(none.duplicateIgnored());

        ScoreboardLogic.Objectives both = ScoreboardLogic.resolve("iml_records", "iml_firsts");
        assertEquals("iml_records", both.records());
        assertEquals("iml_firsts", both.worldFirsts());
        assertFalse(both.duplicateIgnored());

        ScoreboardLogic.Objectives invalid = ScoreboardLogic.resolve("bad name", null);
        assertNull(invalid.records());
        assertNull(invalid.worldFirsts());
    }

    @Test
    void resolveDisablesWorldFirstOnDuplicate() {
        ScoreboardLogic.Objectives dup = ScoreboardLogic.resolve("iml", "iml");
        assertEquals("iml", dup.records());
        assertNull(dup.worldFirsts());
        assertTrue(dup.duplicateIgnored());

        ScoreboardLogic.Objectives onlyWorldFirst = ScoreboardLogic.resolve("", "iml");
        assertNull(onlyWorldFirst.records());
        assertEquals("iml", onlyWorldFirst.worldFirsts());
        assertFalse(onlyWorldFirst.duplicateIgnored());
    }

    @Test
    void holderNameSkipsEmpty() {
        assertNull(ScoreboardLogic.holderName(""));
        assertNull(ScoreboardLogic.holderName(null));
        assertEquals("Steve", ScoreboardLogic.holderName("Steve"));
    }

    @Test
    void countWorldFirstsPerHolder() {
        Map<UUID, Integer> counts = ScoreboardLogic.countWorldFirsts(List.of(A, B, A, A));
        assertEquals(3, counts.get(A));
        assertEquals(1, counts.get(B));
        assertNull(counts.get(C));
    }

    @Test
    void computeScoresWritesRecordAndWorldFirstCountsIncludingZero() {
        List<ScoreboardLogic.PlayerScore<UUID>> scores = ScoreboardLogic.computeScores(List.of(
                new ScoreboardLogic.PlayerInput<>(A, "Alice", 5, 10L),
                new ScoreboardLogic.PlayerInput<>(B, "Bob", 0, 10L)
        ), Map.of(A, 2));
        assertEquals(List.of(
                new ScoreboardLogic.PlayerScore<>(A, "Alice", 5, 2),
                new ScoreboardLogic.PlayerScore<>(B, "Bob", 0, 0)
        ), scores);
    }

    @Test
    void computeScoresSkipsPlayersWithoutName() {
        List<ScoreboardLogic.PlayerScore<UUID>> scores = ScoreboardLogic.computeScores(List.of(
                new ScoreboardLogic.PlayerInput<>(A, "", 5, 10L),
                new ScoreboardLogic.PlayerInput<>(B, "Bob", 1, 10L)
        ), Map.of(A, 3));
        assertEquals(List.of(new ScoreboardLogic.PlayerScore<>(B, "Bob", 1, 0)), scores);
    }

    @Test
    void computeScoresPrefersMostRecentlySeenOnNameCollision() {
        List<ScoreboardLogic.PlayerScore<UUID>> scores = ScoreboardLogic.computeScores(List.of(
                new ScoreboardLogic.PlayerInput<>(A, "Same", 5, 100L),
                new ScoreboardLogic.PlayerInput<>(B, "Same", 1, 200L),
                new ScoreboardLogic.PlayerInput<>(C, "Same", 9, 200L)
        ), Map.of());
        assertEquals(List.of(new ScoreboardLogic.PlayerScore<>(B, "Same", 1, 0)), scores);
    }

    @Test
    void staleNameOnLogin() {
        assertEquals("OldName", ScoreboardLogic.staleNameOnLogin("OldName", "NewName", Set.of()));
        assertNull(ScoreboardLogic.staleNameOnLogin("Same", "Same", Set.of()));
        assertNull(ScoreboardLogic.staleNameOnLogin("", "NewName", Set.of()));
        assertNull(ScoreboardLogic.staleNameOnLogin(null, "NewName", Set.of()));
        assertNull(ScoreboardLogic.staleNameOnLogin("OldName", "NewName", Set.of("OldName")),
                "another player now uses the old name");
    }
}
