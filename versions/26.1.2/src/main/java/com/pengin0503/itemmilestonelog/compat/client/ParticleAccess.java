package com.pengin0503.itemmilestonelog.compat.client;

import com.pengin0503.itemmilestonelog.compat.McVersion;

/** Lazy, failure-tolerant loader for {@link ParticleCompat}; nothing is loaded at construction. */
public final class ParticleAccess {
    private static volatile ParticleCompat impl;

    private ParticleAccess() {
    }

    public static ParticleCompat get() {
        ParticleCompat current = impl;
        if (current == null) {
            synchronized (ParticleAccess.class) {
                current = impl;
                if (current == null) {
                    current = load();
                    impl = current;
                }
            }
        }
        return current;
    }

    public static boolean spawn(double x, double y, double z, String particleName) {
        return get().spawn(x, y, z, particleName);
    }

    private static ParticleCompat load() {
        String className = "com.pengin0503.itemmilestonelog.compat."
                + McVersion.current().packageSuffix() + ".client.ParticleCompatImpl";
        try {
            return ParticleCompat.class.cast(
                    Class.forName(className).getDeclaredConstructor().newInstance());
        } catch (Throwable t) {
            return (x, y, z, name) -> false;
        }
    }
}
