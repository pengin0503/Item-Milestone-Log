package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.TargetFilterModel.Kind;
import com.pengin0503.itemmilestonelog.TargetFilterModel.QueryIssue;
import com.pengin0503.itemmilestonelog.TargetFilterModel.QueryValidationException;
import com.pengin0503.itemmilestonelog.TargetFilterModel.SearchHit;
import com.pengin0503.itemmilestonelog.TargetFilterModel.SearchScope;
import com.pengin0503.itemmilestonelog.TargetFilterModel.SearchSource;
import com.pengin0503.itemmilestonelog.TargetFilterModel.TargetView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the F-150-1 / F-150-2 shared filtering foundation. */
class TargetFilterModelTest {
    private static TargetView view(String itemId, int count) {
        return new TargetView(itemId, count);
    }

    // ---------------------------------------------------------------- tokenization

    @Test
    void tokensNormalizeWhitespaceCaseAndNamespaceQueries() {
        assertEquals(List.of("iron", "block"), TargetFilterModel.tokens("  IRON   Block  "));
        assertEquals(List.of("@mod", "gem"), TargetFilterModel.tokens("@MOD\tgem"));
        assertEquals(List.of(), TargetFilterModel.tokens(""));
        assertEquals(List.of(), TargetFilterModel.tokens("   "));
        assertEquals(List.of(), TargetFilterModel.tokens(null));
    }

    @Test
    void validateQueryRejectsEmptyAndOversizeQueries() {
        assertEquals(QueryIssue.EMPTY, assertThrows(QueryValidationException.class,
                () -> TargetFilterModel.validateQuery("   ")).issue());
        assertEquals(QueryIssue.EMPTY, assertThrows(QueryValidationException.class,
                () -> TargetFilterModel.validateQuery(null)).issue());

        String atLimit = "a".repeat(TargetFilterModel.MAX_QUERY_LENGTH);
        TargetFilterModel.validateQuery(atLimit);
        assertEquals(QueryIssue.TOO_LONG, assertThrows(QueryValidationException.class,
                () -> TargetFilterModel.validateQuery(atLimit + "a")).issue());

        TargetFilterModel.validateQuery("a b c d e f g h"); // 8 tokens
        assertEquals(QueryIssue.TOO_LONG, assertThrows(QueryValidationException.class,
                () -> TargetFilterModel.validateQuery("a b c d e f g h i")).issue());
        // Oversize token count shares the single 'query too long' lang key.
        assertEquals(QueryIssue.TOO_LONG, assertThrows(QueryValidationException.class,
                () -> TargetFilterModel.validateQuery("a b c d e f g h i j")).issue());
    }

    // ---------------------------------------------------------------- keyword matching

    @Test
    void keywordMatchesItemIdPartsCaseInsensitively() {
        TargetView ingot = view("minecraft:iron_ingot", 1);
        assertTrue(TargetFilterModel.matchesKeyword(ingot, TargetFilterModel.tokens("iron")));
        assertTrue(TargetFilterModel.matchesKeyword(ingot, List.of("IRON")));
        assertTrue(TargetFilterModel.matchesKeyword(ingot, TargetFilterModel.tokens("@minecraft")));
        assertFalse(TargetFilterModel.matchesKeyword(ingot, TargetFilterModel.tokens("diamond")));
        // Namespace is a substring match, so a genuinely absent namespace is the non-match case.
        assertFalse(TargetFilterModel.matchesKeyword(ingot, TargetFilterModel.tokens("@no_such_ns")));
    }

    @Test
    void keywordMatchesTagIdPartsIncludingHashAndNamespace() {
        TargetView tag = view("#c:ingots/iron", 1);
        assertTrue(TargetFilterModel.matchesKeyword(tag, TargetFilterModel.tokens("iron")));
        assertTrue(TargetFilterModel.matchesKeyword(tag, TargetFilterModel.tokens("@c")));
        assertTrue(TargetFilterModel.matchesKeyword(tag, TargetFilterModel.tokens("ingots/iron")));
        assertTrue(TargetFilterModel.matchesKeyword(tag, TargetFilterModel.tokens("#c:")));
    }

    @Test
    void displayNamesAreNotMatchedOnlyIdPartsAre() {
        // D-150-1 pin: matching consults only full/path/@namespace, never a localized display name.
        // A word that exists only in a hypothetical display name must not create a hit.
        TargetView ingot = view("minecraft:iron_ingot", 1);
        assertFalse(TargetFilterModel.matchesKeyword(ingot, TargetFilterModel.tokens("ferro")));
        assertFalse(TargetFilterModel.matchesKeyword(ingot, TargetFilterModel.tokens("shiny")));

        // Note (spec contradiction, recorded in progress-150-pr1.md): the spec's literal example
        // 'query "Iron Ingot" must not hit minecraft:iron_ingot' cannot hold under the normative
        // whitespace+substring rule, because both 'iron' and 'ingot' are ID substrings.
    }

    @Test
    void multipleTokensCombineWithAndAndBareAtIsNoOp() {
        TargetView ingot = view("minecraft:iron_ingot", 1);
        assertTrue(TargetFilterModel.matchesKeyword(ingot, TargetFilterModel.tokens("iron ingot")));
        assertFalse(TargetFilterModel.matchesKeyword(ingot, TargetFilterModel.tokens("iron diamond")));
        assertTrue(TargetFilterModel.matchesKeyword(ingot, TargetFilterModel.tokens("@")));
        assertTrue(TargetFilterModel.matchesKeyword(ingot, TargetFilterModel.tokens("@ iron")));
        assertTrue(TargetFilterModel.matchesKeyword(ingot, List.of()));
    }

    // ---------------------------------------------------------------- kind boundaries

    @Test
    void kindBoundariesAllowOverlapsAndDefaultExactMembership() {
        TargetView plainTag = view("#c:ingots/iron", 1);
        TargetView plainItem = view("minecraft:diamond", 1);
        TargetView countedItem = view("minecraft:diamond", 100);
        TargetView countedTag = view("#c:ingots/iron", 100);
        Set<TargetView> defaults = Set.of(plainItem, countedTag);

        assertTrue(TargetFilterModel.matchesKind(plainTag, Kind.TAGS, defaults));
        assertFalse(TargetFilterModel.matchesKind(plainTag, Kind.ITEMS, defaults));
        assertFalse(TargetFilterModel.matchesKind(plainTag, Kind.COUNTS, defaults));
        assertFalse(TargetFilterModel.matchesKind(plainTag, Kind.DEFAULT, defaults));

        assertFalse(TargetFilterModel.matchesKind(plainItem, Kind.TAGS, defaults));
        assertTrue(TargetFilterModel.matchesKind(plainItem, Kind.ITEMS, defaults));
        assertFalse(TargetFilterModel.matchesKind(plainItem, Kind.COUNTS, defaults));
        assertTrue(TargetFilterModel.matchesKind(plainItem, Kind.DEFAULT, defaults));

        assertFalse(TargetFilterModel.matchesKind(countedItem, Kind.TAGS, defaults));
        assertFalse(TargetFilterModel.matchesKind(countedItem, Kind.ITEMS, defaults));
        assertTrue(TargetFilterModel.matchesKind(countedItem, Kind.COUNTS, defaults));
        assertFalse(TargetFilterModel.matchesKind(countedItem, Kind.DEFAULT, defaults));

        // Overlap: a tagged count target is both TAGS and COUNTS, and may also be DEFAULT.
        assertTrue(TargetFilterModel.matchesKind(countedTag, Kind.TAGS, defaults));
        assertTrue(TargetFilterModel.matchesKind(countedTag, Kind.COUNTS, defaults));
        assertTrue(TargetFilterModel.matchesKind(countedTag, Kind.DEFAULT, defaults));
        // Same ID with a different count is not the exact (itemId, count) default membership.
        assertFalse(TargetFilterModel.matchesKind(view("#c:ingots/iron", 1), Kind.DEFAULT, defaults));

        // A key that is both a personal target and a config default still matches DEFAULT.
        assertTrue(TargetFilterModel.matchesKind(plainItem, Kind.DEFAULT, Set.of(plainItem)));
        assertFalse(TargetFilterModel.matchesKind(plainItem, Kind.DEFAULT, null));
        assertTrue(TargetFilterModel.matchesKind(plainItem, null, defaults));
    }

    // ---------------------------------------------------------------- apply / equivalence

    private record Fixture(String label, TargetView view) {
    }

    private static List<Fixture> fixture() {
        List<Fixture> items = new ArrayList<>();
        items.add(new Fixture("tag-1", view("#c:ingots/iron", 1)));
        items.add(new Fixture("tag-100", view("#c:ingots/iron", 100)));
        items.add(new Fixture("item-1", view("minecraft:diamond", 1)));
        items.add(new Fixture("item-100", view("minecraft:diamond", 100)));
        items.add(new Fixture("item-2", view("minecraft:iron_ingot", 2)));
        items.add(new Fixture("other-1", view("mod:gem", 1)));
        while (items.size() < 20) {
            items.add(new Fixture("extra-" + items.size(), view("mod:extra_" + items.size(), 1)));
        }
        return items;
    }

    @Test
    void applyWithEmptyCriteriaReturnsEqualOrderedNewList() {
        List<Fixture> input = fixture();
        List<Fixture> result = TargetFilterModel.apply(input, Fixture::view, List.of(), null, Set.of());
        assertEquals(input, result);
        assertNotSame(input, result);

        // Same identity when explicitly passing null token/kind/default collections.
        assertEquals(input, TargetFilterModel.apply(input, Fixture::view, null, null, null));
    }

    @Test
    void applyCombinesKeywordAndKindWithAndAndPreservesOrder() {
        List<Fixture> input = fixture();
        Set<TargetView> defaults = Set.of(view("minecraft:diamond", 1));
        List<Fixture> result = TargetFilterModel.apply(
                input, Fixture::view, TargetFilterModel.tokens("@c iron"), Kind.TAGS, defaults);
        assertEquals(List.of("tag-1", "tag-100"), result.stream().map(Fixture::label).toList());

        // One criterion fails -> excluded (AND, not OR).
        List<Fixture> andResult = TargetFilterModel.apply(
                input, Fixture::view, TargetFilterModel.tokens("iron"), Kind.ITEMS, defaults);
        assertEquals(List.of(), andResult);

        // Simulates pending + kind + query: pre-filtered input, then both criteria apply.
        List<Fixture> pending = input.stream()
                .filter(f -> f.view().count() > 1)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        List<Fixture> triple = TargetFilterModel.apply(
                pending, Fixture::view, TargetFilterModel.tokens("iron"), Kind.COUNTS, Set.of());
        assertEquals(List.of("tag-100", "item-2"), triple.stream().map(Fixture::label).toList());
    }

    @Test
    void applyUsesInsertionOrderDefaultsWithoutMutatingInput() {
        List<Fixture> input = fixture();
        List<Fixture> snapshot = new ArrayList<>(input);
        Set<TargetView> defaults = new LinkedHashSet<>(Set.of(view("minecraft:diamond", 1)));
        List<Fixture> result = TargetFilterModel.apply(
                input, Fixture::view, List.of(), Kind.DEFAULT, defaults);
        assertEquals(List.of("item-1"), result.stream().map(Fixture::label).toList());
        assertEquals(snapshot, input);
    }

    // ------------------------------------------------------------- F-150-3 unified search

    private static TargetFilterModel.SearchCandidate targetCandidate(String id, int count) {
        return TargetFilterModel.searchTarget(id, count);
    }

    private static TargetFilterModel.SearchCandidate recordCandidate(String id, int count) {
        return TargetFilterModel.searchRecord(id, count);
    }

    private static TargetFilterModel.SearchCandidate presetCandidate(String id, String... searchables) {
        return TargetFilterModel.searchPreset(id, List.of(searchables));
    }

    private static SearchHit targetHit(String id, int count) {
        return new SearchHit(SearchSource.TARGET, id, count, null);
    }

    private static SearchHit recordHit(String id, int count) {
        return new SearchHit(SearchSource.RECORD, id, count, null);
    }

    private static SearchHit presetHit(String id) {
        return new SearchHit(SearchSource.PRESET, null, 0, id);
    }

    @Test
    void searchScopesSelectOnlyTheirOwnDataAndPreserveScopeOrder() {
        List<TargetFilterModel.SearchCandidate> targets = List.of(
                targetCandidate("minecraft:iron_ingot", 1), targetCandidate("minecraft:diamond", 1));
        List<TargetFilterModel.SearchCandidate> records = List.of(
                recordCandidate("minecraft:iron_ingot", 1), recordCandidate("mod:gem", 1));
        List<TargetFilterModel.SearchCandidate> presets = List.of(
                presetCandidate("iron_kit", "iron_kit", "Iron Kit"));
        List<String> iron = TargetFilterModel.tokens("iron");

        assertEquals(List.of(targetHit("minecraft:iron_ingot", 1)),
                TargetFilterModel.search(targets, records, presets, SearchScope.TARGETS, iron));
        assertEquals(List.of(recordHit("minecraft:iron_ingot", 1)),
                TargetFilterModel.search(targets, records, presets, SearchScope.RECORDS, iron));
        assertEquals(List.of(presetHit("iron_kit")),
                TargetFilterModel.search(targets, records, presets, SearchScope.PRESETS, iron));
        assertEquals(List.of(),
                TargetFilterModel.search(targets, records, presets, SearchScope.PRESETS,
                        TargetFilterModel.tokens("diamond")));

        // A bare '@' token is a no-op under the F-150-1 rules, so it exposes the full flat order.
        assertEquals(List.of(
                        targetHit("minecraft:iron_ingot", 1), targetHit("minecraft:diamond", 1),
                        recordHit("minecraft:iron_ingot", 1), recordHit("mod:gem", 1),
                        presetHit("iron_kit")),
                TargetFilterModel.search(targets, records, presets, SearchScope.ALL,
                        TargetFilterModel.tokens("@")));
        assertEquals(List.of(targetHit("minecraft:iron_ingot", 1), recordHit("minecraft:iron_ingot", 1),
                        presetHit("iron_kit")),
                TargetFilterModel.search(targets, records, presets, SearchScope.ALL, iron));
    }

    @Test
    void searchKeepsDuplicateTargetAndRecordKeysButEachPresetOnlyOnce() {
        List<TargetFilterModel.SearchCandidate> targets = List.of(targetCandidate("minecraft:diamond", 1));
        List<TargetFilterModel.SearchCandidate> records = List.of(recordCandidate("minecraft:diamond", 1));
        List<TargetFilterModel.SearchCandidate> presets = List.of(
                presetCandidate("starter", "starter", "Starter Kit"));

        // Same (itemId, count) in both sources: both hits survive, distinguished by source.
        assertEquals(List.of(targetHit("minecraft:diamond", 1), recordHit("minecraft:diamond", 1)),
                TargetFilterModel.search(targets, records, presets, SearchScope.ALL,
                        TargetFilterModel.tokens("diamond")));

        // Two searchable strings of one preset match: still exactly one hit for that preset.
        assertEquals(List.of(presetHit("starter")),
                TargetFilterModel.search(List.of(), List.of(), presets, SearchScope.PRESETS,
                        TargetFilterModel.tokens("starter kit")));
        assertEquals(List.of(presetHit("starter")),
                TargetFilterModel.search(List.of(), List.of(), presets, SearchScope.PRESETS,
                        TargetFilterModel.tokens("kit starter")));
    }

    @Test
    void searchCapsAt200HitsAcrossScopesAndCountsTheRest() {
        List<TargetFilterModel.SearchCandidate> targets = new ArrayList<>();
        for (int i = 0; i < 205; i++) {
            targets.add(targetCandidate("mod:item_" + i, 1));
        }
        List<TargetFilterModel.SearchCandidate> records = List.of(recordCandidate("mod:record_1", 1));
        TargetFilterModel.SearchResult result = TargetFilterModel.searchWithOverflow(
                targets, records, List.of(), SearchScope.ALL, List.of());

        assertEquals(TargetFilterModel.SEARCH_RESULT_LIMIT, result.hits().size());
        assertEquals(6, result.moreCount()); // 206 matches, 200 shown
        assertEquals(targetHit("mod:item_0", 1), result.hits().get(0));
        assertEquals(targetHit("mod:item_199", 1), result.hits().get(199));
        assertEquals(TargetFilterModel.SEARCH_RESULT_LIMIT,
                TargetFilterModel.search(targets, records, List.of(), SearchScope.ALL, List.of()).size());
        assertEquals(5, TargetFilterModel.searchWithOverflow(
                targets, records, List.of(), SearchScope.TARGETS, List.of()).moreCount());
        // No overflow means no search_more count.
        assertEquals(0, TargetFilterModel.searchWithOverflow(
                List.of(targetCandidate("mod:item_1", 1)), List.of(), List.of(),
                SearchScope.ALL, List.of()).moreCount());
    }

    @Test
    void searchHandlesEmptyResultsQueryValidationAndScopeParsing() {
        TargetFilterModel.SearchResult none = TargetFilterModel.searchWithOverflow(
                List.of(targetCandidate("minecraft:diamond", 1)),
                List.of(recordCandidate("minecraft:diamond", 1)), List.of(),
                SearchScope.ALL, TargetFilterModel.tokens("iron"));
        assertEquals(List.of(), none.hits());
        assertEquals(0, none.moreCount());

        // The command layer calls validateQuery before search; both failures use F-150-1 keys.
        assertEquals(QueryIssue.EMPTY, assertThrows(QueryValidationException.class,
                () -> TargetFilterModel.validateQuery("  ")).issue());
        assertEquals(QueryIssue.TOO_LONG, assertThrows(QueryValidationException.class,
                () -> TargetFilterModel.validateQuery("a".repeat(TargetFilterModel.MAX_QUERY_LENGTH + 1))).issue());

        assertEquals(SearchScope.ALL, SearchScope.parse(null));
        assertEquals(SearchScope.RECORDS, SearchScope.parse("RECORDS"));
        assertEquals(SearchScope.PRESETS, SearchScope.parse("presets"));
        assertNull(SearchScope.parse("nope"));
        assertNull(SearchScope.parse(""));
        assertFalse(SearchScope.ALL.failsWhenPresetsDisabled());
        assertTrue(SearchScope.PRESETS.failsWhenPresetsDisabled());
        assertFalse(SearchScope.ALL.includesPresets(false));
        assertTrue(SearchScope.ALL.includesPresets(true));
        assertFalse(SearchScope.TARGETS.includesPresets(true));
        assertFalse(SearchScope.RECORDS.includesPresets(true));
    }

    @Test
    void presetSearchMatchesLangResolvedNamesFromTheAdapterButNotOtherFields() {
        // Strings below are what the version-tree adapter supplies via ServerLang.formatForLang
        // (gui.itemmilestonelog.preset.atm_star) and PresetName.resolve for a custom preset.
        String enUsBuiltin = "Preset: ATM Star";
        String jaJpBuiltin = "プリセット: ATM Star";
        List<TargetFilterModel.SearchCandidate> builtin = List.of(
                presetCandidate("atm_star", "atm_star", enUsBuiltin, jaJpBuiltin));

        assertEquals(List.of(presetHit("atm_star")),
                TargetFilterModel.search(List.of(), List.of(), builtin, SearchScope.PRESETS,
                        TargetFilterModel.tokens("ATM STAR")));
        assertEquals(List.of(presetHit("atm_star")),
                TargetFilterModel.search(List.of(), List.of(), builtin, SearchScope.PRESETS,
                        TargetFilterModel.tokens("プリセット")));
        // description / category / items / requiredMods are never handed to the matcher.
        assertEquals(List.of(),
                TargetFilterModel.search(List.of(), List.of(), builtin, SearchScope.PRESETS,
                        TargetFilterModel.tokens("allthetweaks:atm_star")));

        Map<String, String> localizedValues = new LinkedHashMap<>();
        localizedValues.put("ja_jp", "スターターキット");
        localizedValues.put("en_us", "Starter Kit");
        PresetName custom = PresetName.ofLocalized(localizedValues);
        assertEquals("スターターキット", custom.resolve("ja_jp", "starter"));
        assertEquals("Starter Kit", custom.resolve("fr_fr", "starter")); // en_us fallback
        assertEquals("Starter Kit", custom.resolve(null, "starter")); // console: en_us priority

        List<TargetFilterModel.SearchCandidate> customCandidates = List.of(presetCandidate("starter",
                "starter", custom.resolve("ja_jp", "starter")));
        assertEquals(List.of(presetHit("starter")),
                TargetFilterModel.search(List.of(), List.of(), customCandidates,
                        SearchScope.PRESETS, TargetFilterModel.tokens("スターター")));
        // The ID always matches; the en_us alternative is absent when ja_jp was resolved.
        assertEquals(List.of(presetHit("starter")),
                TargetFilterModel.search(List.of(), List.of(), customCandidates,
                        SearchScope.PRESETS, TargetFilterModel.tokens("starter")));
        assertEquals(List.of(),
                TargetFilterModel.search(List.of(), List.of(), customCandidates,
                        SearchScope.PRESETS, TargetFilterModel.tokens("kit")));
        // Absent names fall back to the ID, exactly like preset.resolveDisplayName(lang).
        assertEquals("starter", PresetName.absent().resolve("ja_jp", "starter"));
    }
}
