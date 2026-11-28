package com.pengin0503.itemmilestonelog.network;

import com.pengin0503.itemmilestonelog.ItemMilestoneLog;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * F-150-69 (N31): the ONE new optional S2C of step 6, a record/effect trigger for client particles.
 *
 * <p>The channel is registered with the same {@code optional()} registrar as the four existing
 * payloads; servers check {@code hasChannel(TYPE)} before sending and vanilla/old peers never see
 * it. The wire fields are {@code itemId}, {@code count} and a tail-extensible {@code kind} ordinal;
 * {@code kind} is decoded leniently so an unknown future value falls back to {@code RECORDED}.
 * No other step-6 feature may add a channel (D-150-199/200).
 */
public record MilestoneEffectPayload(String itemId, int count, Kind kind) implements CustomPacketPayload {

    /** {@code RECORDED} (0) is unchanged; {@code PRESET_COMPLETE} (1) is F-150-83's tail append. */
    public enum Kind {
        RECORDED,
        PRESET_COMPLETE
    }

    public static final int MAX_ITEM_ID_LENGTH = 256;
    public static final int MAX_COUNT = 1_000_000;

    public static final Type<MilestoneEffectPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(ItemMilestoneLog.MOD_ID, "milestone_effect"));

    public static final StreamCodec<FriendlyByteBuf, MilestoneEffectPayload> STREAM_CODEC =
            StreamCodec.of(MilestoneEffectPayload::encode, MilestoneEffectPayload::decode);

    public MilestoneEffectPayload {
        itemId = itemId == null ? "" : itemId.substring(0, Math.min(itemId.length(), MAX_ITEM_ID_LENGTH));
        count = Math.max(1, Math.min(count, MAX_COUNT));
        kind = kind == null ? Kind.RECORDED : kind;
    }

    private static void encode(FriendlyByteBuf buf, MilestoneEffectPayload payload) {
        buf.writeUtf(payload.itemId, MAX_ITEM_ID_LENGTH);
        buf.writeVarInt(payload.count);
        buf.writeVarInt(payload.kind.ordinal());
    }

    /** Lenient: a truncated or future payload still yields a usable effect (or a safe no-op). */
    private static MilestoneEffectPayload decode(FriendlyByteBuf buf) {
        String itemId = "";
        int count = 1;
        Kind kind = Kind.RECORDED;
        try {
            if (buf.isReadable()) {
                itemId = buf.readUtf(MAX_ITEM_ID_LENGTH);
            }
            if (buf.isReadable()) {
                count = buf.readVarInt();
            }
            if (buf.isReadable()) {
                int ordinal = buf.readVarInt();
                Kind[] kinds = Kind.values();
                if (ordinal >= 0 && ordinal < kinds.length) {
                    kind = kinds[ordinal];
                }
            }
        } catch (RuntimeException ignored) {
            // Truncated or malformed optional S2C: keep the defaults, never disconnect a client.
        }
        return new MilestoneEffectPayload(itemId, count, kind);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
