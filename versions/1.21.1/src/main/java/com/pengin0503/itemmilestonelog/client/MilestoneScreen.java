package com.pengin0503.itemmilestonelog.client;

import com.pengin0503.itemmilestonelog.Playtime;
import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.network.MilestoneActionPayload;
import com.pengin0503.itemmilestonelog.network.SyncMilestonesPayload;
import com.pengin0503.itemmilestonelog.network.TargetEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Main GUI screen for Item Milestone Log.
 *
 * Displays cumulative playtime, progress summary, scrollable targets/records list,
 * and action buttons covering every command feature.
 */
public final class MilestoneScreen extends Screen {
    private enum ViewMode {
        TARGETS,
        RECORDED
    }

    private SyncMilestonesPayload data;
    private ViewMode viewMode = ViewMode.TARGETS;
    @Nullable
    private Component feedbackMessage;
    private int elapsedTicks = 0;
    private int feedbackY = 0;

    private MilestoneListWidget listWidget;
    private Button targetsTabButton;
    private Button recordedTabButton;
    private Button resetAllButton;

    public MilestoneScreen(SyncMilestonesPayload initialData) {
        super(Component.translatable("gui.itemmilestonelog.title"));
        this.data = initialData;
        this.feedbackMessage = initialData.feedback();
    }

    public SyncMilestonesPayload getData() {
        return data;
    }

    public Set<String> getTrackedItemIds() {
        Set<String> ids = new HashSet<>();
        for (TargetEntry entry : data.targets()) {
            ids.add(entry.itemId());
        }
        return ids;
    }

    public void updateData(SyncMilestonesPayload newData) {
        double prevScroll = listWidget != null ? listWidget.getScrollAmount() : 0.0;
        this.data = newData;
        this.elapsedTicks = 0;
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

    @Override
    public void tick() {
        super.tick();
        if (this.minecraft != null && !this.minecraft.isPaused()) {
            this.elapsedTicks++;
        }
    }

    @Override
    protected void init() {
        int gap = 4;
        int maxRowWidth = Math.min(500, this.width - 16);

        List<ButtonSpec> addSpecs = List.of(
                new ButtonSpec(
                        Component.translatable("gui.itemmilestonelog.button.add"),
                        btn -> {
                            if (this.minecraft != null) {
                                this.minecraft.setScreen(new ItemPickerScreen(this));
                            }
                        },
                        Tooltip.create(Component.translatable("gui.itemmilestonelog.button.add"))),
                new ButtonSpec(
                        Component.translatable("gui.itemmilestonelog.button.add_hand"),
                        btn -> ClientMilestoneHandler.sendAction(MilestoneActionPayload.Action.ADD_HAND, ""),
                        Tooltip.create(Component.translatable("gui.itemmilestonelog.button.add_hand")))
        );

        List<ButtonSpec> presetSpecs = List.of(
                new ButtonSpec(
                        Component.translatable("gui.itemmilestonelog.button.presets"),
                        btn -> {
                            if (this.minecraft != null) {
                                this.minecraft.setScreen(new PresetListScreen(this));
                            }
                        },
                        Tooltip.create(Component.translatable("gui.itemmilestonelog.button.presets")))
        );

        List<ButtonSpec> combinedSpecs = new ArrayList<>(addSpecs.size() + presetSpecs.size());
        combinedSpecs.addAll(addSpecs);
        combinedSpecs.addAll(presetSpecs);

        // One row only when every label fits its button; otherwise long labels such as
        // the Japanese "add held item" would scroll inside the button.
        int widestLabel = 0;
        for (ButtonSpec spec : combinedSpecs) {
            widestLabel = Math.max(widestLabel, this.font.width(spec.label));
        }
        boolean splitActionRows = ActionRowLayout.needsSplit(maxRowWidth, combinedSpecs.size(), gap, widestLabel);

        int footerHeight = splitActionRows ? 88 : 66;
        this.feedbackY = this.height - footerHeight + 6;

        int listTop = 58;
        int listBottom = this.height - footerHeight;
        int listHeight = Math.max(40, listBottom - listTop);

        // View mode tabs (Targets / Recorded)
        int tabWidth = Math.min(130, (this.width - 24) / 2);
        int tabGap = 6;
        int tabsTotalWidth = tabWidth * 2 + tabGap;
        int tabsLeft = (this.width - tabsTotalWidth) / 2;

        targetsTabButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.itemmilestonelog.tab.targets", data.targets().size()),
                        btn -> switchView(ViewMode.TARGETS))
                .bounds(tabsLeft, 36, tabWidth, 18)
                .build());
        targetsTabButton.active = (viewMode != ViewMode.TARGETS);

        recordedTabButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.itemmilestonelog.tab.recorded", data.records().size()),
                        btn -> switchView(ViewMode.RECORDED))
                .bounds(tabsLeft + tabWidth + tabGap, 36, tabWidth, 18)
                .build());
        recordedTabButton.active = (viewMode != ViewMode.RECORDED);

        // Scrollable list
        listWidget = addRenderableWidget(new MilestoneListWidget(this.minecraft, this.width, listHeight, listTop, 28));
        populateList();

        // Bottom action buttons
        int bottomRowY = this.height - 24;
        if (splitActionRows) {
            addButtonRow(addSpecs, this.height - 68, maxRowWidth, gap);
            addButtonRow(presetSpecs, this.height - 46, maxRowWidth, gap);
        } else {
            addButtonRow(combinedSpecs, this.height - 46, maxRowWidth, gap);
        }

        // Bottom row: Reset All Records, Close
        int row2BtnWidth = Math.min(140, (maxRowWidth - 8) / 2);
        int row2TotalWidth = row2BtnWidth * 2 + 8;
        int row2StartX = (this.width - row2TotalWidth) / 2;

        resetAllButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.itemmilestonelog.button.reset_all"),
                        btn -> openConfirmResetAll())
                .bounds(row2StartX, bottomRowY, row2BtnWidth, 20)
                .build());
        resetAllButton.active = !data.records().isEmpty();

        addRenderableWidget(Button.builder(
                        CommonComponents.GUI_DONE,
                        btn -> onClose())
                .bounds(row2StartX + row2BtnWidth + 8, bottomRowY, row2BtnWidth, 20)
                .build());
    }

    private void addButtonRow(List<ButtonSpec> specs, int y, int maxRowWidth, int gap) {
        if (specs.isEmpty()) {
            return;
        }
        int count = specs.size();
        int btnWidth = Math.max(60, ActionRowLayout.buttonWidth(maxRowWidth, count, gap));
        int actualRowWidth = btnWidth * count + gap * (count - 1);
        int startX = (this.width - actualRowWidth) / 2;

        for (int i = 0; i < count; i++) {
            ButtonSpec spec = specs.get(i);
            Button.Builder builder = Button.builder(spec.label, spec.onPress)
                    .bounds(startX + i * (btnWidth + gap), y, btnWidth, 20);
            if (spec.tooltip != null) {
                builder.tooltip(spec.tooltip);
            }
            addRenderableWidget(builder.build());
        }
    }

    private void switchView(ViewMode mode) {
        if (this.viewMode != mode) {
            this.viewMode = mode;
            rebuildWidgets();
        }
    }

    private void populateList() {
        listWidget.clearAllEntries();
        if (viewMode == ViewMode.TARGETS) {
            for (TargetEntry target : data.targets()) {
                MilestoneRecord recorded = data.records().get(target.itemId());
                listWidget.addRow(new MilestoneRow(
                        target.itemId(),
                        target.personal(),
                        target.defaultTarget(),
                        recorded,
                        true
                ));
            }
        } else {
            List<Map.Entry<String, MilestoneRecord>> sorted = new ArrayList<>(data.records().entrySet());
            sorted.sort(Comparator.comparingInt((Map.Entry<String, MilestoneRecord> e) -> e.getValue().playTicks())
                    .thenComparing(Map.Entry::getKey));
            for (Map.Entry<String, MilestoneRecord> entry : sorted) {
                listWidget.addRow(new MilestoneRow(
                        entry.getKey(),
                        false,
                        false,
                        entry.getValue(),
                        false
                ));
            }
        }
    }

    private void openConfirmResetSingle(String itemId, Component itemDisplayName) {
        if (this.minecraft == null) {
            return;
        }
        this.minecraft.setScreen(new ConfirmResetScreen(
                this,
                Component.translatable("gui.itemmilestonelog.confirm_reset.title"),
                Component.translatable("gui.itemmilestonelog.confirm_reset.message", itemDisplayName),
                Component.translatable("gui.itemmilestonelog.button.reset"),
                CommonComponents.GUI_CANCEL,
                () -> ClientMilestoneHandler.sendAction(MilestoneActionPayload.Action.RESET_RECORD, itemId)
        ));
    }

    private void openConfirmResetAll() {
        if (this.minecraft == null) {
            return;
        }
        this.minecraft.setScreen(new ConfirmResetScreen(
                this,
                Component.translatable("gui.itemmilestonelog.confirm_resetall.title"),
                Component.translatable("gui.itemmilestonelog.confirm_resetall.message", data.records().size()),
                Component.translatable("gui.itemmilestonelog.button.reset_all"),
                CommonComponents.GUI_CANCEL,
                () -> ClientMilestoneHandler.sendAction(MilestoneActionPayload.Action.RESET_ALL, "")
        ));
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // Title
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);

        // Header summary: current playtime (live ticking) + achieved / total targets
        long achievedCount = data.targets().stream()
                .filter(t -> data.records().containsKey(t.itemId()))
                .count();
        Component header = Component.translatable(
                "gui.itemmilestonelog.header",
                Playtime.format(data.currentPlaytimeTicks() + elapsedTicks),
                achievedCount,
                data.targets().size()
        );
        guiGraphics.drawCenteredString(this.font, header, this.width / 2, 22, 0xD0D0D0);

        // Empty list hint
        if (listWidget != null && listWidget.children().isEmpty()) {
            Component emptyText = (viewMode == ViewMode.TARGETS)
                    ? Component.translatable("gui.itemmilestonelog.empty_targets")
                    : Component.translatable("gui.itemmilestonelog.empty_records");
            int centerY = listWidget.getY() + listWidget.getHeight() / 2 - 4;
            guiGraphics.drawCenteredString(this.font, emptyText, this.width / 2, centerY, 0xA0A0A0);
        }

        // Feedback banner from last action
        if (feedbackMessage != null) {
            int maxWidth = this.width - 20;
            String trimmed = this.font.plainSubstrByWidth(feedbackMessage.getString(), maxWidth);
            guiGraphics.drawCenteredString(this.font, trimmed, this.width / 2, this.feedbackY, 0xFFE88A);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record ButtonSpec(Component label, Button.OnPress onPress, @Nullable Tooltip tooltip) {
    }

    // --------------------------------------------------------- list widget

    private final class MilestoneListWidget extends ContainerObjectSelectionList<MilestoneRow> {
        public MilestoneListWidget(Minecraft mc, int width, int height, int y, int itemHeight) {
            super(mc, width, height, y, itemHeight);
        }

        public void clearAllEntries() {
            this.clearEntries();
        }

        public void addRow(MilestoneRow row) {
            this.addEntry(row);
        }

        @Override
        public int getRowWidth() {
            return Math.min(460, MilestoneScreen.this.width - 32);
        }

        @Override
        protected int getScrollbarPosition() {
            return this.getX() + this.width / 2 + getRowWidth() / 2 + 6;
        }
    }

    private final class MilestoneRow extends ContainerObjectSelectionList.Entry<MilestoneRow> {
        private final String itemId;
        private final ItemStack iconStack;
        private final Component displayName;
        private final boolean defaultTarget;
        @Nullable
        private final MilestoneRecord recordedRecord;
        private final List<Button> rowButtons = new ArrayList<>();
        @Nullable
        private final Button resetButton;
        @Nullable
        private final Button removeOrDefaultButton;

        public MilestoneRow(String itemId, boolean personal, boolean defaultTarget,
                            @Nullable MilestoneRecord recordedRecord, boolean showTargetControls) {
            this.itemId = itemId;
            this.defaultTarget = defaultTarget;
            this.recordedRecord = recordedRecord;

            ResourceLocation rl = ResourceLocation.tryParse(itemId);
            if (rl != null && BuiltInRegistries.ITEM.containsKey(rl)) {
                Item item = BuiltInRegistries.ITEM.get(rl);
                this.iconStack = new ItemStack(item);
                this.displayName = item.getDescription();
            } else {
                this.iconStack = new ItemStack(Items.BARRIER);
                this.displayName = Component.literal(itemId);
            }

            if (recordedRecord != null) {
                this.resetButton = Button.builder(
                                Component.translatable("gui.itemmilestonelog.button.reset"),
                                btn -> openConfirmResetSingle(this.itemId, this.displayName))
                        .bounds(0, 0, 50, 20)
                        .tooltip(Tooltip.create(Component.translatable("gui.itemmilestonelog.tooltip.reset")))
                        .build();
                this.rowButtons.add(this.resetButton);
            } else {
                this.resetButton = null;
            }

            if (showTargetControls) {
                if (personal) {
                    this.removeOrDefaultButton = Button.builder(
                                    Component.translatable("gui.itemmilestonelog.button.remove"),
                                    btn -> ClientMilestoneHandler.sendAction(MilestoneActionPayload.Action.REMOVE_TARGET, this.itemId))
                            .bounds(0, 0, 54, 20)
                            .tooltip(Tooltip.create(Component.translatable("gui.itemmilestonelog.tooltip.remove")))
                            .build();
                } else {
                    this.removeOrDefaultButton = Button.builder(
                                    Component.translatable("gui.itemmilestonelog.badge.default"),
                                    btn -> {
                                    })
                            .bounds(0, 0, 54, 20)
                            .tooltip(Tooltip.create(Component.translatable("gui.itemmilestonelog.tooltip.default_locked")))
                            .build();
                    this.removeOrDefaultButton.active = false;
                }
                this.rowButtons.add(this.removeOrDefaultButton);
            } else {
                this.removeOrDefaultButton = null;
            }
        }

        @Override
        public void render(GuiGraphics guiGraphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovering, float partialTick) {
            if (hovering) {
                guiGraphics.fill(left, top, left + width, top + height, 0x22FFFFFF);
            }

            guiGraphics.renderItem(iconStack, left + 4, top + 6);

            int rightEdge = left + width - 4;
            if (removeOrDefaultButton != null) {
                rightEdge -= removeOrDefaultButton.getWidth();
                removeOrDefaultButton.setPosition(rightEdge, top + 4);
                removeOrDefaultButton.render(guiGraphics, mouseX, mouseY, partialTick);
                rightEdge -= 4;
            }
            if (resetButton != null) {
                rightEdge -= resetButton.getWidth();
                resetButton.setPosition(rightEdge, top + 4);
                resetButton.render(guiGraphics, mouseX, mouseY, partialTick);
                rightEdge -= 4;
            }

            int textX = left + 26;
            int maxTextWidth = Math.max(40, rightEdge - textX - 4);

            String nameStr = displayName.getString();
            if (defaultTarget && removeOrDefaultButton != null && removeOrDefaultButton.active) {
                nameStr = nameStr + " [" + Component.translatable("gui.itemmilestonelog.badge.default").getString() + "]";
            }
            String trimmedName = MilestoneScreen.this.font.plainSubstrByWidth(nameStr, maxTextWidth);
            guiGraphics.drawString(MilestoneScreen.this.font, trimmedName, textX, top + 4, 0xFFFFFF, false);

            Component statusComponent;
            int statusColor;
            if (recordedRecord != null) {
                statusComponent = Component.translatable("gui.itemmilestonelog.status.recorded",
                        Playtime.formatRecord(recordedRecord));
                statusColor = 0x55FF55;
            } else {
                statusComponent = Component.translatable("gui.itemmilestonelog.status.pending");
                statusColor = 0xA0A0A0;
            }
            String trimmedStatus = MilestoneScreen.this.font.plainSubstrByWidth(statusComponent.getString(), maxTextWidth);
            guiGraphics.drawString(MilestoneScreen.this.font, trimmedStatus, textX, top + 15, statusColor, false);
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
