package com.pengin0503.itemmilestonelog;

import java.util.List;
import java.util.Map;

/** Built-in target item presets that players can apply with one command. */
public final class Presets {
    public static final Map<String, List<String>> PRESETS = Map.of(
            "atm_star", List.of(
                    "allthetweaks:atm_star",
                    "allthetweaks:dragon_soul",
                    "allthetweaks:dimensional_seed",
                    "allthetweaks:improbable_probability_device",
                    "allthetweaks:nexium_emitter",
                    "allthetweaks:oblivion_shard",
                    "allthetweaks:patrick_star",
                    "allthetweaks:philosophers_fuel",
                    "allthetweaks:pulsating_black_hole",
                    "allthetweaks:withers_compass"
            ),
            "vanilla_endgame", List.of(
                    "minecraft:diamond",
                    "minecraft:netherite_ingot",
                    "minecraft:elytra",
                    "minecraft:nether_star",
                    "minecraft:beacon",
                    "minecraft:dragon_egg",
                    "minecraft:enchanted_golden_apple",
                    "minecraft:totem_of_undying"
            )
    );

    private static final List<String> SORTED_NAMES = PRESETS.keySet().stream().sorted().toList();

    private Presets() {
    }

    /** Preset names in a stable (alphabetical) order for suggestions, messages and the GUI. */
    public static List<String> names() {
        return SORTED_NAMES;
    }
}
