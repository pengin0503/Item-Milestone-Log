package com.pengin0503.itemmilestonelog.compat.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * N252 (1.3.1): GUI text rendering. MC 1.21.6 changed the return type of the
 * {@code GuiGraphics.drawString(...)} overloads from {@code int} (the x after the text)
 * to {@code void}. The source is identical on both sides, but the compiled method
 * descriptor is not, so a shared class compiled against one side throws
 * {@code NoSuchMethodError} on the other. Every text call of the shared client code goes
 * through this interface (return values are never used); each profile's implementation
 * is compiled against its own version. {@code drawCenteredString} goes through it too,
 * so no shared class links any {@code GuiGraphics} text method directly. Client-only.
 */
public interface TextCompat {
    void drawString(GuiGraphics graphics, Font font, String text, int x, int y, int color, boolean dropShadow);

    void drawString(GuiGraphics graphics, Font font, Component text, int x, int y, int color, boolean dropShadow);

    void drawCenteredString(GuiGraphics graphics, Font font, String text, int x, int y, int color);

    void drawCenteredString(GuiGraphics graphics, Font font, Component text, int x, int y, int color);
}
