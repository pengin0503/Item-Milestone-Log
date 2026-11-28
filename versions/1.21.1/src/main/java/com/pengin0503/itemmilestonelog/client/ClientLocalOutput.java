package com.pengin0503.itemmilestonelog.client;

import com.pengin0503.itemmilestonelog.ClientBackupRotation;
import com.pengin0503.itemmilestonelog.ClientExportLogic;
import com.pengin0503.itemmilestonelog.ClientStorageIO;
import com.pengin0503.itemmilestonelog.Config;
import com.pengin0503.itemmilestonelog.LocalSyncCache;
import com.pengin0503.itemmilestonelog.ScreenshotStore;
import com.pengin0503.itemmilestonelog.StreamStatusWriter;
import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.compat.client.ScreenshotAccess;
import com.pengin0503.itemmilestonelog.network.PresetEntry;
import com.pengin0503.itemmilestonelog.network.SyncMilestonesPayload;
import com.pengin0503.itemmilestonelog.network.TargetEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * F-150-65/66/67/70/83/85 client-local output wiring. All writes stay below
 * {@link ClientStorageIO#CLIENT_DIR}, use atomic writes, and are guarded by opt-in config keys
 * with safe defaults (backup is the PR-1 default-true key). No event listener is registered here;
 * this is called from the existing sync and logout hooks, so construction stays unchanged.
 */
public final class ClientLocalOutput {
    private static volatile LocalSyncCache.Snapshot lastSnapshot;
    private static volatile long lastCacheWrite = -1L;
    private static volatile long lastStatusWrite = -1L;
    private static volatile String lastStatusTxt = "";
    private static volatile boolean statusAnnounced;
    private static volatile String lastRotatedServerKey = "";
    private static volatile boolean backupWarned;

    private ClientLocalOutput() {
    }

    /**
     * F-150-67 (a): rotate once when connecting to a server whose key differs from the last one.
     * F-150-66: create the offline status files even when no sync ever arrives (vanilla servers).
     */
    public static void onLogin() {
        Path configDir = FMLPaths.CONFIGDIR.get();
        String serverKey = MilestoneHudOverlay.serverKey();
        if (!serverKey.isEmpty() && !serverKey.equals(lastRotatedServerKey)) {
            lastRotatedServerKey = serverKey;
            rotateBackup(configDir);
        }
        if (streamStatusEnabled()) {
            long now = System.currentTimeMillis();
            String txt = StreamStatusWriter.renderTxt(StreamStatusWriter.offlineValues());
            if (StreamStatusWriter.shouldWrite(lastStatusWrite, now, lastStatusTxt, txt)) {
                try {
                    StreamStatusWriter.write(configDir, StreamStatusWriter.offlineValues());
                    lastStatusWrite = now;
                    lastStatusTxt = txt;
                    announceStatus(configDir);
                } catch (IOException | RuntimeException ignored) {
                    warnOnce("Could not write the offline OBS status files.");
                }
            }
        }
    }

    /** Called after the shared HUD snapshot has been rebuilt from a live sync. */
    public static void onSyncReceived(SyncMilestonesPayload payload) {
        if (payload == null) {
            return;
        }
        Path configDir = FMLPaths.CONFIGDIR.get();
        long now = System.currentTimeMillis();
        LocalSyncCache.Snapshot cache = cacheSnapshot(payload);
        lastSnapshot = cache;

        if (cacheEnabled() && LocalSyncCache.writeDue(lastCacheWrite, now)) {
            try {
                LocalSyncCache.write(configDir, cache);
                lastCacheWrite = now;
            } catch (RuntimeException ignored) {
                // Local caching is best-effort and never blocks the game.
            }
        }

        if (streamStatusEnabled()) {
            StreamStatusWriter.Values values = statusValues(payload);
            String txt = StreamStatusWriter.renderTxt(values);
            if (StreamStatusWriter.shouldWrite(lastStatusWrite, now, lastStatusTxt, txt)) {
                try {
                    StreamStatusWriter.write(configDir, values);
                    lastStatusWrite = now;
                    lastStatusTxt = txt;
                    announceStatus(configDir);
                } catch (IOException | RuntimeException ignored) {
                    // OBS output is optional.
                }
            }
        }

        boolean capture = MilestoneHudOverlay.consumeNewRecord();
        if (capture && screenshotEnabled()) {
            Minecraft mc = Minecraft.getInstance();
            if (screenOpen(mc)) {
                notice("message.itemmilestonelog.screenshot_skipped");
            } else {
                captureScreenshot(configDir);
            }
        }
    }

    private static void announceStatus(Path configDir) {
        if (!statusAnnounced) {
            statusAnnounced = true;
            notice("message.itemmilestonelog.stream_status_written",
                    StreamStatusWriter.statusDir(configDir).toString());
        }
    }

    /** One warn at most per session (F-150-67); gameplay and disconnect are never interrupted. */
    private static void rotateBackup(Path configDir) {
        if (!backupEnabled() || !Files.exists(LocalSyncCache.fileFor(configDir))) {
            return;
        }
        try {
            ClientBackupRotation.rotate(configDir, Instant.now(), ZoneId.systemDefault(),
                    Config.CLIENT_BACKUP_GENERATIONS.get());
        } catch (RuntimeException e) {
            warnOnce("Could not rotate the local cache backup.");
        }
    }

    private static void warnOnce(String message) {
        if (!backupWarned) {
            backupWarned = true;
            java.util.logging.Logger.getLogger("itemmilestonelog").warning(message);
        }
    }

    /** Called once on logout, before the sync snapshot is cleared. At most three side effects. */
    /**
     * F-150-83: takes one screenshot on PRESET_COMPLETE using exactly F-150-70's setting and
     * secret-screen skip rule; no new config key, channel or screen is introduced.
     */
    public static void capturePresetComplete() {
        if (!screenshotEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (screenOpen(mc)) {
            notice("message.itemmilestonelog.screenshot_skipped");
            return;
        }
        captureScreenshot(FMLPaths.CONFIGDIR.get());
    }

    public static void onLogout() {
        Path configDir = FMLPaths.CONFIGDIR.get();
        Instant now = Instant.now();
        ZoneId zone = ZoneId.systemDefault();
        LocalSyncCache.Snapshot snapshot = lastSnapshot;

        // 1. Flush the latest live snapshot so offline views and exports see it.
        if (snapshot != null && cacheEnabled()) {
            try {
                LocalSyncCache.write(configDir, snapshot);
            } catch (RuntimeException ignored) {
                // never interrupt a disconnect
            }
        }
        // 2. Rotate local cache backups (PR-1 default-true key).
        rotateBackup(configDir);
        // 3. Optional exit export, a different file from the backup.
        if (exitExportEnabled()) {
            try {
                LocalSyncCache.Snapshot source = snapshot;
                if (source == null) {
                    source = LocalSyncCache.read(configDir, MilestoneHudOverlay.serverKey()).snapshot();
                }
                if (source != null) {
                    ClientExportLogic.export(configDir, Config.CLIENT_EXPORT_DIR.get(), "json",
                            source, source.offline(), now, zone);
                }
            } catch (IOException | RuntimeException ignored) {
                // never interrupt a disconnect
            }
        }
        lastSnapshot = null;
        lastCacheWrite = -1L;
        lastStatusWrite = -1L;
        lastStatusTxt = "";
        statusAnnounced = false;
    }

    /** Builds the display-only cache snapshot from a live sync (no player identity is stored). */
    static LocalSyncCache.Snapshot cacheSnapshot(SyncMilestonesPayload payload) {
        List<LocalSyncCache.CachedTarget> targets = new ArrayList<>();
        for (TargetEntry target : payload.targets()) {
            targets.add(new LocalSyncCache.CachedTarget(target.itemId(), target.count(),
                    target.personal(), target.defaultTarget()));
        }
        List<LocalSyncCache.CachedRecord> records = new ArrayList<>();
        for (Map.Entry<TargetKey, MilestoneRecord> entry : payload.records().entrySet()) {
            TargetKey key = entry.getKey();
            MilestoneRecord record = entry.getValue();
            records.add(new LocalSyncCache.CachedRecord(key.itemId(), key.count(),
                    record.playTicks(), record.day(), record.epochMillis()));
        }
        List<LocalSyncCache.CachedProgress> progress = new ArrayList<>();
        for (Map.Entry<TargetKey, Integer> entry : payload.progress().entrySet()) {
            TargetKey key = entry.getKey();
            progress.add(new LocalSyncCache.CachedProgress(key.itemId(), key.count(), entry.getValue()));
        }
        List<LocalSyncCache.CachedPreset> presets = new ArrayList<>();
        for (PresetEntry preset : payload.presets()) {
            presets.add(new LocalSyncCache.CachedPreset(preset.id(), preset.displayName(),
                    preset.itemCount(), preset.builtin()));
        }
        return new LocalSyncCache.Snapshot(targets, records, progress,
                payload.currentPlaytimeTicks(), presets, payload.canManagePresets(),
                payload.openScreen(), MilestoneHudOverlay.serverKey(),
                System.currentTimeMillis(), false);
    }

    private static StreamStatusWriter.Values statusValues(SyncMilestonesPayload payload) {
        TargetKey newestKey = null;
        MilestoneRecord newest = null;
        for (Map.Entry<TargetKey, MilestoneRecord> entry : payload.records().entrySet()) {
            MilestoneRecord record = entry.getValue();
            if (newest == null || record.epochMillis() > newest.epochMillis()
                    || (record.epochMillis() == newest.epochMillis()
                        && record.playTicks() > newest.playTicks())) {
                newest = record;
                newestKey = entry.getKey();
            }
        }
        String last = newestKey == null ? "" : encoded(newestKey.itemId(), newestKey.count());
        int worldFirsts = ClientMilestoneHandler.getExtraData().worldFirsts().size();
        // No "current preset" field exists in either sync payload; F-150-83 allows it to stay
        // empty when it cannot be derived from synced data (no new S2C channel).
        return new StreamStatusWriter.Values(payload.records().size(), payload.targets().size(),
                last, payload.currentPlaytimeTicks(), false, "", worldFirsts);
    }

    private static String encoded(String itemId, int count) {
        return count == 1 ? itemId : itemId + "#" + count;
    }

    private static void captureScreenshot(Path configDir) {
        try {
            Path dir = ScreenshotStore.screenshotDir(configDir);
            Files.createDirectories(dir);
            String name = ScreenshotStore.chooseFileName(Instant.now(), ZoneId.systemDefault(),
                    candidate -> Files.exists(dir.resolve(candidate)));
            if (!ScreenshotAccess.capture(configDir, name)) {
                return;
            }
            List<String> names = new ArrayList<>();
            try (var stream = Files.list(dir)) {
                stream.forEach(path -> names.add(path.getFileName().toString()));
            }
            for (String old : ScreenshotStore.selectFilesToPrune(names)) {
                try {
                    Files.deleteIfExists(dir.resolve(old));
                } catch (IOException ignored) {
                    // Rotation is best-effort.
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // A failed screenshot never affects gameplay.
        }
    }

    /** Screen detection uses reflection so no version-varying field is linked from shared code. */
    private static boolean screenOpen(Minecraft mc) {
        try {
            try {
                Field field = Minecraft.class.getDeclaredField("screen");
                field.setAccessible(true);
                return field.get(mc) != null;
            } catch (NoSuchFieldException e) {
                Object gui = Minecraft.class.getDeclaredField("gui").get(mc);
                try {
                    Field field = gui.getClass().getDeclaredField("screen");
                    field.setAccessible(true);
                    return field.get(gui) != null;
                } catch (NoSuchFieldException e2) {
                    Method method = gui.getClass().getMethod("screen");
                    return method.invoke(gui) != null;
                }
            }
        } catch (Throwable t) {
            // Fail closed: never capture when a secret screen cannot be ruled out.
            return true;
        }
    }

    private static void notice(String key) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable(key), true);
        }
    }

    private static void notice(String key, String argument) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable(key, argument), true);
        }
    }

    private static boolean cacheEnabled() {
        try {
            return Config.LOCAL_CACHE_ENABLED.get();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private static boolean backupEnabled() {
        try {
            return Config.CLIENT_BACKUP_ENABLED.get();
        } catch (IllegalStateException e) {
            return true; // PR-1 default; only reached before the config is loaded.
        }
    }

    private static boolean exitExportEnabled() {
        try {
            return Config.EXIT_AUTO_EXPORT.get();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private static boolean streamStatusEnabled() {
        try {
            return Config.STREAM_STATUS_ENABLED.get();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private static boolean screenshotEnabled() {
        try {
            return Config.SCREENSHOT_ON_RECORD.get();
        } catch (IllegalStateException e) {
            return false;
        }
    }
}
