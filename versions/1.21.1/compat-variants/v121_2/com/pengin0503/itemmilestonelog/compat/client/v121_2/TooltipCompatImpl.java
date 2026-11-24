package com.pengin0503.itemmilestonelog.compat.client.v121_2;

import com.pengin0503.itemmilestonelog.compat.client.TooltipCompat;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/**
 * 1.21 - 1.21.5 {@link TooltipCompat}: the legacy {@code renderTooltip(Font, ItemStack, int, int)}.
 */
public final class TooltipCompatImpl implements TooltipCompat {
    @Override
    public void renderItemTooltip(GuiGraphics graphics, Font font, ItemStack stack, int x, int y) {
        graphics.renderTooltip(font, stack, x, y);
    }
}
