package com.pengin0503.itemmilestonelog;

/**
 * Pure-Java privilege check shared across all Minecraft versions via {@code common/src/main/java}
 * (spec §1 constraint 11 / G1).
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class PrivilegePolicy {
    /** Vanilla gamemaster/admin permission level required for privileged operations. */
    public static final int ADMIN_PERMISSION_LEVEL = 2;

    private PrivilegePolicy() {
    }

    /**
     * Evaluates whether a command source or player is privileged for admin/management operations:
     * <ul>
     *   <li>Non-player sources (console, RCON, command blocks): {@code true} iff {@code hasAdminPermission}.</li>
     *   <li>Dedicated server players: {@code true} iff {@code hasAdminPermission}.</li>
     *   <li>Integrated server (singleplayer / LAN) players: {@code true} iff {@code isSingleplayerOwner}
     *       (the host) or {@code hasAdminPermission} (e.g. LAN guest with cheats enabled).</li>
     * </ul>
     */
    public static boolean isPrivileged(
            boolean dedicatedServer,
            boolean isPlayer,
            boolean isSingleplayerOwner,
            boolean hasAdminPermission) {
        if (!isPlayer || dedicatedServer) {
            return hasAdminPermission;
        }
        return isSingleplayerOwner || hasAdminPermission;
    }

    /** Convenience overload accepting a numeric vanilla permission level (0-4). */
    public static boolean isPrivileged(
            boolean dedicatedServer,
            boolean isPlayer,
            boolean isSingleplayerOwner,
            int permissionLevel) {
        return isPrivileged(dedicatedServer, isPlayer, isSingleplayerOwner,
                permissionLevel >= ADMIN_PERMISSION_LEVEL);
    }
}
