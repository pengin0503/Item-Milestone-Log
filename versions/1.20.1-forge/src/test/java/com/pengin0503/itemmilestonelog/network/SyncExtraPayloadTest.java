package com.pengin0503.itemmilestonelog.network;

import com.pengin0503.itemmilestonelog.api.TargetKey;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncExtraPayloadTest {
    private static SyncExtraPayload roundTrip(SyncExtraPayload payload) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            SyncExtraPayload.encode(payload, buf);
            SyncExtraPayload decoded = SyncExtraPayload.decode(buf);
            assertEquals(0, buf.readableBytes(), "decode should consume the whole payload");
            return decoded;
        } finally {
            buf.release();
        }
    }

    private static SyncExtraPayload decodeRawCompound(CompoundTag tag) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeNbt(tag);
            SyncExtraPayload decoded = SyncExtraPayload.decode(buf);
            assertEquals(0, buf.readableBytes(), "decode should consume the whole compound");
            return decoded;
        } finally {
            buf.release();
        }
    }

    @Test
    void typeIdMatchesSpec() {
        assertEquals("itemmilestonelog:sync_extra", SyncExtraPayload.TYPE.toString());
    }

    @Test
    void roundTripsCompoundWithItemsQuantitiesTagsAndReadOnlyPresets() {
        Set<TargetKey> worldFirsts = new LinkedHashSet<>(List.of(
                TargetKey.of("minecraft:diamond"),
                TargetKey.of("minecraft:diamond", 64),
                TargetKey.of("#c:ingots/iron"),
                TargetKey.of("#c:ingots/iron", 100)
        ));
        Set<String> readOnlyPresets = new LinkedHashSet<>(List.of("datapack_starter", "pack-2_goals"));

        SyncExtraPayload original = new SyncExtraPayload(worldFirsts, readOnlyPresets);
        CompoundTag tag = original.toTag();
        assertTrue(tag.contains(SyncExtraPayload.KEY_WORLD_FIRSTS));
        assertTrue(tag.contains(SyncExtraPayload.KEY_READ_ONLY_PRESETS));

        SyncExtraPayload decoded = roundTrip(original);
        assertEquals(worldFirsts, decoded.worldFirsts());
        assertEquals(readOnlyPresets, decoded.readOnlyPresets());
    }

    @Test
    void roundTripsEmptyPayloadAndHandlesNullOrEmptyTag() {
        SyncExtraPayload decoded = roundTrip(SyncExtraPayload.EMPTY);
        assertTrue(decoded.worldFirsts().isEmpty());
        assertTrue(decoded.readOnlyPresets().isEmpty());

        SyncExtraPayload fromEmptyTag = decodeRawCompound(new CompoundTag());
        assertTrue(fromEmptyTag.worldFirsts().isEmpty());
        assertTrue(fromEmptyTag.readOnlyPresets().isEmpty());

        SyncExtraPayload fromNull = SyncExtraPayload.fromTag(null);
        assertTrue(fromNull.worldFirsts().isEmpty());
        assertTrue(fromNull.readOnlyPresets().isEmpty());
    }

    @Test
    void ignoresUnknownKeysWrongTypesAndInvalidElements() {
        CompoundTag raw = new CompoundTag();
        raw.putInt("futureVersion", 99);
        raw.putString("futureString", "ignored");
        CompoundTag nested = new CompoundTag();
        nested.putBoolean("flag", true);
        raw.put("futureNested", nested);

        ListTag wfList = new ListTag();
        wfList.add(StringTag.valueOf("minecraft:diamond"));
        wfList.add(StringTag.valueOf(""));                     // invalid: empty
        wfList.add(StringTag.valueOf("bad id"));               // invalid: whitespace
        wfList.add(StringTag.valueOf("minecraft:gold#0"));     // invalid: count 0
        wfList.add(StringTag.valueOf("minecraft:gold#1000001")); // invalid: count > max
        wfList.add(StringTag.valueOf("#"));                    // invalid: bare tag
        wfList.add(StringTag.valueOf("##c:ingots"));           // invalid: double hash
        wfList.add(StringTag.valueOf("#c:x#abc"));             // invalid: non-numeric count
        wfList.add(StringTag.valueOf(":missing_ns"));          // invalid: empty namespace
        wfList.add(StringTag.valueOf("ns:"));                  // invalid: empty path
        wfList.add(StringTag.valueOf("#c:ingots/iron#32"));    // valid tag with count
        raw.put(SyncExtraPayload.KEY_WORLD_FIRSTS, wfList);

        ListTag roList = new ListTag();
        roList.add(StringTag.valueOf("valid_datapack-1"));
        roList.add(StringTag.valueOf(""));                     // invalid: empty
        roList.add(StringTag.valueOf("Bad Uppercase"));        // invalid: uppercase & space
        roList.add(StringTag.valueOf("list"));                 // invalid: reserved
        roList.add(StringTag.valueOf("save"));                 // invalid: reserved
        roList.add(StringTag.valueOf("delete"));               // invalid: reserved
        roList.add(StringTag.valueOf("reload"));               // invalid: reserved
        roList.add(StringTag.valueOf("a".repeat(33)));         // invalid: > 32 chars
        roList.add(StringTag.valueOf("a".repeat(32)));         // valid: exactly 32 chars
        raw.put(SyncExtraPayload.KEY_READ_ONLY_PRESETS, roList);

        SyncExtraPayload decoded = decodeRawCompound(raw);
        assertEquals(Set.of(
                TargetKey.of("minecraft:diamond"),
                TargetKey.of("#c:ingots/iron", 32)
        ), decoded.worldFirsts());
        assertEquals(Set.of("valid_datapack-1", "a".repeat(32)), decoded.readOnlyPresets());

        // Wrong tag types for known keys are ignored without failing
        CompoundTag wrongTypes = new CompoundTag();
        wrongTypes.putString(SyncExtraPayload.KEY_WORLD_FIRSTS, "not_a_list");
        wrongTypes.putInt(SyncExtraPayload.KEY_READ_ONLY_PRESETS, 123);
        SyncExtraPayload decodedWrong = decodeRawCompound(wrongTypes);
        assertTrue(decodedWrong.worldFirsts().isEmpty());
        assertTrue(decodedWrong.readOnlyPresets().isEmpty());

        // Lists containing non-string tags are ignored without failing
        CompoundTag intLists = new CompoundTag();
        ListTag intsA = new ListTag();
        intsA.add(IntTag.valueOf(1));
        intsA.add(IntTag.valueOf(2));
        ListTag intsB = new ListTag();
        intsB.add(IntTag.valueOf(3));
        intLists.put(SyncExtraPayload.KEY_WORLD_FIRSTS, intsA);
        intLists.put(SyncExtraPayload.KEY_READ_ONLY_PRESETS, intsB);
        SyncExtraPayload decodedInts = decodeRawCompound(intLists);
        assertTrue(decodedInts.worldFirsts().isEmpty());
        assertTrue(decodedInts.readOnlyPresets().isEmpty());
    }

    @Test
    void capsListsAt4096OnEncodeAndDecode() {
        int oversized = SyncExtraPayload.MAX_LIST_ENTRIES + 25;
        Set<TargetKey> worldFirsts = new LinkedHashSet<>(oversized);
        Set<String> readOnlyPresets = new LinkedHashSet<>(oversized);
        for (int i = 0; i < oversized; i++) {
            worldFirsts.add(TargetKey.of("minecraft:item_" + i));
            readOnlyPresets.add("preset_" + i);
        }

        // Encode-side truncation via round-trip
        SyncExtraPayload payload = new SyncExtraPayload(worldFirsts, readOnlyPresets);
        SyncExtraPayload decoded = roundTrip(payload);
        assertEquals(SyncExtraPayload.MAX_LIST_ENTRIES, decoded.worldFirsts().size());
        assertEquals(SyncExtraPayload.MAX_LIST_ENTRIES, decoded.readOnlyPresets().size());
        List<TargetKey> decodedWfs = new ArrayList<>(decoded.worldFirsts());
        List<String> decodedRos = new ArrayList<>(decoded.readOnlyPresets());
        assertEquals(TargetKey.of("minecraft:item_0"), decodedWfs.get(0));
        assertEquals(TargetKey.of("minecraft:item_" + (SyncExtraPayload.MAX_LIST_ENTRIES - 1)),
                decodedWfs.get(SyncExtraPayload.MAX_LIST_ENTRIES - 1));
        assertEquals("preset_0", decodedRos.get(0));
        assertEquals("preset_" + (SyncExtraPayload.MAX_LIST_ENTRIES - 1),
                decodedRos.get(SyncExtraPayload.MAX_LIST_ENTRIES - 1));

        // Decode-side truncation directly from an oversized NBT CompoundTag
        CompoundTag raw = new CompoundTag();
        ListTag rawWf = new ListTag();
        ListTag rawRo = new ListTag();
        for (int i = 0; i < oversized; i++) {
            rawWf.add(StringTag.valueOf("minecraft:raw_" + i));
            rawRo.add(StringTag.valueOf("raw_" + i));
        }
        raw.put(SyncExtraPayload.KEY_WORLD_FIRSTS, rawWf);
        raw.put(SyncExtraPayload.KEY_READ_ONLY_PRESETS, rawRo);

        SyncExtraPayload decodedRaw = decodeRawCompound(raw);
        assertEquals(SyncExtraPayload.MAX_LIST_ENTRIES, decodedRaw.worldFirsts().size());
        assertEquals(SyncExtraPayload.MAX_LIST_ENTRIES, decodedRaw.readOnlyPresets().size());
        List<TargetKey> rawDecodedWfs = new ArrayList<>(decodedRaw.worldFirsts());
        List<String> rawDecodedRos = new ArrayList<>(decodedRaw.readOnlyPresets());
        assertEquals(TargetKey.of("minecraft:raw_0"), rawDecodedWfs.get(0));
        assertEquals(TargetKey.of("minecraft:raw_" + (SyncExtraPayload.MAX_LIST_ENTRIES - 1)),
                rawDecodedWfs.get(SyncExtraPayload.MAX_LIST_ENTRIES - 1));
        assertEquals("raw_0", rawDecodedRos.get(0));
        assertEquals("raw_" + (SyncExtraPayload.MAX_LIST_ENTRIES - 1),
                rawDecodedRos.get(SyncExtraPayload.MAX_LIST_ENTRIES - 1));
    }
}
