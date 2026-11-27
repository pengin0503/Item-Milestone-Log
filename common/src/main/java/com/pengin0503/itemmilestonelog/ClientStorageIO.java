package com.pengin0503.itemmilestonelog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;

/**
 * Pure-Java file and identity helpers for the 1.5.0 step 6 local client foundation
 * (spec-150-client.md §1.4). Every client-side file lives under
 * {@code <configDir>/itemmilestonelog-client/}, is written through a temporary file plus
 * {@link Files#move(Path, Path, java.nio.file.CopyOption...)} with {@code ATOMIC_MOVE}, and is
 * keyed by a hash of the connection address and world name rather than by the raw address.
 *
 * <p>Nothing in this class writes to the server, SavedData, or a version-specific compat class.
 * Must stay Java 17 compatible and free of Minecraft/NeoForge dependencies so the same bytes
 * compile into all five version trees.
 */
public final class ClientStorageIO {
    /** Directory (below the Minecraft config dir) that owns all client-local JSON files. */
    public static final String CLIENT_DIR = "itemmilestonelog-client";

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private ClientStorageIO() {
    }

    /** Resolves the client-local directory under {@code configDir}. */
    public static Path clientDir(Path configDir) {
        Objects.requireNonNull(configDir, "configDir");
        return configDir.resolve(CLIENT_DIR);
    }

    /**
     * Builds an opaque, stable server identifier from the connection address and world name.
     * The raw address / IP / URL and world name are never persisted in a file name or file body.
     */
    public static String serverKey(String address, String worldName) {
        String normalized = (address == null ? "" : address.trim().toLowerCase(Locale.ROOT))
                + "\u0000"
                + (worldName == null ? "" : worldName.trim());
        return sha256(normalized);
    }

    /** Returns whether {@code value} looks like a server key produced by {@link #serverKey}. */
    public static boolean isServerKey(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by every supported Java runtime; this cannot be reached in practice.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Reads a UTF-8 file, returning {@code null} when it is missing or unreadable. Callers use
     * {@code null} as the lenient "no local data" signal instead of propagating an exception.
     */
    public static String readOrNull(Path file) {
        if (file == null) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Writes UTF-8 text to {@code file} through a temporary file in the same directory and an
     * atomic move with replace. The temporary file is removed on every failure path. If the
     * filesystem cannot do an atomic move, the write still completes with a plain replace so a
     * local cache never blocks the game (the trade-off is recorded in the progress file).
     */
    public static void atomicWrite(Path file, String content) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(content, "content");
        Path normalized = file.toAbsolutePath().normalize();
        Path dir = normalized.getParent();
        if (dir == null) {
            throw new IOException("No parent directory for " + file);
        }
        Files.createDirectories(dir);
        Path temp = dir.resolve(normalized.getFileName().toString() + ".tmp-" + Thread.currentThread().getId());
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, normalized, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, normalized, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // The atomic move already consumed the temporary file; nothing to clean up.
            }
        }
    }
}
