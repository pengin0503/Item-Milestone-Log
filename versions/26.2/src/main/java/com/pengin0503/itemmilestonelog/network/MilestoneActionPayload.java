package com.pengin0503.itemmilestonelog.network;

import com.pengin0503.itemmilestonelog.ItemMilestoneLog;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S payload sent when the player performs an action in the Milestone GUI.
 */
public record MilestoneActionPayload(Action action, String argument, int count) implements CustomPacketPayload {
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

    public static final Type<MilestoneActionPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(ItemMilestoneLog.MOD_ID, "milestone_action"));

    public static final StreamCodec<FriendlyByteBuf, MilestoneActionPayload> STREAM_CODEC =
            StreamCodec.of(MilestoneActionPayload::encode, MilestoneActionPayload::decode);

    private static final int MAX_ARGUMENT_LENGTH = 256;

    private static void encode(FriendlyByteBuf buf, MilestoneActionPayload payload) {
        buf.writeEnum(payload.action);
        buf.writeUtf(payload.argument == null ? "" : payload.argument, MAX_ARGUMENT_LENGTH);
        buf.writeVarInt(payload.count);
    }

    private static MilestoneActionPayload decode(FriendlyByteBuf buf) {
        Action action = buf.readEnum(Action.class);
        String arg = buf.readUtf(MAX_ARGUMENT_LENGTH);
        int count = buf.readVarInt();
        return new MilestoneActionPayload(action, arg, count);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
