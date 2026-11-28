package com.pengin0503.itemmilestonelog.network;

import com.pengin0503.itemmilestonelog.ItemMilestoneLog;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * F-150-69 (N31): Forge 1.20.1 form of the one new optional S2C record/effect trigger.
 *
 * <p>Registered as id 4 on the existing {@code acceptMissingOr} channel; the server gates it with
 * {@code ModNetworking.isRemotePresent(player)} so vanilla peers are unaffected. The wire fields
 * and {@link Kind} ordinals match the NeoForge trees exactly.
 */
public record MilestoneEffectPayload(String itemId, int count, Kind kind) {

    /** {@code RECORDED} (0) is unchanged; {@code PRESET_COMPLETE} (1) is F-150-83's tail append. */
    public enum Kind {
        RECORDED,
        PRESET_COMPLETE
    }

    public static final int MAX_ITEM_ID_LENGTH = 256;
    public static final int MAX_COUNT = 1_000_000;

    public static final ResourceLocation TYPE =
            new ResourceLocation(ItemMilestoneLog.MOD_ID, "milestone_effect");

    public MilestoneEffectPayload {
        itemId = itemId == null ? "" : itemId.substring(0, Math.min(itemId.length(), MAX_ITEM_ID_LENGTH));
        count = Math.max(1, Math.min(count, MAX_COUNT));
        kind = kind == null ? Kind.RECORDED : kind;
    }

    public static void encode(MilestoneEffectPayload payload, FriendlyByteBuf buf) {
        buf.writeUtf(payload.itemId, MAX_ITEM_ID_LENGTH);
        buf.writeVarInt(payload.count);
        buf.writeVarInt(payload.kind.ordinal());
    }

    /** Lenient: a truncated or future payload still yields a usable effect (or a safe no-op). */
    public static MilestoneEffectPayload decode(FriendlyByteBuf buf) {
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

    public static void handle(MilestoneEffectPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            if (!FMLEnvironment.dist.isClient()) {
                return;
            }
            ClientNetworkHandlers.handleEffect(payload);
        });
        context.setPacketHandled(true);
    }
}
