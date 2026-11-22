package com.pengin0503.itemmilestonelog.client;

import com.pengin0503.itemmilestonelog.network.MilestoneActionPayload;
import com.pengin0503.itemmilestonelog.network.SyncMilestonesPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Searchable item selector screen for adding new personal target items.
 *
 * Pre-caches all registered items with lowercase name and registry ID so
 * real-time search stays responsive even in large modpacks such as ATM10.
 */
public final class ItemPickerScreen extends Screen {
    private static final int ACTION_BTN_WIDTH = 64;
    private static final int ACTION_BTN_HEIGHT = 18;

    private final MilestoneScreen parent;
    private final List<CatalogItem> allItems;
    private Set<String> trackedIds;

    private EditBox searchBox;
    private ItemListWidget listWidget;
    private String currentQuery = "";
    private int matchedCount = 0;

    public ItemPickerScreen(MilestoneScreen parent) {
        super(Component.translatable("gui.itemmilestonelog.picker.title"));
        this.parent = parent;
        this.trackedIds = parent.getTrackedItemIds();
        this.allItems = buildCatalog();
    }

    private static List<CatalogItem> buildCatalog() {
        List<CatalogItem> catalog = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) {
                continue;
            }
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(item);
            String id = rl.toString();
            Component name = item.getDescription();
            String nameStr = name.getString();
            catalog.add(new CatalogItem(
                    id,
                    new ItemStack(item),
                    name,
                    nameStr.toLowerCase(Locale.ROOT),
                    id.toLowerCase(Locale.ROOT)
            ));
        }
        catalog.sort(Comparator.comparing(c -> c.lowerName));
        return catalog;
    }

    public void updateData(SyncMilestonesPayload payload) {
        this.parent.updateData(payload);
        this.trackedIds = this.parent.getTrackedItemIds();
    }

    @Override
    protected void init() {
        int searchWidth = Math.min(340, this.width - 40);
        int searchX = (this.width - searchWidth) / 2;

        searchBox = addRenderableWidget(new EditBox(
                this.font, searchX, 24, searchWidth, 20,
                Component.translatable("gui.itemmilestonelog.picker.search")));
        searchBox.setHint(Component.translatable("gui.itemmilestonelog.picker.search_hint"));
        searchBox.setValue(currentQuery);
        searchBox.setResponder(this::onSearchChanged);
        setInitialFocus(searchBox);

        int listTop = 50;
        int listBottom = this.height - 34;
        int listHeight = Math.max(40, listBottom - listTop);

        listWidget = addRenderableWidget(new ItemListWidget(this.minecraft, this.width, listHeight, listTop, 26));
        refreshList();

        int backWidth = 120;
        addRenderableWidget(Button.builder(
                        CommonComponents.GUI_BACK,
                        btn -> onClose())
                .bounds((this.width - backWidth) / 2, this.height - 26, backWidth, 20)
                .build());
    }

    private void onSearchChanged(String query) {
        this.currentQuery = query;
        refreshList();
    }

    private void refreshList() {
        if (listWidget == null) {
            return;
        }
        listWidget.clearAllEntries();
        String trimmed = currentQuery.trim().toLowerCase(Locale.ROOT);
        int count = 0;
        for (CatalogItem item : allItems) {
            if (trimmed.isEmpty() || item.lowerName.contains(trimmed) || item.lowerId.contains(trimmed)) {
                listWidget.addRow(new ItemRow(item));
                count++;
            }
        }
        this.matchedCount = count;
        listWidget.setScrollAmount(0.0);
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

        Component countText = Component.translatable("gui.itemmilestonelog.picker.count", matchedCount, allItems.size());
        guiGraphics.drawString(this.font, countText, 12, this.height - 20, 0xB0B0B0, false);

        if (matchedCount == 0 && listWidget != null) {
            int centerY = listWidget.getY() + listWidget.getHeight() / 2 - 4;
            guiGraphics.drawCenteredString(this.font,
                    Component.translatable("gui.itemmilestonelog.picker.no_results"),
                    this.width / 2, centerY, 0xA0A0A0);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record CatalogItem(
            String id,
            ItemStack stack,
            Component displayName,
            String lowerName,
            String lowerId
    ) {
    }

    // --------------------------------------------------------- list widget

    private final class ItemListWidget extends ObjectSelectionList<ItemRow> {
        public ItemListWidget(Minecraft mc, int width, int height, int y, int itemHeight) {
            super(mc, width, height, y, itemHeight);
        }

        public void clearAllEntries() {
            this.clearEntries();
        }

        public void addRow(ItemRow row) {
            this.addEntry(row);
        }

        @Override
        public int getRowWidth() {
            return Math.min(440, ItemPickerScreen.this.width - 32);
        }

        @Override
        protected int getScrollbarPosition() {
            return this.getX() + this.width / 2 + getRowWidth() / 2 + 6;
        }
    }

    private final class ItemRow extends ObjectSelectionList.Entry<ItemRow> {
        private final CatalogItem item;

        public ItemRow(CatalogItem item) {
            this.item = item;
        }

        @Override
        public void render(GuiGraphics guiGraphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovering, float partialTick) {
            boolean alreadyTracked = trackedIds.contains(item.id);

            if (hovering && !alreadyTracked) {
                guiGraphics.fill(left, top, left + width, top + height, 0x22FFFFFF);
            }

            // Item icon
            guiGraphics.renderItem(item.stack, left + 4, top + 5);

            // Right-side lightweight Add / Tracked button
            int btnX = left + width - ACTION_BTN_WIDTH - 4;
            int btnY = top + (height - ACTION_BTN_HEIGHT) / 2;
            boolean btnHovered = !alreadyTracked
                    && mouseX >= btnX && mouseX < btnX + ACTION_BTN_WIDTH
                    && mouseY >= btnY && mouseY < btnY + ACTION_BTN_HEIGHT;

            int bgColor = alreadyTracked ? 0xFF2B2B2B : (btnHovered ? 0xFF4E6B4E : 0xFF3A3A3A);
            int borderColor = alreadyTracked ? 0xFF505050 : (btnHovered ? 0xFF88CC88 : 0xFF707070);
            int labelColor = alreadyTracked ? 0xFF88CC88 : 0xFFFFFFFF;

            guiGraphics.fill(btnX, btnY, btnX + ACTION_BTN_WIDTH, btnY + ACTION_BTN_HEIGHT, borderColor);
            guiGraphics.fill(btnX + 1, btnY + 1, btnX + ACTION_BTN_WIDTH - 1, btnY + ACTION_BTN_HEIGHT - 1, bgColor);

            Component btnLabel = alreadyTracked
                    ? Component.translatable("gui.itemmilestonelog.picker.tracked")
                    : Component.translatable("gui.itemmilestonelog.picker.add_item");
            guiGraphics.drawCenteredString(ItemPickerScreen.this.font, btnLabel,
                    btnX + ACTION_BTN_WIDTH / 2, btnY + 5, labelColor);

            // Text column
            int textX = left + 26;
            int maxTextWidth = Math.max(40, btnX - textX - 6);

            String trimmedName = ItemPickerScreen.this.font.plainSubstrByWidth(item.displayName.getString(), maxTextWidth);
            guiGraphics.drawString(ItemPickerScreen.this.font, trimmedName, textX, top + 3,
                    alreadyTracked ? 0xB0B0B0 : 0xFFFFFF, false);

            String trimmedId = ItemPickerScreen.this.font.plainSubstrByWidth(item.id, maxTextWidth);
            guiGraphics.drawString(ItemPickerScreen.this.font, trimmedId, textX, top + 14, 0x909090, false);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0 && !trackedIds.contains(item.id)) {
                if (ItemPickerScreen.this.minecraft != null) {
                    ItemPickerScreen.this.minecraft.getSoundManager()
                            .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                }
                ClientMilestoneHandler.sendAction(MilestoneActionPayload.Action.ADD_TARGET, item.id);
                return true;
            }
            return false;
        }

        @Override
        public Component getNarration() {
            return item.displayName;
        }
    }
}
