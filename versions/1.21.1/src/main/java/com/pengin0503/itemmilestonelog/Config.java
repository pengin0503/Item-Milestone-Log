package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.TargetKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.time.ZoneId;
import java.util.List;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** Items every player tracks by default (in addition to their personal targets). */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DEFAULT_TARGETS = BUILDER
            .comment("Item IDs that every player tracks by default, e.g. [\"minecraft:netherite_ingot\", \"minecraft:diamond#100\"].",
                    "Append #<count> (1-1000000) to require a quantity greater than 1.",
                    "Players can add their own targets on top of these with /milestonelog add.")
            .defineListAllowEmpty("defaultTargets", List.of(), () -> "minecraft:diamond", Config::validateTargetEntry);

    /** How often (in ticks) each player's inventory is scanned for tracked items. */
    public static final ModConfigSpec.IntValue SCAN_INTERVAL_TICKS = BUILDER
            .comment("Inventory scan interval in ticks (20 ticks = 1 second). Lower = more precise timestamps, slightly more cost.")
            .defineInRange("scanIntervalTicks", 20, 1, 1200);

    /** Send the player a chat message when a milestone is recorded. */
    public static final ModConfigSpec.BooleanValue NOTIFY_PLAYER = BUILDER
            .comment("Notify the player in chat when one of their target items is recorded.")
            .define("notifyPlayer", true);

    /** Broadcast milestone records to every online player. */
    public static final ModConfigSpec.BooleanValue BROADCAST = BUILDER
            .comment("Broadcast milestone records to all online players (in addition to the player themself).")
            .define("broadcastMilestones", false);

    /** Record nothing in creative or spectator mode (see Tracker for items carried out of it). */
    public static final ModConfigSpec.BooleanValue IGNORE_CREATIVE_AND_SPECTATOR = BUILDER
            .comment("Do not record anything while a player is in creative or spectator mode.",
                    "Items a player still carries when switching back to survival or adventure are not",
                    "recorded either, until that item is no longer in the player's inventory.")
            .define("ignoreCreativeAndSpectator", true);

    /** Play a sound effect to the player when one of their target items is recorded. */
    public static final ModConfigSpec.BooleanValue MILESTONE_SOUND_ENABLED = BUILDER
            .comment("Play a sound to the player when a milestone is recorded (independent of notifyPlayer).")
            .define("milestoneSoundEnabled", true);

    /** Vanilla sound event ID to play when a milestone is recorded. */
    public static final ModConfigSpec.ConfigValue<String> MILESTONE_SOUND = BUILDER
            .comment("Sound event ID to play when a milestone is recorded.")
            .define("milestoneSound", "minecraft:ui.toast.challenge_complete", Config::validateSoundId);

    /** Announce server-wide world-first records to all online players. */
    public static final ModConfigSpec.BooleanValue ANNOUNCE_WORLD_FIRST = BUILDER
            .comment("Announce to all online players when someone records a world-first item in this world.")
            .define("announceWorldFirst", false);

    /** Discord webhook URL for milestone notifications (empty = disabled; supports env:VAR_NAME; never logged). */
    public static final ModConfigSpec.ConfigValue<String> DISCORD_WEBHOOK_URL = BUILDER
            .comment("Optional Discord webhook URL for milestone notifications. Leave empty to disable.",
                    "You can also use \"env:VAR_NAME\" to read the URL from an environment variable.",
                    "Treat this URL as a secret; it is never printed to logs or chat.")
            .define("discordWebhookUrl", "");

    /** Scope of Discord notifications: "all" or "world_first". */
    public static final ModConfigSpec.ConfigValue<String> DISCORD_NOTIFY_SCOPE = BUILDER
            .comment("Which milestones to send to Discord: \"all\" or \"world_first\".")
            .define("discordNotifyScope", "all", Config::validateDiscordScope);

    /** Show a one-time usage hint when a player joins a world for the first time. */
    public static final ModConfigSpec.BooleanValue SHOW_FIRST_JOIN_HINT = BUILDER
            .comment("Show a one-time chat tip when a player joins for the first time.")
            .define("showFirstJoinHint", true);

    /** Enable built-in and custom presets (commands and GUI). */
    public static final ModConfigSpec.BooleanValue PRESETS_ENABLED = BUILDER
            .comment("Enable preset commands and preset management in the GUI (existing preset files are never deleted).")
            .define("presetsEnabled", true);

    /**
     * F-140-20: reject a file/datapack preset entirely when any element fails validation,
     * instead of skipping only the invalid elements. Built-in presets are never rejected.
     */
    public static final ModConfigSpec.BooleanValue STRICT_PRESET_VALIDATION = BUILDER
            .comment("Reject a config-folder or datapack preset entirely when any element fails validation, instead of skipping only the invalid elements.",
                    "Includes resolution failures (missing preset ID or cycle) and invalid groups also reject the whole preset.",
                    "Built-in presets are never rejected. A rejected preset is logged as an error and treated as unknown (not listed, applied, or auto-applied).")
            .define("strictPresetValidation", false);

    /** Enable export and import commands. */
    public static final ModConfigSpec.BooleanValue EXPORT_IMPORT_ENABLED = BUILDER
            .comment("Enable milestone export and import commands (applies to OPs as well).")
            .define("exportImportEnabled", true);

    /** Record new world-first milestones in this world. */
    public static final ModConfigSpec.BooleanValue WORLD_FIRST_ENABLED = BUILDER
            .comment("Record new server-wide world-first milestones (existing world-first records are kept and viewable).")
            .define("worldFirstEnabled", true);

    /** Timezone ID used for dates formatted by the server (empty = server default). */
    public static final ModConfigSpec.ConfigValue<String> DISPLAY_TIME_ZONE = BUILDER
            .comment("Timezone ID used for server-formatted dates (chat, server log, Discord, exports).",
                    "Leave empty to use the server's default timezone. Examples: \"UTC\", \"Asia/Tokyo\", \"+09:00\".",
                    "The client GUI always uses the player's local timezone.")
            .define("displayTimeZone", "", Config::validateDisplayTimeZone);

    /** Send the player a chat alert when a quantity target crosses a progress threshold. */
    public static final ModConfigSpec.BooleanValue PROGRESS_ALERTS = BUILDER
            .comment("Notify the player in chat when a quantity target crosses a progress threshold (independent of notifyPlayer).")
            .define("progressAlerts", true);

    /** Percentage thresholds (1-99) at which to send progress alerts for quantity targets. */
    public static final ModConfigSpec.ConfigValue<List<? extends Integer>> PROGRESS_ALERT_THRESHOLDS = BUILDER
            .comment("Percentage thresholds (1-99) at which to send progress alerts for quantity targets.",
                    "Duplicates are ignored and values are evaluated in ascending order. Leave empty to disable.")
            .defineListAllowEmpty("progressAlertThresholds", List.of(50, 90), () -> 50, Config::validateProgressThreshold);

    /** Minimum target quantity required to trigger progress alerts. */
    public static final ModConfigSpec.IntValue PROGRESS_ALERT_MIN_COUNT = BUILDER
            .comment("Minimum target quantity required for progress alerts (2-1000000).")
            .defineInRange("progressAlertMinCount", ProgressAlertLogic.DEFAULT_MIN_COUNT,
                    ProgressAlertLogic.MIN_ALERT_COUNT_LOWER, TargetKey.MAX_COUNT);

    /** Group simultaneous milestone record notifications from the same tick into one message per channel. */
    public static final ModConfigSpec.BooleanValue GROUP_SIMULTANEOUS_RECORDS = BUILDER
            .comment("Group notifications when 3 or more milestones are recorded for the same player in the same tick.",
                    "Set to false to send an individual notification immediately for every record.")
            .define("groupSimultaneousRecords", true);

    /** N63/N125: single shared aggregation window for in-game and Discord notifications. */
    public static final ModConfigSpec.IntValue NOTIFICATION_WINDOW_TICKS = BUILDER
            .comment("Window length in ticks (1-20) used to aggregate in-game and Discord notifications before one flush.",
                    "1 keeps the current per-tick behaviour; the same window applies to both destinations.")
            .defineInRange("notificationWindowTicks", 1,
                    NotificationGrouping.MIN_WINDOW_TICKS, NotificationGrouping.MAX_WINDOW_TICKS);

    /** Send Discord milestone notifications as rich embeds instead of plain text. */
    public static final ModConfigSpec.BooleanValue DISCORD_USE_EMBEDS = BUILDER
            .comment("Send Discord milestone notifications as rich embeds instead of plain text.",
                    "Set to false to keep the plain-text format.")
            .define("discordUseEmbeds", false);

    /** Language used for Discord webhook notifications (must be a bundled language). */
    public static final ModConfigSpec.ConfigValue<String> DISCORD_LANGUAGE = BUILDER
            .comment("Language for Discord webhook notifications (\"en_us\" or \"ja_jp\").")
            .define("discordLanguage", "en_us", Config::validateDiscordLanguage);

    /** Allow players to opt out of having their milestones posted to Discord. */
    public static final ModConfigSpec.BooleanValue ALLOW_DISCORD_OPT_OUT = BUILDER
            .comment("Allow players to opt out of having their milestones posted to Discord via /milestonelog discordoptout.",
                    "When false, the command is disabled and saved opt-out preferences are ignored (but preserved).")
            .define("allowDiscordOptOut", true);

    /** Allow every player to view other players' records with /milestonelog lookup. */
    public static final ModConfigSpec.BooleanValue ALLOW_LOOKUP = BUILDER
            .comment("Allow every player to view other players' records with /milestonelog lookup <player>.",
                    "When false, only operators (and the singleplayer/LAN host) can look up other players; looking up yourself always works.")
            .define("allowLookup", true);

    /** Announce preset completions to all online players instead of only the completing player. */
    public static final ModConfigSpec.BooleanValue ANNOUNCE_PRESET_COMPLETION = BUILDER
            .comment("Announce to all online players when someone completes all targets of a preset.",
                    "When false, only the completing player is notified (if notifyPlayer is enabled).")
            .define("announcePresetCompletion", false);

    /** Post preset completions to Discord when a webhook is configured. */
    public static final ModConfigSpec.BooleanValue DISCORD_NOTIFY_PRESET_COMPLETION = BUILDER
            .comment("Post preset completions to Discord when a webhook URL is configured.",
                    "Independent of announcePresetCompletion and discordNotifyScope.")
            .define("discordNotifyPresetCompletion", false);

    /** Preset IDs automatically applied as personal targets when a player joins for the first time (empty = disabled). */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> FIRST_JOIN_PRESETS = BUILDER
            .comment("Preset IDs to apply automatically as personal targets the first time a player joins this world.",
                    "Leave empty to disable. Unlike defaultTargets, players can remove these targets with /milestonelog remove.",
                    "Example: [\"vanilla_endgame\"]")
            .defineListAllowEmpty("firstJoinPresets", List.of(), () -> "vanilla_endgame", Config::validateFirstJoinPreset);

    /** F12: attach a show_item hover (the item's default stack) to item names in chat messages. */
    public static final ModConfigSpec.BooleanValue CHAT_ITEM_HOVER = BUILDER
            .comment("Show the item when hovering an item name in this mod's chat messages (show_item hover).",
                    "Tag targets show the first item of the tag (by registry ID). The text itself is unchanged.")
            .define("chatItemHover", true);

    /** F17: scoreboard objective name for each player's milestone record count (empty = disabled). */
    public static final ModConfigSpec.ConfigValue<String> SCOREBOARD_OBJECTIVE = BUILDER
            .comment("Optional scoreboard objective name to store each player's milestone record count (empty = disabled).",
                    "Use a dedicated name (e.g. \"iml_records\") matching ^[A-Za-z0-9_.+-]{1,32}$ so it does not overwrite other datapacks.",
                    "Created automatically with the dummy criterion on first write; clearing this setting does not delete existing objectives or scores.")
            .define("scoreboardObjective", "", Config::validateScoreboardObjective);

    /** F17: scoreboard objective name for each player's world-first count (empty = disabled). */
    public static final ModConfigSpec.ConfigValue<String> SCOREBOARD_WORLD_FIRST_OBJECTIVE = BUILDER
            .comment("Optional scoreboard objective name to store each player's world-first count (empty = disabled).",
                    "Must match ^[A-Za-z0-9_.+-]{1,32}$ and differ from scoreboardObjective (if equal, this one is disabled with a warning).",
                    "Created automatically with the dummy criterion on first write; clearing this setting does not delete existing objectives or scores.")
            .define("scoreboardWorldFirstObjective", "", Config::validateScoreboardObjective);

    /** F7: server command executed on milestone confirmation (without leading '/', empty = disabled). */
    public static final ModConfigSpec.ConfigValue<String> MILESTONE_COMMAND = BUILDER
            .comment("Optional server command to run (without a leading '/') when a milestone is recorded (empty = disabled).",
                    "Example: \"give %player% minecraft:firework_rocket 3\" (use \"function <namespace>:<path>\" for multiple commands).",
                    "Placeholders: %player%, %uuid%, %item% (or #tag for tag targets), %count%, %playtime% (may contain spaces), %worldfirst% (true/false).",
                    "Security note: only server owners can edit this config file. %player% is substituted only when the",
                    "player's name matches ^[A-Za-z0-9_.*+-]{1,20}$ (otherwise execution is skipped and a warning is logged);",
                    "%uuid% can also target the player, and %item% only ever contains validated item or tag IDs.")
            .define("milestoneCommand", "");

    /** F10: automatically back up MilestoneData on world save and server stop. */
    public static final ModConfigSpec.BooleanValue AUTO_BACKUP_ENABLED = BUILDER
            .comment("Automatically back up MilestoneData to <world>/itemmilestonelog/backups/ on world save (at most once per hour) and server stop.",
                    "Independent of exportImportEnabled.")
            .define("autoBackupEnabled", true);

    /** F10: maximum number of automatic backup files to keep per world (1-20). */
    public static final ModConfigSpec.IntValue AUTO_BACKUP_GENERATIONS = BUILDER
            .comment("Maximum number of automatic backup files to keep in <world>/itemmilestonelog/backups/ (1-20).")
            .defineInRange("autoBackupGenerations", MilestoneBackupLogic.DEFAULT_GENERATIONS,
                    MilestoneBackupLogic.MIN_GENERATIONS, MilestoneBackupLogic.MAX_GENERATIONS);

    /** F9: periodically send a Discord digest summarizing milestones recorded since the previous digest. */
    public static final ModConfigSpec.BooleanValue DISCORD_DIGEST_ENABLED = BUILDER
            .comment("Send a periodic Discord digest summarizing milestones recorded since the previous digest.",
                    "Uses discordWebhookUrl, discordNotifyScope, discordUseEmbeds, and discordLanguage.")
            .define("discordDigestEnabled", false);

    /** F9: interval in days between Discord digest messages (1-30). */
    public static final ModConfigSpec.IntValue DISCORD_DIGEST_INTERVAL_DAYS = BUILDER
            .comment("Interval in days between Discord digest messages (1-30).")
            .defineInRange("discordDigestIntervalDays", DiscordDigestLogic.DEFAULT_INTERVAL_DAYS,
                    DiscordDigestLogic.MIN_INTERVAL_DAYS, DiscordDigestLogic.MAX_INTERVAL_DAYS);

    /** F46: optional separate Discord webhook URL used exclusively for world-first notifications (empty = unused; supports env:VAR_NAME; never logged). */
    public static final ModConfigSpec.ConfigValue<String> DISCORD_WORLD_FIRST_WEBHOOK_URL = BUILDER
            .comment("Optional Discord webhook URL dedicated to world-first milestone notifications. Leave empty to use discordWebhookUrl.",
                    "When set (and resolvable), world-first posts are sent ONLY to this URL (not duplicated to discordWebhookUrl).",
                    "Preset completion (F19) and periodic digest (F9) posts always use discordWebhookUrl.",
                    "You can also use \"env:VAR_NAME\" to read the URL from an environment variable.",
                    "Treat this URL as a secret; it is never printed to logs or chat.")
            .define("discordWorldFirstWebhookUrl", "");

    /** F52: send a chat summary of world firsts recorded while the player was away at login. */
    public static final ModConfigSpec.BooleanValue LOGIN_WORLD_FIRST_SUMMARY = BUILDER
            .comment("Send a chat summary when a player logs in, listing world firsts recorded while they were away.",
                    "Requires a stored lastSeen time (1.1.0+) and is skipped while the player has muteBroadcasts enabled.",
                    "Shows at most 5 entries plus the remaining count.")
            .define("loginWorldFirstSummary", false);

    /** F53: send normal (non-world-first) milestone record posts to Discord silently (SUPPRESS_NOTIFICATIONS flag). */
    public static final ModConfigSpec.BooleanValue DISCORD_SILENT_RECORDS = BUILDER
            .comment("Send normal (non-world-first) milestone record posts to Discord without triggering notifications (flags: 4096).",
                    "Does not affect world-first posts, grouped batches containing a world first, preset completions (F19),",
                    "periodic digests (F9), or /milestonelog discordtest (F49).")
            .define("discordSilentRecords", false);

    /** F25: show a star mark on milestones in the GUI for which the player holds the world first. */
    public static final ModConfigSpec.BooleanValue GUI_WORLD_FIRST_MARK = BUILDER
            .comment("Show a star mark (★) on recorded milestones in the GUI for which you hold the world first.",
                    "Does not affect datapack preset metadata sent in the extra sync payload.")
            .define("guiWorldFirstMark", true);

    /** F42: show milestone status lines at the end of item tooltips (read from the client's own config file). */
    public static final ModConfigSpec.BooleanValue ITEM_TOOLTIP_STATUS = BUILDER
            .comment("Show milestone status lines at the end of item tooltips on this client.",
                    "This is a client-side display setting: each player's own config file controls it on their client;",
                    "the server's value is never used.")
            .define("itemTooltipStatus", true);

    static final ModConfigSpec SPEC = BUILDER.build();

    private record ThresholdsCache(List<? extends Integer> source, List<Integer> normalized) {
    }

    private static volatile ThresholdsCache progressAlertThresholdsCache = new ThresholdsCache(null, List.of());

    private Config() {
    }

    static boolean validateTargetEntry(Object obj) {
        return obj instanceof String s && TargetKey.tryParseNormalized(s, true, true) != null;
    }

    static boolean validateProgressThreshold(Object obj) {
        return obj instanceof Integer n && ProgressAlertLogic.isValidThreshold(n);
    }

    /** Returns deduplicated, ascending-sorted progress alert thresholds (1-99). */
    public static List<Integer> progressAlertThresholds() {
        List<? extends Integer> current = PROGRESS_ALERT_THRESHOLDS.get();
        ThresholdsCache cache = progressAlertThresholdsCache;
        if (current == cache.source()) {
            return cache.normalized();
        }
        List<Integer> normalized = ProgressAlertLogic.normalizeThresholds(current);
        progressAlertThresholdsCache = new ThresholdsCache(current, normalized);
        return normalized;
    }

    static boolean validateDisplayTimeZone(Object obj) {
        return DisplayTimeZone.isValidConfigValue(obj);
    }

    /** Resolves {@link #DISPLAY_TIME_ZONE}, falling back to the server's default timezone when empty or invalid. */
    public static ZoneId displayTimeZone() {
        return DisplayTimeZone.resolve(DISPLAY_TIME_ZONE.get());
    }

    /**
     * F12: {@link #CHAT_ITEM_HOVER}, or {@code false} while the config is not loaded yet
     * (plain unit tests), so building a chat component never throws.
     */
    public static boolean chatItemHover() {
        try {
            return CHAT_ITEM_HOVER.get();
        } catch (IllegalStateException notLoaded) {
            return false;
        }
    }

    static boolean validateScoreboardObjective(Object obj) {
        return ScoreboardLogic.isValidConfigValue(obj);
    }

    /**
     * F17: resolves {@link #SCOREBOARD_OBJECTIVE} and {@link #SCOREBOARD_WORLD_FIRST_OBJECTIVE},
     * returning both disabled while the config is not loaded yet (plain unit tests).
     */
    public static ScoreboardLogic.Objectives scoreboardObjectives() {
        try {
            return ScoreboardLogic.resolve(SCOREBOARD_OBJECTIVE.get(), SCOREBOARD_WORLD_FIRST_OBJECTIVE.get());
        } catch (IllegalStateException notLoaded) {
            return new ScoreboardLogic.Objectives(null, null, false);
        }
    }

    /**
     * F7: {@link #MILESTONE_COMMAND}, or {@code ""} while the config is not loaded yet
     * (plain unit tests), so milestone recording never throws.
     */
    public static String milestoneCommand() {
        try {
            String cmd = MILESTONE_COMMAND.get();
            return cmd != null ? cmd : "";
        } catch (IllegalStateException notLoaded) {
            return "";
        }
    }

    /**
     * F10: {@link #AUTO_BACKUP_ENABLED}, or {@code true} while the config is not loaded yet
     * (plain unit tests).
     */
    public static boolean autoBackupEnabled() {
        try {
            return AUTO_BACKUP_ENABLED.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }

    /**
     * F10: {@link #AUTO_BACKUP_GENERATIONS}, or {@link MilestoneBackupLogic#DEFAULT_GENERATIONS}
     * while the config is not loaded yet (plain unit tests).
     */
    public static int autoBackupGenerations() {
        try {
            return AUTO_BACKUP_GENERATIONS.get();
        } catch (IllegalStateException notLoaded) {
            return MilestoneBackupLogic.DEFAULT_GENERATIONS;
        }
    }

    /**
     * F9: {@link #DISCORD_DIGEST_ENABLED}, or {@code false} while the config is not loaded yet
     * (plain unit tests).
     */
    public static boolean discordDigestEnabled() {
        try {
            return DISCORD_DIGEST_ENABLED.get();
        } catch (IllegalStateException notLoaded) {
            return false;
        }
    }

    /**
     * F9: {@link #DISCORD_DIGEST_INTERVAL_DAYS}, or {@link DiscordDigestLogic#DEFAULT_INTERVAL_DAYS}
     * while the config is not loaded yet (plain unit tests).
     */
    public static int discordDigestIntervalDays() {
        try {
            return DISCORD_DIGEST_INTERVAL_DAYS.get();
        } catch (IllegalStateException notLoaded) {
            return DiscordDigestLogic.DEFAULT_INTERVAL_DAYS;
        }
    }

    /**
     * N63/N125: {@link #NOTIFICATION_WINDOW_TICKS}, or {@code 1} while the config is not loaded yet
     * (plain unit tests). The default is the current per-tick F27 behaviour.
     */
    public static int notificationWindowTicks() {
        try {
            return NotificationGrouping.normalizeWindowTicks(NOTIFICATION_WINDOW_TICKS.get());
        } catch (IllegalStateException notLoaded) {
            return NotificationGrouping.normalizeWindowTicks(NotificationGrouping.MIN_WINDOW_TICKS);
        }
    }

    /**
     * F46: {@link #DISCORD_WORLD_FIRST_WEBHOOK_URL}, or {@code ""} while the config is not loaded yet
     * (plain unit tests).
     */
    public static String discordWorldFirstWebhookUrl() {
        try {
            String url = DISCORD_WORLD_FIRST_WEBHOOK_URL.get();
            return url != null ? url : "";
        } catch (IllegalStateException notLoaded) {
            return "";
        }
    }

    /**
     * F53: {@link #DISCORD_SILENT_RECORDS}, or {@code false} while the config is not loaded yet
     * (plain unit tests).
     */
    public static boolean discordSilentRecords() {
        try {
            return DISCORD_SILENT_RECORDS.get();
        } catch (IllegalStateException notLoaded) {
            return false;
        }
    }

    /**
     * F52: {@link #LOGIN_WORLD_FIRST_SUMMARY}, or {@code false} while the config is not loaded yet
     * (plain unit tests).
     */
    public static boolean loginWorldFirstSummary() {
        try {
            return LOGIN_WORLD_FIRST_SUMMARY.get();
        } catch (IllegalStateException notLoaded) {
            return false;
        }
    }

    /**
     * F25: {@link #GUI_WORLD_FIRST_MARK}, or {@code true} while the config is not loaded yet
     * (plain unit tests).
     */
    public static boolean guiWorldFirstMark() {
        try {
            return GUI_WORLD_FIRST_MARK.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }

    /**
     * F42: {@link #ITEM_TOOLTIP_STATUS}, or {@code true} while the config is not loaded yet
     * (plain unit tests). The client reads its own config file here.
     */
    public static boolean itemTooltipStatus() {
        try {
            return ITEM_TOOLTIP_STATUS.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }

    /**
     * F-140-20: {@link #STRICT_PRESET_VALIDATION}, or {@code false} while the config is not
     * loaded yet (plain unit tests).
     */
    public static boolean strictPresetValidation() {
        try {
            return STRICT_PRESET_VALIDATION.get();
        } catch (IllegalStateException notLoaded) {
            return false;
        }
    }

    private static boolean validateSoundId(Object obj) {
        return obj instanceof String s && !s.isBlank() && ResourceLocation.tryParse(s) != null;
    }

    private static boolean validateDiscordScope(Object obj) {
        return obj instanceof String s && ("all".equals(s) || "world_first".equals(s));
    }

    static boolean validateDiscordLanguage(Object obj) {
        return obj instanceof String s && ServerLang.BUNDLED_LANGUAGES.contains(s);
    }

    static boolean validateFirstJoinPreset(Object obj) {
        return obj instanceof String s && Presets.isValidId(s);
    }
}
