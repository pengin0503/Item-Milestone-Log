package com.pengin0503.itemmilestonelog.compat.v121_7;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.Encoder;
import com.pengin0503.itemmilestonelog.MilestoneData;
import com.pengin0503.itemmilestonelog.compat.DataCompat;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.DimensionDataStorage;

import javax.annotation.Nullable;

/**
 * 1.21.5+ {@link DataCompat}: {@code SavedData.Factory} was removed in 1.21.5 in favour of
 * {@link SavedDataType}, whose codec here round-trips through the existing NBT
 * {@link MilestoneData#save} / {@link MilestoneData#load}. The codec preserves the legacy
 * payload directly; vanilla supplies the outer {@code "data"} wrapper. Decoding accepts
 * both the correct form and the historical double-wrapped form written by 1.21.5-1.21.11
 * builds, so world files stay interchangeable across the whole 1.21.x line in both directions.
 */
public final class DataCompatImpl implements DataCompat {
    private static final SavedDataType<MilestoneData> TYPE = new SavedDataType<MilestoneData>(
            MilestoneData.DATA_NAME,
            context -> new MilestoneData(),
            context -> codec(context.levelOrThrow().registryAccess()));

    private static Codec<MilestoneData> codec(HolderLookup.Provider provider) {
        Encoder<MilestoneData> encoder = new Encoder<>() {
            @Override
            public <T> DataResult<T> encode(MilestoneData data, DynamicOps<T> ops, T prefix) {
                // Vanilla DimensionDataStorage wraps the codec output in {"data": ...}
                // itself. Returning the payload directly avoids the double wrapper (#99);
                // the decoder below still accepts the old double-wrapped form.
                // DimensionDataStorage always drives this codec with NbtOps, so T is Tag.
                @SuppressWarnings("unchecked")
                T result = (T) data.save(new CompoundTag(), provider);
                return DataResult.success(result);
            }
        };
        Decoder<MilestoneData> decoder = new Decoder<>() {
            @Override
            public <T> DataResult<Pair<MilestoneData, T>> decode(DynamicOps<T> ops, T input) {
                if (!(input instanceof CompoundTag root)) {
                    return DataResult.error(() -> "MilestoneData: expected a compound tag");
                }
                CompoundTag inner = root.contains("data") ? root.getCompoundOrEmpty("data") : root;
                return DataResult.success(new Pair<>(MilestoneData.load(inner, provider), input));
            }
        };
        return Codec.of(encoder, decoder);
    }

    @Override
    public MilestoneData getOrCreate(DimensionDataStorage storage) {
        return storage.computeIfAbsent(TYPE);
    }

    @Override
    @Nullable
    public MilestoneData get(DimensionDataStorage storage) {
        return storage.get(TYPE);
    }
}
