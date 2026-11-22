package com.pengin0503.itemmilestonelog;

import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** Items every player tracks by default (in addition to their personal targets). */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DEFAULT_TARGETS = BUILDER
            .comment("Item IDs that every player tracks by default, e.g. [\"minecraft:netherite_ingot\"].",
                    "Players can add their own targets on top of these with /milestonelog add.")
            .defineListAllowEmpty("defaultTargets", List.of(), () -> "minecraft:diamond", Config::validateItemId);

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

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }

    private static boolean validateItemId(Object obj) {
        return obj instanceof String s && s.contains(":") && Identifier.tryParse(s) != null;
    }
}
