package com.pengin0503.itemmilestonelog.compat.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

import javax.annotation.Nullable;

/**
 * N251: client screen access. 26.2 moved screen access behind {@code Minecraft#gui},
 * and 26.3 renamed {@code AbstractContainerScreen#getSlotUnderMouse}; per-version
 * implementations live in the {@code compat.v26_*} packages. Client-only: server
 * code must never reference this interface.
 */
public interface ScreenCompat {
    @Nullable
    Screen currentScreen(Minecraft mc);

    void setScreen(Minecraft mc, @Nullable Screen screen);

    @Nullable
    Slot hoveredSlot(AbstractContainerScreen<?> screen);
}
