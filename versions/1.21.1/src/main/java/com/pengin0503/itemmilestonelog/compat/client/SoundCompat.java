package com.pengin0503.itemmilestonelog.compat.client;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;

/**
 * N252 (1.3.1): client sound playback. MC 1.21.6 changed the return type of
 * {@code SoundManager.play(SoundInstance)} from {@code void} to
 * {@code SoundEngine.PlayResult}, so (as with {@link TextCompat}) a shared class compiled
 * against one side throws {@code NoSuchMethodError} on the other. The return value is
 * never used. Client-only.
 */
public interface SoundCompat {
    void play(SoundManager manager, SoundInstance sound);
}
