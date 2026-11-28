package com.pengin0503.itemmilestonelog;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java file-name rules for F-150-70 (N67) screenshots. The images themselves are produced by
 * the per-version compat implementation; this class only chooses names, resolves paths and prunes
 * within the client-local directory. No player or server name is used in a file name (§1.4-1).
 *
 * <p>Must stay Java 17 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class ScreenshotStore {
    /** Subdirectory inside the client-local area. */
    public static final String SCREENSHOT_DIR = "screenshots";
    /** Maximum number of retained screenshots (F-150-70 item 5). */
    public static final int MAX_SCREENSHOTS = 20;
    /** Names owned by this feature; pruning never touches other files. */
    public static final Pattern FILE_PATTERN =
            Pattern.compile("^shot-(\\d{8})-(\\d{6})(?:-(\\d+))?\\.png$");

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss", Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);

    private ScreenshotStore() {
    }

    public static Path screenshotDir(Path configDir) {
        return ClientStorageIO.clientDir(configDir).resolve(SCREENSHOT_DIR);
    }

    public static boolean isScreenshotFileName(String name) {
        return name != null && FILE_PATTERN.matcher(name).matches();
    }

    /** Chooses a collision-free name; several captures in the same second get -1, -2, ... . */
    public static String chooseFileName(Instant instant, ZoneId zone, Predicate<String> exists) {
        Objects.requireNonNull(instant, "instant");
        Objects.requireNonNull(exists, "exists");
        String base = "shot-" + TIMESTAMP_FORMATTER.format(
                instant.atZone(zone == null ? ZoneId.systemDefault() : zone));
        String candidate = base + ".png";
        if (!exists.test(candidate)) {
            return candidate;
        }
        for (int suffix = 1; suffix < Integer.MAX_VALUE; suffix++) {
            candidate = base + "-" + suffix + ".png";
            if (!exists.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Exhausted screenshot suffixes for " + base);
    }

    /** Selects only this feature's oldest files when more than {@link #MAX_SCREENSHOTS} exist. */
    public static List<String> selectFilesToPrune(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<String> owned = new ArrayList<>();
        for (String name : names) {
            if (isScreenshotFileName(name)) {
                owned.add(name);
            }
        }
        owned.sort(ScreenshotStore::compareNames);
        if (owned.size() <= MAX_SCREENSHOTS) {
            return List.of();
        }
        return List.copyOf(owned.subList(0, owned.size() - MAX_SCREENSHOTS));
    }

    private static int compareNames(String a, String b) {
        Matcher ma = FILE_PATTERN.matcher(a);
        Matcher mb = FILE_PATTERN.matcher(b);
        if (!ma.matches() || !mb.matches()) {
            return a.compareTo(b);
        }
        int byDate = (ma.group(1) + ma.group(2)).compareTo(mb.group(1) + mb.group(2));
        if (byDate != 0) {
            return byDate;
        }
        return Integer.compare(suffix(ma), suffix(mb));
    }

    private static int suffix(Matcher matcher) {
        return matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
    }
}
