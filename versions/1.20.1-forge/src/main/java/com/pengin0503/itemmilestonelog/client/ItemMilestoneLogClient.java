package com.pengin0503.itemmilestonelog.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.pengin0503.itemmilestonelog.Config;
import com.pengin0503.itemmilestonelog.ItemMilestoneLog;
import com.pengin0503.itemmilestonelog.network.MilestoneActionPayload;
import com.pengin0503.itemmilestonelog.network.ModNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.util.List;

/**
 * Client-only bootstrap for Item Milestone Log on Forge 1.20.1.
 *
 * <p>Forge has no dist-specific {@code @Mod} class, so {@link ItemMilestoneLog} calls
 * {@link #init()} through {@code DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)}. That
 * keeps every client rendering, screen and keybinding class off the dedicated server.
 *
 * <p>Unlike NeoForge 1.21.1 there is no generic configuration screen (DESIGN_1.20.1
 * §10-Q1: the screen is omitted), so no {@code IConfigScreenFactory} is registered.
 */
public final class ItemMilestoneLogClient {
    public static final String KEY_CATEGORY = "key.categories." + ItemMilestoneLog.MOD_ID;
    public static final String KEY_OPEN_GUI = "key." + ItemMilestoneLog.MOD_ID + ".open_gui";
    public static final String KEY_ADD_HOVERED = "key." + ItemMilestoneLog.MOD_ID + ".add_hovered";
    public static final String KEY_MUTE_NOTIFICATION_SOUND =
            "key." + ItemMilestoneLog.MOD_ID + ".mute_notification_sound";

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

    // F-150-50 (N121): third optional key, unbound by default like the existing two.
    public static final KeyMapping MUTE_NOTIFICATION_SOUND_MAPPING = new KeyMapping(
            KEY_MUTE_NOTIFICATION_SOUND,
            KeyConflictContext.IN_GAME,
            InputConstants.UNKNOWN,
            KEY_CATEGORY
    );

    private ItemMilestoneLogClient() {
    }

    /**
     * Registers the client payload handlers (sync -> {@link ClientMilestoneHandler}), the
     * key mappings (mod bus) and the client-only Forge event listeners. Called from the mod
     * constructor only on {@code Dist.CLIENT}.
     */
    public static void init() {
        ModNetworking.setClientSyncHandler(ClientMilestoneHandler::onSyncReceived);
        ModNetworking.setClientSyncExtraHandler(ClientMilestoneHandler::onSyncExtraReceived);
        FMLJavaModLoadingContext.get().getModEventBus()
                .addListener(ItemMilestoneLogClient::onRegisterKeyMappings);
        MinecraftForge.EVENT_BUS.addListener(ItemMilestoneLogClient::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(ItemMilestoneLogClient::onScreenKeyPressed);
        MinecraftForge.EVENT_BUS.addListener(ItemMilestoneLogClient::onScreenMousePressed);
        // F42: run after Tracker's NORMAL-priority listener has rebuilt the tag cache.
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOW, ItemMilestoneLogClient::onTagsUpdated);
        MinecraftForge.EVENT_BUS.addListener(ItemMilestoneLogClient::onItemTooltip);
        MinecraftForge.EVENT_BUS.addListener(ItemMilestoneLogClient::onClientLoggingOut);
    }

    private static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientMilestoneHandler.clearExtraData();
        ClientMilestoneHandler.clearTooltipData();
    }

    /**
     * F42: appends milestone status lines to item tooltips. This listener is registered only by
     * this client bootstrap, so the event is never handled on a dedicated server.
     */
    private static void onItemTooltip(ItemTooltipEvent event) {
        if (!Config.itemTooltipStatus()) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (stack == null || stack.isEmpty()) {
            return;
        }
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        List<Component> lines = ClientMilestoneHandler.tooltipLines(itemId);
        if (!lines.isEmpty()) {
            event.getToolTip().addAll(lines);
        }
    }

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_GUI_MAPPING);
        event.register(ADD_HOVERED_MAPPING);
        event.register(MUTE_NOTIFICATION_SOUND_MAPPING);
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        while (MUTE_NOTIFICATION_SOUND_MAPPING.consumeClick()) {
            // sendAction checks connection/channel and does nothing for vanilla-only peers.
            ClientMilestoneHandler.sendAction(
                    MilestoneActionPayload.Action.MUTE_NOTIFICATION_SOUND, "");
        }
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
                InputConstants.getKey(event.getKeyCode(), event.getScanCode()))) {
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
        // #155: Forge's TagsUpdatedEvent carries the same UpdateCause as NeoForge; ignore
        // SERVER_DATA_LOAD so an integrated-server rebuild cannot restore discarded tooltip state.
        if (event.getUpdateCause() != TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED) {
            return;
        }
        ClientMilestoneHandler.onTagsUpdated();
    }
}
