package com.pengin0503.itemmilestonelog.client;

import com.pengin0503.itemmilestonelog.AccessibilityPolicy;
import com.pengin0503.itemmilestonelog.ClientCarryStore;
import com.pengin0503.itemmilestonelog.ClientProfileStore;
import com.pengin0503.itemmilestonelog.ClientStorageIO;
import com.pengin0503.itemmilestonelog.Config;
import com.pengin0503.itemmilestonelog.DisplayOptions;
import com.pengin0503.itemmilestonelog.HudLayoutOptions;
import com.pengin0503.itemmilestonelog.HudRowPlan;
import com.pengin0503.itemmilestonelog.LocalSyncCache;
import com.pengin0503.itemmilestonelog.LowPowerModePolicy;
import com.pengin0503.itemmilestonelog.PinStore;
import com.pengin0503.itemmilestonelog.RecordDiff;
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
    // F-150-86: row-cache rebuilds are coalesced only while low-power mode is on.
    private static long tickCounter;
    private static long lastRebuildTick;
    private static volatile boolean rebuildPending;
    // F-150-70: the screenshot diff reuses this HUD snapshot history; no second store is kept.
    private static volatile Set<String> previousRecorded;
    private static volatile boolean pendingCapture;
    private static volatile long lastCaptureMillis;
    // F-150-64: the invalid-cache notice is shown at most once per session.
    private static boolean cacheInvalidNoticeShown;

    private MilestoneHudOverlay() {
    }

    /** Pin commands use this set to refuse ids that are not in the current synced data. */
    @Nullable
    public static Set<String> availableIds() {
        Snapshot current = snapshot;
        return current == null ? null : current.available();
    }

    /**
     * F-150-64 / SF-2: opaque server identifier computed at login. It is deterministic as soon as
     * {@link #onLogin(Minecraft)} runs, before any snapshot or sync arrives, so connect-time backup
     * rotation and profile loading see the real key. Empty only outside a connection.
     */
    public static String serverKey() {
        return serverKey;
    }

    /** True only when a profile-adjusted config enables the HUD (default false). */
    public static boolean isEnabled() {
        return effective.pinHudEnabled();
    }

    /** PR-6: one read-only snapshot shared by the chat style, effect and GUI clock consumers. */
    public static DisplayOptions effectiveOptions() {
        return effective;
    }

    /** F-150-72: re-applies config + the current per-server profile after a profile command. */
    public static void reloadOptions() {
        refreshOptions();
        rebuild();
    }

    /** Called on login: binds the local profile and loads any offline snapshot. */
    public static void onLogin(Minecraft mc) {
        String address = mc.getConnection() != null && mc.getConnection().getConnection() != null
                && mc.getConnection().getConnection().getRemoteAddress() != null
                ? mc.getConnection().getConnection().getRemoteAddress().toString() : "";
        // SF-1: a real save/world identity, not the dimension (every save has overworld).
        String world = worldIdentity(mc);
        serverKey = ClientStorageIO.serverKey(address, world);
        // Set before any consumer runs: ClientLocalOutput.onLogin() rotates on this key.
        ClientLocalOutput.onLogin();
        refreshOptions();
        LocalSyncCache.LoadResult cached = LocalSyncCache.read(FMLPaths.CONFIGDIR.get(), serverKey);
        if (cached.invalid() && !cacheInvalidNoticeShown) {
            cacheInvalidNoticeShown = true;
            ClientNotice.overlay(Component.translatable("message.itemmilestonelog.cache_invalid"));
        }
        if (snapshot == null && cached.present()) {
            applyOffline(cached.snapshot());
        } else {
            requestRebuild();
        }
    }

    /**
     * SF-1: resolves the client-side save identity without linking version-varying APIs. The
     * integrated server's level name and save directory distinguish two single-player saves that
     * share a dimension; the level seed and finally the dimension are only fallbacks.
     */
    private static String worldIdentity(Minecraft mc) {
        try {
            Object integrated = Minecraft.class.getMethod("getSingleplayerServer").invoke(mc);
            if (integrated != null) {
                String name = "";
                Object worldData = integrated.getClass().getMethod("getWorldData").invoke(integrated);
                if (worldData != null) {
                    Object levelName = worldData.getClass().getMethod("getLevelName").invoke(worldData);
                    if (levelName instanceof String s && !s.isEmpty()) {
                        name = s;
                    }
                }
                Object dir = integrated.getClass().getMethod("getServerDirectory").invoke(integrated);
                if (dir instanceof java.nio.file.Path path && path.getFileName() != null) {
                    String folder = path.getFileName().toString();
                    name = name.isEmpty() ? folder : name + "\0" + folder;
                }
                if (!name.isEmpty()) {
                    return name;
                }
            }
        } catch (Throwable ignored) {
            // Reflection is best-effort; fall through to the level identity below.
        }
        try {
            java.lang.reflect.Field levelField = Minecraft.class.getDeclaredField("level");
            levelField.setAccessible(true);
            Object level = levelField.get(mc);
            if (level != null) {
                Object seed = level.getClass().getMethod("getSeed").invoke(level);
                if (seed instanceof Number number) {
                    return "seed:" + number;
                }
            }
        } catch (Throwable ignored) {
            // Fall through to the dimension, which keeps the pre-fix behaviour as a last resort.
        }
        return mc.level == null ? "" : mc.level.dimension().toString();
    }

    /** Called on logout: no rows, no identity and no cached ids survive the connection. */
    public static void onLogout() {
        // F-150-72: persist the current display options under this connection's key first.
        if (ClientStorageIO.isServerKey(serverKey)) {
            ClientProfileStore.save(FMLPaths.CONFIGDIR.get(), serverKey, effective.toOverrides());
        }
        ClientCarryStore.clearImported();
        snapshot = null;
        rows = List.of();
        serverKey = "";
        retreatStartedMillis = 0L;
        previousRecorded = null;
        pendingCapture = false;
        lastCaptureMillis = 0L;
    }

    private static void refreshOptions() {
        // PR-6: read every display key from Config (tail-appended keys included) so profile
        // overrides for showRecordClock / chatStyleMode / clientEffectEnabled take effect.
        DisplayOptions fromConfig = Config.displayOptions();
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
        // F-150-70: one diff per sync, computed from the HUD's own previous snapshot.
        if (previousRecorded == null) {
            previousRecorded = Set.copyOf(recorded);
        } else {
            if (!RecordDiff.added(previousRecorded, recorded).isEmpty()) {
                long now = System.currentTimeMillis();
                if (RecordDiff.captureDue(lastCaptureMillis, now)) {
                    pendingCapture = true;
                    lastCaptureMillis = now;
                }
            }
            previousRecorded = Set.copyOf(recorded);
        }
        snapshot = new Snapshot(available, progress, recorded, false);
        requestRebuild();
    }

    /** F-150-70: consumes the pending capture decision produced by the shared snapshot diff. */
    public static boolean consumeNewRecord() {
        boolean pending = pendingCapture;
        pendingCapture = false;
        return pending;
    }

    /**
     * MN-5: PRESET_COMPLETE screenshots share the record-diff capture clock. A pending record
     * capture already covers this arrival; otherwise only one capture per second passes.
     */
    public static boolean presetCaptureDue() {
        if (pendingCapture) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (!RecordDiff.captureDue(lastCaptureMillis, now)) {
            return false;
        }
        lastCaptureMillis = now;
        return true;
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
        requestRebuild();
    }

    /** Rebuilds after a client-local pin add/remove/clear. */
    public static void onPinsChanged() {
        requestRebuild();
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

    /**
     * F-150-86: with low-power mode off (the default) every event rebuilds immediately, exactly
     * as before. With it on, only the cosmetic row cache is deferred; snapshots, pin validation
     * ids and the record-diff used by local output still update on every sync.
     */
    private static void requestRebuild() {
        if (Config.lowPowerMode()) {
            rebuildPending = true;
        } else {
            rebuild();
        }
    }

    /** Called from the client tick handler; at most one deferred rebuild per four ticks. */
    public static void onClientTick() {
        if (!Config.lowPowerMode() || !rebuildPending) {
            return;
        }
        tickCounter++;
        if (LowPowerModePolicy.hudRebuildDue(true,
                tickCounter - lastRebuildTick, rebuildPending)) {
            rebuildPending = false;
            lastRebuildTick = tickCounter;
            rebuild();
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
