package com.pengin0503.itemmilestonelog.compat.v26_3.client;

import com.pengin0503.itemmilestonelog.compat.client.ScreenCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

import javax.annotation.Nullable;

/** 26.3: screen access through Minecraft#gui; slot accessor renamed to getHoveredSlot. */
public final class ScreenCompatImpl implements ScreenCompat {
    @Override
    public @Nullable Screen currentScreen(Minecraft mc) {
        return mc.gui.screen();
    }

    @Override
    public void setScreen(Minecraft mc, @Nullable Screen screen) {
        mc.gui.setScreen(screen);
    }

    @Override
    public @Nullable Slot hoveredSlot(AbstractContainerScreen<?> screen) {
        return screen.getHoveredSlot();
    }
}
