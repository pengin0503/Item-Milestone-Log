package com.pengin0503.itemmilestonelog.compat.client.v120_1;

import com.pengin0503.itemmilestonelog.compat.client.ClipboardCompat;
import net.minecraft.client.Minecraft;

/** 1.20.1-forge: clipboard hook isolated from the shared client classes. */
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
