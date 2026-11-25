package com.pengin0503.itemmilestonelog.compat.client.v120_1;

import com.pengin0503.itemmilestonelog.compat.client.NetworkCompat;
import com.pengin0503.itemmilestonelog.network.ModNetworking;
import net.minecraft.client.multiplayer.ClientPacketListener;

/**
 * 1.20.1-forge {@link NetworkCompat}: sends through the Phase 4a
 * {@code ModNetworking} {@code SimpleChannel} and checks the remote channel list with
 * {@code SimpleChannel#isRemotePresent(Connection)}.
 */
public final class NetworkCompatImpl implements NetworkCompat {
    @Override
    public void sendToServer(Object payload) {
        ModNetworking.sendToServer(payload);
    }

    @Override
    public boolean isRemotePresent(ClientPacketListener connection) {
        return connection != null && ModNetworking.isRemotePresent(connection.getConnection());
    }
}
