package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerLangTest {
    @Test
    void loadsEnglishFallbacksFromTheModsOwnLangFile() {
        Map<String, String> fallbacks = ServerLang.fallbacks();
        assertFalse(fallbacks.isEmpty(), "en_us.json should be readable from the mod's resources");
        assertEquals("Now tracking: %s", fallbacks.get("message.itemmilestonelog.added"));
        assertEquals("★ Milestone recorded: %s at playtime %s", fallbacks.get("message.itemmilestonelog.recorded"));
    }

    private static Path resolveMainSourceDir() {
        String dir = System.getProperty("itemmilestonelog.mainSourceDir");
        if (dir != null && !dir.isBlank()) {
            return Path.of(dir);
        }
        Path local = Path.of("src", "main", "java");
        if (Files.isDirectory(local)) {
            return local;
        }
        return Path.of("versions", "1.21.1", "src", "main", "java");
    }

    @Test
    void everyKeyUsedByServerCodeHasAnEnglishFallback() throws IOException {
        Path sources = resolveMainSourceDir();
        assertTrue(Files.isDirectory(sources), "sources not found at " + sources.toAbsolutePath());

        Pattern literal = Pattern.compile("\"(message\\.itemmilestonelog\\.[a-z0-9_.]+)\"");
        Pattern dynamicPrefix = Pattern.compile("\"(message\\.itemmilestonelog\\.[a-z0-9_.]*)\"\\s*\\+");
        Set<String> used = new TreeSet<>();
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String content = Files.readString(file);
                Matcher dyn = dynamicPrefix.matcher(content);
                assertFalse(dyn.find(), "Dynamic message key concatenation in " + file.getFileName()
                        + " cannot be statically verified against en_us.json");
                Matcher matcher = literal.matcher(content);
                while (matcher.find()) {
                    String key = matcher.group(1);
                    if (!key.endsWith(".")) {
                        used.add(key);
                    }
                }
            }
        }
        assertTrue(used.size() >= 20, "expected to find the server messages, found " + used);
        Set<String> missing = new TreeSet<>(used);
        missing.removeAll(ServerLang.fallbacks().keySet());
        assertTrue(missing.isEmpty(), "keys without an en_us.json entry: " + missing);
    }

    @Test
    void everyGuiKeyUsedByClientScreensHasAnEnglishEntry() throws IOException {
        Path sources = resolveMainSourceDir();
        assertTrue(Files.isDirectory(sources), "sources not found at " + sources.toAbsolutePath());

        Pattern literal = Pattern.compile("\"(gui\\.itemmilestonelog\\.[a-z0-9_.]+)\"");
        Pattern dynamicPrefix = Pattern.compile("\"(gui\\.itemmilestonelog\\.[a-z0-9_.]*)\"\\s*\\+");
        Set<String> allowedDynamicPrefixes = Set.of("gui.itemmilestonelog.preset.");
        Set<String> used = new TreeSet<>();
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String content = Files.readString(file);
                Matcher dyn = dynamicPrefix.matcher(content);
                while (dyn.find()) {
                    String prefix = dyn.group(1);
                    assertTrue(allowedDynamicPrefixes.contains(prefix),
                            "Unexpected dynamic gui key concatenation '" + prefix + "' in " + file.getFileName());
                }
                Matcher matcher = literal.matcher(content);
                while (matcher.find()) {
                    String key = matcher.group(1);
                    // Known dynamic prefix (gui.itemmilestonelog.preset.) is checked per built-in preset below.
                    if (!key.endsWith(".")) {
                        used.add(key);
                    }
                }
            }
        }
        assertTrue(used.size() >= 10, "expected to find the client GUI keys, found " + used);
        for (String builtinPresetId : Presets.PRESETS.keySet()) {
            used.add("gui.itemmilestonelog.preset." + builtinPresetId);
        }
        used.add("gui.itemmilestonelog.preset.generic");

        Set<String> missing = new TreeSet<>(used);
        missing.removeAll(ServerLang.fallbacks().keySet());
        assertTrue(missing.isEmpty(), "gui keys without an en_us.json entry: " + missing);
    }

    @Test
    void keybindKeysHaveEnglishEntries() {
        Map<String, String> fallbacks = ServerLang.fallbacks();
        assertTrue(fallbacks.containsKey("key.itemmilestonelog.open_gui"), "missing key.itemmilestonelog.open_gui");
        assertTrue(fallbacks.containsKey("key.categories.itemmilestonelog"), "missing key.categories.itemmilestonelog");
        assertTrue(fallbacks.containsKey("key.category.itemmilestonelog.general"), "missing key.category.itemmilestonelog.general");
    }

    @Test
    void rendersEnglishAndJapaneseFallbacksForVanillaClients() {
        String enTemplate = ServerLang.fallbacks().get("message.itemmilestonelog.added");
        assertNotNull(enTemplate);
        assertEquals(String.format(enTemplate, "Diamond"),
                ServerLang.tr("message.itemmilestonelog.added", "Diamond").getString());
        String summaryTemplate = ServerLang.fallbacks().get("message.itemmilestonelog.summary");
        assertNotNull(summaryTemplate);
        assertEquals(String.format(summaryTemplate, "1h 02m 03s", 2, 5),
                ServerLang.tr("message.itemmilestonelog.summary", "1h 02m 03s", 2, 5).getString());
        String jaRendered = ServerLang.trForLang("ja_jp", "message.itemmilestonelog.added", "Diamond").getString();
        assertTrue(jaRendered.contains("Diamond") && !jaRendered.equals(String.format(enTemplate, "Diamond")));
        assertEquals(jaRendered,
                ServerLang.trForLang("ja_JP", "message.itemmilestonelog.added", "Diamond").getString());
        assertEquals(String.format(enTemplate, "Diamond"),
                ServerLang.trForLang("fr_fr", "message.itemmilestonelog.added", "Diamond").getString());
    }

    @Test
    void unknownKeysStillProduceAComponent() {
        assertEquals("message.itemmilestonelog.does_not_exist",
                ServerLang.tr("message.itemmilestonelog.does_not_exist").getString());
    }
}
