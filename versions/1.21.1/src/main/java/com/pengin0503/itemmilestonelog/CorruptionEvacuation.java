package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.compat.McCompat;
import com.pengin0503.itemmilestonelog.compat.NbtCompat;
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
 * copy {@code data/itemmilestonelog_data.dat} to {@code <world>/itemmilestonelog/corrupt/} when
 * the latest load skipped at least one element or failed entirely.
 *
 * <p>Vanilla {@code DimensionDataStorage} treats a failed load as absent and the next save
 * silently replaces the file, so this copy is the only preservation path. The source file is
 * never deleted or renamed. Any failure here logs a warning and never blocks server start.
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
            Path dataFile = worldRoot.resolve("data").resolve(MilestoneData.DATA_NAME + ".dat");
            if (!Files.isRegularFile(dataFile)) {
                return;
            }
            Path corruptDir = worldRoot.resolve(ItemMilestoneLog.MOD_ID).resolve("corrupt");

            CompoundTag root = readRoot(dataFile);
            boolean rootUnreadable = root == null;
            boolean rootInvalid = false;
            int rawSchemaVersion = CorruptionEvacuationLogic.UNKNOWN_SCHEMA_VERSION;
            if (!rootUnreadable) {
                if (McCompat.nbt().containsTyped(root, "data", Tag.TAG_COMPOUND)) {
                    // #99: inspect through a leftover inner "data" wrapper written by the
                    // buggy 1.21.5-1.21.11 codec; otherwise schema/root checks see no fields.
                    CompoundTag dataTag = MilestoneData.unwrapDoubleWrappedData(
                            McCompat.nbt().getCompound(root, "data"));
                    rawSchemaVersion = readSchemaVersion(dataTag);
                    // Vanilla's NBT getters silently treat a wrong-typed players/worldFirsts
                    // container as empty; 26.x's Codec fails the whole decode for the same input.
                    // Mark it as a total failure so the original file is preserved before save.
                    rootInvalid = hasMalformedRootContainer(dataTag);
                } else {
                    rootInvalid = true;
                }
            }

            var storage = server.overworld().getDataStorage();
            MilestoneData data = McCompat.data().get(storage);
            boolean loadFailed = data == null;
            if (data == null) {
                // Vanilla failed to load the existing file (it will be overwritten by the next save).
                data = McCompat.data().getOrCreate(storage);
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

    private static boolean hasMalformedRootContainer(CompoundTag dataTag) {
        return (McCompat.nbt().contains(dataTag, "players")
                && !McCompat.nbt().containsTyped(dataTag, "players", Tag.TAG_COMPOUND))
                || (McCompat.nbt().contains(dataTag, "worldFirsts")
                && !McCompat.nbt().containsTyped(dataTag, "worldFirsts", Tag.TAG_COMPOUND));
    }

    private static int readSchemaVersion(CompoundTag dataTag) {
        int raw = SchemaVersionLogic.ABSENT_SCHEMA_VERSION;
        if (McCompat.nbt().containsTyped(dataTag, "schemaVersion", NbtCompat.ANY_NUMERIC)) {
            raw = McCompat.nbt().getInt(dataTag, "schemaVersion");
        } else if (McCompat.nbt().contains(dataTag, "schemaVersion")) {
            raw = SchemaVersionLogic.MALFORMED_SCHEMA_VERSION;
        }
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
