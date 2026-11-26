package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for F30 page slicing, total-page calculation, and clickable command formatting. */
class PaginationTest {
    @Test
    void totalPagesComputesCeilingWithoutOverflow() {
        assertEquals(0, Pagination.totalPages(0));
        assertEquals(0, Pagination.totalPages(-5));
        assertEquals(1, Pagination.totalPages(1));
        assertEquals(1, Pagination.totalPages(15));
        assertEquals(2, Pagination.totalPages(16));
        assertEquals(2, Pagination.totalPages(30));
        assertEquals(3, Pagination.totalPages(31));

        assertEquals(1, Pagination.totalPages(1, 1));
        assertEquals(5, Pagination.totalPages(5, 1));
        assertEquals(3, Pagination.totalPages(10, 4));
        assertEquals((Integer.MAX_VALUE - 1) / 15 + 1, Pagination.totalPages(Integer.MAX_VALUE));

        assertThrows(IllegalArgumentException.class, () -> Pagination.totalPages(10, 0));
        assertThrows(IllegalArgumentException.class, () -> Pagination.totalPages(10, -1));
    }

    @Test
    void sliceReturnsSinglePageWithoutPrevOrNext() {
        List<Integer> items = IntStream.rangeClosed(1, 15).boxed().toList();
        Pagination.PageSlice<Integer> slice = Pagination.slice(items, 1);

        assertFalse(slice.isOutOfRange());
        assertEquals(1, slice.page());
        assertEquals(1, slice.totalPages());
        assertEquals(15, slice.totalItems());
        assertEquals(items, slice.items());
        assertFalse(slice.hasPrevious());
        assertFalse(slice.hasNext());
    }

    @Test
    void slicePreservesOrderingAcrossFirstMiddleAndLastPages() {
        List<Integer> items = IntStream.rangeClosed(1, 32).boxed().toList();

        Pagination.PageSlice<Integer> p1 = Pagination.slice(items, 1);
        assertFalse(p1.isOutOfRange());
        assertEquals(1, p1.page());
        assertEquals(3, p1.totalPages());
        assertEquals(32, p1.totalItems());
        assertEquals(IntStream.rangeClosed(1, 15).boxed().toList(), p1.items());
        assertFalse(p1.hasPrevious());
        assertTrue(p1.hasNext());

        Pagination.PageSlice<Integer> p2 = Pagination.slice(items, 2);
        assertFalse(p2.isOutOfRange());
        assertEquals(2, p2.page());
        assertEquals(3, p2.totalPages());
        assertEquals(IntStream.rangeClosed(16, 30).boxed().toList(), p2.items());
        assertTrue(p2.hasPrevious());
        assertTrue(p2.hasNext());

        Pagination.PageSlice<Integer> p3 = Pagination.slice(items, 3);
        assertFalse(p3.isOutOfRange());
        assertEquals(3, p3.page());
        assertEquals(3, p3.totalPages());
        assertEquals(List.of(31, 32), p3.items());
        assertTrue(p3.hasPrevious());
        assertFalse(p3.hasNext());
    }

    @Test
    void sliceHandlesOutOfRangeAndEmptyLists() {
        List<String> items = List.of("a", "b", "c");

        Pagination.PageSlice<String> tooHigh = Pagination.slice(items, 2);
        assertTrue(tooHigh.isOutOfRange());
        assertEquals(2, tooHigh.page());
        assertEquals(1, tooHigh.totalPages());
        assertEquals(3, tooHigh.totalItems());
        assertTrue(tooHigh.items().isEmpty());
        assertFalse(tooHigh.hasPrevious());
        assertFalse(tooHigh.hasNext());

        Pagination.PageSlice<String> zero = Pagination.slice(items, 0);
        assertTrue(zero.isOutOfRange());
        assertTrue(zero.items().isEmpty());
        assertFalse(zero.hasPrevious());
        assertFalse(zero.hasNext());

        Pagination.PageSlice<String> empty = Pagination.slice(List.of(), 1);
        assertTrue(empty.isOutOfRange());
        assertEquals(0, empty.totalPages());
        assertEquals(0, empty.totalItems());
        assertTrue(empty.items().isEmpty());
        assertFalse(empty.hasPrevious());
        assertFalse(empty.hasNext());
    }

    @Test
    void pageCommandBuildsAllSupportedCommandFormsAndPreservesTypedPlayerSpelling() {
        assertEquals("/milestonelog list 3",
                Pagination.pageCommand(Pagination.listCommandPrefix(), 3));
        assertEquals("/milestonelog targets 2",
                Pagination.pageCommand(Pagination.targetsCommandPrefix(Pagination.TargetFilter.ALL), 2));
        assertEquals("/milestonelog targets pending 2",
                Pagination.pageCommand(Pagination.targetsCommandPrefix(Pagination.TargetFilter.PENDING), 2));
        assertEquals("/milestonelog targets done 4",
                Pagination.pageCommand(Pagination.targetsCommandPrefix(Pagination.TargetFilter.DONE), 4));
        assertEquals("/milestonelog firsts 1",
                Pagination.pageCommand(Pagination.firstsCommandPrefix(), 1));
        assertEquals("/milestonelog presettop demo 2",
                Pagination.pageCommand(Pagination.presettopCommandPrefix("demo"), 2));
        assertEquals("/milestonelog firsts top 3",
                Pagination.pageCommand(Pagination.firstsTopCommandPrefix(), 3));
        assertEquals("/milestonelog lookup aLeX 2",
                Pagination.pageCommand(Pagination.lookupCommandPrefix("aLeX"), 2));
        assertEquals("/milestonelog lookup 12345678-1234-1234-1234-123456789abc 5",
                Pagination.pageCommand(Pagination.lookupCommandPrefix("12345678-1234-1234-1234-123456789abc"), 5));
        assertEquals("/milestonelog admin players 2",
                Pagination.pageCommand(Pagination.adminPlayersCommandPrefix(), 2));
        assertEquals("/milestonelog admin sTeVe list 3",
                Pagination.pageCommand(Pagination.adminListCommandPrefix("sTeVe"), 3));
        assertEquals("/milestonelog admin sTeVe targets 2",
                Pagination.pageCommand(Pagination.adminTargetsCommandPrefix("sTeVe"), 2));

        assertThrows(IllegalArgumentException.class, () -> Pagination.pageCommand("/milestonelog list", 0));
        assertThrows(IllegalArgumentException.class, () -> Pagination.pageCommand("   ", 1));
    }

    @Test
    void filterSuffixOverloadsPreserveLegacyPrefixStringsAndCarryActiveFilters() {
        // Legacy no-argument forms must stay byte-identical.
        assertEquals("/milestonelog list", Pagination.listCommandPrefix());
        assertEquals("/milestonelog list", Pagination.listCommandPrefix(""));
        assertEquals("/milestonelog targets", Pagination.targetsCommandPrefix(Pagination.TargetFilter.ALL));
        assertEquals("/milestonelog targets pending", Pagination.targetsCommandPrefix(Pagination.TargetFilter.PENDING));
        assertEquals("/milestonelog targets done", Pagination.targetsCommandPrefix(Pagination.TargetFilter.DONE));
        for (Pagination.TargetFilter f : Pagination.TargetFilter.values()) {
            assertEquals(Pagination.targetsCommandPrefix(f), Pagination.targetsCommandPrefix(f, ""));
        }

        // Active filters are retained verbatim in the clickable page link.
        assertEquals("/milestonelog list filter \"iron block\"",
                Pagination.listCommandPrefix("filter \"iron block\""));
        assertEquals("/milestonelog targets pending filter \"iron block\" kind counts 2",
                Pagination.pageCommand(Pagination.targetsCommandPrefix(Pagination.TargetFilter.PENDING,
                        "filter \"iron block\" kind counts"), 2));
        assertEquals("/milestonelog search \"iron block\" all 2",
                Pagination.pageCommand(Pagination.searchCommandPrefix("iron block", "all"), 2));
    }

    @Test
    void searchPageLinksKeepTheQuotedQueryAndEveryScope() {
        // PR-1's helper shape is unchanged; the command layer passes the 'scope' literal as part
        // of the second argument so the generated clickable link parses with the real command tree.
        for (String scope : List.of("all", "targets", "records", "presets")) {
            assertEquals("/milestonelog search \"iron block\" " + scope + " 2",
                    Pagination.pageCommand(Pagination.searchCommandPrefix("iron block", scope), 2));
            assertEquals("/milestonelog search \"iron block\" scope " + scope + " 3",
                    Pagination.pageCommand(Pagination.searchCommandPrefix("iron block", "scope " + scope), 3));
        }
    }

    @Test
    void rankingPageLinksKeepPresetAndUnclaimedScope() {
        assertEquals("/milestonelog presettop my_preset", Pagination.presettopCommandPrefix("my_preset"));
        assertEquals("/milestonelog firsts top", Pagination.firstsTopCommandPrefix());
        for (String scope : List.of("targets", "presets", "all")) {
            assertEquals("/milestonelog firsts unclaimed scope " + scope + " 2",
                    Pagination.pageCommand(Pagination.firstsUnclaimedCommandPrefix(scope), 2));
        }
        assertThrows(IllegalArgumentException.class, () -> Pagination.firstsUnclaimedCommandPrefix(""));
    }
}
