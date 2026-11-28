package com.pengin0503.itemmilestonelog.client;

import com.pengin0503.itemmilestonelog.AccessibilityPolicy;
import com.pengin0503.itemmilestonelog.ClientProfileStore;
import com.pengin0503.itemmilestonelog.ClientStorageIO;
import com.pengin0503.itemmilestonelog.Config;
import com.pengin0503.itemmilestonelog.DisplayOptions;
import com.pengin0503.itemmilestonelog.HudLayoutOptions;
import com.pengin0503.itemmilestonelog.HudRowPlan;
import com.pengin0503.itemmilestonelog.LocalSyncCache;
import com.pengin0503.itemmilestonelog.PinStore;
import com.pengin0503.itemmilestonelog.compat.client.HudCompat;
import com.pengin0503.itemmilestonelog.network.SyncMilestonesPayload;
import com.pengin0503.itemmilestonelog.network.TargetEntry;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.fml.loading.FMLPaths;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F-150-63 / F-150-80 / F-150-82 (HUD part): an independent HUD overlay for locally pinned
 * targets. It is registered from {@link ItemMilestoneLogClient}'s {@code RenderGuiEvent.Post}
 * listener and never touches the five frozen screens' {@code init()} (FL-3, D-150-187).
 *
 * <p>All text is assembled when a sync or a pin changes; a render frame only reads immutable
 * Components and draws them. While {@code pinHudEnabled} is false (the default, D-150-188) the
 * render path returns before any allocation or registry lookup. No server state, new packet or
 * SavedData field is involved.
 */
public final class MilestoneHudOverlay {
    private static final int TEXT_COLOR = 0xFFFFFF;
    private static final int BACKGROUND_COLOR = 0x99000000;
    private static final int PADDING = 3;
    private static final int LINE_HEIGHT = 12;

    private record RenderRow(Component text) {
    }

    private record Snapshot(Set<String> available, Map<String, Integer> progress,
                            Set<String> recorded, boolean offline) {
    }

    private static volatile List<RenderRow> rows = List.of();
    private static volatile Snapshot snapshot;
    private static volatile DisplayOptions effective = DisplayOptions.defaults();
    private static volatile HudLayoutOptions layout = HudLayoutOptions.defaults();
    private static volatile AccessibilityPolicy accessibility = AccessibilityPolicy.defaults();
    private static volatile String serverKey = "";
    private static volatile long retreatStartedMillis;

    private MilestoneHudOverlay() {
    }

    /** Pin commands use this set to refuse ids that are not in the current synced data. */
    @Nullable
    public static Set<String> availableIds() {
        Snapshot current = snapshot;
        return current == null ? null : current.available();
    }

    /** True only when a profile-adjusted config enables the HUD (default false). */
    public static boolean isEnabled() {
        return effective.pinHudEnabled();
    }

    /** Called on login: binds the local profile and loads any offline snapshot. */
    public static void onLogin(Minecraft mc) {
        String address = mc.getConnection() != null && mc.getConnection().getConnection() != null
                && mc.getConnection().getConnection().getRemoteAddress() != null
                ? mc.getConnection().getConnection().getRemoteAddress().toString() : "";
        String world = mc.level == null ? "" : mc.level.dimension().toString();
        serverKey = ClientStorageIO.serverKey(address, world);
        refreshOptions();
        LocalSyncCache.LoadResult cached = LocalSyncCache.read(FMLPaths.CONFIGDIR.get(), serverKey);
        if (snapshot == null && cached.present()) {
            applyOffline(cached.snapshot());
        } else {
            rebuild();
        }
    }

    /** Called on logout: no rows, no identity and no cached ids survive the connection. */
    public static void onLogout() {
        snapshot = null;
        rows = List.of();
        serverKey = "";
        retreatStartedMillis = 0L;
    }

    private static void refreshOptions() {
        DisplayOptions fromConfig = new DisplayOptions(
                Config.pinHudEnabled(), Config.pinHudMaxRows(),
                3, true, true, "default", false, "default", false,
                Config.pinHudAnchor(), Config.pinHudAutoHide(), Config.pinHudScale());
        effective = fromConfig.apply(ClientProfileStore.load(FMLPaths.CONFIGDIR.get(), serverKey));
        layout = HudLayoutOptions.fromConfig(effective.pinHudAnchor(),
                effective.pinHudAutoHide(), effective.pinHudScale());
        accessibility = AccessibilityPolicy.fromConfig(Config.accessibleNarration(),
                Config.hudHighContrast(), Config.reducedMotion());
    }

    /** Rebuilds the row cache from a freshly received sync payload (no new channel). */
    public static void onSyncReceived(SyncMilestonesPayload payload) {
        Set<String> available = new LinkedHashSet<>();
        Map<String, Integer> progress = new LinkedHashMap<>();
        Set<String> recorded = new LinkedHashSet<>();
        for (TargetEntry target : payload.targets()) {
            available.add(encode(target.itemId(), target.count()));
        }
        payload.records().forEach((key, value) -> {
            available.add(encode(key.itemId(), key.count()));
            recorded.add(encode(key.itemId(), key.count()));
        });
        payload.progress().forEach((key, value) -> progress.put(encode(key.itemId(), key.count()), value));
        snapshot = new Snapshot(available, progress, recorded, false);
        rebuild();
    }

    /** Uses the PR-1 local cache as the only offline data source (F-150-64). */
    public static void onOfflineCacheAvailable(LocalSyncCache.Snapshot cached) {
        if (snapshot == null) {
            applyOffline(cached);
        }
    }

    private static void applyOffline(LocalSyncCache.Snapshot cached) {
        Set<String> available = new LinkedHashSet<>();
        Map<String, Integer> progress = new LinkedHashMap<>();
        Set<String> recorded = new LinkedHashSet<>();
        for (LocalSyncCache.CachedTarget target : cached.targets()) {
            available.add(encode(target.itemId(), target.count()));
        }
        for (LocalSyncCache.CachedRecord record : cached.records()) {
            available.add(encode(record.itemId(), record.count()));
            recorded.add(encode(record.itemId(), record.count()));
        }
        for (LocalSyncCache.CachedProgress entry : cached.progress()) {
            progress.put(encode(entry.itemId(), entry.count()), entry.value());
        }
        snapshot = new Snapshot(available, progress, recorded, true);
        rebuild();
    }

    /** Rebuilds after a client-local pin add/remove/clear. */
    public static void onPinsChanged() {
        rebuild();
    }

    /** Damage starts the three-second retreat (F-150-80-3). */
    public static void onDamage() {
        if (layout.autoHide()) {
            retreatStartedMillis = System.currentTimeMillis();
        }
    }

    private static String encode(String itemId, int count) {
        return count == 1 ? itemId : itemId + "#" + count;
    }

    private static int countOf(String encoded) {
        int hash = encoded.lastIndexOf('#');
        if (hash < 1) {
            return 1;
        }
        try {
            return Integer.parseInt(encoded.substring(hash + 1));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static void rebuild() {
        if (!isEnabled()) {
            rows = List.of(); // zero work and zero allocations while disabled
            return;
        }
        Snapshot current = snapshot;
        if (current == null) {
            rows = List.of();
            return;
        }
        List<PinStore.Pin> pins = PinStore.read(FMLPaths.CONFIGDIR.get());
        Map<String, String> names = new HashMap<>();
        for (PinStore.Pin pin : pins) {
            names.put(pin.encoded(), HudCompat.itemName(pin.itemId()));
        }
        List<HudRowPlan.HudRow> planned = HudRowPlan.plan(pins, current.available(),
                current.progress(), current.recorded(), names, current.offline(),
                effective.pinHudMaxRows());
        List<RenderRow> built = new ArrayList<>(planned.size());
        MutableComponent offlinePrefix = current.offline()
                ? Component.translatable(HudRowPlan.OFFLINE_KEY).append(Component.literal(" "))
                : Component.empty().copy();
        for (HudRowPlan.HudRow row : planned) {
            StringBuilder text = new StringBuilder(row.displayName());
            int count = countOf(row.encodedId());
            if (count > 1) {
                text.append(" x").append(count);
            }
            if (row.status() == HudRowPlan.Status.ACHIEVED) {
                text.append(" [done]");
            } else if (row.progress() > 0) {
                text.append(" ").append(row.progress()).append("/").append(count);
            }
            built.add(new RenderRow(offlinePrefix.append(Component.literal(text.toString()))));
        }
        rows = List.copyOf(built);
        if (accessibility.accessibleNarration() && !rows.isEmpty()) {
            Component narrated = Component.literal("");
            for (RenderRow row : rows) {
                narrated = narrated.copy().append(row.text()).append(Component.literal(". "));
            }
            HudCompat.narrate(Minecraft.getInstance(), narrated);
        }
    }

    /** Called once per rendered frame from {@code RenderGuiEvent.Post}. */
    public static void render(GuiGraphics graphics) {
        if (!isEnabled() || rows.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || HudCompat.screen(mc) != null) {
            return; // F-150-63-5: no overlay while a screen (including chat) is open
        }
        long now = System.currentTimeMillis();
        if (HudCompat.hudDisabled(mc) && layout.autoHide()) {
            retreatStartedMillis = now;
        }
        int alpha = layout.fadeAlpha(now, retreatStartedMillis, accessibility.reducedMotion());
        if (alpha <= 0) {
            return;
        }
        List<RenderRow> current = rows;
        int rowHeight = layout.scaled(LINE_HEIGHT);
        int width = 0;
        for (RenderRow row : current) {
            width = Math.max(width, mc.font.width(row.text()));
        }
        width += PADDING * 2;
        int height = rowHeight * current.size() + PADDING * 2;
        Window window = mc.getWindow();
        HudLayoutOptions.Position at = layout.position(
                window.getGuiScaledWidth(), window.getGuiScaledHeight(), width, height);
        if (accessibility.hudHighContrast()) {
            graphics.fill(at.x(), at.y(), at.x() + width, at.y() + height, BACKGROUND_COLOR);
        }
        int color = (alpha << 24) | TEXT_COLOR;
        int y = at.y() + PADDING;
        for (RenderRow row : current) {
            HudCompat.drawString(graphics, mc.font, row.text(), at.x() + PADDING, y, color);
            y += rowHeight;
        }
    }
}
