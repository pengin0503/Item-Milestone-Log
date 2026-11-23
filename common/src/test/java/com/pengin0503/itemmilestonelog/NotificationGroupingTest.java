package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationGroupingTest {
    private record StubRecord(String key, boolean worldFirst) {
    }

    private static final Comparator<StubRecord> BY_KEY = Comparator.comparing(StubRecord::key);

    private static final NotificationGrouping.DiscordTemplates JA_TEMPLATES = new NotificationGrouping.DiscordTemplates(
            "★ マイルストーン記録: %s が %s 件を入手（%s）",
            "- %s",
            "- ★ [世界初] %s",
            "…ほか %s 件"
    );

    @Test
    void emptyRecordsProduceNoSendsAndNoSound() {
        NotificationGrouping.FlushPlan<StubRecord> empty = NotificationGrouping.planFlush(
                List.of(), StubRecord::worldFirst, BY_KEY, true, true, true, true, "all");
        assertEquals(NotificationGrouping.SendMode.NONE, empty.playerChat().mode());
        assertEquals(NotificationGrouping.SendMode.NONE, empty.broadcastChat().mode());
        assertEquals(NotificationGrouping.SendMode.NONE, empty.worldFirstChat().mode());
        assertEquals(NotificationGrouping.SendMode.NONE, empty.discord().mode());
        assertFalse(empty.playSound());
    }

    @Test
    void thresholdThreeDecidesIndividualVsGroupedPerChannel() {
        // 1 record -> INDIVIDUAL on all active channels
        List<StubRecord> one = List.of(new StubRecord("minecraft:diamond", true));
        NotificationGrouping.FlushPlan<StubRecord> plan1 = NotificationGrouping.planFlush(
                one, StubRecord::worldFirst, BY_KEY, true, true, true, true, "all");
        assertEquals(NotificationGrouping.SendMode.INDIVIDUAL, plan1.playerChat().mode());
        assertEquals(NotificationGrouping.SendMode.INDIVIDUAL, plan1.broadcastChat().mode());
        assertEquals(NotificationGrouping.SendMode.INDIVIDUAL, plan1.worldFirstChat().mode());
        assertEquals(NotificationGrouping.SendMode.INDIVIDUAL, plan1.discord().mode());
        assertTrue(plan1.playSound());

        // 2 records -> INDIVIDUAL (preserves input order)
        List<StubRecord> two = List.of(
                new StubRecord("minecraft:emerald", true),
                new StubRecord("minecraft:coal", true));
        NotificationGrouping.FlushPlan<StubRecord> plan2 = NotificationGrouping.planFlush(
                two, StubRecord::worldFirst, BY_KEY, true, true, true, true, "all");
        assertEquals(NotificationGrouping.SendMode.INDIVIDUAL, plan2.playerChat().mode());
        assertEquals(two, plan2.playerChat().items());
        assertEquals(NotificationGrouping.SendMode.INDIVIDUAL, plan2.broadcastChat().mode());
        assertEquals(NotificationGrouping.SendMode.INDIVIDUAL, plan2.worldFirstChat().mode());
        assertEquals(NotificationGrouping.SendMode.INDIVIDUAL, plan2.discord().mode());
        assertTrue(plan2.playSound());

        // 3 records -> GROUPED (sorted by groupSortOrder)
        List<StubRecord> three = List.of(
                new StubRecord("minecraft:emerald", true),
                new StubRecord("minecraft:coal", true),
                new StubRecord("minecraft:diamond", true));
        NotificationGrouping.FlushPlan<StubRecord> plan3 = NotificationGrouping.planFlush(
                three, StubRecord::worldFirst, BY_KEY, true, true, true, true, "all");
        List<StubRecord> expectedSorted = List.of(
                new StubRecord("minecraft:coal", true),
                new StubRecord("minecraft:diamond", true),
                new StubRecord("minecraft:emerald", true));
        assertEquals(NotificationGrouping.SendMode.GROUPED, plan3.playerChat().mode());
        assertEquals(expectedSorted, plan3.playerChat().items());
        assertEquals(NotificationGrouping.SendMode.GROUPED, plan3.broadcastChat().mode());
        assertEquals(expectedSorted, plan3.broadcastChat().items());
        assertEquals(NotificationGrouping.SendMode.GROUPED, plan3.worldFirstChat().mode());
        assertEquals(expectedSorted, plan3.worldFirstChat().items());
        assertEquals(NotificationGrouping.SendMode.GROUPED, plan3.discord().mode());
        assertEquals(expectedSorted, plan3.discord().items());
        assertTrue(plan3.playSound());
    }

    @Test
    void channelsCountEligibleRecordsIndependently() {
        // 4 records total, only 2 are world-first
        List<StubRecord> records = List.of(
                new StubRecord("minecraft:stone", false),
                new StubRecord("minecraft:emerald", true),
                new StubRecord("minecraft:coal", false),
                new StubRecord("minecraft:diamond", true));

        // discordNotifyScope = "world_first" -> worldFirst & discord have 2 (INDIVIDUAL), player & broadcast have 4 (GROUPED)
        NotificationGrouping.FlushPlan<StubRecord> wfScope = NotificationGrouping.planFlush(
                records, StubRecord::worldFirst, BY_KEY, true, true, true, true, "world_first");
        assertEquals(NotificationGrouping.SendMode.GROUPED, wfScope.playerChat().mode());
        assertEquals(4, wfScope.playerChat().items().size());
        assertEquals(NotificationGrouping.SendMode.GROUPED, wfScope.broadcastChat().mode());
        assertEquals(4, wfScope.broadcastChat().items().size());
        assertEquals(NotificationGrouping.SendMode.INDIVIDUAL, wfScope.worldFirstChat().mode());
        assertEquals(2, wfScope.worldFirstChat().items().size());
        assertEquals(NotificationGrouping.SendMode.INDIVIDUAL, wfScope.discord().mode());
        assertEquals(2, wfScope.discord().items().size());

        // discordNotifyScope = "all" -> discord has 4 (GROUPED), worldFirst still has 2 (INDIVIDUAL)
        NotificationGrouping.FlushPlan<StubRecord> allScope = NotificationGrouping.planFlush(
                records, StubRecord::worldFirst, BY_KEY, true, true, true, true, "all");
        assertEquals(NotificationGrouping.SendMode.GROUPED, allScope.discord().mode());
        assertEquals(4, allScope.discord().items().size());
        assertEquals(NotificationGrouping.SendMode.INDIVIDUAL, allScope.worldFirstChat().mode());
        assertEquals(2, allScope.worldFirstChat().items().size());

        // 4 records, 3 world-first, discordNotifyScope = "world_first" -> worldFirst & discord are GROUPED with only the 3 WF items
        List<StubRecord> threeWf = List.of(
                new StubRecord("minecraft:stone", false),
                new StubRecord("minecraft:iron_ingot", true),
                new StubRecord("minecraft:diamond", true),
                new StubRecord("minecraft:emerald", true));
        NotificationGrouping.FlushPlan<StubRecord> wfGrouped = NotificationGrouping.planFlush(
                threeWf, StubRecord::worldFirst, BY_KEY, true, true, true, true, "world_first");
        assertEquals(NotificationGrouping.SendMode.GROUPED, wfGrouped.worldFirstChat().mode());
        assertEquals(List.of("minecraft:diamond", "minecraft:emerald", "minecraft:iron_ingot"),
                wfGrouped.worldFirstChat().items().stream().map(StubRecord::key).toList());
        assertEquals(NotificationGrouping.SendMode.GROUPED, wfGrouped.discord().mode());
        assertEquals(List.of("minecraft:diamond", "minecraft:emerald", "minecraft:iron_ingot"),
                wfGrouped.discord().items().stream().map(StubRecord::key).toList());

        // 4 records, 0 world-first, discordNotifyScope = "world_first" -> worldFirst & discord are NONE
        List<StubRecord> zeroWf = List.of(
                new StubRecord("minecraft:a", false),
                new StubRecord("minecraft:b", false),
                new StubRecord("minecraft:c", false),
                new StubRecord("minecraft:d", false));
        NotificationGrouping.FlushPlan<StubRecord> noWf = NotificationGrouping.planFlush(
                zeroWf, StubRecord::worldFirst, BY_KEY, true, true, true, true, "world_first");
        assertEquals(NotificationGrouping.SendMode.NONE, noWf.worldFirstChat().mode());
        assertEquals(NotificationGrouping.SendMode.NONE, noWf.discord().mode());
        assertEquals(NotificationGrouping.SendMode.GROUPED, noWf.playerChat().mode());
    }

    @Test
    void respectsChannelEnableSwitchesIndependently() {
        List<StubRecord> three = List.of(
                new StubRecord("minecraft:a", true),
                new StubRecord("minecraft:b", true),
                new StubRecord("minecraft:c", true));

        NotificationGrouping.FlushPlan<StubRecord> allOffExceptSound = NotificationGrouping.planFlush(
                three, StubRecord::worldFirst, BY_KEY, false, false, false, true, "all");
        assertEquals(NotificationGrouping.SendMode.NONE, allOffExceptSound.playerChat().mode());
        assertEquals(NotificationGrouping.SendMode.NONE, allOffExceptSound.broadcastChat().mode());
        assertEquals(NotificationGrouping.SendMode.NONE, allOffExceptSound.worldFirstChat().mode());
        assertTrue(allOffExceptSound.playSound());
        assertEquals(NotificationGrouping.SendMode.GROUPED, allOffExceptSound.discord().mode());

        NotificationGrouping.FlushPlan<StubRecord> soundOff = NotificationGrouping.planFlush(
                three, StubRecord::worldFirst, BY_KEY, true, false, false, false, "all");
        assertEquals(NotificationGrouping.SendMode.GROUPED, soundOff.playerChat().mode());
        assertFalse(soundOff.playSound());
    }

    @Test
    void summarizeChatListCapsAtFiveAndCountsRemainder() {
        NotificationGrouping.ListSummary<String> empty = NotificationGrouping.summarizeChatList(List.of());
        assertTrue(empty.shown().isEmpty());
        assertEquals(0, empty.remainingCount());
        assertFalse(empty.hasMore());

        List<String> five = List.of("A", "B", "C", "D", "E");
        NotificationGrouping.ListSummary<String> s5 = NotificationGrouping.summarizeChatList(five);
        assertEquals(five, s5.shown());
        assertEquals(0, s5.remainingCount());
        assertFalse(s5.hasMore());
        assertEquals("A, B, C, D, E",
                NotificationGrouping.formatChatListText(five, ", ", ", and %s more"));
        assertEquals("A、B、C、D、E",
                NotificationGrouping.formatChatListText(five, "、", " ほか %s 件"));

        List<String> six = List.of("A", "B", "C", "D", "E", "F");
        NotificationGrouping.ListSummary<String> s6 = NotificationGrouping.summarizeChatList(six);
        assertEquals(five, s6.shown());
        assertEquals(1, s6.remainingCount());
        assertTrue(s6.hasMore());
        assertEquals("A, B, C, D, E, and 1 more",
                NotificationGrouping.formatChatListText(six, ", ", ", and %s more"));
        assertEquals("A、B、C、D、E ほか 1 件",
                NotificationGrouping.formatChatListText(six, "、", " ほか %s 件"));

        List<String> eight = List.of("A", "B", "C", "D", "E", "F", "G", "H");
        assertEquals("A, B, C, D, E, and 3 more",
                NotificationGrouping.formatChatListText(eight, ", ", ", and %s more"));
        assertEquals("A、B、C、D、E ほか 3 件",
                NotificationGrouping.formatChatListText(eight, "、", " ほか %s 件"));
    }

    @Test
    void buildsDiscordGroupPlainTextWithNormalAndWorldFirstLinesInEnglishAndJapanese() {
        List<NotificationGrouping.DiscordItem> items = List.of(
                new NotificationGrouping.DiscordItem("minecraft:coal", false),
                new NotificationGrouping.DiscordItem("minecraft:diamond ×64", true),
                new NotificationGrouping.DiscordItem("minecraft:emerald", false));

        String en = NotificationGrouping.buildDiscordGroupPlainText(
                "Alice", "1h 02m 03s (Day 2, 2026-11-23)", items, NotificationGrouping.DiscordTemplates.ENGLISH_DEFAULTS);
        assertEquals(String.join("\n",
                "★ Milestones recorded: Alice obtained 3 targets at 1h 02m 03s (Day 2, 2026-11-23)",
                "- minecraft:coal",
                "- ★ [World First] minecraft:diamond ×64",
                "- minecraft:emerald"), en);

        String ja = NotificationGrouping.buildDiscordGroupPlainText(
                "Alice", "1h 02m 03s (Day 2, 2026-11-23)", items, JA_TEMPLATES);
        assertEquals(String.join("\n",
                "★ マイルストーン記録: Alice が 3 件を入手（1h 02m 03s (Day 2, 2026-11-23)）",
                "- minecraft:coal",
                "- ★ [世界初] minecraft:diamond ×64",
                "- minecraft:emerald"), ja);
    }

    @Test
    void discordGroupCapsAtTwentyLinesAndCollapsesRemainingIntoMoreLine() {
        // Exactly 20 items -> no "...and N more" line
        List<NotificationGrouping.DiscordItem> twenty = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            twenty.add(new NotificationGrouping.DiscordItem("minecraft:item_" + i, false));
        }
        String text20 = NotificationGrouping.buildDiscordGroupPlainText(
                "Alice", "0h 10m 00s", twenty, NotificationGrouping.DiscordTemplates.ENGLISH_DEFAULTS);
        String[] lines20 = text20.split("\n");
        assertEquals(21, lines20.length); // 1 header + 20 item lines
        assertEquals("- minecraft:item_20", lines20[20]);
        assertFalse(text20.contains("...and"));

        // 25 items -> 1 header + 20 item lines + "...and 5 more"
        List<NotificationGrouping.DiscordItem> twentyFive = new ArrayList<>(twenty);
        for (int i = 21; i <= 25; i++) {
            twentyFive.add(new NotificationGrouping.DiscordItem("minecraft:item_" + i, true));
        }
        String text25 = NotificationGrouping.buildDiscordGroupPlainText(
                "Alice", "0h 10m 00s", twentyFive, NotificationGrouping.DiscordTemplates.ENGLISH_DEFAULTS);
        String[] lines25 = text25.split("\n");
        assertEquals(22, lines25.length); // 1 header + 20 item lines + 1 more line
        assertEquals("- minecraft:item_20", lines25[20]);
        assertEquals("...and 5 more", lines25[21]);
    }

    @Test
    void discordGroupRespects2000CharLimitByDroppingTrailingLinesAndIncreasingMoreCount() {
        // 15 long item labels (~180 chars each) -> total > 2000 even though count <= 20
        String longLabel = "modid:" + "a".repeat(170);
        List<NotificationGrouping.DiscordItem> fifteenLong = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            fifteenLong.add(new NotificationGrouping.DiscordItem(longLabel + "_" + i, false));
        }
        String trimmed15 = NotificationGrouping.buildDiscordGroupPlainText(
                "Alice", "1h 00m 00s (Day 1, 2026-11-23)", fifteenLong,
                NotificationGrouping.DiscordTemplates.ENGLISH_DEFAULTS);
        assertTrue(trimmed15.length() <= NotificationGrouping.DISCORD_PLAIN_MAX_CHARS,
                "Expected <= 2000 chars, got " + trimmed15.length());
        String[] lines15 = trimmed15.split("\n");
        int shownItems15 = lines15.length - 2; // minus header and "...and N more"
        assertTrue(shownItems15 > 0 && shownItems15 < 15);
        assertEquals("...and " + (15 - shownItems15) + " more", lines15[lines15.length - 1]);

        // 30 long item labels -> starts capped at 20, drops further to fit 2000 chars, and reflects all dropped items in N
        List<NotificationGrouping.DiscordItem> thirtyLong = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            thirtyLong.add(new NotificationGrouping.DiscordItem(longLabel + "_" + i, i % 2 == 0));
        }
        String trimmed30 = NotificationGrouping.buildDiscordGroupPlainText(
                "Alice", "1h 00m 00s (Day 1, 2026-11-23)", thirtyLong,
                NotificationGrouping.DiscordTemplates.ENGLISH_DEFAULTS);
        assertTrue(trimmed30.length() <= NotificationGrouping.DISCORD_PLAIN_MAX_CHARS,
                "Expected <= 2000 chars, got " + trimmed30.length());
        String[] lines30 = trimmed30.split("\n");
        int shownItems30 = lines30.length - 2;
        assertTrue(shownItems30 > 0 && shownItems30 < 20);
        assertEquals("...and " + (30 - shownItems30) + " more", lines30[lines30.length - 1]);
    }

    @Test
    void discordGroupTruncatesSafelyWithoutSplittingSurrogatePairIfHeaderAloneExceedsLimit() {
        String emojiName = "\uD83C\uDF1F".repeat(1050); // 2100 UTF-16 code units
        List<NotificationGrouping.DiscordItem> items = List.of(
                new NotificationGrouping.DiscordItem("minecraft:a", false),
                new NotificationGrouping.DiscordItem("minecraft:b", false),
                new NotificationGrouping.DiscordItem("minecraft:c", false));
        String content = NotificationGrouping.buildDiscordGroupPlainText(
                emojiName, "0h 00m 00s", items, NotificationGrouping.DiscordTemplates.ENGLISH_DEFAULTS);
        assertTrue(content.length() <= NotificationGrouping.DISCORD_PLAIN_MAX_CHARS);
        assertFalse(Character.isHighSurrogate(content.charAt(content.length() - 1)));
    }

    @Test
    void buildDiscordGroupLinesSupportsEmbedReuseWithoutHeader() {
        List<NotificationGrouping.DiscordItem> items = List.of(
                new NotificationGrouping.DiscordItem("Coal", false),
                new NotificationGrouping.DiscordItem("Diamond", true),
                new NotificationGrouping.DiscordItem("Emerald", false),
                new NotificationGrouping.DiscordItem("Gold Ingot", false));
        String lines = NotificationGrouping.buildDiscordGroupLines(
                items, NotificationGrouping.DiscordTemplates.ENGLISH_DEFAULTS, 2,
                NotificationGrouping.DISCORD_EMBED_DESCRIPTION_MAX_CHARS);
        assertEquals(String.join("\n",
                "- Coal",
                "- ★ [World First] Diamond",
                "...and 2 more"), lines);
    }
}
