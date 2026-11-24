package com.pengin0503.itemmilestonelog.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

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
}
