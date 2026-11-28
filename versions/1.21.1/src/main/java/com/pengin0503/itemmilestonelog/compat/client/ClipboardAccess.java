package com.pengin0503.itemmilestonelog.compat.client;

import com.pengin0503.itemmilestonelog.compat.McVersion;

/** Lazy, failure-tolerant loader for {@link ClipboardCompat}; nothing loads at construction. */
public final class ClipboardAccess {
    private static volatile ClipboardCompat impl;

    private ClipboardAccess() {
    }

    public static ClipboardCompat get() {
        ClipboardCompat current = impl;
        if (current == null) {
            synchronized (ClipboardAccess.class) {
                current = impl;
                if (current == null) {
                    current = load();
                    impl = current;
                }
            }
        }
        return current;
    }

    public static boolean setClipboard(String value) {
        return get().setClipboard(value);
    }

    private static ClipboardCompat load() {
        String className = "com.pengin0503.itemmilestonelog.compat.client.v121_1.ClipboardCompatImpl";
        try {
            return ClipboardCompat.class.cast(
                    Class.forName(className).getDeclaredConstructor().newInstance());
        } catch (Throwable t) {
            return value -> false;
        }
    }
}
