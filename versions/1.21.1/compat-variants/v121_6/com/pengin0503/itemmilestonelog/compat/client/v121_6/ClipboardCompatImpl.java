package com.pengin0503.itemmilestonelog.compat.client.v121_6;

import com.pengin0503.itemmilestonelog.compat.client.ClipboardCompat;
import net.minecraft.client.Minecraft;

/** v121_6: clipboard hook for this 1.21.x profile. */
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
