package com.pengin0503.itemmilestonelog.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-150-69 / F-150-83: Forge form of the optional S2C, including its tail-appended kind. */
class MilestoneEffectPayloadTest {
    private static MilestoneEffectPayload roundTrip(MilestoneEffectPayload original) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            MilestoneEffectPayload.encode(original, buf);
            MilestoneEffectPayload decoded = MilestoneEffectPayload.decode(buf);
            assertEquals(0, buf.readableBytes());
            return decoded;
        } finally {
            buf.release();
        }
    }

    @Test
    void typeIdAndTailAppendedKindOrdinalsAreFixed() {
        assertEquals("itemmilestonelog:milestone_effect", MilestoneEffectPayload.TYPE.toString());
        assertEquals(2, MilestoneEffectPayload.Kind.values().length);
        assertEquals(0, MilestoneEffectPayload.Kind.RECORDED.ordinal());
        assertEquals(1, MilestoneEffectPayload.Kind.PRESET_COMPLETE.ordinal());
        assertEquals(MilestoneEffectPayload.Kind.PRESET_COMPLETE,
                MilestoneEffectPayload.Kind.values()[1]);
    }

    @Test
    void roundTripsBothKindsAndClampsFields() {
        MilestoneEffectPayload recorded = new MilestoneEffectPayload("minecraft:diamond", 64,
                MilestoneEffectPayload.Kind.RECORDED);
        assertEquals(recorded, roundTrip(recorded));
        MilestoneEffectPayload preset = new MilestoneEffectPayload("builtin:starter", 1,
                MilestoneEffectPayload.Kind.PRESET_COMPLETE);
        assertEquals(preset, roundTrip(preset));
        assertEquals("", new MilestoneEffectPayload(null, 1, null).itemId());
        assertEquals(MilestoneEffectPayload.MAX_COUNT,
                new MilestoneEffectPayload("x", MilestoneEffectPayload.MAX_COUNT + 1,
                        MilestoneEffectPayload.Kind.RECORDED).count());
    }

    @Test
    void truncatesOverlongItemIdWithoutTouchingTheWireLimit() {
        String longId = "a".repeat(MilestoneEffectPayload.MAX_ITEM_ID_LENGTH + 20);
        MilestoneEffectPayload payload = new MilestoneEffectPayload(longId, 2,
                MilestoneEffectPayload.Kind.RECORDED);
        assertEquals(MilestoneEffectPayload.MAX_ITEM_ID_LENGTH, payload.itemId().length());
        assertEquals(payload, roundTrip(payload));
    }

    @Test
    void missingFieldsAndUnknownKindFallBackToSafeDefaults() {
        FriendlyByteBuf empty = new FriendlyByteBuf(Unpooled.buffer());
        try {
            MilestoneEffectPayload decoded = MilestoneEffectPayload.decode(empty);
            assertEquals("", decoded.itemId());
            assertEquals(1, decoded.count());
            assertEquals(MilestoneEffectPayload.Kind.RECORDED, decoded.kind());
        } finally {
            empty.release();
        }
        FriendlyByteBuf future = new FriendlyByteBuf(Unpooled.buffer());
        try {
            future.writeUtf("minecraft:diamond", MilestoneEffectPayload.MAX_ITEM_ID_LENGTH);
            future.writeVarInt(1);
            future.writeVarInt(2); // a future kind unknown to this client
            assertEquals(MilestoneEffectPayload.Kind.RECORDED,
                    MilestoneEffectPayload.decode(future).kind());
        } finally {
            future.release();
        }
    }

    @Test
    void oldClientReadingKindZeroStillDecodesTheRecordedWire() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeUtf("minecraft:diamond", MilestoneEffectPayload.MAX_ITEM_ID_LENGTH);
            buf.writeVarInt(1);
            buf.writeVarInt(MilestoneEffectPayload.Kind.RECORDED.ordinal());
            MilestoneEffectPayload decoded = MilestoneEffectPayload.decode(buf);
            assertTrue(decoded.itemId().endsWith("diamond"));
            assertEquals(MilestoneEffectPayload.Kind.RECORDED, decoded.kind());
        } finally {
            buf.release();
        }
    }
}
