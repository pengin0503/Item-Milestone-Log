package com.pengin0503.itemmilestonelog.client;

import com.pengin0503.itemmilestonelog.network.SyncMilestonesPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Non-pausing confirmation dialog for record resets and custom preset deletion.
 */
public final class ConfirmResetScreen extends ConfirmScreen {
    private final Consumer<SyncMilestonesPayload> onSyncUpdate;

    public ConfirmResetScreen(
            MilestoneScreen parent,
            Component title,
            Component message,
            Component confirmButton,
            Component cancelButton,
            Runnable onConfirm
    ) {
        this(parent, parent::updateData, title, message, confirmButton, cancelButton, onConfirm);
    }

    public ConfirmResetScreen(
            Screen parent,
            Consumer<SyncMilestonesPayload> onSyncUpdate,
            Component title,
            Component message,
            Component confirmButton,
            Component cancelButton,
            Runnable onConfirm
    ) {
        super(confirmed -> {
            if (confirmed) {
                onConfirm.run();
            }
            Minecraft.getInstance().gui.setScreen(parent);
        }, title, message, confirmButton, cancelButton);
        this.onSyncUpdate = onSyncUpdate;
    }

    public void updateParentData(SyncMilestonesPayload payload) {
        this.onSyncUpdate.accept(payload);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
