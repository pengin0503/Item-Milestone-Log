package com.pengin0503.itemmilestonelog.client;

import com.pengin0503.itemmilestonelog.network.MilestoneActionPayload;
import com.pengin0503.itemmilestonelog.network.PresetEntry;
import com.pengin0503.itemmilestonelog.network.SyncMilestonesPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
        double prevScroll = listWidget != null ? listWidget.scrollAmount() : 0.0;
        this.parent.updateData(newData, awaitingApplyFeedback);
        this.awaitingApplyFeedback = false;
        this.data = newData;
        if (newData.feedback() != null) {
            this.feedbackMessage = newData.feedback();
        }
        if (this.minecraft != null && this.minecraft.gui.screen() == this) {
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
                                    this.minecraft.gui.setScreen(new SavePresetScreen(this));
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
        this.minecraft.gui.setScreen(new ConfirmResetScreen(
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
            this.minecraft.gui.setScreen(parent);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        graphics.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);

        if (listWidget != null && listWidget.children().isEmpty()) {
            int centerY = listWidget.getY() + listWidget.getHeight() / 2 - 4;
            graphics.centeredText(this.font,
                    Component.translatable("gui.itemmilestonelog.presets.empty"),
                    this.width / 2, centerY, 0xFFA0A0A0);
        }

        if (feedbackMessage != null) {
            int maxWidth = this.width - 20;
            String trimmed = this.font.plainSubstrByWidth(feedbackMessage.getString(), maxWidth);
            graphics.centeredText(this.font, trimmed, this.width / 2, this.feedbackY, 0xFFFFE88A);
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

        @Override
        protected int scrollBarX() {
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
            Component countText = Component.translatable("gui.itemmilestonelog.presets.count", entry.itemCount());
            Component typeText = Component.translatable(entry.builtin()
                    ? "gui.itemmilestonelog.presets.type.builtin"
                    : "gui.itemmilestonelog.presets.type.file");
            this.metaLine = Component.translatable("gui.itemmilestonelog.presets.row_meta", countText, typeText);

            this.applyButton = Button.builder(
                            Component.translatable("gui.itemmilestonelog.presets.apply"),
                            btn -> {
                                PresetListScreen.this.awaitingApplyFeedback = true;
                                ClientMilestoneHandler.sendAction(MilestoneActionPayload.Action.APPLY_PRESET, entry.id());
                                if (PresetListScreen.this.minecraft != null) {
                                    PresetListScreen.this.minecraft.gui.setScreen(parent);
                                }
                            })
                    .bounds(0, 0, 50, 20)
                    .build();
            this.rowButtons.add(this.applyButton);

            if (!entry.builtin() && data.canManagePresets()) {
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
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovering, float partialTick) {
            int left = getContentX();
            int top = getContentY();
            int width = getContentWidth();

            if (hovering) {
                graphics.fill(left, top, left + width, top + getContentHeight(), 0x22FFFFFF);
            }

            int rightEdge = left + width - 4;
            if (deleteButton != null) {
                rightEdge -= deleteButton.getWidth();
                deleteButton.setPosition(rightEdge, top + 2);
                deleteButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
                rightEdge -= 4;
            }
            rightEdge -= applyButton.getWidth();
            applyButton.setPosition(rightEdge, top + 2);
            applyButton.extractRenderState(graphics, mouseX, mouseY, partialTick);
            rightEdge -= 4;

            int textX = left + 6;
            int maxTextWidth = Math.max(40, rightEdge - textX - 4);

            String trimmedName = PresetListScreen.this.font.plainSubstrByWidth(displayName.getString(), maxTextWidth);
            graphics.text(PresetListScreen.this.font, trimmedName, textX, top + 2, 0xFFFFFFFF, false);

            String trimmedMeta = PresetListScreen.this.font.plainSubstrByWidth(metaLine.getString(), maxTextWidth);
            graphics.text(PresetListScreen.this.font, trimmedMeta, textX, top + 13, 0xFFA0A0A0, false);
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
