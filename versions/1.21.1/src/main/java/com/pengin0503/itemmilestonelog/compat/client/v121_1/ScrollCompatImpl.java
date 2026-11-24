package com.pengin0503.itemmilestonelog.compat.client.v121_1;

import com.pengin0503.itemmilestonelog.compat.client.ScrollCompat;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;

/**
 * 1.21 - 1.21.3 {@link ScrollCompat}: the legacy {@code getScrollAmount()}.
 */
public final class ScrollCompatImpl implements ScrollCompat {
    @Override
    public double getScrollAmount(ContainerObjectSelectionList<?> list) {
        return list.getScrollAmount();
    }
}
