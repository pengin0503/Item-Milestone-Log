package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * F-150-71 / PR-3 hard lesson: all five trees and both stale 1.21.x whole-file overrides must
 * register exactly four KeyMappings (no duplicates), each unbound by default. Constructor-time
 * key registrations are FML-critical, so this is checked against the real sources rather than a
 * running client.
 */
class ClientKeyRegistrationShapeTest {
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

    private static String registrations(String source) {
        int start = source.indexOf("private static void onRegisterKeyMappings(");
        assertTrue(start >= 0, "onRegisterKeyMappings is missing");
        int end = source.indexOf("\n    }", start);
        assertTrue(end > start, "onRegisterKeyMappings body is truncated");
        return source.substring(start, end);
    }

    @Test
    void everyTreeRegistersExactlyFourUnboundKeys() throws IOException {
        Path root = repoRoot();
        for (String tree : TREES) {
            String source = Files.readString(root.resolve("versions").resolve(tree).resolve(CLIENT));
            String body = registrations(source);
            assertEquals(4, body.split("event\\.register\\(", -1).length - 1,
                    tree + ": expected four key mappings, got " + body);
            assertTrue(body.contains("event.register(COPY_RECENT_MAPPING);"),
                    tree + ": the F-150-71 key must be registered alongside the other three");
            // Follows the exact MOD_ID-concatenation pattern of the three existing keys; the
            // resolved name ("key.itemmilestonelog.copy_recent") is asserted by the per-tree
            // ClientCopyRecentKeyBindingTest and is present in both lang files (test below).
            assertTrue(source.contains(
                            "KEY_COPY_RECENT = \"key.\" + ItemMilestoneLog.MOD_ID + \".copy_recent\""),
                    tree + ": copy key name must follow the MOD_ID pattern used by the lang key");
            int unbound = 0;
            for (String name : List.of("OPEN_GUI_MAPPING", "ADD_HOVERED_MAPPING",
                    "MUTE_NOTIFICATION_SOUND_MAPPING", "COPY_RECENT_MAPPING")) {
                int from = source.indexOf("public static final KeyMapping " + name);
                assertTrue(from >= 0, tree + ": missing mapping " + name);
                int to = source.indexOf(");", from);
                String declaration = source.substring(from, to);
                assertTrue(declaration.contains("InputConstants.UNKNOWN"),
                        tree + ": " + name + " must be unbound by default");
                unbound++;
            }
            assertEquals(4, unbound);
        }
    }

    @Test
    void staleOverrideEntryPointsAlsoHaveFourKeysAndNoAbstractListeners() throws IOException {
        Path root = repoRoot();
        for (String profile : List.of("v121_9", "v121_11")) {
            Path path = root.resolve("versions/1.21.1/compat-variants/" + profile
                    + "/overrides/com/pengin0503/itemmilestonelog/client/ItemMilestoneLogClient.java");
            assertTrue(Files.isRegularFile(path), "missing override: " + path);
            String source = Files.readString(path);
            assertEquals(4, registrations(source).split("event\\.register\\(", -1).length - 1,
                    profile + ": override must register four keys");
            assertFalse(source.contains("onLivingDamage(LivingDamageEvent event)"),
                    profile + ": abstract LivingDamageEvent listener is forbidden");
            assertFalse(source.contains("SoundEvent.SoundSourceEvent"),
                    profile + ": abstract sound event listener is forbidden");
        }
    }

    @Test
    void bothLanguagesDefineTheCopyKeyMessages() throws IOException {
        Path root = repoRoot();
        for (String lang : List.of("en_us", "ja_jp")) {
            String json = Files.readString(root.resolve("common/resources/assets/itemmilestonelog/lang/"
                    + lang + ".json"));
            for (String key : List.of("key.itemmilestonelog.copy_recent",
                    "message.itemmilestonelog.copied_recent",
                    "message.itemmilestonelog.no_recent_record")) {
                assertTrue(json.contains("\"" + key + "\""), lang + ": missing " + key);
            }
        }
    }
}
