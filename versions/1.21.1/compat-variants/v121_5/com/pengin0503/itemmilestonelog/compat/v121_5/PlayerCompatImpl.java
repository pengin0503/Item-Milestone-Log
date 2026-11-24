package com.pengin0503.itemmilestonelog.compat.v121_5;

import com.mojang.authlib.GameProfile;
import com.pengin0503.itemmilestonelog.compat.PlayerCompat;

/**
 * 1.21 - 1.21.8 {@link PlayerCompat}: the legacy class-style {@code GameProfile.getName()}.
 */
public final class PlayerCompatImpl implements PlayerCompat {
    @Override
    public String profileName(GameProfile profile) {
        return profile.getName();
    }
}
