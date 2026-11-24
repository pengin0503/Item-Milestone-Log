package com.pengin0503.itemmilestonelog.compat;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/**
 * N252: vanilla permission-level checks. MC 1.21.11 removed
 * {@code CommandSourceStack.hasPermission(int)} / {@code Entity.hasPermissions(int)} in favour
 * of {@code PermissionCheck} / {@code PermissionSet}, so the checks go through this interface.
 */
public interface PermissionCompat {
    /** Whether {@code source} has at least the given vanilla permission level. */
    boolean sourceHasPermission(CommandSourceStack source, int level);

    /** Whether {@code player} has at least the given vanilla permission level. */
    boolean playerHasPermission(ServerPlayer player, int level);
}
