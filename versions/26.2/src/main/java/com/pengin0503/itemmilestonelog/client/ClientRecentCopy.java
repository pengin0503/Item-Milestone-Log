package com.pengin0503.itemmilestonelog.client;

import com.pengin0503.itemmilestonelog.ClientExportLogic;
import com.pengin0503.itemmilestonelog.RecentRecordSelection;
import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.Playtime;
import com.pengin0503.itemmilestonelog.compat.client.ClipboardAccess;
import com.pengin0503.itemmilestonelog.network.SyncMilestonesPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * F-150-71 (N68): fourth-key action. Copies only the item id and the record time of the newest
 * locally known milestone to the clipboard, as one line from the existing
 * {@code Playtime.formatRecordComponent} path used by MilestoneScreen. No player name, UUID,
 * address, URL or server packet is involved; with no usable record it prints the existing
 * {@code no_recent_record} message.
 */
public final class ClientRecentCopy {
    private ClientRecentCopy() {
    }

    public static void copyRecent() {
        SyncMilestonesPayload sync = ClientMilestoneHandler.currentSync();
        List<RecentRecordSelection.Candidate> candidates = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        List<MilestoneRecord> records = new ArrayList<>();
        if (sync != null) {
            for (Map.Entry<TargetKey, MilestoneRecord> entry : sync.records().entrySet()) {
                MilestoneRecord record = entry.getValue();
                candidates.add(new RecentRecordSelection.Candidate(
                        record.hasEpoch() ? record.epochMillis() : 0L, record.hasEpoch(),
                        record.hasDay() ? record.day() : 0L, record.hasDay()));
                ids.add(entry.getKey().itemId());
                records.add(record);
            }
        }
        int index = RecentRecordSelection.selectIndex(candidates);
        if (index < 0 || Minecraft.getInstance().player == null) {
            notice("message.itemmilestonelog.no_recent_record");
            return;
        }
        MutableComponent time = Playtime.formatRecordComponent(
                records.get(index), ZoneId.systemDefault(), Playtime.CLIENT);
        String line = safeItemId(ids.get(index)) + " " + time.getString();
        if (!ClipboardAccess.setClipboard(line)) {
            notice("message.itemmilestonelog.no_recent_record");
            return;
        }
        notice("message.itemmilestonelog.copied_recent");
    }

    /**
     * Item ids come from the wire snapshot; allow only id-shaped characters, never raw text or
     * a URL / {@code env:} reference in any letter case (MN-2).
     */
    private static String safeItemId(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 128
                || ClientExportLogic.hasUnsafeScheme(raw)) {
            return "item";
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == ':' || c == '_' || c == '.'
                    || c == '-' || c == '#' || c == '/';
            if (!allowed) {
                return "item";
            }
        }
        return raw;
    }

    private static void notice(String key) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            OVERLAY.send(mc, Component.translatable(key));
        }
    }

    @FunctionalInterface
    private interface Overlay {
        void send(Minecraft mc, Component text);
    }

    private static final Overlay OVERLAY = (mc, text) -> mc.player.sendOverlayMessage(text);
}
