package com.pengin0503.itemmilestonelog.compat;

import com.pengin0503.itemmilestonelog.api.TargetKey;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;

import javax.annotation.Nullable;

/**
 * N251: advancement-trigger operations. The trigger classes themselves differ per
 * 26.x version (docs/DESIGN_1.3.0.md section 1.1 A), so registration and firing are
 * isolated behind this interface.
 */
public interface TriggerCompat {
    void register(IEventBus modEventBus);

    void trigger(@Nullable ServerPlayer player, @Nullable TargetKey key,
                 boolean worldFirst, boolean worldFirstEnabled);
}
