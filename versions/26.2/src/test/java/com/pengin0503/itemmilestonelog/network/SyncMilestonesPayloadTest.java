package com.pengin0503.itemmilestonelog.network;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SyncMilestonesPayloadTest {
    private static SyncMilestonesPayload roundTrip(SyncMilestonesPayload payload) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            SyncMilestonesPayload.STREAM_CODEC.encode(buf, payload);
            SyncMilestonesPayload decoded = SyncMilestonesPayload.STREAM_CODEC.decode(buf);
            assertEquals(0, buf.readableBytes(), "decode should consume the whole payload");
            return decoded;
        } finally {
            buf.release();
        }
    }

    private static SyncMilestonesPayload sample(String presetName) {
        List<TargetEntry> targets = List.of(
                new TargetEntry("minecraft:diamond", 1, true, false),
                new TargetEntry("minecraft:diamond", 64, true, true),
                new TargetEntry("minecraft:iron_ingot", 128, false, true));
        Map<TargetKey, MilestoneRecord> records = new LinkedHashMap<>();
        records.put(new TargetKey("minecraft:diamond", 1), new MilestoneRecord(1200, 5L, 1_700_000_000_000L));
        records.put(new TargetKey("minecraft:iron_ingot", 128), new MilestoneRecord(0, 0L, 0L));
        Map<TargetKey, Integer> progress = new LinkedHashMap<>();
        progress.put(new TargetKey("minecraft:diamond", 64), 17);
        progress.put(new TargetKey("minecraft:iron_ingot", 128), 128);
        List<PresetEntry> presets = List.of(
                new PresetEntry("starter", "", 12, true),
                new PresetEntry("custom_one", presetName, 3, false));
        return new SyncMilestonesPayload(targets, records, progress, 987_654, presets, true, false, null);
    }

    @Test
    void roundTripsRepresentativeData() {
        SyncMilestonesPayload original = sample("My Preset");
        SyncMilestonesPayload decoded = roundTrip(original);
        assertEquals(original.targets(), decoded.targets());
        assertEquals(original.records(), decoded.records());
        assertEquals(original.progress(), decoded.progress());
        assertEquals(original.currentPlaytimeTicks(), decoded.currentPlaytimeTicks());
        assertEquals(original.presets(), decoded.presets());
        assertEquals(original.canManagePresets(), decoded.canManagePresets());
        assertEquals(original.openScreen(), decoded.openScreen());
        assertNull(decoded.feedback());
    }

    @Test
    void roundTripsAMaximumLengthPresetDisplayName() {
        String name = "n".repeat(256);
        SyncMilestonesPayload decoded = roundTrip(sample(name));
        assertEquals(name, decoded.presets().get(1).displayName());
    }

    @Test
    void roundTripsEmptyPayload() {
        SyncMilestonesPayload empty = new SyncMilestonesPayload(
                List.of(), Map.of(), Map.of(), 0, List.of(), false, true, null);
        SyncMilestonesPayload decoded = roundTrip(empty);
        assertEquals(List.of(), decoded.targets());
        assertEquals(Map.of(), decoded.records());
        assertEquals(Map.of(), decoded.progress());
        assertEquals(true, decoded.openScreen());
    }
}
