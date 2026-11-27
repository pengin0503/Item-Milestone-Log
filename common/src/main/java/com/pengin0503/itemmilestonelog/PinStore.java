package com.pengin0503.itemmilestonelog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Pure-Java storage for F-150-62 (N113): the locally pinned milestone targets that feed the
 * HUD (F-150-63). Pins live in {@code <configDir>/itemmilestonelog-client/pins.json}, are capped
 * at {@value #MAX_PINS}, and are never sent to the server or written to SavedData (STALE-6).
 *
 * <p>IDs are normalised with the same rules as the version trees' {@code TargetKey}:
 * implicit {@code minecraft:} namespace, optional {@code #count} suffix (1..1,000,000), tag IDs
 * keep their leading {@code '#'}, and {@code minecraft:air} is rejected. The implementation is
 * deliberately free of Minecraft types so the same bytes compile in all five trees.
 *
 * <p>Reads are lenient (missing/corrupt JSON and malformed entries become empty data) and writes
 * go through {@link ClientStorageIO#atomicWrite(Path, String)}.
 */
public final class PinStore {
    /** File name inside {@link ClientStorageIO#CLIENT_DIR}. */
    public static final String FILE_NAME = "pins.json";
    /** Maximum number of pins (spec 1.4-3 and D-150-186). */
    public static final int MAX_PINS = 64;
    /** Mirrors {@code TargetKey.MAX_COUNT}. */
    public static final int MAX_COUNT = 1_000_000;
    private static final String VERSION = "1";

    private PinStore() {
    }

    /** One pin, identified by a normalised item/tag id and a positive count. */
    public record Pin(String itemId, int count) {
        public Pin {
            Objects.requireNonNull(itemId, "itemId");
        }

        /** Wire/storage form: bare id for count 1, otherwise {@code id#count}. */
        public String encoded() {
            return count == 1 ? itemId : itemId + "#" + count;
        }
    }

    /** Outcome of {@link #add}; no case changes server state. */
    public enum AddOutcome {
        ADDED,
        DUPLICATE,
        INVALID_ID,
        NOT_IN_SYNC,
        NO_SYNC
    }

    /** Result of an add: the outcome and how many oldest pins were evicted by the 64-pin cap. */
    public record AddResult(AddOutcome outcome, int evicted) {
    }

    /** Resolves the pin file below {@code configDir}. */
    public static Path fileFor(Path configDir) {
        return ClientStorageIO.clientDir(configDir).resolve(FILE_NAME);
    }

    /**
     * Normalises an encoded target string exactly as {@code TargetKey.tryParseNormalized(raw,
     * false, true)} does, returning {@code null} for invalid ids, counts and air.
     */
    public static Pin normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        int lastHash = trimmed.lastIndexOf('#');
        String rawId = trimmed;
        int count = 1;
        if (lastHash >= 1) {
            rawId = trimmed.substring(0, lastHash);
            String rawCount = trimmed.substring(lastHash + 1);
            if (rawCount.isEmpty()) {
                return null;
            }
            for (int i = 0; i < rawCount.length(); i++) {
                char c = rawCount.charAt(i);
                if (c < '0' || c > '9') {
                    return null;
                }
            }
            try {
                count = Integer.parseInt(rawCount);
            } catch (NumberFormatException e) {
                return null;
            }
            if (count < 1 || count > MAX_COUNT) {
                return null;
            }
        }
        String itemId = normalizeItemId(rawId);
        return itemId == null ? null : new Pin(itemId, count);
    }

    private static String normalizeItemId(String rawId) {
        boolean tag = rawId.startsWith("#");
        String body = tag ? rawId.substring(1) : rawId;
        if (body.isEmpty()) {
            return null;
        }
        int colon = body.indexOf(':');
        String namespace;
        String path;
        if (colon < 0) {
            if (tag) {
                return null;
            }
            namespace = "minecraft";
            path = body;
        } else {
            if (colon == 0 || colon == body.length() - 1) {
                return null;
            }
            namespace = body.substring(0, colon);
            path = body.substring(colon + 1);
        }
        if (!validNamespace(namespace) || !validPath(path)) {
            return null;
        }
        String normalized = (tag ? "#" : "") + namespace + ":" + path;
        return "minecraft:air".equals(normalized) ? null : normalized;
    }

    private static boolean validNamespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c == '_' || c == '-' || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.')) {
                return false;
            }
        }
        return !value.isEmpty();
    }

    private static boolean validPath(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c == '_' || c == '-' || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '/' || c == '.')) {
                return false;
            }
        }
        return !value.isEmpty();
    }

    /** Reads pins in insertion order; malformed files and entries are silently ignored. */
    public static List<Pin> read(Path configDir) {
        String raw = ClientStorageIO.readOrNull(fileFor(configDir));
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) {
                return List.of();
            }
            JsonElement pinsElement = parsed.getAsJsonObject().get("pins");
            if (pinsElement == null || !pinsElement.isJsonArray()) {
                return List.of();
            }
            Set<String> seen = new LinkedHashSet<>();
            List<Pin> pins = new ArrayList<>();
            for (JsonElement child : pinsElement.getAsJsonArray()) {
                if (!child.isJsonPrimitive() || !child.getAsJsonPrimitive().isString()) {
                    continue;
                }
                Pin pin = normalize(child.getAsString());
                if (pin != null && seen.add(pin.encoded())) {
                    pins.add(pin);
                    if (pins.size() >= MAX_PINS) {
                        break;
                    }
                }
            }
            return List.copyOf(pins);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Adds one pin from the currently synced target ids. {@code availableEncodedIds == null}
     * means there is no sync snapshot at all (vanilla/old server) and the operation is refused;
     * an empty set is a real, empty sync snapshot. At the cap the oldest pin is evicted (1.4-3).
     */
    public static AddResult add(Path configDir, String rawId, Set<String> availableEncodedIds) {
        Pin pin = normalize(rawId);
        if (pin == null) {
            return new AddResult(AddOutcome.INVALID_ID, 0);
        }
        if (availableEncodedIds == null) {
            return new AddResult(AddOutcome.NO_SYNC, 0);
        }
        if (!availableEncodedIds.contains(pin.encoded())) {
            return new AddResult(AddOutcome.NOT_IN_SYNC, 0);
        }
        List<Pin> pins = new ArrayList<>(read(configDir));
        if (pins.stream().anyMatch(existing -> existing.encoded().equals(pin.encoded()))) {
            return new AddResult(AddOutcome.DUPLICATE, 0);
        }
        pins.add(pin);
        int evicted = 0;
        while (pins.size() > MAX_PINS) {
            pins.remove(0);
            evicted++;
        }
        write(configDir, pins);
        return new AddResult(AddOutcome.ADDED, evicted);
    }

    /** Removes one pin; a malformed id or an unknown pin is a silent no-op. */
    public static boolean remove(Path configDir, String rawId) {
        Pin pin = normalize(rawId);
        if (pin == null) {
            return false;
        }
        List<Pin> pins = new ArrayList<>(read(configDir));
        if (!pins.removeIf(existing -> existing.encoded().equals(pin.encoded()))) {
            return false;
        }
        write(configDir, pins);
        return true;
    }

    /** Empties the local pin file (it does not delete other client-local files). */
    public static void clear(Path configDir) {
        Objects.requireNonNull(configDir, "configDir");
        write(configDir, List.of());
    }

    private static void write(Path configDir, List<Pin> pins) {
        JsonObject root = new JsonObject();
        root.addProperty("v", VERSION);
        JsonArray array = new JsonArray();
        for (Pin pin : pins) {
            array.add(pin.encoded());
        }
        root.add("pins", array);
        try {
            ClientStorageIO.atomicWrite(fileFor(configDir), root.toString());
        } catch (Exception e) {
            // A local display preference must never interrupt the game.
        }
    }
}
