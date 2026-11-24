package com.pengin0503.itemmilestonelog.client;

import com.pengin0503.itemmilestonelog.AddTargetFeedbackWindow;
import com.pengin0503.itemmilestonelog.ItemTooltipLogic;
import com.pengin0503.itemmilestonelog.Playtime;
import com.pengin0503.itemmilestonelog.TagTargets;
import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.compat.client.ClientCompat;
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
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

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
    }

    /** F42: discards the sync snapshot and tooltip index when the client disconnects. */
    public static void clearTooltipData() {
        latestSync = null;
        tooltipIndex = Map.of();
        tagTooltipNames = Map.of();
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
        if (!connection.hasChannel(RequestSyncPayload.TYPE)) {
            showServerNotInstalled(mc);
            return;
        }
        ClientPacketDistributor.sendToServer(new RequestSyncPayload());
    }

    /**
     * Sends the F41 "add hovered item" action for {@code stack} and opens the feedback window.
     * Does nothing when the server does not have the mod installed, showing the same action
     * bar message as the GUI-open key.
     */
    public static void addHoveredTarget(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            return;
        }
        if (!connection.hasChannel(MilestoneActionPayload.TYPE)) {
            showServerNotInstalled(mc);
            return;
        }
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        ADD_TARGET_FEEDBACK.markSent(System.currentTimeMillis());
        ClientPacketDistributor.sendToServer(new MilestoneActionPayload(
                MilestoneActionPayload.Action.ADD_TARGET, itemId, 1));
    }

    private static void showServerNotInstalled(Minecraft mc) {
        if (mc.player != null) {
            mc.player.sendOverlayMessage(
                    Component.translatable("message.itemmilestonelog.server_not_installed"));
        }
    }

    public static void sendAction(MilestoneActionPayload.Action action, String argument) {
        sendAction(action, argument, 1);
    }

    public static void sendAction(MilestoneActionPayload.Action action, String argument, int count) {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null || !connection.hasChannel(MilestoneActionPayload.TYPE)) {
            return;
        }
        ClientPacketDistributor.sendToServer(new MilestoneActionPayload(action, argument == null ? "" : argument, count));
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
        Screen current = ClientCompat.screens().currentScreen(mc);

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
            mc.player.sendOverlayMessage(payload.feedback());
        }
        if (payload.openScreen() && current == null) {
            ClientCompat.screens().setScreen(mc, new MilestoneScreen(payload));
        }
    }
}
