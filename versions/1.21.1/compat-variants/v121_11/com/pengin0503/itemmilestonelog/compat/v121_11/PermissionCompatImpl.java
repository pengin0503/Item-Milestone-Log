package com.pengin0503.itemmilestonelog.compat.v121_11;

import com.pengin0503.itemmilestonelog.compat.PermissionCompat;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionCheck;

/**
 * 1.21.11+ {@link PermissionCompat}: the integer levels map onto the {@code Commands.LEVEL_*}
 * {@link PermissionCheck}s, checked against the source's / player's {@code PermissionSet}.
 */
public final class PermissionCompatImpl implements PermissionCompat {
    private static PermissionCheck checkForLevel(int level) {
        return switch (level) {
            case 0 -> Commands.LEVEL_ALL;
            case 1 -> Commands.LEVEL_MODERATORS;
            case 3 -> Commands.LEVEL_ADMINS;
            case 4 -> Commands.LEVEL_OWNERS;
            default -> Commands.LEVEL_GAMEMASTERS;
        };
    }

    @Override
    public boolean sourceHasPermission(CommandSourceStack source, int level) {
        return checkForLevel(level).check(source.permissions());
    }

    @Override
    public boolean playerHasPermission(ServerPlayer player, int level) {
        return checkForLevel(level).check(player.permissions());
    }
}
