package com.pengin0503.itemmilestonelog.api;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;

/**
 * Posted on {@link net.neoforged.neoforge.common.NeoForge#EVENT_BUS} on the server
 * when a player completes all resolvable targets of a preset for the first time.
 *
 * <p>This event is not cancellable. It is fired regardless of the {@code announcePresetCompletion}
 * and {@code notifyPlayer} notification settings, after the preset ID has been added to
 * the player's {@code completedPresets} set and after {@link MilestoneRecordedEvent}
 * for the triggering milestone record. It is not fired when {@code presetsEnabled} is
 * {@code false}, nor for imports or resets.
 *
 * <p>Example KubeJS server script granting a reward on preset completion:
 * <pre>{@code
 * NeoForgeEvents.onEvent('com.pengin0503.itemmilestonelog.api.PresetCompletedEvent', event => {
 *   if (event.getPresetId() == 'vanilla_endgame') {
 *     event.getPlayer().give('minecraft:nether_star')
 *   }
 * })
 * }</pre>
 */
public class PresetCompletedEvent extends Event {
    private final ServerPlayer player;
    private final String presetId;
    private final boolean builtin;

    /**
     * Creates an event for a newly completed preset.
     *
     * @param player   the player who completed the preset
     * @param presetId ID of the completed preset
     * @param builtin  {@code true} if the preset is built-in, {@code false} if it is a custom preset
     */
    public PresetCompletedEvent(ServerPlayer player, String presetId, boolean builtin) {
        this.player = player;
        this.presetId = presetId;
        this.builtin = builtin;
    }

    /** Returns the player who completed the preset. */
    public ServerPlayer getPlayer() {
        return player;
    }

    /** Returns the ID of the completed preset. */
    public String getPresetId() {
        return presetId;
    }

    /** Returns {@code true} if the completed preset is a built-in preset, or {@code false} for a custom preset. */
    public boolean isBuiltin() {
        return builtin;
    }
}
