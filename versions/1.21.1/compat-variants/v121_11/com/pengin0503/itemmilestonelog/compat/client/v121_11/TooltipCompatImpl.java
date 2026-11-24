package com.pengin0503.itemmilestonelog.compat.client.v121_11;

import com.pengin0503.itemmilestonelog.compat.client.TooltipCompat;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/**
 * 1.21.11 {@link TooltipCompat}: {@code setTooltipForNextFrame(Font, ItemStack, int, int)} (1.21.6+).
 */
public final class TooltipCompatImpl implements TooltipCompat {
    @Override
    public void renderItemTooltip(GuiGraphics graphics, Font font, ItemStack stack, int x, int y) {
        graphics.setTooltipForNextFrame(font, stack, x, y);
    }
}
