package com.pengin0503.itemmilestonelog.compat.v26_2;

import com.pengin0503.itemmilestonelog.ItemMilestoneLog;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import net.minecraft.advancements.triggers.CriterionTrigger;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import javax.annotation.Nullable;

/**
 * F2: registers the mod's advancement criterion triggers on {@link Registries#TRIGGER_TYPE}.
 */
public final class ModTriggers {
    public static final String MILESTONE_RECORDED_PATH = "milestone_recorded";

    private static final DeferredRegister<CriterionTrigger<?>> TRIGGERS =
            DeferredRegister.create(Registries.TRIGGER_TYPE, ItemMilestoneLog.MOD_ID);

    public static final DeferredHolder<CriterionTrigger<?>, MilestoneRecordedTrigger> MILESTONE_RECORDED =
            TRIGGERS.register(MILESTONE_RECORDED_PATH, MilestoneRecordedTrigger::new);

    private ModTriggers() {
    }

    public static void register(IEventBus modEventBus) {
        TRIGGERS.register(modEventBus);
    }

    /**
     * Fires {@code itemmilestonelog:milestone_recorded} for {@code player} if the trigger is bound
     * and {@code player} is a real server player with advancements initialized.
     */
    public static void trigger(
            @Nullable ServerPlayer player,
            @Nullable TargetKey key,
            boolean worldFirst,
            boolean worldFirstEnabled) {
        if (player == null
                || player instanceof FakePlayer
                || key == null
                || player.getAdvancements() == null
                || !MILESTONE_RECORDED.isBound()) {
            return;
        }
        MILESTONE_RECORDED.get().trigger(player, key, worldFirst, worldFirstEnabled);
    }
}
