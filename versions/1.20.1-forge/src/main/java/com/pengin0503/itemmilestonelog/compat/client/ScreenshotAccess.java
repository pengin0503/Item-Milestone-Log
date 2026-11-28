package com.pengin0503.itemmilestonelog.compat.client;

import com.pengin0503.itemmilestonelog.compat.McVersion;

import java.nio.file.Path;

/**
 * Lazy, failure-tolerant loader for {@link ScreenshotCompat}. No implementation is loaded at
 * construction time: screenshots are opt-in and must never be able to crash client startup
 * (PR-3 hard lesson). A missing or incompatible implementation becomes a no-op.
 */
public final class ScreenshotAccess {
    private static volatile ScreenshotCompat impl;

    private ScreenshotAccess() {
    }

    /** Returns the profile implementation, or a no-op when it cannot be loaded. */
    public static ScreenshotCompat get() {
        ScreenshotCompat current = impl;
        if (current == null) {
            synchronized (ScreenshotAccess.class) {
                current = impl;
                if (current == null) {
                    current = load();
                    impl = current;
                }
            }
        }
        return current;
    }

    public static boolean capture(Path clientDir, String fileName) {
        return get().capture(clientDir, fileName);
    }

    private static ScreenshotCompat load() {
        String className = "com.pengin0503.itemmilestonelog.compat.client."
                + McVersion.current().packageSuffix() + ".ScreenshotCompatImpl";
        try {
            return ScreenshotCompat.class.cast(
                    Class.forName(className).getDeclaredConstructor().newInstance());
        } catch (Throwable t) {
            return (dir, name) -> false;
        }
    }
}
