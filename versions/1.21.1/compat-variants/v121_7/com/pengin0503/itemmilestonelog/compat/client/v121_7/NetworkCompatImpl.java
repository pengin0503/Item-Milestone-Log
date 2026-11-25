package com.pengin0503.itemmilestonelog.compat.client.v121_7;

import com.pengin0503.itemmilestonelog.compat.client.NetworkCompat;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * 1.21.7 {@link NetworkCompat}: resolved reflectively, because the NeoForge 21.7 line
 * moved the method mid-line. Early 21.7 builds (21.7.0-beta, the profile's compile
 * target) only have the legacy {@code PacketDistributor.sendToServer(...)}; later 21.7
 * builds (e.g. 21.7.25-beta) removed it and only have
 * {@code ClientPacketDistributor.sendToServer(...)}. Neither class is referenced
 * directly, so this class links on every 21.7 build. The new location is tried first.
 */
public final class NetworkCompatImpl implements NetworkCompat {
    private static final String[] DISTRIBUTORS = {
            "net.neoforged.neoforge.client.network.ClientPacketDistributor",
            "net.neoforged.neoforge.network.PacketDistributor",
    };

    /** Resolved once when the implementation is loaded; null when neither method exists. */
    private final MethodHandle sendToServer = resolve();

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        if (sendToServer == null) {
            throw new IllegalStateException("No NeoForge sendToServer(CustomPacketPayload, CustomPacketPayload...) found in "
                    + String.join(" or ", DISTRIBUTORS));
        }
        try {
            sendToServer.invokeExact(payload, new CustomPacketPayload[0]);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("sendToServer failed", t);
        }
    }

    /**
     * {@code static void sendToServer(CustomPacketPayload, CustomPacketPayload...)} of the first
     * class in {@link #DISTRIBUTORS} that has it. Failure is deferred to the first send (rather
     * than thrown here) so the other client compat implementations still load.
     */
    private static MethodHandle resolve() {
        MethodType type = MethodType.methodType(void.class, CustomPacketPayload.class, CustomPacketPayload[].class);
        ClassLoader loader = NetworkCompatImpl.class.getClassLoader();
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        for (String name : DISTRIBUTORS) {
            try {
                return lookup.findStatic(Class.forName(name, false, loader), "sendToServer", type);
            } catch (ReflectiveOperationException e) {
                // Not on this NeoForge build; try the next location.
            }
        }
        return null;
    }
}
