package com.pengin0503.itemmilestonelog.compat.v121_2;

import com.pengin0503.itemmilestonelog.compat.NbtCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.Set;

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

    @Override
    public boolean containsTyped(CompoundTag tag, String key, byte type) {
        return tag.contains(key, type);
    }

    @Override
    public ListTag getStringList(CompoundTag tag, String key) {
        return tag.getList(key, Tag.TAG_STRING);
    }

    @Override
    public Set<String> keys(CompoundTag tag) {
        return tag.getAllKeys();
    }

    @Override
    public byte listElementType(ListTag list) {
        return list.getElementType();
    }

    @Override
    public String listString(ListTag list, int index) {
        return list.getString(index);
    }

    @Override
    public String tagAsString(Tag tag) {
        return tag.getAsString();
    }
}
