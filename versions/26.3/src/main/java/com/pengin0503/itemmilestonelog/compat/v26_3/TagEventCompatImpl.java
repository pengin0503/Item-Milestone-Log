package com.pengin0503.itemmilestonelog.compat.v26_3;

import com.pengin0503.itemmilestonelog.compat.TagEventCompat;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

/** 26.3: getUpdateCause() was removed; shouldUpdateStaticData() is its equivalent. */
public final class TagEventCompatImpl implements TagEventCompat {
    @Override
    public boolean isStaticDataLoad(TagsUpdatedEvent event) {
        return event.shouldUpdateStaticData();
    }
}
