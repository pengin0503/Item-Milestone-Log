package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-Java unit tests for the F42 {@link ItemTooltipLogic} tooltip index and line cap. */
class ItemTooltipLogicTest {
    private static final String DIAMOND = "minecraft:diamond";
    private static final String IRON = "minecraft:iron_ingot";
    private static final String GOLD = "minecraft:gold_ingot";
    private static final String STEEL = "othermod:steel_ingot";

    @Test
    void targetItemProducesOneUnrecordedLine() {
        Map<String, List<ItemTooltipLogic.Status>> index = ItemTooltipLogic.buildIndex(
                List.of(ItemTooltipLogic.Status.target(DIAMOND, 1)), TagIndex.EMPTY);

        ItemTooltipLogic.Lines lines = ItemTooltipLogic.linesForItem(index, DIAMOND);
        assertEquals(1, lines.shown().size());
        assertTrue(lines.shown().get(0).target());
        assertFalse(lines.shown().get(0).recorded());
        assertEquals(0, lines.remaining());

        assertEquals(0, ItemTooltipLogic.linesForItem(index, "minecraft:emerald").shown().size(),
                "items that are neither targets nor recorded get no lines");
        assertEquals(0, ItemTooltipLogic.linesForItem(index, null).shown().size());
        assertEquals(0, ItemTooltipLogic.linesForItem(null, DIAMOND).shown().size());
    }

    @Test
    void recordedItemProducesOneRecordedLineWithPlaytime() {
        Map<String, List<ItemTooltipLogic.Status>> index = ItemTooltipLogic.buildIndex(
                List.of(ItemTooltipLogic.Status.recorded(DIAMOND, 5, 72_000)), TagIndex.EMPTY);

        ItemTooltipLogic.Lines lines = ItemTooltipLogic.linesForItem(index, DIAMOND);
        assertEquals(1, lines.shown().size());
        ItemTooltipLogic.Status status = lines.shown().get(0);
        assertTrue(status.recorded());
        assertFalse(status.target());
        assertEquals(5, status.count());
        assertEquals(72_000, status.playTicks());
    }

    @Test
    void targetAndRecordForTheSameKeyAreMergedIntoOneRecordedLine() {
        Map<String, List<ItemTooltipLogic.Status>> index = ItemTooltipLogic.buildIndex(
                List.of(
                        ItemTooltipLogic.Status.target(DIAMOND, 1),
                        ItemTooltipLogic.Status.recorded(DIAMOND, 1, 1_200)),
                TagIndex.EMPTY);

        ItemTooltipLogic.Lines lines = ItemTooltipLogic.linesForItem(index, DIAMOND);
        assertEquals(1, lines.shown().size(), "a recorded target must not produce two lines");
        assertTrue(lines.shown().get(0).recorded());
        assertTrue(lines.shown().get(0).target());
        assertEquals(1_200, lines.shown().get(0).playTicks());
    }

    @Test
    void multipleKeysAreSortedByTargetKeyOrder() {
        Map<String, List<ItemTooltipLogic.Status>> index = ItemTooltipLogic.buildIndex(
                List.of(
                        ItemTooltipLogic.Status.target(DIAMOND, 100),
                        ItemTooltipLogic.Status.target(DIAMOND, 1),
                        ItemTooltipLogic.Status.target(DIAMOND, 5)),
                TagIndex.EMPTY);

        List<ItemTooltipLogic.Status> statuses = ItemTooltipLogic.linesForItem(index, DIAMOND).shown();
        assertEquals(List.of(1, 5, 100), statuses.stream().map(ItemTooltipLogic.Status::count).toList());
    }

    @Test
    void atMostThreeLinesAreShownAndTheRestAreCounted() {
        Map<String, List<ItemTooltipLogic.Status>> index = ItemTooltipLogic.buildIndex(
                List.of(
                        ItemTooltipLogic.Status.target(DIAMOND, 1),
                        ItemTooltipLogic.Status.target(DIAMOND, 2),
                        ItemTooltipLogic.Status.target(DIAMOND, 3),
                        ItemTooltipLogic.Status.target(DIAMOND, 4)),
                TagIndex.EMPTY);

        ItemTooltipLogic.Lines lines = ItemTooltipLogic.linesForItem(index, DIAMOND);
        assertEquals(3, lines.shown().size());
        assertEquals(1, lines.remaining());
        assertEquals(List.of(1, 2, 3), lines.shown().stream().map(ItemTooltipLogic.Status::count).toList());

        assertEquals(3, ItemTooltipLogic.MAX_LINES);
    }

    @Test
    void tagTargetsProduceLinesForEveryMemberItem() {
        TagIndex tags = TagIndex.of(Map.of("#c:ingots/iron", List.of(IRON, STEEL)));
        Map<String, List<ItemTooltipLogic.Status>> index = ItemTooltipLogic.buildIndex(
                List.of(
                        ItemTooltipLogic.Status.target("#c:ingots/iron", 100),
                        ItemTooltipLogic.Status.recorded("#c:ingots/iron", 1, 600),
                        ItemTooltipLogic.Status.target(IRON, 5),
                        ItemTooltipLogic.Status.target(GOLD, 2)),
                tags);

        List<ItemTooltipLogic.Status> ironLines = ItemTooltipLogic.linesForItem(index, IRON).shown();
        assertEquals(List.of(
                ItemTooltipLogic.Status.recorded("#c:ingots/iron", 1, 600),
                ItemTooltipLogic.Status.target("#c:ingots/iron", 100),
                ItemTooltipLogic.Status.target(IRON, 5)), ironLines,
                "tags sort before item IDs and count orders the two tag keys");
        assertTrue(ironLines.get(0).isTag());
        assertTrue(ironLines.get(1).isTag());

        assertEquals(2, ItemTooltipLogic.linesForItem(index, STEEL).shown().size(),
                "every member of the tag gets the tag lines");
        assertEquals(1, ItemTooltipLogic.linesForItem(index, GOLD).shown().size(),
                "an item only gets tag lines for tags that contain it");
        assertEquals(GOLD, ItemTooltipLogic.linesForItem(index, GOLD).shown().get(0).itemId());
    }

    @Test
    void unknownOrEmptyTagsDoNotProduceLines() {
        TagIndex tags = TagIndex.of(Map.of("#c:ingots/iron", List.of(IRON)));
        Map<String, List<ItemTooltipLogic.Status>> index = ItemTooltipLogic.buildIndex(
                List.of(ItemTooltipLogic.Status.target("#c:missing", 10)), tags);

        assertTrue(index.isEmpty());
        assertTrue(ItemTooltipLogic.linesForItem(index, IRON).isEmpty());
    }

    @Test
    void countSuffixMatchesTheSpecifiedFormat() {
        assertEquals("", ItemTooltipLogic.countSuffix(1));
        assertEquals(" \u00d72", ItemTooltipLogic.countSuffix(2));
        assertEquals(" \u00d7100", ItemTooltipLogic.countSuffix(100));
        assertEquals(" \u00d71000000", ItemTooltipLogic.countSuffix(1_000_000));
    }

    @Test
    void emptyInputsProduceAnEmptyIndex() {
        assertTrue(ItemTooltipLogic.buildIndex(List.of(), TagIndex.EMPTY).isEmpty());
        assertTrue(ItemTooltipLogic.buildIndex(null, null).isEmpty());
        assertEquals(Set.of(DIAMOND), ItemTooltipLogic.buildIndex(
                List.of(ItemTooltipLogic.Status.target(DIAMOND, 1)), null).keySet());
    }

    // ---- F-150-68 / F-150-79: refineLines is additive; defaults are byte-for-byte F42.

    private static ItemTooltipLogic.Lines f42Output() {
        Map<String, List<ItemTooltipLogic.Status>> index = ItemTooltipLogic.buildIndex(
                List.of(
                        ItemTooltipLogic.Status.target(DIAMOND, 1),
                        ItemTooltipLogic.Status.target(DIAMOND, 2),
                        ItemTooltipLogic.Status.recorded(DIAMOND, 3, 120),
                        ItemTooltipLogic.Status.target(DIAMOND, 4)),
                TagIndex.EMPTY);
        return ItemTooltipLogic.linesForItem(index, DIAMOND);
    }

    /** The pre-cap list, used to prove maxLines can only remove rows. */
    private static ItemTooltipLogic.Lines fourLines() {
        Map<String, List<ItemTooltipLogic.Status>> index = ItemTooltipLogic.buildIndex(
                List.of(
                        ItemTooltipLogic.Status.target(DIAMOND, 1),
                        ItemTooltipLogic.Status.target(DIAMOND, 2),
                        ItemTooltipLogic.Status.recorded(DIAMOND, 3, 120),
                        ItemTooltipLogic.Status.target(DIAMOND, 4)),
                TagIndex.EMPTY);
        return new ItemTooltipLogic.Lines(index.get(DIAMOND), 0);
    }

    @Test
    void defaultOptionsReproduceTheExistingLinesExactly() {
        ItemTooltipLogic.Lines original = f42Output();
        assertEquals(original, ItemTooltipLogic.refineLines(original, TooltipOptions.DEFAULT));
        assertEquals(original, ItemTooltipLogic.refineLines(original, null));
        assertEquals(original, ItemTooltipLogic.refineLines(original,
                TooltipOptions.of(99, true, true, "bogus", "bogus", true)));
    }

    @Test
    void maxLinesOnlyReducesRowsAndNeverRevealsHiddenStatuses() {
        ItemTooltipLogic.Lines original = fourLines();
        assertEquals(4, original.shown().size());
        assertEquals(0, original.remaining());

        ItemTooltipLogic.Lines one = ItemTooltipLogic.refineLines(original,
                new TooltipOptions(1, true, true, TooltipOptions.Sort.DEFAULT,
                        TooltipOptions.StatusColor.DEFAULT, true));
        assertEquals(1, one.shown().size());
        assertEquals(original.shown().get(0), one.shown().get(0));
        assertEquals(3, one.remaining(), "three capped rows");

        ItemTooltipLogic.Lines ten = ItemTooltipLogic.refineLines(original,
                new TooltipOptions(10, true, true, TooltipOptions.Sort.DEFAULT,
                        TooltipOptions.StatusColor.DEFAULT, true));
        assertEquals(original, ten, "a larger setting cannot reveal F42's hidden status");
        assertTrue(ItemTooltipLogic.refineLines(ItemTooltipLogic.Lines.EMPTY,
                TooltipOptions.DEFAULT).isEmpty());
    }

    @Test
    void showTargetAndShowRecordedFilterOnlyTheirOwnRows() {
        ItemTooltipLogic.Lines original = fourLines();
        ItemTooltipLogic.Lines targetsOnly = ItemTooltipLogic.refineLines(original,
                new TooltipOptions(10, true, false, TooltipOptions.Sort.DEFAULT,
                        TooltipOptions.StatusColor.DEFAULT, true));
        assertEquals(List.of(1, 2, 4), targetsOnly.shown().stream()
                .map(ItemTooltipLogic.Status::count).toList());
        assertEquals(1, targetsOnly.remaining(), "the filtered recorded row is counted as hidden");

        ItemTooltipLogic.Lines recordedOnly = ItemTooltipLogic.refineLines(original,
                new TooltipOptions(10, false, true, TooltipOptions.Sort.DEFAULT,
                        TooltipOptions.StatusColor.DEFAULT, true));
        assertEquals(1, recordedOnly.shown().size());
        assertTrue(recordedOnly.shown().get(0).recorded());
        assertEquals(3, recordedOnly.remaining(), "three filtered target rows are counted as hidden");

        assertTrue(ItemTooltipLogic.refineLines(original,
                new TooltipOptions(10, false, false, TooltipOptions.Sort.DEFAULT,
                        TooltipOptions.StatusColor.DEFAULT, true)).isEmpty());
    }

    @Test
    void sortChangesOnlyDirectionAndKeepsEqualStatusOrderStable() {
        ItemTooltipLogic.Lines original = fourLines();
        ItemTooltipLogic.Lines targetFirst = ItemTooltipLogic.refineLines(original,
                new TooltipOptions(10, true, true, TooltipOptions.Sort.TARGET_FIRST,
                        TooltipOptions.StatusColor.DEFAULT, true));
        assertEquals(List.of(1, 2, 4, 3), targetFirst.shown().stream()
                .map(ItemTooltipLogic.Status::count).toList(),
                "the recorded row moves last, the three target rows keep their order");

        ItemTooltipLogic.Lines recordedFirst = ItemTooltipLogic.refineLines(original,
                new TooltipOptions(10, true, true, TooltipOptions.Sort.RECORDED_FIRST,
                        TooltipOptions.StatusColor.DEFAULT, true));
        assertEquals(List.of(3, 1, 2, 4), recordedFirst.shown().stream()
                .map(ItemTooltipLogic.Status::count).toList());
    }

    @Test
    void colourAndTagNameOptionsParseLeniently() {
        assertEquals(TooltipOptions.Sort.DEFAULT, TooltipOptions.parseSort(null));
        assertEquals(TooltipOptions.Sort.TARGET_FIRST, TooltipOptions.parseSort("target-first"));
        assertEquals(TooltipOptions.Sort.RECORDED_FIRST, TooltipOptions.parseSort("RECORDED_FIRST"));
        assertEquals(TooltipOptions.StatusColor.GOLD, TooltipOptions.parseColor("gold"));
        assertEquals(TooltipOptions.StatusColor.DEFAULT, TooltipOptions.parseColor("rainbow"));
        assertTrue(TooltipOptions.isValidSort("target-first"));
        assertFalse(TooltipOptions.isValidSort("first-target"));
        assertTrue(TooltipOptions.isValidColor("white"));
        assertFalse(TooltipOptions.isValidColor("#ff0000"));
        assertEquals(1, new TooltipOptions(0, true, true, TooltipOptions.Sort.DEFAULT,
                TooltipOptions.StatusColor.DEFAULT, true).maxLines());
        assertEquals(10, new TooltipOptions(100, true, true, TooltipOptions.Sort.DEFAULT,
                TooltipOptions.StatusColor.DEFAULT, true).maxLines());
    }
}
