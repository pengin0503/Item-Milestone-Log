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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
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
    /** How long the server-thread fallback waits for an in-flight final backup before giving up. */
    static final long FINAL_BACKUP_GRACE_MILLIS = 3_000L;
    private static final long FINAL_BACKUP_POLL_MILLIS = 50L;

    private static final int FINAL_BACKUP_OPEN = 0;
    private static final int FINAL_BACKUP_CLAIMED = 1;
    private static final int FINAL_BACKUP_WRITTEN = 2;

    private static volatile long lastBackupMillis = MilestoneBackupLogic.NO_BACKUP_RECORDED;
    /**
     * Serializes the whole snapshot write (filename choice, temp write, atomic move and prune) so
     * a synchronous {@link #writeBackupNow} can never collide with an executor backup on the same
     * second (review 1-2). A {@link ReentrantLock} lets the stop-time server-thread fallback try
     * the lock with a bounded timeout instead of blocking a server stop indefinitely.
     */
    private static final ReentrantLock SNAPSHOT_WRITE_LOCK = new ReentrantLock();
    /** Guards generation/session replacement only; never held during disk I/O. */
    private static final Object FINAL_BACKUP_SESSION_LOCK = new Object();
    private static long finalBackupGeneration;
    /**
     * Exactly-once final-backup claim state for the current server session. Replaced on every
     * server start/stop, so a lingering I/O thread from a previous world can only mark its own
     * captured session as written (review 3-2 / fix 8).
     */
    private static volatile FinalBackupSession finalBackupSession;

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
        beginFinalBackupSession();
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
        beginFinalBackupSession();
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
                    server.registryAccess(),
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
            CompoundTag root = data.toSavedDataFileTag(server.registryAccess());
            ZoneId zone = backupTimeZone();
            int maxGenerations = Config.autoBackupGenerations();
            // F-140-3: the rate-limit timestamp is owned by the server thread and is recorded at
            // acceptance time, before the asynchronous write starts. Recording it in the I/O task
            // would let concurrent saves queue duplicate backups while a write is pending (and a
            // late write from a previous world could contaminate the next world in single-player).
            lastBackupMillis = now.toEpochMilli();
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
            CompoundTag root = data.toSavedDataFileTag(server.registryAccess());
            Instant now = Instant.now();
            ZoneId zone = backupTimeZone();
            int maxGenerations = Config.autoBackupGenerations();
            FinalBackupSession session = currentFinalBackupSession();
            return new PreparedFinalBackup(session, dir, root, now, zone, maxGenerations);
        } catch (RuntimeException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Could not prepare final SavedData backup: {}",
                    e.getMessage());
            return null;
        }
    }

    /**
     * Synchronous last-resort final backup (N149 drain timeout or missing executor).
     *
     * <p>The exactly-once claim is an atomic state transition, never a lock held across disk I/O.
     * If the asynchronous final backup already claimed the write, this path only polls its
     * completion for a bounded grace period and then lets the server stop continue with a warning
     * (review 2-1 / F-140-4).
     */
    public static boolean writeFinalBackupNow(@Nullable MinecraftServer server) {
        if (server == null) {
            return false;
        }
        FinalBackupSession session = currentFinalBackupSession();
        if (session.state.get() == FINAL_BACKUP_WRITTEN) {
            return true;
        }
        if (session.state.get() == FINAL_BACKUP_CLAIMED
                && !awaitFinalBackupSettled(session, FINAL_BACKUP_GRACE_MILLIS)) {
            ItemMilestoneLog.LOGGER.warn(
                    "[ItemMilestoneLog] Final SavedData backup is still being written after {} ms; continuing server stop",
                    FINAL_BACKUP_GRACE_MILLIS);
            return false;
        }
        try {
            if (!Config.autoBackupEnabled()) {
                return false;
            }
            MilestoneData data = MilestoneData.get(server);
            if (data.isEmpty()) {
                return false;
            }
            return claimAndWriteFinalBackup(
                    session,
                    backupsDir(server),
                    data.toSavedDataFileTag(server.registryAccess()),
                    Instant.now(),
                    backupTimeZone(),
                    Config.autoBackupGenerations(),
                    FINAL_BACKUP_GRACE_MILLIS);
        } catch (RuntimeException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Could not write final SavedData backup: {}",
                    e.getMessage());
            return false;
        }
    }

    /** Executor-side final backup: claims exactly once, then writes without holding the claim. */
    private static boolean writeFinalBackup(
            FinalBackupSession session,
            Path dir,
            CompoundTag root,
            Instant now,
            ZoneId zone,
            int maxGenerations) {
        if (session.state.get() == FINAL_BACKUP_WRITTEN) {
            return true;
        }
        if (!session.state.compareAndSet(FINAL_BACKUP_OPEN, FINAL_BACKUP_CLAIMED)) {
            return session.state.get() == FINAL_BACKUP_WRITTEN;
        }
        boolean written = false;
        try {
            written = writeBackupSnapshot(dir, root, now, zone, maxGenerations);
        } finally {
            if (written) {
                session.state.set(FINAL_BACKUP_WRITTEN);
            } else {
                session.state.compareAndSet(FINAL_BACKUP_CLAIMED, FINAL_BACKUP_OPEN);
            }
        }
        return written;
    }

    /**
     * Claims the current session's final-backup slot and writes the snapshot. If another path
     * already claimed it, waits only {@code graceMillis} so a server stop can never block on an
     * in-flight write indefinitely.
     */
    private static boolean claimAndWriteFinalBackup(
            FinalBackupSession session,
            Path dir,
            CompoundTag root,
            Instant now,
            ZoneId zone,
            int maxGenerations,
            long graceMillis) {
        long grace = Math.max(0L, graceMillis);
        while (true) {
            int state = session.state.get();
            if (state == FINAL_BACKUP_WRITTEN) {
                return true;
            }
            if (state == FINAL_BACKUP_CLAIMED) {
                if (!awaitFinalBackupSettled(session, grace)) {
                    ItemMilestoneLog.LOGGER.warn(
                            "[ItemMilestoneLog] Final SavedData backup is still being written after {} ms; continuing server stop",
                            grace);
                    return false;
                }
                continue;
            }
            if (session.state.compareAndSet(FINAL_BACKUP_OPEN, FINAL_BACKUP_CLAIMED)) {
                break;
            }
        }
        boolean written = false;
        try {
            written = writeBackupSnapshotBounded(dir, root, now, zone, maxGenerations, grace);
        } finally {
            if (written) {
                session.state.set(FINAL_BACKUP_WRITTEN);
            } else {
                session.state.compareAndSet(FINAL_BACKUP_CLAIMED, FINAL_BACKUP_OPEN);
            }
        }
        return written;
    }

    /**
     * Runs a task returned by {@link #prepareFinalBackup} on the server thread with the bounded
     * claim/lock path, so the prepared snapshot is written exactly once and never re-serialized.
     */
    public static boolean writePreparedFinalBackupNow(Runnable prepared) {
        if (prepared instanceof PreparedFinalBackup task) {
            return claimAndWriteFinalBackup(
                    task.session,
                    task.dir,
                    task.root,
                    task.now,
                    task.zone,
                    task.maxGenerations,
                    FINAL_BACKUP_GRACE_MILLIS);
        }
        prepared.run();
        return false;
    }

    /** Waits (bounded) until the in-flight claim is no longer {@code CLAIMED}. */
    private static boolean awaitFinalBackupSettled(FinalBackupSession session, long graceMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, graceMillis));
        while (session.state.get() == FINAL_BACKUP_CLAIMED) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(FINAL_BACKUP_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private static FinalBackupSession currentFinalBackupSession() {
        FinalBackupSession session = finalBackupSession;
        return session != null ? session : beginFinalBackupSession();
    }

    private static FinalBackupSession beginFinalBackupSession() {
        synchronized (FINAL_BACKUP_SESSION_LOCK) {
            finalBackupGeneration++;
            FinalBackupSession session = new FinalBackupSession(finalBackupGeneration);
            finalBackupSession = session;
            return session;
        }
    }

    static long finalBackupGeneration() {
        FinalBackupSession session = finalBackupSession;
        return session != null ? session.generation : 0L;
    }

    static boolean isFinalBackupWritten() {
        FinalBackupSession session = finalBackupSession;
        return session != null && session.state.get() == FINAL_BACKUP_WRITTEN;
    }

    /** Test hook: returns the executor-side task for the current session without a server. */
    static Runnable prepareFinalBackupForTest(
            Path dir,
            CompoundTag root,
            Instant now,
            ZoneId zone,
            int maxGenerations) {
        FinalBackupSession session = currentFinalBackupSession();
        return new PreparedFinalBackup(session, dir, root, now, zone, maxGenerations);
    }

    /** Test hook: runs the bounded server-thread claim/write path without a server. */
    static boolean writeFinalBackupNowForTest(
            Path dir,
            CompoundTag root,
            Instant now,
            ZoneId zone,
            int maxGenerations,
            long graceMillis) {
        return claimAndWriteFinalBackup(
                currentFinalBackupSession(), dir, root, now, zone, maxGenerations, graceMillis);
    }

    /** Test hook: simulates another writer that has claimed but not finished the final backup. */
    static boolean claimFinalBackupForTest() {
        return currentFinalBackupSession().state.compareAndSet(FINAL_BACKUP_OPEN, FINAL_BACKUP_CLAIMED);
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
            if (!writeBackupSnapshot(dir, root, now, zone, maxGenerations)) {
                return false;
            }
            // F-140-3: the synchronous path records the timestamp on its caller's thread (the
            // server thread) only after the write succeeded; the I/O thread never owns it.
            lastBackupMillis = now.toEpochMilli();
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
        SNAPSHOT_WRITE_LOCK.lock();
        try {
            return writeBackupSnapshotLocked(dir, root, now, zone, maxGenerations);
        } finally {
            SNAPSHOT_WRITE_LOCK.unlock();
        }
    }

    /**
     * Stop-time variant used by the server-thread fallback: acquires the shared write lock with a
     * bounded timeout, so a normal backup stuck on disk I/O cannot extend the server stop
     * indefinitely. The whole filename-choice-through-prune sequence still stays mutually
     * exclusive with every other path.
     */
    private static boolean writeBackupSnapshotBounded(
            Path dir,
            @Nullable CompoundTag root,
            Instant now,
            ZoneId zone,
            int maxGenerations,
            long timeoutMillis) {
        if (dir == null || root == null) {
            return false;
        }
        long timeout = Math.max(0L, timeoutMillis);
        boolean locked;
        try {
            locked = SNAPSHOT_WRITE_LOCK.tryLock(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (!locked) {
            ItemMilestoneLog.LOGGER.warn(
                    "[ItemMilestoneLog] Timed out after {} ms waiting for the backup write lock in {}; skipping the final SavedData backup",
                    timeout, dir);
            return false;
        }
        try {
            return writeBackupSnapshotLocked(dir, root, now, zone, maxGenerations);
        } finally {
            SNAPSHOT_WRITE_LOCK.unlock();
        }
    }

    private static boolean writeBackupSnapshotLocked(
            Path dir,
            CompoundTag root,
            Instant now,
            ZoneId zone,
            int maxGenerations) {
        try {
            Files.createDirectories(dir);
            String fileName = MilestoneBackupLogic.chooseFileName(
                    now, zone, name -> Files.exists(dir.resolve(name)));
            Path target = dir.resolve(fileName).normalize();
            if (!dir.normalize().equals(target.getParent())) {
                throw new IOException("Invalid backup target path: " + target);
            }
            writeCompressedAtomically(dir, fileName, target, root);
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

    /** Snapshot captured on the server thread for the stop-time final backup. */
    private static final class PreparedFinalBackup implements Runnable {
        final FinalBackupSession session;
        final Path dir;
        final CompoundTag root;
        final Instant now;
        final ZoneId zone;
        final int maxGenerations;

        PreparedFinalBackup(FinalBackupSession session, Path dir, CompoundTag root, Instant now,
                ZoneId zone, int maxGenerations) {
            this.session = session;
            this.dir = dir;
            this.root = root;
            this.now = now;
            this.zone = zone;
            this.maxGenerations = maxGenerations;
        }

        @Override
        public void run() {
            writeFinalBackup(session, dir, root, now, zone, maxGenerations);
        }
    }

    /** One server session's exactly-once final-backup claim; replaced on start/stop. */
    private static final class FinalBackupSession {
        final long generation;
        final AtomicInteger state = new AtomicInteger(FINAL_BACKUP_OPEN);

        FinalBackupSession(long generation) {
            this.generation = generation;
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
