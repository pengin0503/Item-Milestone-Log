package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.compat.McCompat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Path;

/**
 * F-150-55 (N39): thin, read-only NBT wrapper for {@code backup verify} on Forge 1.20.1.
 *
 * <p>Decodes a disposable {@link MilestoneData} snapshot from a backup file. It never calls
 * {@link MilestoneData#get}, never writes/prunes files, and returns only the pure
 * {@link BackupVerifyResult} statuses (no exception messages, paths or secret values).
 */
final class BackupVerify {
    private BackupVerify() {
    }

    static BackupVerifyResult verifyFile(Path file) {
        CompoundTag root;
        try {
            root = NbtIo.readCompressed(file.toFile());
        } catch (IOException | RuntimeException e) {
            // Truncated gzip / malformed NBT is a corrupt backup, not a host I/O failure.
            return BackupVerifyResult.failure(BackupVerifyResult.Status.INVALID_NBT);
        }
        if (root == null || !McCompat.nbt().containsTyped(root, "data", (byte) 10)) { // NBT TAG_COMPOUND
            return BackupVerifyResult.failure(BackupVerifyResult.Status.INVALID_NBT);
        }
        CompoundTag data = McCompat.nbt().getCompound(root, "data");
        int rawSchema;
        if (!McCompat.nbt().contains(data, "schemaVersion")) {
            rawSchema = SchemaVersionLogic.ABSENT_SCHEMA_VERSION;
        } else if (McCompat.nbt().containsTyped(data, "schemaVersion", (byte) 3)) { // NBT TAG_INT
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
            MilestoneData snapshot = MilestoneData.load(data);
            return BackupVerifyResult.ok(snapshot.allPlayers().size(), snapshot.worldFirsts().size(),
                    schema.retainedVersion());
        } catch (RuntimeException e) {
            return BackupVerifyResult.failure(BackupVerifyResult.Status.INVALID_NBT);
        }
    }
}
