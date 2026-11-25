package com.pengin0503.itemmilestonelog.compat.v120_1;

import com.pengin0503.itemmilestonelog.api.TargetKey;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.eventbus.api.IEventBus;

import javax.annotation.Nullable;

/**
 * F2: registers the mod's advancement criterion triggers.
 *
 * <p>1.20.1 registers triggers globally via the public {@code CriteriaTriggers.register(...)}
 * (there is no {@code Registries.TRIGGER_TYPE}); no access transformer is needed. The
 * {@link IEventBus} parameter is kept only to match the shared {@code TriggerCompat} signature.
 */
public final class ModTriggers {
    public static final String MILESTONE_RECORDED_PATH = "milestone_recorded";

    public static final MilestoneRecordedTrigger MILESTONE_RECORDED = new MilestoneRecordedTrigger();

    private static boolean registered;

    private ModTriggers() {
    }

    public static synchronized void register(IEventBus modEventBus) {
        if (registered) {
            return;
        }
        registered = true;
        CriteriaTriggers.register(MILESTONE_RECORDED);
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
                || player.getAdvancements() == null) {
            return;
        }
        MILESTONE_RECORDED.trigger(player, key, worldFirst, worldFirstEnabled);
    }
}
