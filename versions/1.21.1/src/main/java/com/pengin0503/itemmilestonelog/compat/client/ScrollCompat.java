package com.pengin0503.itemmilestonelog.compat.client;

import net.minecraft.client.gui.components.ContainerObjectSelectionList;

/**
 * N252: scroll position reads for selection lists. MC 1.21.4 renamed
 * {@code getScrollAmount()} to {@code scrollAmount()} ({@code setScrollAmount(double)} is
 * unchanged), so reads go through this interface. Client-only.
 */
public interface ScrollCompat {
    double getScrollAmount(ContainerObjectSelectionList<?> list);
}
