package com.pengin0503.itemmilestonelog.compat;

/**
 * N251: single access point for the version-specific compat implementations.
 * Implementations whose bodies differ per version live in {@code compat.v26_*}
 * packages and are loaded reflectively by name, so a class compiled against
 * another version's API is only ever loaded on that version.
 */
public final class McCompat {
    private static final TriggerCompat TRIGGERS = new TriggerCompatImpl();
    private static final TagEventCompat TAG_EVENTS = load(TagEventCompat.class, "TagEventCompatImpl");
    private static final ConfigCompat CONFIG = load(ConfigCompat.class, "ConfigCompatImpl");

    private McCompat() {
    }

    public static TriggerCompat triggers() {
        return TRIGGERS;
    }

    public static TagEventCompat tagEvents() {
        return TAG_EVENTS;
    }

    public static ConfigCompat config() {
        return CONFIG;
    }

    private static <T> T load(Class<T> iface, String simpleName) {
        String className = "com.pengin0503.itemmilestonelog.compat."
                + McVersion.current().packageSuffix() + "." + simpleName;
        try {
            return iface.cast(Class.forName(className).getDeclaredConstructor().newInstance());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to load compat implementation " + className, e);
        }
    }
}
