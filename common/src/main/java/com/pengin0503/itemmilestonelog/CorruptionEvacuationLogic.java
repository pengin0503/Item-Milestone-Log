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
 * Pure-Java logic for F-140-2 (N141): evacuation of a possibly corrupted {@code MilestoneData}
 * SavedData file, shared across all Minecraft versions via {@code common/src/main/java}.
 *
 * <p>The vanilla per-version {@code DimensionDataStorage}/{@code SavedDataStorage} does not
 * preserve a file that failed to load: it logs an error, treats the data as absent, and the next
 * normal save writes over the same path. This class only decides <em>whether</em> to evacuate,
 * what the evacuation file is called, what the operator-only summary says, and which old
 * evacuation files may be pruned. The copy itself is done by {@link CorruptionEvacuationIO}.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class CorruptionEvacuationLogic {
    /** Number of newest evacuations kept in {@code <world>/itemmilestonelog/corrupt/}. */
    public static final int MAX_EVACUATIONS = 5;

    /** Sentinel for "the stored schemaVersion could not be read at all" in the summary. */
    public static final int UNKNOWN_SCHEMA_VERSION = -1;

    /**
     * Exact file-name pattern owned by N141 evacuations:
     * {@code itemmilestonelog_data-YYYYMMDD-HHmmss.dat} or
     * {@code itemmilestonelog_data-YYYYMMDD-HHmmss-<N>.dat}. Pruning only ever considers files
     * matching this pattern; the companion summary is the same base name with {@code .txt}.
     */
    public static final Pattern EVACUATION_FILE_PATTERN =
            Pattern.compile("^itemmilestonelog_data-(\\d{8})-(\\d{6})(?:-(\\d+))?\\.dat$");

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss", Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);

    private static final DateTimeFormatter SUMMARY_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss XXX", Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);

    /**
     * Chronological ascending comparator for evacuation file names matching
     * {@link #EVACUATION_FILE_PATTERN} (date, time, then numeric collision suffix).
     */
    public static final Comparator<String> EVACUATION_NAME_ORDER =
            CorruptionEvacuationLogic::compareEvacuationFileNames;

    private CorruptionEvacuationLogic() {
    }

    /**
     * Result of one {@code MilestoneData} load, as needed by the evacuation decision.
     *
     * @param elementSkipCount    number of individual elements/fields the lenient loader skipped
     * @param totalFailure        {@code true} when the whole file could not be loaded or its root
     *                            structure was unusable (data treated as absent/empty)
     * @param loadedSchemaVersion schemaVersion read from the data, or
     *                            {@link #UNKNOWN_SCHEMA_VERSION} when the file could not be read
     */
    public record LoadOutcome(int elementSkipCount, boolean totalFailure, int loadedSchemaVersion) {
        public LoadOutcome {
            if (elementSkipCount < 0) {
                throw new IllegalArgumentException("elementSkipCount must not be negative: " + elementSkipCount);
            }
            if (loadedSchemaVersion < UNKNOWN_SCHEMA_VERSION) {
                throw new IllegalArgumentException("loadedSchemaVersion must be >= -1: " + loadedSchemaVersion);
            }
        }

        /** Whether the D-140-2 trigger fired: at least one element skip, or a total load failure. */
        public boolean requiresEvacuation() {
            return CorruptionEvacuationLogic.shouldEvacuate(this);
        }
    }

    /** D-140-2: evacuate on any element-level skip or on a total load failure. */
    public static boolean shouldEvacuate(LoadOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        return shouldEvacuate(outcome.elementSkipCount(), outcome.totalFailure());
    }

    /** D-140-2: evacuate on any element-level skip or on a total load failure. */
    public static boolean shouldEvacuate(int elementSkipCount, boolean totalFailure) {
        return totalFailure || elementSkipCount >= 1;
    }

    /**
     * Combines the server-start checks into the total-failure flag used for evacuation.
     *
     * <p>An existing file that cannot be read at all ({@code rootUnreadable}) is a total failure
     * by definition, even when another listener already caused {@link MilestoneData#get} to
     * return a cached empty instance ({@code loadFailed == false}) and therefore no
     * {@link MilestoneLoadReport} is available. Without this the unreadable file would never be
     * evacuated and the next normal save would overwrite it.
     */
    public static boolean isTotalLoadFailure(boolean rootUnreadable, boolean rootInvalid, boolean loadFailed) {
        return rootUnreadable || rootInvalid || loadFailed;
    }

    /** Returns whether {@code fileName} matches {@link #EVACUATION_FILE_PATTERN}. */
    public static boolean isEvacuationDataFileName(String fileName) {
        return fileName != null && EVACUATION_FILE_PATTERN.matcher(fileName).matches();
    }

    /** Returns whether {@code fileName} is the summary companion of a matching evacuation file. */
    public static boolean isEvacuationSummaryFileName(String fileName) {
        if (fileName == null || !fileName.endsWith(".txt")) {
            return false;
        }
        String dataName = fileName.substring(0, fileName.length() - ".txt".length()) + ".dat";
        return isEvacuationDataFileName(dataName);
    }

    /** Formats the base evacuation name (without extension), e.g. {@code itemmilestonelog_data-20261125-120000}. */
    public static String formatBaseName(Instant instant, ZoneId zone) {
        Objects.requireNonNull(instant, "instant");
        ZoneId effectiveZone = zone != null ? zone : ZoneId.systemDefault();
        return "itemmilestonelog_data-" + TIMESTAMP_FORMATTER.format(instant.atZone(effectiveZone));
    }

    /**
     * Chooses a non-colliding evacuation data file name for {@code instant}:
     * {@code itemmilestonelog_data-YYYYMMDD-HHmmss.dat}, or {@code -1}, {@code -2}, ... when the
     * data file or its summary already exists (same-second collision style as F10 backups).
     */
    public static String chooseDataFileName(Instant instant, ZoneId zone, Predicate<String> exists) {
        Objects.requireNonNull(exists, "exists");
        String base = formatBaseName(instant, zone);
        String candidate = base + ".dat";
        if (!exists.test(candidate) && !exists.test(summaryFileName(candidate))) {
            return candidate;
        }
        for (int suffix = 1; suffix < Integer.MAX_VALUE; suffix++) {
            candidate = base + "-" + suffix + ".dat";
            if (!exists.test(candidate) && !exists.test(summaryFileName(candidate))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Exhausted evacuation file name suffixes for " + base);
    }

    /** Returns the same base name with {@code .txt} instead of {@code .dat}. */
    public static String summaryFileName(String dataFileName) {
        if (!isEvacuationDataFileName(dataFileName)) {
            throw new IllegalArgumentException("Not an evacuation data file name: " + dataFileName);
        }
        return dataFileName.substring(0, dataFileName.length() - ".dat".length()) + ".txt";
    }

    /**
     * Builds the operator-only summary. Deliberately contains only metadata (timestamp, skip
     * count, total-failure flag, loaded schemaVersion and the source file name); never player
     * names, UUIDs, item ids or record contents.
     */
    public static String formatSummary(LoadOutcome outcome, String sourceFileName, Instant instant, ZoneId zone) {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(instant, "instant");
        Objects.requireNonNull(sourceFileName, "sourceFileName");
        ZoneId effectiveZone = zone != null ? zone : ZoneId.systemDefault();
        String schemaVersion = outcome.loadedSchemaVersion() == UNKNOWN_SCHEMA_VERSION
                ? "unknown"
                : Integer.toString(outcome.loadedSchemaVersion());
        return """
                Item Milestone Log - corrupt SavedData evacuation
                evacuatedAt: %s
                sourceFile: %s
                elementSkips: %d
                totalLoadFailure: %s
                loadedSchemaVersion: %s
                """.formatted(
                SUMMARY_TIMESTAMP_FORMATTER.format(instant.atZone(effectiveZone)),
                sourceFileName,
                outcome.elementSkipCount(),
                outcome.totalFailure(),
                schemaVersion);
    }

    /**
     * Parses the timestamp encoded in a matching evacuation file name into epoch milliseconds
     * using {@code zone}, or returns {@code -1} when the name does not match or the date is invalid.
     */
    public static long parseTimestampMillis(String fileName, ZoneId zone) {
        if (!isEvacuationDataFileName(fileName)) {
            return -1L;
        }
        Matcher matcher = EVACUATION_FILE_PATTERN.matcher(fileName);
        matcher.matches();
        ZoneId effectiveZone = zone != null ? zone : ZoneId.systemDefault();
        String rawTimestamp = matcher.group(1) + "-" + matcher.group(2);
        try {
            LocalDateTime ldt = LocalDateTime.parse(rawTimestamp, TIMESTAMP_FORMATTER);
            return ldt.atZone(effectiveZone).toInstant().toEpochMilli();
        } catch (DateTimeException ignored) {
            return -1L;
        }
    }

    /**
     * Selects the oldest matching evacuation data files to delete so that at most
     * {@code maxEvacuations} matching files remain. Files not matching
     * {@link #EVACUATION_FILE_PATTERN} are never counted and never returned.
     *
     * @return immutable list of data file names to delete, ordered oldest first
     */
    public static List<String> selectDataFilesToPrune(Collection<String> fileNames, int maxEvacuations) {
        if (fileNames == null || fileNames.isEmpty()) {
            return List.of();
        }
        int keep = Math.max(1, maxEvacuations);
        List<String> matching = new ArrayList<>();
        for (String name : fileNames) {
            if (isEvacuationDataFileName(name)) {
                matching.add(name);
            }
        }
        if (matching.size() <= keep) {
            return List.of();
        }
        matching.sort(EVACUATION_NAME_ORDER);
        return List.copyOf(matching.subList(0, matching.size() - keep));
    }

    static int compareEvacuationFileNames(String a, String b) {
        if (Objects.equals(a, b)) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        Matcher ma = EVACUATION_FILE_PATTERN.matcher(a);
        Matcher mb = EVACUATION_FILE_PATTERN.matcher(b);
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
