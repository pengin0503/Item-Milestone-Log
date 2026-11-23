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
        return Path.of("versions", "26.3", "src", "main", "java");
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
        Path sources = resolveMainSourceDir().resolve("com/pengin0503/itemmilestonelog/client");
        assertTrue(Files.isDirectory(sources), "client sources not found at " + sources.toAbsolutePath());

        Pattern literal = Pattern.compile("\"(gui\\.itemmilestonelog\\.[a-z0-9_.]+)\"");
        Set<String> used = new TreeSet<>();
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher matcher = literal.matcher(Files.readString(file));
                while (matcher.find()) {
                    String key = matcher.group(1);
                    // Keys ending in '.' are prefixes completed at runtime (e.g. per-preset names) and checked separately.
                    if (!key.endsWith(".")) {
                        used.add(key);
                    }
                }
            }
        }
        assertTrue(used.size() >= 10, "expected to find the client GUI keys, found " + used);
        Set<String> missing = new TreeSet<>(used);
        missing.removeAll(ServerLang.fallbacks().keySet());
        assertTrue(missing.isEmpty(), "gui keys without an en_us.json entry: " + missing);
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
