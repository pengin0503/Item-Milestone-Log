package com.pengin0503.itemmilestonelog.compat.v121_11;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.pengin0503.itemmilestonelog.MilestoneData;
import com.pengin0503.itemmilestonelog.compat.DataCompat;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.DimensionDataStorage;

/**
 * 1.21.5+ {@link DataCompat}: {@code SavedData.Factory} was removed in 1.21.5 in favour of
 * {@link SavedDataType}, whose codec here round-trips through the existing NBT
 * {@link MilestoneData#save} / {@link MilestoneData#load}. The codec preserves the legacy
 * {@code "data"} wrapper on disk (encoding writes {@code {"data": ...}}; decoding also
 * accepts an unwrapped root), so world files stay interchangeable across the whole 1.21.x
 * line in both directions.
 */
public final class DataCompatImpl implements DataCompat {
    private static final SavedDataType<MilestoneData> TYPE = new SavedDataType<>(
            MilestoneData.DATA_NAME,
            context -> new MilestoneData(),
            context -> {
                HolderLookup.Provider provider = context.levelOrThrow().registryAccess();
                return Codec.of(
                        (data, ops, prefix) -> {
                            CompoundTag root = new CompoundTag();
                            root.put("data", data.save(new CompoundTag(), provider));
                            return DataResult.success(root);
                        },
                        (ops, input) -> {
                            if (!(input instanceof CompoundTag root)) {
                                return DataResult.error(() -> "MilestoneData: expected a compound tag");
                            }
                            CompoundTag inner = root.contains("data") ? root.getCompoundOrEmpty("data") : root;
                            return DataResult.success(new Pair<>(MilestoneData.load(inner, provider), input));
                        });
            });

    @Override
    public MilestoneData getOrCreate(DimensionDataStorage storage) {
        return storage.computeIfAbsent(TYPE);
    }
}
