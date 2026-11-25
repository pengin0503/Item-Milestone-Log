package com.pengin0503.itemmilestonelog.compat;

import com.pengin0503.itemmilestonelog.compat.client.ListCompat;
import com.pengin0503.itemmilestonelog.compat.client.NetworkCompat;
import com.pengin0503.itemmilestonelog.compat.client.ScrollCompat;
import com.pengin0503.itemmilestonelog.compat.client.SoundCompat;
import com.pengin0503.itemmilestonelog.compat.client.TextCompat;
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

    /** Client-only, lazily loaded like {@link #scroll()}. */
    public static TextCompat text() {
        return ClientHolder.TEXT;
    }

    /** Client-only, lazily loaded like {@link #scroll()}. */
    public static SoundCompat sound() {
        return ClientHolder.SOUND;
    }

    /** Client-only, lazily loaded like {@link #scroll()}. */
    public static ListCompat lists() {
        return ClientHolder.LISTS;
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
        private static final NetworkCompat NETWORK = load(NetworkCompat.class, "client.", "NetworkCompatImpl");
        private static final TextCompat TEXT = load(TextCompat.class, "client.", "TextCompatImpl");
        private static final SoundCompat SOUND = load(SoundCompat.class, "client.", "SoundCompatImpl");
        private static final ListCompat LISTS = load(ListCompat.class, "client.", "ListCompatImpl");
    }
}
