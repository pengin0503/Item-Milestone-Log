package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.pengin0503.itemmilestonelog.DiscordRouting.Destination;
import static com.pengin0503.itemmilestonelog.DiscordRouting.PostKind;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DiscordRoutingTest {
    private record StubEntry(String id, boolean worldFirst) {
    }

    @Test
    void routingMatrixCoversAllPostKindsUrlCombinationsAndScopes() {
        String[] scopes = {"all", "world_first"};
        boolean[] flags = {false, true};

        for (PostKind kind : PostKind.values()) {
            for (boolean mainSet : flags) {
                for (boolean wfSet : flags) {
                    for (String scope : scopes) {
                        Set<Destination> expected = expectedRoute(kind, mainSet, wfSet, scope);
                        Set<Destination> actual = DiscordRouting.route(kind, mainSet, wfSet, scope);
                        assertEquals(expected, actual,
                                "Mismatch for kind=" + kind + ", mainSet=" + mainSet
                                        + ", wfSet=" + wfSet + ", scope=" + scope);
                    }
                }
            }
        }
    }

    private static Set<Destination> expectedRoute(
            PostKind kind, boolean mainSet, boolean wfSet, String scope) {
        boolean allScope = !"world_first".equals(scope);
        return switch (kind) {
            case RECORD, GROUPED_NORMAL_ONLY ->
                    (mainSet && allScope) ? Set.of(Destination.MAIN) : Set.of();
            case WORLD_FIRST_RECORD, GROUPED_WORLD_FIRST_ONLY -> {
                if (wfSet) {
                    yield Set.of(Destination.WORLD_FIRST);
                }
                yield mainSet ? Set.of(Destination.MAIN) : Set.of();
            }
            case GROUPED_MIXED -> {
                if (wfSet) {
                    yield (mainSet && allScope)
                            ? Set.of(Destination.MAIN, Destination.WORLD_FIRST)
                            : Set.of(Destination.WORLD_FIRST);
                }
                yield mainSet ? Set.of(Destination.MAIN) : Set.of();
            }
            case PRESET_COMPLETION, DIGEST, TEST_MAIN ->
                    mainSet ? Set.of(Destination.MAIN) : Set.of();
            case TEST_WORLD_FIRST ->
                    wfSet ? Set.of(Destination.WORLD_FIRST) : Set.of();
        };
    }

    @Test
    void spotChecksKeySpecRules() {
        // World-first URL set: world-first goes ONLY to WORLD_FIRST (never duplicated to MAIN)
        assertEquals(Set.of(Destination.WORLD_FIRST),
                DiscordRouting.route(PostKind.WORLD_FIRST_RECORD, true, true, "all"));
        assertEquals(Set.of(Destination.WORLD_FIRST),
                DiscordRouting.route(PostKind.WORLD_FIRST_RECORD, true, true, "world_first"));
        assertEquals(Destination.WORLD_FIRST,
                DiscordRouting.routeRecord(true, true, true, "all"));

        // scope=world_first + both URLs set: MAIN gets NO record posts, but F19 and F9 still go to MAIN
        assertEquals(Set.of(),
                DiscordRouting.route(PostKind.RECORD, true, true, "world_first"));
        assertEquals(Set.of(Destination.WORLD_FIRST),
                DiscordRouting.route(PostKind.GROUPED_MIXED, true, true, "world_first"));
        assertEquals(Set.of(Destination.MAIN),
                DiscordRouting.route(PostKind.PRESET_COMPLETION, true, true, "world_first"));
        assertEquals(Set.of(Destination.MAIN),
                DiscordRouting.route(PostKind.DIGEST, true, true, "world_first"));

        // Only world-first URL set: only world firsts and worldfirst test are posted
        assertEquals(Set.of(),
                DiscordRouting.route(PostKind.RECORD, false, true, "all"));
        assertEquals(Set.of(Destination.WORLD_FIRST),
                DiscordRouting.route(PostKind.WORLD_FIRST_RECORD, false, true, "all"));
        assertEquals(Set.of(),
                DiscordRouting.route(PostKind.PRESET_COMPLETION, false, true, "all"));
        assertEquals(Set.of(),
                DiscordRouting.route(PostKind.DIGEST, false, true, "all"));
        assertEquals(Set.of(),
                DiscordRouting.route(PostKind.TEST_MAIN, false, true, "all"));
        assertEquals(Set.of(Destination.WORLD_FIRST),
                DiscordRouting.route(PostKind.TEST_WORLD_FIRST, false, true, "all"));

        // Only main URL set: world firsts fall back to MAIN under both scopes
        assertEquals(Destination.MAIN,
                DiscordRouting.routeRecord(true, true, false, "all"));
        assertEquals(Destination.MAIN,
                DiscordRouting.routeRecord(true, true, false, "world_first"));
        assertEquals(Destination.MAIN,
                DiscordRouting.routeRecord(false, true, false, "all"));
        assertNull(DiscordRouting.routeRecord(false, true, false, "world_first"));

        // Neither URL set: nothing goes anywhere
        for (PostKind kind : PostKind.values()) {
            assertEquals(Set.of(), DiscordRouting.route(kind, false, false, "all"));
            assertEquals(Set.of(), DiscordRouting.route(kind, false, false, "world_first"));
        }
    }

    @Test
    void classifyAndSplitBatchByDestinationPreservesOrderAndConsistency() {
        assertNull(DiscordRouting.classifyGroupedBatch(List.of(), StubEntry::worldFirst));
        assertNull(DiscordRouting.classifyGroupedBatch(null, StubEntry::worldFirst));

        List<StubEntry> normalOnly = List.of(
                new StubEntry("a", false),
                new StubEntry("b", false));
        List<StubEntry> wfOnly = List.of(
                new StubEntry("c", true),
                new StubEntry("d", true));
        List<StubEntry> mixed = List.of(
                new StubEntry("n1", false),
                new StubEntry("wf1", true),
                new StubEntry("n2", false),
                new StubEntry("wf2", true),
                new StubEntry("n3", false));

        assertEquals(PostKind.GROUPED_NORMAL_ONLY,
                DiscordRouting.classifyGroupedBatch(normalOnly, StubEntry::worldFirst));
        assertEquals(PostKind.GROUPED_WORLD_FIRST_ONLY,
                DiscordRouting.classifyGroupedBatch(wfOnly, StubEntry::worldFirst));
        assertEquals(PostKind.GROUPED_MIXED,
                DiscordRouting.classifyGroupedBatch(mixed, StubEntry::worldFirst));

        // Both URLs + scope=all: 3 normal to MAIN, 2 WF to WORLD_FIRST (order preserved)
        DiscordRouting.RecordBatchSplit<StubEntry> splitBothAll =
                DiscordRouting.splitRecordsByDestination(mixed, StubEntry::worldFirst, true, true, "all");
        assertEquals(List.of("n1", "n2", "n3"),
                splitBothAll.mainRecords().stream().map(StubEntry::id).toList());
        assertEquals(List.of("wf1", "wf2"),
                splitBothAll.worldFirstRecords().stream().map(StubEntry::id).toList());

        // Both URLs + scope=world_first: 0 to MAIN, 2 WF to WORLD_FIRST
        DiscordRouting.RecordBatchSplit<StubEntry> splitBothWf =
                DiscordRouting.splitRecordsByDestination(mixed, StubEntry::worldFirst, true, true, "world_first");
        assertEquals(List.of(), splitBothWf.mainRecords());
        assertEquals(List.of("wf1", "wf2"),
                splitBothWf.worldFirstRecords().stream().map(StubEntry::id).toList());

        // Only WF URL + scope=all: 0 to MAIN, 2 WF to WORLD_FIRST
        DiscordRouting.RecordBatchSplit<StubEntry> splitWfOnly =
                DiscordRouting.splitRecordsByDestination(mixed, StubEntry::worldFirst, false, true, "all");
        assertEquals(List.of(), splitWfOnly.mainRecords());
        assertEquals(List.of("wf1", "wf2"),
                splitWfOnly.worldFirstRecords().stream().map(StubEntry::id).toList());

        // Only MAIN URL + scope=all: all 5 to MAIN, 0 to WORLD_FIRST
        DiscordRouting.RecordBatchSplit<StubEntry> splitMainAll =
                DiscordRouting.splitRecordsByDestination(mixed, StubEntry::worldFirst, true, false, "all");
        assertEquals(5, splitMainAll.mainRecords().size());
        assertEquals(List.of(), splitMainAll.worldFirstRecords());

        // Only MAIN URL + scope=world_first: 2 WF to MAIN, 0 to WORLD_FIRST
        DiscordRouting.RecordBatchSplit<StubEntry> splitMainWf =
                DiscordRouting.splitRecordsByDestination(mixed, StubEntry::worldFirst, true, false, "world_first");
        assertEquals(List.of("wf1", "wf2"),
                splitMainWf.mainRecords().stream().map(StubEntry::id).toList());
        assertEquals(List.of(), splitMainWf.worldFirstRecords());
    }
}
