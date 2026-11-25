package com.pengin0503.itemmilestonelog.compat.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.ObjectSelectionList;

import java.util.function.IntSupplier;

/**
 * N252 (1.3.1): the screens' selection lists. The lists override the scrollbar position, and
 * the overridden method changed inside the merged-jar range: {@code
 * AbstractSelectionList.getScrollbarPosition()} up to MC 1.21.3, {@code
 * AbstractScrollArea.scrollBarX()} (overridden by {@code AbstractSelectionList}) from 1.21.4.
 * A shared class can only carry one of them, and its bytecode overrides nothing on the other
 * side of the change (the link audit reports that as override drift). The list subclasses
 * therefore live in the profile implementations; the screens only see the vanilla list
 * types. Client-only.
 */
public interface ListCompat {
    /**
     * An {@link ObjectSelectionList} whose rows are {@code rowWidth} wide (re-read on every
     * layout) and whose scrollbar sits at {@link #scrollbarX} on profiles that position it.
     */
    <E extends ObjectSelectionList.Entry<E>> ObjectSelectionList<E> objectList(
            Minecraft mc, int width, int height, int y, int itemHeight, IntSupplier rowWidth);

    /** {@link #objectList} for a {@link ContainerObjectSelectionList}. */
    <E extends ContainerObjectSelectionList.Entry<E>> ContainerObjectSelectionList<E> containerList(
            Minecraft mc, int width, int height, int y, int itemHeight, IntSupplier rowWidth);

    /** Removes every row of a list created by this facet (the protected {@code clearEntries()}). */
    void clearRows(AbstractSelectionList<?> list);

    /** Appends a row to a list created by {@link #objectList} (the protected {@code addEntry(E)}). */
    <E extends ObjectSelectionList.Entry<E>> void addRow(ObjectSelectionList<E> list, E row);

    /** Appends a row to a list created by {@link #containerList}. */
    <E extends ContainerObjectSelectionList.Entry<E>> void addRow(ContainerObjectSelectionList<E> list, E row);

    /** The mod's scrollbar x: 6 px right of the centred rows (the 1.3.0 screens' value). */
    static int scrollbarX(int listX, int listWidth, int rowWidth) {
        return listX + listWidth / 2 + rowWidth / 2 + 6;
    }
}
