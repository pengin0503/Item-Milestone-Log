package com.pengin0503.itemmilestonelog.client;

import com.pengin0503.itemmilestonelog.network.MilestoneActionPayload;
import com.pengin0503.itemmilestonelog.network.RequestSyncPayload;
import com.pengin0503.itemmilestonelog.network.SyncMilestonesPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Handles client-side GUI networking: requesting snapshots, dispatching GUI actions,
 * and opening or live-updating the active milestone screens.
 */
public final class ClientMilestoneHandler {
    private ClientMilestoneHandler() {
    }

    public static void requestOpenGui() {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            return;
        }
        if (!connection.hasChannel(RequestSyncPayload.TYPE)) {
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.translatable("message.itemmilestonelog.server_not_installed"), true);
            }
            return;
        }
        PacketDistributor.sendToServer(new RequestSyncPayload());
    }

    public static void sendAction(MilestoneActionPayload.Action action, String argument) {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null || !connection.hasChannel(MilestoneActionPayload.TYPE)) {
            return;
        }
        PacketDistributor.sendToServer(new MilestoneActionPayload(action, argument == null ? "" : argument));
    }

    public static void onSyncReceived(SyncMilestonesPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        Screen current = mc.screen;

        if (current instanceof MilestoneScreen screen) {
            screen.updateData(payload);
            return;
        }
        if (current instanceof ItemPickerScreen picker) {
            picker.updateData(payload);
            return;
        }
        if (current instanceof ConfirmResetScreen confirm) {
            confirm.updateParentData(payload);
            return;
        }
        // Only open on an empty screen: the player may have opened another screen
        // (inventory, a map, ...) while waiting for the server's answer.
        if (payload.openScreen() && current == null) {
            mc.setScreen(new MilestoneScreen(payload));
        }
    }
}
