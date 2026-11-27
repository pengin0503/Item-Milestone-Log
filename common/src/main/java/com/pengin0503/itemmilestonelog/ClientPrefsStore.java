package com.pengin0503.itemmilestonelog;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * F-150-77 (N122): the single local preference needed by the one-time unbound-key hint.
 *
 * <p>Stored at {@code <configDir>/itemmilestonelog-client/prefs.json}. Reads are lenient (missing,
 * corrupt or wrongly shaped files mean "not shown yet"); writes go through
 * {@link ClientStorageIO#atomicWrite}. Nothing here touches SavedData or sends data to a server.
 */
public final class ClientPrefsStore {
    /** File name inside {@link ClientStorageIO#CLIENT_DIR}. */
    public static final String FILE_NAME = "prefs.json";
    private static final String KEY = "unboundHintShown";

    private ClientPrefsStore() {
    }

    /** Resolves the preference file below {@code configDir}. */
    public static Path fileFor(Path configDir) {
        Objects.requireNonNull(configDir, "configDir");
        return ClientStorageIO.clientDir(configDir).resolve(FILE_NAME);
    }

    /** Returns whether the unbound-key hint has already been shown on this client. */
    public static boolean unboundHintShown(Path configDir) {
        String raw = ClientStorageIO.readOrNull(fileFor(configDir));
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) {
                return false;
            }
            JsonElement value = parsed.getAsJsonObject().get(KEY);
            return value != null && value.isJsonPrimitive() && value.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Records that the hint was shown. Returns {@code false} without throwing when the local file
     * cannot be written, so display data can never interrupt gameplay.
     */
    public static boolean markUnboundHintShown(Path configDir) {
        try {
            ClientStorageIO.atomicWrite(fileFor(configDir), "{\"" + KEY + "\":true}\n");
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
