package com.pengin0503.itemmilestonelog.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.pengin0503.itemmilestonelog.Config;
import com.pengin0503.itemmilestonelog.ExternalIntegrationGate;
import com.pengin0503.itemmilestonelog.ItemMilestoneLog;
import com.pengin0503.itemmilestonelog.network.ModNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.List;

/**
 * Client-only entrypoint for Item Milestone Log.
 *
 * Loaded only on {@link Dist#CLIENT} so dedicated servers never touch any
 * client rendering, screen, or keybinding classes.
 */
@Mod(value = ItemMilestoneLog.MOD_ID, dist = Dist.CLIENT)
public final class ItemMilestoneLogClient {
    public static final KeyMapping.Category KEY_CATEGORY = new KeyMapping.Category(
            ResourceLocation.fromNamespaceAndPath(ItemMilestoneLog.MOD_ID, "general"));
    public static final String KEY_OPEN_GUI = "key." + ItemMilestoneLog.MOD_ID + ".open_gui";
    public static final String KEY_ADD_HOVERED = "key." + ItemMilestoneLog.MOD_ID + ".add_hovered";

    // No default key: M, the obvious choice, is also the full-screen map key of FTB Chunks,
    // which All the Mods 10 includes (issue #17). Players pick a key under Controls.
    public static final KeyMapping OPEN_GUI_MAPPING = new KeyMapping(
            KEY_OPEN_GUI,
            KeyConflictContext.IN_GAME,
            InputConstants.UNKNOWN,
            KEY_CATEGORY
    );

    // Same category as the GUI key and also unbound by default (F41). GUI context means the
    // key is only active while a screen is open, so it cannot fire in the world.
    public static final KeyMapping ADD_HOVERED_MAPPING = new KeyMapping(
            KEY_ADD_HOVERED,
            KeyConflictContext.GUI,
            InputConstants.UNKNOWN,
            KEY_CATEGORY
    );

    // F-150-73: soft integration flags, read only once at client construction.
    private static boolean externalJade;
    private static boolean externalWthit;
    private static boolean externalTop;

    /**
     * F44: the NeoForge config screen must never render the webhook URLs, because their raw
     * values would be captured in screenshots and streams (F39). They are edited in the file.
     */
    private static final ConfigurationScreen.ConfigurationSectionScreen.Filter HIDDEN_CONFIG_FILTER =
            (context, key, original) -> switch (key) {
                case "discordWebhookUrl", "discordWorldFirstWebhookUrl" -> null;
                default -> original;
            };

    public ItemMilestoneLogClient(IEventBus modEventBus, ModContainer modContainer) {
        try {
            ModList list = ModList.get();
            externalJade = list.isLoaded("jade");
            externalWthit = list.isLoaded("wthit");
            externalTop = list.isLoaded("top");
        } catch (RuntimeException | LinkageError ignored) {
            externalJade = false;
            externalWthit = false;
            externalTop = false;
        }
        modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                (mod, parentScreen) -> new ConfigurationScreen(mod, parentScreen, HIDDEN_CONFIG_FILTER));
        ModNetworking.setClientSyncHandler(ClientMilestoneHandler::onSyncReceived);
        ModNetworking.setClientSyncExtraHandler(ClientMilestoneHandler::onSyncExtraReceived);
        modEventBus.addListener(ItemMilestoneLogClient::onRegisterKeyMappings);
        NeoForge.EVENT_BUS.addListener(ItemMilestoneLogClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(ItemMilestoneLogClient::onScreenKeyPressed);
        NeoForge.EVENT_BUS.addListener(ItemMilestoneLogClient::onScreenMousePressed);
        // F42: run after Tracker's NORMAL-priority listener has rebuilt the tag cache.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, ItemMilestoneLogClient::onTagsUpdated);
        NeoForge.EVENT_BUS.addListener(ItemMilestoneLogClient::onItemTooltip);
        NeoForge.EVENT_BUS.addListener(ItemMilestoneLogClient::onClientLoggingOut);
    }

    private static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // PR-5: flush cache, rotate backup, optional export - before snapshots are cleared.
        ClientLocalOutput.onLogout();
        ClientMilestoneHandler.clearExtraData();
        ClientMilestoneHandler.clearTooltipData();
    }

    /**
     * F42: appends milestone status lines to item tooltips. This listener is registered only by
     * this {@link Dist#CLIENT} entry point, so the event is never handled on a dedicated server.
     */
    private static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack == null || stack.isEmpty()) {
            return;
        }
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        List<Component> lines = ClientMilestoneHandler.tooltipLines(itemId);
        boolean addedF42 = false;
        if (Config.itemTooltipStatus() && !lines.isEmpty()
                && !event.getToolTip().contains(lines.get(0))) {
            event.getToolTip().addAll(lines);
            addedF42 = true;
        }
        if (ExternalIntegrationGate.addLine(Config.externalTooltipIntegration(),
                externalJade, externalWthit, externalTop, addedF42) && !lines.isEmpty()) {
            event.getToolTip().add(lines.get(0));
        }
    }

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.registerCategory(KEY_CATEGORY);
        event.register(OPEN_GUI_MAPPING);
        event.register(ADD_HOVERED_MAPPING);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) {
            return;
        }
        while (OPEN_GUI_MAPPING.consumeClick()) {
            ClientMilestoneHandler.requestOpenGui();
        }
    }

    /**
     * F41: only {@code KeyPressed.Post} is used, so a key typed into a search field has
     * already been consumed by the screen and never adds the hovered item.
     */
    private static void onScreenKeyPressed(ScreenEvent.KeyPressed.Post event) {
        if (!ADD_HOVERED_MAPPING.isActiveAndMatches(
                InputConstants.getKey(event.getKeyEvent()))) {
            return;
        }
        addHoveredFromScreen(event.getScreen());
    }

    /**
     * F41: the same hovered-slot action for a mouse button assigned to {@code ADD_HOVERED_MAPPING}.
     * A KeyMapping can be bound to a mouse button, but mouse presses only produce
     * {@link ScreenEvent.MouseButtonPressed.Post}, not {@link ScreenEvent.KeyPressed.Post}.
     */
    private static void onScreenMousePressed(ScreenEvent.MouseButtonPressed.Post event) {
        if (!ADD_HOVERED_MAPPING.isActiveAndMatches(
                InputConstants.Type.MOUSE.getOrCreate(event.getButton()))) {
            return;
        }
        addHoveredFromScreen(event.getScreen());
    }

    private static void addHoveredFromScreen(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            return;
        }
        Slot slot = container.getSlotUnderMouse();
        if (slot == null || !slot.hasItem()) {
            return;
        }
        ClientMilestoneHandler.addHoveredTarget(slot.getItem());
    }

    /** F42: rebuild the tooltip index after the client's tag-packet update. */
    private static void onTagsUpdated(TagsUpdatedEvent event) {
        // #155: ignore SERVER_DATA_LOAD (on an integrated server it fires on the server thread and
        // could write back tooltip state the client thread discarded).
        if (event.getUpdateCause() != TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED) {
            return;
        }
        ClientMilestoneHandler.onTagsUpdated();
    }
}
