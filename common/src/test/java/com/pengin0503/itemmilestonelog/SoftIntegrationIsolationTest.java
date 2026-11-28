package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * F-150-73 (N71) gate finishing: the soft Jade / WTHIT / TOP line must never become a hard
 * dependency. The 8-combination OR gate itself is fixed by {@link ExternalIntegrationGateTest};
 * this test keeps the build files and the client entry points free of mod APIs (D-150-207/208).
 */
class SoftIntegrationIsolationTest {
    private static final List<String> TREES =
            List.of("1.21.1", "1.20.1-forge", "26.1.2", "26.2", "26.3");
    private static final String CLIENT =
            "src/main/java/com/pengin0503/itemmilestonelog/client/ItemMilestoneLogClient.java";

    private static Path repoRoot() {
        String explicit = System.getProperty("itemmilestonelog.repoRoot");
        Path start = explicit == null || explicit.isBlank() ? Path.of("").toAbsolutePath() : Path.of(explicit);
        for (Path p = start; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("versions/1.21.1/src/main/java"))) {
                return p;
            }
        }
        return fail("cannot locate repository root from " + start);
    }

    @Test
    void noBuildDependencyOnJadeWthitOrTop() throws IOException {
        Path root = repoRoot();
        for (String tree : TREES) {
            Path gradle = root.resolve("versions").resolve(tree).resolve("build.gradle");
            assertTrue(Files.isRegularFile(gradle), "missing build file: " + gradle);
            for (String line : Files.readAllLines(gradle)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.isEmpty()) {
                    continue;
                }
                String lower = trimmed.toLowerCase(Locale.ROOT);
                assertFalse(lower.contains("jade") || lower.contains("wthit")
                                || lower.contains("theoneprobe"),
                        tree + ": soft integration must not become a build dependency: " + trimmed);
            }
        }
    }

    @Test
    void gateUsesOnlyModListPresenceChecks() throws IOException {
        Path root = repoRoot();
        String gate = Files.readString(root.resolve("common/src/main/java/com/pengin0503/itemmilestonelog/ExternalIntegrationGate.java"));
        assertFalse(gate.contains("import jade") || gate.contains("import wthit")
                || gate.contains("import mcjty"), "the pure gate must not import mod APIs");
        for (String tree : TREES) {
            String source = Files.readString(root.resolve("versions").resolve(tree).resolve(CLIENT));
            for (String mod : List.of("jade", "wthit", "top")) {
                assertTrue(source.contains("list.isLoaded(\"" + mod + "\")"),
                        tree + ": one-time ModList.isLoaded(" + mod + ") check is required");
            }
            assertFalse(source.contains("net.blay09.mods")
                            || source.contains("mcjty.theoneprobe")
                            || source.contains("jade.render"),
                    tree + ": no external mod class or API may be referenced");
        }
    }
}
