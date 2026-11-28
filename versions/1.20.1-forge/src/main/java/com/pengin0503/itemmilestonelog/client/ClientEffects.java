package com.pengin0503.itemmilestonelog.client;

import com.pengin0503.itemmilestonelog.Config;
import com.pengin0503.itemmilestonelog.EffectRateLimit;
import com.pengin0503.itemmilestonelog.LowPowerModePolicy;
import com.pengin0503.itemmilestonelog.compat.client.ParticleAccess;
import com.pengin0503.itemmilestonelog.network.MilestoneEffectPayload;
import net.minecraft.client.Minecraft;

/**
 * F-150-69 / F-150-83: receives the one optional S2C effect channel.
 *
 * <p>When the config is OFF the packet is accepted and discarded (the channel stays registered),
 * so toggling the setting needs no relogin. Particles use the reflective per-profile compat hook;
 * no version-varying particle call is linked here. Preset completions reuse F-150-70's screenshot
 * settings instead of adding a channel or a config key.
 */
public final class ClientEffects {
    private static final EffectRateLimit RATE_LIMIT = new EffectRateLimit();

    private ClientEffects() {
    }

    public static void onEffect(MilestoneEffectPayload payload) {
        if (payload == null) {
            return;
        }
        if (payload.kind() == MilestoneEffectPayload.Kind.PRESET_COMPLETE) {
            ClientLocalOutput.capturePresetComplete();
            return;
        }
        if (!LowPowerModePolicy.particlesAllowed(Config.lowPowerMode(),
                MilestoneHudOverlay.effectiveOptions().clientEffectEnabled(), Config.reducedMotion())) {
            return;
        }
        if (!RATE_LIMIT.allow(System.currentTimeMillis())) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        ParticleAccess.spawn(mc.player.getX(), mc.player.getY() + 1.0D, mc.player.getZ(), "FLAME");
    }

    /** Clears the one-second window on logout. */
    public static void onLogout() {
        RATE_LIMIT.clear();
    }
}
