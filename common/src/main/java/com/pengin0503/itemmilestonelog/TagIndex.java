package com.pengin0503.itemmilestonelog;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * F8b: immutable snapshot of item tag membership used to detect, count, and display tag targets.
 *
 * <p>Tag keys are stored in the {@code TargetKey} form with the leading {@code '#'}
 * (e.g. {@code "#c:ingots/iron"}); members are item IDs ({@code "minecraft:iron_ingot"}).
 * Only non-empty tags are present: a tag that exists but has no items is indistinguishable from
 * an unknown tag, which matches the F8b rule that both are rejected by {@code addtag} and
 * counted as possession 0.
 *
 * <p>Built by the version-specific {@code TagTargets} from {@code BuiltInRegistries.ITEM} at
 * startup and on every {@code TagsUpdatedEvent}, so scans never re-resolve tags.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class TagIndex {
    /** Index with no tags (before the registry tags are bound, or in unit tests). */
    public static final TagIndex EMPTY = new TagIndex(Map.of(), Map.of());

    private final Map<String, Set<String>> membersByTag;
    private final Map<String, Set<String>> tagsByItem;

    private TagIndex(Map<String, Set<String>> membersByTag, Map<String, Set<String>> tagsByItem) {
        this.membersByTag = membersByTag;
        this.tagsByItem = tagsByItem;
    }

    /**
     * Builds an index from {@code tag -> member item IDs}. Tag keys without the leading {@code '#'}
     * are prefixed; {@code null}/blank keys and members are ignored, and tags left without members
     * are dropped. Member iteration order is preserved (the first member is used as the GUI icon).
     */
    public static TagIndex of(Map<String, ? extends Collection<String>> tagToItems) {
        Objects.requireNonNull(tagToItems, "tagToItems");
        Map<String, Set<String>> members = new LinkedHashMap<>();
        Map<String, Set<String>> byItem = new HashMap<>();
        for (Map.Entry<String, ? extends Collection<String>> e : tagToItems.entrySet()) {
            String tag = e.getKey();
            if (tag == null || tag.isBlank() || e.getValue() == null) {
                continue;
            }
            String key = tag.charAt(0) == '#' ? tag : "#" + tag;
            Set<String> set = new LinkedHashSet<>();
            for (String item : e.getValue()) {
                if (item != null && !item.isBlank()) {
                    set.add(item);
                }
            }
            if (set.isEmpty()) {
                continue;
            }
            Set<String> existing = members.get(key);
            if (existing != null) {
                existing.addAll(set);
            } else {
                members.put(key, set);
            }
        }
        Map<String, Set<String>> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : members.entrySet()) {
            Set<String> set = Collections.unmodifiableSet(e.getValue());
            frozen.put(e.getKey(), set);
            for (String item : set) {
                byItem.computeIfAbsent(item, k -> new TreeSet<>()).add(e.getKey());
            }
        }
        Map<String, Set<String>> frozenByItem = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : byItem.entrySet()) {
            frozenByItem.put(e.getKey(), Collections.unmodifiableSet(e.getValue()));
        }
        return new TagIndex(Collections.unmodifiableMap(frozen), Collections.unmodifiableMap(frozenByItem));
    }

    /** Whether {@code tagKey} ({@code "#ns:path"}) exists and has at least one item. */
    public boolean isNonEmptyTag(String tagKey) {
        return tagKey != null && membersByTag.containsKey(tagKey);
    }

    /** Member item IDs of {@code tagKey} ({@code "#ns:path"}), or an empty set if unknown/empty. */
    public Set<String> members(String tagKey) {
        if (tagKey == null) {
            return Set.of();
        }
        Set<String> set = membersByTag.get(tagKey);
        return set != null ? set : Set.of();
    }

    /** First member item ID of {@code tagKey} (used as the GUI icon), or {@code null}. */
    public String firstMember(String tagKey) {
        Set<String> set = members(tagKey);
        return set.isEmpty() ? null : set.iterator().next();
    }

    /** Tag keys ({@code "#ns:path"}, sorted) that contain {@code itemId}. */
    public Set<String> tagsContaining(String itemId) {
        if (itemId == null) {
            return Set.of();
        }
        Set<String> set = tagsByItem.get(itemId);
        return set != null ? set : Set.of();
    }

    /** All non-empty tag keys ({@code "#ns:path"}), sorted. */
    public Set<String> tagKeys() {
        return Collections.unmodifiableSet(new TreeSet<>(membersByTag.keySet()));
    }

    /** Number of non-empty tags. */
    public int size() {
        return membersByTag.size();
    }

    /** Whether any member of {@code tagKey} is in {@code itemIds} (e.g. F50 {@code carriedFromCreative}). */
    public boolean anyMemberIn(String tagKey, Set<String> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return false;
        }
        for (String member : members(tagKey)) {
            if (itemIds.contains(member)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Expands requested target IDs into the concrete item IDs whose stacks must be counted:
     * non-tag IDs are kept as-is and each tag ({@code '#'}-prefixed) is replaced by its members
     * that are not in {@code excludedItems} (F8b: creative-carried items never count toward a tag).
     */
    public Set<String> expandForCounting(Collection<String> requestedIds, Set<String> excludedItems) {
        Set<String> result = new LinkedHashSet<>();
        if (requestedIds == null) {
            return result;
        }
        for (String id : requestedIds) {
            if (id == null || id.isEmpty()) {
                continue;
            }
            if (id.charAt(0) == '#') {
                for (String member : members(id)) {
                    if (excludedItems == null || !excludedItems.contains(member)) {
                        result.add(member);
                    }
                }
            } else {
                result.add(id);
            }
        }
        return result;
    }

    /**
     * Returns a copy of {@code itemCounts} plus one entry per requested tag ID holding the sum of
     * its members' counts, skipping members in {@code excludedItems}. Tags with a total of 0 are
     * omitted (callers use {@code getOrDefault(id, 0)}).
     */
    public Map<String, Integer> withTagTotals(Map<String, Integer> itemCounts,
                                              Collection<String> requestedIds,
                                              Set<String> excludedItems) {
        Map<String, Integer> result = new HashMap<>(itemCounts);
        if (requestedIds == null) {
            return result;
        }
        for (String id : requestedIds) {
            if (id == null || id.isEmpty() || id.charAt(0) != '#') {
                continue;
            }
            long total = 0;
            for (String member : members(id)) {
                if (excludedItems != null && excludedItems.contains(member)) {
                    continue;
                }
                total += itemCounts.getOrDefault(member, 0);
            }
            if (total > 0) {
                result.put(id, (int) Math.min(Integer.MAX_VALUE, total));
            } else {
                result.remove(id);
            }
        }
        return result;
    }
}
