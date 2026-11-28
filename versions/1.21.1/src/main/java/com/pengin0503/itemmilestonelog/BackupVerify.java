package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.compat.McCompat;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NumericTag;

import javax.annotation.Nullable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipException;

/**
 * F-150-55 (N39): thin, read-only NBT wrapper for {@code backup verify} on 1.21.1.
 *
 * <p>Decodes a disposable {@link MilestoneData} snapshot from a backup file. It never calls
 * {@link MilestoneData#get}, never writes/prunes files, and returns only the pure
 * {@link BackupVerifyResult} statuses (no exception messages, paths or secret values).
 */
final class BackupVerify {
    /** MN-5: cap inflated NBT at 64 MiB; {@link NbtAccounter} budgets are expressed in bits. */
    private static final long MAX_VERIFY_NBT_BITS = 64L * 1024L * 1024L * 8L;

    private BackupVerify() {
    }

    static BackupVerifyResult verifyFile(Path file, @Nullable HolderLookup.Provider registries) {
        CompoundTag root;
        try {
            root = NbtIo.readCompressed(file, NbtAccounter.create(MAX_VERIFY_NBT_BITS));
        } catch (IOException e) {
            // MN-2: a gzip/structure failure is corrupt data; a genuine OS read failure is I/O.
            return BackupVerifyResult.failure(isCorruptPayload(e)
                    ? BackupVerifyResult.Status.INVALID_NBT : BackupVerifyResult.Status.IO_ERROR);
        } catch (RuntimeException e) {
            // Includes the NbtAccounter budget failure for an oversized payload.
            return BackupVerifyResult.failure(BackupVerifyResult.Status.INVALID_NBT);
        } catch (Error e) {
            // MN-5: never let an allocation/verification failure escape command dispatch.
            return BackupVerifyResult.failure(BackupVerifyResult.Status.IO_ERROR);
        }
        if (root == null || !McCompat.nbt().containsTyped(root, "data", (byte) 10)) { // NBT TAG_COMPOUND
            return BackupVerifyResult.failure(BackupVerifyResult.Status.INVALID_NBT);
        }
        CompoundTag data = McCompat.nbt().getCompound(root, "data");
        int rawSchema;
        if (!McCompat.nbt().contains(data, "schemaVersion")) {
            rawSchema = SchemaVersionLogic.ABSENT_SCHEMA_VERSION;
        // MN-4: any numeric tag is accepted, matching 26.x and the loader's lenient schema codec.
        } else if (data.get("schemaVersion") instanceof NumericTag) {
            rawSchema = McCompat.nbt().getInt(data, "schemaVersion");
        } else {
            rawSchema = SchemaVersionLogic.MALFORMED_SCHEMA_VERSION;
        }
        SchemaVersionLogic.LoadResult schema = SchemaVersionLogic.fromStoredVersion(rawSchema);
        if (schema.status() != SchemaVersionLogic.Status.VALID
                && schema.status() != SchemaVersionLogic.Status.MISSING) {
            // Future (or malformed) schema: never restore or partially apply such a backup.
            return BackupVerifyResult.failure(BackupVerifyResult.Status.INCOMPATIBLE_SCHEMA);
        }
        try {
            MilestoneData snapshot = MilestoneData.load(data, registries);
            // MN-3: lenient decoding records skipped elements; such a backup is not fully restorable.
            MilestoneLoadReport report = snapshot.loadReport();
            if (report != null && (report.elementSkipCount() > 0 || report.totalFailure())) {
                return BackupVerifyResult.failure(BackupVerifyResult.Status.INVALID_NBT);
            }
            return BackupVerifyResult.ok(snapshot.allPlayers().size(), snapshot.worldFirsts().size(),
                    schema.retainedVersion());
        } catch (RuntimeException e) {
            return BackupVerifyResult.failure(BackupVerifyResult.Status.INVALID_NBT);
        } catch (Error e) {
            return BackupVerifyResult.failure(BackupVerifyResult.Status.IO_ERROR);
        }
    }

    /** Recognizes corrupt gzip/NBT errors in either the exception itself or a wrapping cause. */
    private static boolean isCorruptPayload(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof ZipException || current instanceof EOFException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
