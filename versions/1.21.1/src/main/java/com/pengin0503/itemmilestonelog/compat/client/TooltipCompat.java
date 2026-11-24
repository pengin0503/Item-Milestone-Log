package com.pengin0503.itemmilestonelog.compat.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/**
 * N252: item tooltip rendering. MC 1.21.6 removed
 * {@code GuiGraphics.renderTooltip(Font, ItemStack, int, int)} in favour of
 * {@code GuiGraphics.setTooltipForNextFrame(Font, ItemStack, int, int)}, so the call goes
 * through this interface. Client-only.
 */
public interface TooltipCompat {
    void renderItemTooltip(GuiGraphics graphics, Font font, ItemStack stack, int x, int y);
}
