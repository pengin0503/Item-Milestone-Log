package com.pengin0503.itemmilestonelog.compat.v121_6;

import com.pengin0503.itemmilestonelog.compat.NbtCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.Set;

/**
 * 1.21.5+ {@link NbtCompat}: the typed {@link CompoundTag} accessors return {@code Optional}
 * since 1.21.5, so reads use the {@code get*Or} / {@code get*OrEmpty} variants with the legacy
 * defaults documented on {@link NbtCompat}. {@code getList} / {@code getStringList} keep the
 * legacy {@code getList(key, type)} semantics: a stored list whose element type differs from
 * the requested one yields a fresh empty list (NBT lists are homogeneous, so the first
 * element decides).
 */
public final class NbtCompatImpl implements NbtCompat {
    @Override
    public int getInt(CompoundTag tag, String key) {
        return tag.getIntOr(key, 0);
    }

    @Override
    public long getLong(CompoundTag tag, String key) {
        return tag.getLongOr(key, 0L);
    }

    @Override
    public boolean getBoolean(CompoundTag tag, String key) {
        return tag.getBooleanOr(key, false);
    }

    @Override
    public String getString(CompoundTag tag, String key) {
        return tag.getStringOr(key, "");
    }

    @Override
    public CompoundTag getCompound(CompoundTag tag, String key) {
        return tag.getCompoundOrEmpty(key);
    }

    @Override
    public ListTag getList(CompoundTag tag, String key) {
        ListTag raw = tag.getListOrEmpty(key);
        if (!raw.isEmpty() && !(raw.get(0) instanceof CompoundTag)) {
            return new ListTag();
        }
        return raw;
    }

    @Override
    public boolean contains(CompoundTag tag, String key) {
        return tag.contains(key);
    }

    @Override
    public boolean containsTyped(CompoundTag tag, String key, byte type) {
        Tag t = tag.get(key);
        if (t == null) {
            return false;
        }
        if (type == NbtCompat.ANY_NUMERIC) {
            return t instanceof NumericTag;
        }
        return t.getId() == type;
    }

    @Override
    public ListTag getStringList(CompoundTag tag, String key) {
        ListTag raw = tag.getListOrEmpty(key);
        if (!raw.isEmpty() && !(raw.get(0) instanceof StringTag)) {
            return new ListTag();
        }
        return raw;
    }

    @Override
    public Set<String> keys(CompoundTag tag) {
        return tag.keySet();
    }

    @Override
    public byte listElementType(ListTag list) {
        return list.isEmpty() ? Tag.TAG_END : list.get(0).getId();
    }

    @Override
    public String listString(ListTag list, int index) {
        return list.getStringOr(index, "");
    }

    @Override
    public String tagAsString(Tag tag) {
        return tag instanceof StringTag stringTag ? stringTag.value() : "";
    }
}
