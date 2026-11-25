package com.pengin0503.itemmilestonelog.network;

import com.pengin0503.itemmilestonelog.ItemMilestoneLog;
import com.pengin0503.itemmilestonelog.MilestoneService;
import com.pengin0503.itemmilestonelog.compat.McCompat;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S payload requesting the player's current milestone snapshot in order to open the GUI.
 */
public record RequestSyncPayload() {
    public static final ResourceLocation TYPE =
            new ResourceLocation(ItemMilestoneLog.MOD_ID, "request_sync");

    public static void encode(RequestSyncPayload payload, FriendlyByteBuf buf) {
        // No fields.
    }

    public static RequestSyncPayload decode(FriendlyByteBuf buf) {
        return new RequestSyncPayload();
    }

    public static void handle(RequestSyncPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player != null && ModNetworking.tryAcquireRateLimit(player)) {
            context.enqueueWork(() -> {
                ItemMilestoneLog.LOGGER.debug("[ItemMilestoneLog] Received RequestSyncPayload from {}",
                        McCompat.player().profileName(player.getGameProfile()));
                MilestoneService.sendSync(player, true, null);
            });
        }
        context.setPacketHandled(true);
    }
}
