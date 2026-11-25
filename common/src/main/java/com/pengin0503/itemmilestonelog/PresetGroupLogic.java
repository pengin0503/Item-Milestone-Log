package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.TargetKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure-Java validation of preset {@code groups} against the preset's <em>expanded</em> items
 * (spec 1.4.0 F-140-10 / N106, B2-D6), shared across all Minecraft versions.
 *
 * <p>Rules, in order:
 * <ul>
 *   <li>a group whose ID was already used is dropped with a warning (the first group wins),</li>
 *   <li>a group item that is not in the expanded items is dropped with a warning,</li>
 *   <li>an item that already belongs to an earlier group is dropped from the later group with
 *       a warning (the first group wins),</li>
 *   <li>a group that has no items left is dropped with a warning.</li>
 * </ul>
 * Items that are not in any group are simply "no group". Completion judgment is unchanged.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class PresetGroupLogic {
    /**
     * Result of one groups validation.
     *
     * @param groups   surviving groups in their original order
     * @param warnings human-readable warnings for dropped groups and items
     */
    public record Result(List<PresetGroup> groups, List<String> warnings) {
        public Result {
            groups = groups == null ? List.of() : List.copyOf(groups);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    private PresetGroupLogic() {
    }

    /** Validates {@code groups} against {@code expandedItems} (the preset's final item list). */
    public static Result validate(List<PresetGroup> groups, List<TargetKey> expandedItems) {
        if (groups == null || groups.isEmpty()) {
            return new Result(List.of(), List.of());
        }
        Set<TargetKey> available = new LinkedHashSet<>();
        if (expandedItems != null) {
            for (TargetKey key : expandedItems) {
                if (key != null) {
                    available.add(key);
                }
            }
        }

        List<String> warnings = new ArrayList<>();
        List<PresetGroup> surviving = new ArrayList<>();
        Set<String> usedGroupIds = new LinkedHashSet<>();
        Map<TargetKey, String> claimedByGroup = new LinkedHashMap<>();

        for (PresetGroup group : groups) {
            if (group == null) {
                continue;
            }
            String groupId = group.id();
            if (groupId == null || groupId.isBlank()) {
                warnings.add("group with a blank id dropped");
                continue;
            }
            if (!usedGroupIds.add(groupId)) {
                warnings.add("duplicate group id '" + groupId + "' dropped (the first definition wins)");
                continue;
            }

            List<TargetKey> kept = new ArrayList<>();
            Set<TargetKey> seenInGroup = new LinkedHashSet<>();
            for (TargetKey key : group.items()) {
                if (key == null) {
                    continue;
                }
                if (!available.contains(key)) {
                    warnings.add("group '" + groupId + "' references '" + describe(key)
                            + "', which is not in the preset items; dropping it");
                    continue;
                }
                if (!seenInGroup.add(key)) {
                    continue;
                }
                String owner = claimedByGroup.get(key);
                if (owner != null) {
                    warnings.add("item '" + describe(key) + "' already belongs to group '" + owner
                            + "'; dropping it from group '" + groupId + "'");
                    continue;
                }
                claimedByGroup.put(key, groupId);
                kept.add(key);
            }
            if (kept.isEmpty()) {
                warnings.add("group '" + groupId + "' has no valid items after validation; dropping it");
                continue;
            }
            surviving.add(new PresetGroup(groupId, group.name(), Collections.unmodifiableList(kept)));
        }
        return new Result(surviving, warnings);
    }

    private static String describe(TargetKey key) {
        return key.count() > TargetKey.MIN_COUNT ? key.itemId() + "#" + key.count() : key.itemId();
    }
}
