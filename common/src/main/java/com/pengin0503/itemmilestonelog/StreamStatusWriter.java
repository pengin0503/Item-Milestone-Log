package com.pengin0503.itemmilestonelog;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java OBS status writer for F-150-66 (N27) and F-150-83 (N196). Writes only two files
 * under {@link ClientStorageIO#CLIENT_DIR}: {@code stream/status.txt} (language-neutral
 * {@code key: value} lines for OBS polling) and {@code stream/status.json}.
 *
 * <p>The original five keys are never renamed or reordered; F-150-83 appends exactly two lines.
 * No player name, UUID, webhook URL, {@code env:} value, world name or address is ever written;
 * the only free-form values are canonical item/preset ids that pass {@link #isSafeValue}.
 * Writes go through {@link ClientStorageIO#atomicWrite}; callers handle the one-second debounce
 * and same-value skip with {@link #shouldWrite}.
 *
 * <p>Must stay Java 17 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class StreamStatusWriter {
    /** Subdirectory inside the client-local area. */
    public static final String STREAM_DIR = "stream";
    public static final String TXT_FILE = "status.txt";
    public static final String JSON_FILE = "status.json";
    /** Debounce window for status updates (F-150-66 / D-150-194). */
    public static final long DEBOUNCE_MILLIS = 1000L;
    /** Mirrors {@code TargetKey.MAX_COUNT} without depending on a version tree. */
    public static final int MAX_RECORD_COUNT = 1_000_000;
    /** Canonical id, optional tag prefix and optional {@code #count} suffix (F-150-66-1). */
    private static final Pattern SAFE_VALUE =
            Pattern.compile("^(#?[a-z0-9_]+:[a-z0-9_/.-]{1,255})(?:#([0-9]{1,7}))?$");

    /** Stable, untranslated key order. The first five are the F-150-66 interface. */
    public static final List<String> KEYS = List.of(
            "records", "targets", "last_record", "playtime_ticks", "offline",
            "current_preset", "world_first_count");

    private StreamStatusWriter() {
    }

    /** Sanitized values for one status snapshot. Numeric fields are clamped to non-negative. */
    public record Values(int records, int targets, String lastRecord, int playtimeTicks,
                         boolean offline, String currentPreset, int worldFirstCount) {

        public Values {
            records = Math.max(0, records);
            targets = Math.max(0, targets);
            playtimeTicks = Math.max(0, playtimeTicks);
            worldFirstCount = Math.max(0, worldFirstCount);
        }
    }

    /** Empty/offline values, used when no sync data is available. */
    public static Values offlineValues() {
        return new Values(0, 0, "", 0, true, "", 0);
    }

    /**
     * Accepts only a canonical id, an optional leading {@code #} tag and an optional bounded
     * {@code #count} suffix, so counted and tag achievements reach OBS (F-150-66-1 / SF-3).
     * URLs and {@code env:} references are rejected case-insensitively (MN-1); player names,
     * control characters and arbitrary text never match.
     */
    public static boolean isSafeValue(String value) {
        if (value == null || value.isEmpty() || value.length() > 256
                || ClientExportLogic.hasUnsafeScheme(value)) {
            return false;
        }
        Matcher matcher = SAFE_VALUE.matcher(value);
        if (!matcher.matches()) {
            return false;
        }
        String count = matcher.group(2);
        if (count == null) {
            return true;
        }
        try {
            int parsed = Integer.parseInt(count);
            return parsed >= 1 && parsed <= MAX_RECORD_COUNT;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Keeps only safe canonical ids; everything else becomes an empty value. */
    public static String sanitize(String value) {
        return isSafeValue(value) ? value : "";
    }

    /** Returns the status directory below {@code configDir}. */
    public static Path statusDir(Path configDir) {
        return ClientStorageIO.clientDir(configDir).resolve(STREAM_DIR);
    }

    public static Path txtFile(Path configDir) {
        return statusDir(configDir).resolve(TXT_FILE);
    }

    public static Path jsonFile(Path configDir) {
        return statusDir(configDir).resolve(JSON_FILE);
    }

    /**
     * Renders language-neutral {@code key: value} lines. With no sync data, only {@code offline}
     * has a value and the other six lines are empty, so an OBS text source can still parse them.
     */
    public static String renderTxt(Values values) {
        Objects.requireNonNull(values, "values");
        String records = values.offline() ? "" : Integer.toString(values.records());
        String targets = values.offline() ? "" : Integer.toString(values.targets());
        String last = values.offline() ? "" : sanitize(values.lastRecord());
        String playtime = values.offline() ? "" : Integer.toString(values.playtimeTicks());
        String preset = values.offline() ? "" : sanitize(values.currentPreset());
        String worldFirsts = values.offline() ? "" : Integer.toString(values.worldFirstCount());
        return "records: " + records + "\n"
                + "targets: " + targets + "\n"
                + "last_record: " + last + "\n"
                + "playtime_ticks: " + playtime + "\n"
                + "offline: " + values.offline() + "\n"
                + "current_preset: " + preset + "\n"
                + "world_first_count: " + worldFirsts + "\n";
    }

    /** Renders the same seven keys as a JSON object for OBS widget values. */
    public static String renderJson(Values values) {
        Objects.requireNonNull(values, "values");
        JsonObject root = new JsonObject();
        if (values.offline()) {
            root.addProperty("records", "");
            root.addProperty("targets", "");
            root.addProperty("last_record", "");
            root.addProperty("playtime_ticks", "");
            root.addProperty("offline", true);
            root.addProperty("current_preset", "");
            root.addProperty("world_first_count", "");
            return root.toString();
        }
        root.addProperty("records", values.records());
        root.addProperty("targets", values.targets());
        root.addProperty("last_record", sanitize(values.lastRecord()));
        root.addProperty("playtime_ticks", values.playtimeTicks());
        root.addProperty("offline", false);
        root.addProperty("current_preset", sanitize(values.currentPreset()));
        root.addProperty("world_first_count", values.worldFirstCount());
        return root.toString();
    }

    /** True once the debounce window has elapsed; a backwards clock is treated as due. */
    public static boolean writeDue(long lastWriteMillis, long nowMillis) {
        return nowMillis < lastWriteMillis || nowMillis - lastWriteMillis >= DEBOUNCE_MILLIS;
    }

    /** True when the debounce has elapsed AND the rendered text changed (same-value skip). */
    public static boolean shouldWrite(long lastWriteMillis, long nowMillis,
                                      String previousTxt, String nextTxt) {
        return shouldWrite(lastWriteMillis, nowMillis, previousTxt, nextTxt, DEBOUNCE_MILLIS);
    }

    /**
     * F-150-86: additive overload so the low-power policy can lengthen the debounce without
     * changing the shipped signature or the five stable status keys. The rendered text is
     * identical; only the write cadence changes.
     */
    public static boolean shouldWrite(long lastWriteMillis, long nowMillis,
                                      String previousTxt, String nextTxt, long debounceMillis) {
        boolean due = nowMillis < lastWriteMillis || nowMillis - lastWriteMillis >= debounceMillis;
        return due && !Objects.equals(previousTxt == null ? "" : previousTxt,
                nextTxt == null ? "" : nextTxt);
    }

    /** Writes both status files atomically. IO errors are reported to the caller, never swallowed. */
    public static void write(Path configDir, Values values) throws IOException {
        Objects.requireNonNull(configDir, "configDir");
        String txt = renderTxt(values);
        String json = renderJson(values);
        ClientStorageIO.atomicWrite(txtFile(configDir), txt);
        ClientStorageIO.atomicWrite(jsonFile(configDir), json);
    }
}
