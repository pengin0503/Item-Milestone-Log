package com.pengin0503.itemmilestonelog.compat.client.v120_1;

import com.pengin0503.itemmilestonelog.compat.client.ScrollCompat;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;

/**
 * 1.20.1 {@link ScrollCompat}: {@code getScrollAmount()} exists unchanged in 1.20.1.
 */
public final class ScrollCompatImpl implements ScrollCompat {
    @Override
    public double getScrollAmount(ContainerObjectSelectionList<?> list) {
        return list.getScrollAmount();
    }
}
