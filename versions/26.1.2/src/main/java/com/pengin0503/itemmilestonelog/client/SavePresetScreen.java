package com.pengin0503.itemmilestonelog.client;

import com.pengin0503.itemmilestonelog.Presets;
import com.pengin0503.itemmilestonelog.network.MilestoneActionPayload;
import com.pengin0503.itemmilestonelog.network.SyncMilestonesPayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Simple name-input screen to save the player's current personal targets as a custom preset.
 */
public final class SavePresetScreen extends Screen {
    private final PresetListScreen parent;
    private EditBox nameBox;
    private Button saveButton;
    private String currentName = "";

    public SavePresetScreen(PresetListScreen parent) {
        super(Component.translatable("gui.itemmilestonelog.save_preset.title"));
        this.parent = parent;
    }

    public void updateParentData(SyncMilestonesPayload payload) {
        this.parent.updateData(payload);
    }

    @Override
    protected void init() {
        int boxWidth = Math.min(260, this.width - 40);
        int boxX = (this.width - boxWidth) / 2;
        int boxY = this.height / 2 - 12;

        nameBox = addRenderableWidget(new EditBox(
                this.font, boxX, boxY, boxWidth, 20,
                Component.translatable("gui.itemmilestonelog.save_preset.name_label")));
        nameBox.setMaxLength(32);
        nameBox.setHint(Component.translatable("gui.itemmilestonelog.save_preset.name_hint"));
        nameBox.setValue(currentName);
        nameBox.setResponder(val -> {
            this.currentName = val;
            if (saveButton != null) {
                saveButton.active = Presets.isValidId(val.trim());
            }
        });
        setInitialFocus(nameBox);

        int btnWidth = 100;
        int gap = 8;
        int startX = (this.width - (btnWidth * 2 + gap)) / 2;
        int btnY = boxY + 28;

        saveButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.itemmilestonelog.save_preset.save"),
                        btn -> submit())
                .bounds(startX, btnY, btnWidth, 20)
                .build());
        saveButton.active = Presets.isValidId(currentName.trim());

        addRenderableWidget(Button.builder(
                        CommonComponents.GUI_CANCEL,
                        btn -> onClose())
                .bounds(startX + btnWidth + gap, btnY, btnWidth, 20)
                .build());
    }

    private void submit() {
        String trimmed = currentName.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        ClientMilestoneHandler.sendAction(MilestoneActionPayload.Action.SAVE_PRESET, trimmed);
        onClose();
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 44, 0xFFFFFFFF);
        graphics.centeredText(this.font,
                Component.translatable("gui.itemmilestonelog.save_preset.name_label"),
                this.width / 2, this.height / 2 - 26, 0xFFD0D0D0);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
