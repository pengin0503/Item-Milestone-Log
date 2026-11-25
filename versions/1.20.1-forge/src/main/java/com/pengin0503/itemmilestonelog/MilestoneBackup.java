package com.pengin0503.itemmilestonelog;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.level.LevelEvent;

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
    /**
     * Guards the stop-time final backup so the asynchronous task and the synchronous timeout
     * fallback can never write two final backups (or race on the same target name).
     */
    private static final Object FINAL_BACKUP_LOCK = new Object();
    private static volatile boolean finalBackupWritten;

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
        finalBackupWritten = false;
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
        finalBackupWritten = false;
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
        tryAutoBackup(level.getServer());
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
        // Deliberately synchronous (N45): F24 renameitem proceeds only after the pre-rename
        // backup is known to be on disk. Moving this to the I/O executor would let the caller
        // mutate/delete data before the snapshot becomes durable.
        if (server == null) {
            return false;
        }
        try {
            MilestoneData data = MilestoneData.get(server);
            return writeBackup(
                    backupsDir(server),
                    data,
                    Instant.now(),
                    backupTimeZone(),
                    Config.autoBackupGenerations());
        } catch (RuntimeException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Could not write SavedData backup: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Save-triggered backup (rate-limited on the server thread). The NBT root snapshot is taken on
     * the server thread; the disk write runs on the {@code itemmilestonelog-io} executor (N45).
     */
    private static void tryAutoBackup(@Nullable MinecraftServer server) {
        if (server == null) {
            return;
        }
        try {
            if (!Config.autoBackupEnabled()) {
                return;
            }
            Instant now = Instant.now();
            if (!MilestoneBackupLogic.isRateLimitElapsed(lastBackupMillis, now.toEpochMilli())) {
                return;
            }
            MilestoneData data = MilestoneData.get(server);
            if (data.isEmpty()) {
                return;
            }
            Path dir = backupsDir(server);
            CompoundTag root = data.toSavedDataFileTag();
            ZoneId zone = backupTimeZone();
            int maxGenerations = Config.autoBackupGenerations();
            Runnable writeTask = () -> writeBackupSnapshot(dir, root, now, zone, maxGenerations);
            if (!MilestoneIo.submit(MilestoneIoLogic.TaskKind.BACKUP, writeTask)) {
                // N45: executor rejected/shut down -> run only this task synchronously.
                ItemMilestoneLog.LOGGER.warn(
                        "[ItemMilestoneLog] I/O executor unavailable; writing automatic SavedData backup synchronously");
                writeTask.run();
            }
        } catch (RuntimeException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Automatic SavedData backup failed: {}", e.getMessage());
        }
    }

    /**
     * Prepares the stop-time final backup on the server thread (N149): the rate limit is ignored,
     * the NBT snapshot is taken now, and the returned task writes it on the executor. Returns
     * {@code null} when no backup is needed (disabled or empty data).
     */
    @Nullable
    public static Runnable prepareFinalBackup(@Nullable MinecraftServer server) {
        if (server == null) {
            return null;
        }
        try {
            if (!Config.autoBackupEnabled()) {
                return null;
            }
            MilestoneData data = MilestoneData.get(server);
            if (data.isEmpty()) {
                return null;
            }
            Path dir = backupsDir(server);
            CompoundTag root = data.toSavedDataFileTag();
            Instant now = Instant.now();
            ZoneId zone = backupTimeZone();
            int maxGenerations = Config.autoBackupGenerations();
            synchronized (FINAL_BACKUP_LOCK) {
                finalBackupWritten = false;
            }
            return () -> writeFinalBackup(dir, root, now, zone, maxGenerations);
        } catch (RuntimeException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Could not prepare final SavedData backup: {}",
                    e.getMessage());
            return null;
        }
    }

    /**
     * Synchronous last-resort final backup (N149 drain timeout or missing executor). The shared
     * lock makes this a no-op if the queued final backup already completed.
     */
    public static boolean writeFinalBackupNow(@Nullable MinecraftServer server) {
        if (server == null) {
            return false;
        }
        synchronized (FINAL_BACKUP_LOCK) {
            if (finalBackupWritten) {
                return true;
            }
            try {
                if (!Config.autoBackupEnabled()) {
                    return false;
                }
                MilestoneData data = MilestoneData.get(server);
                if (data.isEmpty()) {
                    return false;
                }
                boolean written = writeBackupSnapshot(
                        backupsDir(server),
                        data.toSavedDataFileTag(),
                        Instant.now(),
                        backupTimeZone(),
                        Config.autoBackupGenerations());
                if (written) {
                    finalBackupWritten = true;
                }
                return written;
            } catch (RuntimeException e) {
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Could not write final SavedData backup: {}",
                        e.getMessage());
                return false;
            }
        }
    }

    private static boolean writeFinalBackup(
            Path dir,
            CompoundTag root,
            Instant now,
            ZoneId zone,
            int maxGenerations) {
        synchronized (FINAL_BACKUP_LOCK) {
            if (finalBackupWritten) {
                return true;
            }
            boolean written = writeBackupSnapshot(dir, root, now, zone, maxGenerations);
            if (written) {
                finalBackupWritten = true;
            }
            return written;
        }
    }

    static boolean tryAutoBackupForTest(
            Path dir,
            @Nullable MilestoneData data,
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
        return writeBackup(dir, data, now, zone, maxGenerations);
    }

    static boolean writeBackup(
            Path dir,
            @Nullable MilestoneData data,
            Instant now,
            ZoneId zone,
            int maxGenerations) {
        if (dir == null || data == null || data.isEmpty()) {
            return false;
        }
        try {
            CompoundTag root = data.toSavedDataFileTag();
            if (!writeBackupSnapshot(dir, root, now, zone, maxGenerations)) {
                return false;
            }
        } catch (RuntimeException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Could not write SavedData backup in {}: {}",
                    dir, e.getMessage());
            return false;
        }
        return true;
    }

    /** Writes an already-serialized SavedData root and prunes old generations (I/O thread). */
    static boolean writeBackupSnapshot(
            Path dir,
            @Nullable CompoundTag root,
            Instant now,
            ZoneId zone,
            int maxGenerations) {
        if (dir == null || root == null) {
            return false;
        }
        try {
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
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Could not write SavedData backup in {}: {}",
                    dir, e.getMessage());
            return false;
        }
        pruneOldBackups(dir, maxGenerations);
        return true;
    }

    private static void writeCompressedAtomically(Path dir, String fileName, Path target, CompoundTag root)
            throws IOException {
        Path temp = Files.createTempFile(dir, "." + fileName + ".", ".tmp");
        try {
            NbtIo.writeCompressed(root, temp.toFile());
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
