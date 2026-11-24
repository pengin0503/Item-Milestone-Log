package com.pengin0503.itemmilestonelog.compat;

import com.pengin0503.itemmilestonelog.compat.client.ScrollCompat;
import com.pengin0503.itemmilestonelog.compat.client.TooltipCompat;

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
    private static final ReloadCompat RELOAD = load(ReloadCompat.class, "ReloadCompatImpl");
    private static final DataCompat DATA = load(DataCompat.class, "DataCompatImpl");
    private static final ChatCompat CHAT = load(ChatCompat.class, "ChatCompatImpl");

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

    public static ReloadCompat reload() {
        return RELOAD;
    }

    public static DataCompat data() {
        return DATA;
    }

    public static ChatCompat chat() {
        return CHAT;
    }

    /**
     * Client-only: the implementation references client classes, so it is loaded lazily on
     * first use (never during {@code McCompat} initialization on a dedicated server).
     */
    public static ScrollCompat scroll() {
        return ClientHolder.SCROLL;
    }

    /** Client-only, lazily loaded like {@link #scroll()}. */
    public static TooltipCompat tooltip() {
        return ClientHolder.TOOLTIP;
    }

    private static <T> T load(Class<T> iface, String simpleName) {
        return load(iface, "", simpleName);
    }

    /** {@code subPackage} is empty or ends with '.', e.g. "client.". */
    private static <T> T load(Class<T> iface, String subPackage, String simpleName) {
        String className = "com.pengin0503.itemmilestonelog.compat." + subPackage
                + McVersion.current().packageSuffix() + "." + simpleName;
        try {
            return iface.cast(Class.forName(className).getDeclaredConstructor().newInstance());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to load compat implementation " + className, e);
        }
    }

    private static final class ClientHolder {
        private static final ScrollCompat SCROLL = load(ScrollCompat.class, "client.", "ScrollCompatImpl");
        private static final TooltipCompat TOOLTIP = load(TooltipCompat.class, "client.", "TooltipCompatImpl");
    }
}
