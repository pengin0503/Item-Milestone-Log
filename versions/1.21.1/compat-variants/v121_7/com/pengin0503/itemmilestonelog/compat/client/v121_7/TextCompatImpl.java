package com.pengin0503.itemmilestonelog.compat.client.v121_7;

import com.pengin0503.itemmilestonelog.compat.client.TextCompat;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 1.21.7 {@link TextCompat}: plain {@code GuiGraphics} text calls, compiled against this
 * profile's version ({@code drawString} returns {@code void}).
 */
public final class TextCompatImpl implements TextCompat {
    @Override
    public void drawString(GuiGraphics graphics, Font font, String text, int x, int y, int color, boolean dropShadow) {
        graphics.drawString(font, text, x, y, color, dropShadow);
    }

    @Override
    public void drawString(GuiGraphics graphics, Font font, Component text, int x, int y, int color, boolean dropShadow) {
        graphics.drawString(font, text, x, y, color, dropShadow);
    }

    @Override
    public void drawCenteredString(GuiGraphics graphics, Font font, String text, int x, int y, int color) {
        graphics.drawCenteredString(font, text, x, y, color);
    }

    @Override
    public void drawCenteredString(GuiGraphics graphics, Font font, Component text, int x, int y, int color) {
        graphics.drawCenteredString(font, text, x, y, color);
    }
}
