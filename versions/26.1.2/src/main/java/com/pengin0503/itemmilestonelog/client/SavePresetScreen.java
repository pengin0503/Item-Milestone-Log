package com.pengin0503.itemmilestonelog.client;

import com.pengin0503.itemmilestonelog.Presets;
import com.pengin0503.itemmilestonelog.network.MilestoneActionPayload;
import com.pengin0503.itemmilestonelog.network.PresetEntry;
import com.pengin0503.itemmilestonelog.network.SyncMilestonesPayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;

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
        updateSaveButtonState();
    }

    @Override
    public void tick() {
        super.tick();
        this.parent.getParent().tickPlaytime();
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
            updateSaveButtonState();
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
        updateSaveButtonState();

        addRenderableWidget(Button.builder(
                        CommonComponents.GUI_CANCEL,
                        btn -> onClose())
                .bounds(startX + btnWidth + gap, btnY, btnWidth, 20)
                .build());
    }

    @Nullable
    private PresetEntry findMatchingPreset(String trimmedId) {
        if (trimmedId.isEmpty()) {
            return null;
        }
        for (PresetEntry entry : this.parent.getParent().getData().presets()) {
            if (entry.id().equals(trimmedId)) {
                return entry;
            }
        }
        return null;
    }

    private static boolean isBuiltinMatch(String trimmedId, @Nullable PresetEntry matched) {
        return Presets.isBuiltin(trimmedId) || (matched != null && matched.builtin());
    }

    private void updateSaveButtonState() {
        if (saveButton == null) {
            return;
        }
        String trimmed = currentName.trim();
        PresetEntry matched = findMatchingPreset(trimmed);
        boolean valid = Presets.isValidId(trimmed) && !isBuiltinMatch(trimmed, matched);
        saveButton.active = valid;
        boolean overwrite = valid && matched != null && !matched.builtin();
        saveButton.setMessage(Component.translatable(
                overwrite ? "gui.itemmilestonelog.save_preset.overwrite" : "gui.itemmilestonelog.save_preset.save"));
    }

    private void submit() {
        String trimmed = currentName.trim();
        PresetEntry matched = findMatchingPreset(trimmed);
        if (!Presets.isValidId(trimmed) || isBuiltinMatch(trimmed, matched)) {
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

        String trimmed = currentName.trim();
        PresetEntry matched = findMatchingPreset(trimmed);
        Component label;
        if (Presets.isValidId(trimmed) && matched != null && !matched.builtin()) {
            String rawName = matched.displayName();
            String existingDisplayName = (rawName == null || rawName.isEmpty()) ? matched.id() : rawName;
            label = Component.translatable(
                    "gui.itemmilestonelog.save_preset.overwrite_notice",
                    Component.literal(existingDisplayName));
        } else {
            label = Component.translatable("gui.itemmilestonelog.save_preset.name_label");
        }
        int maxWidth = Math.max(40, this.width - 20);
        if (this.font.width(label) > maxWidth) {
            String trimmedLabel = this.font.plainSubstrByWidth(label.getString(), maxWidth);
            graphics.centeredText(this.font, trimmedLabel, this.width / 2, this.height / 2 - 26, 0xFFD0D0D0);
        } else {
            graphics.centeredText(this.font, label, this.width / 2, this.height / 2 - 26, 0xFFD0D0D0);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
