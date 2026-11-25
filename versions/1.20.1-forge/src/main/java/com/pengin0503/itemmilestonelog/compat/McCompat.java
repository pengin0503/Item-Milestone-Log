package com.pengin0503.itemmilestonelog.compat;

import com.pengin0503.itemmilestonelog.compat.client.NetworkCompat;
import com.pengin0503.itemmilestonelog.compat.client.ScrollCompat;
import com.pengin0503.itemmilestonelog.compat.client.TooltipCompat;

/**
 * Single access point for the compat implementations of the 1.20.1-forge tree.
 * All implementations live in {@code compat.v120_1} (and the client-only ones in
 * {@code compat.client.v120_1}) and are loaded reflectively by name, mirroring the
 * McCompat shape used by the 1.21.x tree (docs/DESIGN_1.20.1.md §4.2).
 */
public final class McCompat {
    private static final TriggerCompat TRIGGERS = load(TriggerCompat.class, "TriggerCompatImpl");
    private static final RegistryCompat REGISTRIES = load(RegistryCompat.class, "RegistryCompatImpl");
    private static final NbtCompat NBT = load(NbtCompat.class, "NbtCompatImpl");
    private static final GameTestCompat GAME_TEST = load(GameTestCompat.class, "GameTestCompatImpl");
    private static final ReloadCompat RELOAD = load(ReloadCompat.class, "ReloadCompatImpl");
    private static final DataCompat DATA = load(DataCompat.class, "DataCompatImpl");
    private static final ChatCompat CHAT = load(ChatCompat.class, "ChatCompatImpl");
    private static final PlayerCompat PLAYER = load(PlayerCompat.class, "PlayerCompatImpl");
    private static final PermissionCompat PERMISSIONS = load(PermissionCompat.class, "PermissionCompatImpl");

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

    public static PlayerCompat player() {
        return PLAYER;
    }

    public static PermissionCompat permissions() {
        return PERMISSIONS;
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

    /** Client-only, lazily loaded like {@link #scroll()}. */
    public static NetworkCompat network() {
        return ClientHolder.NETWORK;
    }

    private static final class ClientHolder {
        private static final ScrollCompat SCROLL = load(ScrollCompat.class, "client.", "ScrollCompatImpl");
        private static final TooltipCompat TOOLTIP = load(TooltipCompat.class, "client.", "TooltipCompatImpl");
        private static final NetworkCompat NETWORK = load(NetworkCompat.class, "client.", "NetworkCompatImpl");
    }
}
