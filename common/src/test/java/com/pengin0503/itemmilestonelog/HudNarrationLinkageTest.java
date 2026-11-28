package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Static guard for the 1.21.x HUD narrator linkage. {@code GameNarrator.say(Component)} exists
 * only up to 1.21.5 and {@code saySystemQueued(Component)} only from 1.21.6, so one shared
 * {@code HudCompat.class} cannot name either method in a merged segment jar. The established
 * pattern (N252's TextCompat / SoundCompat / ListCompat) is a shared facade plus one
 * per-profile implementation reached through {@code McCompat.narration()}. This test keeps the
 * shared class free of narrator references, keeps the implementations on their own profiles,
 * and rejects a whole-file HudCompat override, which would silently replace the shared class in
 * a merged jar with one profile's bytecode.
 */
class HudNarrationLinkageTest {
    private static final String HUD =
            "src/main/java/com/pengin0503/itemmilestonelog/compat/client/HudCompat.java";
    private static final String IMPL =
            "compat/client/%s/NarrationCompatImpl.java";
    private static final String OVERRIDE_SUFFIX =
            "/overrides/com/pengin0503/itemmilestonelog/compat/client/HudCompat.java";
    private static final Pattern COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/|(?m)//[^\n]*");

    /** Locates the repository root from the current working dir (CI project dir or repo root). */
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

    /** Source with javadoc/line comments stripped, so narration mentions in prose do not count. */
    private static String code(Path file) throws IOException {
        assertTrue(Files.isRegularFile(file), "missing source: " + file);
        return COMMENTS.matcher(Files.readString(file)).replaceAll("");
    }

    @Test
    void sharedHudCompatRoutesNarrationThroughTheFacade() throws IOException {
        Path root = repoRoot();
        String source = code(root.resolve("versions/1.21.1").resolve(HUD));
        assertTrue(source.contains("McCompat.narration().narrate(mc, text)"),
                "shared HudCompat must reach narration through McCompat.narration()");
        assertFalse(source.contains("GameNarrator"), "shared HudCompat must not name GameNarrator");
        assertFalse(source.contains("getNarrator()"), "shared HudCompat must not call getNarrator()");
        assertFalse(source.contains("saySystemQueued"), "shared HudCompat must not name saySystemQueued");
        assertFalse(source.contains(".say("), "shared HudCompat must not name say(Component)");
    }

    @Test
    void narrationCallsLiveOnlyInProfileImplsWithoutWholeFileOverrides() throws IOException {
        Path root = repoRoot();
        Path variants = root.resolve("versions/1.21.1/compat-variants");
        List<Path> stale = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(variants)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().replace('\\', '/').endsWith(OVERRIDE_SUFFIX))
                    .forEach(stale::add);
        }
        assertTrue(stale.isEmpty(),
                "a whole-file HudCompat override would put one profile's narrator bytecode into"
                        + " the shared class of a merged jar: " + stale);

        // Profile -> the only narrator call that may appear in that profile's implementation.
        String[][] profiles = {
                {"v121_1", "mc.getNarrator().say(text);"},
                {"v121_2", "mc.getNarrator().say(text);"},
                {"v121_4", "mc.getNarrator().say(text);"},
                {"v121_5", "mc.getNarrator().say(text);"},
                {"v121_6", "mc.getNarrator().saySystemQueued(text);"},
                {"v121_7", "mc.getNarrator().saySystemQueued(text);"},
                {"v121_8", "mc.getNarrator().saySystemQueued(text);"},
                {"v121_9", "mc.getNarrator().saySystemQueued(text);"},
                {"v121_11", "mc.getNarrator().saySystemQueued(text);"},
        };
        for (String[] profile : profiles) {
            String id = profile[0];
            String rel = String.format(IMPL, id);
            Path impl = "v121_1".equals(id)
                    ? root.resolve("versions/1.21.1/src/main/java/com/pengin0503/itemmilestonelog").resolve(rel)
                    : variants.resolve(id + "/com/pengin0503/itemmilestonelog").resolve(rel);
            String source = code(impl);
            assertTrue(source.contains("implements NarrationCompat"), id + ": not a NarrationCompat implementation");
            assertTrue(source.contains(profile[1]), id + ": expected narrator call " + profile[1]);
        }
    }
}
