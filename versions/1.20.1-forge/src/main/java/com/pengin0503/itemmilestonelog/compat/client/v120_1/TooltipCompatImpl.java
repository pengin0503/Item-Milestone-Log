package com.pengin0503.itemmilestonelog.compat.client.v120_1;

import com.pengin0503.itemmilestonelog.compat.client.TooltipCompat;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/**
 * 1.20.1 {@link TooltipCompat}: {@code renderTooltip(Font, ItemStack, int, int)}
 * exists unchanged in 1.20.1.
 */
public final class TooltipCompatImpl implements TooltipCompat {
    @Override
    public void renderItemTooltip(GuiGraphics graphics, Font font, ItemStack stack, int x, int y) {
        graphics.renderTooltip(font, stack, x, y);
    }
}
