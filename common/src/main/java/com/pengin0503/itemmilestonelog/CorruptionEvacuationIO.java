package com.pengin0503.itemmilestonelog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * File-system side of F-140-2 (N141): copies the current SavedData file into
 * {@code <world>/itemmilestonelog/corrupt/}, writes the operator-only summary, and prunes old
 * evacuations. Kept in {@code common/src/main/java} so all editions share the same rules.
 *
 * <p>The source file is only read (never deleted or renamed), and the copy runs at server start
 * before the first normal save. Failures are reported to the caller as warnings/exceptions; the
 * caller logs them and never blocks server start.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class CorruptionEvacuationIO {
    /**
     * Result of a successful copy.
     *
     * @param dataFile       copied {@code .dat} file
     * @param summaryFile    companion {@code .txt} path
     * @param summaryWritten whether the summary was written
     * @param retainedCount  number of matching evacuation files kept, or {@code -1} if unknown
     * @param warnings       non-fatal problems the caller should log
     */
    public record Result(Path dataFile, Path summaryFile, boolean summaryWritten, int retainedCount,
                         List<String> warnings) {
        public Result {
            warnings = List.copyOf(warnings);
        }
    }

    private CorruptionEvacuationIO() {
    }

    /**
     * Copies {@code sourceFile} to a new evacuation file and writes its summary. If writing the
     * summary or pruning fails, the copy is still reported as successful and the problem is added
     * to {@link Result#warnings()}.
     *
     * @throws IOException if the evacuation directory or data copy itself cannot be created
     */
    public static Result evacuate(Path sourceFile,
                                  Path corruptDir,
                                  CorruptionEvacuationLogic.LoadOutcome outcome,
                                  Instant now,
                                  ZoneId zone) throws IOException {
        Objects.requireNonNull(sourceFile, "sourceFile");
        Objects.requireNonNull(corruptDir, "corruptDir");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(now, "now");

        Files.createDirectories(corruptDir);
        String dataName = CorruptionEvacuationLogic.chooseDataFileName(
                now, zone, name -> Files.exists(corruptDir.resolve(name)));
        Path target = corruptDir.resolve(dataName).normalize();
        if (!corruptDir.normalize().equals(target.getParent())) {
            throw new IOException("Invalid evacuation target path: " + target);
        }
        Files.copy(sourceFile, target, StandardCopyOption.COPY_ATTRIBUTES);

        List<String> warnings = new ArrayList<>();
        Path summary = corruptDir.resolve(CorruptionEvacuationLogic.summaryFileName(dataName));
        boolean summaryWritten = false;
        try {
            String summaryText = CorruptionEvacuationLogic.formatSummary(
                    outcome, sourceFile.getFileName().toString(), now, zone);
            Files.writeString(summary, summaryText, StandardCharsets.UTF_8);
            summaryWritten = true;
        } catch (IOException e) {
            warnings.add("could not write summary " + summary.getFileName() + ": " + e.getMessage());
        }

        warnings.addAll(prune(corruptDir));
        int retained = countRetained(corruptDir, warnings);
        return new Result(target, summary, summaryWritten, retained, warnings);
    }

    private static List<String> prune(Path corruptDir) {
        List<String> warnings = new ArrayList<>();
        List<String> names = listNames(corruptDir, warnings);
        for (String oldName : CorruptionEvacuationLogic.selectDataFilesToPrune(
                names, CorruptionEvacuationLogic.MAX_EVACUATIONS)) {
            delete(corruptDir.resolve(oldName), warnings);
            try {
                delete(corruptDir.resolve(CorruptionEvacuationLogic.summaryFileName(oldName)), warnings);
            } catch (IllegalArgumentException ignored) {
                // The data name already matched the evacuation pattern, so this cannot happen.
            }
        }
        return warnings;
    }

    private static int countRetained(Path corruptDir, List<String> warnings) {
        List<String> names = listNames(corruptDir, warnings);
        int count = 0;
        for (String name : names) {
            if (CorruptionEvacuationLogic.isEvacuationDataFileName(name)) {
                count++;
            }
        }
        return count;
    }

    private static List<String> listNames(Path dir, List<String> warnings) {
        List<String> names = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .forEach(names::add);
        } catch (IOException e) {
            warnings.add("could not list " + dir + ": " + e.getMessage());
        }
        return names;
    }

    private static void delete(Path path, List<String> warnings) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            warnings.add("could not delete " + path.getFileName() + ": " + e.getMessage());
        }
    }
}
