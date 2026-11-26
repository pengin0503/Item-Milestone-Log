package com.pengin0503.itemmilestonelog.network;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncMilestonesPayloadTest {
    private static final int MAX_LIST_ENTRIES = 4096;
    private static final int MAX_STRING_LEN = 256;

    private static SyncMilestonesPayload roundTrip(SyncMilestonesPayload payload) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            SyncMilestonesPayload.encode(payload, buf);
            SyncMilestonesPayload decoded = SyncMilestonesPayload.decode(buf);
            assertEquals(0, buf.readableBytes(), "decode should consume the whole payload");
            return decoded;
        } finally {
            buf.release();
        }
    }

    private static void decodeExpecting(Class<? extends Throwable> expected, Consumer<FriendlyByteBuf> writer) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            writer.accept(buf);
            assertThrows(expected, () -> SyncMilestonesPayload.decode(buf));
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
        String name = "n".repeat(MAX_STRING_LEN);
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

    @Test
    void capsListsAndMapsAtMaxEntriesOnEncode() {
        int oversized = MAX_LIST_ENTRIES + 25;
        List<TargetEntry> targets = new ArrayList<>(oversized);
        Map<TargetKey, MilestoneRecord> records = new LinkedHashMap<>(oversized);
        Map<TargetKey, Integer> progress = new LinkedHashMap<>(oversized);
        List<PresetEntry> presets = new ArrayList<>(oversized);
        for (int i = 0; i < oversized; i++) {
            String itemId = "minecraft:item_" + i;
            targets.add(new TargetEntry(itemId, 1, true, false));
            records.put(new TargetKey(itemId, 1), new MilestoneRecord(i, i, i * 1000L));
            progress.put(new TargetKey(itemId, 10), i);
            presets.add(new PresetEntry("preset_" + i, "Preset " + i, 1, false));
        }

        SyncMilestonesPayload payload = new SyncMilestonesPayload(
                targets, records, progress, 1234, presets, false, false, null);
        SyncMilestonesPayload decoded = roundTrip(payload);

        assertEquals(MAX_LIST_ENTRIES, decoded.targets().size());
        assertEquals(MAX_LIST_ENTRIES, decoded.records().size());
        assertEquals(MAX_LIST_ENTRIES, decoded.progress().size());
        assertEquals(MAX_LIST_ENTRIES, decoded.presets().size());
        assertEquals("minecraft:item_0", decoded.targets().get(0).itemId());
        assertEquals("minecraft:item_" + (MAX_LIST_ENTRIES - 1), decoded.targets().get(MAX_LIST_ENTRIES - 1).itemId());
        assertEquals("preset_0", decoded.presets().get(0).id());
        assertEquals("preset_" + (MAX_LIST_ENTRIES - 1), decoded.presets().get(MAX_LIST_ENTRIES - 1).id());
    }

    @Test
    void rejectsOutOfRangeCountsOnDecode() {
        // Targets count > 4096 and < 0
        decodeExpecting(IllegalArgumentException.class, buf -> buf.writeVarInt(MAX_LIST_ENTRIES + 1));
        decodeExpecting(IllegalArgumentException.class, buf -> buf.writeVarInt(-1));

        // Records count > 4096 and < 0
        decodeExpecting(IllegalArgumentException.class, buf -> {
            buf.writeVarInt(0); // targets
            buf.writeVarInt(MAX_LIST_ENTRIES + 1);
        });
        decodeExpecting(IllegalArgumentException.class, buf -> {
            buf.writeVarInt(0); // targets
            buf.writeVarInt(-1);
        });

        // Progress count > 4096 and < 0
        decodeExpecting(IllegalArgumentException.class, buf -> {
            buf.writeVarInt(0); // targets
            buf.writeVarInt(0); // records
            buf.writeVarInt(MAX_LIST_ENTRIES + 1);
        });
        decodeExpecting(IllegalArgumentException.class, buf -> {
            buf.writeVarInt(0); // targets
            buf.writeVarInt(0); // records
            buf.writeVarInt(-1);
        });

        // Presets count > 4096 and < 0
        decodeExpecting(IllegalArgumentException.class, buf -> {
            buf.writeVarInt(0); // targets
            buf.writeVarInt(0); // records
            buf.writeVarInt(0); // progress
            buf.writeVarInt(0); // playtime
            buf.writeVarInt(MAX_LIST_ENTRIES + 1);
        });
        decodeExpecting(IllegalArgumentException.class, buf -> {
            buf.writeVarInt(0); // targets
            buf.writeVarInt(0); // records
            buf.writeVarInt(0); // progress
            buf.writeVarInt(0); // playtime
            buf.writeVarInt(-1);
        });
    }

    @Test
    void rejectsOverlongStringsOnEncodeAndDecode() {
        String tooLong = "a".repeat(MAX_STRING_LEN + 1);

        // Encode rejects > 256 char preset displayName
        FriendlyByteBuf encodeBuf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            assertThrows(EncoderException.class, () -> SyncMilestonesPayload.encode(sample(tooLong), encodeBuf));
        } finally {
            encodeBuf.release();
        }

        // Null preset displayName encodes as empty string
        SyncMilestonesPayload nullDisplayName = new SyncMilestonesPayload(
                List.of(), Map.of(), Map.of(), 0,
                List.of(new PresetEntry("custom", null, 2, false)),
                false, false, null);
        assertEquals("", roundTrip(nullDisplayName).presets().get(0).displayName());

        // Decode rejects > 256 char string in target itemId
        decodeExpecting(DecoderException.class, buf -> {
            buf.writeVarInt(1);
            buf.writeUtf(tooLong);
        });

        // Decode rejects > 256 char string in record itemId
        decodeExpecting(DecoderException.class, buf -> {
            buf.writeVarInt(0);
            buf.writeVarInt(1);
            buf.writeUtf(tooLong);
        });

        // Decode rejects > 256 char string in progress itemId
        decodeExpecting(DecoderException.class, buf -> {
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeVarInt(1);
            buf.writeUtf(tooLong);
        });

        // Decode rejects > 256 char string in preset id
        decodeExpecting(DecoderException.class, buf -> {
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeVarInt(1);
            buf.writeUtf(tooLong);
        });

        // Decode rejects > 256 char string in preset displayName
        decodeExpecting(DecoderException.class, buf -> {
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeVarInt(0);
            buf.writeVarInt(1);
            buf.writeUtf("valid_id");
            buf.writeUtf(tooLong);
        });
    }

    @Test
    void filtersInvalidEntriesAndClampsNegativeValuesOnDecodeAndEncode() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            // 4 targets: 1 valid, 3 invalid
            buf.writeVarInt(4);
            buf.writeUtf("minecraft:diamond");
            buf.writeVarInt(1);
            buf.writeBoolean(true);
            buf.writeBoolean(false);
            buf.writeUtf("");
            buf.writeVarInt(1);
            buf.writeBoolean(true);
            buf.writeBoolean(false);
            buf.writeUtf("minecraft:gold_ingot");
            buf.writeVarInt(0);
            buf.writeBoolean(true);
            buf.writeBoolean(false);
            buf.writeUtf("minecraft:emerald");
            buf.writeVarInt(TargetKey.MAX_COUNT + 1);
            buf.writeBoolean(true);
            buf.writeBoolean(false);

            // 4 records: 1 valid, 3 invalid (negative ticks, invalid itemId, invalid count)
            buf.writeVarInt(4);
            buf.writeUtf("minecraft:diamond");
            buf.writeVarInt(1);
            buf.writeVarInt(50);
            buf.writeLong(3L);
            buf.writeLong(1_700_000_000_000L);
            buf.writeUtf("minecraft:iron_ingot");
            buf.writeVarInt(1);
            buf.writeVarInt(-1);
            buf.writeLong(1L);
            buf.writeLong(1L);
            buf.writeUtf("bad id");
            buf.writeVarInt(1);
            buf.writeVarInt(10);
            buf.writeLong(1L);
            buf.writeLong(1L);
            buf.writeUtf("minecraft:coal");
            buf.writeVarInt(0);
            buf.writeVarInt(10);
            buf.writeLong(1L);
            buf.writeLong(1L);

            // 4 progress entries: 1 valid, 3 invalid (negative current, empty id, invalid count)
            buf.writeVarInt(4);
            buf.writeUtf("minecraft:diamond");
            buf.writeVarInt(10);
            buf.writeVarInt(4);
            buf.writeUtf("minecraft:iron_ingot");
            buf.writeVarInt(10);
            buf.writeVarInt(-5);
            buf.writeUtf("");
            buf.writeVarInt(10);
            buf.writeVarInt(1);
            buf.writeUtf("minecraft:gold_ingot");
            buf.writeVarInt(0);
            buf.writeVarInt(1);

            // Negative playtime -> clamped to 0
            buf.writeVarInt(-100);

            // Preset with negative itemCount -> clamped to 0
            buf.writeVarInt(1);
            buf.writeUtf("custom");
            buf.writeUtf("Custom");
            buf.writeVarInt(-3);
            buf.writeBoolean(false);

            buf.writeBoolean(false); // canManagePresets
            buf.writeBoolean(true);  // openScreen
            buf.writeBoolean(false); // hasFeedback

            SyncMilestonesPayload decoded = SyncMilestonesPayload.decode(buf);
            assertEquals(0, buf.readableBytes());
            assertEquals(List.of(new TargetEntry("minecraft:diamond", 1, true, false)), decoded.targets());
            assertEquals(Map.of(TargetKey.of("minecraft:diamond"), new MilestoneRecord(50, 3L, 1_700_000_000_000L)), decoded.records());
            assertEquals(Map.of(TargetKey.of("minecraft:diamond", 10), 4), decoded.progress());
            assertEquals(0, decoded.currentPlaytimeTicks());
            assertEquals(List.of(new PresetEntry("custom", "Custom", 0, false)), decoded.presets());
            assertFalse(decoded.canManagePresets());
            assertTrue(decoded.openScreen());
            assertNull(decoded.feedback());
        } finally {
            buf.release();
        }

        // Encode clamps negative progress values to 0
        SyncMilestonesPayload negProgress = new SyncMilestonesPayload(
                List.of(), Map.of(), Map.of(TargetKey.of("minecraft:diamond", 10), -7),
                0, List.of(), false, false, null);
        assertEquals(Map.of(TargetKey.of("minecraft:diamond", 10), 0), roundTrip(negProgress).progress());
    }

    @Test
    void encodeRejectsOverlongItemAndPresetIdsLikeTheDecodeLimit() {
        String tooLong = "a".repeat(MAX_STRING_LEN + 1);
        TargetKey longKey = new TargetKey(tooLong, 1);

        assertThrows(EncoderException.class, () -> roundTrip(new SyncMilestonesPayload(
                List.of(new TargetEntry(tooLong, 1, true, false)),
                Map.of(), Map.of(), 0, List.of(), false, false, null)));
        assertThrows(EncoderException.class, () -> roundTrip(new SyncMilestonesPayload(
                List.of(), Map.of(longKey, new MilestoneRecord(1, 1L, 1L)),
                Map.of(), 0, List.of(), false, false, null)));
        assertThrows(EncoderException.class, () -> roundTrip(new SyncMilestonesPayload(
                List.of(), Map.of(), Map.of(longKey, 1), 0, List.of(), false, false, null)));
        assertThrows(EncoderException.class, () -> roundTrip(new SyncMilestonesPayload(
                List.of(), Map.of(), Map.of(), 0,
                List.of(new PresetEntry(tooLong, "", 1, false)),
                false, false, null)));
    }

}
