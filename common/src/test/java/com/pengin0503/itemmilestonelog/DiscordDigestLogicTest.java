package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordDigestLogicTest {
    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CHARLIE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final long BASE = 1_700_000_000_000L;

    @Test
    void decideActionEnforcesDisabledNoWebhookFirstRunAndIntervalBoundaries() {
        long sevenDays = 7L * DiscordDigestLogic.MILLIS_PER_DAY;

        // Disabled -> SKIP even on first run (lastDigestEpoch == 0)
        assertEquals(DiscordDigestLogic.CheckAction.SKIP,
                DiscordDigestLogic.decideAction(false, true, 0L, BASE, 7));
        assertEquals(DiscordDigestLogic.CheckAction.SKIP,
                DiscordDigestLogic.decideAction(false, true, BASE, BASE + sevenDays, 7));

        // Webhook not configured -> SKIP even on first run
        assertEquals(DiscordDigestLogic.CheckAction.SKIP,
                DiscordDigestLogic.decideAction(true, false, 0L, BASE, 7));
        assertEquals(DiscordDigestLogic.CheckAction.SKIP,
                DiscordDigestLogic.decideAction(true, false, BASE, BASE + sevenDays, 7));

        // Non-positive current time -> SKIP
        assertEquals(DiscordDigestLogic.CheckAction.SKIP,
                DiscordDigestLogic.decideAction(true, true, 0L, 0L, 7));

        // First run (lastDigestEpoch == 0) -> record timestamp only, send nothing
        assertEquals(DiscordDigestLogic.CheckAction.INIT_TIMESTAMP_ONLY,
                DiscordDigestLogic.decideAction(true, true, 0L, BASE, 7));

        // Before interval boundary -> SKIP (including 1ms short and clock skew)
        assertEquals(DiscordDigestLogic.CheckAction.SKIP,
                DiscordDigestLogic.decideAction(true, true, BASE, BASE - 1000L, 7));
        assertEquals(DiscordDigestLogic.CheckAction.SKIP,
                DiscordDigestLogic.decideAction(true, true, BASE, BASE, 7));
        assertEquals(DiscordDigestLogic.CheckAction.SKIP,
                DiscordDigestLogic.decideAction(true, true, BASE, BASE + sevenDays - 1L, 7));

        // Exact interval boundary and beyond -> COLLECT_AND_SEND
        assertEquals(DiscordDigestLogic.CheckAction.COLLECT_AND_SEND,
                DiscordDigestLogic.decideAction(true, true, BASE, BASE + sevenDays, 7));
        assertEquals(DiscordDigestLogic.CheckAction.COLLECT_AND_SEND,
                DiscordDigestLogic.decideAction(true, true, BASE, BASE + sevenDays + 1000L, 7));

        // Interval clamping: <1 clamps to 1 day, >30 clamps to 30 days
        assertEquals(1, DiscordDigestLogic.clampIntervalDays(0));
        assertEquals(1, DiscordDigestLogic.clampIntervalDays(-5));
        assertEquals(30, DiscordDigestLogic.clampIntervalDays(31));
        assertEquals(DiscordDigestLogic.CheckAction.COLLECT_AND_SEND,
                DiscordDigestLogic.decideAction(true, true, BASE, BASE + DiscordDigestLogic.MILLIS_PER_DAY, 0));
        assertEquals(DiscordDigestLogic.CheckAction.SKIP,
                DiscordDigestLogic.decideAction(true, true, BASE, BASE + 29L * DiscordDigestLogic.MILLIS_PER_DAY, 99));
        assertEquals(DiscordDigestLogic.CheckAction.COLLECT_AND_SEND,
                DiscordDigestLogic.decideAction(true, true, BASE, BASE + 30L * DiscordDigestLogic.MILLIS_PER_DAY, 99));
    }

    @Test
    void windowBoundariesAreHalfOpenAndExcludeUnknownEpochZero() {
        long start = BASE;
        long end = BASE + 7L * DiscordDigestLogic.MILLIS_PER_DAY;

        assertFalse(DiscordDigestLogic.isInWindow(0L, 0L, end));
        assertFalse(DiscordDigestLogic.isInWindow(0L, start, end));
        assertFalse(DiscordDigestLogic.isInWindow(-1L, start, end));
        assertFalse(DiscordDigestLogic.isInWindow(start - 1L, start, end));
        assertFalse(DiscordDigestLogic.isInWindow(start, start, end), "lower bound is exclusive");
        assertTrue(DiscordDigestLogic.isInWindow(start + 1L, start, end));
        assertTrue(DiscordDigestLogic.isInWindow(end, start, end), "upper bound is inclusive");
        assertFalse(DiscordDigestLogic.isInWindow(end + 1L, start, end));
    }

    @Test
    void extractEntriesHonorsWindowBoundariesZeroEpochAndAscendingSortOrder() {
        long start = BASE;
        long end = BASE + 7L * DiscordDigestLogic.MILLIS_PER_DAY;

        DiscordDigestLogic.PlayerSnapshot alice = new DiscordDigestLogic.PlayerSnapshot(
                ALICE, "Alice", false, List.of(
                        new DiscordDigestLogic.RecordSnapshot("minecraft:coal", 1, 100, 0L, 0L), // epoch 0 -> excluded
                        new DiscordDigestLogic.RecordSnapshot("minecraft:iron_ingot", 1, 200, 1L, start), // == start -> excluded
                        new DiscordDigestLogic.RecordSnapshot("minecraft:gold_ingot", 1, 300, 2L, start + 1L), // included (earliest)
                        new DiscordDigestLogic.RecordSnapshot("minecraft:diamond", 1, 400, 3L, end), // == end -> included (latest)
                        new DiscordDigestLogic.RecordSnapshot("minecraft:emerald", 1, 500, 4L, end + 1L) // > end -> excluded
                ));
        DiscordDigestLogic.PlayerSnapshot bob = new DiscordDigestLogic.PlayerSnapshot(
                BOB, "Bob", false, List.of(
                        new DiscordDigestLogic.RecordSnapshot("#c:ingots/iron", 64, 600, 2L, start + 5000L), // middle
                        new DiscordDigestLogic.RecordSnapshot("minecraft:diamond", 1, 700, 3L, end) // ties with Alice at end
                ));

        List<DiscordDigestLogic.DigestEntry> entries = DiscordDigestLogic.extractEntries(
                List.of(alice, bob), List.of(), start, end, "all", true);

        assertEquals(4, entries.size());
        assertEquals("minecraft:gold_ingot", entries.get(0).itemId());
        assertEquals(start + 1L, entries.get(0).epochMillis());
        assertEquals("#c:ingots/iron", entries.get(1).itemId());
        assertEquals(64, entries.get(1).count());
        assertEquals(start + 5000L, entries.get(1).epochMillis());
        // Tie at `end`: sorted by player name ascending ("Alice" before "Bob")
        assertEquals("Alice", entries.get(2).playerName());
        assertEquals(end, entries.get(2).epochMillis());
        assertEquals("Bob", entries.get(3).playerName());
        assertEquals(end, entries.get(3).epochMillis());

        // First run (lastDigestEpoch == 0) never extracts entries
        assertTrue(DiscordDigestLogic.extractEntries(List.of(alice, bob), List.of(), 0L, end, "all", true).isEmpty());
    }

    @Test
    void extractEntriesHonorsScopeFilteringAndF47NamesWithoutRenamedSuffix() {
        long start = BASE;
        long end = BASE + 7L * DiscordDigestLogic.MILLIS_PER_DAY;

        // Alice renamed from OldAlice -> NewAlice; Bob recorded diamond after Alice (not world first)
        // Charlie was purged (no PlayerSnapshot) but holds a world first for emerald
        DiscordDigestLogic.PlayerSnapshot alice = new DiscordDigestLogic.PlayerSnapshot(
                ALICE, "NewAlice", false, List.of(
                        new DiscordDigestLogic.RecordSnapshot("minecraft:diamond", 1, 1000, 1L, start + 1000L),
                        new DiscordDigestLogic.RecordSnapshot("minecraft:iron_ingot", 10, 2000, 2L, start + 3000L)
                ));
        DiscordDigestLogic.PlayerSnapshot bob = new DiscordDigestLogic.PlayerSnapshot(
                BOB, "Bob", false, List.of(
                        new DiscordDigestLogic.RecordSnapshot("minecraft:diamond", 1, 3000, 3L, start + 2000L)
                ));
        List<DiscordDigestLogic.WorldFirstSnapshot> worldFirsts = List.of(
                new DiscordDigestLogic.WorldFirstSnapshot("minecraft:diamond", 1, ALICE, "OldAlice", 1000, 1L, start + 1000L),
                new DiscordDigestLogic.WorldFirstSnapshot("minecraft:emerald", 1, CHARLIE, "PurgedCharlie", 1500, 2L, start + 4000L),
                new DiscordDigestLogic.WorldFirstSnapshot("minecraft:netherite_ingot", 1, CHARLIE, "", 2500, 2L, start + 5000L)
        );

        // Scope "all": includes Alice's WF (no duplicate), Bob's non-WF diamond, Alice's non-WF iron,
        // and Charlie's purged WFs (rule 3: recordedName or UUID)
        List<DiscordDigestLogic.DigestEntry> all = DiscordDigestLogic.extractEntries(
                List.of(alice, bob), worldFirsts, start, end, "all", true);
        assertEquals(5, all.size());

        assertEquals("NewAlice", all.get(0).playerName());
        assertFalse(all.get(0).playerName().contains("OldAlice"), "F47 digest name must omit (was ...) suffix");
        assertTrue(all.get(0).worldFirst());

        assertEquals("Bob", all.get(1).playerName());
        assertFalse(all.get(1).worldFirst());

        assertEquals("NewAlice", all.get(2).playerName());
        assertEquals("minecraft:iron_ingot", all.get(2).itemId());
        assertFalse(all.get(2).worldFirst());

        assertEquals("PurgedCharlie", all.get(3).playerName());
        assertTrue(all.get(3).worldFirst());

        assertEquals(CHARLIE.toString(), all.get(4).playerName());
        assertTrue(all.get(4).worldFirst());

        // Scope "world_first": only the 3 world-first entries
        List<DiscordDigestLogic.DigestEntry> wfOnly = DiscordDigestLogic.extractEntries(
                List.of(alice, bob), worldFirsts, start, end, "world_first", true);
        assertEquals(3, wfOnly.size());
        assertEquals("NewAlice", wfOnly.get(0).playerName());
        assertTrue(wfOnly.get(0).worldFirst());
        assertEquals("PurgedCharlie", wfOnly.get(1).playerName());
        assertTrue(wfOnly.get(1).worldFirst());
        assertEquals(CHARLIE.toString(), wfOnly.get(2).playerName());
        assertTrue(wfOnly.get(2).worldFirst());
    }

    @Test
    void extractEntriesExcludesF45OptedOutPlayersWhenAllowedAndIncludesWhenDisallowed() {
        long start = BASE;
        long end = BASE + 7L * DiscordDigestLogic.MILLIS_PER_DAY;

        DiscordDigestLogic.PlayerSnapshot optedOutAlice = new DiscordDigestLogic.PlayerSnapshot(
                ALICE, "Alice", true, List.of(
                        new DiscordDigestLogic.RecordSnapshot("minecraft:diamond", 1, 1000, 1L, start + 1000L)
                ));
        DiscordDigestLogic.PlayerSnapshot normalBob = new DiscordDigestLogic.PlayerSnapshot(
                BOB, "Bob", false, List.of(
                        new DiscordDigestLogic.RecordSnapshot("minecraft:emerald", 1, 2000, 2L, start + 2000L)
                ));
        List<DiscordDigestLogic.WorldFirstSnapshot> worldFirsts = List.of(
                new DiscordDigestLogic.WorldFirstSnapshot("minecraft:diamond", 1, ALICE, "Alice", 1000, 1L, start + 1000L),
                new DiscordDigestLogic.WorldFirstSnapshot("minecraft:emerald", 1, BOB, "Bob", 2000, 2L, start + 2000L)
        );

        // allowDiscordOptOut = true -> Alice excluded in BOTH "all" and "world_first"
        List<DiscordDigestLogic.DigestEntry> allFiltered = DiscordDigestLogic.extractEntries(
                List.of(optedOutAlice, normalBob), worldFirsts, start, end, "all", true);
        assertEquals(1, allFiltered.size());
        assertEquals(BOB, allFiltered.get(0).playerUuid());

        List<DiscordDigestLogic.DigestEntry> wfFiltered = DiscordDigestLogic.extractEntries(
                List.of(optedOutAlice, normalBob), worldFirsts, start, end, "world_first", true);
        assertEquals(1, wfFiltered.size());
        assertEquals(BOB, wfFiltered.get(0).playerUuid());

        // allowDiscordOptOut = false -> Alice's saved opt-out is ignored, both included
        List<DiscordDigestLogic.DigestEntry> allUnfiltered = DiscordDigestLogic.extractEntries(
                List.of(optedOutAlice, normalBob), worldFirsts, start, end, "all", false);
        assertEquals(2, allUnfiltered.size());

        List<DiscordDigestLogic.DigestEntry> wfUnfiltered = DiscordDigestLogic.extractEntries(
                List.of(optedOutAlice, normalBob), worldFirsts, start, end, "world_first", false);
        assertEquals(2, wfUnfiltered.size());
    }

    @Test
    void formatLineEscapesPlayerAndItemMarkdownKeepsPlaytimeUnescapedAndPrefixesWorldFirst() {
        String normalEn = DiscordDigestLogic.formatLine(
                "%s — %s (%s)", "_Steve_ [OP]", "#c:ingots/iron ×64", "1h 00m 00s (Day 2, 2023-11-14)", false);
        assertEquals("\\_Steve\\_ \\[OP\\] — \\#c:ingots/iron ×64 (1h 00m 00s (Day 2, 2023-11-14))", normalEn);

        String wfJa = DiscordDigestLogic.formatLine(
                "%s — %s（%s）", "Alice*1", "Super_Sword", "2h 05m 10s (Day 3, 2023-11-15)", true);
        assertEquals("★ Alice\\*1 — Super\\_Sword（2h 05m 10s (Day 3, 2023-11-15)）", wfJa);
    }

    @Test
    void assemblyEnforces20LineCapAndDropsTrailingLinesWhenCharLimitsExceeded() {
        // 25 short lines -> capped at 20 + "...and 5 more"
        List<String> twentyFive = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            twentyFive.add("Player" + i + " — Item" + i + " (1h 00m 00s)");
        }

        String plain = DiscordDigestLogic.assemblePlainText(
                "Milestones in the last 7 day(s)", twentyFive, n -> "...and " + n + " more");
        String[] plainLines = plain.split("\n");
        assertEquals(22, plainLines.length);
        assertEquals("Milestones in the last 7 day(s)", plainLines[0]);
        assertEquals(twentyFive.get(0), plainLines[1]);
        assertEquals(twentyFive.get(19), plainLines[20]);
        assertEquals("...and 5 more", plainLines[21]);
        assertTrue(plain.length() <= NotificationGrouping.DISCORD_PLAIN_MAX_CHARS);

        String embedDesc = DiscordDigestLogic.assembleEmbedDescription(twentyFive, n -> "...and " + n + " more");
        String[] embedLines = embedDesc.split("\n");
        assertEquals(21, embedLines.length);
        assertEquals(twentyFive.get(0), embedLines[0]);
        assertEquals(twentyFive.get(19), embedLines[19]);
        assertEquals("...and 5 more", embedLines[20]);
        assertTrue(embedDesc.length() <= NotificationGrouping.DISCORD_EMBED_DESCRIPTION_MAX_CHARS);

        // Long lines that exceed 2000 (plain) and 4096 (embed) even below 20 lines:
        // trailing lines are dropped and the "more" count increases until within the limit.
        List<String> longLines = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            longLines.add("Line" + i + " — " + "X".repeat(250));
        }

        String cappedPlain = DiscordDigestLogic.assemblePlainText(
                "Milestones in the last 7 day(s)", longLines, n -> "...and " + n + " more");
        assertTrue(cappedPlain.length() <= NotificationGrouping.DISCORD_PLAIN_MAX_CHARS);
        String[] cappedPlainLines = cappedPlain.split("\n");
        int shownPlain = cappedPlainLines.length - 2; // minus title and more line
        assertTrue(shownPlain > 0 && shownPlain < 20);
        assertEquals("...and " + (20 - shownPlain) + " more", cappedPlainLines[cappedPlainLines.length - 1]);

        String cappedEmbed = DiscordDigestLogic.assembleEmbedDescription(longLines, n -> "…ほか " + n + " 件");
        assertTrue(cappedEmbed.length() <= NotificationGrouping.DISCORD_EMBED_DESCRIPTION_MAX_CHARS);
        String[] cappedEmbedLines = cappedEmbed.split("\n");
        int shownEmbed = cappedEmbedLines.length - 1; // minus more line
        assertTrue(shownEmbed > 0 && shownEmbed < 20);
        assertEquals("…ほか " + (20 - shownEmbed) + " 件", cappedEmbedLines[cappedEmbedLines.length - 1]);
    }

    @Test
    void tickThrottlerFiresOncePerIntervalAndResetsCleanly() {
        DiscordDigestLogic.TickThrottler throttler = new DiscordDigestLogic.TickThrottler(3);
        assertFalse(throttler.tick());
        assertFalse(throttler.tick());
        assertTrue(throttler.tick());
        assertFalse(throttler.tick());
        throttler.reset();
        assertFalse(throttler.tick());
        assertFalse(throttler.tick());
        assertTrue(throttler.tick());
    }
}
