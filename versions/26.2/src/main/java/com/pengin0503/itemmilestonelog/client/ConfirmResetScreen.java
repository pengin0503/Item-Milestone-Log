package com.pengin0503.itemmilestonelog.client;

import com.pengin0503.itemmilestonelog.network.SyncMilestonesPayload;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;

/**
 * Non-pausing confirmation dialog for single-item and full record resets.
 */
public final class ConfirmResetScreen extends ConfirmScreen {
    private final MilestoneScreen parent;

    public ConfirmResetScreen(
            MilestoneScreen parent,
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
            if (parent.getMinecraft() != null) {
                parent.getMinecraft().gui.setScreen(parent);
            }
        }, title, message, confirmButton, cancelButton);
        this.parent = parent;
    }

    public void updateParentData(SyncMilestonesPayload payload) {
        this.parent.updateData(payload);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
