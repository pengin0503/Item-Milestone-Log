package com.pengin0503.itemmilestonelog.compat.v121_4;

import com.pengin0503.itemmilestonelog.compat.PermissionCompat;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/**
 * 1.21 - 1.21.10 {@link PermissionCompat}: the legacy integer-level
 * {@code hasPermission(int)} / {@code hasPermissions(int)}.
 */
public final class PermissionCompatImpl implements PermissionCompat {
    @Override
    public boolean sourceHasPermission(CommandSourceStack source, int level) {
        return source.hasPermission(level);
    }

    @Override
    public boolean playerHasPermission(ServerPlayer player, int level) {
        return player.hasPermissions(level);
    }
}
