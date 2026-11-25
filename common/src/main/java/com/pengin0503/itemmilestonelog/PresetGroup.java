package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.TargetKey;

import java.util.List;

/**
 * One optional item group inside a preset definition (spec 1.4.0 F-140-10 / N106), shared
 * across all Minecraft versions.
 *
 * <p>Groups are definition data only in 1.4.0: they do not change completion judgment and
 * have no editing UI. {@link #items()} always holds a subset of the preset's expanded items
 * after {@link PresetGroupLogic#validate} has run.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 *
 * @param id    group ID, validated with the same format as preset IDs
 * @param name  optional display name (plain or per-language), absent when omitted
 * @param items item keys belonging to this group
 */
public record PresetGroup(String id, PresetName name, List<TargetKey> items) {
    public PresetGroup {
        name = name == null ? PresetName.absent() : name;
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** Convenience constructor for a group without a display name. */
    public PresetGroup(String id, List<TargetKey> items) {
        this(id, PresetName.absent(), items);
    }
}
