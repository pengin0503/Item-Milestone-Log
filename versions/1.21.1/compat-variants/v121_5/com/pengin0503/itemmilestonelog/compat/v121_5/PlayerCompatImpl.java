package com.pengin0503.itemmilestonelog.compat.v121_5;

import com.mojang.authlib.GameProfile;
import com.pengin0503.itemmilestonelog.compat.PlayerCompat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;

/**
 * 1.21 - 1.21.8 {@link PlayerCompat}: the legacy class-style {@code GameProfile.getName()}.
 */
public final class PlayerCompatImpl implements PlayerCompat {
    @Override
    public String profileName(GameProfile profile) {
        return profile.getName();
    }

    @Override
    public boolean isSingleplayerOwner(MinecraftServer server, Player player) {
        return server.isSingleplayerOwner(player.getGameProfile());
    }

    @Override
    public void playNotifySound(ServerPlayer player, SoundEvent event, SoundSource source, float volume, float pitch) {
        player.playNotifySound(event, source, volume, pitch);
    }
}
