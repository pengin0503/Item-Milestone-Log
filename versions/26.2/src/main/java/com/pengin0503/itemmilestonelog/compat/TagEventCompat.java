package com.pengin0503.itemmilestonelog.compat;

import net.neoforged.neoforge.event.TagsUpdatedEvent;

/**
 * N251: interpretation of {@link TagsUpdatedEvent}. 26.3 removed
 * {@code getUpdateCause()}; per-version implementations live in the
 * {@code compat.v26_*} packages.
 */
public interface TagEventCompat {
    /** True when the tag update comes from a static-data (datapack) load. */
    boolean isStaticDataLoad(TagsUpdatedEvent event);
}
