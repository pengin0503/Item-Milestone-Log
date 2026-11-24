package com.pengin0503.itemmilestonelog.compat;

import com.pengin0503.itemmilestonelog.MilestoneData;
import net.minecraft.world.level.storage.DimensionDataStorage;

/**
 * N252: obtains the mod's {@link MilestoneData} from a {@link DimensionDataStorage}.
 * {@code SavedData.Factory} was replaced by {@code SavedDataType} in MC 1.21.5, so the
 * lookup goes through this interface.
 */
public interface DataCompat {
    /** The stored {@link MilestoneData}, created (empty) on first access. */
    MilestoneData getOrCreate(DimensionDataStorage storage);
}
