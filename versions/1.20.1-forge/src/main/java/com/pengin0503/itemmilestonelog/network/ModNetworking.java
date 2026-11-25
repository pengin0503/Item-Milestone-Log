package com.pengin0503.itemmilestonelog.network;

import com.pengin0503.itemmilestonelog.ItemMilestoneLog;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Registers the optional Forge {@link SimpleChannel} used for GUI synchronization and actions.
 *
 * <p>The channel accepts clients without the mod ({@link NetworkRegistry#acceptMissingOr(String)}),
 * so vanilla clients can still join a dedicated server and use all commands normally. Outgoing
 * packets must be guarded with {@link #isRemotePresent(ServerPlayer)} (see
 * {@code MilestoneService.sendSync}).
 */
public final class ModNetworking {
    private static final String PROTOCOL_VERSION = "1";
    private static final ResourceLocation CHANNEL_NAME =
            new ResourceLocation(ItemMilestoneLog.MOD_ID, "main");

    /**
     * Created lazily so that unit tests touching the pure rate-limit logic do not need the Forge
     * event bus / launcher environment that channel registration requires. {@link #registerPayloads()}
     * is called from the mod constructor, before {@link NetworkRegistry} is locked.
     */
    private static volatile SimpleChannel channel;

    private static SimpleChannel channel() {
        SimpleChannel result = channel;
        if (result == null) {
            synchronized (ModNetworking.class) {
                result = channel;
                if (result == null) {
                    result = NetworkRegistry.newSimpleChannel(
                            CHANNEL_NAME,
                            () -> PROTOCOL_VERSION,
                            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION),
                            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION));
                    channel = result;
                }
            }
        }
        return result;
    }

    private static final long REFILL_INTERVAL_NANOS = 50_000_000L; // 20 actions/sec steady rate
    private static final int MAX_BURST_TOKENS = 40;
    private static final int MAX_RATE_LIMIT_ENTRIES = 1024;
    private static final long RATE_LIMIT_IDLE_NANOS = 60_000_000_000L; // 60s; a bucket fully refills in ~2s, so older entries are equivalent to fresh ones
    private static final long CLEANUP_COOLDOWN_NANOS = 10_000_000_000L; // 10s between cleanup sweeps when the map stays large
    private static final Map<UUID, RateLimitState> RATE_LIMITS = new ConcurrentHashMap<>();
    private static volatile long lastCleanupNanos = System.nanoTime();

    private static final class RateLimitState {
        long lastNanos = System.nanoTime();
        int tokens = MAX_BURST_TOKENS;
    }

    private ModNetworking() {
    }

    /** Registers every payload on the channel. Must run before {@link NetworkRegistry} is locked. */
    public static void registerPayloads() {
        SimpleChannel ch = channel();
        int id = 0;
        ch.messageBuilder(RequestSyncPayload.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RequestSyncPayload::encode)
                .decoder(RequestSyncPayload::decode)
                .consumerNetworkThread(RequestSyncPayload::handle)
                .add();
        ch.messageBuilder(MilestoneActionPayload.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(MilestoneActionPayload::encode)
                .decoder(MilestoneActionPayload::decode)
                .consumerNetworkThread(MilestoneActionPayload::handle)
                .add();
        ch.messageBuilder(SyncMilestonesPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SyncMilestonesPayload::encode)
                .decoder(SyncMilestonesPayload::decode)
                .consumerNetworkThread(SyncMilestonesPayload::handle)
                .add();
        ch.messageBuilder(SyncExtraPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SyncExtraPayload::encode)
                .decoder(SyncExtraPayload::decode)
                .consumerNetworkThread(SyncExtraPayload::handle)
                .add();
    }

    /** {@code channel.isRemotePresent(player.connection.connection)}; false for vanilla clients. */
    public static boolean isRemotePresent(ServerPlayer player) {
        return player != null
                && player.connection != null
                && channel().isRemotePresent(player.connection.connection);
    }

    /**
     * Client-side remote-presence check: Forge 1.20.1 has no
     * {@code ClientPacketListener#hasChannel(ResourceLocation)}, so the GUI asks the
     * {@code SimpleChannel} whether the server registered our channel during the handshake.
     */
    public static boolean isRemotePresent(Connection connection) {
        return connection != null && channel().isRemotePresent(connection);
    }

    /** Server-to-client send; callers must check {@link #isRemotePresent(ServerPlayer)} first. */
    public static <MSG> void sendToPlayer(ServerPlayer player, MSG message) {
        channel().send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    /** Client-to-server send, used by the client GUI call sites (ported in Phase 4b). */
    public static <MSG> void sendToServer(MSG message) {
        channel().sendToServer(message);
    }

    /** Drops a player's rate-limit state, e.g. when they log out, so the map stays bounded by online players. */
    public static void removeRateLimit(UUID uuid) {
        RATE_LIMITS.remove(uuid);
    }

    static boolean tryAcquireRateLimit(ServerPlayer player) {
        return tryAcquireRateLimit(player.getUUID());
    }

    // Package-private for tests (a ServerPlayer cannot be constructed without a live server).
    static boolean tryAcquireRateLimit(UUID playerId) {
        long now = System.nanoTime();
        if (RATE_LIMITS.size() > MAX_RATE_LIMIT_ENTRIES && now - lastCleanupNanos > CLEANUP_COOLDOWN_NANOS) {
            lastCleanupNanos = now;
            // Evict only idle entries instead of clearing every player's limits.
            RATE_LIMITS.entrySet().removeIf(entry -> {
                RateLimitState s = entry.getValue();
                synchronized (s) {
                    return now - s.lastNanos > RATE_LIMIT_IDLE_NANOS;
                }
            });
        }
        RateLimitState state = RATE_LIMITS.computeIfAbsent(playerId, u -> new RateLimitState());
        synchronized (state) {
            long elapsed = now - state.lastNanos;
            if (elapsed >= REFILL_INTERVAL_NANOS) {
                int refill = (int) (elapsed / REFILL_INTERVAL_NANOS);
                if (state.tokens + refill >= MAX_BURST_TOKENS) {
                    state.tokens = MAX_BURST_TOKENS;
                    state.lastNanos = now;
                } else {
                    state.tokens += refill;
                    state.lastNanos += refill * REFILL_INTERVAL_NANOS;
                }
            }
            if (state.tokens <= 0) {
                return false;
            }
            state.tokens--;
            return true;
        }
    }

    /**
     * Client GUI handler registration. The handlers themselves (screen opening) are ported in
     * Phase 4b; keeping the indirection in {@link ClientNetworkHandlers} keeps client screen
     * classes out of the common/server code paths.
     */
    public static void setClientSyncHandler(Consumer<SyncMilestonesPayload> handler) {
        ClientNetworkHandlers.setSyncHandler(handler);
    }

    /** Client GUI extra-metadata handler registration (see {@link #setClientSyncHandler}). */
    public static void setClientSyncExtraHandler(Consumer<SyncExtraPayload> handler) {
        ClientNetworkHandlers.setSyncExtraHandler(handler);
    }
}
