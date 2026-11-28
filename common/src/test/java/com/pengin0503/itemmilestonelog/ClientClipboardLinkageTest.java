package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * PR-3 hard lesson: a shared class in a merged jar must not name a version-varying client API.
 * The clipboard call therefore lives only in per-profile {@code ClipboardCompatImpl} classes, and
 * the new pure client helpers must not import Minecraft or NeoForge at all.
 */
class ClientClipboardLinkageTest {
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

    private static List<Path> javaSources(Path root) throws IOException {
        List<Path> result = new ArrayList<>();
        for (String tree : List.of("1.21.1", "1.20.1-forge", "26.1.2", "26.2", "26.3")) {
            try (Stream<Path> stream = Files.walk(root.resolve("versions").resolve(tree).resolve("src/main/java"))) {
                stream.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java"))
                        .forEach(result::add);
            }
        }
        try (Stream<Path> stream = Files.walk(root.resolve("versions/1.21.1/compat-variants"))) {
            stream.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java"))
                    .forEach(result::add);
        }
        return result;
    }

    @Test
    void clipboardCallExistsOnlyInProfileImplementations() throws IOException {
        Path root = repoRoot();
        int impls = 0;
        for (Path file : javaSources(root)) {
            String source = Files.readString(file);
            String name = file.getFileName().toString();
            // Only the version-varying game API must be isolated. The shared loader's
            // `get().setClipboard(value)` is a call on the stable compat interface, not a game
            // API, so matching on any ".setClipboard(" would falsely flag that seam.
            if (source.contains("keyboardHandler.setClipboard(")) {
                assertTrue(name.equals("ClipboardCompatImpl.java"),
                        "version-varying clipboard API outside a profile impl: " + root.relativize(file));
                assertTrue(source.contains("Minecraft.getInstance().keyboardHandler.setClipboard(value);"),
                        "clipboard impl must use the per-profile seam: " + root.relativize(file));
                impls++;
            }
            if (source.contains("ClipboardAccess.setClipboard(")) {
                assertTrue(name.equals("ClientRecentCopy.java"),
                        "clipboard seam used outside the client action: " + root.relativize(file));
                assertFalse(source.contains("keyboardHandler"),
                        "the client action must not touch the game clipboard API directly");
            }
        }
        assertTrue(impls >= 13, "expected one clipboard impl per compat profile, found " + impls);
    }

    @Test
    void clipboardSanitizerRejectsSchemesCaseInsensitively() throws IOException {
        for (String tree : List.of("1.21.1", "1.20.1-forge", "26.1.2", "26.2", "26.3")) {
            String source = Files.readString(repoRoot().resolve("versions").resolve(tree)
                    .resolve("src/main/java/com/pengin0503/itemmilestonelog/client/ClientRecentCopy.java"));
            assertTrue(source.contains("ClientExportLogic.hasUnsafeScheme(raw)"),
                    tree + ": clipboard sanitizer must use the case-insensitive scheme check");
        }
    }

    @Test
    void newPureHelpersHaveNoGameImports() throws IOException {
        Path root = repoRoot();
        for (String name : List.of("ClientDiagRender.java", "LowPowerModePolicy.java",
                "RecentRecordSelection.java", "ExternalIntegrationGate.java")) {
            String source = Files.readString(root.resolve("common/src/main/java/com/pengin0503/itemmilestonelog/")
                    .resolve(name));
            assertFalse(source.contains("import net.minecraft")
                            || source.contains("import net.neoforged")
                            || source.contains("import net.minecraftforge"),
                    name + " must stay pure Java for the merged jar");
        }
    }
}
