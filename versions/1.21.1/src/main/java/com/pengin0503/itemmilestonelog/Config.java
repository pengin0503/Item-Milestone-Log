package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.TargetKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

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

    /** Discord webhook URL for milestone notifications (empty = disabled; never logged). */
    public static final ModConfigSpec.ConfigValue<String> DISCORD_WEBHOOK_URL = BUILDER
            .comment("Optional Discord webhook URL for milestone notifications. Leave empty to disable.",
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

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }

    static boolean validateTargetEntry(Object obj) {
        if (!(obj instanceof String s)) {
            return false;
        }
        TargetKey parsed = TargetKey.tryParseEncoded(s);
        if (parsed == null || !parsed.itemId().contains(":")) {
            return false;
        }
        ResourceLocation rl = ResourceLocation.tryParse(parsed.itemId());
        return rl != null && !"minecraft:air".equals(rl.toString());
    }

    private static boolean validateSoundId(Object obj) {
        return obj instanceof String s && !s.isBlank() && ResourceLocation.tryParse(s) != null;
    }

    private static boolean validateDiscordScope(Object obj) {
        return obj instanceof String s && ("all".equals(s) || "world_first".equals(s));
    }
}
