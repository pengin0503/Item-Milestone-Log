package com.pengin0503.itemmilestonelog.compat;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;

/**
 * N252: player profile access. MC 1.21.9 turned {@link GameProfile} into a record, removing
 * {@code getName()} in favour of {@code name()}, so the call goes through this interface.
 */
public interface PlayerCompat {
    /** The profile's player name. */
    String profileName(GameProfile profile);

    /** Whether {@code player} owns the integrated (singleplayer) server. */
    boolean isSingleplayerOwner(MinecraftServer server, Player player);
}
