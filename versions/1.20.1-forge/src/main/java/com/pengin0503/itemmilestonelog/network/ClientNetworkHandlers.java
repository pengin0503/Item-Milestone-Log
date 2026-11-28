package com.pengin0503.itemmilestonelog.network;

import java.util.function.Consumer;

/**
 * Holds the client-only payload handlers behind static volatile fields.
 *
 * <p>This class intentionally references no client-only classes (screens, key mappings, ...);
 * the actual handler bodies are registered from the client entry point in Phase 4b. Payload
 * handlers only invoke these sinks behind a {@code FMLEnvironment.dist.isClient()} check, so the
 * dedicated server never loads client GUI code through the common network path.
 */
public final class ClientNetworkHandlers {
    private static final Consumer<SyncMilestonesPayload> NO_OP_SYNC = payload -> {
    };
    private static final Consumer<SyncExtraPayload> NO_OP_SYNC_EXTRA = payload -> {
    };
    private static final Consumer<MilestoneEffectPayload> NO_OP_EFFECT = payload -> {
    };

    private static volatile Consumer<SyncMilestonesPayload> syncHandler = NO_OP_SYNC;
    private static volatile Consumer<SyncExtraPayload> syncExtraHandler = NO_OP_SYNC_EXTRA;
    private static volatile Consumer<MilestoneEffectPayload> effectHandler = NO_OP_EFFECT;

    private ClientNetworkHandlers() {
    }

    public static void setSyncHandler(Consumer<SyncMilestonesPayload> handler) {
        syncHandler = handler != null ? handler : NO_OP_SYNC;
    }

    public static void setSyncExtraHandler(Consumer<SyncExtraPayload> handler) {
        syncExtraHandler = handler != null ? handler : NO_OP_SYNC_EXTRA;
    }

    public static void handleSync(SyncMilestonesPayload payload) {
        syncHandler.accept(payload);
    }

    public static void handleSyncExtra(SyncExtraPayload payload) {
        syncExtraHandler.accept(payload);
    }

    public static void setEffectHandler(Consumer<MilestoneEffectPayload> handler) {
        effectHandler = handler != null ? handler : NO_OP_EFFECT;
    }

    public static void handleEffect(MilestoneEffectPayload payload) {
        effectHandler.accept(payload);
    }
}
