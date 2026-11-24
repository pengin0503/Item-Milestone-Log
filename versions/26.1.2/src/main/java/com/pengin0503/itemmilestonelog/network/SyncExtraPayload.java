package com.pengin0503.itemmilestonelog.network;

import com.pengin0503.itemmilestonelog.ItemMilestoneLog;
import com.pengin0503.itemmilestonelog.Presets;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Optional S2C payload (F25) carrying extensible extra GUI metadata in a single NBT compound so
 * future additions do not require new payload types or protocol version bumps.
 */
public record SyncExtraPayload(
        Set<TargetKey> worldFirsts,
        Set<String> readOnlyPresets
) implements CustomPacketPayload {

    public static final String KEY_WORLD_FIRSTS = "worldFirsts";
    public static final String KEY_READ_ONLY_PRESETS = "readOnlyPresets";
    public static final int MAX_LIST_ENTRIES = 4096;

    public static final SyncExtraPayload EMPTY = new SyncExtraPayload(Set.of(), Set.of());

    public static final Type<SyncExtraPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(ItemMilestoneLog.MOD_ID, "sync_extra"));

    public static final StreamCodec<ByteBuf, SyncExtraPayload> STREAM_CODEC =
            ByteBufCodecs.COMPOUND_TAG.map(SyncExtraPayload::fromTag, SyncExtraPayload::toTag);

    public SyncExtraPayload {
        if (worldFirsts == null || worldFirsts.isEmpty()) {
            worldFirsts = Set.of();
        } else {
            Set<TargetKey> copy = new LinkedHashSet<>(Math.min(worldFirsts.size(), MAX_LIST_ENTRIES));
            for (TargetKey key : worldFirsts) {
                if (key != null) {
                    copy.add(key);
                }
            }
            worldFirsts = copy.isEmpty() ? Set.of() : Collections.unmodifiableSet(copy);
        }
        if (readOnlyPresets == null || readOnlyPresets.isEmpty()) {
            readOnlyPresets = Set.of();
        } else {
            Set<String> copy = new LinkedHashSet<>(Math.min(readOnlyPresets.size(), MAX_LIST_ENTRIES));
            for (String id : readOnlyPresets) {
                if (id != null) {
                    copy.add(id);
                }
            }
            readOnlyPresets = copy.isEmpty() ? Set.of() : Collections.unmodifiableSet(copy);
        }
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();

        ListTag wfList = new ListTag();
        int wfCount = 0;
        for (TargetKey key : worldFirsts) {
            if (wfCount++ >= MAX_LIST_ENTRIES) {
                break;
            }
            wfList.add(StringTag.valueOf(key.toEncodedString()));
        }
        tag.put(KEY_WORLD_FIRSTS, wfList);

        ListTag roList = new ListTag();
        int roCount = 0;
        for (String presetId : readOnlyPresets) {
            if (roCount++ >= MAX_LIST_ENTRIES) {
                break;
            }
            roList.add(StringTag.valueOf(presetId));
        }
        tag.put(KEY_READ_ONLY_PRESETS, roList);

        return tag;
    }

    public static SyncExtraPayload fromTag(@Nullable CompoundTag tag) {
        if (tag == null) {
            return EMPTY;
        }
        Set<TargetKey> worldFirsts = new LinkedHashSet<>();
        tag.getList(KEY_WORLD_FIRSTS).ifPresent(list -> {
            int limit = Math.min(list.size(), MAX_LIST_ENTRIES);
            for (int i = 0; i < limit; i++) {
                list.getString(i).ifPresent(raw -> {
                    TargetKey parsed = TargetKey.tryParseEncoded(raw);
                    if (parsed != null) {
                        worldFirsts.add(parsed);
                    }
                });
            }
        });

        Set<String> readOnlyPresets = new LinkedHashSet<>();
        tag.getList(KEY_READ_ONLY_PRESETS).ifPresent(list -> {
            int limit = Math.min(list.size(), MAX_LIST_ENTRIES);
            for (int i = 0; i < limit; i++) {
                list.getString(i).ifPresent(presetId -> {
                    if (Presets.isValidId(presetId)) {
                        readOnlyPresets.add(presetId);
                    }
                });
            }
        });

        if (worldFirsts.isEmpty() && readOnlyPresets.isEmpty()) {
            return EMPTY;
        }
        return new SyncExtraPayload(worldFirsts, readOnlyPresets);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
