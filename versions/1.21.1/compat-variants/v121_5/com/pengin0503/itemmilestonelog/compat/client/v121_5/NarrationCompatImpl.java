package com.pengin0503.itemmilestonelog.compat.client.v121_5;

import com.pengin0503.itemmilestonelog.compat.client.NarrationCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 1.21.5 {@link NarrationCompat}: Mojang mappings expose
 * {@code GameNarrator.say(Component)} on this version. Compiled against this profile's
 * own game jar so the shared {@code HudCompat.class} never names a version-specific narrator
 * method (1.21.6 renamed {@code say} to {@code saySystemQueued}).
 */
public final class NarrationCompatImpl implements NarrationCompat {
    @Override
    public void narrate(Minecraft mc, Component text) {
        mc.getNarrator().say(text);
    }
}
