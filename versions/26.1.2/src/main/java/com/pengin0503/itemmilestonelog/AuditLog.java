package com.pengin0503.itemmilestonelog;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * F-150-57 (N90): thin version-side writer for the structured admin audit log.
 *
 * <p>The record format and the secret rules are defined once in {@link AuditEvent} and in
 * {@code spec-150-operations.md} §1.3. This class only owns file access: it appends one JSONL line
 * to {@code <world>/itemmilestonelog/audit.log}, rotating at 1 MiB to five numbered generations.
 * Writes are synchronous and stay on the calling (server) thread; no {@code TaskKind} is added and
 * the I/O executor / stop drain is never involved (D-150-164).
 *
 * <p>An {@link IOException} or {@link RuntimeException} is caught here, logged once to the server
 * log and never rethrown, so an audit failure cannot fail, roll back or dirty an operation
 * (D-150-163). The warn intentionally uses only the exception's simple class name, never its
 * message.
 */
public final class AuditLog {
    /** File name directly under {@code <world>/itemmilestonelog/}. */
    public static final String FILE_NAME = "audit.log";
    /** Rotate before appending once the current file has reached 1 MiB. */
    public static final long MAX_FILE_BYTES = 1024L * 1024L;
    /** Kept generations: audit.1.log .. audit.5.log, alongside the current file. */
    public static final int MAX_GENERATIONS = 5;

    /** Production sink; tests install a throwing sink to prove hooks stay non-destructive. */
    @Nullable
    private static Consumer<String> sinkForTest;

    private AuditLog() {
    }

    /** Appends one record for a server. Null sources/events are ignored without side effects. */
    public static void append(@Nullable MinecraftServer server, AuditEvent event) {
        if (server == null || event == null) {
            return;
        }
        append(MilestoneBackup.backupsDir(server).getParent(), event);
    }

    /**
     * Convenience for command handlers: extracts the actor UUID from the source (or uses
     * {@code #nonplayer}) and records one successful operation on the server thread.
     */
    public static void record(@Nullable CommandSourceStack source, AuditEvent.Operation op,
                              @Nullable String target, Map<String, ?> detail) {
        if (source == null) {
            return;
        }
        ServerPlayer player = source.getPlayer();
        append(source.getServer(), AuditEvent.of(op,
                player == null ? null : player.getUUID(), target, detail));
    }

    /** Convenience for server-thread hooks that already hold an actor UUID (async import handoff). */
    public static void record(@Nullable MinecraftServer server, @Nullable UUID actorUuid,
                              AuditEvent.Operation op, @Nullable String target,
                              Map<String, ?> detail) {
        append(server, AuditEvent.of(op, actorUuid, target, detail));
    }

    /** Appends one record into an explicit directory (used by the version-side tests). */
    public static void append(Path dir, AuditEvent event) {
        try {
            write(dir, event.toJsonLine() + "\n");
        } catch (IOException | RuntimeException e) {
            ItemMilestoneLog.LOGGER.warn(
                    "[ItemMilestoneLog] Could not write audit log ({}); operation left unchanged",
                    DiscordTestResult.safeExceptionName(e));
        }
    }

    /** Rotation + append. Kept separate so tests can verify the byte-level boundary. */
    static void write(Path dir, String line) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(FILE_NAME);
        if (Files.exists(file) && Files.size(file) >= MAX_FILE_BYTES) {
            rotate(file, dir);
        }
        Consumer<String> sink = sinkForTest;
        if (sink != null) {
            sink.accept(line);
        } else {
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    private static void rotate(Path file, Path dir) throws IOException {
        Files.deleteIfExists(dir.resolve(FILE_NAME + "." + MAX_GENERATIONS + ".log"));
        for (int generation = MAX_GENERATIONS - 1; generation >= 1; generation--) {
            Path source = dir.resolve(FILE_NAME + "." + generation + ".log");
            if (Files.exists(source)) {
                moveReplace(source, dir.resolve(FILE_NAME + "." + (generation + 1) + ".log"));
            }
        }
        moveReplace(file, dir.resolve(FILE_NAME + ".1.log"));
    }

    /** Atomic move with the same fallback F10 uses for filesystems without atomic move. */
    private static void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Test seam. Reset to {@code null} in a finally block so production never sees a fake sink. */
    public static void setSinkForTest(@Nullable Consumer<String> sink) {
        sinkForTest = sink;
    }

    /** Returns the current test sink, or {@code null} in production. */
    @Nullable
    public static Consumer<String> sinkForTest() {
        return sinkForTest;
    }
}
