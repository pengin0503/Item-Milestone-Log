package com.pengin0503.itemmilestonelog.compat;

import com.mojang.authlib.GameProfile;

/**
 * N252: player profile access. MC 1.21.9 turned {@link GameProfile} into a record, removing
 * {@code getName()} in favour of {@code name()}, so the call goes through this interface.
 */
public interface PlayerCompat {
    /** The profile's player name. */
    String profileName(GameProfile profile);
}
