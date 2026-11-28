package com.pengin0503.itemmilestonelog.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.pengin0503.itemmilestonelog.ClientDiagRender;
import com.pengin0503.itemmilestonelog.ClientProfileStore;
import com.pengin0503.itemmilestonelog.LocalSyncCache;
import com.pengin0503.itemmilestonelog.PinStore;
import com.pengin0503.itemmilestonelog.SearchHistoryStore;
import com.pengin0503.itemmilestonelog.compat.McVersion;
import com.pengin0503.itemmilestonelog.network.MilestoneActionPayload;
import com.pengin0503.itemmilestonelog.network.MilestoneEffectPayload;
import com.pengin0503.itemmilestonelog.network.RequestSyncPayload;
import com.pengin0503.itemmilestonelog.network.SyncExtraPayload;
import com.pengin0503.itemmilestonelog.network.SyncMilestonesPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * F-150-87 (N200): client-only {@code /imlclient diag}. Gathering happens here, in the version
 * tree; rendering happens in the pure {@link ClientDiagRender}. Nothing is sent to the server,
 * no log file is written and the server-side {@code CommandHelp} table is untouched.
 */
public final class ClientDiagCommands {
    private ClientDiagCommands() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return LiteralArgumentBuilder.<CommandSourceStack>literal("diag")
                .executes(ClientDiagCommands::diag);
    }

    private static int diag(CommandContext<CommandSourceStack> context) {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        Path configDir = FMLPaths.CONFIGDIR.get();
        LocalSyncCache.LoadResult cached = LocalSyncCache.read(configDir, MilestoneHudOverlay.serverKey());
        LocalSyncCache.Snapshot snapshot = cached.snapshot();
        long fileBytes = 0L;
        try {
            fileBytes = Files.size(LocalSyncCache.fileFor(configDir));
        } catch (IOException | RuntimeException ignored) {
            // A missing/unreadable cache file is simply reported as zero bytes.
        }
        SyncMilestonesPayload live = ClientMilestoneHandler.currentSync();
        ClientDiagRender.Inputs inputs = new ClientDiagRender.Inputs(
                "forge", McVersion.current().name(),
                channel(connection, RequestSyncPayload.TYPE),
                channel(connection, MilestoneActionPayload.TYPE),
                channel(connection, SyncMilestonesPayload.TYPE),
                channel(connection, SyncExtraPayload.TYPE),
                channel(connection, MilestoneEffectPayload.TYPE),
                new boolean[]{
                        ItemMilestoneLogClient.OPEN_GUI_MAPPING.getKey() != InputConstants.UNKNOWN,
                        ItemMilestoneLogClient.ADD_HOVERED_MAPPING.getKey() != InputConstants.UNKNOWN,
                        ItemMilestoneLogClient.MUTE_NOTIFICATION_SOUND_MAPPING.getKey() != InputConstants.UNKNOWN,
                        ItemMilestoneLogClient.COPY_RECENT_MAPPING.getKey() != InputConstants.UNKNOWN
                },
                cached.present(), snapshot.savedAtMillis(),
                snapshot.targets().size(), snapshot.records().size(), snapshot.presets().size(),
                fileBytes,
                ClientProfileStore.readAll(configDir).profiles().size(),
                PinStore.read(configDir).size(),
                SearchHistoryStore.read(configDir).size(),
                live == null ? 0 : live.targets().size(),
                live == null ? 0 : live.records().size(),
                live == null ? 0 : live.presets().size());
        MutableComponent out = Component.literal("");
        for (ClientDiagRender.Line line : ClientDiagRender.render(inputs)) {
            out.append("\n").append(Component.translatable(line.key(), (Object[]) line.args()));
        }
        context.getSource().sendSuccess(() -> out, false);
        return Command.SINGLE_SUCCESS;
    }

        /** 1.20.1 has a single optional Forge channel carrying all payload ids. */
    private static boolean channel(ClientPacketListener connection, Object unused) {
        return com.pengin0503.itemmilestonelog.compat.McCompat.network().isRemotePresent(connection);
    }

}
