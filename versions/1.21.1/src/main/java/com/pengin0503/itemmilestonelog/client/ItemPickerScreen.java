package com.pengin0503.itemmilestonelog.client;

import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.Config;
import com.pengin0503.itemmilestonelog.ItemNameCompletion;
import com.pengin0503.itemmilestonelog.SearchHistoryStore;
import com.pengin0503.itemmilestonelog.compat.McCompat;
import com.pengin0503.itemmilestonelog.network.MilestoneActionPayload;
import com.pengin0503.itemmilestonelog.network.SyncMilestonesPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
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
import net.neoforged.fml.loading.FMLPaths;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.lwjgl.glfw.GLFW;

/**
 * Searchable item selector screen for adding new personal target items.
 *
 * Caches the sorted item catalog per language/registry size and caps displayed
 * rows so real-time search stays smooth even in huge modpacks such as ATM10.
 * Supports multi-token search and {@code @modid} namespace filtering.
 */
public final class ItemPickerScreen extends Screen {
    private static final int ACTION_BTN_WIDTH = 64;
    private static final int ACTION_BTN_HEIGHT = 18;
    private static final int MAX_DISPLAYED_ROWS = 300;
    private static final int COUNT_BOX_WIDTH = 56;
    // #116: immutable labels; render() runs for every visible row on every frame.
    private static final Component TRACKED_LABEL = Component.translatable("gui.itemmilestonelog.picker.tracked");
    private static final Component ADD_LABEL = Component.translatable("gui.itemmilestonelog.picker.add_item");

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
    // Created by McCompat.lists(): the list subclass that positions the scrollbar is per profile.
    private ContainerObjectSelectionList<ItemRow> listWidget;
    private String currentQuery = "";
    private String currentCountText = "1";
    private int parsedCount = TargetKey.MIN_COUNT;
    private int countBoxX = 0;
    private int countBoxY = 0;
    private int matchedCount = 0;
    private int displayedCount = 0;
    // #116: item IDs already tracked at the current count, rebuilt on count/data changes.
    private Set<String> trackedItemIdsAtCount = Set.of();
    @Nullable
    private ItemStack hoveredIconStack = null;
    // F-150-75/84: suggestions and history are rebuilt only when the query changes (FL-5).
    private List<ItemNameCompletion.Candidate> catalogCandidates = List.of();
    private List<ItemNameCompletion.Candidate> suggestions = List.of();
    private List<String> searchHistory = List.of();
    private int historyIndex = -1;
    private String historyDraft = "";
    private boolean restoringHistory;

    public ItemPickerScreen(MilestoneScreen parent) {
        super(Component.translatable("gui.itemmilestonelog.picker.title"));
        this.parent = parent;
        this.trackedTargets = parent.getTrackedTargets();
        this.allItems = getOrBuildCatalog();
        List<ItemNameCompletion.Candidate> candidates = new ArrayList<>(this.allItems.size());
        for (CatalogItem item : this.allItems) {
            candidates.add(new ItemNameCompletion.Candidate(item.id(), item.nameText(),
                    item.lowerName(), item.lowerId()));
        }
        this.catalogCandidates = List.copyOf(candidates);
        if (Config.searchHistoryEnabled()) {
            try {
                this.searchHistory = SearchHistoryStore.read(FMLPaths.CONFIGDIR.get());
            } catch (RuntimeException | LinkageError ignored) {
                this.searchHistory = List.of();
            }
        }
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
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(item);
            String id = rl.toString();
            Component name = McCompat.registries().itemName(item);
            String nameStr = name.getString();
            catalog.add(new CatalogItem(
                    id,
                    rl.getNamespace().toLowerCase(Locale.ROOT),
                    new ItemStack(item),
                    name,
                    nameStr,
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
        refreshTrackedAtCount();
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
        // #125: keep the scroll position across a resize.
        double previousScroll = listWidget != null ? McCompat.scroll().getScrollAmount(listWidget) : 0.0;
        int searchWidth = Math.min(340, this.width - 40);
        int searchX = (this.width - searchWidth) / 2;

        searchBox = addRenderableWidget(new EditBox(
                this.font, searchX, 24, searchWidth, 20,
                Component.translatable("gui.itemmilestonelog.picker.search")));
        searchBox.setHint(Component.translatable("gui.itemmilestonelog.picker.search_hint"));
        searchBox.setValue(currentQuery);
        searchBox.setResponder(this::onSearchChanged);
        setInitialFocus(searchBox);
        // No widget, coordinate, dimension or default hint text is changed: only the hint's
        // runtime text reflects the current completion candidates (FL-5 / D-150-211).
        refreshSearchHint();

        int listTop = 50;
        int listBottom = this.height - 44;
        int listHeight = Math.max(40, listBottom - listTop);

        listWidget = addRenderableWidget(McCompat.lists().containerList(
                this.minecraft, this.width, listHeight, listTop, 26, () -> Math.min(440, this.width - 32)));
        refreshList();
        listWidget.setScrollAmount(previousScroll);

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

        // Small quantity input box placed in the empty bottom-right space without moving existing widgets.
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
        this.suggestions = Config.searchCompletion()
                ? ItemNameCompletion.complete(catalogCandidates, query, ItemNameCompletion.MAX_SUGGESTIONS)
                : List.of();
        refreshSearchHint();
        if (!restoringHistory && Config.searchHistoryEnabled()
                && SearchHistoryStore.isSafeQuery(query)) {
            try {
                this.searchHistory = SearchHistoryStore.add(FMLPaths.CONFIGDIR.get(), query);
                this.historyIndex = -1;
            } catch (RuntimeException | LinkageError ignored) {
                // Local history is cosmetic; never interrupt the picker.
            }
        }
    }

    private void refreshSearchHint() {
        if (searchBox == null) {
            return;
        }
        if (suggestions.isEmpty()) {
            searchBox.setHint(Component.translatable("gui.itemmilestonelog.picker.search_hint"));
        } else {
            searchBox.setHint(Component.translatable("gui.itemmilestonelog.picker.search_completions",
                    ItemNameCompletion.formatHint(suggestions)));
        }
    }

    private void setSearchValue(String value) {
        restoringHistory = true;
        searchBox.setValue(value);
        onSearchChanged(value);
        restoringHistory = false;
    }

    /** Up/Down walk the local history; Tab (handled in keyPressed) accepts a completion. */
    private boolean cycleHistory(boolean up) {
        if (!Config.searchHistoryEnabled() || searchHistory.isEmpty()) {
            return false;
        }
        if (up) {
            if (historyIndex < 0) {
                historyDraft = currentQuery;
                historyIndex = searchHistory.size() - 1;
            } else if (historyIndex > 0) {
                historyIndex--;
            }
            setSearchValue(searchHistory.get(historyIndex));
            return true;
        }
        if (historyIndex < 0) {
            return false;
        }
        if (historyIndex + 1 >= searchHistory.size()) {
            historyIndex = -1;
            setSearchValue(historyDraft);
        } else {
            historyIndex++;
            setSearchValue(searchHistory.get(historyIndex));
        }
        return true;
    }
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // F-150-75/84: the focused EditBox keeps priority; Tab confirms, Up/Down recall history.
        if (this.getFocused() == searchBox) {
            if (keyCode == GLFW.GLFW_KEY_TAB && !suggestions.isEmpty()) {
                setSearchValue(suggestions.get(0).displayName());
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) {
                return cycleHistory(keyCode == GLFW.GLFW_KEY_UP);
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }


    private void onCountChanged(String text) {
        this.currentCountText = text;
        this.parsedCount = parseCountInput(text);
        refreshTrackedAtCount();
        if (countBox != null) {
            countBox.setTextColor(this.parsedCount >= TargetKey.MIN_COUNT ? 0xFFE0E0E0 : 0xFFFF5555);
        }
    }

    /** #116: precomputes the (itemId, count) matches instead of allocating a TargetKey per row/frame. */
    private void refreshTrackedAtCount() {
        if (parsedCount < TargetKey.MIN_COUNT) {
            this.trackedItemIdsAtCount = Set.of();
            return;
        }
        Set<String> ids = new HashSet<>();
        for (TargetKey key : trackedTargets) {
            if (key.count() == parsedCount) {
                ids.add(key.itemId());
            }
        }
        this.trackedItemIdsAtCount = ids;
    }

    private void refreshList() {
        if (listWidget == null) {
            return;
        }
        McCompat.lists().clearRows(listWidget);
        String trimmed = currentQuery.trim().toLowerCase(Locale.ROOT);
        String[] tokens = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");

        int matches = 0;
        int shown = 0;
        for (CatalogItem item : allItems) {
            if (matchesQuery(item, tokens)) {
                matches++;
                if (shown < MAX_DISPLAYED_ROWS) {
                    McCompat.lists().addRow(listWidget, new ItemRow(item));
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
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.hoveredIconStack = null;
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        McCompat.text().drawCenteredString(guiGraphics, this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);

        Component countText = matchedCount > displayedCount
                ? Component.translatable("gui.itemmilestonelog.picker.count_capped", displayedCount, matchedCount)
                : Component.translatable("gui.itemmilestonelog.picker.count", matchedCount, allItems.size());
        int backLeft = Math.min((this.width - 120) / 2, this.countBoxX - 124);
        if (backLeft - 16 >= this.font.width(countText)) {
            McCompat.text().drawString(guiGraphics, this.font, countText, 12, this.height - 20, 0xFFB0B0B0, false);
        }

        // Quantity label to the left of the count box (uses short "×" if narrow).
        int backRight = Math.min((this.width + 120) / 2, this.countBoxX - 4);
        Component fullQtyLabel = Component.translatable("gui.itemmilestonelog.picker.quantity");
        int fullWidth = this.font.width(fullQtyLabel);
        if (countBoxX - backRight >= fullWidth + 10) {
            McCompat.text().drawString(guiGraphics, this.font, fullQtyLabel, countBoxX - fullWidth - 4, countBoxY + 6, 0xFFB0B0B0, false);
        } else if (countBoxX - backRight >= this.font.width("×") + 6) {
            McCompat.text().drawString(guiGraphics, this.font, "×", countBoxX - this.font.width("×") - 3, countBoxY + 6, 0xFFB0B0B0, false);
        }

        if (feedbackMessage != null) {
            int maxWidth = this.width - 20;
            String trimmedFeedback = this.font.plainSubstrByWidth(feedbackMessage.getString(), maxWidth);
            McCompat.text().drawCenteredString(guiGraphics, this.font, trimmedFeedback, this.width / 2, this.height - 39, 0xFFFFE88A);
        }

        if (matchedCount == 0 && listWidget != null) {
            int centerY = listWidget.getY() + listWidget.getHeight() / 2 - 4;
            McCompat.text().drawCenteredString(guiGraphics, this.font,
                    Component.translatable("gui.itemmilestonelog.picker.no_results"),
                    this.width / 2, centerY, 0xFFA0A0A0);
        }

        if (hoveredIconStack != null) {
            McCompat.tooltip().renderItemTooltip(guiGraphics, this.font, hoveredIconStack, mouseX, mouseY);
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
            String nameText,
            String lowerName,
            String lowerId
    ) {
    }

    // --------------------------------------------------------- list rows

    private final class ItemRow extends ContainerObjectSelectionList.Entry<ItemRow> {
        private final CatalogItem item;

        public ItemRow(CatalogItem item) {
            this.item = item;
        }

        @Override
        public void render(GuiGraphics guiGraphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovering, float partialTick) {
            boolean countValid = parsedCount >= TargetKey.MIN_COUNT;
            boolean alreadyTracked = countValid && trackedItemIdsAtCount.contains(item.id);
            boolean canAdd = countValid && !alreadyTracked;

            if (hovering && canAdd) {
                guiGraphics.fill(left, top, left + width, top + height, 0x22FFFFFF);
            }

            // Item icon (+ tooltip on hover)
            int iconX = left + 4;
            int iconY = top + 5;
            guiGraphics.renderItem(item.stack, iconX, iconY);
            if (hovering && mouseX >= iconX && mouseX < iconX + 16 && mouseY >= iconY && mouseY < iconY + 16) {
                ItemPickerScreen.this.hoveredIconStack = item.stack;
            }

            // Right-side lightweight Add / Tracked button
            int btnX = left + width - ACTION_BTN_WIDTH - 4;
            int btnY = top + (height - ACTION_BTN_HEIGHT) / 2;
            boolean btnHovered = canAdd
                    && mouseX >= btnX && mouseX < btnX + ACTION_BTN_WIDTH
                    && mouseY >= btnY && mouseY < btnY + ACTION_BTN_HEIGHT;

            int bgColor = !canAdd ? 0xFF2B2B2B : (btnHovered ? 0xFF4E6B4E : 0xFF3A3A3A);
            int borderColor = !canAdd ? 0xFF505050 : (btnHovered ? 0xFF88CC88 : 0xFF707070);
            int labelColor = alreadyTracked ? 0xFF88CC88 : (canAdd ? 0xFFFFFFFF : 0xFF808080);

            guiGraphics.fill(btnX, btnY, btnX + ACTION_BTN_WIDTH, btnY + ACTION_BTN_HEIGHT, borderColor);
            guiGraphics.fill(btnX + 1, btnY + 1, btnX + ACTION_BTN_WIDTH - 1, btnY + ACTION_BTN_HEIGHT - 1, bgColor);

            Component btnLabel = alreadyTracked ? TRACKED_LABEL : ADD_LABEL;
            McCompat.text().drawCenteredString(guiGraphics, ItemPickerScreen.this.font, btnLabel,
                    btnX + ACTION_BTN_WIDTH / 2, btnY + 5, labelColor);

            // Text column
            int textX = left + 26;
            int maxTextWidth = Math.max(40, btnX - textX - 6);

            String trimmedName = ItemPickerScreen.this.font.plainSubstrByWidth(item.nameText, maxTextWidth);
            McCompat.text().drawString(guiGraphics, ItemPickerScreen.this.font, trimmedName, textX, top + 3,
                    alreadyTracked ? 0xFFB0B0B0 : 0xFFFFFFFF, false);

            String trimmedId = ItemPickerScreen.this.font.plainSubstrByWidth(item.id, maxTextWidth);
            McCompat.text().drawString(guiGraphics, ItemPickerScreen.this.font, trimmedId, textX, top + 14, 0xFF909090, false);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0 && parsedCount >= TargetKey.MIN_COUNT
                    && !trackedItemIdsAtCount.contains(item.id)) {
                if (ItemPickerScreen.this.minecraft != null) {
                    McCompat.sound().play(ItemPickerScreen.this.minecraft.getSoundManager(),
                            SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                }
                ClientMilestoneHandler.sendAction(MilestoneActionPayload.Action.ADD_TARGET, item.id, parsedCount);
                return true;
            }
            return false;
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of();
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of();
        }
    }
}
