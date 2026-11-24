package com.pengin0503.itemmilestonelog.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.Set;

/**
 * N252: typed {@link CompoundTag} reads with the legacy (1.21.1) default semantics.
 * 1.21.5+ made these accessors return {@code Optional} and changed the
 * {@code contains} / {@code getList} signatures, so reads go through this interface.
 *
 * <p>Absent or wrongly typed keys yield the legacy defaults: {@code 0}, {@code 0L},
 * {@code false}, {@code ""}, a fresh empty compound (not attached to {@code tag}), or a
 * fresh empty list. A present compound child is returned live (same instance).
 */
public interface NbtCompat {
    int getInt(CompoundTag tag, String key);

    long getLong(CompoundTag tag, String key);

    boolean getBoolean(CompoundTag tag, String key);

    String getString(CompoundTag tag, String key);

    CompoundTag getCompound(CompoundTag tag, String key);

    /** List of compound elements stored under {@code key}, or a fresh empty list. */
    ListTag getList(CompoundTag tag, String key);

    /** Whether {@code key} is present (any tag type). */
    boolean contains(CompoundTag tag, String key);

    /**
     * Whether {@code key} is present with tag type {@code type} (a {@code Tag.TAG_*} constant;
     * {@code Tag.TAG_ANY_NUMERIC} matches any numeric type).
     */
    boolean containsTyped(CompoundTag tag, String key, byte type);

    /** List of string elements stored under {@code key}, or a fresh empty list. */
    ListTag getStringList(CompoundTag tag, String key);

    /** The keys of {@code tag}. */
    Set<String> keys(CompoundTag tag);

    /** Tag type id of {@code list}'s elements ({@code Tag.TAG_END} when empty). */
    byte listElementType(ListTag list);

    /** String element at {@code index}, or {@code ""} when out of range or not a string. */
    String listString(ListTag list, int index);
}
