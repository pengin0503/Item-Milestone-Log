package com.pengin0503.itemmilestonelog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Single source of truth for {@code /milestonelog help} (F29): the ordered command table
 * (name, player-only flag, feature gate) together with pure-Java visibility and usage formatting
 * helpers shared across all Minecraft versions via {@code common/src/main/java}.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class CommandHelp {
    /** Root command label shown in help output and clickable suggestions. */
    public static final String ROOT_COMMAND = "/milestonelog";

    /** Table name representing the bare {@code /milestonelog} summary command. */
    public static final String SUMMARY_NAME = "summary";

    /** Prefix for per-command description keys ({@code message.itemmilestonelog.help.<name>}). */
    public static final String HELP_KEY_PREFIX = "message.itemmilestonelog.help.";

    /** Config/feature switch gating a command in {@code /milestonelog help}. */
    public enum Gate {
        /** Always available unless blocked by Brigadier {@code requires} or {@code playerOnly}. */
        NONE,
        /** Requires {@code presetsEnabled == true}. */
        PRESETS,
        /** Requires {@code exportImportEnabled == true}. */
        EXPORT_IMPORT,
        /** Requires {@code allowLookup == true} or the F43 viewhidden privilege. */
        LOOKUP,
        /** Requires {@code allowDiscordOptOut == true}. */
        DISCORD_OPT_OUT
    }

    /**
     * One row in the F29 command help table.
     *
     * @param name       subcommand literal name, or {@link #SUMMARY_NAME} for the root command
     * @param playerOnly whether executing this command requires a player source
     * @param gate       config switch gating this command
     */
    public record Entry(String name, boolean playerOnly, Gate gate) {
        public Entry {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("Command help entry name must not be blank");
            }
            Objects.requireNonNull(gate, "gate");
        }

        /** Whether this entry represents the bare root {@code /milestonelog} command. */
        public boolean isRoot() {
            return SUMMARY_NAME.equals(name);
        }

        /** Translation key for this command's short description. */
        public String descriptionKey() {
            return HELP_KEY_PREFIX + name;
        }

        /** Command string shown in {@code help_entry} (e.g. {@code /milestonelog add} or {@code /milestonelog}). */
        public String displayCommand() {
            return isRoot() ? ROOT_COMMAND : ROOT_COMMAND + " " + name;
        }

        /** Text inserted into chat input by {@code ClickEvent} {@code suggest_command}. */
        public String suggestCommand() {
            return isRoot() ? ROOT_COMMAND : ROOT_COMMAND + " " + name + " ";
        }
    }

    /** Snapshot of the caller and server config switches used to filter visible help entries. */
    public record Context(
            boolean isPlayer,
            /** Whether the source may view hidden records: legacy privilege OR the F43 viewhidden node. */
            boolean privileged,
            boolean presetsEnabled,
            boolean exportImportEnabled,
            boolean allowLookup,
            boolean allowDiscordOptOut
    ) {
    }

    /**
     * Complete ordered table of {@code /milestonelog} commands (spec F29; v1.2.0 adds the F8b
     * tag commands {@code addtag} / {@code removetag} / {@code resettag}, F13 {@code toptag},
     * and F51 {@code info}).
     * {@code MilestoneCommandsHelpTest} verifies that every registered subcommand appears here
     * and every entry has both English and Japanese descriptions.
     */
    public static final List<Entry> ENTRIES = List.of(
            new Entry("summary", true, Gate.NONE),
            new Entry("playtime", true, Gate.NONE),
            new Entry("add", true, Gate.NONE),
            new Entry("addhand", true, Gate.NONE),
            new Entry("addtag", true, Gate.NONE),
            new Entry("remove", true, Gate.NONE),
            new Entry("removetag", true, Gate.NONE),
            new Entry("removeall", true, Gate.NONE),
            new Entry("targets", true, Gate.NONE),
            new Entry("list", true, Gate.NONE),
            new Entry("search", true, Gate.NONE),
            new Entry("preset", false, Gate.PRESETS),
            new Entry("presetremove", true, Gate.PRESETS),
            new Entry("presetprogress", true, Gate.PRESETS),
            new Entry("reset", true, Gate.NONE),
            new Entry("resettag", true, Gate.NONE),
            new Entry("resetall", true, Gate.NONE),
            new Entry("undo", true, Gate.NONE),
            new Entry("firsts", false, Gate.NONE),
            new Entry("info", true, Gate.NONE),
            new Entry("top", false, Gate.LOOKUP),
            new Entry("presettop", false, Gate.LOOKUP),
            new Entry("toptag", false, Gate.LOOKUP),
            new Entry("recent", false, Gate.LOOKUP),
            new Entry("stats", false, Gate.LOOKUP),
            new Entry("statstag", false, Gate.LOOKUP),
            new Entry("whohas", false, Gate.LOOKUP),
            new Entry("whohastag", false, Gate.LOOKUP),
            new Entry("lookup", false, Gate.LOOKUP),
            new Entry("privacy", true, Gate.NONE),
            new Entry("mutebroadcast", true, Gate.NONE),
            new Entry("muteprogress", true, Gate.NONE),
            new Entry("discordoptout", true, Gate.DISCORD_OPT_OUT),
            new Entry("settings", true, Gate.NONE),
            new Entry("randomtarget", true, Gate.NONE),
            new Entry("trends", true, Gate.NONE),
            new Entry("recap", true, Gate.NONE),
            // PR-2 (F-150-11/12/18/24/29/39/20): read-only analytics.
            new Entry("compare", false, Gate.LOOKUP),
            new Entry("timeline", false, Gate.LOOKUP),
            new Entry("timelinetag", false, Gate.LOOKUP),
            new Entry("session", true, Gate.NONE),
            new Entry("modstats", false, Gate.LOOKUP),
            new Entry("presetstats", false, Gate.LOOKUP),
            new Entry("presetmatrix", false, Gate.LOOKUP),
            // PR-3 write operations (F-150-13/14/16/17/25/26/30/31/37).
            new Entry("copytargets", true, Gate.LOOKUP),
            new Entry("sharetargets", true, Gate.LOOKUP),
            new Entry("addsteps", true, Gate.NONE),
            new Entry("bulk", true, Gate.NONE),
            new Entry("pinned", true, Gate.NONE),
            new Entry("export", true, Gate.EXPORT_IMPORT),
            new Entry("exporthtml", true, Gate.EXPORT_IMPORT),
            new Entry("exportcsv", true, Gate.EXPORT_IMPORT),
            new Entry("import", true, Gate.EXPORT_IMPORT),
            new Entry("exportall", false, Gate.EXPORT_IMPORT),
            new Entry("exporthtmlall", false, Gate.EXPORT_IMPORT),
            new Entry("exportcsvall", false, Gate.EXPORT_IMPORT),
            new Entry("importall", false, Gate.EXPORT_IMPORT),
            new Entry("admin", false, Gate.NONE),
            new Entry("prunedata", false, Gate.NONE),
            new Entry("renameitem", false, Gate.NONE),
            new Entry("renametag", false, Gate.NONE),
            new Entry("discordtest", false, Gate.NONE),
            new Entry("help", false, Gate.NONE)
    );

    private CommandHelp() {
    }

    /** Returns whether {@code entry} passes the player-only check and its config gate for {@code context}. */
    public static boolean isAllowedBySwitchesAndSource(Entry entry, Context context) {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(context, "context");
        if (entry.playerOnly() && !context.isPlayer()) {
            return false;
        }
        return switch (entry.gate()) {
            case NONE -> true;
            case PRESETS -> context.presetsEnabled();
            case EXPORT_IMPORT -> context.exportImportEnabled();
            case LOOKUP -> LookupPolicy.canLookUpOthers(context.allowLookup(), context.privileged());
            case DISCORD_OPT_OUT -> context.allowDiscordOptOut();
        };
    }

    /**
     * Filters {@link #ENTRIES} in table order, keeping only entries that pass
     * {@link #isAllowedBySwitchesAndSource} and the Brigadier {@code canUseNode} predicate.
     */
    public static List<Entry> visibleEntries(Context context, Predicate<Entry> canUseNode) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(canUseNode, "canUseNode");
        List<Entry> visible = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            if (isAllowedBySwitchesAndSource(entry, context) && canUseNode.test(entry)) {
                visible.add(entry);
            }
        }
        return List.copyOf(visible);
    }

    /**
     * Looks up a single command by {@code name} if and only if it is visible to this source
     * (passes switches, player-only check, and Brigadier {@code canUseNode}). Returns {@code null}
     * for unknown or unusable names.
     */
    public static Entry findVisible(String name, Context context, Predicate<Entry> canUseNode) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(canUseNode, "canUseNode");
        if (name == null || name.isBlank()) {
            return null;
        }
        for (Entry entry : ENTRIES) {
            if (entry.name().equals(name)) {
                return isAllowedBySwitchesAndSource(entry, context) && canUseNode.test(entry) ? entry : null;
            }
        }
        return null;
    }

    /**
     * Formats Brigadier {@code CommandDispatcher#getSmartUsage} values for {@code entry} into full
     * command usage strings. For the root {@code summary} entry or any leaf command with no child
     * usages, returns a single-element list containing {@link Entry#displayCommand()}.
     */
    public static List<String> formatUsageLines(Entry entry, Collection<String> smartUsages) {
        Objects.requireNonNull(entry, "entry");
        if (entry.isRoot() || smartUsages == null || smartUsages.isEmpty()) {
            return List.of(entry.displayCommand());
        }
        List<String> lines = new ArrayList<>(smartUsages.size());
        String prefix = entry.displayCommand() + " ";
        for (String usage : smartUsages) {
            if (usage != null && !usage.isBlank()) {
                lines.add(prefix + usage);
            }
        }
        return lines.isEmpty() ? List.of(entry.displayCommand()) : List.copyOf(lines);
    }
}
