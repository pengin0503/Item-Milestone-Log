package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.TargetKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.Tags;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F8b: resolves item tag targets ({@code #namespace:path}) against {@link BuiltInRegistries#ITEM}.
 *
 * <p>The resolved membership is cached as an immutable {@link TagIndex} and rebuilt only when tags
 * change ({@code TagsUpdatedEvent}, handled by {@link Tracker#onTagsUpdated}) so inventory scans
 * never re-resolve tags. Until the registry tags are bound the cache stays empty and is retried on
 * the next access.
 */
public final class TagTargets {
    private static volatile TagIndex cache;

    private TagTargets() {
    }

    /** Current tag membership snapshot (built lazily from the registry on first use). */
    public static TagIndex index() {
        TagIndex current = cache;
        if (current != null) {
            return current;
        }
        TagIndex built = buildFromRegistry();
        if (built != null) {
            cache = built;
            return built;
        }
        return TagIndex.EMPTY;
    }

    /** Drops and rebuilds the cached snapshot from the registry (tags were reloaded). */
    public static TagIndex rebuild() {
        cache = null;
        return index();
    }

    /** Replaces the cache (GameTests / tests); {@code null} forces a registry rebuild on next use. */
    static void setIndexForTesting(@Nullable TagIndex index) {
        cache = index;
    }

    @Nullable
    private static TagIndex buildFromRegistry() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        try {
            for (Item item : BuiltInRegistries.ITEM) {
                if (item == Items.AIR) {
                    continue;
                }
                String itemId = BuiltInRegistries.ITEM.getKey(item).toString();
                item.builtInRegistryHolder().tags().forEach(
                        tag -> map.computeIfAbsent("#" + tag.location(), k -> new ArrayList<>()).add(itemId));
            }
        } catch (IllegalStateException e) {
            // Tags are not bound yet (very early startup); retry on the next access.
            return null;
        }
        return TagIndex.of(map);
    }

    /** Whether {@code tagKey} ({@code "#ns:path"}) exists in the registry and contains at least one item. */
    public static boolean isNonEmptyTag(@Nullable String tagKey) {
        return TargetKey.isTagId(tagKey) && index().isNonEmptyTag(tagKey);
    }

    /**
     * F8b display name: {@code Component.translatableWithFallback(Tags.getTagTranslationKey(tag), "#ns:path")}.
     * Uses the NeoForge tag translation key convention (e.g. {@code tag.item.c.ingots.iron}); falls back
     * to the tag ID itself when no translation exists (and on vanilla clients).
     */
    public static Component displayName(String tagKey) {
        String body = tagKey.startsWith("#") ? tagKey.substring(1) : tagKey;
        ResourceLocation id = ResourceLocation.tryParse(body);
        if (id == null) {
            return Component.literal(tagKey);
        }
        TagKey<Item> key = TagKey.create(Registries.ITEM, id);
        return Component.translatableWithFallback(Tags.getTagTranslationKey(key), "#" + body);
    }

    /** Item ID used as the GUI icon of a tag target (its first member), or {@code null} if unknown/empty. */
    @Nullable
    public static String iconItemId(String tagKey) {
        return index().firstMember(tagKey);
    }

    /** Sorted IDs of all non-empty item tags without the leading {@code '#'} (for {@code addtag} suggestions). */
    public static List<String> suggestableTagIds() {
        Set<String> keys = index().tagKeys();
        List<String> ids = new ArrayList<>(keys.size());
        for (String key : keys) {
            ids.add(key.substring(1));
        }
        return ids;
    }
}
