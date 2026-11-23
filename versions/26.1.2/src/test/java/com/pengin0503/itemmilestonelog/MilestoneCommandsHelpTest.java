package com.pengin0503.itemmilestonelog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies F29 {@code /milestonelog help} invariants against the real registered Brigadier command
 * tree and both {@code en_us.json} and {@code ja_jp.json}.
 */
class MilestoneCommandsHelpTest {
    private static final Set<String> ADMIN_ONLY = Set.of(
            "exportall", "exporthtmlall", "exportcsvall", "importall",
            "admin", "prunedata", "discordtest");

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
}
