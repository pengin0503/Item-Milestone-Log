package com.pengin0503.itemmilestonelog.compat;

import com.pengin0503.itemmilestonelog.api.TargetKey;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;

import javax.annotation.Nullable;

/**
 * N252: advancement-trigger operations. The trigger classes themselves differ across
 * the 1.21.x line (1.21.11 moves the advancements.critereon package), so registration
 * and firing are isolated behind this interface (mirrors the 26.x N251 TriggerCompat).
 */
public interface TriggerCompat {
    void register(IEventBus modEventBus);

    void trigger(@Nullable ServerPlayer player, @Nullable TargetKey key,
                 boolean worldFirst, boolean worldFirstEnabled);
}
