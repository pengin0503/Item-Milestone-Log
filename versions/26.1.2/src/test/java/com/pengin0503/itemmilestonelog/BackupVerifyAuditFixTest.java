package com.pengin0503.itemmilestonelog;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MN-2/MN-3/MN-4: backup verify must distinguish I/O, corrupt data and lenient skips. */
class BackupVerifyAuditFixTest {
    private static CompoundTag backupRoot(int schemaVersion) {
        CompoundTag root = new CompoundTag();
        CompoundTag data = new CompoundTag();
        data.putInt("schemaVersion", schemaVersion);
        root.put("data", data);
        return root;
    }

    private static void write(Path file, CompoundTag root) throws Exception {
        NbtIo.writeCompressed(root, file);
    }

    @Test
    void genuineReadFailureIsIoError(@TempDir Path dir) throws Exception {
        // A directory cannot be opened for reading; this is host I/O, not invalid NBT.
        BackupVerifyResult result = BackupVerify.verifyFile(dir, null);
        assertEquals(BackupVerifyResult.Status.IO_ERROR, result.status());
    }

    @Test
    void corruptGzipIsInvalidNbt(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("corrupt.dat");
        Files.writeString(file, "not a gzip stream", StandardCharsets.UTF_8);
        BackupVerifyResult result = BackupVerify.verifyFile(file, null);
        assertEquals(BackupVerifyResult.Status.INVALID_NBT, result.status());
    }

    @Test
    void silentlySkippedElementsAreNotReportedOk(@TempDir Path dir) throws Exception {
        CompoundTag root = backupRoot(SchemaVersionLogic.CURRENT_SCHEMA_VERSION);
        CompoundTag data = root.getCompound("data").orElseThrow();
        CompoundTag players = new CompoundTag();
        // Not a UUID: every loader records an element skip for this player entry.
        players.put("not-a-uuid", new CompoundTag());
        data.put("players", players);
        Path file = dir.resolve("skipped.dat");
        write(file, root);
        BackupVerifyResult result = BackupVerify.verifyFile(file, null);
        assertEquals(BackupVerifyResult.Status.INVALID_NBT, result.status(),
                "a backup whose codec parse skipped an element must not verify as OK");
    }

    @Test
    void anyNumericSchemaVersionIsAccepted(@TempDir Path dir) throws Exception {
        CompoundTag root = backupRoot(SchemaVersionLogic.CURRENT_SCHEMA_VERSION);
        root.getCompound("data").orElseThrow().putLong("schemaVersion", SchemaVersionLogic.CURRENT_SCHEMA_VERSION);
        Path file = dir.resolve("long-schema.dat");
        write(file, root);
        BackupVerifyResult result = BackupVerify.verifyFile(file, null);
        assertEquals(BackupVerifyResult.Status.OK, result.status(),
                "a LongTag schemaVersion must verify like an IntTag in every tree");
        assertTrue(result.schemaVersion() == SchemaVersionLogic.CURRENT_SCHEMA_VERSION);
    }
}
