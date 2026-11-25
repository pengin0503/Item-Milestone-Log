package com.pengin0503.itemmilestonelog.compat.client.v121_4;

import com.pengin0503.itemmilestonelog.compat.client.SoundCompat;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;

/**
 * 1.21.4 {@link SoundCompat}: plain {@code SoundManager.play(SoundInstance)}, compiled against
 * this profile's version (returns {@code void}).
 */
public final class SoundCompatImpl implements SoundCompat {
    @Override
    public void play(SoundManager manager, SoundInstance sound) {
        manager.play(sound);
    }
}
