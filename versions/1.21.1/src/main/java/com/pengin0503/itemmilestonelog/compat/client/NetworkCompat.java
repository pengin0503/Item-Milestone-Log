package com.pengin0503.itemmilestonelog.compat.client;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * N252: client-to-server payload sending. NeoForge for MC 1.21.8 removed
 * {@code PacketDistributor.sendToServer(...)} in favour of
 * {@code ClientPacketDistributor.sendToServer(...)}, so the call goes through this
 * interface. Client-only.
 */
public interface NetworkCompat {
    void sendToServer(CustomPacketPayload payload);
}
