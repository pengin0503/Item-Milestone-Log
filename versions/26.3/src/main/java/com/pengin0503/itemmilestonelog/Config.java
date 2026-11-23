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

    public static final ModConfigSpec.BooleanValue ANNOUNCE_WORLD_FIRST = BUILDER
            .comment("Announce to all online players when someone records a world-first item in this world.")
            .define("announceWorldFirst", false);

    public static final ModConfigSpec.ConfigValue<String> DISCORD_WEBHOOK_URL = BUILDER
            .comment("Optional Discord webhook URL for milestone notifications. Leave empty to disable.",
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

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }

    static boolean validateTargetEntry(Object obj) {
        return obj instanceof String s && TargetKey.tryParseNormalized(s, true, true) != null;
    }

    static boolean validateDisplayTimeZone(Object obj) {
        return DisplayTimeZone.isValidConfigValue(obj);
    }

    /** Resolves {@link #DISPLAY_TIME_ZONE}, falling back to the server's default timezone when empty or invalid. */
    public static ZoneId displayTimeZone() {
        return DisplayTimeZone.resolve(DISPLAY_TIME_ZONE.get());
    }

    private static boolean validateSoundId(Object obj) {
        return obj instanceof String s && !s.isBlank() && Identifier.tryParse(s) != null;
    }

    private static boolean validateDiscordScope(Object obj) {
        return obj instanceof String s && ("all".equals(s) || "world_first".equals(s));
    }
}
