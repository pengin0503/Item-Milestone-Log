package com.pengin0503.itemmilestonelog;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.level.LevelEvent;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * F10: automatic SavedData backups stored in {@code <world>/itemmilestonelog/backups/}.
 *
 * <p>Serializes the in-memory {@link MilestoneData} into the exact compressed-NBT wrapper used by
 * Minecraft's own SavedData writer (never copying the live file on disk), writes via a temp file
 * plus atomic move on the server thread, rate-limits save-triggered backups to once per hour using
 * an in-memory timestamp initialized once at server start, and prunes only files matching
 * {@link MilestoneBackupLogic#BACKUP_FILE_PATTERN}.
 */
public final class MilestoneBackup {
    private static volatile long lastBackupMillis = MilestoneBackupLogic.NO_BACKUP_RECORDED;

    private MilestoneBackup() {
    }

    /** Returns {@code <world>/itemmilestonelog/backups/}. */
    public static Path backupsDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve(ItemMilestoneLog.MOD_ID)
                .resolve("backups");
    }

    /**
     * Initializes the in-memory last-backup timestamp once at server start by reading existing
     * backup file names in {@code <world>/itemmilestonelog/backups/}.
     */
    public static void initForServer(@Nullable MinecraftServer server) {
        if (server == null) {
            resetState();
            return;
        }
        try {
            initFromDir(backupsDir(server), backupTimeZone());
        } catch (RuntimeException e) {
            lastBackupMillis = MilestoneBackupLogic.NO_BACKUP_RECORDED;
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Could not initialize backup state: {}", e.getMessage());
        }
    }

    static void initFromDir(@Nullable Path dir, ZoneId zone) {
        if (dir == null || !Files.isDirectory(dir)) {
            lastBackupMillis = MilestoneBackupLogic.NO_BACKUP_RECORDED;
            return;
        }
        List<String> names = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .forEach(names::add);
        } catch (IOException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Could not scan backup directory {}: {}", dir, e.getMessage());
            lastBackupMillis = MilestoneBackupLogic.NO_BACKUP_RECORDED;
            return;
        }
        lastBackupMillis = MilestoneBackupLogic.findNewestBackupTimeMillis(names, zone);
    }

    /** Clears in-memory backup timing state (called on {@code ServerStoppedEvent} and in tests). */
    public static void resetState() {
        lastBackupMillis = MilestoneBackupLogic.NO_BACKUP_RECORDED;
    }

    static long lastBackupMillis() {
        return lastBackupMillis;
    }

    /** Triggered on {@link LevelEvent.Save}: backs up when the overworld saves (rate-limited). */
    public static void onLevelSave(LevelEvent.Save event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!Level.OVERWORLD.equals(level.dimension())) {
            return;
        }
        tryAutoBackup(level.getServer(), false);
    }

    /** Triggered on {@code ServerStoppingEvent}: backs up ignoring the 1-hour rate limit. */
    public static void onServerStopping(@Nullable MinecraftServer server) {
        tryAutoBackup(server, true);
    }

    /**
     * Writes one backup immediately, ignoring both {@code autoBackupEnabled} and the 1-hour rate
     * limit (e.g. before F24 {@code renameitem}), while still counting toward generation pruning.
     * Failures are logged and never thrown.
     *
     * @return {@code true} if a backup file was written successfully; {@code false} if data is
     *         empty or writing failed
     */
    public static boolean writeBackupNow(@Nullable MinecraftServer server) {
        if (server == null) {
            return false;
        }
        try {
            MilestoneData data = MilestoneData.get(server);
            return writeBackup(
                    backupsDir(server),
                    data,
                    server.registryAccess(),
                    Instant.now(),
                    backupTimeZone(),
                    Config.autoBackupGenerations());
        } catch (RuntimeException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Could not write SavedData backup: {}", e.getMessage());
            return false;
        }
    }

    private static void tryAutoBackup(@Nullable MinecraftServer server, boolean ignoreRateLimit) {
        if (server == null) {
            return;
        }
        try {
            if (!Config.autoBackupEnabled()) {
                return;
            }
            Instant now = Instant.now();
            if (!ignoreRateLimit && !MilestoneBackupLogic.isRateLimitElapsed(lastBackupMillis, now.toEpochMilli())) {
                return;
            }
            MilestoneData data = MilestoneData.get(server);
            if (data.isEmpty()) {
                return;
            }
            writeBackup(
                    backupsDir(server),
                    data,
                    server.registryAccess(),
                    now,
                    backupTimeZone(),
                    Config.autoBackupGenerations());
        } catch (RuntimeException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Automatic SavedData backup failed: {}", e.getMessage());
        }
    }

    static boolean tryAutoBackupForTest(
            Path dir,
            @Nullable MilestoneData data,
            @Nullable HolderLookup.Provider registries,
            boolean enabled,
            boolean ignoreRateLimit,
            Instant now,
            ZoneId zone,
            int maxGenerations) {
        if (!enabled) {
            return false;
        }
        if (!ignoreRateLimit && !MilestoneBackupLogic.isRateLimitElapsed(lastBackupMillis, now.toEpochMilli())) {
            return false;
        }
        return writeBackup(dir, data, registries, now, zone, maxGenerations);
    }

    static boolean writeBackup(
            Path dir,
            @Nullable MilestoneData data,
            @Nullable HolderLookup.Provider registries,
            Instant now,
            ZoneId zone,
            int maxGenerations) {
        if (dir == null || data == null || data.isEmpty()) {
            return false;
        }
        try {
            CompoundTag root = data.toSavedDataFileTag(registries);
            Files.createDirectories(dir);
            String fileName = MilestoneBackupLogic.chooseFileName(
                    now, zone, name -> Files.exists(dir.resolve(name)));
            Path target = dir.resolve(fileName).normalize();
            if (!dir.normalize().equals(target.getParent())) {
                throw new IOException("Invalid backup target path: " + target);
            }
            writeCompressedAtomically(dir, fileName, target, root);
            lastBackupMillis = now.toEpochMilli();
        } catch (IOException | RuntimeException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Could not write SavedData backup in {}: {}", dir, e.getMessage());
            return false;
        }
        pruneOldBackups(dir, maxGenerations);
        return true;
    }

    private static void writeCompressedAtomically(Path dir, String fileName, Path target, CompoundTag root)
            throws IOException {
        Path temp = Files.createTempFile(dir, "." + fileName + ".", ".tmp");
        try {
            NbtIo.writeCompressed(root, temp);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    private static void pruneOldBackups(Path dir, int maxGenerations) {
        int keep = MilestoneBackupLogic.clampGenerations(maxGenerations);
        List<String> names = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .forEach(names::add);
        } catch (IOException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Could not list backup directory {} for pruning: {}", dir, e.getMessage());
            return;
        }
        for (String oldName : MilestoneBackupLogic.selectFilesToPrune(names, keep)) {
            try {
                Files.deleteIfExists(dir.resolve(oldName));
            } catch (IOException e) {
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Could not delete old backup {}: {}", oldName, e.getMessage());
            }
        }
    }

    private static ZoneId backupTimeZone() {
        try {
            return Config.displayTimeZone();
        } catch (IllegalStateException notLoaded) {
            return ZoneId.systemDefault();
        }
    }
}
