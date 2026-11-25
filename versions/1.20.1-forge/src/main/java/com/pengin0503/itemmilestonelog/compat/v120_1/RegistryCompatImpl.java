package com.pengin0503.itemmilestonelog.compat.v120_1;

import com.pengin0503.itemmilestonelog.compat.RegistryCompat;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.Item;

import javax.annotation.Nullable;

/**
 * 1.21 / 1.21.1 {@link RegistryCompat}: legacy {@code Registry#get(ResourceLocation)}.
 * {@code BuiltInRegistries.ITEM} is a defaulted registry (default {@code minecraft:air}),
 * so an unknown id already yields {@code Items.AIR}; {@code SOUND_EVENT} is not defaulted
 * and yields {@code null}.
 */
public final class RegistryCompatImpl implements RegistryCompat {
    @Override
    public ResourceLocation rl(String namespace, String path) {
        return new ResourceLocation(namespace, path);
    }

    @Override
    @Nullable
    public ResourceLocation parseRl(String value) {
        return ResourceLocation.tryParse(value);
    }

    @Override
    public Item itemOrAir(ResourceLocation id) {
        return BuiltInRegistries.ITEM.get(id);
    }

    @Override
    @Nullable
    public SoundEvent soundOrNull(ResourceLocation id) {
        return BuiltInRegistries.SOUND_EVENT.get(id);
    }

    @Override
    public Component itemName(Item item) {
        return item.getDescription();
    }
}
