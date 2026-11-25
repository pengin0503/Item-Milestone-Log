package com.pengin0503.itemmilestonelog.compat.v120_1;

import com.pengin0503.itemmilestonelog.MilestoneData;
import com.pengin0503.itemmilestonelog.compat.DataCompat;
import net.minecraft.world.level.storage.DimensionDataStorage;

import javax.annotation.Nullable;

/**
 * 1.20.1-forge {@link DataCompat}: the legacy {@code computeIfAbsent(load, create, name)}
 * lookup. 1.20.1's {@code SavedData} has no {@code DataFixTypes} / {@code SavedData.Factory};
 * {@code save}/{@code load} take a plain {@code CompoundTag}.
 */
public final class DataCompatImpl implements DataCompat {
    @Override
    public MilestoneData getOrCreate(DimensionDataStorage storage) {
        return storage.computeIfAbsent(MilestoneData::load, MilestoneData::new, MilestoneData.DATA_NAME);
    }

    @Override
    @Nullable
    public MilestoneData get(DimensionDataStorage storage) {
        return storage.get(MilestoneData::load, MilestoneData.DATA_NAME);
    }
}
