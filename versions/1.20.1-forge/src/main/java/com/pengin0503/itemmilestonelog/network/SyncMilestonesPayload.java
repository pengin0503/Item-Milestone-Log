package com.pengin0503.itemmilestonelog.network;

import com.pengin0503.itemmilestonelog.ItemMilestoneLog;
import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * S2C payload carrying the player's full milestone state so the client GUI can open or refresh.
 *
 * <p>The manual {@link FriendlyByteBuf} encoding mirrors the 1.21.1 {@code StreamCodec} field
 * order, limits and validation exactly.
 */
public record SyncMilestonesPayload(
        List<TargetEntry> targets,
        Map<TargetKey, MilestoneRecord> records,
        Map<TargetKey, Integer> progress,
        int currentPlaytimeTicks,
        List<PresetEntry> presets,
        boolean canManagePresets,
        boolean openScreen,
        @Nullable Component feedback
) {
    public static final ResourceLocation TYPE =
            new ResourceLocation(ItemMilestoneLog.MOD_ID, "sync_milestones");

    private static final int MAX_LIST_ENTRIES = 4096;
    private static final int MAX_STRING_LEN = 256;

    public static void encode(SyncMilestonesPayload payload, FriendlyByteBuf buf) {
        // Mirror the decode-side MAX_LIST_ENTRIES cap so we never write a list the client would reject.
        List<TargetEntry> targets = payload.targets.subList(0, Math.min(payload.targets.size(), MAX_LIST_ENTRIES));
        buf.writeVarInt(targets.size());
        for (TargetEntry entry : targets) {
            buf.writeUtf(entry.itemId(), MAX_STRING_LEN);
            buf.writeVarInt(entry.count());
            buf.writeBoolean(entry.personal());
            buf.writeBoolean(entry.defaultTarget());
        }

        buf.writeVarInt(Math.min(payload.records.size(), MAX_LIST_ENTRIES));
        int recordsWritten = 0;
        for (Map.Entry<TargetKey, MilestoneRecord> e : payload.records.entrySet()) {
            if (recordsWritten++ >= MAX_LIST_ENTRIES) {
                break;
            }
            TargetKey key = e.getKey();
            MilestoneRecord rec = e.getValue();
            buf.writeUtf(key.itemId(), MAX_STRING_LEN);
            buf.writeVarInt(key.count());
            buf.writeVarInt(rec.playTicks());
            buf.writeLong(rec.day());
            buf.writeLong(rec.epochMillis());
        }

        buf.writeVarInt(Math.min(payload.progress.size(), MAX_LIST_ENTRIES));
        int progressWritten = 0;
        for (Map.Entry<TargetKey, Integer> e : payload.progress.entrySet()) {
            if (progressWritten++ >= MAX_LIST_ENTRIES) {
                break;
            }
            TargetKey key = e.getKey();
            buf.writeUtf(key.itemId(), MAX_STRING_LEN);
            buf.writeVarInt(key.count());
            buf.writeVarInt(Math.max(0, e.getValue()));
        }

        buf.writeVarInt(payload.currentPlaytimeTicks);

        List<PresetEntry> presets = payload.presets.subList(0, Math.min(payload.presets.size(), MAX_LIST_ENTRIES));
        buf.writeVarInt(presets.size());
        for (PresetEntry preset : presets) {
            buf.writeUtf(preset.id(), MAX_STRING_LEN);
            buf.writeUtf(preset.displayName() == null ? "" : preset.displayName(), MAX_STRING_LEN);
            buf.writeVarInt(preset.itemCount());
            buf.writeBoolean(preset.builtin());
        }

        buf.writeBoolean(payload.canManagePresets);
        buf.writeBoolean(payload.openScreen);

        boolean hasFeedback = payload.feedback != null;
        buf.writeBoolean(hasFeedback);
        if (hasFeedback) {
            buf.writeComponent(payload.feedback);
        }
    }

    private static int readBoundedCount(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_LIST_ENTRIES) {
            throw new IllegalArgumentException("Payload entry count out of range: " + count);
        }
        return count;
    }

    public static SyncMilestonesPayload decode(FriendlyByteBuf buf) {
        int targetCount = readBoundedCount(buf);
        List<TargetEntry> targets = new ArrayList<>(Math.min(targetCount, 256));
        for (int i = 0; i < targetCount; i++) {
            String itemId = buf.readUtf(MAX_STRING_LEN);
            int count = buf.readVarInt();
            boolean personal = buf.readBoolean();
            boolean defaultTarget = buf.readBoolean();
            if (TargetKey.isValidItemId(itemId) && TargetKey.isValidCount(count)) {
                targets.add(new TargetEntry(itemId, count, personal, defaultTarget));
            }
        }

        int recordCount = readBoundedCount(buf);
        Map<TargetKey, MilestoneRecord> records = new LinkedHashMap<>(Math.min(recordCount, 256));
        for (int i = 0; i < recordCount; i++) {
            String itemId = buf.readUtf(MAX_STRING_LEN);
            int count = buf.readVarInt();
            int ticks = buf.readVarInt();
            long day = buf.readLong();
            long epoch = buf.readLong();
            if (TargetKey.isValidItemId(itemId) && TargetKey.isValidCount(count) && ticks >= 0) {
                records.put(new TargetKey(itemId, count), new MilestoneRecord(ticks, day, epoch));
            }
        }

        int progressCount = readBoundedCount(buf);
        Map<TargetKey, Integer> progress = new LinkedHashMap<>(Math.min(progressCount, 256));
        for (int i = 0; i < progressCount; i++) {
            String itemId = buf.readUtf(MAX_STRING_LEN);
            int count = buf.readVarInt();
            int current = buf.readVarInt();
            if (TargetKey.isValidItemId(itemId) && TargetKey.isValidCount(count) && current >= 0) {
                progress.put(new TargetKey(itemId, count), current);
            }
        }

        int playtime = Math.max(0, buf.readVarInt());

        int presetCount = readBoundedCount(buf);
        List<PresetEntry> presets = new ArrayList<>(Math.min(presetCount, 64));
        for (int i = 0; i < presetCount; i++) {
            String id = buf.readUtf(MAX_STRING_LEN);
            String displayName = buf.readUtf(MAX_STRING_LEN);
            int itemCount = buf.readVarInt();
            boolean builtin = buf.readBoolean();
            presets.add(new PresetEntry(id, displayName, Math.max(0, itemCount), builtin));
        }

        boolean canManagePresets = buf.readBoolean();
        boolean openScreen = buf.readBoolean();

        Component feedback = null;
        if (buf.readBoolean()) {
            feedback = buf.readComponent();
        }

        return new SyncMilestonesPayload(targets, records, progress, playtime, presets, canManagePresets, openScreen, feedback);
    }

    public static void handle(SyncMilestonesPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            if (!FMLEnvironment.dist.isClient()) {
                return;
            }
            ItemMilestoneLog.LOGGER.debug(
                    "[ItemMilestoneLog] Received SyncMilestonesPayload (targets={}, records={}, presets={}, openScreen={})",
                    payload.targets().size(), payload.records().size(), payload.presets().size(), payload.openScreen());
            ClientNetworkHandlers.handleSync(payload);
        });
        context.setPacketHandled(true);
    }
}
