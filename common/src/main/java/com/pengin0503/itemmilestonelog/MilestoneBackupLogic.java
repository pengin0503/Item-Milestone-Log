package com.pengin0503.itemmilestonelog;

import java.math.BigInteger;
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

/**
 * Pure-Java logic for F10 automatic SavedData backups, shared across all Minecraft versions via
 * {@code common/src/main/java}: file-name formatting, same-second collision suffixes, strict
 * file-name pattern matching, timestamp parsing, generation pruning selection, and rate limiting.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class MilestoneBackupLogic {
    /** Default value for {@code autoBackupGenerations}. */
    public static final int DEFAULT_GENERATIONS = 5;
    /** Minimum allowed value for {@code autoBackupGenerations}. */
    public static final int MIN_GENERATIONS = 1;
    /** Maximum allowed value for {@code autoBackupGenerations}. */
    public static final int MAX_GENERATIONS = 20;
    /** Minimum interval between automatic save-triggered backups (1 hour). */
    public static final long MIN_INTERVAL_MILLIS = 3_600_000L;
    /** Sentinel indicating no previous backup timestamp is known in memory. */
    public static final long NO_BACKUP_RECORDED = Long.MIN_VALUE;

    /**
     * Exact file-name pattern owned by F10 backups:
     * {@code milestones-YYYYMMDD-HHmmss.dat} or {@code milestones-YYYYMMDD-HHmmss-<N>.dat}.
     * Generation pruning only ever considers files matching this pattern.
     */
    public static final Pattern BACKUP_FILE_PATTERN =
            Pattern.compile("^milestones-(\\d{8})-(\\d{6})(?:-(\\d+))?\\.dat$");

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss", Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);

    /**
     * Chronological ascending comparator for backup file names matching {@link #BACKUP_FILE_PATTERN}.
     * Orders by date, then time, then numeric collision suffix (no suffix &lt; {@code -1} &lt;
     * {@code -2} &lt; {@code -10}).
     */
    public static final Comparator<String> BACKUP_NAME_ORDER = MilestoneBackupLogic::compareBackupFileNames;

    private MilestoneBackupLogic() {
    }

    /** Returns whether {@code fileName} matches {@link #BACKUP_FILE_PATTERN}. */
    public static boolean isBackupFileName(String fileName) {
        return fileName != null && BACKUP_FILE_PATTERN.matcher(fileName).matches();
    }

    /** Clamps {@code generations} to [{@link #MIN_GENERATIONS}, {@link #MAX_GENERATIONS}]. */
    public static int clampGenerations(int generations) {
        return Math.max(MIN_GENERATIONS, Math.min(MAX_GENERATIONS, generations));
    }

    /**
     * Formats the base backup file name (without {@code .dat}), e.g. {@code milestones-20261124-111530}.
     */
    public static String formatBaseName(Instant instant, ZoneId zone) {
        Objects.requireNonNull(instant, "instant");
        ZoneId effectiveZone = zone != null ? zone : ZoneId.systemDefault();
        return "milestones-" + TIMESTAMP_FORMATTER.format(instant.atZone(effectiveZone));
    }

    /**
     * Chooses a non-colliding backup file name for {@code instant}:
     * {@code milestones-YYYYMMDD-HHmmss.dat}, or {@code -1}, {@code -2}, ... on same-second collisions.
     */
    public static String chooseFileName(Instant instant, ZoneId zone, Predicate<String> exists) {
        Objects.requireNonNull(exists, "exists");
        String base = formatBaseName(instant, zone);
        String candidate = base + ".dat";
        if (!exists.test(candidate)) {
            return candidate;
        }
        for (int suffix = 1; suffix < Integer.MAX_VALUE; suffix++) {
            candidate = base + "-" + suffix + ".dat";
            if (!exists.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Exhausted backup file name suffixes for " + base);
    }

    /**
     * Parses the timestamp encoded in a matching backup file name into epoch milliseconds using
     * {@code zone}, or returns {@link #NO_BACKUP_RECORDED} if the name does not match
     * {@link #BACKUP_FILE_PATTERN} or contains an invalid calendar date/time.
     */
    public static long parseTimestampMillis(String fileName, ZoneId zone) {
        if (fileName == null) {
            return NO_BACKUP_RECORDED;
        }
        Matcher matcher = BACKUP_FILE_PATTERN.matcher(fileName);
        if (!matcher.matches()) {
            return NO_BACKUP_RECORDED;
        }
        ZoneId effectiveZone = zone != null ? zone : ZoneId.systemDefault();
        String rawTimestamp = matcher.group(1) + "-" + matcher.group(2);
        try {
            LocalDateTime ldt = LocalDateTime.parse(rawTimestamp, TIMESTAMP_FORMATTER);
            return ldt.atZone(effectiveZone).toInstant().toEpochMilli();
        } catch (DateTimeException ignored) {
            return NO_BACKUP_RECORDED;
        }
    }

    /**
     * Finds the newest valid backup timestamp among {@code fileNames}, ignoring non-matching names
     * and names with invalid calendar dates. Returns {@link #NO_BACKUP_RECORDED} if none exist.
     */
    public static long findNewestBackupTimeMillis(Collection<String> fileNames, ZoneId zone) {
        if (fileNames == null || fileNames.isEmpty()) {
            return NO_BACKUP_RECORDED;
        }
        long newest = NO_BACKUP_RECORDED;
        for (String name : fileNames) {
            long parsed = parseTimestampMillis(name, zone);
            if (parsed != NO_BACKUP_RECORDED && (newest == NO_BACKUP_RECORDED || parsed > newest)) {
                newest = parsed;
            }
        }
        return newest;
    }

    /**
     * Returns whether an automatic save-triggered backup is permitted by the 1-hour rate limit.
     * Always returns {@code true} when {@code lastBackupMillis == NO_BACKUP_RECORDED}.
     */
    public static boolean isRateLimitElapsed(long lastBackupMillis, long nowMillis) {
        if (lastBackupMillis == NO_BACKUP_RECORDED) {
            return true;
        }
        return nowMillis - lastBackupMillis >= MIN_INTERVAL_MILLIS;
    }

    /**
     * Selects the oldest matching backup files to delete so that at most {@code maxGenerations}
     * matching files remain. Files that do not match {@link #BACKUP_FILE_PATTERN} are never
     * counted and never returned.
     *
     * @return immutable list of file names to delete, ordered oldest first
     */
    public static List<String> selectFilesToPrune(Collection<String> fileNames, int maxGenerations) {
        if (fileNames == null || fileNames.isEmpty()) {
            return List.of();
        }
        int keep = Math.max(MIN_GENERATIONS, maxGenerations);
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

    static int compareBackupFileNames(String a, String b) {
        if (Objects.equals(a, b)) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        Matcher ma = BACKUP_FILE_PATTERN.matcher(a);
        Matcher mb = BACKUP_FILE_PATTERN.matcher(b);
        boolean matchA = ma.matches();
        boolean matchB = mb.matches();
        if (!matchA || !matchB) {
            return a.compareTo(b);
        }
        int cmp = ma.group(1).compareTo(mb.group(1));
        if (cmp != 0) {
            return cmp;
        }
        cmp = ma.group(2).compareTo(mb.group(2));
        if (cmp != 0) {
            return cmp;
        }
        String sufA = ma.group(3);
        String sufB = mb.group(3);
        if (sufA == null && sufB == null) {
            return 0;
        }
        if (sufA == null) {
            return -1;
        }
        if (sufB == null) {
            return 1;
        }
        cmp = new BigInteger(sufA).compareTo(new BigInteger(sufB));
        return cmp != 0 ? cmp : sufA.compareTo(sufB);
    }
}
