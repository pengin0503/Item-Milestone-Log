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

    private static SyncMilestonesPayload decode(RegistryFriendlyByteBuf buf) {
        int targetCount = buf.readVarInt();
        List<TargetEntry> targets = new ArrayList<>(targetCount);
        for (int i = 0; i < targetCount; i++) {
            String itemId = buf.readUtf();
            int count = buf.readVarInt();
            boolean personal = buf.readBoolean();
            boolean defaultTarget = buf.readBoolean();
            if (TargetKey.isValidCount(count)) {
                targets.add(new TargetEntry(itemId, count, personal, defaultTarget));
            }
        }

        int recordCount = buf.readVarInt();
        Map<TargetKey, MilestoneRecord> records = new LinkedHashMap<>(recordCount);
        for (int i = 0; i < recordCount; i++) {
            String itemId = buf.readUtf();
            int count = buf.readVarInt();
            int ticks = buf.readVarInt();
            long day = buf.readLong();
            long epoch = buf.readLong();
            if (TargetKey.isValidCount(count)) {
                records.put(new TargetKey(itemId, count), new MilestoneRecord(ticks, day, epoch));
            }
        }

        int playtime = buf.readVarInt();

        int presetCount = buf.readVarInt();
        List<PresetEntry> presets = new ArrayList<>(presetCount);
        for (int i = 0; i < presetCount; i++) {
            String id = buf.readUtf();
            String displayName = buf.readUtf();
            int itemCount = buf.readVarInt();
            boolean builtin = buf.readBoolean();
            presets.add(new PresetEntry(id, displayName, itemCount, builtin));
        }

        boolean canManagePresets = buf.readBoolean();
        boolean openScreen = buf.readBoolean();

        Component feedback = null;
        if (buf.readBoolean()) {
            feedback = ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf);
        }

        return new SyncMilestonesPayload(targets, records, playtime, presets, canManagePresets, openScreen, feedback);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
