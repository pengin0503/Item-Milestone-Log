package com.pengin0503.itemmilestonelog.compat.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * N252 / F-150-82: standard (non-interrupting) system narration of HUD rows.
 *
 * <p>Mojang's official mappings show the entry point changed in MC 1.21.6:
 * 1.21 - 1.21.5 expose {@code GameNarrator.say(Component)} while 1.21.6+ expose
 * {@code GameNarrator.saySystemQueued(Component)} (the rename keeps the same
 * "queue a system message, do not interrupt" behaviour). A shared class cannot
 * reference either name directly and still link across that boundary, so the
 * call lives in a per-profile implementation compiled against its own version
 * and reached only through {@code McCompat.narration()}.
 */
public interface NarrationCompat {
    void narrate(Minecraft mc, Component text);
}
