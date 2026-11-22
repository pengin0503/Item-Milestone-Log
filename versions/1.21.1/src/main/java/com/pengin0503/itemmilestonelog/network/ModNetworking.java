package com.pengin0503.itemmilestonelog.network;

import com.pengin0503.itemmilestonelog.MilestoneService;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.function.Consumer;

/**
 * Registers optional network payloads for GUI synchronization and actions.
 *
 * The registrar is marked {@code optional()} so vanilla clients without this mod
 * can still connect to a dedicated server and use all commands normally.
 */
public final class ModNetworking {
    private static final String PROTOCOL_VERSION = "1";

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
            if (context.player() instanceof ServerPlayer player) {
                SyncMilestonesPayload sync = MilestoneService.createSyncPayload(player, true, null);
                PacketDistributor.sendToPlayer(player, sync);
            }
        });
    }

    private static void handleAction(MilestoneActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
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
