package com.pengin0503.itemmilestonelog.compat.client;

import net.minecraft.client.multiplayer.ClientPacketListener;

/**
 * 1.20.1-forge client networking facet.
 *
 * <p>The 1.21.1 facet took a {@code CustomPacketPayload} and relied on
 * {@code ClientPacketListener#hasChannel(ResourceLocation)}; neither exists in 1.20.1.
 * Every GUI packet travels over the single optional {@code itemmilestonelog:main}
 * Forge {@code SimpleChannel}, so sending takes the payload object directly and
 * presence is checked with Forge's {@code SimpleChannel#isRemotePresent(Connection)}.
 * Client-only.
 */
public interface NetworkCompat {
    void sendToServer(Object payload);

    boolean isRemotePresent(ClientPacketListener connection);
}
