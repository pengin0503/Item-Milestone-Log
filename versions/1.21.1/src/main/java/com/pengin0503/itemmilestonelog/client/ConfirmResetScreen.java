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
    private final Screen parentScreen;
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
            Minecraft.getInstance().setScreen(parent);
        }, title, message, confirmButton, cancelButton);
        this.parentScreen = parent;
        this.onSyncUpdate = onSyncUpdate;
    }

    public void updateParentData(SyncMilestonesPayload payload) {
        this.onSyncUpdate.accept(payload);
    }

    @Override
    public void tick() {
        super.tick();
        if (parentScreen instanceof MilestoneScreen ms) {
            ms.tickPlaytime();
        } else if (parentScreen instanceof PresetListScreen pls) {
            pls.getParent().tickPlaytime();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
