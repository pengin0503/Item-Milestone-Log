package com.pengin0503.itemmilestonelog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Pure-Java generation rotation for F-150-67 (N119): copies
 * {@code <configDir>/itemmilestonelog-client/cache.json} into
 * {@code <configDir>/itemmilestonelog-client/backups/cache-<timestamp>.json} and prunes only files
 * that match {@link #BACKUP_FILE_PATTERN}. The file-name rules reuse the conventions of
 * {@link MilestoneBackupLogic}, but the server backup writer and SavedData are never touched.
 *
 * <p>A failed write is reported through {@link Result#warnings()} and never thrown, so a client
 * backup cannot stop gameplay. Must stay Java 17 compatible and free of Minecraft dependencies.
 */
public final class ClientBackupRotation {
    /** Directory (inside the client-local dir) that owns backup generations. */
    public static final String BACKUP_DIR = "backups";
    /** Exact file-name pattern owned by the client cache backups. */
    public static final Pattern BACKUP_FILE_PATTERN =
            Pattern.compile("^cache-(\\d{8})-(\\d{6})(?:-(\\d+))?\\.json$");
    /** Default number of generations (F-150-67: 1..10, default 3). */
    public static final int DEFAULT_GENERATIONS = 3;
    public static final int MIN_GENERATIONS = 1;
    public static final int MAX_GENERATIONS = 10;

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss", Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);
    /** Chronological ascending order, including same-second {@code -N} suffixes. */
    public static final Comparator<String> BACKUP_NAME_ORDER = ClientBackupRotation::compareNames;

    private ClientBackupRotation() {
    }

    /** Result of one rotation attempt; {@code written} is false when there was no cache or IO failed. */
    public record Result(Path backupFile, boolean written, int retained, List<String> warnings) {
        public Result {
            warnings = List.copyOf(warnings);
        }
    }

    /** Returns whether {@code fileName} is a cache backup this rotation owns. */
    public static boolean isBackupFileName(String fileName) {
        return fileName != null && BACKUP_FILE_PATTERN.matcher(fileName).matches();
    }

    /** Clamps the configured generation count to the allowed 1..10 range. */
    public static int clampGenerations(int generations) {
        return Math.max(MIN_GENERATIONS, Math.min(MAX_GENERATIONS, generations));
    }

    /** Formats the collision-free backup name for {@code instant}, using {@code -1}, {@code -2}, … per second. */
    public static String chooseFileName(Instant instant, ZoneId zone, Predicate<String> exists) {
        Objects.requireNonNull(instant, "instant");
        Objects.requireNonNull(exists, "exists");
        String base = "cache-" + TIMESTAMP_FORMATTER.format(instant.atZone(zone == null ? ZoneId.systemDefault() : zone));
        String candidate = base + ".json";
        if (!exists.test(candidate)) {
            return candidate;
        }
        for (int suffix = 1; suffix < Integer.MAX_VALUE; suffix++) {
            candidate = base + "-" + suffix + ".json";
            if (!exists.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Exhausted client backup suffixes for " + base);
    }

    /**
     * Selects the oldest matching names to delete so that at most {@code generations} remain.
     * Foreign files are never counted or returned (so other mods' files are left alone).
     */
    public static List<String> selectFilesToPrune(Collection<String> fileNames, int generations) {
        if (fileNames == null || fileNames.isEmpty()) {
            return List.of();
        }
        int keep = clampGenerations(generations);
        List<String> matching = new ArrayList<>();
        for (String name : fileNames) {
            if (isBackupFileName(name)) {
                matching.add(name);
            }
        }
        if (matching.size() <= keep) {
            return List.of();
        }
        matching.sort(BACKUP_NAME_ORDER);
        return List.copyOf(matching.subList(0, matching.size() - keep));
    }

    /** Parses the encoded timestamp of a matching name, or a value that sorts before all valid ones. */
    private static long timestampMillis(String fileName, ZoneId zone) {
        Matcher matcher = BACKUP_FILE_PATTERN.matcher(fileName == null ? "" : fileName);
        if (!matcher.matches()) {
            return Long.MIN_VALUE;
        }
        try {
            LocalDateTime parsed = LocalDateTime.parse(matcher.group(1) + "-" + matcher.group(2), TIMESTAMP_FORMATTER);
            return parsed.atZone(zone == null ? ZoneId.systemDefault() : zone).toInstant().toEpochMilli();
        } catch (DateTimeException e) {
            return Long.MIN_VALUE;
        }
    }

    private static int compareNames(String a, String b) {
        if (Objects.equals(a, b)) {
            return 0;
        }
        long timeA = timestampMillis(a, null);
        long timeB = timestampMillis(b, null);
        if (timeA != timeB) {
            return Long.compare(timeA, timeB);
        }
        Matcher ma = BACKUP_FILE_PATTERN.matcher(a == null ? "" : a);
        Matcher mb = BACKUP_FILE_PATTERN.matcher(b == null ? "" : b);
        int suffixA = suffix(ma);
        int suffixB = suffix(mb);
        if (suffixA != suffixB) {
            return Integer.compare(suffixA, suffixB);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    private static int suffix(Matcher matcher) {
        if (!matcher.matches() || matcher.group(3) == null) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(3));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** Resolves the backup directory below {@code configDir}. */
    public static Path backupDir(Path configDir) {
        return ClientStorageIO.clientDir(configDir).resolve(BACKUP_DIR);
    }

    /**
     * Rotates the current cache into one backup and prunes old generations. A missing cache is a
     * silent no-op; all IO errors are accumulated as warnings and returned rather than thrown.
     */
    public static Result rotate(Path configDir, Instant now, ZoneId zone, int generations) {
        Objects.requireNonNull(configDir, "configDir");
        Objects.requireNonNull(now, "now");
        List<String> warnings = new ArrayList<>();
        Path cache = LocalSyncCache.fileFor(configDir);
        String contents = ClientStorageIO.readOrNull(cache);
        if (contents == null) {
            return new Result(null, false, 0, warnings);
        }
        Path dir = backupDir(configDir);
        String name;
        try {
            List<String> existing = listNames(dir, warnings);
            if (existing == null) {
                return new Result(null, false, -1, warnings);
            }
            name = chooseFileName(now, zone, existing::contains);
            Path target = dir.resolve(name);
            if (!dir.normalize().equals(target.normalize().getParent())) {
                warnings.add("refusing backup target outside backup directory");
                return new Result(null, false, -1, warnings);
            }
            ClientStorageIO.atomicWrite(target, contents);
        } catch (Exception e) {
            warnings.add("client backup write failed: " + e.getMessage());
            return new Result(null, false, -1, warnings);
        }

        int retained = pruneAndCount(dir, generations, warnings);
        return new Result(dir.resolve(name), true, retained, warnings);
    }

    private static int pruneAndCount(Path dir, int generations, List<String> warnings) {
        List<String> names = listNames(dir, warnings);
        if (names == null) {
            return -1;
        }
        for (String old : selectFilesToPrune(names, generations)) {
            try {
                Files.deleteIfExists(dir.resolve(old));
            } catch (IOException | RuntimeException e) {
                warnings.add("could not prune client backup " + old + ": " + e.getMessage());
            }
        }
        List<String> after = listNames(dir, warnings);
        if (after == null) {
            return -1;
        }
        int count = 0;
        for (String name : after) {
            if (isBackupFileName(name)) {
                count++;
            }
        }
        return count;
    }

    private static List<String> listNames(Path dir, List<String> warnings) {
        if (!Files.isDirectory(dir)) {
            return new ArrayList<>();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<String> names = new ArrayList<>();
            for (Path path : stream.toList()) {
                names.add(path.getFileName().toString());
            }
            return names;
        } catch (IOException | RuntimeException e) {
            warnings.add("could not list client backup directory: " + e.getMessage());
            return null;
        }
    }
}
