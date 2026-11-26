package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresetStateLogicTest {
    @Test
    void mergeOrsAutoAppliedAndTakesTheLargerSeenVersion() {
        assertEquals(new PresetState(true, 5),
                PresetStateLogic.merge(new PresetState(false, 3), new PresetState(true, 5)));
        assertEquals(new PresetState(true, 5),
                PresetStateLogic.merge(new PresetState(true, 5), new PresetState(false, 1)));
        assertEquals(new PresetState(false, 4),
                PresetStateLogic.merge(new PresetState(false, 4), new PresetState(false, 2)));
        assertEquals(new PresetState(true, 7),
                PresetStateLogic.merge(new PresetState(true, 7), new PresetState(true, 7)));
    }

    @Test
    void mergeTreatsMissingSidesAsEmpty() {
        PresetState state = new PresetState(true, 4);
        assertEquals(state, PresetStateLogic.merge(null, state));
        assertEquals(state, PresetStateLogic.merge(state, null));
        assertEquals(PresetState.EMPTY, PresetStateLogic.merge(null, null));
    }

    @Test
    void mergeIntoUnionsIdsAndKeepsOrder() {
        Map<String, PresetState> target = new LinkedHashMap<>();
        target.put("a", new PresetState(false, 2));
        target.put("b", new PresetState(true, 1));
        Map<String, PresetState> incoming = new LinkedHashMap<>();
        incoming.put("a", new PresetState(true, 1));
        incoming.put("c", new PresetState(false, 9));

        PresetStateLogic.mergeInto(target, incoming);

        assertEquals(Map.of(
                "a", new PresetState(true, 2),
                "b", new PresetState(true, 1),
                "c", new PresetState(false, 9)
        ), target);
        assertEquals(java.util.List.of("a", "b", "c"), java.util.List.copyOf(target.keySet()));
    }

    @Test
    void mergeIntoNoOpForNullOrEmpty() {
        Map<String, PresetState> target = new LinkedHashMap<>();
        target.put("a", new PresetState(true, 1));
        PresetStateLogic.mergeInto(target, null);
        PresetStateLogic.mergeInto(target, Map.of());
        assertEquals(Map.of("a", new PresetState(true, 1)), target);
    }

    @Test
    void ticksToHoursUsesRealTimeAtTwentyTicksPerSecond() {
        assertEquals(0.0d, PresetStateLogic.ticksToHours(0));
        assertEquals(1.0d, PresetStateLogic.ticksToHours(72_000));
        assertEquals(0.5d, PresetStateLogic.ticksToHours(36_000));
        assertEquals(0.0d, PresetStateLogic.ticksToHours(-1));
    }

    @Test
    void unlockThresholdIsInclusiveAndIgnoresMissingOrInvalidValues() {
        Double oneHour = 1.0d;
        assertFalse(PresetStateLogic.isUnlockDue(oneHour, 71_999));
        assertTrue(PresetStateLogic.isUnlockDue(oneHour, 72_000));
        assertTrue(PresetStateLogic.isUnlockDue(oneHour, 72_001));
        assertTrue(PresetStateLogic.isUnlockDue(0.0d, 0));
        assertFalse(PresetStateLogic.isUnlockDue(null, 1_000_000));
        assertFalse(PresetStateLogic.isUnlockDue(-1.0d, 1_000_000));
        assertFalse(PresetStateLogic.isUnlockDue(Double.NaN, 1_000_000));
        assertFalse(PresetStateLogic.isUnlockDue(Double.POSITIVE_INFINITY, 1_000_000));
    }

    @Test
    void versionNoticeIsDueOnlyForAnAlreadySeenOlderVersion() {
        assertTrue(PresetStateLogic.isVersionNoticeDue(2, new PresetState(false, 1)));
        assertFalse(PresetStateLogic.isVersionNoticeDue(2, new PresetState(false, 2)));
        assertFalse(PresetStateLogic.isVersionNoticeDue(2, new PresetState(false, 3)));
        assertFalse(PresetStateLogic.isVersionNoticeDue(2, new PresetState(false, 0)));
        assertFalse(PresetStateLogic.isVersionNoticeDue(2, null));
        assertFalse(PresetStateLogic.isVersionNoticeDue(null, new PresetState(false, 1)));
        assertFalse(PresetStateLogic.isVersionNoticeDue(0, new PresetState(false, 1)));
    }
}
