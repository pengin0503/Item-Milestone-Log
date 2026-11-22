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

    @Test
    void everyKeyUsedByServerCodeHasAnEnglishFallback() throws IOException {
        String dir = System.getProperty("itemmilestonelog.mainSourceDir");
        assertNotNull(dir, "system property itemmilestonelog.mainSourceDir is not set");
        Path sources = Path.of(dir);
        assertTrue(Files.isDirectory(sources), "sources not found at " + sources);

        Pattern literal = Pattern.compile("\"(message\\.itemmilestonelog\\.[a-z0-9_.]+)\"");
        Set<String> used = new TreeSet<>();
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher matcher = literal.matcher(Files.readString(file));
                while (matcher.find()) {
                    used.add(matcher.group(1));
                }
            }
        }
        assertTrue(used.size() >= 20, "expected to find the server messages, found " + used);
        Set<String> missing = new TreeSet<>(used);
        missing.removeAll(ServerLang.fallbacks().keySet());
        assertTrue(missing.isEmpty(), "keys without an en_us.json entry: " + missing);
    }

    @Test
    void rendersEnglishAndJapaneseFallbacksForVanillaClients() {
        assertEquals("Now tracking: Diamond",
                ServerLang.tr("message.itemmilestonelog.added", "Diamond").getString());
        assertEquals("Playtime: 1h 02m 03s | Milestones: 2 / 5 targets recorded. Use /milestonelog targets to see details.",
                ServerLang.tr("message.itemmilestonelog.summary", "1h 02m 03s", 2, 5).getString());
        assertEquals("目標に追加しました: Diamond",
                ServerLang.trForLang("ja_jp", "message.itemmilestonelog.added", "Diamond").getString());
        assertEquals("目標に追加しました: Diamond",
                ServerLang.trForLang("ja_JP", "message.itemmilestonelog.added", "Diamond").getString());
        assertEquals("Now tracking: Diamond",
                ServerLang.trForLang("fr_fr", "message.itemmilestonelog.added", "Diamond").getString());
    }

    @Test
    void unknownKeysStillProduceAComponent() {
        assertEquals("message.itemmilestonelog.does_not_exist",
                ServerLang.tr("message.itemmilestonelog.does_not_exist").getString());
    }
}
