package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-Java unit tests for F29 {@link CommandHelp} table ordering, gating, and usage formatting. */
class CommandHelpTest {
    private static final Set<String> ADMIN_ONLY = Set.of(
            "exportall", "exporthtmlall", "exportcsvall", "importall",
            "admin", "prunedata", "renameitem", "renametag", "discordtest");

    private static List<String> namesOf(List<CommandHelp.Entry> entries) {
        return entries.stream().map(CommandHelp.Entry::name).toList();
    }

    @Test
    void entriesFollowSpecOrderWithUniqueNamesAndExpectedCommands() {
        List<String> expectedOrder = List.of(
                "summary", "playtime", "add", "addhand", "addtag", "remove", "removetag", "removeall",
                "targets", "list", "search",
                "preset", "presetremove", "presetprogress", "reset", "resettag", "resetall", "undo",
                "firsts", "info", "top", "toptag", "recent", "stats", "lookup",
                "privacy", "mutebroadcast", "muteprogress", "discordoptout",
                "export", "exporthtml", "exportcsv", "import",
                "exportall", "exporthtmlall", "exportcsvall", "importall",
                "admin", "prunedata", "renameitem", "renametag", "discordtest", "help"
        );
        List<String> actual = namesOf(CommandHelp.ENTRIES);
        assertEquals(expectedOrder, actual);
        assertEquals(actual.size(), new LinkedHashSet<>(actual).size(), "Duplicate command help entries");

        for (CommandHelp.Entry entry : CommandHelp.ENTRIES) {
            assertEquals("message.itemmilestonelog.help." + entry.name(), entry.descriptionKey());
            if ("summary".equals(entry.name())) {
                assertTrue(entry.isRoot());
                assertEquals("/milestonelog", entry.displayCommand());
                assertEquals("/milestonelog", entry.suggestCommand());
            } else {
                assertFalse(entry.isRoot());
                assertEquals("/milestonelog " + entry.name(), entry.displayCommand());
                assertEquals("/milestonelog " + entry.name() + " ", entry.suggestCommand());
            }
        }
    }

    @Test
    void excludesPlayerOnlyCommandsForNonPlayerSources() {
        CommandHelp.Context console = new CommandHelp.Context(false, true, true, true, true, true);
        List<String> visible = namesOf(CommandHelp.visibleEntries(console, e -> true));

        assertEquals(List.of(
                "preset", "firsts", "top", "toptag", "recent", "stats", "lookup",
                "exportall", "exporthtmlall", "exportcsvall", "importall",
                "admin", "prunedata", "renameitem", "renametag", "discordtest", "help"
        ), visible);

        assertNull(CommandHelp.findVisible("summary", console, e -> true));
        assertNull(CommandHelp.findVisible("add", console, e -> true));
        assertNotNull(CommandHelp.findVisible("preset", console, e -> true));
        assertNotNull(CommandHelp.findVisible("admin", console, e -> true));
    }

    @Test
    void excludesNodesFailingBrigadierCanUse() {
        CommandHelp.Context regularPlayer = new CommandHelp.Context(true, false, true, true, true, true);
        List<String> visible = namesOf(CommandHelp.visibleEntries(regularPlayer, e -> !ADMIN_ONLY.contains(e.name())));

        for (String adminCmd : ADMIN_ONLY) {
            assertFalse(visible.contains(adminCmd), adminCmd + " must be excluded when canUse is false");
            assertNull(CommandHelp.findVisible(adminCmd, regularPlayer, e -> !ADMIN_ONLY.contains(e.name())));
        }
        assertTrue(visible.contains("summary"));
        assertTrue(visible.contains("firsts"));
        assertTrue(visible.contains("help"));
    }

    @Test
    void excludesDisabledFeaturesByConfigSwitches() {
        // presetsEnabled = false hides preset, presetremove, presetprogress even for privileged OP
        CommandHelp.Context noPresets = new CommandHelp.Context(true, true, false, true, true, true);
        List<String> noPresetsVisible = namesOf(CommandHelp.visibleEntries(noPresets, e -> true));
        assertFalse(noPresetsVisible.contains("preset"));
        assertFalse(noPresetsVisible.contains("presetremove"));
        assertFalse(noPresetsVisible.contains("presetprogress"));
        assertTrue(noPresetsVisible.contains("add"));

        // exportImportEnabled = false hides all 8 export/import commands even for privileged OP
        CommandHelp.Context noExport = new CommandHelp.Context(true, true, true, false, true, true);
        List<String> noExportVisible = namesOf(CommandHelp.visibleEntries(noExport, e -> true));
        for (String cmd : List.of("export", "exporthtml", "exportcsv", "import",
                "exportall", "exporthtmlall", "exportcsvall", "importall")) {
            assertFalse(noExportVisible.contains(cmd), cmd + " must be hidden when exportImportEnabled=false");
        }

        // allowLookup = false hides lookup, top, recent, stats for non-privileged sources only
        CommandHelp.Context noLookupRegular = new CommandHelp.Context(true, false, true, true, false, true);
        List<String> noLookupRegularVisible = namesOf(CommandHelp.visibleEntries(noLookupRegular, e -> true));
        for (String cmd : List.of("lookup", "top", "recent", "stats")) {
            assertFalse(noLookupRegularVisible.contains(cmd), cmd + " must be hidden for non-privileged when allowLookup=false");
        }
        CommandHelp.Context noLookupPrivileged = new CommandHelp.Context(true, true, true, true, false, true);
        List<String> noLookupPrivilegedVisible = namesOf(CommandHelp.visibleEntries(noLookupPrivileged, e -> true));
        for (String cmd : List.of("lookup", "top", "recent", "stats")) {
            assertTrue(noLookupPrivilegedVisible.contains(cmd), cmd + " must remain visible for privileged when allowLookup=false");
        }

        // allowDiscordOptOut = false hides discordoptout even for privileged OP
        CommandHelp.Context noOptOut = new CommandHelp.Context(true, true, true, true, true, false);
        List<String> noOptOutVisible = namesOf(CommandHelp.visibleEntries(noOptOut, e -> true));
        assertFalse(noOptOutVisible.contains("discordoptout"));
        assertNull(CommandHelp.findVisible("discordoptout", noOptOut, e -> true));
    }

    @Test
    void findVisibleRejectsUnknownBlankAndNullNames() {
        CommandHelp.Context all = new CommandHelp.Context(true, true, true, true, true, true);
        assertNull(CommandHelp.findVisible(null, all, e -> true));
        assertNull(CommandHelp.findVisible("", all, e -> true));
        assertNull(CommandHelp.findVisible("   ", all, e -> true));
        assertNull(CommandHelp.findVisible("nonexistent", all, e -> true));
        assertNull(CommandHelp.findVisible("ADD", all, e -> true));

        CommandHelp.Entry found = CommandHelp.findVisible("add", all, e -> true);
        assertNotNull(found);
        assertEquals("add", found.name());
    }

    @Test
    void formatUsageLinesHandlesRootLeafAndMultiBranchSmartUsages() {
        CommandHelp.Entry summary = new CommandHelp.Entry("summary", true, CommandHelp.Gate.NONE);
        assertEquals(List.of("/milestonelog"), CommandHelp.formatUsageLines(summary, List.of("ignored")));

        CommandHelp.Entry playtime = new CommandHelp.Entry("playtime", true, CommandHelp.Gate.NONE);
        assertEquals(List.of("/milestonelog playtime"), CommandHelp.formatUsageLines(playtime, List.of()));
        assertEquals(List.of("/milestonelog playtime"), CommandHelp.formatUsageLines(playtime, null));

        CommandHelp.Entry preset = new CommandHelp.Entry("preset", false, CommandHelp.Gate.PRESETS);
        assertEquals(List.of(
                "/milestonelog preset list",
                "/milestonelog preset save [<name>]",
                "/milestonelog preset <name>"
        ), CommandHelp.formatUsageLines(preset, List.of("list", "save [<name>]", "<name>")));
    }
}
