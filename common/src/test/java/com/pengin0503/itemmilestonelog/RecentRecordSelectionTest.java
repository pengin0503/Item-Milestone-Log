package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** F-150-71 (N68): epoch-first selection with a day fallback and a stable no-record result. */
class RecentRecordSelectionTest {
    private static RecentRecordSelection.Candidate epoch(long value) {
        return new RecentRecordSelection.Candidate(value, true, -1L, false);
    }

    private static RecentRecordSelection.Candidate day(long value) {
        return new RecentRecordSelection.Candidate(0L, false, value, true);
    }

    private static RecentRecordSelection.Candidate none() {
        return new RecentRecordSelection.Candidate(0L, false, -1L, false);
    }

    @Test
    void newestEpochWins() {
        List<RecentRecordSelection.Candidate> list = List.of(
                epoch(1_000L), epoch(9_000L), epoch(3_000L));
        assertEquals(1, RecentRecordSelection.selectIndex(list));
    }

    @Test
    void epochAlwaysBeatsDayEvenWhenDayIsLarger() {
        List<RecentRecordSelection.Candidate> list = List.of(day(999L), epoch(1L));
        assertEquals(1, RecentRecordSelection.selectIndex(list));
        assertEquals(-1, RecentRecordSelection.selectIndex(List.of(none(), none())));
    }

    @Test
    void dayFallbackIsUsedOnlyWhenNoEntryHasAnEpoch() {
        assertEquals(1, RecentRecordSelection.selectIndex(List.of(day(4L), day(7L), day(6L))));
    }

    @Test
    void missingTimestampsAreSkippedAndTiesKeepTheFirst() {
        assertEquals(1, RecentRecordSelection.selectIndex(List.of(none(), day(2L), none())));
        assertEquals(0, RecentRecordSelection.selectIndex(List.of(epoch(5L), epoch(5L))));
    }
}
