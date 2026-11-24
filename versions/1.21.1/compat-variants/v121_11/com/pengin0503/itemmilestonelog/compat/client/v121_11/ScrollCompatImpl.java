package com.pengin0503.itemmilestonelog.compat.client.v121_11;

import com.pengin0503.itemmilestonelog.compat.client.ScrollCompat;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;

/**
 * 1.21.4+ {@link ScrollCompat}: {@code getScrollAmount()} was renamed to {@code scrollAmount()}.
 */
public final class ScrollCompatImpl implements ScrollCompat {
    @Override
    public double getScrollAmount(ContainerObjectSelectionList<?> list) {
        return list.scrollAmount();
    }
}
