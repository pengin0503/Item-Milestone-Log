package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #122: pins the server-start wiring of {@link CorruptionEvacuation}. A live MinecraftServer cannot
 * be constructed in the unit harness, so the decision inputs read from the on-disk SavedData and
 * the data storage are pinned against the class source (the same technique used by
 * {@link MilestoneServerStopOrderTest}); the decision itself is covered by the pure-logic tests in
 * {@code common/src/test/.../CorruptionEvacuationLogicTest}.
 */
class CorruptionEvacuationWiringTest {
    private static Path resolveSource(String fileName) {
        String mainSourceDir = System.getProperty("itemmilestonelog.mainSourceDir");
        if (mainSourceDir != null && !mainSourceDir.isBlank()) {
            Path cur = Path.of(mainSourceDir).toAbsolutePath().normalize();
            for (int i = 0; i < 6 && cur != null; i++) {
                Path candidate = cur.resolve("com/pengin0503/itemmilestonelog").resolve(fileName);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
                cur = cur.getParent();
            }
        }
        Path local = Path.of("src", "main", "java", "com", "pengin0503", "itemmilestonelog", fileName);
        if (Files.isRegularFile(local)) {
            return local;
        }
        Path fromRun = Path.of("..", "src", "main", "java", "com", "pengin0503", "itemmilestonelog", fileName);
        if (Files.isRegularFile(fromRun)) {
            return fromRun;
        }
        throw new AssertionError(fileName + " not found; "
                + "set -Ditemmilestonelog.mainSourceDir=<version>/src/main/java");
    }

    private static int firstOf(String source, String... needles) {
        int first = -1;
        for (String needle : needles) {
            int at = source.indexOf(needle);
            if (at >= 0 && (first < 0 || at < first)) {
                first = at;
            }
        }
        return first;
    }

    @Test
    void serverStartReadsTheSavedDataFileAndClassifiesUnreadableAndInvalidRoots() throws IOException {
        String src = Files.readString(resolveSource("CorruptionEvacuation.java"));

        assertTrue(src.contains("public static void onServerStarting(MinecraftServer server)"));
        assertTrue(src.contains("server == null"), "a null server must be a no-op");
        int dataFile = src.indexOf("dataFile");
        assertTrue(dataFile > 0, "the SavedData file path must be resolved from the world root");
        assertTrue(src.indexOf("Files.isRegularFile(dataFile)") > dataFile,
                "a missing SavedData file must be a no-op");

        int unreadable = src.indexOf("boolean rootUnreadable = root == null");
        int invalid = src.indexOf("rootInvalid = true");
        assertTrue(unreadable > 0 && invalid > unreadable,
                "both unreadable and invalid roots must be classified before any decision");
        if (src.contains("hasMalformedRootContainer")) {
            assertTrue(src.indexOf("rootInvalid = hasMalformedRootContainer(dataTag)") > unreadable,
                    "wrong-typed containers must mark the root invalid in the 1.21.1/Forge trees");
        }
        assertFalse(src.contains("Files.delete("), "evacuation must copy, never delete, the source file");
        assertFalse(src.contains("Files.move("), "evacuation must copy, never rename, the source file");
    }

    @Test
    void cachedEmptyDataCannotHideAnUnreadableFileAndEvacuationIsGated() throws IOException {
        String src = Files.readString(resolveSource("CorruptionEvacuation.java"));

        int rootChecks = src.indexOf("boolean rootUnreadable = root == null");
        int loadFailed = src.indexOf("boolean loadFailed = data == null", rootChecks);
        int cached = firstOf(src, "MilestoneData.get(server)", "McCompat.data().getOrCreate(storage)");
        assertTrue(loadFailed > rootChecks && cached > loadFailed,
                "the cached-empty fallback must be applied after the root is classified");

        int total = src.indexOf("CorruptionEvacuationLogic.isTotalLoadFailure(");
        String decision = src.substring(total, src.indexOf(";", total));
        assertTrue(total > loadFailed);
        assertTrue(decision.contains("rootUnreadable") && decision.contains("rootInvalid")
                        && decision.contains("loadFailed"),
                "all three failure inputs must reach the total-failure decision (#99 review 4-1)");
        assertTrue(src.indexOf("report.totalFailure()", total) > total,
                "a partial-decode report must be able to turn totalFailure on");

        int outcome = src.indexOf("new CorruptionEvacuationLogic.LoadOutcome(");
        int gate = src.indexOf("CorruptionEvacuationLogic.shouldEvacuate(outcome)");
        int evacuate = src.indexOf("CorruptionEvacuationIO.evacuate(");
        assertTrue(outcome > total && gate > outcome && evacuate > gate,
                "only a shouldEvacuate outcome may reach the file copy");
        assertTrue(src.indexOf("LOGGER.warn", evacuate) > evacuate,
                "a completed evacuation must be reported in the server log");
    }

    @Test
    void evacuationRunsAtServerStartAfterBackupInitAndBeforeNormalSaves() throws IOException {
        String src = Files.readString(resolveSource("ItemMilestoneLog.java"));
        int init = src.indexOf("MilestoneBackup.initForServer(");
        int evacuate = src.indexOf("CorruptionEvacuation.onServerStarting(");
        assertTrue(init > 0, "backup state must be initialized at server start");
        assertTrue(evacuate > init, "the corruption check must run after backup init and before normal saves");
    }
}
