package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.TargetKey;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.time.ZoneId;
import java.util.List;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<List<? extends String>> DEFAULT_TARGETS = BUILDER
            .comment("Item IDs that every player tracks by default, e.g. [\"minecraft:netherite_ingot\", \"minecraft:diamond#100\"].",
                    "Append #<count> (1-1000000) to require a quantity greater than 1.",
                    "Players can add their own targets on top of these with /milestonelog add.")
            .defineListAllowEmpty("defaultTargets", List.of(), () -> "minecraft:diamond", Config::validateTargetEntry);

    public static final ModConfigSpec.IntValue SCAN_INTERVAL_TICKS = BUILDER
            .comment("Inventory scan interval in ticks (20 ticks = 1 second). Lower = more precise timestamps, slightly more cost.")
            .defineInRange("scanIntervalTicks", 20, 1, 1200);

    public static final ModConfigSpec.BooleanValue NOTIFY_PLAYER = BUILDER
            .comment("Notify the player in chat when one of their target items is recorded.")
            .define("notifyPlayer", true);

    public static final ModConfigSpec.BooleanValue BROADCAST = BUILDER
            .comment("Broadcast milestone records to all online players (in addition to the player themself).")
            .define("broadcastMilestones", false);

    public static final ModConfigSpec.BooleanValue IGNORE_CREATIVE_AND_SPECTATOR = BUILDER
            .comment("Do not record anything while a player is in creative or spectator mode.",
                    "Items a player still carries when switching back to survival or adventure are not",
                    "recorded either, until that item is no longer in the player's inventory.")
            .define("ignoreCreativeAndSpectator", true);

    public static final ModConfigSpec.BooleanValue MILESTONE_SOUND_ENABLED = BUILDER
            .comment("Play a sound to the player when a milestone is recorded (independent of notifyPlayer).")
            .define("milestoneSoundEnabled", true);

    public static final ModConfigSpec.ConfigValue<String> MILESTONE_SOUND = BUILDER
            .comment("Sound event ID to play when a milestone is recorded.")
            .define("milestoneSound", "minecraft:ui.toast.challenge_complete", Config::validateSoundId);

    /** F-150-47 (N15): world-first sound ID; empty reuses milestoneSound (1.4.0 behaviour). */
    public static final ModConfigSpec.ConfigValue<String> WORLD_FIRST_SOUND = BUILDER
            .comment("Sound event ID to play for a world-first record (empty = reuse milestoneSound).")
            .define("worldFirstSound", "", Config::validateOptionalSoundId);

    /** F-150-47 (N15): preset-completion sound ID; empty reuses milestoneSound. */
    public static final ModConfigSpec.ConfigValue<String> PRESET_COMPLETE_SOUND = BUILDER
            .comment("Sound event ID to play when a preset is completed (empty = reuse milestoneSound).")
            .define("presetCompleteSound", "", Config::validateOptionalSoundId);

    /** F-150-47 (N15): progress-alert sound ID; empty keeps progress alerts silent. */
    public static final ModConfigSpec.ConfigValue<String> PROGRESS_ALERT_SOUND = BUILDER
            .comment("Sound event ID to play for progress alerts (empty = no new progress-alert sound).")
            .define("progressAlertSound", "", Config::validateOptionalSoundId);

    /** F-150-48 (N16): opt-in sound for world-first announcement recipients. */
    public static final ModConfigSpec.BooleanValue ANNOUNCE_WORLD_FIRST_SOUND = BUILDER
            .comment("Also play the world-first sound to announcement recipients (follows announceWorldFirst and each recipient's muteBroadcasts).",
                    "Default false keeps 1.4.0 behaviour; recipients who muted notification sounds are skipped.")
            .define("announceWorldFirstSound", false);

    /** F-150-49 (N114): volume used when a player has not set a personal value (-1). */
    public static final ModConfigSpec.IntValue NOTIFICATION_SOUND_VOLUME = BUILDER
            .comment("Notification volume (0-100) used when a player follows the server default; 100 keeps vanilla loudness.")
            .defineInRange("notificationSoundVolume", SoundVolumeLogic.DEFAULT_SERVER_VOLUME,
                    SoundVolumeLogic.MIN_VOLUME, SoundVolumeLogic.MAX_VOLUME);

    public static final ModConfigSpec.BooleanValue ANNOUNCE_WORLD_FIRST = BUILDER
            .comment("Announce to all online players when someone records a world-first item in this world.")
            .define("announceWorldFirst", false);

    public static final ModConfigSpec.ConfigValue<String> DISCORD_WEBHOOK_URL = BUILDER
            .comment("Optional Discord webhook URL for milestone notifications. Leave empty to disable.",
                    "You can also use \"env:VAR_NAME\" to read the URL from an environment variable.",
                    "Treat this URL as a secret; it is never printed to logs or chat.")
            .define("discordWebhookUrl", "");

    public static final ModConfigSpec.ConfigValue<String> DISCORD_NOTIFY_SCOPE = BUILDER
            .comment("Which milestones to send to Discord: \"all\" or \"world_first\".")
            .define("discordNotifyScope", "all", Config::validateDiscordScope);

    public static final ModConfigSpec.BooleanValue SHOW_FIRST_JOIN_HINT = BUILDER
            .comment("Show a one-time chat tip when a player joins for the first time.")
            .define("showFirstJoinHint", true);

    public static final ModConfigSpec.BooleanValue PRESETS_ENABLED = BUILDER
            .comment("Enable preset commands and preset management in the GUI (existing preset files are never deleted).")
            .define("presetsEnabled", true);

    public static final ModConfigSpec.BooleanValue STRICT_PRESET_VALIDATION = BUILDER
            .comment("Reject a config-folder or datapack preset entirely when any element fails validation, instead of skipping only the invalid elements.",
                    "Includes resolution failures (missing preset ID or cycle) and invalid groups also reject the whole preset.",
                    "Built-in presets are never rejected. A rejected preset is logged as an error and treated as unknown (not listed, applied, or auto-applied).")
            .define("strictPresetValidation", false);

    public static final ModConfigSpec.BooleanValue EXPORT_IMPORT_ENABLED = BUILDER
            .comment("Enable milestone export and import commands (applies to OPs as well).")
            .define("exportImportEnabled", true);

    public static final ModConfigSpec.BooleanValue WORLD_FIRST_ENABLED = BUILDER
            .comment("Record new server-wide world-first milestones (existing world-first records are kept and viewable).")
            .define("worldFirstEnabled", true);

    public static final ModConfigSpec.ConfigValue<String> DISPLAY_TIME_ZONE = BUILDER
            .comment("Timezone ID used for server-formatted dates (chat, server log, Discord, exports).",
                    "Leave empty to use the server's default timezone. Examples: \"UTC\", \"Asia/Tokyo\", \"+09:00\".",
                    "The client GUI always uses the player's local timezone.")
            .define("displayTimeZone", "", Config::validateDisplayTimeZone);

    public static final ModConfigSpec.BooleanValue PROGRESS_ALERTS = BUILDER
            .comment("Notify the player in chat when a quantity target crosses a progress threshold (independent of notifyPlayer).")
            .define("progressAlerts", true);

    public static final ModConfigSpec.ConfigValue<List<? extends Integer>> PROGRESS_ALERT_THRESHOLDS = BUILDER
            .comment("Percentage thresholds (1-99) at which to send progress alerts for quantity targets.",
                    "Duplicates are ignored and values are evaluated in ascending order. Leave empty to disable.")
            .defineListAllowEmpty("progressAlertThresholds", List.of(50, 90), () -> 50, Config::validateProgressThreshold);

    public static final ModConfigSpec.IntValue PROGRESS_ALERT_MIN_COUNT = BUILDER
            .comment("Minimum target quantity required for progress alerts (2-1000000).")
            .defineInRange("progressAlertMinCount", ProgressAlertLogic.DEFAULT_MIN_COUNT,
                    ProgressAlertLogic.MIN_ALERT_COUNT_LOWER, TargetKey.MAX_COUNT);

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

    /** N64/N116: server default destination for progress alerts; personal choice can override it. */
    public static final ModConfigSpec.ConfigValue<String> PROGRESS_ALERT_DESTINATION = BUILDER
            .comment("Where progress alerts are shown: \"chat\" / \"actionbar\" / \"both\" / \"off\".",
                    "Players can override this with /milestonelog progressdest; \"server\" is personal-only.")
            .define("progressAlertDestination", "chat", Config::validateProgressAlertDestination);

    public static final ModConfigSpec.BooleanValue DISCORD_USE_EMBEDS = BUILDER
            .comment("Send Discord milestone notifications as rich embeds instead of plain text.",
                    "Set to false to keep the plain-text format.")
            .define("discordUseEmbeds", false);

    public static final ModConfigSpec.ConfigValue<String> DISCORD_LANGUAGE = BUILDER
            .comment("Language for Discord webhook notifications (\"en_us\" or \"ja_jp\").")
            .define("discordLanguage", "en_us", Config::validateDiscordLanguage);

    public static final ModConfigSpec.BooleanValue ALLOW_DISCORD_OPT_OUT = BUILDER
            .comment("Allow players to opt out of having their milestones posted to Discord via /milestonelog discordoptout.",
                    "When false, the command is disabled and saved opt-out preferences are ignored (but preserved).")
            .define("allowDiscordOptOut", true);

    public static final ModConfigSpec.BooleanValue ALLOW_LOOKUP = BUILDER
            .comment("Allow every player to view other players' records with /milestonelog lookup <player>.",
                    "When false, only operators (and the singleplayer/LAN host) can look up other players; looking up yourself always works.")
            .define("allowLookup", true);

    public static final ModConfigSpec.BooleanValue ANNOUNCE_PRESET_COMPLETION = BUILDER
            .comment("Announce to all online players when someone completes all targets of a preset.",
                    "When false, only the completing player is notified (if notifyPlayer is enabled).")
            .define("announcePresetCompletion", false);

    public static final ModConfigSpec.BooleanValue DISCORD_NOTIFY_PRESET_COMPLETION = BUILDER
            .comment("Post preset completions to Discord when a webhook URL is configured.",
                    "Independent of announcePresetCompletion and discordNotifyScope.")
            .define("discordNotifyPresetCompletion", false);

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

    /** F-150-45 (N13): separate preset-completion command template (empty = disabled). */
    public static final ModConfigSpec.ConfigValue<String> PRESET_COMPLETE_COMMAND = BUILDER
            .comment("Optional server command to run when a player completes all targets of a preset (empty = disabled).",
                    "Independent of milestoneCommand. Placeholders: %player%, %uuid%, %preset%, %builtin%.",
                    "Security note: only server owners can edit this config file; player and preset IDs are validated before substitution.")
            .define("presetCompleteCommand", "");

    /** F-150-46 (N109): separate world-first command template (empty = disabled). */
    public static final ModConfigSpec.ConfigValue<String> WORLD_FIRST_COMMAND = BUILDER
            .comment("Optional server command to run when a player records a world-first milestone (empty = disabled).",
                    "Independent of milestoneCommand; runs in addition to it. Same placeholders as milestoneCommand.",
                    "Not gated by announceWorldFirst, muteBroadcasts, or Discord opt-out.")
            .define("worldFirstCommand", "");

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

    /** F-150-64 (N115): cache received sync snapshots locally for offline viewing (client display only). */
    public static final ModConfigSpec.BooleanValue LOCAL_CACHE_ENABLED = BUILDER
            .comment("Cache the last received milestone sync snapshot on this client for offline viewing.",
                    "Local display only: never sent to the server and never written to SavedData.",
                    "Cached data is always labelled as an offline view.")
            .define("localCacheEnabled", true);

    /** F-150-67 (N119): keep rotating local backups of the client cache. */
    public static final ModConfigSpec.BooleanValue CLIENT_BACKUP_ENABLED = BUILDER
            .comment("Keep local backups of this client's milestone cache on disconnect and when connecting to another server.",
                    "Local display only: backups are stored under config/itemmilestonelog-client/backups/.")
            .define("clientBackupEnabled", true);

    /** F-150-67 (N119): number of client cache backup generations to keep (1-10). */
    public static final ModConfigSpec.IntValue CLIENT_BACKUP_GENERATIONS = BUILDER
            .comment("Number of local client cache backups to keep (1-10).")
            .defineInRange("clientBackupGenerations", ClientBackupRotation.DEFAULT_GENERATIONS,
                    ClientBackupRotation.MIN_GENERATIONS, ClientBackupRotation.MAX_GENERATIONS);

    /** F-150-85 (N198, PR-1 config only): export local data once on disconnect. */
    public static final ModConfigSpec.BooleanValue EXIT_AUTO_EXPORT = BUILDER
            .comment("Write one local export when disconnecting from a server (client display only).",
                    "The export file writer itself ships in a later client PR; this key only records the preference.")
            .define("exitAutoExport", false);

    /** F-150-86 (N199, PR-1 config only): reduce client-side refresh and cosmetic frequency. */
    public static final ModConfigSpec.BooleanValue LOW_POWER_MODE = BUILDER
            .comment("Reduce client-side refresh frequency and disable cosmetic effects.",
                    "Displayed information and button layout are unchanged; no server setting is affected.")
            .define("lowPowerMode", false);

    /** F-150-59 (N29): use the last tooltip stack as a hover target in other mods' GUIs. */
    public static final ModConfigSpec.BooleanValue HOVER_MOD_INTEGRATION = BUILDER
            .comment("Allow the hovered-item key to use the last item shown in a tooltip from another mod (e.g. JEI/EMI/REI).",
                    "Those mods are detected implicitly and are never required; local display behaviour only.")
            .define("hoverModIntegration", true);

    /** F-150-63 (N25): draw a lightweight HUD of locally pinned targets (default OFF). */
    public static final ModConfigSpec.BooleanValue PIN_HUD_ENABLED = BUILDER
            .comment("Draw a lightweight HUD of locally pinned milestone targets.",
                    "Client-side display only: pins are local and are never sent to the server.",
                    "Off by default so existing players see no new overlay.")
            .define("pinHudEnabled", false);

    /** F-150-63 (N25): maximum number of pinned HUD rows (1-10, default 5). */
    public static final ModConfigSpec.IntValue PIN_HUD_MAX_ROWS = BUILDER
            .comment("Maximum number of pinned targets shown in the HUD (1-10).")
            .defineInRange("pinHudMaxRows", HudRowPlan.DEFAULT_MAX_ROWS, 1, HudRowPlan.MAX_ROWS);

    /** F-150-80 (N193): which corner anchors the independent HUD overlay. */
    public static final ModConfigSpec.ConfigValue<String> PIN_HUD_ANCHOR = BUILDER
            .comment("HUD anchor: top-left, top-right, bottom-left or bottom-right (default top-left).",
                    "Only the new overlay moves; existing screens and widgets are unchanged.")
            .define("pinHudAnchor", DisplayOptions.DEFAULT_PIN_HUD_ANCHOR, Config::validatePinHudAnchor);

    /** F-150-80 (N193): fade the HUD for three seconds after damage, chat or a screen. */
    public static final ModConfigSpec.BooleanValue PIN_HUD_AUTO_HIDE = BUILDER
            .comment("Fade the pinned-target HUD for three seconds after damage, chat input or a screen opening.")
            .define("pinHudAutoHide", DisplayOptions.DEFAULT_PIN_HUD_AUTO_HIDE);

    /** F-150-80 (N193): font matrix scale of the HUD in percent (50-200). */
    public static final ModConfigSpec.IntValue PIN_HUD_SCALE = BUILDER
            .comment("Font scale of the pinned-target HUD in percent (50-200). Widgets are not resized.",
                    "Real high-DPI appearance is unverified and recorded as such in the PR report.")
            .defineInRange("pinHudScale", HudLayoutOptions.DEFAULT_SCALE,
                    HudLayoutOptions.MIN_SCALE, HudLayoutOptions.MAX_SCALE);

    /** F-150-82 (N195, HUD part): narrate HUD rows through the standard narration API. */
    public static final ModConfigSpec.BooleanValue ACCESSIBLE_NARRATION = BUILDER
            .comment("Include pinned-target HUD rows in Minecraft's standard narration.")
            .define("accessibleNarration", true);

    /** F-150-82 (N195, HUD part): white-on-black HUD text. */
    public static final ModConfigSpec.BooleanValue HUD_HIGH_CONTRAST = BUILDER
            .comment("Draw pinned-target HUD rows in white on black for higher contrast.")
            .define("hudHighContrast", false);

    /** F-150-82 (N195, HUD part): skip the fade without removing information. */
    public static final ModConfigSpec.BooleanValue REDUCED_MOTION = BUILDER
            .comment("Skip the pinned-target HUD fade so it appears and disappears immediately.",
                    "Displayed information is never reduced.")
            .define("reducedMotion", false);

    static final ModConfigSpec SPEC = BUILDER.build();

    private record ThresholdsCache(List<? extends Integer> source, List<Integer> normalized) {
    }

    private static volatile ThresholdsCache progressAlertThresholdsCache = new ThresholdsCache(null, List.of());

    private Config() {
    }

    static boolean validatePinHudAnchor(Object obj) {
        if (!(obj instanceof String value)) {
            return false;
        }
        for (HudLayoutOptions.Anchor anchor : HudLayoutOptions.Anchor.values()) {
            if (anchor.configName().equalsIgnoreCase(value.trim())) {
                return true;
            }
        }
        return false;
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
     * F-150-45 (N13): {@link #PRESET_COMPLETE_COMMAND}, or {@code ""} while the config is not
     * loaded yet (plain unit tests).
     */
    public static String presetCompleteCommand() {
        try {
            String cmd = PRESET_COMPLETE_COMMAND.get();
            return cmd != null ? cmd : "";
        } catch (IllegalStateException notLoaded) {
            return "";
        }
    }

    /**
     * F-150-46 (N109): {@link #WORLD_FIRST_COMMAND}, or {@code ""} while the config is not loaded
     * yet (plain unit tests).
     */
    public static String worldFirstCommand() {
        try {
            String cmd = WORLD_FIRST_COMMAND.get();
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
     * N64: {@link #PROGRESS_ALERT_DESTINATION}, or the 1.4.0 chat default while the config is not
     * loaded yet (plain unit tests). Invalid values fall back once with a warning in the parser.
     */
    /** F-150-47 (N15): per-event sound ID, or {@code ""} before the config is loaded. */
    public static String worldFirstSound() {
        try {
            String id = WORLD_FIRST_SOUND.get();
            return id != null ? id : "";
        } catch (IllegalStateException notLoaded) {
            return "";
        }
    }

    public static String presetCompleteSound() {
        try {
            String id = PRESET_COMPLETE_SOUND.get();
            return id != null ? id : "";
        } catch (IllegalStateException notLoaded) {
            return "";
        }
    }

    public static String progressAlertSound() {
        try {
            String id = PROGRESS_ALERT_SOUND.get();
            return id != null ? id : "";
        } catch (IllegalStateException notLoaded) {
            return "";
        }
    }

    /** F-150-48 (N16): opt-in world-first announcement sound; default false before config load. */
    public static boolean announceWorldFirstSound() {
        try {
            return ANNOUNCE_WORLD_FIRST_SOUND.get();
        } catch (IllegalStateException notLoaded) {
            return false;
        }
    }

    /** F-150-49 (N114): server default volume, or 100 before the config is loaded. */
    public static int notificationSoundVolume() {
        try {
            return SoundVolumeLogic.normalizeServer(NOTIFICATION_SOUND_VOLUME.get());
        } catch (IllegalStateException notLoaded) {
            return SoundVolumeLogic.DEFAULT_SERVER_VOLUME;
        }
    }

    public static NotificationDestinationLogic.Channel progressAlertDestination() {
        try {
            return NotificationDestinationLogic.parseChannel(PROGRESS_ALERT_DESTINATION.get());
        } catch (IllegalStateException notLoaded) {
            return NotificationDestinationLogic.Channel.CHAT;
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
     * F-150-59: {@link #HOVER_MOD_INTEGRATION}, or {@code true} while the config is not loaded yet
     * (plain unit tests). This is a client-side preference and never reads a server value.
     */
    public static boolean hoverModIntegration() {
        try {
            return HOVER_MOD_INTEGRATION.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }

    /** F-150-63: local HUD toggle, {@code false} while the config is not loaded. */
    public static boolean pinHudEnabled() {
        try {
            return PIN_HUD_ENABLED.get();
        } catch (IllegalStateException notLoaded) {
            return false;
        }
    }

    /** F-150-63: row limit, five while the config is not loaded. */
    public static int pinHudMaxRows() {
        try {
            return PIN_HUD_MAX_ROWS.get();
        } catch (IllegalStateException notLoaded) {
            return HudRowPlan.DEFAULT_MAX_ROWS;
        }
    }

    /** F-150-80: HUD anchor, {@code top-left} while the config is not loaded. */
    public static String pinHudAnchor() {
        try {
            return PIN_HUD_ANCHOR.get();
        } catch (IllegalStateException notLoaded) {
            return DisplayOptions.DEFAULT_PIN_HUD_ANCHOR;
        }
    }

    /** F-150-80: auto-hide, {@code true} while the config is not loaded. */
    public static boolean pinHudAutoHide() {
        try {
            return PIN_HUD_AUTO_HIDE.get();
        } catch (IllegalStateException notLoaded) {
            return DisplayOptions.DEFAULT_PIN_HUD_AUTO_HIDE;
        }
    }

    /** F-150-80: font scale, 100 while the config is not loaded. */
    public static int pinHudScale() {
        try {
            return PIN_HUD_SCALE.get();
        } catch (IllegalStateException notLoaded) {
            return HudLayoutOptions.DEFAULT_SCALE;
        }
    }

    /** F-150-82: narration, {@code true} while the config is not loaded. */
    public static boolean accessibleNarration() {
        try {
            return ACCESSIBLE_NARRATION.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }

    /** F-150-82: high contrast, {@code false} while the config is not loaded. */
    public static boolean hudHighContrast() {
        try {
            return HUD_HIGH_CONTRAST.get();
        } catch (IllegalStateException notLoaded) {
            return false;
        }
    }

    /** F-150-82: reduced motion, {@code false} while the config is not loaded. */
    public static boolean reducedMotion() {
        try {
            return REDUCED_MOTION.get();
        } catch (IllegalStateException notLoaded) {
            return false;
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

    /** F-150-47: empty means "reuse milestoneSound"; otherwise reuse the existing ID validation. */
    private static boolean validateOptionalSoundId(Object obj) {
        return obj instanceof String s && (s.isEmpty() || validateSoundId(s));
    }

    private static boolean validateSoundId(Object obj) {
        return obj instanceof String s && !s.isBlank() && Identifier.tryParse(s) != null;
    }

    private static boolean validateDiscordScope(Object obj) {
        return obj instanceof String s && ("all".equals(s) || "world_first".equals(s));
    }

    private static boolean validateProgressAlertDestination(Object obj) {
        return obj instanceof String s && NotificationDestinationLogic.isValidChannel(s);
    }

    static boolean validateDiscordLanguage(Object obj) {
        return obj instanceof String s && ServerLang.BUNDLED_LANGUAGES.contains(s);
    }

    static boolean validateFirstJoinPreset(Object obj) {
        return obj instanceof String s && Presets.isValidId(s);
    }
}
