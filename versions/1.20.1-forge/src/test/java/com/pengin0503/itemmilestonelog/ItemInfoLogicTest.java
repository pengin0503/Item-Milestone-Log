package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F51 unit tests: the own-status branches, the {@code allowLookup} / F14 exclusion of the
 * recorder-count line, and the related-tag extraction with its 10-row cap
 * (docs/FEATURE_SPEC_1.2.0.md §3).
 */
class ItemInfoLogicTest {
    private static final UUID ME = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID HIDDEN = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID TIED = UUID.fromString("00000000-0000-0000-0000-00000000000d");

    private static final TargetKey IRON = TargetKey.of("minecraft:iron_ingot");
    private static final TargetKey IRON_100 = TargetKey.of("minecraft:iron_ingot", 100);
    private static final TargetKey GOLD = TargetKey.of("minecraft:gold_ingot");
    private static final TargetKey DIAMOND = TargetKey.of("minecraft:diamond");
    private static final TargetKey EMERALD = TargetKey.of("minecraft:emerald");
    private static final TargetKey INGOTS = TargetKey.of("#c:ingots");
    private static final TargetKey INGOTS_IRON = TargetKey.of("#c:ingots/iron");
    private static final TargetKey INGOTS_IRON_100 = TargetKey.of("#c:ingots/iron", 100);
    private static final TargetKey TOOLS = TargetKey.of("#c:tools");

    private static MilestoneRecord rec(int ticks) {
        return new MilestoneRecord(ticks, 1L, 1000L);
    }

    private static MilestoneData.PlayerData player(String name, boolean hidden, Object... keyAndRecord) {
        MilestoneData.PlayerData pd = new MilestoneData.PlayerData();
        pd.lastKnownName = name;
        pd.hideFromLookup = hidden;
        for (int i = 0; i < keyAndRecord.length; i += 2) {
            pd.records.put((TargetKey) keyAndRecord[i], (MilestoneRecord) keyAndRecord[i + 1]);
        }
        return pd;
    }

    private static TagIndex tagIndex() {
        Map<String, List<String>> tags = new LinkedHashMap<>();
        tags.put("#c:ingots", List.of("minecraft:iron_ingot", "minecraft:gold_ingot"));
        tags.put("#c:ingots/iron", List.of("minecraft:iron_ingot"));
        tags.put("#c:tools", List.of("minecraft:iron_ingot", "minecraft:diamond"));
        tags.put("#c:beacons", List.of("minecraft:beacon"));
        return TagIndex.of(tags);
    }

    // ---------------------------------------------------------------- own status (step 2)

    @Test
    void ownStatusCoversRecordedTargetProgressTargetAndNone() {
        Map<TargetKey, MilestoneRecord> records = Map.of(IRON, rec(10));
        Set<TargetKey> targets = Set.of(IRON, INGOTS, GOLD);
        TagIndex index = tagIndex();

        // Recorded wins over being a target as well.
        ItemInfoLogic.OwnStatus recorded = ItemInfoLogic.ownStatus(
                IRON, records, targets, Set.of(), index, Map.of());
        assertEquals(ItemInfoLogic.OwnStatusKind.RECORDED, recorded.kind());
        assertEquals(rec(10), recorded.record());
        assertFalse(recorded.creativeCarried());

        // Unrecorded quantity target: current possession / goal.
        ItemInfoLogic.OwnStatus progress = ItemInfoLogic.ownStatus(
                IRON_100, records, Set.of(IRON_100), Set.of(), index, Map.of("minecraft:iron_ingot", 12));
        assertEquals(ItemInfoLogic.OwnStatusKind.TARGET_PROGRESS, progress.kind());
        assertEquals(12, progress.current());
        assertEquals(100, progress.goal());
        assertFalse(progress.creativeCarried());
        assertNull(progress.record());

        // Unrecorded target with count 1: no possession is shown.
        ItemInfoLogic.OwnStatus target = ItemInfoLogic.ownStatus(
                INGOTS, records, targets, Set.of(), index, Map.of());
        assertEquals(ItemInfoLogic.OwnStatusKind.TARGET, target.kind());

        // Neither a record nor a target.
        ItemInfoLogic.OwnStatus none = ItemInfoLogic.ownStatus(
                IRON_100, records, targets, Set.of(), index, Map.of());
        assertEquals(ItemInfoLogic.OwnStatusKind.NONE, none.kind());
    }

    @Test
    void ownStatusCountsCreativeCarriedItemsAsZeroAndTagsWithoutThem() {
        TagIndex index = tagIndex();
        Set<String> carried = Set.of("minecraft:iron_ingot");

        // Item carried out of creative: 0 with the F50 note.
        ItemInfoLogic.OwnStatus carriedItem = ItemInfoLogic.ownStatus(
                IRON_100, Map.of(), Set.of(IRON_100), carried, index, Map.of("minecraft:iron_ingot", 12));
        assertEquals(ItemInfoLogic.OwnStatusKind.TARGET_PROGRESS, carriedItem.kind());
        assertEquals(0, carriedItem.current(), "creative-carried items count 0");
        assertTrue(carriedItem.creativeCarried(), "the F50 target_creative_carried note applies");

        // Tag with a carried member: the total (which excludes carried members) plus the note.
        ItemInfoLogic.OwnStatus carriedTag = ItemInfoLogic.ownStatus(
                INGOTS, Map.of(), Set.of(INGOTS), carried, index, Map.of("#c:ingots", 5));
        assertEquals(ItemInfoLogic.OwnStatusKind.TARGET_PROGRESS, carriedTag.kind());
        assertEquals(5, carriedTag.current(), "tag totals already exclude the carried member");
        assertTrue(carriedTag.creativeCarried());

        // Nothing carried: plain possession, no note.
        ItemInfoLogic.OwnStatus clean = ItemInfoLogic.ownStatus(
                IRON_100, Map.of(), Set.of(IRON_100), Set.of(), index, Map.of("minecraft:iron_ingot", 12));
        assertFalse(clean.creativeCarried());
        assertEquals(12, clean.current());
    }

    @Test
    void possessionIdsRequestsOnlyUnrecordedQuantityTargetsNotCarried() {
        Map<TargetKey, MilestoneRecord> records = Map.of(GOLD, rec(1));
        Set<String> carried = Set.of("minecraft:gold_ingot");
        Set<TargetKey> keys = Set.of(
                IRON_100,           // quantity target: requested
                IRON,               // count 1: not needed
                GOLD,               // recorded: not needed
                INGOTS,             // tag quantity target: requested even with a carried member
                TargetKey.of("minecraft:gold_ingot", 10)); // carried item: counts 0, not requested

        assertEquals(Set.of("minecraft:iron_ingot", "#c:ingots"),
                ItemInfoLogic.possessionIds(keys, records, carried));
    }

    // ------------------------------------------------- recorder count and own rank (step 4)

    @Test
    void playersLineIsOmittedWhenAllowLookupIsOffForNonPrivilegedViewers() {
        Map<UUID, MilestoneData.PlayerData> players = new LinkedHashMap<>();
        players.put(ME, player("me", false, IRON, rec(10)));
        players.put(OTHER, player("other", false, IRON, rec(5)));
        MilestoneRankings.Viewer me = new MilestoneRankings.Viewer(ME, false);

        ItemInfoLogic.PlayersLine closed = ItemInfoLogic.playersLine(players, IRON, me, false);
        assertFalse(closed.shown(), "the whole line is omitted");
        assertEquals(0, closed.players());
        assertNull(closed.ownRank());

        assertTrue(ItemInfoLogic.playersLine(players, IRON, new MilestoneRankings.Viewer(ME, true), false).shown(),
                "privileged viewers keep the line");
        assertTrue(ItemInfoLogic.playersLine(players, IRON, me, true).shown(),
                "allowLookup = true keeps the line for everyone");
    }

    @Test
    void playersLineCountsLikeTopItemWithHiddenExcludedAndTheViewerAlwaysIncluded() {
        Map<UUID, MilestoneData.PlayerData> players = new LinkedHashMap<>();
        players.put(HIDDEN, player("hidden", true, IRON, rec(1)));
        players.put(OTHER, player("other", false, IRON, rec(10)));
        players.put(ME, player("me", true, IRON, rec(50)));

        // The viewer is hidden but still counted for themself; the hidden other is not.
        ItemInfoLogic.PlayersLine asMe = ItemInfoLogic.playersLine(
                players, IRON, new MilestoneRankings.Viewer(ME, false), true);
        assertTrue(asMe.shown());
        assertEquals(2, asMe.players());
        assertEquals(2, asMe.ownRank());

        // A privileged viewer sees everyone.
        ItemInfoLogic.PlayersLine asOp = ItemInfoLogic.playersLine(
                players, IRON, new MilestoneRankings.Viewer(ME, true), false);
        assertEquals(3, asOp.players());
        assertEquals(3, asOp.ownRank());
    }

    @Test
    void playersLineSharesRanksOnEqualPlayTicksAndIsUnrankedWithoutARecord() {
        Map<UUID, MilestoneData.PlayerData> players = new LinkedHashMap<>();
        players.put(OTHER, player("other", false, IRON, rec(10)));
        players.put(TIED, player("tied", false, IRON, rec(10)));
        players.put(ME, player("me", false, GOLD, rec(1)));

        ItemInfoLogic.PlayersLine line = ItemInfoLogic.playersLine(
                players, IRON, new MilestoneRankings.Viewer(ME, false), true);
        assertTrue(line.shown());
        assertEquals(2, line.players());
        assertNull(line.ownRank(), "info_players_unranked when the viewer has no record of the key");

        Map<UUID, MilestoneData.PlayerData> tied = new LinkedHashMap<>();
        tied.put(OTHER, player("other", false, IRON, rec(10)));
        tied.put(ME, player("me", false, IRON, rec(10)));
        ItemInfoLogic.PlayersLine tiedLine = ItemInfoLogic.playersLine(
                tied, IRON, new MilestoneRankings.Viewer(ME, false), true);
        assertEquals(2, tiedLine.players());
        assertEquals(1, tiedLine.ownRank(), "equal playTicks share a rank");
    }

    // ------------------------------------------------------- related tags (step 5)

    @Test
    void relatedTagKeysCollectsFromTargetsRecordsAndWorldFirstsInOrder() {
        Set<TargetKey> targets = Set.of(INGOTS_IRON_100);
        Map<TargetKey, MilestoneRecord> records = Map.of(INGOTS, rec(1));
        Set<TargetKey> worldFirsts = Set.of(TOOLS);

        assertEquals(List.of(INGOTS, INGOTS_IRON_100, TOOLS),
                ItemInfoLogic.relatedTagKeys("minecraft:iron_ingot", true, targets, records, worldFirsts, tagIndex()));

        // Tags that do not contain the item, or contain it but have no key anywhere, never appear.
        Set<TargetKey> beaconsOnly = Set.of(TargetKey.of("#c:beacons"));
        assertEquals(List.of(), ItemInfoLogic.relatedTagKeys(
                "minecraft:iron_ingot", true, Set.of(), Map.of(), beaconsOnly, tagIndex()));

        // A removed-mod item (not in the registry) gets no tag section at all.
        assertEquals(List.of(), ItemInfoLogic.relatedTagKeys(
                "oldmod:removed_item", false, targets, records, worldFirsts, tagIndex()));
    }

    @Test
    void relatedTagKeysDeduplicatesKeysAndOrdersThemByCount() {
        Set<TargetKey> targets = Set.of(INGOTS_IRON_100, INGOTS_IRON);
        Map<TargetKey, MilestoneRecord> records = Map.of(INGOTS_IRON, rec(1));
        Set<TargetKey> worldFirsts = Set.of(INGOTS_IRON_100);

        // INGOTS_IRON appears in all three sources but is listed once, lower count first.
        assertEquals(List.of(INGOTS_IRON, INGOTS_IRON_100),
                ItemInfoLogic.relatedTagKeys("minecraft:iron_ingot", true, targets, records, worldFirsts, tagIndex()));
    }

    @Test
    void relatedTagEntriesCapsAtTenRowsAndCountsTheRemainder() {
        Map<String, List<String>> tags = new TreeMap<>();
        List<TargetKey> keys = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            String tagId = String.format("#c:tag%02d", i);
            tags.put(tagId, List.of("minecraft:iron_ingot"));
            keys.add(TargetKey.of(tagId));
        }
        TagIndex index = TagIndex.of(tags);

        List<TargetKey> related = ItemInfoLogic.relatedTagKeys(
                "minecraft:iron_ingot", true, Set.copyOf(keys), Map.of(), Set.of(), index);
        assertEquals(12, related.size());

        ItemInfoLogic.RelatedTags relatedTags = ItemInfoLogic.relatedTagEntries(
                related, Map.of(), Set.copyOf(keys), Set.of(), index, Map.of());
        assertEquals(ItemInfoLogic.TAG_LIMIT, relatedTags.entries().size());
        assertEquals(2, relatedTags.more(), "the cut rows are reported as '...and N more'");
        assertEquals(TargetKey.of("#c:tag01"), relatedTags.entries().get(0).key());
        assertEquals(TargetKey.of("#c:tag10"), relatedTags.entries().get(9).key());
        assertFalse(relatedTags.isEmpty());

        // Exactly ten keys never report an overflow.
        ItemInfoLogic.RelatedTags exact = ItemInfoLogic.relatedTagEntries(
                related.subList(0, 10), Map.of(), Set.copyOf(keys), Set.of(), index, Map.of());
        assertEquals(10, exact.entries().size());
        assertEquals(0, exact.more());
    }

    @Test
    void relatedTagEntriesCarriesTheViewersShortStatusPerKey() {
        Set<TargetKey> targets = Set.of(INGOTS_IRON_100, INGOTS);
        Map<TargetKey, MilestoneRecord> records = Map.of(INGOTS, rec(7));
        Map<String, Integer> possession = Map.of("#c:ingots/iron", 3);

        ItemInfoLogic.RelatedTags relatedTags = ItemInfoLogic.relatedTagEntries(
                List.of(INGOTS, INGOTS_IRON_100, TOOLS),
                records, targets, Set.of(), tagIndex(), possession);

        assertEquals(3, relatedTags.entries().size());
        assertEquals(ItemInfoLogic.OwnStatusKind.RECORDED, relatedTags.entries().get(0).status().kind());
        assertEquals(rec(7), relatedTags.entries().get(0).status().record());
        ItemInfoLogic.OwnStatus progress = relatedTags.entries().get(1).status();
        assertEquals(ItemInfoLogic.OwnStatusKind.TARGET_PROGRESS, progress.kind());
        assertEquals(3, progress.current());
        assertEquals(100, progress.goal());
        // A tag key present only through the world firsts shows the viewer's own (empty) status.
        assertEquals(ItemInfoLogic.OwnStatusKind.NONE, relatedTags.entries().get(2).status().kind());
    }

    // ---------------------------------------------------------------- suggestions

    @Test
    void suggestionItemIdsCombineOwnTargetsOwnRecordsAndVisibleWorldRecords() {
        Map<UUID, MilestoneData.PlayerData> players = new LinkedHashMap<>();
        players.put(ME, player("me", false, GOLD, rec(1)));
        players.put(OTHER, player("other", false, IRON, rec(1), DIAMOND, rec(1)));
        players.put(HIDDEN, player("hidden", true, EMERALD, rec(1)));
        MilestoneRankings.Viewer me = new MilestoneRankings.Viewer(ME, false);
        Set<TargetKey> ownTargets = Set.of(INGOTS_IRON, IRON_100);
        Map<TargetKey, MilestoneRecord> ownRecords = Map.of(GOLD, rec(1));

        // Tag IDs are never suggested: <item> is an item ID argument. Hidden players stay hidden.
        assertEquals(Set.of("minecraft:iron_ingot", "minecraft:gold_ingot", "minecraft:diamond"),
                ItemInfoLogic.suggestionItemIds(players, me, true, ownTargets, ownRecords));

        // With the lookup gate closed, only the viewer's own targets and records are suggested.
        assertEquals(Set.of("minecraft:iron_ingot", "minecraft:gold_ingot"),
                ItemInfoLogic.suggestionItemIds(players, me, false, ownTargets, ownRecords));

        // Other players' records appear on their own; the viewer's own records also come back
        // through the world list (they are visible to themself), and the hidden player stays out.
        assertEquals(Set.of("minecraft:iron_ingot", "minecraft:gold_ingot", "minecraft:diamond"),
                ItemInfoLogic.suggestionItemIds(players, me, true, Set.of(), Map.of()));

        // Records of hidden players appear only for privileged viewers.
        assertEquals(Set.of("minecraft:iron_ingot", "minecraft:gold_ingot", "minecraft:diamond", "minecraft:emerald"),
                ItemInfoLogic.suggestionItemIds(players, new MilestoneRankings.Viewer(ME, true), false,
                        ownTargets, ownRecords));
    }
}
