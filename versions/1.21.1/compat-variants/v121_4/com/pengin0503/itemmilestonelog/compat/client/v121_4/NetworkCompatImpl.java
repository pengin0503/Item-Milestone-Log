package com.pengin0503.itemmilestonelog.compat.client.v121_4;

import com.pengin0503.itemmilestonelog.compat.client.NetworkCompat;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 1.21 - 1.21.7 {@link NetworkCompat}: the legacy {@code PacketDistributor.sendToServer(...)}.
 */
public final class NetworkCompatImpl implements NetworkCompat {
    @Override
    public void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }
}
