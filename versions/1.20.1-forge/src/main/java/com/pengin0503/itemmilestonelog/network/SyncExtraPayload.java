package com.pengin0503.itemmilestonelog.network;

import com.pengin0503.itemmilestonelog.ItemMilestoneLog;
import com.pengin0503.itemmilestonelog.Presets;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.compat.McCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Optional S2C payload (F25) carrying extensible extra GUI metadata in a single NBT compound so
 * future additions do not require new payload types or protocol version bumps.
 *
 * <p>The compound is sent as one NBT tag; the tag layout and per-list caps are unchanged from
 * the 1.21.1 payload.
 */
public record SyncExtraPayload(
        Set<TargetKey> worldFirsts,
        Set<String> readOnlyPresets
) {
    public static final String KEY_WORLD_FIRSTS = "worldFirsts";
    public static final String KEY_READ_ONLY_PRESETS = "readOnlyPresets";
    public static final int MAX_LIST_ENTRIES = 4096;

    public static final SyncExtraPayload EMPTY = new SyncExtraPayload(Set.of(), Set.of());

    public static final ResourceLocation TYPE =
            new ResourceLocation(ItemMilestoneLog.MOD_ID, "sync_extra");

    public SyncExtraPayload {
        if (worldFirsts == null || worldFirsts.isEmpty()) {
            worldFirsts = Set.of();
        } else {
            Set<TargetKey> copy = new LinkedHashSet<>(Math.min(worldFirsts.size(), MAX_LIST_ENTRIES));
            for (TargetKey key : worldFirsts) {
                if (key != null) {
                    copy.add(key);
                }
            }
            worldFirsts = copy.isEmpty() ? Set.of() : Collections.unmodifiableSet(copy);
        }
        if (readOnlyPresets == null || readOnlyPresets.isEmpty()) {
            readOnlyPresets = Set.of();
        } else {
            Set<String> copy = new LinkedHashSet<>(Math.min(readOnlyPresets.size(), MAX_LIST_ENTRIES));
            for (String id : readOnlyPresets) {
                if (id != null) {
                    copy.add(id);
                }
            }
            readOnlyPresets = copy.isEmpty() ? Set.of() : Collections.unmodifiableSet(copy);
        }
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();

        ListTag wfList = new ListTag();
        int wfCount = 0;
        for (TargetKey key : worldFirsts) {
            if (wfCount++ >= MAX_LIST_ENTRIES) {
                break;
            }
            wfList.add(StringTag.valueOf(key.toEncodedString()));
        }
        tag.put(KEY_WORLD_FIRSTS, wfList);

        ListTag roList = new ListTag();
        int roCount = 0;
        for (String presetId : readOnlyPresets) {
            if (roCount++ >= MAX_LIST_ENTRIES) {
                break;
            }
            roList.add(StringTag.valueOf(presetId));
        }
        tag.put(KEY_READ_ONLY_PRESETS, roList);

        return tag;
    }

    public static SyncExtraPayload fromTag(@Nullable CompoundTag tag) {
        if (tag == null) {
            return EMPTY;
        }
        Set<TargetKey> worldFirsts = new LinkedHashSet<>();
        if (McCompat.nbt().containsTyped(tag, KEY_WORLD_FIRSTS, Tag.TAG_LIST)) {
            ListTag list = McCompat.nbt().getStringList(tag, KEY_WORLD_FIRSTS);
            int limit = Math.min(list.size(), MAX_LIST_ENTRIES);
            for (int i = 0; i < limit; i++) {
                TargetKey parsed = TargetKey.tryParseEncoded(McCompat.nbt().listString(list, i));
                if (parsed != null) {
                    worldFirsts.add(parsed);
                }
            }
        }

        Set<String> readOnlyPresets = new LinkedHashSet<>();
        if (McCompat.nbt().containsTyped(tag, KEY_READ_ONLY_PRESETS, Tag.TAG_LIST)) {
            ListTag list = McCompat.nbt().getStringList(tag, KEY_READ_ONLY_PRESETS);
            int limit = Math.min(list.size(), MAX_LIST_ENTRIES);
            for (int i = 0; i < limit; i++) {
                String presetId = McCompat.nbt().listString(list, i);
                if (Presets.isValidId(presetId)) {
                    readOnlyPresets.add(presetId);
                }
            }
        }

        if (worldFirsts.isEmpty() && readOnlyPresets.isEmpty()) {
            return EMPTY;
        }
        return new SyncExtraPayload(worldFirsts, readOnlyPresets);
    }

    public static void encode(SyncExtraPayload payload, FriendlyByteBuf buf) {
        buf.writeNbt(payload.toTag());
    }

    public static SyncExtraPayload decode(FriendlyByteBuf buf) {
        return fromTag(buf.readNbt());
    }

    public static void handle(SyncExtraPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            if (!FMLEnvironment.dist.isClient()) {
                return;
            }
            ItemMilestoneLog.LOGGER.debug(
                    "[ItemMilestoneLog] Received SyncExtraPayload (worldFirsts={}, readOnlyPresets={})",
                    payload.worldFirsts().size(), payload.readOnlyPresets().size());
            ClientNetworkHandlers.handleSyncExtra(payload);
        });
        context.setPacketHandled(true);
    }
}
