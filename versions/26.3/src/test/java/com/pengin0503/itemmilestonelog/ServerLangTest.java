package com.pengin0503.itemmilestonelog;

import net.minecraft.network.chat.Component;
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
        assertTrue(fallbacks.containsKey("key.itemmilestonelog.add_hovered"), "missing key.itemmilestonelog.add_hovered");
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

    @Test
    void everyDiscordKeyUsedByServerCodeHasAnEnglishFallback() throws IOException {
        Path sources = resolveMainSourceDir();
        assertTrue(Files.isDirectory(sources), "sources not found at " + sources.toAbsolutePath());

        Pattern literal = Pattern.compile("\"(discord\\.itemmilestonelog\\.[a-z0-9_.]+)\"");
        Pattern dynamicPrefix = Pattern.compile("\"(discord\\.itemmilestonelog\\.[a-z0-9_.]*)\"\\s*\\+");
        Set<String> used = new TreeSet<>();
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String content = Files.readString(file);
                Matcher dyn = dynamicPrefix.matcher(content);
                assertFalse(dyn.find(), "Dynamic discord key concatenation in " + file.getFileName()
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
        assertTrue(used.size() >= 10, "expected to find the Discord lang keys, found " + used);
        used.add("discord.itemmilestonelog.preset_completed");

        Set<String> missing = new TreeSet<>(used);
        missing.removeAll(ServerLang.fallbacks().keySet());
        assertTrue(missing.isEmpty(), "discord keys without an en_us.json entry: " + missing);
    }

    @Test
    void formatForLangResolvesDirectlyFromBundledTablesAndFallsBackToEnglish() {
        // en_us matches 1.0.1 plain-text format byte-for-byte
        assertEquals(
                String.format("★ Milestone recorded: %s obtained %s at %s",
                        "Alice", "minecraft:diamond", "1h 00m 00s (Day 2, 2023-11-14)"),
                ServerLang.formatForLang("en_us", "discord.itemmilestonelog.recorded",
                        "Alice", "minecraft:diamond", "1h 00m 00s (Day 2, 2023-11-14)"));
        assertEquals(
                String.format("★ [World First] %s obtained %s at %s",
                        "Alice", "minecraft:diamond ×64", "1h 00m 00s (Day 2, 2023-11-14)"),
                ServerLang.formatForLang("en_us", "discord.itemmilestonelog.world_first",
                        "Alice", "minecraft:diamond ×64", "1h 00m 00s (Day 2, 2023-11-14)"));

        // ja_jp resolves directly from ja_jp.json
        assertEquals("★ マイルストーン記録: Alice が minecraft:diamond を入手（1h 00m 00s）",
                ServerLang.formatForLang("ja_jp", "discord.itemmilestonelog.recorded",
                        "Alice", "minecraft:diamond", "1h 00m 00s"));
        assertEquals("★ [世界初] Alice が minecraft:diamond を入手（1h 00m 00s）",
                ServerLang.formatForLang("ja_jp", "discord.itemmilestonelog.world_first",
                        "Alice", "minecraft:diamond", "1h 00m 00s"));
        assertEquals("Alice が Diamond を入手",
                ServerLang.formatForLang("ja_jp", "discord.itemmilestonelog.embed.recorded", "Alice", "Diamond"));
        assertEquals("★ 世界初 — Alice が Diamond を入手",
                ServerLang.formatForLang("ja_jp", "discord.itemmilestonelog.embed.world_first", "Alice", "Diamond"));
        assertEquals("プレイ時間", ServerLang.formatForLang("ja_jp", "discord.itemmilestonelog.field.playtime"));
        assertEquals("ゲーム内日数", ServerLang.formatForLang("ja_jp", "discord.itemmilestonelog.field.day"));
        assertEquals("日付", ServerLang.formatForLang("ja_jp", "discord.itemmilestonelog.field.date"));
        assertEquals("…ほか 5 件", ServerLang.formatForLang("ja_jp", "discord.itemmilestonelog.digest.more", 5));
        assertEquals("★ Alice completed all targets of Vanilla Endgame!",
                ServerLang.formatForLang("en_us", "discord.itemmilestonelog.preset_completed",
                        "Alice", "Vanilla Endgame"));
        assertEquals("★ Alice が Vanilla Endgame の目標をすべて達成しました！",
                ServerLang.formatForLang("ja_jp", "discord.itemmilestonelog.preset_completed",
                        "Alice", "Vanilla Endgame"));

        // Real template containing %%
        assertEquals("Progress: Diamond ×100 — 90 / 100 (90%)",
                ServerLang.formatForLang("en_us", "message.itemmilestonelog.progress_alert",
                        "Diamond ×100", 90, 100, 90));
        assertEquals("進捗: Diamond ×100 — 90 / 100（90%）",
                ServerLang.formatForLang("ja_jp", "message.itemmilestonelog.progress_alert",
                        "Diamond ×100", 90, 100, 90));

        // Case normalization, unbundled language fallback, and unknown/null key handling
        assertEquals("プレイ時間", ServerLang.formatForLang("ja_JP", "discord.itemmilestonelog.field.playtime"));
        assertEquals("Playtime", ServerLang.formatForLang("fr_fr", "discord.itemmilestonelog.field.playtime"));
        assertEquals("Playtime", ServerLang.formatForLang("", "discord.itemmilestonelog.field.playtime"));
        assertEquals("Playtime", ServerLang.formatForLang(null, "discord.itemmilestonelog.field.playtime"));
        assertEquals("discord.itemmilestonelog.does_not_exist",
                ServerLang.formatForLang("ja_jp", "discord.itemmilestonelog.does_not_exist", "ignored"));
        assertEquals("", ServerLang.formatForLang("en_us", null));
    }

    @Test
    void formatTemplateSupportsSequentialPositionalEscapedPercentAndSafeArgs() {
        // Sequential %s
        assertEquals("Alice obtained Diamond at 1h 00m 00s",
                ServerLang.formatTemplate("%s obtained %s at %s", "Alice", "Diamond", "1h 00m 00s"));

        // Positional %1$s / %2$s / %3$s (reordered and reused)
        assertEquals("1h 00m 00s: Diamond by Alice (Alice)",
                ServerLang.formatTemplate("%3$s: %2$s by %1$s (%1$s)", "Alice", "Diamond", "1h 00m 00s"));

        // Mixed positional and sequential (positional does not advance implicit counter)
        assertEquals("B-A-A-B",
                ServerLang.formatTemplate("%2$s-%s-%1$s-%s", "A", "B"));

        // Escaped percent %%
        assertEquals("100% done: Diamond (90%)",
                ServerLang.formatTemplate("100%% done: %s (%s%%)", "Diamond", 90));
        assertEquals("%X%", ServerLang.formatTemplate("%%%s%%", "X"));
        assertEquals("%%", ServerLang.formatTemplate("%%%%"));

        // Substituted arguments containing format/regex metacharacters are not re-interpreted
        assertEquals("Player%s$1\\Name obtained 100%%_%2$s",
                ServerLang.formatTemplate("%s obtained %s", "Player%s$1\\Name", "100%%_%2$s"));

        // Component arguments render via getString()
        assertEquals("Item: Diamond",
                ServerLang.formatTemplate("Item: %s", Component.literal("Diamond")));

        // Out-of-range, invalid, missing, and null arguments never throw
        assertEquals("A and %s", ServerLang.formatTemplate("%s and %s", "A"));
        assertEquals("%0$s %5$s %1$% 50% ok",
                ServerLang.formatTemplate("%0$s %5$s %1$% 50% ok", "A"));
        assertEquals("null", ServerLang.formatTemplate("%s", new Object[]{null}));
        assertEquals("%s", ServerLang.formatTemplate("%s", (Object[]) null));
        assertEquals("plain text", ServerLang.formatTemplate("plain text", "unused"));
        assertEquals("", ServerLang.formatTemplate(null, "unused"));
    }
}
