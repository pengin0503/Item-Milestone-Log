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
import com.pengin0503.itemmilestonelog.compat.client.ClientCompat;
import com.pengin0503.itemmilestonelog.AccessibilityPolicy;
import com.pengin0503.itemmilestonelog.network.MilestoneActionPayload;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import com.pengin0503.itemmilestonelog.network.ModNetworking;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
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

import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-only entrypoint for Item Milestone Log (Minecraft 26.1.2).
 */
@Mod(value = ItemMilestoneLog.MOD_ID, dist = Dist.CLIENT)
public final class ItemMilestoneLogClient {
    public static final KeyMapping.Category KEY_CATEGORY = new KeyMapping.Category(
            Identifier.fromNamespaceAndPath(ItemMilestoneLog.MOD_ID, "general"));
    public static final String KEY_OPEN_GUI = "key." + ItemMilestoneLog.MOD_ID + ".open_gui";
    public static final String KEY_ADD_HOVERED = "key." + ItemMilestoneLog.MOD_ID + ".add_hovered";
    public static final String KEY_MUTE_NOTIFICATION_SOUND =
            "key." + ItemMilestoneLog.MOD_ID + ".mute_notification_sound";

    /** F-150-71 (N68): fourth and final key; unbound by default like the other three. */
    public static final String KEY_COPY_RECENT = "key." + ItemMilestoneLog.MOD_ID + ".copy_recent";

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

    // F-150-71 (N68): fourth optional key. InputConstants.UNKNOWN avoids the FTB Chunks M-key
    // collision (issue #17) and keeps four total key mappings in every tree.
    public static final KeyMapping COPY_RECENT_MAPPING = new KeyMapping(
            KEY_COPY_RECENT,
            KeyConflictContext.IN_GAME,
            InputConstants.UNKNOWN,
            KEY_CATEGORY
    );

    // F-150-77: one-time, delayed local hint; all four bindings are inspected now.
    private static final UnboundKeyHintPolicy UNBOUND_HINT = new UnboundKeyHintPolicy();
    private static boolean unboundHintStored;
    private static boolean keyConflictChecked;
    // F-150-59: soft integration only. No JEI/EMI/REI class or API is referenced; when none of
    // the three is loaded, the tooltip cache is never populated (a pure no-op).
    private static boolean hoverModsPresent;
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
        modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                (mod, parentScreen) -> new ConfigurationScreen(mod, parentScreen, HIDDEN_CONFIG_FILTER));
        ModNetworking.setClientSyncHandler(ClientMilestoneHandler::onSyncReceived);
        ModNetworking.setClientSyncExtraHandler(ClientMilestoneHandler::onSyncExtraReceived);
        ModNetworking.setClientEffectHandler(ClientEffects::onEffect);
        modEventBus.addListener(ItemMilestoneLogClient::onRegisterKeyMappings);
        NeoForge.EVENT_BUS.addListener(ItemMilestoneLogClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(ItemMilestoneLogClient::onScreenKeyPressed);
        NeoForge.EVENT_BUS.addListener(ItemMilestoneLogClient::onScreenMousePressed);
        // F42: run after Tracker's NORMAL-priority listener has rebuilt the tag cache.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, ItemMilestoneLogClient::onTagsUpdated);
        NeoForge.EVENT_BUS.addListener(ItemMilestoneLogClient::onItemTooltip);
        NeoForge.EVENT_BUS.addListener(ItemMilestoneLogClient::onClientLoggingOut);
        NeoForge.EVENT_BUS.addListener(ItemMilestoneLogClient::onClientLoggingIn);
        NeoForge.EVENT_BUS.addListener(ItemMilestoneLogClient::onClientChat);
        NeoForge.EVENT_BUS.addListener(ItemMilestoneLogClient::onClientSound);
        NeoForge.EVENT_BUS.addListener(ItemMilestoneLogClient::onRegisterClientCommands);
        NeoForge.EVENT_BUS.addListener(ItemMilestoneLogClient::onRenderGuiPost);
        NeoForge.EVENT_BUS.addListener(ItemMilestoneLogClient::onLivingDamage);
    }

    private static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // PR-5: flush cache, rotate backup, optional export - before snapshots are cleared.
        ClientLocalOutput.onLogout();
        ClientMilestoneHandler.clearExtraData();
        ClientMilestoneHandler.clearTooltipData();
        ClientMilestoneHandler.clearHoverCache();
        MilestoneHudOverlay.onLogout();
        ClientEffects.onLogout();
        ClientSoundGate.resetMuted();
        UNBOUND_HINT.clear();
    }

    /** F-150-74: restyle only this mod's own chat messages; the default mode returns unchanged. */
    private static void onClientChat(ClientChatReceivedEvent event) {
        Component styled = ChatStyler.apply(event.getMessage());
        if (styled != null && styled != event.getMessage()) {
            event.setMessage(styled);
        }
    }

    /** F-150-81: a concrete PlaySoundEvent; setSound(null) suppresses a filtered notification. */
    private static void onClientSound(PlaySoundEvent event) {
        ClientSoundGate.onPlaySound(event);
    }

    /** F-150-82: report a duplicate key binding once; never change a player's assignment. */
    private static void warnKeyConflicts() {
        Map<String, Integer> bindings = new HashMap<>();
        bindings.put(KEY_OPEN_GUI, OPEN_GUI_MAPPING.getKey().getValue());
        bindings.put(KEY_ADD_HOVERED, ADD_HOVERED_MAPPING.getKey().getValue());
        bindings.put(KEY_MUTE_NOTIFICATION_SOUND, MUTE_NOTIFICATION_SOUND_MAPPING.getKey().getValue());
        List<AccessibilityPolicy.Conflict> conflicts = AccessibilityPolicy.detectConflicts(bindings);
        if (!conflicts.isEmpty()) {
            ClientNotice.overlay(Component.translatable("message.itemmilestonelog.key_conflict",
                    conflicts.get(0).keyCode()));
        }
    }

    /** F-150-62: register /imlclient on the client dispatcher only (D-150-185). */
    private static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        ClientPinCommands.register(event.getDispatcher());
    }

    /** F-150-63: independent overlay, registered outside every frozen screen (FL-3). */
    private static void onRenderGuiPost(RenderGuiEvent.Post event) {
        MilestoneHudOverlay.render(event.getGuiGraphics());
    }

    /**
     * F-150-80: client-player damage starts the three-second retreat. NeoForge splits
     * {@code LivingDamageEvent} into the abstract parent and the concrete {@code Pre}/{@code Post}
     * subclasses; FML rejects a listener registered for the abstract class at construction
     * ("Cannot register listeners for abstract class ..."). {@code Post} is the correct subclass:
     * it fires only after damage has actually been applied (it is not cancelable), which matches
     * "the player took damage -> fade the HUD". {@code Pre} is cancelable and would start the
     * retreat even when another mod/shield cancels the hit. {@code getEntity()} is inherited from
     * {@code LivingEvent}, so the body is unchanged.
     */
    private static void onLivingDamage(LivingDamageEvent.Post event) {
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
                COPY_RECENT_MAPPING.getKey() == InputConstants.UNKNOWN);
    }

    /**
     * F42: appends milestone status lines to item tooltips. This listener is registered only by
     * this {@link Dist#CLIENT} entry point, so the event is never handled on a dedicated server.
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
        event.registerCategory(KEY_CATEGORY);
        event.register(OPEN_GUI_MAPPING);
        event.register(ADD_HOVERED_MAPPING);
        event.register(MUTE_NOTIFICATION_SOUND_MAPPING);
        event.register(COPY_RECENT_MAPPING); // F-150-71: fourth and final registered key
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (!keyConflictChecked) {
            keyConflictChecked = true;
            warnKeyConflicts();
        }
        while (MUTE_NOTIFICATION_SOUND_MAPPING.consumeClick()) {
            ClientSoundGate.toggleMuted();
            // sendAction checks connection/channel and does nothing for vanilla-only peers.
            ClientMilestoneHandler.sendAction(
                    MilestoneActionPayload.Action.MUTE_NOTIFICATION_SOUND, "");
        }
        // F-150-86: coalesce HUD row-cache rebuilds only when low-power mode is on.
        MilestoneHudOverlay.onClientTick();
        // F-150-71: copy the newest local record; never sends anything to the server.
        while (COPY_RECENT_MAPPING.consumeClick()) {
            ClientRecentCopy.copyRecent();
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || ClientCompat.screens().currentScreen(mc) != null) {
            return;
        }
        while (OPEN_GUI_MAPPING.consumeClick()) {
            ClientMilestoneHandler.requestOpenGui();
        }
        if (UNBOUND_HINT.shouldShow(System.currentTimeMillis(), unboundKeys(), unboundHintStored)) {
            Minecraft mcHint = Minecraft.getInstance();
            if (mcHint.player != null) {
                mcHint.player.sendOverlayMessage(Component.translatable("message.itemmilestonelog.unbound_key_hint"));
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
                InputConstants.getKey(event.getKeyEvent()))) {
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
                HoverShortcutPolicy.Modifiers.fromGlfw(event.getMouseButtonEvent().modifiers()));
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
            Slot slot = ClientCompat.screens().hoveredSlot(container);
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
        // #155: only the client-packet event is a client-thread update. A server-data load on the
        // integrated server must not rebuild tooltip state the client thread discarded; the
        // subtype check is the only API common to 26.1/26.2/26.3 (getUpdateCause() is gone in 26.3).
        if (!(event instanceof TagsUpdatedEvent.ClientPacketReceived)) {
            return;
        }
        ClientMilestoneHandler.onTagsUpdated();
    }
}
