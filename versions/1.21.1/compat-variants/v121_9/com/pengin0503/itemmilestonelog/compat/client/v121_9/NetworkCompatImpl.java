package com.pengin0503.itemmilestonelog.compat.client.v121_9;

import com.pengin0503.itemmilestonelog.compat.client.NetworkCompat;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * 1.21.8+ {@link NetworkCompat}: {@code ClientPacketDistributor.sendToServer(...)}.
 */
public final class NetworkCompatImpl implements NetworkCompat {
    @Override
    public void sendToServer(CustomPacketPayload payload) {
        ClientPacketDistributor.sendToServer(payload);
    }
}
