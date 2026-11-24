package com.pengin0503.itemmilestonelog.compat.client;

import com.pengin0503.itemmilestonelog.compat.McVersion;

/**
 * N251: client-side counterpart of {@code McCompat}; loads the per-version
 * {@link ScreenCompat} reflectively by name. Client-only.
 */
public final class ClientCompat {
    private static final ScreenCompat SCREENS = load();

    private ClientCompat() {
    }

    public static ScreenCompat screens() {
        return SCREENS;
    }

    private static ScreenCompat load() {
        String className = "com.pengin0503.itemmilestonelog.compat."
                + McVersion.current().packageSuffix() + ".client.ScreenCompatImpl";
        try {
            return ScreenCompat.class.cast(
                    Class.forName(className).getDeclaredConstructor().newInstance());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to load compat implementation " + className, e);
        }
    }
}
