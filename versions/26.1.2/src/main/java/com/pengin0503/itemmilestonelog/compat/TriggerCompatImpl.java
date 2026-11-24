package com.pengin0503.itemmilestonelog.compat;

import com.pengin0503.itemmilestonelog.ModTriggers;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;

import javax.annotation.Nullable;

/**
 * Default {@link TriggerCompat}: delegates to {@link ModTriggers}, whose registered
 * trigger classes are version-specific even though this delegation is not.
 */
public final class TriggerCompatImpl implements TriggerCompat {
    @Override
    public void register(IEventBus modEventBus) {
        ModTriggers.register(modEventBus);
    }

    @Override
    public void trigger(@Nullable ServerPlayer player, @Nullable TargetKey key,
                        boolean worldFirst, boolean worldFirstEnabled) {
        ModTriggers.trigger(player, key, worldFirst, worldFirstEnabled);
    }
}
