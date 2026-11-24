package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-Java unit tests for F7 {@link MilestoneCommandLogic}. */
class MilestoneCommandLogicTest {
    private static final UUID PLAYER_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAYER_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void playerNameValidationAcceptsStandardBedrockAndExtendedLengths() {
        assertTrue(MilestoneCommandLogic.isValidPlayerName("a"));
        assertTrue(MilestoneCommandLogic.isValidPlayerName("Steve"));
        assertTrue(MilestoneCommandLogic.isValidPlayerName("Player_123"));
        assertTrue(MilestoneCommandLogic.isValidPlayerName("1234567890123456")); // 16 chars
        assertTrue(MilestoneCommandLogic.isValidPlayerName(".BedrockUser"));
        assertTrue(MilestoneCommandLogic.isValidPlayerName("*Floodgate_1"));
        assertTrue(MilestoneCommandLogic.isValidPlayerName("a.b*c+d-e_f"));
        assertTrue(MilestoneCommandLogic.isValidPlayerName("12345678901234567")); // 17 chars
        assertTrue(MilestoneCommandLogic.isValidPlayerName("123456789012345678")); // 18 chars
        assertTrue(MilestoneCommandLogic.isValidPlayerName("1234567890123456789")); // 19 chars
        assertTrue(MilestoneCommandLogic.isValidPlayerName("12345678901234567890")); // 20 chars
        assertTrue(MilestoneCommandLogic.isValidPlayerName(".BedrockPlayerName12")); // 20 chars with dot prefix
        assertTrue(MilestoneCommandLogic.isValidPlayerName("*GeyserPlayerName123")); // 20 chars with asterisk prefix
    }

    @Test
    void playerNameValidationRejectsSelectorsWhitespaceInvalidCharsAndOver20Chars() {
        assertFalse(MilestoneCommandLogic.isValidPlayerName(null));
        assertFalse(MilestoneCommandLogic.isValidPlayerName(""));
        assertFalse(MilestoneCommandLogic.isValidPlayerName("@a"));
        assertFalse(MilestoneCommandLogic.isValidPlayerName("@p"));
        assertFalse(MilestoneCommandLogic.isValidPlayerName("@e"));
        assertFalse(MilestoneCommandLogic.isValidPlayerName("@s"));
        assertFalse(MilestoneCommandLogic.isValidPlayerName("@r"));
        assertFalse(MilestoneCommandLogic.isValidPlayerName("@e[type=player]"));
        assertFalse(MilestoneCommandLogic.isValidPlayerName("Alice@home"));
        assertFalse(MilestoneCommandLogic.isValidPlayerName("Alice Bob"));
        assertFalse(MilestoneCommandLogic.isValidPlayerName(" Alice"));
        assertFalse(MilestoneCommandLogic.isValidPlayerName("Alice "));
        assertFalse(MilestoneCommandLogic.isValidPlayerName("Alice\tBob"));
        assertFalse(MilestoneCommandLogic.isValidPlayerName("Alice\n"));
        assertFalse(MilestoneCommandLogic.isValidPlayerName("123456789012345678901")); // 21 chars
        assertFalse(MilestoneCommandLogic.isValidPlayerName("a".repeat(32)));
        assertFalse(MilestoneCommandLogic.isValidPlayerName("Alice;op"));
        assertFalse(MilestoneCommandLogic.isValidPlayerName("Alice/Bob"));
        assertFalse(MilestoneCommandLogic.isValidPlayerName("Alice\"Bob"));
        assertFalse(MilestoneCommandLogic.isValidPlayerName("Alice'Bob"));
        assertFalse(MilestoneCommandLogic.isValidPlayerName("Alice$1"));
        assertFalse(MilestoneCommandLogic.isValidPlayerName("Alice[1]"));
        assertFalse(MilestoneCommandLogic.isValidPlayerName("プレイヤー"));
    }

    @Test
    void renderSubstitutesEachPlaceholderAndAllTogether() {
        MilestoneCommandLogic.RenderResult playerOnly = MilestoneCommandLogic.render(
                "give %player% minecraft:firework_rocket 3",
                "Alice", PLAYER_A, "minecraft:diamond", 1, "0h 05m 00s", false);
        assertEquals(MilestoneCommandLogic.RenderStatus.READY, playerOnly.status());
        assertEquals("give Alice minecraft:firework_rocket 3", playerOnly.command());

        MilestoneCommandLogic.RenderResult uuidOnly = MilestoneCommandLogic.render(
                "tag %uuid% add got_item",
                "Alice", PLAYER_A, "minecraft:diamond", 1, "0h 05m 00s", false);
        assertEquals("tag 11111111-1111-1111-1111-111111111111 add got_item", uuidOnly.command());

        MilestoneCommandLogic.RenderResult itemOnly = MilestoneCommandLogic.render(
                "say %item%",
                "Alice", PLAYER_A, "minecraft:netherite_ingot", 1, "0h 05m 00s", false);
        assertEquals("say minecraft:netherite_ingot", itemOnly.command());

        MilestoneCommandLogic.RenderResult tagItem = MilestoneCommandLogic.render(
                "say %item% x%count%",
                "Alice", PLAYER_A, "#c:ingots/iron", 100, "27h 15m 30s", true);
        assertEquals("say #c:ingots/iron x100", tagItem.command());

        MilestoneCommandLogic.RenderResult playtimeWithSpaces = MilestoneCommandLogic.render(
                "say playtime=%playtime%",
                "Alice", PLAYER_A, "minecraft:diamond", 1, "27h 15m 30s", false);
        assertEquals("say playtime=27h 15m 30s", playtimeWithSpaces.command());

        MilestoneCommandLogic.RenderResult worldFirstTrue = MilestoneCommandLogic.render(
                "say wf=%worldfirst%",
                "Alice", PLAYER_A, "minecraft:diamond", 1, "0h 00m 00s", true);
        assertEquals("say wf=true", worldFirstTrue.command());

        MilestoneCommandLogic.RenderResult worldFirstFalse = MilestoneCommandLogic.render(
                "say wf=%worldfirst%",
                "Alice", PLAYER_A, "minecraft:diamond", 1, "0h 00m 00s", false);
        assertEquals("say wf=false", worldFirstFalse.command());

        MilestoneCommandLogic.RenderResult allTogether = MilestoneCommandLogic.render(
                "  tellraw @a {\"text\":\"%player% (%uuid%) %item% #%count% [%playtime%] wf=%worldfirst% %player% 100% %unknown%\"}  ",
                ".Bedrock_01", PLAYER_A, "#c:ingots/iron", 64, "27h 15m 30s", true);
        assertTrue(allTogether.isReady());
        assertEquals(
                "tellraw @a {\"text\":\".Bedrock_01 (11111111-1111-1111-1111-111111111111) #c:ingots/iron #64 [27h 15m 30s] wf=true .Bedrock_01 100% %unknown%\"}",
                allTogether.command());
    }

    @Test
    void renderHandlesDisabledTemplatesAndInvalidPlayerNames() {
        assertEquals(MilestoneCommandLogic.RenderStatus.DISABLED,
                MilestoneCommandLogic.render(null, "Alice", PLAYER_A, "minecraft:diamond", 1, "0h 00m 00s", false).status());
        assertEquals(MilestoneCommandLogic.RenderStatus.DISABLED,
                MilestoneCommandLogic.render("", "Alice", PLAYER_A, "minecraft:diamond", 1, "0h 00m 00s", false).status());
        assertEquals(MilestoneCommandLogic.RenderStatus.DISABLED,
                MilestoneCommandLogic.render("   \t  ", "Alice", PLAYER_A, "minecraft:diamond", 1, "0h 00m 00s", false).status());

        for (String badName : new String[]{"@a", "@p", "Alice Bob", "", null, "a".repeat(21), "Alice;op"}) {
            MilestoneCommandLogic.RenderResult res = MilestoneCommandLogic.render(
                    "give %player% minecraft:diamond 1",
                    badName, PLAYER_A, "minecraft:diamond", 1, "0h 00m 00s", false);
            assertEquals(MilestoneCommandLogic.RenderStatus.INVALID_PLAYER_NAME, res.status(),
                    "expected INVALID_PLAYER_NAME for: " + badName);
            assertFalse(res.isReady());
            assertNull(res.command());
        }

        // Templates using %%player%% still contain %player% and must validate playerName.
        MilestoneCommandLogic.RenderResult doublePercentBad = MilestoneCommandLogic.render(
                "say %%player%%", "@a", PLAYER_A, "minecraft:diamond", 1, "0h 00m 00s", false);
        assertEquals(MilestoneCommandLogic.RenderStatus.INVALID_PLAYER_NAME, doublePercentBad.status());

        // Templates not referencing %player% (e.g. targeting by %uuid%) do not embed the unvalidated name.
        MilestoneCommandLogic.RenderResult uuidFallback = MilestoneCommandLogic.render(
                "give %uuid% minecraft:firework_rocket 3",
                "@a", PLAYER_A, "minecraft:diamond", 1, "0h 00m 00s", false);
        assertEquals(MilestoneCommandLogic.RenderStatus.READY, uuidFallback.status());
        assertEquals("give 11111111-1111-1111-1111-111111111111 minecraft:firework_rocket 3", uuidFallback.command());
    }

    @Test
    void commandQueueOrdersSimultaneousRecordsPerPlayerCapsPerTickAndPreservesFifoAcrossTicks() {
        MilestoneCommandLogic.CommandQueue<String> queue =
                new MilestoneCommandLogic.CommandQueue<String>(Comparator.naturalOrder(), 20);
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.pendingSize());
        assertTrue(queue.drainForTick().isEmpty());

        // Simultaneous records for A (out of order) and B in the same tick:
        // player insertion order is preserved (A then B), each player's batch sorted by comparator.
        queue.enqueue(PLAYER_A, "minecraft:iron_ingot");
        queue.enqueue(PLAYER_B, "minecraft:gold_ingot");
        queue.enqueue(PLAYER_A, "#c:ingots/iron");
        queue.enqueue(PLAYER_A, "minecraft:diamond#100");
        queue.enqueue(PLAYER_A, "minecraft:diamond#1");
        queue.enqueue(PLAYER_B, "minecraft:coal");
        assertEquals(6, queue.pendingSize());

        List<String> tick1 = queue.drainForTick();
        assertEquals(List.of(
                "#c:ingots/iron",
                "minecraft:diamond#1",
                "minecraft:diamond#100",
                "minecraft:iron_ingot",
                "minecraft:coal",
                "minecraft:gold_ingot"
        ), tick1);
        assertTrue(queue.isEmpty());

        // 45 simultaneous entries drain as 20, 20, 5, 0 across consecutive ticks,
        // and new entries enqueued on tick 2 wait behind tick 1's remainder.
        List<String> expectedSorted = new ArrayList<>();
        for (int i = 45; i >= 1; i--) {
            String key = String.format("minecraft:item_%02d", i);
            queue.enqueue(PLAYER_A, key);
        }
        for (int i = 1; i <= 45; i++) {
            expectedSorted.add(String.format("minecraft:item_%02d", i));
        }

        List<String> batch1 = queue.drainForTick();
        assertEquals(expectedSorted.subList(0, 20), batch1);
        assertEquals(25, queue.pendingSize());

        // Enqueue a new entry while batch1 is executing: must NOT run until tick 1's remainder finishes.
        queue.enqueue(PLAYER_B, "minecraft:after_tick1");

        List<String> batch2 = queue.drainForTick();
        assertEquals(expectedSorted.subList(20, 40), batch2);
        assertEquals(6, queue.pendingSize());

        List<String> batch3 = queue.drainForTick();
        List<String> expectedBatch3 = new ArrayList<>(expectedSorted.subList(40, 45));
        expectedBatch3.add("minecraft:after_tick1");
        assertEquals(expectedBatch3, batch3);
        assertTrue(queue.isEmpty());
        assertTrue(queue.drainForTick().isEmpty());

        // clear() discards both current-tick and ready items
        for (int i = 0; i < 25; i++) {
            queue.enqueue(PLAYER_A, "x" + i);
        }
        queue.drainForTick(); // 5 left in ready
        queue.enqueue(PLAYER_B, "y"); // 1 in currentTickByPlayer
        assertEquals(6, queue.pendingSize());
        queue.clear();
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.pendingSize());

        assertThrows(IllegalArgumentException.class,
                () -> new MilestoneCommandLogic.CommandQueue<String>(Comparator.naturalOrder(), 0));
    }

    @Test
    void callbackTrackerDistinguishesSuccessZeroResultFailureUninvokedAndForked() {
        MilestoneCommandLogic.CallbackTracker uninvoked = new MilestoneCommandLogic.CallbackTracker();
        assertFalse(uninvoked.invoked());
        assertFalse(uninvoked.succeeded());

        MilestoneCommandLogic.CallbackTracker success = new MilestoneCommandLogic.CallbackTracker();
        success.onResult(true, 1);
        assertTrue(success.invoked());
        assertTrue(success.succeeded());
        assertEquals(1, success.lastResult());

        MilestoneCommandLogic.CallbackTracker zeroResult = new MilestoneCommandLogic.CallbackTracker();
        zeroResult.onResult(true, 0);
        assertTrue(zeroResult.invoked());
        assertFalse(zeroResult.succeeded());
        assertEquals(0, zeroResult.lastResult());

        MilestoneCommandLogic.CallbackTracker failedFlag = new MilestoneCommandLogic.CallbackTracker();
        failedFlag.onResult(false, 1);
        assertTrue(failedFlag.invoked());
        assertFalse(failedFlag.succeeded());

        MilestoneCommandLogic.CallbackTracker forkedAnySuccess = new MilestoneCommandLogic.CallbackTracker();
        forkedAnySuccess.onResult(false, 0);
        forkedAnySuccess.onResult(true, 3);
        forkedAnySuccess.onResult(true, 0);
        assertTrue(forkedAnySuccess.succeeded());

        MilestoneCommandLogic.CallbackTracker forkedAllFail = new MilestoneCommandLogic.CallbackTracker();
        forkedAllFail.onResult(false, 0);
        forkedAllFail.onResult(true, 0);
        assertFalse(forkedAllFail.succeeded());
    }
}
