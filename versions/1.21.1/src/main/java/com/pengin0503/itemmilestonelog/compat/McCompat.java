package com.pengin0503.itemmilestonelog.compat;

/**
 * N252: single access point for the version-specific compat implementations of the
 * 1.21.x line. All implementations live in {@code compat.v121_*} packages and are loaded
 * reflectively by name, so a class compiled against another version's API is only ever
 * loaded on that version (mirrors the 26.x N251 McCompat; docs/DESIGN_1.3.0.md).
 */
public final class McCompat {
    private static final TriggerCompat TRIGGERS = load(TriggerCompat.class, "TriggerCompatImpl");
    private static final RegistryCompat REGISTRIES = load(RegistryCompat.class, "RegistryCompatImpl");
    private static final NbtCompat NBT = load(NbtCompat.class, "NbtCompatImpl");
    private static final GameTestCompat GAME_TEST = load(GameTestCompat.class, "GameTestCompatImpl");

    private McCompat() {
    }

    public static TriggerCompat triggers() {
        return TRIGGERS;
    }

    public static RegistryCompat registries() {
        return REGISTRIES;
    }

    public static NbtCompat nbt() {
        return NBT;
    }

    public static GameTestCompat gameTest() {
        return GAME_TEST;
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
