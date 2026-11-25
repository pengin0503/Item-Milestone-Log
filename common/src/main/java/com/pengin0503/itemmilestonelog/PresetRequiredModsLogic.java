package com.pengin0503.itemmilestonelog;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Pure-Java computation of missing {@code requiredMods} for a preset (spec 1.4.0 F-140-9 /
 * N101), shared across all Minecraft versions.
 *
 * <p>The version-specific loader API (NeoForge / Forge {@code ModList}) is supplied as a
 * predicate, so this class has no Minecraft dependency and stays unit-testable.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class PresetRequiredModsLogic {
    private PresetRequiredModsLogic() {
    }

    /**
     * Returns the distinct required mod IDs that are not loaded, in {@code requiredMods} order.
     * A {@code null} predicate means "nothing is loaded". Null/blank entries are ignored.
     */
    public static List<String> missingMods(Collection<String> requiredMods, Predicate<String> modLoaded) {
        if (requiredMods == null || requiredMods.isEmpty()) {
            return List.of();
        }
        Set<String> missing = new LinkedHashSet<>();
        for (String modId : requiredMods) {
            if (modId == null || modId.isBlank()) {
                continue;
            }
            if (modLoaded == null || !modLoaded.test(modId)) {
                missing.add(modId);
            }
        }
        return missing.isEmpty() ? List.of() : List.copyOf(missing);
    }
}
