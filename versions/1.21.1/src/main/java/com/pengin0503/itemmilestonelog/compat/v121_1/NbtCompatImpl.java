package com.pengin0503.itemmilestonelog.compat.v121_1;

import com.pengin0503.itemmilestonelog.compat.NbtCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * 1.21 / 1.21.1 {@link NbtCompat}: direct calls to the legacy {@link CompoundTag} accessors,
 * which already implement the default semantics documented on {@link NbtCompat}
 * ({@code getCompound} returns the live child or a fresh, unattached empty tag).
 */
public final class NbtCompatImpl implements NbtCompat {
    @Override
    public int getInt(CompoundTag tag, String key) {
        return tag.getInt(key);
    }

    @Override
    public long getLong(CompoundTag tag, String key) {
        return tag.getLong(key);
    }

    @Override
    public boolean getBoolean(CompoundTag tag, String key) {
        return tag.getBoolean(key);
    }

    @Override
    public String getString(CompoundTag tag, String key) {
        return tag.getString(key);
    }

    @Override
    public CompoundTag getCompound(CompoundTag tag, String key) {
        return tag.getCompound(key);
    }

    @Override
    public ListTag getList(CompoundTag tag, String key) {
        return tag.getList(key, Tag.TAG_COMPOUND);
    }

    @Override
    public boolean contains(CompoundTag tag, String key) {
        return tag.contains(key);
    }
}
