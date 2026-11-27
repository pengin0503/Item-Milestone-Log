package com.pengin0503.itemmilestonelog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * F-150-40 (N178): pure rules for in-game custom-preset editing and protection.
 *
 * <p>Items are handled as encoded target strings ({@code "minecraft:diamond"} or
 * {@code "minecraft:diamond#3"}) so this class stays Java 21 / Minecraft free; the command
 * trees convert to and from their own {@code TargetKey}. The insertion-order / de-duplication
 * rule mirrors {@code PresetDefinition.items()} (F-140-6). Editing is allowed only for a
 * config-folder preset that is not protected; built-in and datapack presets stay read-only.
 */
public final class PresetEditLogic {
    /** Where a preset was loaded from; only {@link #FILE} can be edited from chat. */
    public enum Kind {
        BUILTIN,
        FILE,
        DATAPACK
    }

    private PresetEditLogic() {
    }

    /** Whether the given preset may be edited. Protection and source kind are both enforced. */
    public static boolean canEdit(Kind kind, boolean isProtected) {
        return kind == Kind.FILE && !isProtected;
    }

    /**
     * Appends an encoded item to an insertion-ordered list, de-duplicating exact repeats.
     * Never mutates {@code items}; returns an immutable list.
     */
    public static List<String> addEncoded(List<String> items, String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return items == null ? List.of() : List.copyOf(items);
        }
        Set<String> ordered = new LinkedHashSet<>(items == null ? List.of() : items);
        ordered.add(encoded);
        return List.copyOf(ordered);
    }

    /** Removes one exact encoded item. Never mutates {@code items}; returns an immutable list. */
    public static List<String> removeEncoded(List<String> items, String encoded) {
        List<String> base = items == null ? List.of() : items;
        if (encoded == null || encoded.isBlank()) {
            return List.copyOf(base);
        }
        List<String> result = new ArrayList<>(base.size());
        for (String item : base) {
            if (!encoded.equals(item)) {
                result.add(item);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Removes every entry of {@code itemId} regardless of its count. Used when
     * {@code edit remove <item>} is issued without an explicit count.
     */
    public static List<String> removeItemId(List<String> items, String itemId) {
        List<String> base = items == null ? List.of() : items;
        if (itemId == null || itemId.isBlank()) {
            return List.copyOf(base);
        }
        List<String> result = new ArrayList<>(base.size());
        for (String item : base) {
            if (!itemId.equals(keyItemId(item))) {
                result.add(item);
            }
        }
        return List.copyOf(result);
    }

    /** The item-id portion of an encoded target key (the part before {@code #count}). */
    private static String keyItemId(String encoded) {
        if (encoded == null) {
            return "";
        }
        int hash = encoded.lastIndexOf('#');
        return hash < 0 ? encoded : encoded.substring(0, hash);
    }
}
