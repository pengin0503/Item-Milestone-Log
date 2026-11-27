package com.pengin0503.itemmilestonelog;

import com.mojang.serialization.DynamicOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;

/**
 * F-150-55 (N39): thin, read-only NBT wrapper for {@code backup verify} on 26.x.
 *
 * <p>Decodes a disposable {@link MilestoneData} snapshot from a backup file. It never calls
 * {@link MilestoneData#get}, never writes/prunes files, and returns only the pure
 * {@link BackupVerifyResult} statuses (no exception messages, paths or secret values).
 */
final class BackupVerify {
    private BackupVerify() {
    }

    static BackupVerifyResult verifyFile(Path file, @Nullable HolderLookup.Provider registries) {
        CompoundTag root;
        try {
            root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        } catch (IOException | RuntimeException e) {
            // Truncated gzip / malformed NBT is a corrupt backup, not a host I/O failure.
            return BackupVerifyResult.failure(BackupVerifyResult.Status.INVALID_NBT);
        }
        if (root == null || !(root.get("data") instanceof CompoundTag data)) {
            return BackupVerifyResult.failure(BackupVerifyResult.Status.INVALID_NBT);
        }
        int rawSchema = data.contains("schemaVersion")
                ? data.getIntOr("schemaVersion", SchemaVersionLogic.MALFORMED_SCHEMA_VERSION)
                : SchemaVersionLogic.ABSENT_SCHEMA_VERSION;
        SchemaVersionLogic.LoadResult schema = SchemaVersionLogic.fromStoredVersion(rawSchema);
        if (schema.status() != SchemaVersionLogic.Status.VALID
                && schema.status() != SchemaVersionLogic.Status.MISSING) {
            // Future (or malformed) schema: never restore or partially apply such a backup.
            return BackupVerifyResult.failure(BackupVerifyResult.Status.INCOMPATIBLE_SCHEMA);
        }
        try {
            DynamicOps<Tag> ops = registries != null
                    ? registries.createSerializationContext(NbtOps.INSTANCE) : NbtOps.INSTANCE;
            MilestoneData snapshot = MilestoneData.TYPE.codecFactory().create(null)
                    .parse(ops, data).getOrThrow();
            return BackupVerifyResult.ok(snapshot.allPlayers().size(), snapshot.worldFirsts().size(),
                    schema.retainedVersion());
        } catch (RuntimeException e) {
            return BackupVerifyResult.failure(BackupVerifyResult.Status.INVALID_NBT);
        }
    }
}
