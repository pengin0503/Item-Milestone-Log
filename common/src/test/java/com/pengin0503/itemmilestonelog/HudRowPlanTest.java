package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-150-63: HUD rows come from local pins plus synced data only (no new S2C channel). */
class HudRowPlanTest {
    private static final PinStore.Pin DIAMOND = new PinStore.Pin("minecraft:diamond", 10);
    private static final PinStore.Pin STONE = new PinStore.Pin("minecraft:stone", 1);
    private static final Set<String> AVAILABLE = Set.of(DIAMOND.encoded(), STONE.encoded());

    @Test
    void emptyPinsAndMissingSnapshotsProduceNoRows() {
        assertTrue(HudRowPlan.plan(List.of(), AVAILABLE).isEmpty());
        assertTrue(HudRowPlan.plan(List.of(DIAMOND), null).isEmpty());
        assertTrue(HudRowPlan.plan(List.of(DIAMOND), Set.of()).isEmpty());
    }

    @Test
    void pinsKeepTheirOrderAndMissingItemsAreSkipped() {
        List<HudRowPlan.HudRow> rows = HudRowPlan.plan(
                List.of(STONE, DIAMOND, new PinStore.Pin("minecraft:missing", 1)),
                AVAILABLE, Map.of(), Set.of(), Map.of(), false, 5);
        assertEquals(2, rows.size());
        assertEquals(STONE.encoded(), rows.get(0).encodedId());
        assertEquals(DIAMOND.encoded(), rows.get(1).encodedId());
        assertEquals(STONE.encoded(), rows.get(0).displayName(), "no translation provided: use the id");
    }

    @Test
    void rowsCapAtFiveByDefaultAndTenMaximum() {
        List<PinStore.Pin> pins = java.util.stream.IntStream.range(0, 12)
                .mapToObj(i -> new PinStore.Pin("minecraft:item_" + i, 1))
                .toList();
        Set<String> available = pins.stream().map(PinStore.Pin::encoded)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(HudRowPlan.DEFAULT_MAX_ROWS, HudRowPlan.plan(pins, available).size());
        assertEquals(10, HudRowPlan.plan(pins, available, Map.of(), Set.of(), Map.of(), false, 99).size());
        assertEquals(1, HudRowPlan.plan(pins, available, Map.of(), Set.of(), Map.of(), false, -4).size());
    }

    @Test
    void progressAchievedStateAndOfflinePrefixArePrecomputed() {
        List<HudRowPlan.HudRow> rows = HudRowPlan.plan(
                List.of(DIAMOND, STONE), AVAILABLE,
                Map.of(DIAMOND.encoded(), 7, STONE.encoded(), -3),
                Set.of(DIAMOND.encoded()),
                Map.of(DIAMOND.encoded(), "Diamond x10"), true, 5);
        assertEquals(HudRowPlan.Status.ACHIEVED, rows.get(0).status());
        assertEquals(7, rows.get(0).progress());
        assertEquals("Diamond x10", rows.get(0).displayName());
        assertTrue(rows.get(0).offline());
        assertEquals(HudRowPlan.Status.TARGET, rows.get(1).status());
        assertEquals(0, rows.get(1).progress(), "negative progress is clamped");
        assertTrue(rows.get(1).offline());
        assertEquals("gui.itemmilestonelog.hud.offline", HudRowPlan.OFFLINE_KEY);
        assertFalse(rows.get(0).encodedId().isEmpty());
    }
}
