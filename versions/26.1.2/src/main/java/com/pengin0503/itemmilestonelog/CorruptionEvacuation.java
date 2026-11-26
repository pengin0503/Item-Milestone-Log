package com.pengin0503.itemmilestonelog;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;

/**
 * F-140-2 (N141): at server start, before any normal save can overwrite the on-disk SavedData,
 * copy {@code data/itemmilestonelog/milestones.dat} to
 * {@code <world>/itemmilestonelog/corrupt/} when the latest load skipped at least one element or
 * failed entirely.
 *
 * <p>Vanilla {@code SavedDataStorage} treats a failed parse as absent and the next save silently
 * replaces the file, so this copy is the only preservation path. The source file is never deleted
 * or renamed. Any failure here logs a warning and never blocks server start.
 */
public final class CorruptionEvacuation {
    private CorruptionEvacuation() {
    }

    /** Server-start hook: run after {@code MilestoneBackup.initForServer} and before normal saves. */
    public static void onServerStarting(MinecraftServer server) {
        if (server == null) {
            return;
        }
        try {
            Path worldRoot = server.getWorldPath(LevelResource.ROOT);
            Path dataFile = worldRoot.resolve("data/" + ItemMilestoneLog.MOD_ID + "/milestones.dat");
            if (!Files.isRegularFile(dataFile)) {
                return;
            }
            Path corruptDir = worldRoot.resolve(ItemMilestoneLog.MOD_ID).resolve("corrupt");

            CompoundTag root = readRoot(dataFile);
            boolean rootUnreadable = root == null;
            boolean rootInvalid = false;
            int rawSchemaVersion = CorruptionEvacuationLogic.UNKNOWN_SCHEMA_VERSION;
            if (!rootUnreadable) {
                Tag dataTag = root.get("data");
                if (dataTag instanceof CompoundTag compound) {
                    rawSchemaVersion = readSchemaVersion(compound);
                } else {
                    rootInvalid = true;
                }
            }

            var storage = server.getDataStorage();
            MilestoneData data = storage.get(MilestoneData.TYPE);
            boolean loadFailed = data == null;
            if (data == null) {
                // Vanilla failed to load the existing file (it will be overwritten by the next save).
                data = MilestoneData.get(server);
            }
            MilestoneLoadReport report = data.loadReport();
            // Review 4-1: an existing-but-unreadable file is a total failure by definition,
            // even when another listener already populated a cached empty MilestoneData
            // (loadFailed=false, report=null) and the file would otherwise never be evacuated.
            boolean totalFailure = CorruptionEvacuationLogic.isTotalLoadFailure(
                    rootUnreadable, rootInvalid, loadFailed);
            int elementSkips = report != null ? report.elementSkipCount() : 0;
            if (report != null) {
                totalFailure |= report.totalFailure();
            }
            int loadedSchemaVersion = totalFailure
                    ? rawSchemaVersion
                    : (report != null ? report.loadedSchemaVersion() : rawSchemaVersion);
            var outcome = new CorruptionEvacuationLogic.LoadOutcome(
                    elementSkips, totalFailure, loadedSchemaVersion);
            if (!CorruptionEvacuationLogic.shouldEvacuate(outcome)) {
                return;
            }

            CorruptionEvacuationIO.Result result = CorruptionEvacuationIO.evacuate(
                    dataFile, corruptDir, outcome, Instant.now(), timeZone());
            String retained = result.retainedCount() < 0 ? "unknown" : Integer.toString(result.retainedCount());
            ItemMilestoneLog.LOGGER.warn(
                    "[ItemMilestoneLog] Evacuated possibly corrupted SavedData to {} ({} evacuation(s) retained; {} element skip(s), totalFailure={})",
                    result.dataFile(), retained, outcome.elementSkipCount(), outcome.totalFailure());
            for (String warning : result.warnings()) {
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Corruption evacuation: {}", warning);
            }
        } catch (IOException | RuntimeException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Could not evacuate corrupted SavedData: {}", e.getMessage());
        }
    }

    private static CompoundTag readRoot(Path file) {
        try {
            return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        } catch (IOException | RuntimeException e) {
            ItemMilestoneLog.LOGGER.warn(
                    "[ItemMilestoneLog] Could not read SavedData file {} while checking for corruption: {}",
                    file, e.getMessage());
            return null;
        }
    }

    private static int readSchemaVersion(CompoundTag dataTag) {
        int raw = dataTag.contains("schemaVersion")
                ? dataTag.getIntOr("schemaVersion", SchemaVersionLogic.MALFORMED_SCHEMA_VERSION)
                : SchemaVersionLogic.ABSENT_SCHEMA_VERSION;
        return SchemaVersionLogic.fromStoredVersion(raw).retainedVersion();
    }

    private static ZoneId timeZone() {
        try {
            return Config.displayTimeZone();
        } catch (IllegalStateException notLoaded) {
            return ZoneId.systemDefault();
        }
    }
}
