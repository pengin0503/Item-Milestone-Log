package com.pengin0503.itemmilestonelog.compat.v121_11;

import com.mojang.authlib.GameProfile;
import com.pengin0503.itemmilestonelog.compat.PlayerCompat;

/**
 * 1.21.9+ {@link PlayerCompat}: the record accessor {@code GameProfile.name()}.
 */
public final class PlayerCompatImpl implements PlayerCompat {
    @Override
    public String profileName(GameProfile profile) {
        return profile.name();
    }
}
