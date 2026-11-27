package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-150-38 (N167): pure rotation, lenient validation and newest-first display rules. */
class FirstsHolderHistoryTest {
    private static final String A = UUID.fromString("11111111-1111-4111-8111-111111111111").toString();
    private static final String B = UUID.fromString("22222222-2222-4222-8222-222222222222").toString();

    private static HolderEntry entry(String uuid, int ticks) {
        return new HolderEntry(uuid, "Player", ticks, 1795600000000L);
    }

    @Test
    void constantsAndInvalidSentinelAreStable() {
        assertEquals(20, FirstsHolderHistory.HISTORY_LIMIT);
        assertEquals(new HolderEntry("", "", -1, 0L), HolderEntry.INVALID);
        assertFalse(HolderEntry.INVALID.isValid());
        assertTrue(HolderEntry.isValidUuid(A));
        assertFalse(HolderEntry.isValidUuid("not-a-uuid"));
        assertFalse(HolderEntry.isValidUuid(""));
    }

    @Test
    void ofRejectsUnknownUuidNegativeTicksAndOutOfRangeValues() {
        assertEquals(HolderEntry.INVALID, HolderEntry.of("nope", "X", 10, 0L));
        assertEquals(HolderEntry.INVALID, HolderEntry.of(A, "X", -1, 0L));
        assertEquals(HolderEntry.INVALID, HolderEntry.of(A, "X", (long) Integer.MAX_VALUE + 1, 0L));
        assertEquals(HolderEntry.INVALID, HolderEntry.of(A, "X", 10, -5L));
        assertEquals(new HolderEntry(A, "X", 10, 0L), HolderEntry.of(A, "X", 10, 0L));
        assertEquals("", new HolderEntry(A, null, 1, 0L).name());
    }

    @Test
    void appendRotatesAtTwentyEntriesAndKeepsNewestLast() {
        List<HolderEntry> stored = List.of();
        for (int i = 0; i < FirstsHolderHistory.HISTORY_LIMIT + 5; i++) {
            stored = FirstsHolderHistory.append(stored, entry(A, i));
        }
        assertEquals(FirstsHolderHistory.HISTORY_LIMIT, stored.size());
        // The five oldest entries rotated out; ticks 5..24 remain in insertion order.
        assertEquals(5, stored.get(0).playTicks());
        assertEquals(24, stored.get(19).playTicks());
    }

    @Test
    void appendDoesNotMutateInputAndIgnoresInvalidEntries() {
        List<HolderEntry> original = new ArrayList<>(List.of(entry(A, 7)));
        List<HolderEntry> updated = FirstsHolderHistory.append(original, HolderEntry.INVALID);
        assertEquals(List.of(entry(A, 7)), updated);
        assertNotSame(original, updated);
        assertEquals(1, original.size());
        assertEquals(2, FirstsHolderHistory.append(original, entry(B, 8)).size());
    }

    @Test
    void repeatedHolderChangesAreKeptAsSeparateEntries() {
        List<HolderEntry> stored = FirstsHolderHistory.append(List.of(), entry(A, 3));
        stored = FirstsHolderHistory.append(stored, entry(A, 4));
        assertEquals(2, stored.size());
        assertEquals(3, stored.get(0).playTicks());
        assertEquals(4, stored.get(1).playTicks());
    }

    @Test
    void newestFirstReversesStoredOrderAndHandlesEmpty() {
        List<HolderEntry> stored = List.of(entry(A, 1), entry(B, 2));
        assertEquals(List.of(entry(B, 2), entry(A, 1)), FirstsHolderHistory.newestFirst(stored));
        assertTrue(FirstsHolderHistory.newestFirst(List.of()).isEmpty());
        assertTrue(FirstsHolderHistory.newestFirst(null).isEmpty());
    }

    @Test
    void zeroEntriesStartEmpty() {
        assertTrue(FirstsHolderHistory.append(null, null).isEmpty());
    }
}
