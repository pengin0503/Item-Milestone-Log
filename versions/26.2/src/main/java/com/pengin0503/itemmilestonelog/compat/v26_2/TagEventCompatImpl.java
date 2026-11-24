package com.pengin0503.itemmilestonelog.compat.v26_2;

import com.pengin0503.itemmilestonelog.compat.TagEventCompat;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

/** 26.1/26.2: static-data loads are TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD. */
public final class TagEventCompatImpl implements TagEventCompat {
    @Override
    public boolean isStaticDataLoad(TagsUpdatedEvent event) {
        return event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD;
    }
}
