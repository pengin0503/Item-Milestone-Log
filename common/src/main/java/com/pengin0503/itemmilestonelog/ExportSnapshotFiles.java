package com.pengin0503.itemmilestonelog;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-only snapshot names and generation rotation (step 7 PR-1, F-150-89 / D-150-246, 250, 253).
 *
 * <p>This is the single definition of the export snapshot pattern. The canonical export files
 * ({@code all-players.json/html/csv}, player exports) keep their historic names; only files whose
 * name matches this dedicated pattern are ever considered for pruning. It is Minecraft-free and
 * Java 17 compatible so all five trees share the exact same rules.
 */
public final class ExportSnapshotFiles {
    /** Default number of snapshot generations kept (D-150-253: constant, no Config key). */
    public static final int DEFAULT_GENERATIONS = 5;
    /** Minimum snapshot generations. */
    public static final int MIN_GENERATIONS = 1;
    /** Maximum snapshot generations. */
    public static final int MAX_GENERATIONS = 20;

    private static final String PREFIX = "snapshot-";
    private static final Pattern SNAPSHOT_PATTERN = Pattern.compile(
            "^snapshot-([a-zA-Z0-9._-]+)-(\\d{8})-(\\d{6})(?:-(\\d+))?\\.([a-zA-Z0-9]+)$");
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    private static final Comparator<String> ORDER = ExportSnapshotFiles::compareSnapshotNames;

    private ExportSnapshotFiles() {
    }

    public static int clampGenerations(int generations) {
        return Math.max(MIN_GENERATIONS, Math.min(MAX_GENERATIONS, generations));
    }

    /** Formats a timestamp as the strict {@code uuuuMMdd-HHmmss} server snapshot pattern. */
    public static String formatTimestamp(Instant instant, ZoneId zone) {
        if (instant == null) {
            throw new IllegalArgumentException("instant");
        }
        return TIMESTAMP_FORMATTER.format(instant.atZone(zone == null ? ZoneId.systemDefault() : zone));
    }

    /**
     * Chooses a dated snapshot name for one canonical export file name. A second file in the same
     * second gets {@code -1}, a third {@code -2}, and so on, mirroring the client and backup naming
     * conventions without sharing either one's constants.
     */
    public static String chooseFileName(String canonicalFileName, String timestamp,
                                        Collection<String> existingNames) {
        if (canonicalFileName == null || timestamp == null || canonicalFileName.contains("..")) {
            throw new IllegalArgumentException("invalid canonical export file name");
        }
        int dot = canonicalFileName.lastIndexOf('.');
        if (dot <= 0 || dot == canonicalFileName.length() - 1) {
            throw new IllegalArgumentException("canonical export file name needs an extension");
        }
        String base = canonicalFileName.substring(0, dot);
        String extension = canonicalFileName.substring(dot + 1);
        if (!base.matches("[a-zA-Z0-9._-]+") || !extension.matches("[a-zA-Z0-9]+")) {
            throw new IllegalArgumentException("invalid canonical export file name");
        }
        String candidate = PREFIX + base + "-" + timestamp + "." + extension;
        if (existingNames == null || !existingNames.contains(candidate)) {
            return candidate;
        }
        int suffix = 1;
        while (existingNames.contains(PREFIX + base + "-" + timestamp + "-" + suffix + "." + extension)) {
            suffix++;
        }
        return PREFIX + base + "-" + timestamp + "-" + suffix + "." + extension;
    }

    /** True only for names matching the dedicated dated-snapshot pattern. */
    public static boolean isSnapshotFileName(String fileName) {
        return parse(fileName) != null;
    }

    /**
     * Returns the oldest matching snapshot names that should be deleted, keeping at most
     * {@code maxGenerations} newest ones. Non-matching names (including {@code all-players.*},
     * player exports and hand-written files) are never returned (D-150-250).
     */
    public static List<String> selectFilesToPrune(Collection<String> fileNames, int maxGenerations) {
        List<String> matching = new ArrayList<>();
        if (fileNames != null) {
            for (String name : fileNames) {
                if (parse(name) != null) {
                    matching.add(name);
                }
            }
        }
        int keep = clampGenerations(maxGenerations);
        if (matching.size() <= keep) {
            return List.of();
        }
        matching.sort(ORDER);
        return List.copyOf(matching.subList(0, matching.size() - keep));
    }

    private static Matcher parse(String name) {
        if (name == null || name.contains("..")) {
            return null;
        }
        Matcher matcher = SNAPSHOT_PATTERN.matcher(name);
        if (!matcher.matches()) {
            return null;
        }
        try {
            LocalDateTime.parse(matcher.group(2) + "-" + matcher.group(3), TIMESTAMP_FORMATTER);
        } catch (RuntimeException e) {
            return null;
        }
        return matcher;
    }

    private static int compareSnapshotNames(String left, String right) {
        Matcher a = parse(left);
        Matcher b = parse(right);
        if (a == null || b == null) {
            return left.compareTo(right);
        }
        long millisA = epochMillis(a);
        long millisB = epochMillis(b);
        if (millisA != millisB) {
            return Long.compare(millisA, millisB);
        }
        BigInteger suffixA = a.group(4) == null ? BigInteger.ZERO : new BigInteger(a.group(4));
        BigInteger suffixB = b.group(4) == null ? BigInteger.ZERO : new BigInteger(b.group(4));
        int bySuffix = suffixA.compareTo(suffixB);
        return bySuffix != 0 ? bySuffix : left.compareTo(right);
    }

    private static long epochMillis(Matcher matcher) {
        return LocalDateTime.parse(matcher.group(2) + "-" + matcher.group(3), TIMESTAMP_FORMATTER)
                .atZone(ZoneOffset.UTC).toInstant().toEpochMilli();
    }
}
