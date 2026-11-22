package com.pengin0503.itemmilestonelog.network;

import com.pengin0503.itemmilestonelog.ItemMilestoneLog;
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
        Map<String, Integer> records,
        int currentPlaytimeTicks,
        List<String> presetNames,
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
            buf.writeBoolean(entry.personal());
            buf.writeBoolean(entry.defaultTarget());
        }

        buf.writeVarInt(payload.records.size());
        for (Map.Entry<String, Integer> e : payload.records.entrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeVarInt(e.getValue());
        }

        buf.writeVarInt(payload.currentPlaytimeTicks);

        buf.writeVarInt(payload.presetNames.size());
        for (String name : payload.presetNames) {
            buf.writeUtf(name);
        }

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
            boolean personal = buf.readBoolean();
            boolean defaultTarget = buf.readBoolean();
            targets.add(new TargetEntry(itemId, personal, defaultTarget));
        }

        int recordCount = buf.readVarInt();
        Map<String, Integer> records = new LinkedHashMap<>(recordCount);
        for (int i = 0; i < recordCount; i++) {
            String itemId = buf.readUtf();
            int ticks = buf.readVarInt();
            records.put(itemId, ticks);
        }

        int playtime = buf.readVarInt();

        int presetCount = buf.readVarInt();
        List<String> presets = new ArrayList<>(presetCount);
        for (int i = 0; i < presetCount; i++) {
            presets.add(buf.readUtf());
        }

        boolean openScreen = buf.readBoolean();

        Component feedback = null;
        if (buf.readBoolean()) {
            feedback = ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf);
        }

        return new SyncMilestonesPayload(targets, records, playtime, presets, openScreen, feedback);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
