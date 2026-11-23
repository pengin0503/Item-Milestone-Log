package com.pengin0503.itemmilestonelog.network;

import com.pengin0503.itemmilestonelog.MilestoneService;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Registers optional network payloads for GUI synchronization and actions.
 *
 * The registrar is marked {@code optional()} so vanilla clients without this mod
 * can still connect to a dedicated server and use all commands normally.
 */
public final class ModNetworking {
    private static final String PROTOCOL_VERSION = "1";
    private static final long REFILL_INTERVAL_NANOS = 50_000_000L; // 20 actions/sec steady rate
    private static final int MAX_BURST_TOKENS = 40;
    private static final int MAX_RATE_LIMIT_ENTRIES = 1024;
    private static final long RATE_LIMIT_IDLE_NANOS = 60_000_000_000L; // 60s; a bucket fully refills in ~2s, so older entries are equivalent to fresh ones
    private static final Map<UUID, RateLimitState> RATE_LIMITS = new ConcurrentHashMap<>();

    private static final class RateLimitState {
        long lastNanos = System.nanoTime();
        int tokens = MAX_BURST_TOKENS;
    }

    /** Drops a player's rate-limit state, e.g. when they log out, so the map stays bounded by online players. */
    public static void removeRateLimit(UUID uuid) {
        RATE_LIMITS.remove(uuid);
    }

    private static boolean tryAcquireRateLimit(ServerPlayer player) {
        long now = System.nanoTime();
        if (RATE_LIMITS.size() > MAX_RATE_LIMIT_ENTRIES) {
            // Evict only idle entries instead of clearing every player's limits.
            RATE_LIMITS.entrySet().removeIf(entry -> {
                RateLimitState s = entry.getValue();
                synchronized (s) {
                    return now - s.lastNanos > RATE_LIMIT_IDLE_NANOS;
                }
            });
        }
        RateLimitState state = RATE_LIMITS.computeIfAbsent(player.getUUID(), u -> new RateLimitState());
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

    private static volatile Consumer<SyncMilestonesPayload> clientSyncHandler = payload -> {
    };

    private ModNetworking() {
    }

    public static void setClientSyncHandler(Consumer<SyncMilestonesPayload> handler) {
        clientSyncHandler = handler != null ? handler : payload -> {
        };
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION).optional();

        registrar.playToServer(
                RequestSyncPayload.TYPE,
                RequestSyncPayload.STREAM_CODEC,
                ModNetworking::handleRequestSync
        );
        registrar.playToServer(
                MilestoneActionPayload.TYPE,
                MilestoneActionPayload.STREAM_CODEC,
                ModNetworking::handleAction
        );
        registrar.playToClient(
                SyncMilestonesPayload.TYPE,
                SyncMilestonesPayload.STREAM_CODEC,
                ModNetworking::handleSyncClient
        );
    }

    private static void handleRequestSync(RequestSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player && tryAcquireRateLimit(player)) {
                SyncMilestonesPayload sync = MilestoneService.createSyncPayload(player, true, null);
                PacketDistributor.sendToPlayer(player, sync);
            }
        });
    }

    private static void handleAction(MilestoneActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || !tryAcquireRateLimit(player)) {
                return;
            }
            MilestoneService.ActionResult result = switch (payload.action()) {
                case ADD_TARGET -> MilestoneService.addTarget(player, payload.argument(), payload.count());
                case ADD_HAND -> MilestoneService.addHandTarget(player, payload.count());
                case REMOVE_TARGET -> MilestoneService.removeTarget(player, payload.argument(), payload.count());
                case APPLY_PRESET -> MilestoneService.applyPreset(player, payload.argument());
                case RESET_RECORD -> MilestoneService.resetRecord(player, payload.argument(), payload.count());
                case RESET_ALL -> MilestoneService.resetAllRecords(player);
                case SAVE_PRESET -> MilestoneService.savePreset(player, payload.argument());
                case DELETE_PRESET -> MilestoneService.deletePreset(player, payload.argument());
            };
            SyncMilestonesPayload sync = MilestoneService.createSyncPayload(player, false, result.message());
            PacketDistributor.sendToPlayer(player, sync);
            if (result.success() && (payload.action() == MilestoneActionPayload.Action.SAVE_PRESET
                    || payload.action() == MilestoneActionPayload.Action.DELETE_PRESET)) {
                MilestoneService.syncAllOnlineGuiPlayers(player.server, player);
            }
        });
    }

    private static void handleSyncClient(SyncMilestonesPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> clientSyncHandler.accept(payload));
    }
}
