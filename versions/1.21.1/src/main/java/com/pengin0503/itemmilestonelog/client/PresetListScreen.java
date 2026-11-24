package com.pengin0503.itemmilestonelog.client;

import com.pengin0503.itemmilestonelog.compat.McCompat;
import com.pengin0503.itemmilestonelog.network.MilestoneActionPayload;
import com.pengin0503.itemmilestonelog.network.PresetEntry;
import com.pengin0503.itemmilestonelog.network.SyncMilestonesPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Screen listing all built-in and custom file presets, with Apply, Delete, and Save Current Targets actions.
 */
public final class PresetListScreen extends Screen {
    private final MilestoneScreen parent;
    private SyncMilestonesPayload data;
    @Nullable
    private Component feedbackMessage;
    private int feedbackY = 0;

    private PresetListWidget listWidget;

    private boolean awaitingApplyFeedback = false;

    public PresetListScreen(MilestoneScreen parent) {
        super(Component.translatable("gui.itemmilestonelog.presets.title"));
        this.parent = parent;
        this.data = parent.getData();
    }

    public void updateData(SyncMilestonesPayload newData) {
        double prevScroll = listWidget != null ? McCompat.scroll().getScrollAmount(listWidget) : 0.0;
        this.parent.updateData(newData, awaitingApplyFeedback);
        this.awaitingApplyFeedback = false;
        this.data = newData;
        if (newData.feedback() != null) {
            this.feedbackMessage = newData.feedback();
        }
        if (this.minecraft != null && this.minecraft.screen == this) {
            rebuildWidgets();
            if (listWidget != null) {
                listWidget.setScrollAmount(prevScroll);
            }
        }
    }

    public MilestoneScreen getParent() {
        return parent;
    }

    @Override
    public void tick() {
        super.tick();
        this.parent.tickPlaytime();
    }

    @Override
    protected void init() {
        boolean canManage = data.canManagePresets();
        int footerHeight = canManage ? 68 : 46;
        this.feedbackY = this.height - footerHeight + 4;

        int listTop = 26;
        int listBottom = this.height - footerHeight;
        int listHeight = Math.max(40, listBottom - listTop);

        listWidget = addRenderableWidget(new PresetListWidget(this.minecraft, this.width, listHeight, listTop, 28));
        for (PresetEntry entry : data.presets()) {
            listWidget.addRow(new PresetRow(entry));
        }

        if (canManage) {
            int saveWidth = Math.min(260, this.width - 32);
            addRenderableWidget(Button.builder(
                            Component.translatable("gui.itemmilestonelog.presets.save_current"),
                            btn -> {
                                if (this.minecraft != null) {
                                    this.minecraft.setScreen(new SavePresetScreen(this));
                                }
                            })
                    .bounds((this.width - saveWidth) / 2, this.height - 48, saveWidth, 20)
                    .build());
        }

        int backWidth = 120;
        addRenderableWidget(Button.builder(
                        CommonComponents.GUI_BACK,
                        btn -> onClose())
                .bounds((this.width - backWidth) / 2, this.height - 24, backWidth, 20)
                .build());
    }

    private void openConfirmDelete(PresetEntry entry, Component displayName) {
        if (this.minecraft == null) {
            return;
        }
        this.minecraft.setScreen(new ConfirmResetScreen(
                this,
                this::updateData,
                Component.translatable("gui.itemmilestonelog.confirm_delete_preset.title"),
                Component.translatable("gui.itemmilestonelog.confirm_delete_preset.message", displayName),
                Component.translatable("gui.itemmilestonelog.presets.delete"),
                CommonComponents.GUI_CANCEL,
                () -> ClientMilestoneHandler.sendAction(MilestoneActionPayload.Action.DELETE_PRESET, entry.id())
        ));
    }

    static Component resolveDisplayName(PresetEntry entry) {
        if (entry.builtin()) {
            String key = "gui.itemmilestonelog.preset." + entry.id();
            if (Language.getInstance().has(key)) {
                return Component.translatable(key);
            }
        }
        String raw = entry.displayName();
        return Component.literal((raw == null || raw.isEmpty()) ? entry.id() : raw);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);

        if (listWidget != null && listWidget.children().isEmpty()) {
            int centerY = listWidget.getY() + listWidget.getHeight() / 2 - 4;
            guiGraphics.drawCenteredString(this.font,
                    Component.translatable("gui.itemmilestonelog.presets.empty"),
                    this.width / 2, centerY, 0xA0A0A0);
        }

        if (feedbackMessage != null) {
            int maxWidth = this.width - 20;
            String trimmed = this.font.plainSubstrByWidth(feedbackMessage.getString(), maxWidth);
            guiGraphics.drawCenteredString(this.font, trimmed, this.width / 2, this.feedbackY, 0xFFFFE88A);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private final class PresetListWidget extends ContainerObjectSelectionList<PresetRow> {
        public PresetListWidget(Minecraft mc, int width, int height, int y, int itemHeight) {
            super(mc, width, height, y, itemHeight);
        }

        public void addRow(PresetRow row) {
            this.addEntry(row);
        }

        @Override
        public int getRowWidth() {
            return Math.min(460, PresetListScreen.this.width - 32);
        }

        // No @Override: the supertype method was removed in MC 1.21.4; this still overrides it on 1.21-1.21.3.
        protected int getScrollbarPosition() {
            return this.getX() + this.width / 2 + getRowWidth() / 2 + 6;
        }
    }

    private final class PresetRow extends ContainerObjectSelectionList.Entry<PresetRow> {
        private final Component displayName;
        private final Component metaLine;
        private final Button applyButton;
        @Nullable
        private final Button deleteButton;
        private final List<Button> rowButtons = new ArrayList<>();

        public PresetRow(PresetEntry entry) {
            this.displayName = resolveDisplayName(entry);
            boolean readOnly = !entry.builtin() && ClientMilestoneHandler.isReadOnlyPreset(entry.id());
            Component countText = Component.translatable("gui.itemmilestonelog.presets.count", entry.itemCount());
            String typeKey;
            if (entry.builtin()) {
                typeKey = "gui.itemmilestonelog.presets.type.builtin";
            } else if (readOnly) {
                typeKey = "gui.itemmilestonelog.presets.type.datapack";
            } else {
                typeKey = "gui.itemmilestonelog.presets.type.file";
            }
            Component typeText = Component.translatable(typeKey);
            this.metaLine = Component.translatable("gui.itemmilestonelog.presets.row_meta", countText, typeText);

            this.applyButton = Button.builder(
                            Component.translatable("gui.itemmilestonelog.presets.apply"),
                            btn -> {
                                PresetListScreen.this.awaitingApplyFeedback = true;
                                ClientMilestoneHandler.sendAction(MilestoneActionPayload.Action.APPLY_PRESET, entry.id());
                                if (PresetListScreen.this.minecraft != null) {
                                    PresetListScreen.this.minecraft.setScreen(parent);
                                }
                            })
                    .bounds(0, 0, 50, 20)
                    .build();
            this.rowButtons.add(this.applyButton);

            if (!entry.builtin() && !readOnly && data.canManagePresets()) {
                this.deleteButton = Button.builder(
                                Component.translatable("gui.itemmilestonelog.presets.delete"),
                                btn -> openConfirmDelete(entry, this.displayName))
                        .bounds(0, 0, 54, 20)
                        .build();
                this.rowButtons.add(this.deleteButton);
            } else {
                this.deleteButton = null;
            }
        }

        @Override
        public void render(GuiGraphics guiGraphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovering, float partialTick) {
            if (hovering) {
                guiGraphics.fill(left, top, left + width, top + height, 0x22FFFFFF);
            }

            int rightEdge = left + width - 4;
            if (deleteButton != null) {
                rightEdge -= deleteButton.getWidth();
                deleteButton.setPosition(rightEdge, top + 4);
                deleteButton.render(guiGraphics, mouseX, mouseY, partialTick);
                rightEdge -= 4;
            }
            rightEdge -= applyButton.getWidth();
            applyButton.setPosition(rightEdge, top + 4);
            applyButton.render(guiGraphics, mouseX, mouseY, partialTick);
            rightEdge -= 4;

            int textX = left + 6;
            int maxTextWidth = Math.max(40, rightEdge - textX - 4);

            String trimmedName = PresetListScreen.this.font.plainSubstrByWidth(displayName.getString(), maxTextWidth);
            guiGraphics.drawString(PresetListScreen.this.font, trimmedName, textX, top + 4, 0xFFFFFF, false);

            String trimmedMeta = PresetListScreen.this.font.plainSubstrByWidth(metaLine.getString(), maxTextWidth);
            guiGraphics.drawString(PresetListScreen.this.font, trimmedMeta, textX, top + 15, 0xA0A0A0, false);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return rowButtons;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return rowButtons;
        }
    }
}
