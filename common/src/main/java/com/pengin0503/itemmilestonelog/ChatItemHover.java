package com.pengin0503.itemmilestonelog;

import java.util.function.Predicate;

/**
 * F12: decides which item the chat {@code show_item} hover of a target display name shows.
 *
 * <p>The version-specific {@code MilestoneService.targetDisplayName} turns the returned item ID into
 * the item's default stack (count 1, no data components) and attaches the hover; this class holds
 * the version-independent decision so it can be unit tested without Minecraft.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class ChatItemHover {
    private ChatItemHover() {
    }

    /**
     * Returns the item ID whose default stack should be shown when hovering {@code targetId} in chat,
     * or {@code null} for no hover.
     *
     * <ul>
     *   <li>{@code enabled == false} (Config {@code chatItemHover}) → {@code null}</li>
     *   <li>tag target ({@code '#'}-prefixed) → the registry-ID lexicographically first member from
     *       the F8b tag cache ({@link TagIndex#lexicographicFirstMember}); unknown/empty tag → {@code null}</li>
     *   <li>item target → {@code targetId} itself if it is a registered non-air item, else {@code null}
     *       (e.g. the providing mod was removed)</li>
     * </ul>
     *
     * @param isRegisteredItem whether an item ID is in the server's item registry and not air
     */
    public static String hoverItemId(boolean enabled, String targetId, TagIndex tags,
                                     Predicate<String> isRegisteredItem) {
        if (!enabled || targetId == null || targetId.isEmpty()) {
            return null;
        }
        String itemId = targetId.charAt(0) == '#'
                ? (tags == null ? null : tags.lexicographicFirstMember(targetId))
                : targetId;
        if (itemId == null || !isRegisteredItem.test(itemId)) {
            return null;
        }
        return itemId;
    }
}
