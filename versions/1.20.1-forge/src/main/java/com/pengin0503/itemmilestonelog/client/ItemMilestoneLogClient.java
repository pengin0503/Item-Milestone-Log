package com.pengin0503.itemmilestonelog.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.pengin0503.itemmilestonelog.ClientPrefsStore;
import com.pengin0503.itemmilestonelog.Config;
import com.pengin0503.itemmilestonelog.ExternalIntegrationGate;
import com.pengin0503.itemmilestonelog.HoverCountPolicy;
import com.pengin0503.itemmilestonelog.HoverItemCache;
import com.pengin0503.itemmilestonelog.HoverShortcutPolicy;
import com.pengin0503.itemmilestonelog.UnboundKeyHintPolicy;
import com.pengin0503.itemmilestonelog.ItemMilestoneLog;
import com.pengin0503.itemmilestonelog.network.MilestoneActionPayload;
import com.pengin0503.itemmilestonelog.network.ModNetworking;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
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

import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;

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

    // F-150-77: one-time, delayed local hint. The copy key from F-150-71 arrives in a later PR;
    // until then it is treated as unbound (fourth flag in unboundKeys()).
    private static final UnboundKeyHintPolicy UNBOUND_HINT = new UnboundKeyHintPolicy();
    private static boolean unboundHintStored;
    // F-150-59: soft integration only. No JEI/EMI/REI class or API is referenced; when none of
    // the three is loaded, the tooltip cache is never populated (a pure no-op).
    private static boolean hoverModsPresent;
    // F-150-73: soft integration flags, read only once at client construction.
    private static boolean externalJade;
    private static boolean externalWthit;
    private static boolean externalTop;

    private ItemMilestoneLogClient() {
    }

    /**
     * Registers the client payload handlers (sync -> {@link ClientMilestoneHandler}), the
     * key mappings (mod bus) and the client-only Forge event listeners. Called from the mod
     * constructor only on {@code Dist.CLIENT}.
     */
    public static void init() {
        try {
            ModList list = ModList.get();
            hoverModsPresent = list.isLoaded("jei") || list.isLoaded("emi") || list.isLoaded("rei");
            // F-150-73: one ModList check at construction; never reflect into these mods (D-150-207).
            externalJade = list.isLoaded("jade");
            externalWthit = list.isLoaded("wthit");
            externalTop = list.isLoaded("top");
        } catch (RuntimeException | LinkageError ignored) {
            hoverModsPresent = false;
            externalJade = false;
            externalWthit = false;
            externalTop = false;
        }
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
        MinecraftForge.EVENT_BUS.addListener(ItemMilestoneLogClient::onClientLoggingIn);
        MinecraftForge.EVENT_BUS.addListener(ItemMilestoneLogClient::onRegisterClientCommands);
        MinecraftForge.EVENT_BUS.addListener(ItemMilestoneLogClient::onRenderGuiPost);
        MinecraftForge.EVENT_BUS.addListener(ItemMilestoneLogClient::onLivingDamage);
    }

    private static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // PR-5: flush cache, rotate backup, optional export - before snapshots are cleared.
        ClientLocalOutput.onLogout();
        ClientMilestoneHandler.clearExtraData();
        ClientMilestoneHandler.clearTooltipData();
        ClientMilestoneHandler.clearHoverCache();
        MilestoneHudOverlay.onLogout();
        UNBOUND_HINT.clear();
    }

    /** F-150-62: register /imlclient on the client dispatcher only (D-150-185). */
    private static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        ClientPinCommands.register(event.getDispatcher());
    }

    /** F-150-63: independent overlay, registered outside every frozen screen (FL-3). */
    private static void onRenderGuiPost(RenderGuiEvent.Post event) {
        MilestoneHudOverlay.render(event.getGuiGraphics());
    }

    /** F-150-80: client-player damage starts the three-second retreat. */
    private static void onLivingDamage(LivingDamageEvent event) {
        if (event.getEntity() == Minecraft.getInstance().player) {
            MilestoneHudOverlay.onDamage();
        }
    }

    /** F-150-77: schedule the once-only hint on login; the tick handler applies the 2 s delay. */
    private static void onClientLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        try {
            unboundHintStored = ClientPrefsStore.unboundHintShown(FMLPaths.CONFIGDIR.get());
        } catch (RuntimeException | LinkageError e) {
            // If local preferences cannot be read, do not show a hint that could repeat.
            unboundHintStored = true;
        }
        UNBOUND_HINT.schedule(System.currentTimeMillis());
        MilestoneHudOverlay.onLogin(Minecraft.getInstance());
    }

    private static UnboundKeyHintPolicy.Keys unboundKeys() {
        return new UnboundKeyHintPolicy.Keys(
                OPEN_GUI_MAPPING.getKey() == InputConstants.UNKNOWN,
                ADD_HOVERED_MAPPING.getKey() == InputConstants.UNKNOWN,
                MUTE_NOTIFICATION_SOUND_MAPPING.getKey() == InputConstants.UNKNOWN,
                true); // F-150-71's copy key is not registered until PR-7: still unbound here.
    }

    /**
     * F42: appends milestone status lines to item tooltips. This listener is registered only by
     * this client bootstrap, so the event is never handled on a dedicated server.
     */
    private static void onItemTooltip(ItemTooltipEvent event) {
        if (hoverModsPresent) {
            ClientMilestoneHandler.rememberTooltipStack(event.getItemStack());
        }
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
        // F-150-73: at most one line even when all three mods are present; the existing
        // Component and colour are reused, and a duplicate F42 row suppresses it (D-150-208).
        if (ExternalIntegrationGate.addLine(Config.externalTooltipIntegration(),
                externalJade, externalWthit, externalTop, addedF42) && !lines.isEmpty()) {
            event.getToolTip().add(lines.get(0));
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
        if (UNBOUND_HINT.shouldShow(System.currentTimeMillis(), unboundKeys(), unboundHintStored)) {
            Minecraft mcHint = Minecraft.getInstance();
            if (mcHint.player != null) {
                mcHint.player.displayClientMessage(Component.translatable("message.itemmilestonelog.unbound_key_hint"), true);
                UNBOUND_HINT.markShown();
                unboundHintStored = true;
                try {
                    ClientPrefsStore.markUnboundHintShown(FMLPaths.CONFIGDIR.get());
                } catch (RuntimeException | LinkageError ignored) {
                    // Local preference write failure must never interrupt the game.
                }
            }
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
        handleHoverShortcut(event.getScreen(),
                HoverShortcutPolicy.Modifiers.fromGlfw(event.getModifiers()));
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
        // Older ScreenEvent mouse events expose no modifiers; treat those as "no modifier"
        // instead of reading an unavailable API (spec F-150-60-5).
        handleHoverShortcut(event.getScreen(),
                HoverShortcutPolicy.Modifiers.fromGlfw(0));
    }

    /**
     * F-150-59/60/61/78: resolves the hover target (slot first, short-lived tooltip cache second)
     * and sends one of the existing ADD_TARGET / REMOVE_TARGET / ADD_HAND actions. No new key,
     * button, screen or Action ordinal is involved (FL-4/FL-7).
     */
    private static void handleHoverShortcut(Screen screen, HoverShortcutPolicy.Modifiers modifiers) {
        HoverShortcutPolicy.Operation operation = HoverShortcutPolicy.choose(modifiers);
        if (operation == HoverShortcutPolicy.Operation.ADD_HAND) {
            Minecraft mc = Minecraft.getInstance();
            ClientMilestoneHandler.addHandTarget(mc.player == null ? null : mc.player.getMainHandItem());
            return;
        }
        HoverItemCache.Entry target = null;
        if (screen instanceof AbstractContainerScreen<?> container) {
            Slot slot = container.getSlotUnderMouse();
            if (slot != null && slot.hasItem()) {
                target = ClientMilestoneHandler.entryFrom(slot.getItem());
            }
        }
        if (target == null && hoverModsPresent && Config.hoverModIntegration()) {
            target = ClientMilestoneHandler.hoveredStack();
        }
        if (target == null || target.isEmpty()) {
            if (hoverModsPresent && Config.hoverModIntegration()) {
                ClientMilestoneHandler.showHoverUnavailable();
            }
            return;
        }
        switch (operation) {
            case ADD_COUNT_ONE -> ClientMilestoneHandler.addHoveredTarget(target, 1);
            case ADD_STACK_COUNT -> ClientMilestoneHandler.addHoveredTarget(target,
                    HoverCountPolicy.forStack(true, target.count()));
            case REMOVE_TARGET -> ClientMilestoneHandler.removeHoveredTarget(target);
            case ADD_HAND -> { /* handled before resolving the hover target */ }
        }
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
