package com.pengin0503.itemmilestonelog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.MutableComponent;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies F29 {@code /milestonelog help} invariants against the real registered Brigadier command
 * tree and both {@code en_us.json} and {@code ja_jp.json}.
 */
class MilestoneCommandsHelpTest {
    private static final Set<String> ADMIN_ONLY = Set.of(
            "exportall", "exporthtmlall", "exportcsvall", "importall",
            "admin", "prunedata", "renameitem", "renametag", "discordtest");

    private static CommandDispatcher<CommandSourceStack> buildDispatcher(Predicate<CommandSourceStack> adminRequirement) {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(MilestoneCommands.buildCommandTree(StringArgumentType::word, adminRequirement, dispatcher));
        return dispatcher;
    }

    private static Map<String, String> readLang(String language) throws Exception {
        String path = "/assets/" + ItemMilestoneLog.MOD_ID + "/lang/" + language + ".json";
        try (InputStream in = MilestoneCommandsHelpTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path + " is missing");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                Map<String, String> map = new TreeMap<>();
                for (Map.Entry<String, JsonElement> e : json.entrySet()) {
                    map.put(e.getKey(), e.getValue().getAsString());
                }
                return map;
            }
        }
    }

    private static CommandHelp.Entry entryByName(String name) {
        return CommandHelp.ENTRIES.stream()
                .filter(e -> e.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing help entry: " + name));
    }

    @Test
    void tagCommandsAreRegisteredIncludingNestedResettag() {
        CommandDispatcher<CommandSourceStack> dispatcher = buildDispatcher(src -> true);
        CommandNode<CommandSourceStack> root = dispatcher.getRoot().getChild("milestonelog");
        assertNotNull(root);
        for (String name : List.of("addtag", "removetag", "resettag", "toptag")) {
            CommandNode<CommandSourceStack> node = root.getChild(name);
            assertNotNull(node, name + " must be registered");
            assertNotNull(node.getChild("tag"), name + " must take a <tag> argument");
        }
        assertNotNull(root.getChild("firsts").getChild("resettag"), "firsts resettag must be registered");
        CommandNode<CommandSourceStack> adminPlayer = root.getChild("admin").getChild("player");
        assertNotNull(adminPlayer);
        assertNotNull(adminPlayer.getChild("resettag"), "admin <player> resettag must be registered");
    }

    @Test
    void tagIdsOfReturnsOnlyTagKeysWithoutHash() {
        assertEquals(Set.of("c:ingots/iron"), MilestoneCommands.tagIdsOf(List.of(
                com.pengin0503.itemmilestonelog.api.TargetKey.of("#c:ingots/iron"),
                com.pengin0503.itemmilestonelog.api.TargetKey.of("#c:ingots/iron", 10),
                com.pengin0503.itemmilestonelog.api.TargetKey.of("minecraft:diamond"))));
    }

    @Test
    void ownImportFileSuggestionsIncludeLegacyAndUuidFormsWithoutDuplicates() {
        UUID uuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
        List<String> files = List.of(
                "Alice.json", "Alice-" + uuid + ".json", "Bob.json", "Bob-" + uuid + ".json");
        assertEquals(List.of("Alice-" + uuid + ".json", "Alice.json"),
                MilestoneCommands.ownImportFileSuggestions(files, "Alice", uuid));
        // Only a legacy file exists for Carol: still suggested, without inventing a UUID form.
        assertEquals(List.of("Carol.json"),
                MilestoneCommands.ownImportFileSuggestions(List.of("Carol.json"), "Carol", uuid));
        // With no UUID both computed names coincide; a single suggestion is offered.
        assertEquals(List.of("Carol.json"),
                MilestoneCommands.ownImportFileSuggestions(List.of("Carol.json"), "Carol", null));
        assertEquals(List.of(),
                MilestoneCommands.ownImportFileSuggestions(List.of("Bob.json"), "Alice", uuid));
    }

    @Test
    void infoIsRegisteredForPlayersWithItemHandAndOptionalCount() {
        CommandDispatcher<CommandSourceStack> dispatcher = buildDispatcher(src -> false);
        CommandNode<CommandSourceStack> root = dispatcher.getRoot().getChild("milestonelog");
        assertNotNull(root);
        CommandNode<CommandSourceStack> info = root.getChild("info");
        assertNotNull(info, "info must be registered (F51)");
        assertNull(info.getCommand(), "bare /milestonelog info must not execute");
        CommandNode<CommandSourceStack> hand = info.getChild("hand");
        assertNotNull(hand, "info hand must be registered");
        assertNotNull(hand.getCommand(), "info hand must execute");
        CommandNode<CommandSourceStack> item = info.getChild("item");
        assertNotNull(item, "info must take <item>");
        assertNotNull(item.getCommand(), "info <item> must execute with the default count of 1");
        CommandNode<CommandSourceStack> count = item.getChild("count");
        assertNotNull(count, "info <item> must take an optional <count>");
        assertNotNull(count.getCommand(), "info <item> <count> must execute");
        // No .requires: the command tree itself is open to every source; the handler requires a
        // player (the F29 help row is player-only).
        assertTrue(MilestoneCommands.canUseHelpEntry(dispatcher, null, entryByName("info")));
        assertEquals(List.of("/milestonelog info <item> [<count>|preview]", "/milestonelog info hand [preview]"),
                MilestoneCommands.helpUsages(dispatcher, null, entryByName("info")));
    }

    @Test
    void renameItemIsRegisteredAtRootWithAdminRequirementAndOptionalConfirm() {
        CommandDispatcher<CommandSourceStack> op = buildDispatcher(src -> true);
        CommandNode<CommandSourceStack> root = op.getRoot().getChild("milestonelog");
        assertNotNull(root);
        CommandNode<CommandSourceStack> rename = root.getChild("renameitem");
        assertNotNull(rename, "renameitem must be registered at root");
        assertNotNull(rename.getChild("oldId"), "renameitem must take <oldId>");
        CommandNode<CommandSourceStack> newId = rename.getChild("oldId").getChild("newId");
        assertNotNull(newId, "renameitem must take <newId>");
        assertNotNull(newId.getCommand(), "renameitem <oldId> <newId> must execute preview");
        CommandNode<CommandSourceStack> confirm = newId.getChild("confirm");
        assertNotNull(confirm, "renameitem must take optional confirm literal");
        assertNotNull(confirm.getCommand(), "renameitem <oldId> <newId> confirm must execute apply");
        assertEquals(List.of("/milestonelog renameitem <oldId> <newId> [confirm]"),
                MilestoneCommands.helpUsages(op, null, entryByName("renameitem")));
    }

    @Test
    void renameTagUsesOneVanillaStringArgumentAndKeepsUnquotedSyntax() {
        CommandDispatcher<CommandSourceStack> op = buildDispatcher(src -> true);
        CommandNode<CommandSourceStack> root = op.getRoot().getChild("milestonelog");
        assertNotNull(root);
        CommandNode<CommandSourceStack> rename = root.getChild("renametag");
        assertNotNull(rename, "renametag must be registered at root");
        CommandNode<CommandSourceStack> tags = rename.getChild("tags");
        assertNotNull(tags, "renametag must take one greedy string argument");
        assertTrue(tags instanceof ArgumentCommandNode<?, ?>, "tags must be an argument node");
        assertEquals(StringArgumentType.class,
                ((ArgumentCommandNode<CommandSourceStack, ?>) tags).getType().getClass(),
                "renametag must use the vanilla StringArgumentType so vanilla clients can decode the tree");
        assertNotNull(tags.getCommand(), "renametag <oldTag> <newTag> must execute preview");
        assertEquals(List.of("/milestonelog renametag <oldTag> <newTag> [confirm]"),
                MilestoneCommands.helpUsages(op, null, entryByName("renametag")));

        CommandDispatcher<CommandSourceStack> regular = buildDispatcher(src -> false);
        ParseResults<CommandSourceStack> denied = regular.parse("milestonelog renametag #old:a #new:a", null);
        assertTrue(denied.getReader().canRead(), "Non-admin must not match /milestonelog renametag");

        // #129: unquoted #namespace:path, quoted forms, and the trailing confirm literal must
        // all still parse completely; the greedy argument captures them and is split at execution.
        for (String line : List.of(
                "milestonelog renametag #oldmod:gems #newmod:gems",
                "milestonelog renametag \"#old:a\" '#new:a'",
                "milestonelog renametag #c:ingots/iron #c:iron_ingots confirm")) {
            ParseResults<CommandSourceStack> parse = op.parse(line, null);
            assertFalse(parse.getReader().canRead(), "Admin must fully parse: " + line);
            assertTrue(parse.getExceptions().isEmpty(), line + ": " + parse.getExceptions());
            assertNotNull(parse.getContext().getCommand(), line);
            assertEquals(line.substring("milestonelog renametag ".length()), parsedArgument(parse, "tags"), line);
        }
    }

    @Test
    void filterAndKindBranchesAreRegisteredAndLegacyPageParsingIsUnchanged() {
        CommandDispatcher<CommandSourceStack> dispatcher = buildDispatcher(src -> true);
        CommandNode<CommandSourceStack> root = dispatcher.getRoot().getChild("milestonelog");
        assertNotNull(root);
        CommandNode<CommandSourceStack> targets = root.getChild("targets");
        assertNotNull(targets);
        CommandNode<CommandSourceStack> list = root.getChild("list");
        assertNotNull(list);

        // Existing page slots stay integer arguments with execute handlers (D-150-4).
        for (CommandNode<CommandSourceStack> pageNode : List.of(
                targets.getChild("page"), targets.getChild("pending").getChild("page"),
                targets.getChild("done").getChild("page"), list.getChild("page"))) {
            assertNotNull(pageNode, "page argument must still be registered");
            assertTrue(pageNode instanceof ArgumentCommandNode<?, ?>);
            assertEquals(IntegerArgumentType.class,
                    ((ArgumentCommandNode<CommandSourceStack, ?>) pageNode).getType().getClass());
            assertNotNull(pageNode.getCommand(), "page argument must still execute");
        }

        // New filter/kind branches are additive siblings on targets, pending and done.
        for (CommandNode<CommandSourceStack> branch : List.of(
                targets, targets.getChild("pending"), targets.getChild("done"))) {
            CommandNode<CommandSourceStack> filter = branch.getChild("filter");
            assertNotNull(filter, "filter branch must be registered");
            assertNull(filter.getCommand(), "bare filter must not execute");
            CommandNode<CommandSourceStack> query = filter.getChild("query");
            assertNotNull(query);
            assertEquals(StringArgumentType.class,
                    ((ArgumentCommandNode<CommandSourceStack, ?>) query).getType().getClass());
            assertNotNull(query.getCommand(), "filter <query> must execute");
            assertNotNull(query.getChild("page"));
            assertNotNull(query.getChild("page").getCommand());

            CommandNode<CommandSourceStack> filterKind = query.getChild("kind").getChild("kind");
            assertNotNull(filterKind, "filter <query> kind <kind> must be registered");
            assertEquals(StringArgumentType.class,
                    ((ArgumentCommandNode<CommandSourceStack, ?>) filterKind).getType().getClass());
            assertNotNull(filterKind.getCommand());
            assertNotNull(filterKind.getChild("page"));
            assertNotNull(filterKind.getChild("page").getCommand());

            CommandNode<CommandSourceStack> standaloneKind = branch.getChild("kind").getChild("kind");
            assertNotNull(standaloneKind, "kind <kind> must be registered");
            assertEquals(StringArgumentType.class,
                    ((ArgumentCommandNode<CommandSourceStack, ?>) standaloneKind).getType().getClass());
            assertNotNull(standaloneKind.getCommand());
            assertNotNull(standaloneKind.getChild("page"));
            assertNotNull(standaloneKind.getChild("page").getCommand());

            // Four suggested values, in the documented order (D-150-5).
            try {
                List<String> offered = standaloneKind.listSuggestions(
                        dispatcher.parse("milestonelog targets kind tags", null).getContext()
                                .build("milestonelog targets kind tags"),
                        new SuggestionsBuilder("", 0)).get().getList().stream()
                        .map(Suggestion::getText).toList();
                assertEquals(Set.of("tags", "items", "counts", "default"), Set.copyOf(offered));
            } catch (Exception e) {
                throw new AssertionError("kind suggestions failed", e);
            }
        }

        CommandNode<CommandSourceStack> listQuery = list.getChild("filter").getChild("query");
        assertNotNull(listQuery, "list filter <query> must be registered");
        assertNotNull(listQuery.getCommand());
        assertNull(listQuery.getChild("kind"), "list must not take a kind filter");
        assertNotNull(listQuery.getChild("page"));
        assertNotNull(listQuery.getChild("page").getCommand());

        // Legacy forms and new filtered forms all parse completely.
        for (String line : List.of(
                "milestonelog targets 2",
                "milestonelog targets pending 2",
                "milestonelog targets done",
                "milestonelog list 2",
                "milestonelog targets filter \"iron block\"",
                "milestonelog targets pending filter iron kind tags 2",
                "milestonelog targets done kind counts",
                "milestonelog list filter \"iron block\" 2")) {
            ParseResults<CommandSourceStack> parse = dispatcher.parse(line, null);
            assertFalse(parse.getReader().canRead(), "must fully parse: " + line);
            assertTrue(parse.getExceptions().isEmpty(), line + ": " + parse.getExceptions());
            assertNotNull(parse.getContext().getCommand(), line);
        }
    }

    @Test
    void searchBranchIsRegisteredWithScopeSuggestionsAndHelpEntry() {
        CommandDispatcher<CommandSourceStack> dispatcher = buildDispatcher(src -> true);
        CommandNode<CommandSourceStack> root = dispatcher.getRoot().getChild("milestonelog");
        assertNotNull(root);

        CommandNode<CommandSourceStack> search = root.getChild("search");
        assertNotNull(search, "search must be registered (F-150-3)");
        CommandNode<CommandSourceStack> query = search.getChild("query");
        assertNotNull(query, "search must take <query>");
        assertEquals(StringArgumentType.class,
                ((ArgumentCommandNode<CommandSourceStack, ?>) query).getType().getClass());
        assertNotNull(query.getCommand(), "search <query> must execute");

        CommandNode<CommandSourceStack> page = query.getChild("page");
        assertNotNull(page, "search <query> [<page>] must be registered");
        assertEquals(IntegerArgumentType.class,
                ((ArgumentCommandNode<CommandSourceStack, ?>) page).getType().getClass());
        assertNotNull(page.getCommand());

        CommandNode<CommandSourceStack> scopeLiteral = query.getChild("scope");
        assertNotNull(scopeLiteral, "search <query> scope <scope> must be registered");
        assertNull(scopeLiteral.getCommand(), "bare 'scope' must not execute");
        CommandNode<CommandSourceStack> scopeArg = scopeLiteral.getChild("scope");
        assertNotNull(scopeArg);
        assertEquals(StringArgumentType.class,
                ((ArgumentCommandNode<CommandSourceStack, ?>) scopeArg).getType().getClass());
        assertNotNull(scopeArg.getCommand(), "search <query> scope <scope> must execute");
        CommandNode<CommandSourceStack> scopedPage = scopeArg.getChild("page");
        assertNotNull(scopedPage, "search <query> scope <scope> [<page>] must be registered");
        assertNotNull(scopedPage.getCommand());

        // Scope suggestions are four vanilla word values (D-150-10).
        try {
            List<String> offered = scopeArg.listSuggestions(
                    dispatcher.parse("milestonelog search iron scope all", null).getContext()
                            .build("milestonelog search iron scope all"),
                    new SuggestionsBuilder("", 0)).get().getList().stream()
                    .map(Suggestion::getText).toList();
            assertEquals(Set.of("all", "targets", "records", "presets"), Set.copyOf(offered));
        } catch (Exception e) {
            throw new AssertionError("scope suggestions failed", e);
        }

        // Clickable page links keep the query and scope literal, and parse with the real tree.
        for (String line : List.of(
                "/milestonelog search \"iron block\" scope all 2",
                "/milestonelog search \"iron block\" scope presets")) {
            ParseResults<CommandSourceStack> parse = dispatcher.parse(line.substring(1), null);
            assertFalse(parse.getReader().canRead(), "must fully parse: " + line);
            assertTrue(parse.getExceptions().isEmpty(), line + ": " + parse.getExceptions());
            assertNotNull(parse.getContext().getCommand(), line);
        }

        CommandHelp.Entry entry = entryByName("search");
        assertTrue(entry.playerOnly(), "search is player-only like targets/list");
        assertEquals(CommandHelp.Gate.NONE, entry.gate(), "search must not add an admin/config gate");
    }

    @Test
    void searchPresetsEnabledGateOnlyFailsForExplicitPresetsScope() {
        assertEquals("fail", MilestoneCommands.searchPresetMode(
                com.pengin0503.itemmilestonelog.TargetFilterModel.SearchScope.PRESETS, false));
        assertEquals("skip", MilestoneCommands.searchPresetMode(
                com.pengin0503.itemmilestonelog.TargetFilterModel.SearchScope.ALL, false));
        assertEquals("skip", MilestoneCommands.searchPresetMode(
                com.pengin0503.itemmilestonelog.TargetFilterModel.SearchScope.TARGETS, false));
        assertEquals("skip", MilestoneCommands.searchPresetMode(
                com.pengin0503.itemmilestonelog.TargetFilterModel.SearchScope.RECORDS, true));
        assertEquals("include", MilestoneCommands.searchPresetMode(
                com.pengin0503.itemmilestonelog.TargetFilterModel.SearchScope.ALL, true));
        assertEquals("include", MilestoneCommands.searchPresetMode(
                com.pengin0503.itemmilestonelog.TargetFilterModel.SearchScope.PRESETS, true));
    }

    @Test
    void presettopIsRegisteredWithAWordPresetAndOptionalIntegerPage() {
        CommandDispatcher<CommandSourceStack> dispatcher = buildDispatcher(src -> true);
        CommandNode<CommandSourceStack> root = dispatcher.getRoot().getChild("milestonelog");
        assertNotNull(root);

        CommandNode<CommandSourceStack> presettop = root.getChild("presettop");
        assertNotNull(presettop, "presettop must be registered (F-150-4)");
        assertNull(presettop.getCommand(), "bare presettop must not execute");
        CommandNode<CommandSourceStack> preset = presettop.getChild("preset");
        assertNotNull(preset, "presettop must take <preset>");
        assertEquals(StringArgumentType.class,
                ((ArgumentCommandNode<CommandSourceStack, ?>) preset).getType().getClass());
        assertNotNull(preset.getCommand(), "presettop <preset> must execute");
        CommandNode<CommandSourceStack> page = preset.getChild("page");
        assertNotNull(page, "presettop <preset> [<page>] must be registered");
        assertEquals(IntegerArgumentType.class,
                ((ArgumentCommandNode<CommandSourceStack, ?>) page).getType().getClass());
        assertNotNull(page.getCommand());

        for (String line : List.of("milestonelog presettop demo", "milestonelog presettop demo 2")) {
            ParseResults<CommandSourceStack> parse = dispatcher.parse(line, null);
            assertFalse(parse.getReader().canRead(), "must fully parse: " + line);
            assertTrue(parse.getExceptions().isEmpty(), line + ": " + parse.getExceptions());
            assertNotNull(parse.getContext().getCommand(), line);
        }

        CommandHelp.Entry entry = entryByName("presettop");
        assertFalse(entry.playerOnly(), "presettop must remain console-usable like top");
        assertEquals(CommandHelp.Gate.LOOKUP, entry.gate());
        // PR-2 adds a trailing [axis] literal, which changes Brigadier smart usage; the legacy
        // presettop <preset> [<page>] usage must still be offered.
        assertTrue(MilestoneCommands.helpUsages(dispatcher, null, entry).stream()
                        .anyMatch(u -> u.startsWith("/milestonelog presettop <preset> ")
                                && u.contains("<page>")),
                () -> "Expected presettop page usage: "
                        + MilestoneCommands.helpUsages(dispatcher, null, entry));
        // A page link must be a fully parseable command with the preset preserved.
        for (String line : List.of("/milestonelog presettop demo 2")) {
            ParseResults<CommandSourceStack> parse = dispatcher.parse(line.substring(1), null);
            assertFalse(parse.getReader().canRead(), line);
            assertNotNull(parse.getContext().getCommand(), line);
        }
    }

    @Test
    void firstsTopAndUnclaimedArePublicSiblingsOfTheUnchangedAdminResets() {
        CommandDispatcher<CommandSourceStack> dispatcher = buildDispatcher(src -> true);
        CommandDispatcher<CommandSourceStack> regular = buildDispatcher(src -> false);
        CommandNode<CommandSourceStack> firsts = dispatcher.getRoot().getChild("milestonelog").getChild("firsts");
        assertNotNull(firsts);
        CommandNode<CommandSourceStack> regularFirsts = regular.getRoot().getChild("milestonelog").getChild("firsts");
        assertNotNull(regularFirsts);

        CommandNode<CommandSourceStack> top = firsts.getChild("top");
        assertNotNull(top, "firsts top must be registered (F-150-5)");
        assertNotNull(top.getCommand(), "firsts top must execute");
        assertNotNull(top.getChild("page"), "firsts top [<page>] must be registered");
        assertEquals(IntegerArgumentType.class,
                ((ArgumentCommandNode<CommandSourceStack, ?>) top.getChild("page")).getType().getClass());
        assertTrue(regularFirsts.getChild("top").canUse(null),
                "firsts top must not inherit firstsResetRequirement");

        CommandNode<CommandSourceStack> unclaimed = firsts.getChild("unclaimed");
        assertNotNull(unclaimed, "firsts unclaimed must be registered (F-150-6)");
        assertNotNull(unclaimed.getCommand(), "bare firsts unclaimed must execute");
        assertNotNull(unclaimed.getChild("page"), "firsts unclaimed [<page>] must be registered");
        CommandNode<CommandSourceStack> scopeLiteral = unclaimed.getChild("scope");
        assertNotNull(scopeLiteral, "firsts unclaimed scope <scope> must be registered");
        assertNull(scopeLiteral.getCommand(), "bare scope must not execute");
        CommandNode<CommandSourceStack> scope = scopeLiteral.getChild("scope");
        assertNotNull(scope);
        assertEquals(StringArgumentType.class,
                ((ArgumentCommandNode<CommandSourceStack, ?>) scope).getType().getClass());
        assertNotNull(scope.getCommand());
        assertNotNull(scope.getChild("page"));
        assertTrue(regularFirsts.getChild("unclaimed").canUse(null),
                "firsts unclaimed must not inherit firstsResetRequirement");

        try {
            List<String> offered = scope.listSuggestions(
                    dispatcher.parse("milestonelog firsts unclaimed scope targets", null).getContext()
                            .build("milestonelog firsts unclaimed scope targets"),
                    new SuggestionsBuilder("", 0)).get().getList().stream()
                    .map(Suggestion::getText).toList();
            assertEquals(Set.of("targets", "presets", "all"), Set.copyOf(offered));
        } catch (Exception e) {
            throw new AssertionError("unclaimed scope suggestions failed", e);
        }

        // The legacy integer slot and the three admin literals are untouched.
        for (String line : List.of(
                "milestonelog firsts 2",
                "milestonelog firsts top",
                "milestonelog firsts top 2",
                "milestonelog firsts unclaimed",
                "milestonelog firsts unclaimed 3",
                "milestonelog firsts unclaimed scope presets",
                "milestonelog firsts unclaimed scope all 2")) {
            ParseResults<CommandSourceStack> parse = dispatcher.parse(line, null);
            assertFalse(parse.getReader().canRead(), "must fully parse: " + line);
            assertTrue(parse.getExceptions().isEmpty(), line + ": " + parse.getExceptions());
            assertNotNull(parse.getContext().getCommand(), line);
        }
        for (String name : List.of("reset", "resettag", "resetall")) {
            assertNotNull(firsts.getChild(name), "firsts " + name + " must remain registered");
            assertFalse(regularFirsts.getChild(name).canUse(null), "firsts " + name + " must stay admin-gated");
        }
        CommandNode<CommandSourceStack> preset = rootChild(dispatcher, "preset");
        assertNotNull(preset.getChild("show"), "preset subtree must be unchanged");
        // F-150-4: "preset 2" keeps its legacy <name> slot (no new page argument on preset).
        for (String line : List.of("milestonelog preset 2", "milestonelog preset list")) {
            ParseResults<CommandSourceStack> parse = dispatcher.parse(line, null);
            assertFalse(parse.getReader().canRead(), "must fully parse: " + line);
            assertNotNull(parse.getContext().getCommand(), line);
        }
        for (String line : List.of("/milestonelog firsts top 2",
                "/milestonelog firsts unclaimed scope all 2")) {
            ParseResults<CommandSourceStack> parse = dispatcher.parse(line.substring(1), null);
            assertFalse(parse.getReader().canRead(), line);
            assertNotNull(parse.getContext().getCommand(), line);
        }
    }

    @Test
    void firstsCloseIsLookupGatedWithoutInheritingTheResetRequirement() {
        CommandDispatcher<CommandSourceStack> dispatcher = buildDispatcher(src -> true);
        CommandNode<CommandSourceStack> firsts = rootChild(dispatcher, "firsts");
        CommandNode<CommandSourceStack> close = firsts.getChild("close");
        assertNotNull(close, "firsts close must be registered (F-150-10)");
        assertNotNull(close.getCommand(), "bare firsts close must execute");
        CommandNode<CommandSourceStack> page = close.getChild("page");
        assertNotNull(page, "firsts close [<page>] must be registered");
        assertEquals(IntegerArgumentType.class,
                ((ArgumentCommandNode<CommandSourceStack, ?>) page).getType().getClass());
        assertTrue(close.canUse(null),
                "firsts close must not inherit firstsResetRequirement");
        assertNull(firsts.getChild("close").getChild("reset"), "close must not expose admin resets");

        for (String line : List.of("milestonelog firsts", "milestonelog firsts 2",
                "milestonelog firsts top", "milestonelog firsts top 2",
                "milestonelog firsts close", "milestonelog firsts close 2",
                com.pengin0503.itemmilestonelog.Pagination.pageCommand(
                        com.pengin0503.itemmilestonelog.Pagination.firstsCloseCommandPrefix(), 2)
                        .substring(1))) {
            ParseResults<CommandSourceStack> parse = dispatcher.parse(line, null);
            assertFalse(parse.getReader().canRead(), "must fully parse: " + line);
            assertTrue(parse.getExceptions().isEmpty(), line + ": " + parse.getExceptions());
            assertNotNull(parse.getContext().getCommand(), line);
        }
        assertEquals(IntegerArgumentType.class,
                ((ArgumentCommandNode<CommandSourceStack, ?>) firsts.getChild("page")).getType().getClass(),
                "firsts 2 must stay the legacy page argument");

        // The public world-first help entry stays public; only the close branch needs the lookup gate.
        assertEquals(CommandHelp.Gate.NONE, entryByName("firsts").gate());
        assertFalse(MilestoneCommands.canUseRankings(false, false),
                "allowLookup=false without privilege must close the runner-up gate");
        assertTrue(MilestoneCommands.canUseRankings(true, false));
        assertTrue(MilestoneCommands.canUseRankings(false, true),
                "viewhidden privilege must keep the gate open");
    }

    @Test
    void statsItemAndStatstagAreAdditiveBranchesWithLookupGate() {
        CommandDispatcher<CommandSourceStack> dispatcher = buildDispatcher(src -> true);
        CommandNode<CommandSourceStack> stats = rootChild(dispatcher, "stats");
        assertNotNull(stats.getCommand(), "bare stats must still execute server statistics");
        CommandNode<CommandSourceStack> statsPage = stats.getChild("page");
        assertNotNull(statsPage, "PR-2 adds an opt-in stats page branch (F-150-29)");
        assertNull(statsPage.getCommand(), "bare stats page must not change stats output");
        CommandNode<CommandSourceStack> item = stats.getChild("item");
        assertNotNull(item, "stats <item> must be registered (F-150-7)");
        assertTrue(item instanceof ArgumentCommandNode<CommandSourceStack, ?>);
        assertNotNull(item.getCommand(), "stats <item> must execute");
        CommandNode<CommandSourceStack> count = item.getChild("count");
        assertNotNull(count, "stats <item> [<count>] must be registered");
        assertNotNull(count.getCommand());

        CommandNode<CommandSourceStack> statstag = rootChild(dispatcher, "statstag");
        assertNotNull(statstag, "statstag must be registered (F-150-7)");
        assertNull(statstag.getCommand(), "bare statstag must not execute");
        CommandNode<CommandSourceStack> tag = statstag.getChild("tag");
        assertNotNull(tag, "statstag must take <tag>");
        assertTrue(tag instanceof ArgumentCommandNode<CommandSourceStack, ?>);
        assertNotNull(tag.getCommand(), "statstag <tag> must execute");
        assertNotNull(tag.getChild("count"), "statstag <tag> [<count>] must be registered");

        for (String line : List.of("milestonelog stats", "milestonelog stats minecraft:diamond",
                "milestonelog stats minecraft:diamond 3", "milestonelog statstag c:ingots/iron",
                "milestonelog statstag c:ingots/iron 3")) {
            ParseResults<CommandSourceStack> parse = dispatcher.parse(line, null);
            assertFalse(parse.getReader().canRead(), "must fully parse: " + line);
            assertTrue(parse.getExceptions().isEmpty(), line + ": " + parse.getExceptions());
            assertNotNull(parse.getContext().getCommand(), line);
        }
        CommandHelp.Entry entry = entryByName("statstag");
        assertFalse(entry.playerOnly(), "statstag must remain console-usable like stats");
        assertEquals(CommandHelp.Gate.LOOKUP, entry.gate());
        assertTrue(MilestoneCommands.helpUsages(dispatcher, null, entry).stream()
                .anyMatch(u -> u.contains("<tag>")));
        CommandHelp.Context noLookup = new CommandHelp.Context(true, false, true, true, false, true);
        CommandHelp.Context privileged = new CommandHelp.Context(true, true, true, true, false, true);
        assertNull(CommandHelp.findVisible("statstag", noLookup, e -> true));
        assertNotNull(CommandHelp.findVisible("statstag", privileged, e -> true));
    }

    @Test
    void gapsBranchesAreOptInAndLegacyTopFormsStillParse() {
        CommandDispatcher<CommandSourceStack> dispatcher = buildDispatcher(src -> true);
        CommandNode<CommandSourceStack> top = rootChild(dispatcher, "top");
        CommandNode<CommandSourceStack> item = top.getChild("item");
        assertNotNull(item, "top <item> must keep its legacy slot");
        assertNull(top.getChild("gaps"), "there is no bare top gaps branch");
        CommandNode<CommandSourceStack> itemGaps = item.getChild("gaps");
        assertNotNull(itemGaps, "top <item> gaps must be registered (F-150-8)");
        assertNotNull(itemGaps.getCommand());
        CommandNode<CommandSourceStack> count = item.getChild("count");
        assertNotNull(count, "top <item> [<count>] must keep its legacy slot");
        CommandNode<CommandSourceStack> countGaps = count.getChild("gaps");
        assertNotNull(countGaps, "top <item> [<count>] gaps must be registered");
        assertNotNull(countGaps.getCommand());

        CommandNode<CommandSourceStack> toptag = rootChild(dispatcher, "toptag");
        CommandNode<CommandSourceStack> tag = toptag.getChild("tag");
        assertNotNull(tag);
        assertNull(toptag.getChild("gaps"), "there is no bare toptag gaps branch");
        assertNotNull(tag.getChild("gaps"), "toptag <tag> gaps must be registered");
        CommandNode<CommandSourceStack> tagCount = tag.getChild("count");
        assertNotNull(tagCount);
        assertNotNull(tagCount.getChild("gaps"), "toptag <tag> [<count>] gaps must be registered");

        for (String line : List.of("milestonelog top", "milestonelog top minecraft:diamond",
                "milestonelog top minecraft:diamond 3", "milestonelog top minecraft:diamond gaps",
                "milestonelog top minecraft:diamond 3 gaps", "milestonelog toptag c:ingots/iron",
                "milestonelog toptag c:ingots/iron 3", "milestonelog toptag c:ingots/iron gaps",
                "milestonelog toptag c:ingots/iron 3 gaps")) {
            ParseResults<CommandSourceStack> parse = dispatcher.parse(line, null);
            assertFalse(parse.getReader().canRead(), "must fully parse: " + line);
            assertTrue(parse.getExceptions().isEmpty(), line + ": " + parse.getExceptions());
            assertNotNull(parse.getContext().getCommand(), line);
        }
        // The extra literal does change getSmartUsage text; no legacy usage string is pinned.
        assertTrue(MilestoneCommands.helpUsages(dispatcher, null, entryByName("top")).stream()
                .anyMatch(u -> u.contains("gaps")));
        assertTrue(MilestoneCommands.helpUsages(dispatcher, null, entryByName("toptag")).stream()
                .anyMatch(u -> u.contains("gaps")));
    }

    @Test
    void whohasAndWhohastagAreConsoleUsableLookupGatedCommands() {
        CommandDispatcher<CommandSourceStack> dispatcher = buildDispatcher(src -> true);
        CommandNode<CommandSourceStack> whohas = rootChild(dispatcher, "whohas");
        assertNotNull(whohas, "whohas must be registered (F-150-9)");
        assertNull(whohas.getCommand(), "bare whohas must not execute");
        CommandNode<CommandSourceStack> item = whohas.getChild("item");
        assertNotNull(item);
        assertNotNull(item.getCommand(), "whohas <item> must execute");
        CommandNode<CommandSourceStack> count = item.getChild("count");
        assertNotNull(count);
        assertNotNull(count.getCommand());
        assertNotNull(count.getChild("page"), "whohas <item> [<count>] [<page>] must be registered");

        CommandNode<CommandSourceStack> whohastag = rootChild(dispatcher, "whohastag");
        assertNotNull(whohastag, "whohastag must be registered (F-150-9)");
        CommandNode<CommandSourceStack> tag = whohastag.getChild("tag");
        assertNotNull(tag);
        assertNotNull(tag.getChild("count"));
        assertNotNull(tag.getChild("count").getChild("page"));

        for (String line : List.of("milestonelog whohas minecraft:diamond",
                "milestonelog whohas minecraft:diamond 3",
                "milestonelog whohas minecraft:diamond 3 2",
                "milestonelog whohastag c:ingots/iron",
                "milestonelog whohastag c:ingots/iron 3",
                "milestonelog whohastag c:ingots/iron 3 2",
                com.pengin0503.itemmilestonelog.Pagination.pageCommand(
                        com.pengin0503.itemmilestonelog.Pagination.whohasCommandPrefix("minecraft:diamond", 3), 2)
                        .substring(1),
                com.pengin0503.itemmilestonelog.Pagination.pageCommand(
                        com.pengin0503.itemmilestonelog.Pagination.whohastagCommandPrefix("#c:ingots/iron", 3), 2)
                        .substring(1))) {
            ParseResults<CommandSourceStack> parse = dispatcher.parse(line, null);
            assertFalse(parse.getReader().canRead(), "must fully parse: " + line);
            assertTrue(parse.getExceptions().isEmpty(), line + ": " + parse.getExceptions());
            assertNotNull(parse.getContext().getCommand(), line);
        }
        for (String name : List.of("whohas", "whohastag")) {
            CommandHelp.Entry entry = entryByName(name);
            assertFalse(entry.playerOnly(), name + " must remain console-usable like top");
            assertEquals(CommandHelp.Gate.LOOKUP, entry.gate());
            assertTrue(MilestoneCommands.helpUsages(dispatcher, null, entry).stream()
                    .anyMatch(u -> u.contains("<item>") || u.contains("<tag>")));
        }
        CommandHelp.Context noLookup = new CommandHelp.Context(true, false, true, true, false, true);
        CommandHelp.Context privileged = new CommandHelp.Context(true, true, true, true, false, true);
        assertNull(CommandHelp.findVisible("whohas", noLookup, e -> true));
        assertNull(CommandHelp.findVisible("whohastag", noLookup, e -> true));
        assertNotNull(CommandHelp.findVisible("whohas", privileged, e -> true));
        assertNotNull(CommandHelp.findVisible("whohastag", privileged, e -> true));
    }

    private static CommandNode<CommandSourceStack> rootChild(
            CommandDispatcher<CommandSourceStack> dispatcher, String name) {
        return dispatcher.getRoot().getChild("milestonelog").getChild(name);
    }

    @Test
    void unclaimedPresetGateFailsForPresetsAndAllButSkipsTargets() {
        assertEquals("fail", MilestoneCommands.unclaimedPresetMode(
                RankingStatsModel.UnclaimedScope.PRESETS, false));
        assertEquals("fail", MilestoneCommands.unclaimedPresetMode(
                RankingStatsModel.UnclaimedScope.ALL, false));
        assertEquals("skip", MilestoneCommands.unclaimedPresetMode(
                RankingStatsModel.UnclaimedScope.TARGETS, false));
        assertEquals("include", MilestoneCommands.unclaimedPresetMode(
                RankingStatsModel.UnclaimedScope.PRESETS, true));
        assertEquals("include", MilestoneCommands.unclaimedPresetMode(
                RankingStatsModel.UnclaimedScope.ALL, true));
        assertEquals("skip", MilestoneCommands.unclaimedPresetMode(
                RankingStatsModel.UnclaimedScope.TARGETS, true));
    }

    @Test
    void everyArgumentTypeInTheTreeIsVanillaDecodable() {
        CommandDispatcher<CommandSourceStack> op = buildDispatcher(src -> true);
        List<String> custom = new ArrayList<>();
        collectCustomArgumentTypes(op.getRoot(), custom);
        assertEquals(List.of(), custom,
                "Every synchronized argument type must be a vanilla Brigadier/Minecraft class (#146)");
    }

    private static void collectCustomArgumentTypes(CommandNode<CommandSourceStack> node, List<String> custom) {
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            if (child instanceof ArgumentCommandNode<CommandSourceStack, ?> argumentNode) {
                String name = argumentNode.getType().getClass().getName();
                if (!name.startsWith("com.mojang.brigadier.arguments.")
                        && !name.startsWith("net.minecraft.commands.arguments.")) {
                    custom.add(name);
                }
            }
            collectCustomArgumentTypes(child, custom);
        }
    }

    @Test
    void discordTestRegistersOptionalWorldFirstSubcommandWithAdminRequirement() {
        CommandDispatcher<CommandSourceStack> regular = buildDispatcher(src -> false);
        CommandDispatcher<CommandSourceStack> op = buildDispatcher(src -> true);
        CommandNode<CommandSourceStack> root = op.getRoot().getChild("milestonelog");
        assertNotNull(root);
        CommandNode<CommandSourceStack> discordTest = root.getChild("discordtest");
        assertNotNull(discordTest, "discordtest must be registered");
        assertNotNull(discordTest.getCommand(), "discordtest without argument must test main webhook");
        CommandNode<CommandSourceStack> worldFirst = discordTest.getChild("worldfirst");
        assertNotNull(worldFirst, "discordtest worldfirst must be registered (F49 1.2.0)");
        assertNotNull(worldFirst.getCommand(), "discordtest worldfirst must execute");
        assertEquals(List.of("/milestonelog discordtest [worldfirst]"),
                MilestoneCommands.helpUsages(op, null, entryByName("discordtest")));
        assertFalse(MilestoneCommands.canUseHelpEntry(regular, null, entryByName("discordtest")));
        assertTrue(MilestoneCommands.canUseHelpEntry(op, null, entryByName("discordtest")));

        ParseResults<CommandSourceStack> opMain = op.parse("milestonelog discordtest", null);
        assertFalse(opMain.getReader().canRead(), "OP should fully parse /milestonelog discordtest");
        assertTrue(opMain.getExceptions().isEmpty(), "OP parse of /milestonelog discordtest should have no errors");
        assertNotNull(opMain.getContext().getCommand(), "OP /milestonelog discordtest must resolve a command");

        ParseResults<CommandSourceStack> opWorldFirst = op.parse("milestonelog discordtest worldfirst", null);
        assertFalse(opWorldFirst.getReader().canRead(), "OP should fully parse /milestonelog discordtest worldfirst");
        assertTrue(opWorldFirst.getExceptions().isEmpty(), "OP parse of /milestonelog discordtest worldfirst should have no errors");
        assertNotNull(opWorldFirst.getContext().getCommand(), "OP /milestonelog discordtest worldfirst must resolve a command");
        assertEquals(3, opWorldFirst.getContext().getNodes().size());
        assertEquals("worldfirst", opWorldFirst.getContext().getNodes().get(2).getNode().getName());

        ParseResults<CommandSourceStack> regularWorldFirst = regular.parse("milestonelog discordtest worldfirst", null);
        assertTrue(regularWorldFirst.getReader().canRead(),
                "Non-admin must not match /milestonelog discordtest worldfirst");
        assertEquals(1, regularWorldFirst.getContext().getNodes().size(),
                "Non-admin parse must stop at root milestonelog");
    }

    @Test
    void pr2AnalyticsCommandsAreRegisteredAndParsed() {
        CommandDispatcher<CommandSourceStack> op = buildDispatcher(src -> true);
        CommandNode<CommandSourceStack> root = op.getRoot().getChild("milestonelog");
        assertNotNull(root, "/milestonelog root must be registered");

        // PR-2 top-level entries: gate and source flags (F-150-11/12/18/20/24/39).
        for (String name : List.of("compare", "timeline", "timelinetag", "modstats",
                "presetmatrix", "presetstats")) {
            assertNotNull(root.getChild(name), name + " must be registered (PR-2)");
            CommandHelp.Entry entry = entryByName(name);
            assertEquals(CommandHelp.Gate.LOOKUP, entry.gate(), name + " must be lookup-gated");
            assertFalse(entry.playerOnly(), name + " must stay console-usable");
        }
        assertNotNull(root.getChild("session"), "session must be registered (F-150-18)");
        CommandHelp.Entry sessionEntry = entryByName("session");
        assertEquals(CommandHelp.Gate.NONE, sessionEntry.gate(), "session must not be gated");
        assertTrue(sessionEntry.playerOnly(), "session must be player-only (F-150-18)");

        // F-150-11/33: compare <player> [missing|route|rival] [<page>].
        CommandNode<CommandSourceStack> compare = rootChild(op, "compare");
        assertNull(compare.getCommand(), "bare compare must not execute");
        CommandNode<CommandSourceStack> comparePlayer = compare.getChild("player");
        assertNotNull(comparePlayer, "compare must take <player>");
        assertTrue(comparePlayer instanceof ArgumentCommandNode<?, ?>);
        assertEquals(StringArgumentType.class,
                ((ArgumentCommandNode<CommandSourceStack, ?>) comparePlayer).getType().getClass(),
                "compare <player> must be a word argument");
        assertNotNull(comparePlayer.getCommand(), "compare <player> must execute");
        for (String branch : List.of("missing", "route", "rival")) {
            assertNotNull(comparePlayer.getChild(branch), "compare <player> " + branch + " must exist");
        }
        CommandNode<CommandSourceStack> comparePage = comparePlayer.getChild("page");
        assertNotNull(comparePage, "compare <player> [<page>] must be registered");
        assertEquals(IntegerArgumentType.class,
                ((ArgumentCommandNode<CommandSourceStack, ?>) comparePage).getType().getClass());
        assertNotNull(comparePage.getCommand(), "compare <player> <page> must execute");

        // F-150-12: timeline <item> [<count>] [<page>] and timelinetag <tag> [<count>] [<page>].
        for (String[] command : List.of(
                new String[] {"timeline", "item"}, new String[] {"timelinetag", "tag"})) {
            CommandNode<CommandSourceStack> target = rootChild(op, command[0]).getChild(command[1]);
            assertNotNull(target, command[0] + " must take <" + command[1] + ">");
            for (String slot : List.of("count", "page")) {
                CommandNode<CommandSourceStack> child = target.getChild(slot);
                assertNotNull(child, command[0] + " <" + command[1] + "> <" + slot + "> must exist");
                assertTrue(child instanceof ArgumentCommandNode<?, ?>);
                assertEquals(IntegerArgumentType.class,
                        ((ArgumentCommandNode<CommandSourceStack, ?>) child).getType().getClass());
                assertNotNull(child.getCommand(),
                        command[0] + " <" + command[1] + "> <" + slot + "> must execute");
            }
        }
        CommandNode<CommandSourceStack> timelineItem = rootChild(op, "timeline").getChild("item");
        assertNotNull(timelineItem.getChild("count"), "timeline <item> must take <count>");
        assertNotNull(timelineItem.getChild("page"), "timeline <item> must take <page>");
        assertNotNull(timelineItem.getChild("count").getChild("page"),
                "timeline <item> <count> <page> must be registered");

        // F-150-18: session [hours <window>] [page <page>] and session summary.
        CommandNode<CommandSourceStack> session = rootChild(op, "session");
        for (String child : List.of("summary", "hours", "page")) {
            assertNotNull(session.getChild(child), "session " + child + " must be registered");
        }
        CommandNode<CommandSourceStack> hoursArg = session.getChild("hours").getChild("hours");
        assertNotNull(hoursArg, "session hours must take <hours>");
        assertEquals(StringArgumentType.class,
                ((ArgumentCommandNode<CommandSourceStack, ?>) hoursArg).getType().getClass());
        assertNotNull(hoursArg.getCommand(), "session hours <hours> must execute");
        CommandNode<CommandSourceStack> hoursPageLiteral = hoursArg.getChild("page");
        assertNotNull(hoursPageLiteral, "session hours <hours> page must be registered");
        CommandNode<CommandSourceStack> hoursPage = hoursPageLiteral.getChild("page");
        assertNotNull(hoursPage, "session hours <hours> page <page> must be registered");
        assertEquals(IntegerArgumentType.class,
                ((ArgumentCommandNode<CommandSourceStack, ?>) hoursPage).getType().getClass());
        assertNotNull(hoursPage.getCommand(), "session hours <hours> page <page> must execute");
        CommandNode<CommandSourceStack> sessionPage = session.getChild("page").getChild("page");
        assertNotNull(sessionPage, "session page <page> must be registered");
        assertEquals(IntegerArgumentType.class,
                ((ArgumentCommandNode<CommandSourceStack, ?>) sessionPage).getType().getClass());
        assertNotNull(sessionPage.getCommand(), "session page <page> must execute");

        // F-150-24/39/20: paged top-level analytics plus presetstats predict <preset>.
        for (String name : List.of("modstats", "presetmatrix", "presetstats")) {
            CommandNode<CommandSourceStack> page = rootChild(op, name).getChild("page");
            assertNotNull(page, name + " [<page>] must be registered");
            assertEquals(IntegerArgumentType.class,
                    ((ArgumentCommandNode<CommandSourceStack, ?>) page).getType().getClass());
            assertNotNull(page.getCommand(), name + " <page> must execute");
        }
        CommandNode<CommandSourceStack> predict = rootChild(op, "presetstats").getChild("predict");
        assertNotNull(predict, "presetstats predict must be registered (F-150-39)");
        CommandNode<CommandSourceStack> predictPreset = predict.getChild("preset");
        assertNotNull(predictPreset, "presetstats predict must take <preset>");
        assertEquals(StringArgumentType.class,
                ((ArgumentCommandNode<CommandSourceStack, ?>) predictPreset).getType().getClass());

        // F-150-15/29: stats pace and the opt-in axis literals on top / stats / presettop.
        CommandNode<CommandSourceStack> stats = rootChild(op, "stats");
        CommandNode<CommandSourceStack> pace = stats.getChild("pace");
        assertNotNull(pace, "stats pace must be registered (F-150-15)");
        assertNotNull(pace.getCommand(), "bare stats pace must execute");
        CommandNode<CommandSourceStack> pacePage = pace.getChild("page");
        assertNotNull(pacePage, "stats pace [<page>] must be registered");
        assertEquals(IntegerArgumentType.class,
                ((ArgumentCommandNode<CommandSourceStack, ?>) pacePage).getType().getClass());
        assertNotNull(pacePage.getCommand(), "stats pace <page> must execute");
        CommandNode<CommandSourceStack> paceScopeLiteral = pace.getChild("scope");
        assertNotNull(paceScopeLiteral, "stats pace scope <scope> must be registered");
        CommandNode<CommandSourceStack> paceScope = paceScopeLiteral.getChild("scope");
        assertNotNull(paceScope, "stats pace scope must take <scope>");
        assertEquals(StringArgumentType.class,
                ((ArgumentCommandNode<CommandSourceStack, ?>) paceScope).getType().getClass());

        for (String name : List.of("top", "stats")) {
            CommandNode<CommandSourceStack> axisLiteral = rootChild(op, name).getChild("axis");
            assertNotNull(axisLiteral, name + " axis must be registered (F-150-29)");
            CommandNode<CommandSourceStack> axis = axisLiteral.getChild("axis");
            assertNotNull(axis, name + " axis must take <axis>");
            assertEquals(StringArgumentType.class,
                    ((ArgumentCommandNode<CommandSourceStack, ?>) axis).getType().getClass());
        }
        CommandNode<CommandSourceStack> preset = rootChild(op, "presettop").getChild("preset");
        assertNotNull(preset, "presettop must take <preset>");
        CommandNode<CommandSourceStack> presetAxisLiteral = preset.getChild("axis");
        assertNotNull(presetAxisLiteral, "presettop <preset> axis must be registered");
        CommandNode<CommandSourceStack> presetAxis = presetAxisLiteral.getChild("axis");
        assertNotNull(presetAxis, "presettop <preset> axis must take <axis>");
        assertEquals(StringArgumentType.class,
                ((ArgumentCommandNode<CommandSourceStack, ?>) presetAxis).getType().getClass());

        // Every documented PR-2 line must parse completely with an OP dispatcher.
        for (String line : List.of(
                "milestonelog compare SomePlayer",
                "milestonelog compare SomePlayer missing",
                "milestonelog compare SomePlayer route 2",
                "milestonelog compare SomePlayer rival 2",
                "milestonelog timeline minecraft:diamond",
                "milestonelog timeline minecraft:diamond 3 2",
                "milestonelog timelinetag c:ingots/iron",
                "milestonelog session",
                "milestonelog session hours 6",
                "milestonelog session hours 6 page 2",
                "milestonelog session summary",
                "milestonelog modstats",
                "milestonelog modstats 2",
                "milestonelog presetmatrix",
                "milestonelog presetmatrix 2",
                "milestonelog presetstats",
                "milestonelog presetstats predict vanilla_endgame",
                "milestonelog stats pace",
                "milestonelog stats pace scope presets",
                "milestonelog top axis online",
                "milestonelog top minecraft:diamond axis period days 7",
                "milestonelog stats axis team",
                "milestonelog presettop vanilla_endgame axis all")) {
            ParseResults<CommandSourceStack> parse = op.parse(line, null);
            assertFalse(parse.getReader().canRead(), "must fully parse: " + line);
            assertTrue(parse.getExceptions().isEmpty(), line + ": " + parse.getExceptions());
            assertNotNull(parse.getContext().getCommand(), line);
        }
    }

    @Test
    void everyRegisteredSubcommandAppearsInHelpTableAndViceVersa() {
        CommandDispatcher<CommandSourceStack> dispatcher = buildDispatcher(src -> true);
        CommandNode<CommandSourceStack> root = dispatcher.getRoot().getChild("milestonelog");
        assertNotNull(root, "/milestonelog root command must be registered");
        assertNotNull(root.getCommand(), "/milestonelog root command must execute summary");

        Set<String> registeredSubcommands = new TreeSet<>();
        for (CommandNode<CommandSourceStack> child : root.getChildren()) {
            registeredSubcommands.add(child.getName());
        }

        Set<String> tableSubcommands = new TreeSet<>();
        boolean hasSummary = false;
        for (CommandHelp.Entry entry : CommandHelp.ENTRIES) {
            if (entry.isRoot()) {
                hasSummary = true;
            } else {
                tableSubcommands.add(entry.name());
            }
        }

        assertTrue(hasSummary, "CommandHelp.ENTRIES must include the root 'summary' entry");
        assertEquals(registeredSubcommands, tableSubcommands,
                "CommandHelp.ENTRIES subcommands must exactly match registered /milestonelog subcommands");
    }

    @Test
    void everyHelpTableEntryHasEnglishAndJapaneseTextsAndNoOrphanHelpKeys() throws Exception {
        Map<String, String> en = readLang("en_us");
        Map<String, String> ja = readLang("ja_jp");

        for (String key : List.of(
                "message.itemmilestonelog.help_header",
                "message.itemmilestonelog.help_entry",
                "message.itemmilestonelog.help_usage",
                "message.itemmilestonelog.help_unknown")) {
            assertNotNull(en.get(key), "Missing en_us key: " + key);
            assertFalse(en.get(key).isBlank(), "Blank en_us key: " + key);
            assertNotNull(ja.get(key), "Missing ja_jp key: " + key);
            assertFalse(ja.get(key).isBlank(), "Blank ja_jp key: " + key);
        }

        Set<String> expectedHelpKeys = new TreeSet<>();
        for (CommandHelp.Entry entry : CommandHelp.ENTRIES) {
            String key = entry.descriptionKey();
            expectedHelpKeys.add(key);

            String enText = en.get(key);
            String jaText = ja.get(key);
            assertNotNull(enText, "Missing en_us help description for " + entry.name() + " (" + key + ")");
            assertFalse(enText.isBlank(), "Blank en_us help description for " + entry.name());
            assertNotNull(jaText, "Missing ja_jp help description for " + entry.name() + " (" + key + ")");
            assertFalse(jaText.isBlank(), "Blank ja_jp help description for " + entry.name());

            assertEquals(enText, ServerLang.trForLang("en_us", key).getString());
            assertEquals(jaText, ServerLang.trForLang("ja_jp", key).getString());
        }

        Set<String> actualHelpKeys = new TreeSet<>();
        for (String key : en.keySet()) {
            if (key.startsWith(CommandHelp.HELP_KEY_PREFIX)) {
                actualHelpKeys.add(key);
            }
        }
        // F-150-21/27/32 define auxiliary opt-in descriptions that are not top-level ENTRIES;
        // PR-2 adds the axis and compare_branches auxiliary opt-in descriptions.
        Set<String> auxiliaryHelpKeys = Set.of(
                "message.itemmilestonelog.help.axis",
                "message.itemmilestonelog.help.compare_branches",
                "message.itemmilestonelog.help.compact",
                "message.itemmilestonelog.help.info_preview",
                "message.itemmilestonelog.help.list_sort");
        actualHelpKeys.removeAll(auxiliaryHelpKeys);
        assertEquals(expectedHelpKeys, actualHelpKeys,
                "en_us.json help.* keys must match CommandHelp.ENTRIES plus PR-1 and PR-2 auxiliary keys");
    }

    @Test
    void brigadierCanUseAndSmartUsageRespectAdminRequirements() {
        CommandDispatcher<CommandSourceStack> regular = buildDispatcher(src -> false);
        CommandDispatcher<CommandSourceStack> op = buildDispatcher(src -> true);

        for (CommandHelp.Entry entry : CommandHelp.ENTRIES) {
            boolean expectedRegular = !ADMIN_ONLY.contains(entry.name());
            assertEquals(expectedRegular, MilestoneCommands.canUseHelpEntry(regular, null, entry),
                    "Regular canUse mismatch for " + entry.name());
            assertTrue(MilestoneCommands.canUseHelpEntry(op, null, entry),
                    "OP should be able to use " + entry.name());
        }

        // Root summary and leaf commands without children return their own command line
        assertEquals(List.of("/milestonelog"),
                MilestoneCommands.helpUsages(regular, null, entryByName("summary")));
        assertEquals(List.of("/milestonelog playtime"),
                MilestoneCommands.helpUsages(regular, null, entryByName("playtime")));

        // Parameterized commands return Brigadier smart usages
        assertEquals(List.of("/milestonelog add <item> [<count>]"),
                MilestoneCommands.helpUsages(regular, null, entryByName("add")));
        assertEquals(List.of("/milestonelog help [<subcommand>]"),
                MilestoneCommands.helpUsages(regular, null, entryByName("help")));

        // firsts hides admin reset/resetall branches from non-OP sources and shows them to OPs.
        // F-150-5/6 add public sibling branches, so the exact legacy one-line usage grows; the
        // admin reset subcommands themselves stay unchanged.
        List<String> regularFirstsUsages = MilestoneCommands.helpUsages(regular, null, entryByName("firsts"));
        assertTrue(regularFirstsUsages.stream().anyMatch(u -> u.contains("<page>")),
                () -> "Expected page usage: " + regularFirstsUsages);
        assertTrue(regularFirstsUsages.stream().anyMatch(u -> u.contains("top")),
                () -> "Expected firsts top usage: " + regularFirstsUsages);
        assertTrue(regularFirstsUsages.stream().anyMatch(u -> u.contains("unclaimed")),
                () -> "Expected firsts unclaimed usage: " + regularFirstsUsages);
        assertTrue(regularFirstsUsages.stream().anyMatch(u -> u.contains("close")),
                () -> "Expected firsts close usage with the gate open: " + regularFirstsUsages);
        assertFalse(regularFirstsUsages.stream().anyMatch(u -> u.contains("reset")),
                () -> "Resets must stay hidden: " + regularFirstsUsages);

        List<String> opFirstsUsages = MilestoneCommands.helpUsages(op, null, entryByName("firsts"));
        assertTrue(opFirstsUsages.stream().anyMatch(u -> u.contains("<page>")), () -> "Expected page usage: " + opFirstsUsages);
        assertTrue(opFirstsUsages.stream().anyMatch(u -> u.contains("reset")), () -> "Expected reset usage: " + opFirstsUsages);
        assertTrue(opFirstsUsages.stream().anyMatch(u -> u.contains("resetall")), () -> "Expected resetall usage: " + opFirstsUsages);

        // admin shows players and <player> branches to OPs
        List<String> opAdminUsages = MilestoneCommands.helpUsages(op, null, entryByName("admin"));
        assertTrue(opAdminUsages.stream().anyMatch(u -> u.contains("players")), () -> "Expected players usage: " + opAdminUsages);
        assertTrue(opAdminUsages.stream().anyMatch(u -> u.contains("<player>")), () -> "Expected <player> usage: " + opAdminUsages);
    }

    @Test
    void clickableHelpCommandAttachesSuggestCommandClickEvent() {
        for (CommandHelp.Entry entry : CommandHelp.ENTRIES) {
            MutableComponent comp = MilestoneCommands.clickableHelpCommand(entry);
            assertEquals(entry.displayCommand(), comp.getString());
            assertEquals(entry.suggestCommand(),
                    MilestoneCommands.suggestCommandValue(comp.getStyle().getClickEvent()),
                    "ClickEvent suggest_command mismatch for " + entry.name());
        }
    }

    @Test
    void presetShowAndDiffAreRegisteredUnderPreset() {
        CommandDispatcher<CommandSourceStack> dispatcher = buildDispatcher(src -> true);
        CommandNode<CommandSourceStack> preset = dispatcher.getRoot().getChild("milestonelog").getChild("preset");
        assertNotNull(preset);

        CommandNode<CommandSourceStack> show = preset.getChild("show");
        assertNotNull(show, "preset show must be registered");
        assertNull(show.getCommand(), "bare preset show must not execute");
        CommandNode<CommandSourceStack> showName = show.getChild("name");
        assertNotNull(showName, "preset show must take <name>");
        assertNotNull(showName.getCommand(), "preset show <name> must execute");

        CommandNode<CommandSourceStack> diff = preset.getChild("diff");
        assertNotNull(diff, "preset diff must be registered");
        CommandNode<CommandSourceStack> nameA = diff.getChild("nameA");
        assertNotNull(nameA, "preset diff must take <nameA>");
        assertNull(nameA.getCommand(), "preset diff <nameA> alone must not execute");
        CommandNode<CommandSourceStack> nameB = nameA.getChild("nameB");
        assertNotNull(nameB, "preset diff must take <nameB>");
        assertNotNull(nameB.getCommand(), "preset diff <nameA> <nameB> must execute");
    }

    @Test
    void presetShareAndImportcodeAreRegisteredUnderPreset() {
        CommandDispatcher<CommandSourceStack> dispatcher = buildDispatcher(src -> true);
        CommandNode<CommandSourceStack> preset = dispatcher.getRoot().getChild("milestonelog").getChild("preset");
        assertNotNull(preset);

        CommandNode<CommandSourceStack> share = preset.getChild("share");
        assertNotNull(share, "preset share must be registered");
        assertNull(share.getCommand(), "bare preset share must not execute");
        CommandNode<CommandSourceStack> shareName = share.getChild("name");
        assertNotNull(shareName, "preset share must take <name>");
        assertNotNull(shareName.getCommand(), "preset share <name> must execute");

        CommandNode<CommandSourceStack> importcode = preset.getChild("importcode");
        assertNotNull(importcode, "preset importcode must be registered");
        assertNull(importcode.getCommand(), "bare preset importcode must not execute");
        CommandNode<CommandSourceStack> code = importcode.getChild("code");
        assertNotNull(code, "preset importcode must take <code>");
        assertNotNull(code.getCommand(), "preset importcode <code> must execute");

        // #97: StringArgumentType.word() stopped at the ':' in IML1:... so the command could
        // never reach its handler. The greedy argument must consume the whole share code,
        // including one longer than a chat client's 256-character input limit (console / RCON).
        ParseResults<CommandSourceStack> shortCode =
                dispatcher.parse("milestonelog preset importcode IML1:abc", null);
        assertFalse(shortCode.getReader().canRead(), "importcode must consume the whole IML1: code");
        assertTrue(shortCode.getExceptions().isEmpty());
        assertNotNull(shortCode.getContext().getCommand());

        String longCode = "IML1:" + "A".repeat(4_000);
        ParseResults<CommandSourceStack> longParse =
                dispatcher.parse("milestonelog preset importcode " + longCode, null);
        assertFalse(longParse.getReader().canRead());
        assertTrue(longParse.getExceptions().isEmpty());
        assertNotNull(longParse.getContext().getCommand());
        assertEquals(longCode, parsedArgument(longParse, "code"));
    }

    /** Parsed argument value from a {@link ParseResults}; {@code getString} needs an executed context. */
    private static String parsedArgument(ParseResults<CommandSourceStack> parse, String name) {
        return (String) parse.getContext().getArguments().get(name).getResult();
    }

    @Test
    void generatedFooterLinksParseWithTheRegisteredGrammar() {
        CommandDispatcher<CommandSourceStack> dispatcher = buildDispatcher(src -> true);
        String rawQuery = "a\\b\"c";
        MilestoneCommands.FilterCriteria criteria =
                new MilestoneCommands.FilterCriteria(List.of(rawQuery), null, rawQuery, null);
        List<String> links = List.of(
                Pagination.pageCommand(Pagination.trendsCommandPrefix(7), 2),
                Pagination.pageCommand(Pagination.searchCommandPrefix(rawQuery, "scope all"), 2),
                Pagination.pageCommand(Pagination.listCommandPrefix(criteria.listSuffix()), 2),
                Pagination.pageCommand(Pagination.targetsCommandPrefix(
                        Pagination.TargetFilter.ALL, criteria.targetSuffix()), 2));
        for (String line : links) {
            ParseResults<CommandSourceStack> parse = dispatcher.parse(line.substring(1), null);
            assertFalse(parse.getReader().canRead(), "must fully parse: " + line);
            assertTrue(parse.getExceptions().isEmpty(), line + ": " + parse.getExceptions());
            assertNotNull(parse.getContext().getCommand(), line);
        }
        ParseResults<CommandSourceStack> trends = dispatcher.parse(links.get(0).substring(1), null);
        assertEquals("7", parsedArgument(trends, "days"));
        assertEquals(2, (Object) trends.getContext().getArguments().get("page").getResult());
        for (int i = 1; i < links.size(); i++) {
            ParseResults<CommandSourceStack> parse = dispatcher.parse(links.get(i).substring(1), null);
            assertEquals(rawQuery, parsedArgument(parse, "query"), links.get(i));
        }
        assertEquals("all", parsedArgument(dispatcher.parse(links.get(1).substring(1), null), "scope"));
    }

    @Test
    void readOnlyHandlersLeaveADataLessPlayerUntouched() {
        MilestoneData data = new MilestoneData();
        UUID playerId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        assertTrue(MilestoneCommands.settingsLinesFor(data, playerId).stream()
                .noneMatch(CommandAnalysisModel.SettingLine::enabled));
        for (CommandAnalysisModel.RecapKind kind : List.of(CommandAnalysisModel.RecapKind.RETURN,
                CommandAnalysisModel.RecapKind.DAILY)) {
            assertTrue(MilestoneCommands.recapResultFor(data, playerId, kind,
                    System.currentTimeMillis()).entries().isEmpty(), kind.name());
        }
        assertTrue(data.allPlayers().isEmpty(), "read-only handlers must not insert PlayerData");
        assertFalse(data.isDirty(), "read-only handlers must not dirty the data set");
    }

    @Test
    void settingsToggleNoOpLeavesADataLessPlayerUntouched() {
        // R2-SF-1: an absent PlayerData has all-off defaults, so every "off" toggle is a no-op and
        // must not insert or dirty a PlayerData. Only "on" needs a write (done by the legacy handler).
        MilestoneData data = new MilestoneData();
        UUID playerId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        for (CommandAnalysisModel.Toggle toggle : CommandAnalysisModel.Toggle.values()) {
            assertFalse(MilestoneCommands.settingsToggleNeedsWrite(data, playerId, toggle, false),
                    toggle + " off must be a no-op for a data-less player");
            assertTrue(MilestoneCommands.settingsToggleNeedsWrite(data, playerId, toggle, true),
                    toggle + " on must reach the legacy write handler");
        }
        assertTrue(data.allPlayers().isEmpty(), "the read-only decision must not insert PlayerData");
        assertFalse(data.isDirty(), "the read-only decision must not dirty the data set");
    }

    @Test
    void previewStatesCoverEveryRelatedKeyIncludingUntrackedOnes() throws Exception {
        List<CommandAnalysisModel.TargetRef> refs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            refs.add(new CommandAnalysisModel.TargetRef("c:tag_" + i, 1));
        }
        List<ItemInfoLogic.TagEntry> displayed = List.of(new ItemInfoLogic.TagEntry(
                com.pengin0503.itemmilestonelog.api.TargetKey.of("c:tag_0"),
                new ItemInfoLogic.OwnStatus(ItemInfoLogic.OwnStatusKind.TARGET, null, false, 2, 10)));
        Map<CommandAnalysisModel.TargetRef, CommandAnalysisModel.PreviewState> states =
                MilestoneCommands.infoPreviewStates(refs, displayed);
        assertEquals(refs.size(), states.size());
        assertEquals("target", states.get(refs.get(0)).statusKey());
        assertEquals("none", states.get(refs.get(1)).statusKey());
        CommandAnalysisModel.PreviewResult result = CommandAnalysisModel.infoPreview(refs, states);
        assertEquals(10, result.entries().size());
        assertEquals(2, result.moreCount());
        assertEquals("none", result.entries().get(1).statusKey());
        // R2-MN-2(b): the duplicate-section pin is now a rendered-output assertion through
        // MilestoneCommands.sendInfoRelatedTags in the version-local MilestoneCommandRenderTest.
    }
}
