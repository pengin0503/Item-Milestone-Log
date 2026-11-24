package com.pengin0503.itemmilestonelog.client;

import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.compat.client.ClientCompat;
import com.pengin0503.itemmilestonelog.network.MilestoneActionPayload;
import com.pengin0503.itemmilestonelog.network.SyncMilestonesPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Searchable item selector screen for adding new personal target items (Minecraft 26.1.2).
 */
public final class ItemPickerScreen extends Screen {
    private static final int ACTION_BTN_WIDTH = 64;
    private static final int ACTION_BTN_HEIGHT = 18;
    private static final int MAX_DISPLAYED_ROWS = 300;
    private static final int COUNT_BOX_WIDTH = 56;

    @Nullable
    private static List<CatalogItem> cachedCatalog;
    @Nullable
    private static String cachedLanguage;
    private static int cachedRegistrySize = -1;
    @Nullable
    private static Object cachedLanguageInstance;

    private final MilestoneScreen parent;
    private final List<CatalogItem> allItems;
    private Set<TargetKey> trackedTargets;
    @Nullable
    private Component feedbackMessage;

    private EditBox searchBox;
    private EditBox countBox;
    private ItemListWidget listWidget;
    private String currentQuery = "";
    private String currentCountText = "1";
    private int parsedCount = TargetKey.MIN_COUNT;
    private int countBoxX = 0;
    private int countBoxY = 0;
    private int matchedCount = 0;
    private int displayedCount = 0;
    @Nullable
    private ItemStack hoveredIconStack = null;

    public ItemPickerScreen(MilestoneScreen parent) {
        super(Component.translatable("gui.itemmilestonelog.picker.title"));
        this.parent = parent;
        this.trackedTargets = parent.getTrackedTargets();
        this.allItems = getOrBuildCatalog();
    }

    private static List<CatalogItem> getOrBuildCatalog() {
        String lang = Minecraft.getInstance().getLanguageManager().getSelected();
        int regSize = BuiltInRegistries.ITEM.size();
        Object langInstance = net.minecraft.locale.Language.getInstance();
        if (cachedCatalog != null && regSize == cachedRegistrySize
                && lang.equals(cachedLanguage) && langInstance == cachedLanguageInstance) {
            return cachedCatalog;
        }
        List<CatalogItem> catalog = new ArrayList<>(regSize);
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) {
                continue;
            }
            Identifier rl = BuiltInRegistries.ITEM.getKey(item);
            String id = rl.toString();
            ItemStack stack = new ItemStack(item);
            Component name = item.getName(stack);
            String nameStr = name.getString();
            catalog.add(new CatalogItem(
                    id,
                    rl.getNamespace().toLowerCase(Locale.ROOT),
                    stack,
                    name,
                    nameStr.toLowerCase(Locale.ROOT),
                    id.toLowerCase(Locale.ROOT)
            ));
        }
        catalog.sort(Comparator.comparing(c -> c.lowerName));
        cachedCatalog = catalog;
        cachedLanguage = lang;
        cachedRegistrySize = regSize;
        cachedLanguageInstance = langInstance;
        return catalog;
    }

    public void updateData(SyncMilestonesPayload payload) {
        this.parent.updateData(payload, false);
        this.trackedTargets = this.parent.getTrackedTargets();
        if (payload.feedback() != null) {
            this.feedbackMessage = payload.feedback();
        }
    }

    @Override
    public void tick() {
        super.tick();
        this.parent.tickPlaytime();
    }

    static int parseCountInput(@Nullable String raw) {
        if (raw == null) {
            return TargetKey.MIN_COUNT;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return TargetKey.MIN_COUNT;
        }
        return TargetKey.tryParseCount(trimmed);
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
        int listBottom = this.height - 44;
        int listHeight = Math.max(40, listBottom - listTop);

        listWidget = addRenderableWidget(new ItemListWidget(this.minecraft, this.width, listHeight, listTop, 26));
        refreshList();

        int backWidth = 120;
        int backX = (this.width - backWidth) / 2;
        int availRight = this.width - (backX + backWidth) - 16;
        int boxWidth = Math.min(COUNT_BOX_WIDTH, Math.max(28, availRight));
        if (backX + backWidth + 4 > this.width - 12 - boxWidth) {
            backX = Math.max(8, this.width - 16 - boxWidth - backWidth);
        }
        addRenderableWidget(Button.builder(
                        CommonComponents.GUI_BACK,
                        btn -> onClose())
                .bounds(backX, this.height - 26, backWidth, 20)
                .build());

        this.countBoxX = this.width - 12 - boxWidth;
        this.countBoxY = this.height - 26;
        countBox = addRenderableWidget(new EditBox(
                this.font, countBoxX, countBoxY, boxWidth, 20,
                Component.translatable("gui.itemmilestonelog.picker.quantity")));
        countBox.setMaxLength(8);
        countBox.setHint(Component.literal("1"));
        countBox.setValue(currentCountText);
        countBox.setResponder(this::onCountChanged);
        onCountChanged(currentCountText);
    }

    private void onSearchChanged(String query) {
        this.currentQuery = query;
        refreshList();
    }

    private void onCountChanged(String text) {
        this.currentCountText = text;
        this.parsedCount = parseCountInput(text);
        if (countBox != null) {
            countBox.setTextColor(this.parsedCount >= TargetKey.MIN_COUNT ? 0xFFE0E0E0 : 0xFFFF5555);
        }
    }

    private void refreshList() {
        if (listWidget == null) {
            return;
        }
        listWidget.clearAllEntries();
        String trimmed = currentQuery.trim().toLowerCase(Locale.ROOT);
        String[] tokens = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");

        int matches = 0;
        int shown = 0;
        for (CatalogItem item : allItems) {
            if (matchesQuery(item, tokens)) {
                matches++;
                if (shown < MAX_DISPLAYED_ROWS) {
                    listWidget.addRow(new ItemRow(item));
                    shown++;
                }
            }
        }
        this.matchedCount = matches;
        this.displayedCount = shown;
        listWidget.setScrollAmount(0.0);
    }

    private static boolean matchesQuery(CatalogItem item, String[] tokens) {
        for (String token : tokens) {
            if (token.startsWith("@")) {
                String modQuery = token.substring(1);
                if (!modQuery.isEmpty() && !item.lowerNamespace.contains(modQuery)) {
                    return false;
                }
            } else if (!item.lowerName.contains(token) && !item.lowerId.contains(token)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            ClientCompat.screens().setScreen(this.minecraft, parent);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        this.hoveredIconStack = null;
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        graphics.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);

        Component countText = matchedCount > displayedCount
                ? Component.translatable("gui.itemmilestonelog.picker.count_capped", displayedCount, matchedCount)
                : Component.translatable("gui.itemmilestonelog.picker.count", matchedCount, allItems.size());
        int backLeft = Math.min((this.width - 120) / 2, this.countBoxX - 124);
        if (backLeft - 16 >= this.font.width(countText)) {
            graphics.text(this.font, countText, 12, this.height - 20, 0xFFB0B0B0, false);
        }

        int backRight = Math.min((this.width + 120) / 2, this.countBoxX - 4);
        Component fullQtyLabel = Component.translatable("gui.itemmilestonelog.picker.quantity");
        int fullWidth = this.font.width(fullQtyLabel);
        if (countBoxX - backRight >= fullWidth + 10) {
            graphics.text(this.font, fullQtyLabel, countBoxX - fullWidth - 4, countBoxY + 6, 0xFFB0B0B0, false);
        } else if (countBoxX - backRight >= this.font.width("×") + 6) {
            graphics.text(this.font, "×", countBoxX - this.font.width("×") - 3, countBoxY + 6, 0xFFB0B0B0, false);
        }

        if (feedbackMessage != null) {
            int maxWidth = this.width - 20;
            String trimmedFeedback = this.font.plainSubstrByWidth(feedbackMessage.getString(), maxWidth);
            graphics.centeredText(this.font, trimmedFeedback, this.width / 2, this.height - 39, 0xFFFFE88A);
        }

        if (matchedCount == 0 && listWidget != null) {
            int centerY = listWidget.getY() + listWidget.getHeight() / 2 - 4;
            graphics.centeredText(this.font,
                    Component.translatable("gui.itemmilestonelog.picker.no_results"),
                    this.width / 2, centerY, 0xFFA0A0A0);
        }

        if (hoveredIconStack != null) {
            graphics.setTooltipForNextFrame(this.font, hoveredIconStack, mouseX, mouseY);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record CatalogItem(
            String id,
            String lowerNamespace,
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
        protected int scrollBarX() {
            return this.getX() + this.width / 2 + getRowWidth() / 2 + 6;
        }
    }

    private final class ItemRow extends ObjectSelectionList.Entry<ItemRow> {
        private final CatalogItem item;

        public ItemRow(CatalogItem item) {
            this.item = item;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovering, float partialTick) {
            int left = getContentX();
            int top = getContentY();
            int width = getContentWidth();
            int height = getContentHeight();

            boolean countValid = parsedCount >= TargetKey.MIN_COUNT;
            boolean alreadyTracked = countValid && trackedTargets.contains(new TargetKey(item.id, parsedCount));
            boolean canAdd = countValid && !alreadyTracked;

            if (hovering && canAdd) {
                graphics.fill(left, top, left + width, top + height, 0x22FFFFFF);
            }

            int iconX = left + 4;
            int iconY = top + 3;
            graphics.item(item.stack, iconX, iconY);
            if (hovering && mouseX >= iconX && mouseX < iconX + 16 && mouseY >= iconY && mouseY < iconY + 16) {
                ItemPickerScreen.this.hoveredIconStack = item.stack;
            }

            int btnX = left + width - ACTION_BTN_WIDTH - 4;
            int btnY = top + (height - ACTION_BTN_HEIGHT) / 2;
            boolean btnHovered = canAdd
                    && mouseX >= btnX && mouseX < btnX + ACTION_BTN_WIDTH
                    && mouseY >= btnY && mouseY < btnY + ACTION_BTN_HEIGHT;

            int bgColor = !canAdd ? 0xFF2B2B2B : (btnHovered ? 0xFF4E6B4E : 0xFF3A3A3A);
            int borderColor = !canAdd ? 0xFF505050 : (btnHovered ? 0xFF88CC88 : 0xFF707070);
            int labelColor = alreadyTracked ? 0xFF88CC88 : (canAdd ? 0xFFFFFFFF : 0xFF808080);

            graphics.fill(btnX, btnY, btnX + ACTION_BTN_WIDTH, btnY + ACTION_BTN_HEIGHT, borderColor);
            graphics.fill(btnX + 1, btnY + 1, btnX + ACTION_BTN_WIDTH - 1, btnY + ACTION_BTN_HEIGHT - 1, bgColor);

            Component btnLabel = alreadyTracked
                    ? Component.translatable("gui.itemmilestonelog.picker.tracked")
                    : Component.translatable("gui.itemmilestonelog.picker.add_item");
            graphics.centeredText(ItemPickerScreen.this.font, btnLabel,
                    btnX + ACTION_BTN_WIDTH / 2, btnY + 5, labelColor);

            int textX = left + 26;
            int maxTextWidth = Math.max(40, btnX - textX - 6);

            String trimmedName = ItemPickerScreen.this.font.plainSubstrByWidth(item.displayName.getString(), maxTextWidth);
            graphics.text(ItemPickerScreen.this.font, trimmedName, textX, top + 1,
                    alreadyTracked ? 0xFFB0B0B0 : 0xFFFFFFFF, false);

            String trimmedId = ItemPickerScreen.this.font.plainSubstrByWidth(item.id, maxTextWidth);
            graphics.text(ItemPickerScreen.this.font, trimmedId, textX, top + 12, 0xFF909090, false);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
            if (event.button() == 0 && parsedCount >= TargetKey.MIN_COUNT
                    && !trackedTargets.contains(new TargetKey(item.id, parsedCount))) {
                if (ItemPickerScreen.this.minecraft != null) {
                    ItemPickerScreen.this.minecraft.getSoundManager()
                            .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                }
                ClientMilestoneHandler.sendAction(MilestoneActionPayload.Action.ADD_TARGET, item.id, parsedCount);
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
