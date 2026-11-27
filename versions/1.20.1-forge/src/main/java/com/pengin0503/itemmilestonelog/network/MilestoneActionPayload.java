package com.pengin0503.itemmilestonelog.network;

import com.pengin0503.itemmilestonelog.ItemMilestoneLog;
import com.pengin0503.itemmilestonelog.MilestoneService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S payload sent when the player performs an action in the Milestone GUI.
 */
public record MilestoneActionPayload(Action action, String argument, int count) {
    public MilestoneActionPayload(Action action, String argument) {
        this(action, argument, 1);
    }

    public enum Action {
        ADD_TARGET,
        ADD_HAND,
        REMOVE_TARGET,
        APPLY_PRESET,
        RESET_RECORD,
        RESET_ALL,
        SAVE_PRESET,
        DELETE_PRESET,
        // F-150-50 (N121): tail-only optional action; old ordinals are unchanged.
        MUTE_NOTIFICATION_SOUND
    }

    public static final ResourceLocation TYPE =
            new ResourceLocation(ItemMilestoneLog.MOD_ID, "milestone_action");

    private static final int MAX_ARGUMENT_LENGTH = 256;

    public static void encode(MilestoneActionPayload payload, FriendlyByteBuf buf) {
        buf.writeEnum(payload.action);
        buf.writeUtf(payload.argument == null ? "" : payload.argument, MAX_ARGUMENT_LENGTH);
        buf.writeVarInt(payload.count);
    }

    public static MilestoneActionPayload decode(FriendlyByteBuf buf) {
        Action action = buf.readEnum(Action.class);
        String arg = buf.readUtf(MAX_ARGUMENT_LENGTH);
        int count = buf.readVarInt();
        return new MilestoneActionPayload(action, arg, count);
    }

    public static void handle(MilestoneActionPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player != null && ModNetworking.tryAcquireRateLimit(player)) {
            context.enqueueWork(() -> {
                MilestoneService.ActionResult result = switch (payload.action()) {
                    case ADD_TARGET -> MilestoneService.addTarget(player, payload.argument(), payload.count());
                    case ADD_HAND -> MilestoneService.addHandTarget(player, payload.count());
                    case REMOVE_TARGET -> MilestoneService.removeTarget(player, payload.argument(), payload.count());
                    case APPLY_PRESET -> MilestoneService.applyPreset(player, payload.argument());
                    case RESET_RECORD -> MilestoneService.resetRecord(player, payload.argument(), payload.count());
                    case RESET_ALL -> MilestoneService.resetAllRecords(player);
                    case SAVE_PRESET -> MilestoneService.savePreset(player, payload.argument());
                    case DELETE_PRESET -> MilestoneService.deletePreset(player, payload.argument());
                    case MUTE_NOTIFICATION_SOUND -> MilestoneService.toggleMuteNotificationSounds(player);
                };
                MilestoneService.sendSync(player, false, result.message());
                if (result.success() && (payload.action() == Action.SAVE_PRESET
                        || payload.action() == Action.DELETE_PRESET)) {
                    MilestoneService.syncAllOnlineGuiPlayers(player.level().getServer(), player);
                }
            });
        }
        context.setPacketHandled(true);
    }
}
