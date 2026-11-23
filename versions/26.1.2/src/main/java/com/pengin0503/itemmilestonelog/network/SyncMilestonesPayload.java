package com.pengin0503.itemmilestonelog.network;

import com.pengin0503.itemmilestonelog.ItemMilestoneLog;
import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S2C payload carrying the player's full milestone state so the client GUI can open or refresh.
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
) implements CustomPacketPayload {

    public static final Type<SyncMilestonesPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(ItemMilestoneLog.MOD_ID, "sync_milestones"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncMilestonesPayload> STREAM_CODEC =
            StreamCodec.of(SyncMilestonesPayload::encode, SyncMilestonesPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, SyncMilestonesPayload payload) {
        buf.writeVarInt(payload.targets.size());
        for (TargetEntry entry : payload.targets) {
            buf.writeUtf(entry.itemId());
            buf.writeVarInt(entry.count());
            buf.writeBoolean(entry.personal());
            buf.writeBoolean(entry.defaultTarget());
        }

        buf.writeVarInt(payload.records.size());
        for (Map.Entry<TargetKey, MilestoneRecord> e : payload.records.entrySet()) {
            TargetKey key = e.getKey();
            MilestoneRecord rec = e.getValue();
            buf.writeUtf(key.itemId());
            buf.writeVarInt(key.count());
            buf.writeVarInt(rec.playTicks());
            buf.writeLong(rec.day());
            buf.writeLong(rec.epochMillis());
        }

        buf.writeVarInt(payload.progress.size());
        for (Map.Entry<TargetKey, Integer> e : payload.progress.entrySet()) {
            TargetKey key = e.getKey();
            buf.writeUtf(key.itemId());
            buf.writeVarInt(key.count());
            buf.writeVarInt(Math.max(0, e.getValue()));
        }

        buf.writeVarInt(payload.currentPlaytimeTicks);

        buf.writeVarInt(payload.presets.size());
        for (PresetEntry preset : payload.presets) {
            buf.writeUtf(preset.id());
            buf.writeUtf(preset.displayName() == null ? "" : preset.displayName());
            buf.writeVarInt(preset.itemCount());
            buf.writeBoolean(preset.builtin());
        }

        buf.writeBoolean(payload.canManagePresets);
        buf.writeBoolean(payload.openScreen);

        boolean hasFeedback = payload.feedback != null;
        buf.writeBoolean(hasFeedback);
        if (hasFeedback) {
            ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, payload.feedback);
        }
    }

    private static final int MAX_LIST_ENTRIES = 4096;
    private static final int MAX_STRING_LEN = 256;

    private static int readBoundedCount(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_LIST_ENTRIES) {
            throw new IllegalArgumentException("Payload entry count out of range: " + count);
        }
        return count;
    }

    private static SyncMilestonesPayload decode(RegistryFriendlyByteBuf buf) {
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
            feedback = ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf);
        }

        return new SyncMilestonesPayload(targets, records, progress, playtime, presets, canManagePresets, openScreen, feedback);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
