package com.pengin0503.itemmilestonelog.compat.v121_4;

import com.pengin0503.itemmilestonelog.compat.RegistryCompat;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;

/**
 * 1.21.2+ {@link RegistryCompat}: {@code Registry#get(ResourceLocation)} returns
 * {@code Optional<Holder.Reference<T>>} since 1.21.2, so the value is unwrapped via
 * {@link Holder#value()}. An absent id yields {@link Items#AIR} for items and {@code null}
 * for sound events, matching the legacy semantics.
 */
public final class RegistryCompatImpl implements RegistryCompat {
    @Override
    public Item itemOrAir(ResourceLocation id) {
        return BuiltInRegistries.ITEM.get(id).map(Holder::value).orElse(Items.AIR);
    }

    @Override
    @Nullable
    public SoundEvent soundOrNull(ResourceLocation id) {
        return BuiltInRegistries.SOUND_EVENT.get(id).map(Holder::value).orElse(null);
    }
}
