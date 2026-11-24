package com.pengin0503.itemmilestonelog.network;

import com.pengin0503.itemmilestonelog.ItemMilestoneLog;
import com.pengin0503.itemmilestonelog.MilestoneService;
import com.pengin0503.itemmilestonelog.compat.McCompat;
import net.minecraft.server.level.ServerPlayer;
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
    private static final long CLEANUP_COOLDOWN_NANOS = 10_000_000_000L; // 10s between cleanup sweeps when the map stays large
    private static final Map<UUID, RateLimitState> RATE_LIMITS = new ConcurrentHashMap<>();
    private static volatile long lastCleanupNanos = System.nanoTime();

    private static final class RateLimitState {
        long lastNanos = System.nanoTime();
        int tokens = MAX_BURST_TOKENS;
    }

    /** Drops a player's rate-limit state, e.g. when they log out, so the map stays bounded by online players. */
    public static void removeRateLimit(UUID uuid) {
        RATE_LIMITS.remove(uuid);
    }

    private static boolean tryAcquireRateLimit(ServerPlayer player) {
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

    private static volatile Consumer<SyncMilestonesPayload> clientSyncHandler = payload -> {
    };
    private static volatile Consumer<SyncExtraPayload> clientSyncExtraHandler = payload -> {
    };

    private ModNetworking() {
    }

    public static void setClientSyncHandler(Consumer<SyncMilestonesPayload> handler) {
        clientSyncHandler = handler != null ? handler : payload -> {
        };
    }

    public static void setClientSyncExtraHandler(Consumer<SyncExtraPayload> handler) {
        clientSyncExtraHandler = handler != null ? handler : payload -> {
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
        registrar.playToClient(
                SyncExtraPayload.TYPE,
                SyncExtraPayload.STREAM_CODEC,
                ModNetworking::handleSyncExtraClient
        );
    }

    private static void handleRequestSync(RequestSyncPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !tryAcquireRateLimit(player)) {
            return;
        }
        context.enqueueWork(() -> {
            ItemMilestoneLog.LOGGER.debug("[ItemMilestoneLog] Received RequestSyncPayload from {}",
                    McCompat.player().profileName(player.getGameProfile()));
            MilestoneService.sendSync(player, true, null);
        });
    }

    private static void handleAction(MilestoneActionPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !tryAcquireRateLimit(player)) {
            return;
        }
        context.enqueueWork(() -> {
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
            MilestoneService.sendSync(player, false, result.message());
            if (result.success() && (payload.action() == MilestoneActionPayload.Action.SAVE_PRESET
                    || payload.action() == MilestoneActionPayload.Action.DELETE_PRESET)) {
                MilestoneService.syncAllOnlineGuiPlayers(player.level().getServer(), player);
            }
        });
    }

    private static void handleSyncClient(SyncMilestonesPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ItemMilestoneLog.LOGGER.debug(
                    "[ItemMilestoneLog] Received SyncMilestonesPayload (targets={}, records={}, presets={}, openScreen={})",
                    payload.targets().size(), payload.records().size(), payload.presets().size(), payload.openScreen());
            clientSyncHandler.accept(payload);
        });
    }

    private static void handleSyncExtraClient(SyncExtraPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ItemMilestoneLog.LOGGER.debug(
                    "[ItemMilestoneLog] Received SyncExtraPayload (worldFirsts={}, readOnlyPresets={})",
                    payload.worldFirsts().size(), payload.readOnlyPresets().size());
            clientSyncExtraHandler.accept(payload);
        });
    }
}
