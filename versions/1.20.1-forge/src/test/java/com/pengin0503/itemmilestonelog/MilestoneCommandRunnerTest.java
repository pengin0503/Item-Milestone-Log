package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for F7 {@link MilestoneCommandRunner} and {@link Config#MILESTONE_COMMAND}. */
class MilestoneCommandRunnerTest {
    private static final UUID PLAYER_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAYER_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void configDefaultAndUnloadedFallbackAreDisabled() {
        assertEquals("", Config.MILESTONE_COMMAND.getDefault());
        assertEquals("", Config.milestoneCommand());
    }

    @Test
    void executeBatchFormatsPlaceholdersWithPlaytimeTagAndWorldFirstAndSkipsInvalidNames() {
        // 27h 15m 30s = (27 * 3600 + 15 * 60 + 30) * 20 ticks = 1,962,600 ticks
        int ticks = (27 * 3600 + 15 * 60 + 30) * 20;
        MilestoneData data = new MilestoneData();
        Tracker.RecordOutcome wfOffOutcome = Tracker.applyRecord(
                data, PLAYER_B, "Bob", TargetKey.of("minecraft:emerald", 5),
                new MilestoneRecord(1200, 1L, 1_000L), false);
        assertFalse(wfOffOutcome == Tracker.RecordOutcome.RECORDED_WORLD_FIRST);

        List<MilestoneCommandRunner.QueuedCommand> batch = List.of(
                new MilestoneCommandRunner.QueuedCommand(
                        PLAYER_A, ".BedrockUser", TargetKey.of("#c:ingots/iron", 100), ticks, true),
                new MilestoneCommandRunner.QueuedCommand(
                        PLAYER_A, "@a", TargetKey.of("minecraft:diamond", 1), 0, false),
                new MilestoneCommandRunner.QueuedCommand(
                        PLAYER_A, "a".repeat(21), TargetKey.of("minecraft:diamond", 2), 0, false),
                new MilestoneCommandRunner.QueuedCommand(
                        PLAYER_B, "Bob", TargetKey.of("minecraft:emerald", 5), 1200,
                        wfOffOutcome == Tracker.RecordOutcome.RECORDED_WORLD_FIRST)
        );

        List<String> executed = new ArrayList<>();
        MilestoneCommandRunner.executeBatch(
                batch,
                "tellraw @a %player%|%uuid%|%item%|%count%|%playtime%|%worldfirst%",
                executed::add);

        assertEquals(List.of(
                "tellraw @a .BedrockUser|11111111-1111-1111-1111-111111111111|#c:ingots/iron|100|27h 15m 30s|true",
                "tellraw @a Bob|22222222-2222-2222-2222-222222222222|minecraft:emerald|5|0h 01m 00s|false"
        ), executed);
    }

    @Test
    void executeBatchIsolatesExceptionsSoSubsequentCommandsStillRun() {
        List<MilestoneCommandRunner.QueuedCommand> batch = List.of(
                new MilestoneCommandRunner.QueuedCommand(
                        PLAYER_A, "Alice", TargetKey.of("minecraft:diamond", 1), 0, false),
                new MilestoneCommandRunner.QueuedCommand(
                        PLAYER_B, "Bob", TargetKey.of("minecraft:emerald", 1), 0, true)
        );

        List<String> ran = new ArrayList<>();
        MilestoneCommandRunner.executeBatch(batch, "give %player% %item% %count%", cmd -> {
            ran.add(cmd);
            if (cmd.contains("Alice")) {
                throw new RuntimeException("boom");
            }
        });

        assertEquals(List.of(
                "give Alice minecraft:diamond 1",
                "give Bob minecraft:emerald 1"
        ), ran);
    }

    @Test
    void queueDrainsInTargetKeyOrderCapsAt20PerTickAndClearsCleanly() {
        MilestoneCommandRunner.clearPending();
        try {
            // Simultaneous records out of TargetKey order (including tag + multiple counts)
            MilestoneCommandRunner.enqueueUnchecked(PLAYER_A, "Alice", TargetKey.of("minecraft:iron_ingot", 1), 100, false);
            MilestoneCommandRunner.enqueueUnchecked(PLAYER_A, "Alice", TargetKey.of("minecraft:diamond", 100), 100, false);
            MilestoneCommandRunner.enqueueUnchecked(PLAYER_A, "Alice", TargetKey.of("#c:ingots/iron", 1), 100, true);
            MilestoneCommandRunner.enqueueUnchecked(PLAYER_A, "Alice", TargetKey.of("minecraft:diamond", 1), 100, false);

            List<MilestoneCommandRunner.QueuedCommand> first = MilestoneCommandRunner.drainForTick();
            assertEquals(List.of(
                    TargetKey.of("#c:ingots/iron", 1),
                    TargetKey.of("minecraft:diamond", 1),
                    TargetKey.of("minecraft:diamond", 100),
                    TargetKey.of("minecraft:iron_ingot", 1)
            ), first.stream().map(MilestoneCommandRunner.QueuedCommand::key).toList());
            assertEquals(0, MilestoneCommandRunner.pendingSize());

            // 25 simultaneous records -> 20 this tick, 5 next tick
            for (int i = 25; i >= 1; i--) {
                MilestoneCommandRunner.enqueueUnchecked(
                        PLAYER_A, "Alice", TargetKey.of("minecraft:diamond", i), 200, false);
            }
            List<MilestoneCommandRunner.QueuedCommand> tick1 = MilestoneCommandRunner.drainForTick();
            assertEquals(20, tick1.size());
            assertEquals(1, tick1.get(0).key().count());
            assertEquals(20, tick1.get(19).key().count());
            assertEquals(5, MilestoneCommandRunner.pendingSize());

            List<MilestoneCommandRunner.QueuedCommand> tick2 = MilestoneCommandRunner.drainForTick();
            assertEquals(5, tick2.size());
            assertEquals(21, tick2.get(0).key().count());
            assertEquals(25, tick2.get(4).key().count());
            assertEquals(0, MilestoneCommandRunner.pendingSize());

            // clearPending drops queued items
            MilestoneCommandRunner.enqueueUnchecked(PLAYER_A, "Alice", TargetKey.of("minecraft:coal", 1), 0, false);
            assertEquals(1, MilestoneCommandRunner.pendingSize());
            MilestoneCommandRunner.clearPending();
            assertEquals(0, MilestoneCommandRunner.pendingSize());
            assertTrue(MilestoneCommandRunner.drainForTick().isEmpty());
        } finally {
            MilestoneCommandRunner.clearPending();
        }
    }
}
