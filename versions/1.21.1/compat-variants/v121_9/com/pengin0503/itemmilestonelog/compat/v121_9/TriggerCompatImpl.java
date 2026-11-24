package com.pengin0503.itemmilestonelog.compat.v121_9;

import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.compat.TriggerCompat;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;

import javax.annotation.Nullable;

/**
 * Per-version {@link TriggerCompat}: delegates to this version's {@link ModTriggers},
 * whose registered trigger classes differ across the 1.21.x line. Loaded reflectively by
 * {@code McCompat}; only the running version's variant is ever loaded.
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
