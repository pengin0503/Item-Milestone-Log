package com.pengin0503.itemmilestonelog.compat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.Item;

import javax.annotation.Nullable;

/**
 * N252: built-in registry lookups. 1.21.2+ changed {@code Registry#get(ResourceLocation)} to
 * return {@code Optional<Holder.Reference<T>>}, so value lookups go through this interface.
 * {@code getKey} / {@code containsKey} / iteration are unchanged and stay direct calls.
 */
public interface RegistryCompat {
    /** The item registered as {@code id}, or {@link net.minecraft.world.item.Items#AIR} when absent. */
    Item itemOrAir(ResourceLocation id);

    /** The sound event registered as {@code id}, or {@code null} when absent. */
    @Nullable
    SoundEvent soundOrNull(ResourceLocation id);
}
