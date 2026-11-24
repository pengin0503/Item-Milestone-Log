package com.pengin0503.itemmilestonelog.client;

import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.network.MilestoneActionPayload;
import com.pengin0503.itemmilestonelog.network.RequestSyncPayload;
import com.pengin0503.itemmilestonelog.network.SyncExtraPayload;
import com.pengin0503.itemmilestonelog.network.SyncMilestonesPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import javax.annotation.Nullable;

/**
 * Handles client-side GUI networking: requesting snapshots, dispatching GUI actions,
 * and opening or live-updating the active milestone screens.
 */
public final class ClientMilestoneHandler {
    private static volatile SyncExtraPayload extraData = SyncExtraPayload.EMPTY;

    private ClientMilestoneHandler() {
    }

    public static void onSyncExtraReceived(@Nullable SyncExtraPayload payload) {
        extraData = payload != null ? payload : SyncExtraPayload.EMPTY;
    }

    public static void clearExtraData() {
        extraData = SyncExtraPayload.EMPTY;
    }

    public static SyncExtraPayload getExtraData() {
        return extraData;
    }

    public static boolean isWorldFirst(@Nullable TargetKey key) {
        return key != null && extraData.worldFirsts().contains(key);
    }

    public static boolean isReadOnlyPreset(@Nullable String presetId) {
        return presetId != null && extraData.readOnlyPresets().contains(presetId);
    }

    public static void requestOpenGui() {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            return;
        }
        if (!connection.hasChannel(RequestSyncPayload.TYPE)) {
            if (mc.player != null) {
                mc.player.sendOverlayMessage(
                        Component.translatable("message.itemmilestonelog.server_not_installed"));
            }
            return;
        }
        ClientPacketDistributor.sendToServer(new RequestSyncPayload());
    }

    public static void sendAction(MilestoneActionPayload.Action action, String argument) {
        sendAction(action, argument, 1);
    }

    public static void sendAction(MilestoneActionPayload.Action action, String argument, int count) {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null || !connection.hasChannel(MilestoneActionPayload.TYPE)) {
            return;
        }
        ClientPacketDistributor.sendToServer(new MilestoneActionPayload(action, argument == null ? "" : argument, count));
    }

    public static void onSyncReceived(SyncMilestonesPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        Screen current = mc.screen;

        if (current instanceof MilestoneScreen screen) {
            screen.updateData(payload);
            return;
        }
        if (current instanceof PresetListScreen presetList) {
            presetList.updateData(payload);
            return;
        }
        if (current instanceof SavePresetScreen savePreset) {
            savePreset.updateParentData(payload);
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
        if (payload.openScreen() && current == null) {
            mc.setScreen(new MilestoneScreen(payload));
        }
    }
}
