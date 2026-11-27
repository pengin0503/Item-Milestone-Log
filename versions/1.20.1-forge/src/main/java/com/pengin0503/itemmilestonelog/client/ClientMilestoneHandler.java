package com.pengin0503.itemmilestonelog.client;

import com.pengin0503.itemmilestonelog.AddTargetFeedbackWindow;
import com.pengin0503.itemmilestonelog.Config;
import com.pengin0503.itemmilestonelog.HoverCountPolicy;
import com.pengin0503.itemmilestonelog.HoverItemCache;
import com.pengin0503.itemmilestonelog.HoverShortcutPolicy;
import com.pengin0503.itemmilestonelog.ItemTooltipLogic;
import com.pengin0503.itemmilestonelog.Playtime;
import com.pengin0503.itemmilestonelog.TagTargets;
import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.compat.McCompat;
import com.pengin0503.itemmilestonelog.network.MilestoneActionPayload;
import com.pengin0503.itemmilestonelog.network.RequestSyncPayload;
import com.pengin0503.itemmilestonelog.network.SyncExtraPayload;
import com.pengin0503.itemmilestonelog.network.SyncMilestonesPayload;
import com.pengin0503.itemmilestonelog.network.TargetEntry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Handles client-side GUI networking: requesting snapshots, dispatching GUI actions,
 * and opening or live-updating the active milestone screens.
 */
public final class ClientMilestoneHandler {
    private static final String TOOLTIP_TARGET_KEY = "gui.itemmilestonelog.item_tooltip.target";
    private static final String TOOLTIP_RECORDED_KEY = "gui.itemmilestonelog.item_tooltip.recorded";
    private static final String TOOLTIP_MORE_KEY = "gui.itemmilestonelog.item_tooltip.more";

    private static final AddTargetFeedbackWindow ADD_TARGET_FEEDBACK = new AddTargetFeedbackWindow();
    private static final HoverItemCache HOVER_CACHE = new HoverItemCache();
    private static volatile SyncExtraPayload extraData = SyncExtraPayload.EMPTY;
    // F42: the latest sync and the precomputed itemId -> tooltip status index. Tooltips only
    // read these immutable snapshots; they never walk the payload or the tag registry.
    private static volatile SyncMilestonesPayload latestSync;
    private static volatile Map<String, List<ItemTooltipLogic.Status>> tooltipIndex = Map.of();
    private static volatile Map<String, Component> tagTooltipNames = Map.of();

    private ClientMilestoneHandler() {
    }

    public static void onSyncExtraReceived(@Nullable SyncExtraPayload payload) {
        extraData = payload != null ? payload : SyncExtraPayload.EMPTY;
    }

    public static void clearExtraData() {
        extraData = SyncExtraPayload.EMPTY;
        ADD_TARGET_FEEDBACK.clear();
        HOVER_CACHE.clear();
    }

    /** F42: discards the sync snapshot and tooltip index when the client disconnects. */
    public static void clearTooltipData() {
        latestSync = null;
        tooltipIndex = Map.of();
        tagTooltipNames = Map.of();
    }

    /**
     * F42: rebuilds the tooltip index from the latest sync after the client's item tags are
     * rebound (login tag sync or a server {@code /reload}). The server-side Tracker's
     * TagsUpdatedEvent listener runs first and refreshes {@link TagTargets}; this runs at
     * {@code EventPriority.LOW} so tag expansion sees the new members.
     */
    public static void onTagsUpdated() {
        SyncMilestonesPayload sync = latestSync;
        if (sync != null) {
            rebuildTooltipIndex(sync);
        }
    }

    public static SyncExtraPayload getExtraData() {
        return extraData;
    }

    public static boolean isWorldFirst(@Nullable TargetKey key) {
        return key != null && extraData.worldFirsts().contains(key);
    }

    public static boolean isReadOnlyPreset(@Nullable String presetId) {
        return presetId != null && extraData.readOnlyPresets().contains(presetId);
    }

    public static void requestOpenGui() {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            return;
        }
        // 1.20.1 has no ClientPacketListener#hasChannel; the optional Forge channel
        // presence is checked through the client networking facet instead.
        if (!McCompat.network().isRemotePresent(connection)) {
            showServerNotInstalled(mc);
            return;
        }
        McCompat.network().sendToServer(new RequestSyncPayload());
    }

    /** F41: add the hovered stack with the existing count-one ADD_TARGET action. */
    public static void addHoveredTarget(ItemStack stack) {
        HoverItemCache.Entry entry = entryFrom(stack);
        addHoveredTarget(entry, 1);
    }

    /** F-150-59: remembers a copied tooltip stack for other mods' GUIs (500 ms window). */
    public static void rememberTooltipStack(ItemStack stack) {
        if (!Config.hoverModIntegration()) {
            return;
        }
        if (stack == null || stack.isEmpty()) {
            HOVER_CACHE.clear();
            return;
        }
        ItemStack copy = stack.copy();
        String itemId = BuiltInRegistries.ITEM.getKey(copy.getItem()).toString();
        HOVER_CACHE.capture(new CopiedHoverStack(itemId, copy.getCount()), System.currentTimeMillis());
    }

    private record CopiedHoverStack(String itemId, int count) implements HoverItemCache.StackView {
    }

    /** Reads a real slot stack directly; works even when hover-mod integration is disabled. */
    public static HoverItemCache.Entry entryFrom(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return new HoverItemCache.Entry(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                stack.getCount());
    }

    /** Returns the short-lived tooltip stack, or null when none is usable. */
    public static HoverItemCache.Entry hoveredStack() {
        return HOVER_CACHE.get(System.currentTimeMillis());
    }

    public static void clearHoverCache() {
        HOVER_CACHE.clear();
    }

    /**
     * F-150-61 / F-150-78: resolves each shortcut to an existing action. No Action ordinal is
     * added; the version-tree dispatch test pins the mapping against the real enum.
     */
    public static MilestoneActionPayload.Action actionFor(HoverShortcutPolicy.Operation operation) {
        return switch (operation) {
            case ADD_COUNT_ONE, ADD_STACK_COUNT -> MilestoneActionPayload.Action.ADD_TARGET;
            case REMOVE_TARGET -> MilestoneActionPayload.Action.REMOVE_TARGET;
            case ADD_HAND -> MilestoneActionPayload.Action.ADD_HAND;
        };
    }

    /** F-150-60: Shift adds the hovered stack count through the same ADD_TARGET ordinal. */
    public static void addHoveredTarget(HoverItemCache.Entry hovered, int count) {
        if (hovered == null || hovered.isEmpty()) {
            return;
        }
        sendHoverAction(MilestoneActionPayload.Action.ADD_TARGET, hovered.itemId(), count);
        if (count > 1) {
            showOverlay(Component.translatable("message.itemmilestonelog.add_hovered_count", count));
        }
    }

    /** F-150-61: Alt removes the hovered item through the existing REMOVE_TARGET ordinal. */
    public static void removeHoveredTarget(HoverItemCache.Entry hovered) {
        if (hovered == null || hovered.isEmpty()) {
            return;
        }
        showOverlay(Component.translatable("message.itemmilestonelog.remove_hovered", hovered.itemId()));
        sendHoverAction(MilestoneActionPayload.Action.REMOVE_TARGET, hovered.itemId(), hovered.count());
    }

    /** F-150-78: Ctrl adds the held stack through the existing ADD_HAND ordinal. */
    public static void addHandTarget(ItemStack hand) {
        if (hand == null || hand.isEmpty()) {
            return;
        }
        int count = HoverCountPolicy.clamp(hand.getCount());
        String itemId = BuiltInRegistries.ITEM.getKey(hand.getItem()).toString();
        showOverlay(Component.translatable("message.itemmilestonelog.shortcut_add_hand", count));
        sendHoverAction(MilestoneActionPayload.Action.ADD_HAND, itemId, count);
    }

    /** F-150-59: an expired or empty hover cache must never add a stale item. */
    public static void showHoverUnavailable() {
        showOverlay(Component.translatable("message.itemmilestonelog.hover_unavailable"));
    }

    /**
     * Sends a hover-shortcut action. Unlike the silent {@link #sendAction} path used for the mute
     * key, server-dependent hover operations report a vanilla/old server and arm the existing
     * two-second feedback window (spec 1.3-5).
     */
    private static void sendHoverAction(MilestoneActionPayload.Action action, String argument, int count) {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            return;
        }
        if (!McCompat.network().isRemotePresent(connection)) {
            showServerNotInstalled(mc);
            return;
        }
        ADD_TARGET_FEEDBACK.markSent(System.currentTimeMillis());
        MilestoneActionPayload payload = new MilestoneActionPayload(
                action, argument == null ? "" : argument, count);
        McCompat.network().sendToServer(payload);
    }

    private static void showOverlay(Component message) {
        showOverlayOn(Minecraft.getInstance(), message);
    }

    private static void showOverlayOn(Minecraft mc, Component message) {
        if (mc.player == null) {
            return;
        }
        mc.player.displayClientMessage(message, true);
    }

    private static void showServerNotInstalled(Minecraft mc) {
        HOVER_CACHE.clear();
        showOverlayOn(mc, Component.translatable("message.itemmilestonelog.server_not_installed"));
    }

    public static void sendAction(MilestoneActionPayload.Action action, String argument) {
        sendAction(action, argument, 1);
    }

    public static void sendAction(MilestoneActionPayload.Action action, String argument, int count) {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null || !McCompat.network().isRemotePresent(connection)) {
            return;
        }
        McCompat.network().sendToServer(new MilestoneActionPayload(action, argument == null ? "" : argument, count));
    }

    /**
     * F42: rebuilds the {@code itemId -> ordered status list} lookup from the sync payload.
     * Personal + default targets and the player's own records are indexed; tag targets are
     * expanded to their member items through the client-side tag cache.
     */
    private static void rebuildTooltipIndex(SyncMilestonesPayload payload) {
        latestSync = payload;
        List<ItemTooltipLogic.Status> statuses =
                new ArrayList<>(payload.targets().size() + payload.records().size());
        Set<String> tagIds = new LinkedHashSet<>();
        for (TargetEntry entry : payload.targets()) {
            statuses.add(ItemTooltipLogic.Status.target(entry.itemId(), entry.count()));
            if (TargetKey.isTagId(entry.itemId())) {
                tagIds.add(entry.itemId());
            }
        }
        for (Map.Entry<TargetKey, MilestoneRecord> entry : payload.records().entrySet()) {
            TargetKey key = entry.getKey();
            MilestoneRecord record = entry.getValue();
            statuses.add(ItemTooltipLogic.Status.recorded(key.itemId(), key.count(), record.playTicks()));
            if (key.isTag()) {
                tagIds.add(key.itemId());
            }
        }

        Map<String, List<ItemTooltipLogic.Status>> rebuilt =
                ItemTooltipLogic.buildIndex(statuses, TagTargets.index());
        Map<String, Component> names = new LinkedHashMap<>();
        for (String tagId : tagIds) {
            names.put(tagId, TagTargets.displayName(tagId));
        }
        tooltipIndex = rebuilt;
        tagTooltipNames = names;
    }

    /**
     * F42: builds the gray tooltip lines for {@code itemId} from the precomputed index only.
     * The recorded line uses the F48 client-side Component playtime format.
     */
    public static List<Component> tooltipLines(String itemId) {
        ItemTooltipLogic.Lines lines = ItemTooltipLogic.linesForItem(tooltipIndex, itemId);
        if (lines.isEmpty()) {
            return List.of();
        }
        List<Component> result = new ArrayList<>(lines.shown().size() + 1);
        Map<String, Component> names = tagTooltipNames;
        for (ItemTooltipLogic.Status status : lines.shown()) {
            String suffix = ItemTooltipLogic.countSuffix(status.count());
            Component line = status.recorded()
                    ? Component.translatable(TOOLTIP_RECORDED_KEY, suffix,
                            Playtime.formatComponent(status.playTicks(), Playtime.CLIENT))
                    : Component.translatable(TOOLTIP_TARGET_KEY, suffix);
            if (status.isTag()) {
                Component tagName = names.get(status.itemId());
                if (tagName != null) {
                    line = Component.empty().append(tagName)
                            .append(Component.literal(": "))
                            .append(line);
                }
            }
            result.add(line.copy().withStyle(ChatFormatting.GRAY));
        }
        if (lines.remaining() > 0) {
            result.add(Component.translatable(TOOLTIP_MORE_KEY, lines.remaining())
                    .withStyle(ChatFormatting.GRAY));
        }
        return result;
    }

    public static void onSyncReceived(SyncMilestonesPayload payload) {
        rebuildTooltipIndex(payload);
        Minecraft mc = Minecraft.getInstance();
        Screen current = mc.screen;

        if (current instanceof MilestoneScreen screen) {
            screen.updateData(payload);
            return;
        }
        if (current instanceof PresetListScreen presetList) {
            presetList.updateData(payload);
            return;
        }
        if (current instanceof SavePresetScreen savePreset) {
            savePreset.updateParentData(payload);
            return;
        }
        if (current instanceof ItemPickerScreen picker) {
            picker.updateData(payload);
            return;
        }
        if (current instanceof ConfirmResetScreen confirm) {
            confirm.updateParentData(payload);
            return;
        }
        // F41: show the server's reply to the hovered-item key in the action bar, but only
        // for the first feedback-carrying sync within two seconds of sending the action.
        if (payload.feedback() != null
                && ADD_TARGET_FEEDBACK.consume(System.currentTimeMillis(), true)
                && mc.player != null) {
            mc.player.displayClientMessage(payload.feedback(), true);
        }
        // Only open on an empty screen: the player may have opened another screen
        // (inventory, a map, ...) while waiting for the server's answer.
        if (payload.openScreen() && current == null) {
            mc.setScreen(new MilestoneScreen(payload));
        }
    }
}
