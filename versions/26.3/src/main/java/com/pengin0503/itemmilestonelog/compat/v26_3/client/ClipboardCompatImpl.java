package com.pengin0503.itemmilestonelog.compat.v26_3.client;

import com.pengin0503.itemmilestonelog.compat.client.ClipboardCompat;
import net.minecraft.client.Minecraft;

/** v26_3: clipboard hook for the 26.x merged jar. */
public final class ClipboardCompatImpl implements ClipboardCompat {
    @Override
    public boolean setClipboard(String value) {
        if (value == null) {
            return false;
        }
        try {
            Minecraft.getInstance().keyboardHandler.setClipboard(value);
            return true;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }
}
