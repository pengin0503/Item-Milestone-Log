package com.pengin0503.itemmilestonelog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * F42: pure item-tooltip index and line selection for milestone status.
 *
 * <p>The client rebuilds the index from each {@code SyncMilestonesPayload} (effective targets plus
 * the player's own records) and then only looks up the hovered item ID while a tooltip is built.
 * Tag targets are expanded to their member item IDs with the client-side {@link TagIndex}, so a
 * tag target produces a line for every item it contains.
 *
 * <p>The returned {@link Status} entries and {@link Lines} carry no Minecraft types: the
 * version-specific client turns them into translatable {@code Component}s, using the F48
 * client-side playtime format. This keeps the selection rules unit-testable without Minecraft.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class ItemTooltipLogic {
    /** Maximum number of status lines shown before {@code item_tooltip.more}. */
    public static final int MAX_LINES = 3;

    private static final Comparator<Status> TARGET_KEY_ORDER =
            Comparator.comparing(Status::itemId).thenComparingInt(Status::count);

    private ItemTooltipLogic() {
    }

    /**
     * One key that should produce a tooltip line for an item.
     *
     * @param itemId   item ID ({@code namespace:path}) or tag ID ({@code #namespace:path})
     * @param count    target quantity (1-1,000,000)
     * @param target   whether the key is one of the player's effective targets
     * @param recorded whether the player has a record for the key
     * @param playTicks recorded playtime in ticks, or 0 when {@code recorded} is false
     */
    public record Status(String itemId, int count, boolean target, boolean recorded, int playTicks) {
        public Status {
            Objects.requireNonNull(itemId, "itemId");
            if (itemId.isEmpty()) {
                throw new IllegalArgumentException("itemId must not be empty");
            }
            if (count < 1) {
                throw new IllegalArgumentException("count must be at least 1: " + count);
            }
        }

        /** An effective target without a record (F42 "Milestone target"). */
        public static Status target(String itemId, int count) {
            return new Status(itemId, count, true, false, 0);
        }

        /** A recorded key, even when it is no longer one of the player's targets. */
        public static Status recorded(String itemId, int count, int playTicks) {
            return new Status(itemId, count, false, true, Math.max(0, playTicks));
        }

        /** Whether {@link #itemId()} is a tag ID ({@code '#'}-prefixed). */
        public boolean isTag() {
            return itemId.charAt(0) == '#';
        }

        private Status merge(Status other) {
            boolean mergedRecorded = recorded || other.recorded;
            Status recordSource = recorded ? this : other;
            return new Status(itemId, count, target || other.target, mergedRecorded,
                    mergedRecorded ? recordSource.playTicks : 0);
        }
    }

    /** The lines to show for one hovered item and the number of keys hidden by the cap. */
    public record Lines(List<Status> shown, int remaining) {
        public static final Lines EMPTY = new Lines(List.of(), 0);

        public Lines {
            shown = List.copyOf(shown);
            if (remaining < 0) {
                throw new IllegalArgumentException("remaining must not be negative: " + remaining);
            }
        }

        public boolean isEmpty() {
            return shown.isEmpty();
        }
    }

    private record Key(String itemId, int count) {
    }

    /**
     * Builds the {@code itemId -> ordered Status list} lookup used by the tooltip handler.
     *
     * <p>Target and record entries for the same {@link Key} are merged (a record wins the line
     * type). Tag entries are added to every member item of the tag in {@code tags}; unknown or
     * empty tags add nothing. Each per-item list is sorted in {@code TargetKey} order
     * (item/tag ID, then count).
     */
    public static Map<String, List<Status>> buildIndex(Collection<Status> statuses, TagIndex tags) {
        if (statuses == null || statuses.isEmpty()) {
            return Map.of();
        }
        TagIndex effectiveTags = tags != null ? tags : TagIndex.EMPTY;

        Map<Key, Status> merged = new LinkedHashMap<>();
        for (Status status : statuses) {
            if (status == null) {
                continue;
            }
            Key key = new Key(status.itemId(), status.count());
            Status previous = merged.get(key);
            merged.put(key, previous == null ? status : previous.merge(status));
        }

        Map<String, List<Status>> byItem = new HashMap<>();
        for (Status status : merged.values()) {
            if (status.isTag()) {
                for (String member : effectiveTags.members(status.itemId())) {
                    byItem.computeIfAbsent(member, ignored -> new ArrayList<>()).add(status);
                }
            } else {
                byItem.computeIfAbsent(status.itemId(), ignored -> new ArrayList<>()).add(status);
            }
        }

        Map<String, List<Status>> result = new HashMap<>(byItem.size());
        for (Map.Entry<String, List<Status>> entry : byItem.entrySet()) {
            entry.getValue().sort(TARGET_KEY_ORDER);
            result.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    /** Returns at most {@link #MAX_LINES} statuses for {@code itemId}, plus the hidden count. */
    public static Lines linesForItem(Map<String, List<Status>> index, String itemId) {
        if (index == null || itemId == null) {
            return Lines.EMPTY;
        }
        List<Status> statuses = index.get(itemId);
        if (statuses == null || statuses.isEmpty()) {
            return Lines.EMPTY;
        }
        if (statuses.size() <= MAX_LINES) {
            return new Lines(statuses, 0);
        }
        return new Lines(statuses.subList(0, MAX_LINES), statuses.size() - MAX_LINES);
    }

    /**
     * F-150-68 / F-150-79: applies the client-local display options to F42's already capped
     * {@link Lines} without changing {@link #linesForItem} or any other existing signature (D-150-197).
     *
     * <p>Only {@code shown} entries can be removed: statuses hidden by F42's own
     * {@code remaining()} cap can never be revealed by a larger {@code tooltipMaxLines}. Filtering
     * and the new cap increase {@code remaining()} by exactly the number of entries they hide, so
     * the "and N more" line keeps counting every hidden status. Sorting is stable, so the relative
     * order inside one direction is preserved. {@link TooltipOptions#DEFAULT} returns a value equal
     * to its input, byte for byte.
     */
    public static Lines refineLines(Lines lines, TooltipOptions options) {
        if (lines == null || lines.isEmpty()) {
            return Lines.EMPTY;
        }
        TooltipOptions effective = options == null ? TooltipOptions.DEFAULT : options;
        List<Status> kept = new ArrayList<>(lines.shown().size());
        for (Status status : lines.shown()) {
            if ((status.target() && effective.showTarget())
                    || (status.recorded() && effective.showRecorded())) {
                kept.add(status);
            }
        }
        if (effective.sort() == TooltipOptions.Sort.TARGET_FIRST) {
            kept.sort(Comparator.comparingInt(status -> status.target() ? 0 : 1));
        } else if (effective.sort() == TooltipOptions.Sort.RECORDED_FIRST) {
            kept.sort(Comparator.comparingInt(status -> status.recorded() ? 0 : 1));
        }
        int cap = Math.min(effective.maxLines(), lines.shown().size());
        List<Status> shown = kept.size() > cap ? kept.subList(0, cap) : kept;
        int hidden = lines.shown().size() - shown.size();
        return new Lines(shown, lines.remaining() + hidden);
    }

    /** Count suffix shared with {@code TargetKey.formatSuffix()}: empty for 1, {@code " ×N"} otherwise. */
    public static String countSuffix(int count) {
        return count > 1 ? " \u00d7" + count : "";
    }
}
