package com.pengin0503.itemmilestonelog.compat.v121_4;

import com.pengin0503.itemmilestonelog.MilestoneData;
import com.pengin0503.itemmilestonelog.compat.DataCompat;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;

/**
 * 1.21 - 1.21.4 {@link DataCompat}: the legacy {@link SavedData.Factory} lookup.
 */
public final class DataCompatImpl implements DataCompat {
    private static final SavedData.Factory<MilestoneData> FACTORY = new SavedData.Factory<>(
            MilestoneData::new, MilestoneData::load, null);

    @Override
    public MilestoneData getOrCreate(DimensionDataStorage storage) {
        return storage.computeIfAbsent(FACTORY, MilestoneData.DATA_NAME);
    }
}
