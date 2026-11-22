package com.pengin0503.itemmilestonelog.network;

import com.pengin0503.itemmilestonelog.ItemMilestoneLog;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S payload requesting the player's current milestone snapshot in order to open the GUI.
 */
public record RequestSyncPayload() implements CustomPacketPayload {
    public static final Type<RequestSyncPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(ItemMilestoneLog.MOD_ID, "request_sync"));

    public static final StreamCodec<FriendlyByteBuf, RequestSyncPayload> STREAM_CODEC =
            StreamCodec.unit(new RequestSyncPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
