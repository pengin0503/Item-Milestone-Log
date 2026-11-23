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
