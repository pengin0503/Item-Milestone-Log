package com.pengin0503.itemmilestonelog.compat.client;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * N252: client-to-server payload sending. NeoForge removed
 * {@code PacketDistributor.sendToServer(...)} in favour of
 * {@code ClientPacketDistributor.sendToServer(...)} partway through its 21.7 line (MC 1.21.7;
 * 21.7.0-beta has only the former, 21.7.25-beta only the latter; 21.8+ only the latter), so
 * the call goes through this interface. Client-only.
 */
public interface NetworkCompat {
    void sendToServer(CustomPacketPayload payload);
}
