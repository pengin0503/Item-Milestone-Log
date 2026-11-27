package com.pengin0503.itemmilestonelog.network;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MilestoneActionPayloadTest {
    private static final int MAX_ARGUMENT_LENGTH = 256;

    private static MilestoneActionPayload roundTrip(MilestoneActionPayload payload) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            MilestoneActionPayload.encode(payload, buf);
            MilestoneActionPayload decoded = MilestoneActionPayload.decode(buf);
            assertEquals(0, buf.readableBytes(), "decode should consume the whole payload");
            return decoded;
        } finally {
            buf.release();
        }
    }

    @Test
    void roundTripsEveryActionWithArgumentAndCount() {
        for (MilestoneActionPayload.Action action : MilestoneActionPayload.Action.values()) {
            MilestoneActionPayload original = new MilestoneActionPayload(action, "minecraft:diamond", action.ordinal() + 1);
            MilestoneActionPayload decoded = roundTrip(original);
            assertEquals(original, decoded);
        }
    }

    @Test
    void twoArgConstructorDefaultsCountToOne() {
        MilestoneActionPayload original = new MilestoneActionPayload(
                MilestoneActionPayload.Action.ADD_TARGET, "minecraft:netherite_ingot");
        assertEquals(1, original.count());
        assertEquals(original, roundTrip(original));
    }

    @Test
    void nullArgumentEncodesAsEmptyString() {
        MilestoneActionPayload original = new MilestoneActionPayload(
                MilestoneActionPayload.Action.RESET_ALL, null, 1);
        MilestoneActionPayload decoded = roundTrip(original);
        assertEquals(MilestoneActionPayload.Action.RESET_ALL, decoded.action());
        assertEquals("", decoded.argument());
        assertEquals(1, decoded.count());
    }

    @Test
    void roundTripsMaximumLengthArgument() {
        String maxArg = "a".repeat(MAX_ARGUMENT_LENGTH);
        MilestoneActionPayload original = new MilestoneActionPayload(
                MilestoneActionPayload.Action.SAVE_PRESET, maxArg, 64);
        assertEquals(original, roundTrip(original));
    }

    @Test
    void rejectsOverlongArgumentOnEncodeAndDecode() {
        String tooLong = "a".repeat(MAX_ARGUMENT_LENGTH + 1);

        FriendlyByteBuf encodeBuf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            MilestoneActionPayload oversized = new MilestoneActionPayload(
                    MilestoneActionPayload.Action.ADD_TARGET, tooLong, 1);
            assertThrows(EncoderException.class, () -> MilestoneActionPayload.encode(oversized, encodeBuf));
        } finally {
            encodeBuf.release();
        }

        FriendlyByteBuf decodeBuf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            decodeBuf.writeEnum(MilestoneActionPayload.Action.ADD_TARGET);
            decodeBuf.writeUtf(tooLong);
            decodeBuf.writeVarInt(1);
            assertThrows(DecoderException.class, () -> MilestoneActionPayload.decode(decodeBuf));
        } finally {
            decodeBuf.release();
        }
    }

    @Test
    void rejectsInvalidActionOrdinalOnDecode() {
        for (int badOrdinal : new int[]{-1, MilestoneActionPayload.Action.values().length}) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buf.writeVarInt(badOrdinal);
                buf.writeUtf("minecraft:diamond");
                buf.writeVarInt(1);
                assertThrows(IndexOutOfBoundsException.class, () -> MilestoneActionPayload.decode(buf));
            } finally {
                buf.release();
            }
        }
    }

    @Test
    void muteActionIsTailAppendedWithoutReorderingExistingOrdinals() {
        MilestoneActionPayload.Action[] values = MilestoneActionPayload.Action.values();
        assertEquals(9, values.length);
        assertEquals(MilestoneActionPayload.Action.ADD_TARGET, values[0]);
        assertEquals(MilestoneActionPayload.Action.DELETE_PRESET, values[7]);
        assertEquals(MilestoneActionPayload.Action.MUTE_NOTIFICATION_SOUND, values[8]);
        assertEquals(8, MilestoneActionPayload.Action.MUTE_NOTIFICATION_SOUND.ordinal());
    }

    @Test
    void oldPayloadBytesStillDecodeAndTheNewTailActionRoundTrips() {
        FriendlyByteBuf old = new FriendlyByteBuf(Unpooled.buffer());
        try {
            old.writeEnum(MilestoneActionPayload.Action.DELETE_PRESET);
            old.writeUtf("minecraft:diamond");
            old.writeVarInt(3);
            assertEquals(new MilestoneActionPayload(
                    MilestoneActionPayload.Action.DELETE_PRESET, "minecraft:diamond", 3),
                    MilestoneActionPayload.decode(old));
            assertEquals(0, old.readableBytes());
        } finally {
            old.release();
        }
        MilestoneActionPayload mute = new MilestoneActionPayload(
                MilestoneActionPayload.Action.MUTE_NOTIFICATION_SOUND, "");
        assertEquals(mute, roundTrip(mute));
        assertEquals(1, mute.count(), "the optional action needs no new payload fields");
    }

}
