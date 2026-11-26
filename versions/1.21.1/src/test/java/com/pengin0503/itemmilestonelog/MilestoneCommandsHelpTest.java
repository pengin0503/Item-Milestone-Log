package com.pengin0503.itemmilestonelog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.MutableComponent;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
        assertEquals(List.of("/milestonelog info <item> [<count>]", "/milestonelog info hand"),
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
    void renameTagIsRegisteredAtRootWithAdminRequirementAndOptionalConfirm() {
        CommandDispatcher<CommandSourceStack> op = buildDispatcher(src -> true);
        CommandNode<CommandSourceStack> root = op.getRoot().getChild("milestonelog");
        assertNotNull(root);
        CommandNode<CommandSourceStack> rename = root.getChild("renametag");
        assertNotNull(rename, "renametag must be registered at root");
        assertNotNull(rename.getChild("oldTag"), "renametag must take <oldTag>");
        CommandNode<CommandSourceStack> newTag = rename.getChild("oldTag").getChild("newTag");
        assertNotNull(newTag, "renametag must take <newTag>");
        assertNotNull(newTag.getCommand(), "renametag <oldTag> <newTag> must execute preview");
        CommandNode<CommandSourceStack> confirm = newTag.getChild("confirm");
        assertNotNull(confirm, "renametag must take optional confirm literal");
        assertNotNull(confirm.getCommand(), "renametag <oldTag> <newTag> confirm must execute apply");
        assertEquals(List.of("/milestonelog renametag <oldTag> <newTag> [confirm]"),
                MilestoneCommands.helpUsages(op, null, entryByName("renametag")));

        CommandDispatcher<CommandSourceStack> regular = buildDispatcher(src -> false);
        ParseResults<CommandSourceStack> parse = regular.parse("milestonelog renametag #old:a #new:a", null);
        assertTrue(parse.getReader().canRead(), "Non-admin must not match /milestonelog renametag");

        // #129: for an admin, the unquoted #namespace:path form (what suggestions offer) must
        // parse completely; only the permission predicate should reject a non-admin.
        ParseResults<CommandSourceStack> adminPreview = op.parse("milestonelog renametag #old:a #new:a", null);
        assertFalse(adminPreview.getReader().canRead(), "Admin must fully parse an unquoted tag ID");
        assertTrue(adminPreview.getExceptions().isEmpty());
        assertNotNull(adminPreview.getContext().getCommand());

        ParseResults<CommandSourceStack> adminConfirm = op.parse(
                "milestonelog renametag #c:ingots/iron #c:iron_ingots confirm", null);
        assertFalse(adminConfirm.getReader().canRead());
        assertTrue(adminConfirm.getExceptions().isEmpty());
        assertNotNull(adminConfirm.getContext().getCommand());
        assertEquals("#c:ingots/iron", parsedArgument(adminConfirm, "oldTag"));
        assertEquals("#c:iron_ingots", parsedArgument(adminConfirm, "newTag"));
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
        assertEquals(expectedHelpKeys, actualHelpKeys,
                "en_us.json help.* keys must exactly match CommandHelp.ENTRIES");
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

        // firsts hides admin reset/resetall branches from non-OP sources and shows them to OPs
        List<String> regularFirstsUsages = MilestoneCommands.helpUsages(regular, null, entryByName("firsts"));
        assertEquals(List.of("/milestonelog firsts [<page>]"), regularFirstsUsages);

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
}
