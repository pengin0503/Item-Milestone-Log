package com.pengin0503.itemmilestonelog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Pure-Java client-side export for F-150-65 (N26). The only input is the PR-1 local cache
 * snapshot (or an equivalent snapshot supplied by the caller); the server export writer,
 * SavedData and the server's player data are never read or written.
 *
 * <p>Column order matches {@link CsvExportRenderer#HEADER_COLUMNS} and the JSON keys match
 * {@code MilestoneExport}'s single-player shape, but player identity columns are deliberately
 * empty: only the player's own records are exported (D-150-192). Output stays under
 * {@link ClientStorageIO#CLIENT_DIR} and is written atomically through {@link ClientStorageIO}.
 *
 * <p>Must stay Java 17 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class ClientExportLogic {
    /** Subdirectory used when {@code clientExportDir} is empty. */
    public static final String DEFAULT_DIR = "exports";
    /** Hard cap on lines/records in one export (F-150-65 item 4). */
    public static final int MAX_LINES = 4096;
    /** JSON format version, kept in step with the server export reader. */
    public static final int FORMAT_VERSION = 1;

    private static final Pattern SAFE_ITEM_ID =
            Pattern.compile("^[a-z0-9_]+:[a-z0-9_/.-]{1,255}$");
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss", Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);

    private ClientExportLogic() {
    }

    /** Result of one export attempt. */
    public record Result(Path file, int lines, boolean offline, boolean written) {
    }

    /**
     * Resolves the configured export directory. It must be a relative path below the client-local
     * directory: absolute paths, {@code ..} segments and paths escaping {@code itemmilestonelog-client}
     * are rejected. An empty value selects {@code exports/}.
     */
    public static Path resolveExportDir(Path configDir, String configured) throws IOException {
        Objects.requireNonNull(configDir, "configDir");
        Path root = ClientStorageIO.clientDir(configDir).toAbsolutePath().normalize();
        if (configured == null || configured.isBlank()) {
            return root.resolve(DEFAULT_DIR).normalize();
        }
        String raw = configured.trim();
        if (raw.indexOf('\u0000') >= 0 || raw.contains("\\") || raw.contains("://")) {
            throw new IOException("Invalid client export directory");
        }
        Path relative;
        try {
            relative = Path.of(raw);
        } catch (RuntimeException e) {
            throw new IOException("Invalid client export directory", e);
        }
        if (relative.isAbsolute()) {
            throw new IOException("Client export directory must be relative to the config dir");
        }
        for (Path part : relative) {
            String name = part.toString();
            if (name.equals("..") || name.equals(".") || name.isBlank()) {
                throw new IOException("Client export directory may not contain '.' or '..'");
            }
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IOException("Client export directory escapes the client-local area");
        }
        return resolved;
    }

    /**
     * True for any URL-style {@code scheme://} or {@code env:} reference, compared
     * case-insensitively. Wire-provided ids are untrusted, so lowercase variants must be rejected
     * as well as uppercase ones (MN-1 / MN-2).
     */
    public static boolean hasUnsafeScheme(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("://") || lower.contains("env:");
    }

    /** Returns whether {@code itemId} is a canonical item id that is safe to write to a file. */
    public static boolean isSafeItemId(String itemId) {
        return itemId != null && SAFE_ITEM_ID.matcher(itemId).matches()
                && !hasUnsafeScheme(itemId);
    }

    /** Chooses a collision-free name; multiple exports in the same second get -1, -2, ... . */
    public static String chooseFileName(Instant instant, ZoneId zone, String format,
                                        Predicate<String> exists) {
        Objects.requireNonNull(instant, "instant");
        Objects.requireNonNull(exists, "exists");
        String extension = "json".equalsIgnoreCase(format) ? "json" : "csv";
        String base = "client-" + TIMESTAMP_FORMATTER.format(
                instant.atZone(zone == null ? ZoneId.systemDefault() : zone));
        String candidate = base + "." + extension;
        if (!exists.test(candidate)) {
            return candidate;
        }
        for (int suffix = 1; suffix < Integer.MAX_VALUE; suffix++) {
            candidate = base + "-" + suffix + "." + extension;
            if (!exists.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Exhausted client export suffixes for " + base);
    }

    /** Renders the cache snapshot in the server's single-player JSON key order, minus identity. */
    public static String renderJson(LocalSyncCache.Snapshot snapshot, boolean offline) {
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT_VERSION);
        if (offline) {
            root.addProperty("offline", true);
        }
        LocalSyncCache.Snapshot safe = sanitize(snapshot);
        JsonArray targets = new JsonArray();
        int targetCount = 0;
        for (LocalSyncCache.CachedTarget target : safe.targets()) {
            if (targetCount++ >= MAX_LINES) {
                break;
            }
            JsonObject item = new JsonObject();
            if (target.count() > 1) {
                item.addProperty("item", target.itemId());
                item.addProperty("count", target.count());
            } else {
                targets.add(target.itemId());
                continue;
            }
            targets.add(item);
        }
        root.add("targets", targets);
        JsonObject records = new JsonObject();
        int recordCount = 0;
        for (LocalSyncCache.CachedRecord record : safe.records()) {
            if (recordCount++ >= MAX_LINES) {
                break;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("ticks", Math.max(0, record.playTicks()));
            entry.addProperty("day", record.day());
            entry.addProperty("epoch", record.epochMillis());
            if (record.count() > 1) {
                entry.addProperty("count", record.count());
            }
            records.add(encoded(record.itemId(), record.count()), entry);
        }
        root.add("records", records);
        return root.toString();
    }

    /** Renders the snapshot with the exact F26 CSV header and empty identity columns. */
    public static String renderCsv(LocalSyncCache.Snapshot snapshot, boolean offline) {
        StringBuilder out = new StringBuilder(256);
        out.append(CsvExportRenderer.UTF8_BOM);
        int lines = 0;
        if (offline) {
            out.append("# offline: true").append(CsvExportRenderer.CRLF);
            lines++;
        }
        out.append(CsvExportRenderer.HEADER_LINE).append(CsvExportRenderer.CRLF);
        lines++;
        for (LocalSyncCache.CachedRecord record : sanitize(snapshot).records()) {
            if (lines >= MAX_LINES) {
                break;
            }
            CsvExportRenderer.Row row = new CsvExportRenderer.Row(
                    "", "", record.itemId(), Math.max(1, record.count()), "",
                    Math.max(0, record.playTicks()), "", record.day(),
                    record.epochMillis() > 0 ? record.epochMillis() : null, "", false);
            out.append(CsvExportRenderer.formatRow(row)).append(CsvExportRenderer.CRLF);
            lines++;
        }
        return out.toString();
    }

    /** Writes one export atomically below the configured (or default) client-local directory. */
    public static Result export(Path configDir, String configuredDir, String format,
                                LocalSyncCache.Snapshot snapshot, boolean offline,
                                Instant now, ZoneId zone) throws IOException {
        Objects.requireNonNull(configDir, "configDir");
        Objects.requireNonNull(snapshot, "snapshot");
        Path dir = resolveExportDir(configDir, configuredDir);
        boolean json = !"csv".equalsIgnoreCase(format);
        if (!json && !"csv".equalsIgnoreCase(format)) {
            throw new IOException("Unsupported export format: " + format);
        }
        String contents = json ? renderJson(snapshot, offline) : renderCsv(snapshot, offline);
        String name = chooseFileName(now, zone, json ? "json" : "csv",
                candidate -> Files.exists(dir.resolve(candidate)));
        Path target = dir.resolve(name).normalize();
        if (!target.getParent().equals(dir)) {
            throw new IOException("Invalid client export target path");
        }
        ClientStorageIO.atomicWrite(target, contents);
        return new Result(target, countLines(contents), offline, true);
    }

    /** Number of rendered lines, used by the cap and by tests. */
    public static int countLines(String contents) {
        if (contents == null || contents.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while (index < contents.length()) {
            char c = contents.charAt(index++);
            if (c == '\n') {
                count++;
            } else if (c == '\r') {
                if (index < contents.length() && contents.charAt(index) == '\n') {
                    index++;
                }
                count++;
            }
        }
        return count;
    }

    private static String encoded(String itemId, int count) {
        return count > 1 ? itemId + " x " + count : itemId;
    }

    /** Drops unsafe ids and caps lists to the export line limit (oldest entries win). */
    private static LocalSyncCache.Snapshot sanitize(LocalSyncCache.Snapshot snapshot) {
        if (snapshot == null) {
            return LocalSyncCache.empty("");
        }
        List<LocalSyncCache.CachedTarget> targets = new ArrayList<>();
        for (LocalSyncCache.CachedTarget target : snapshot.targets()) {
            if (isSafeItemId(target.itemId())) {
                targets.add(target);
            }
        }
        List<LocalSyncCache.CachedRecord> records = new ArrayList<>();
        for (LocalSyncCache.CachedRecord record : snapshot.records()) {
            if (isSafeItemId(record.itemId())) {
                records.add(record);
            }
        }
        return new LocalSyncCache.Snapshot(
                targets, records, List.of(), snapshot.currentPlaytimeTicks(), List.of(),
                false, false, snapshot.serverKey(), snapshot.savedAtMillis(), snapshot.offline());
    }
}
