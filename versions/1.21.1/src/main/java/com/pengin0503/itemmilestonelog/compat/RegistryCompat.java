package com.pengin0503.itemmilestonelog.compat;

import net.minecraft.network.chat.Component;
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

    /**
     * The display name of {@code item}. {@code Item#getDescription()} was removed in 1.21.2;
     * 1.21.2+ uses {@code item.getName(new ItemStack(item))}.
     */
    Component itemName(Item item);
}
