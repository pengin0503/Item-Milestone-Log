package com.pengin0503.itemmilestonelog.compat.client.v121_9;

import com.pengin0503.itemmilestonelog.compat.client.ListCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.ObjectSelectionList;

import java.util.function.IntSupplier;

/**
 * 1.21.9 - 1.21.10 {@link ListCompat}: the lists keep the vanilla scrollbar position ({@code scrollBarX()} is
 * not overridden). The 1.3.0 screens' {@code getScrollbarPosition()} overrode nothing on
 * this profile's versions, so this keeps the released behaviour of this profile.
 */
public final class ListCompatImpl implements ListCompat {
    @Override
    public <E extends ObjectSelectionList.Entry<E>> ObjectSelectionList<E> objectList(
            Minecraft mc, int width, int height, int y, int itemHeight, IntSupplier rowWidth) {
        return new ObjectList<>(mc, width, height, y, itemHeight, rowWidth);
    }

    @Override
    public <E extends ContainerObjectSelectionList.Entry<E>> ContainerObjectSelectionList<E> containerList(
            Minecraft mc, int width, int height, int y, int itemHeight, IntSupplier rowWidth) {
        return new ContainerList<>(mc, width, height, y, itemHeight, rowWidth);
    }

    @Override
    public void clearRows(AbstractSelectionList<?> list) {
        rows(list).clearRows();
    }

    @Override
    public <E extends ObjectSelectionList.Entry<E>> void addRow(ObjectSelectionList<E> list, E row) {
        ListCompatImpl.<E>rows(list).addRow(row);
    }

    @Override
    public <E extends ContainerObjectSelectionList.Entry<E>> void addRow(ContainerObjectSelectionList<E> list, E row) {
        ListCompatImpl.<E>rows(list).addRow(row);
    }

    @SuppressWarnings("unchecked")
    private static <E> Rows<E> rows(AbstractSelectionList<?> list) {
        if (!(list instanceof Rows<?> rows)) {
            throw new IllegalArgumentException("not a ListCompat list: " + list.getClass().getName());
        }
        return (Rows<E>) rows;
    }

    private interface Rows<E> {
        void clearRows();

        void addRow(E row);
    }

    private static final class ObjectList<E extends ObjectSelectionList.Entry<E>>
            extends ObjectSelectionList<E> implements Rows<E> {
        private final IntSupplier rowWidth;

        ObjectList(Minecraft mc, int width, int height, int y, int itemHeight, IntSupplier rowWidth) {
            super(mc, width, height, y, itemHeight);
            this.rowWidth = rowWidth;
        }

        @Override
        public int getRowWidth() {
            // null only while the super constructor runs (before the field is assigned)
            return rowWidth != null ? rowWidth.getAsInt() : super.getRowWidth();
        }

        @Override
        public void clearRows() {
            clearEntries();
        }

        @Override
        public void addRow(E row) {
            addEntry(row);
        }
    }

    private static final class ContainerList<E extends ContainerObjectSelectionList.Entry<E>>
            extends ContainerObjectSelectionList<E> implements Rows<E> {
        private final IntSupplier rowWidth;

        ContainerList(Minecraft mc, int width, int height, int y, int itemHeight, IntSupplier rowWidth) {
            super(mc, width, height, y, itemHeight);
            this.rowWidth = rowWidth;
        }

        @Override
        public int getRowWidth() {
            return rowWidth != null ? rowWidth.getAsInt() : super.getRowWidth();
        }

        @Override
        public void clearRows() {
            clearEntries();
        }

        @Override
        public void addRow(E row) {
            addEntry(row);
        }
    }
}
