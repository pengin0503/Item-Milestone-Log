package com.pengin0503.itemmilestonelog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps the shared translation files in step with each other and with {@link ServerLang}. */
class LangFilesTest {
    private static final Pattern ANY_FORMAT_SPECIFIER = Pattern.compile("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?([a-zA-Z%])");
    private static final Path LANG_REL_PATH = Path.of("common", "resources", "assets", ItemMilestoneLog.MOD_ID, "lang");

    @SuppressWarnings("unchecked")
    private static List<String> bundledLanguages() throws Exception {
        Field f = ServerLang.class.getDeclaredField("BUNDLED_LANGUAGES");
        f.setAccessible(true);
        return (List<String>) f.get(null);
    }

    private static Path resolveLangDir() throws Exception {
        String mainSourceDir = System.getProperty("itemmilestonelog.mainSourceDir");
        if (mainSourceDir != null && !mainSourceDir.isBlank()) {
            Path cur = Path.of(mainSourceDir).toAbsolutePath().normalize();
            for (int i = 0; i < 6 && cur != null; i++) {
                Path candidate = cur.resolve(LANG_REL_PATH);
                if (Files.isDirectory(candidate)) {
                    return candidate;
                }
                cur = cur.getParent();
            }
        }
        Path fromVersionDir = Path.of("..", "..").resolve(LANG_REL_PATH).normalize();
        if (Files.isDirectory(fromVersionDir)) {
            return fromVersionDir;
        }
        if (Files.isDirectory(LANG_REL_PATH)) {
            return LANG_REL_PATH;
        }
        URL url = LangFilesTest.class.getResource("/assets/" + ItemMilestoneLog.MOD_ID + "/lang/en_us.json");
        if (url != null && "file".equals(url.getProtocol())) {
            Path parent = Path.of(url.toURI()).getParent();
            if (parent != null && Files.isDirectory(parent)) {
                return parent;
            }
        }
        throw new AssertionError("Could not locate lang directory for " + ItemMilestoneLog.MOD_ID);
    }

    @Test
    void bundledLanguagesMatchLangDirectoryFiles() throws Exception {
        List<String> bundled = bundledLanguages();
        Set<String> bundledSet = new LinkedHashSet<>(bundled);
        assertEquals(bundled.size(), bundledSet.size(), "ServerLang.BUNDLED_LANGUAGES must not contain duplicates: " + bundled);
        assertTrue(bundledSet.contains("en_us"), "en_us must be in ServerLang.BUNDLED_LANGUAGES");
        assertTrue(bundledSet.contains("ja_jp"), "ja_jp must be in ServerLang.BUNDLED_LANGUAGES");
        for (String lang : bundled) {
            assertEquals(lang.toLowerCase(Locale.ROOT), lang,
                    "ServerLang.BUNDLED_LANGUAGES entries must be lowercase: " + lang);
        }

        Path langDir = resolveLangDir();
        Set<String> diskLangs = new TreeSet<>();
        try (Stream<Path> files = Files.list(langDir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith(".json")) {
                    diskLangs.add(name.substring(0, name.length() - ".json".length()));
                }
            }
        }

        assertEquals(diskLangs, new TreeSet<>(bundledSet),
                "Lang files in " + langDir + " must match ServerLang.BUNDLED_LANGUAGES");
    }

    @Test
    void japaneseHasExactlyTheEnglishKeys() throws Exception {
        Map<String, String> en = read("en_us");
        Map<String, String> ja = read("ja_jp");
        assertEquals(en.keySet(), ja.keySet());
    }

    @Test
    void filterFeatureKeysExistInBothLanguages() throws Exception {
        Map<String, String> en = read("en_us");
        Map<String, String> ja = read("ja_jp");
        for (String key : List.of(
                "message.itemmilestonelog.filter_empty_query",
                "message.itemmilestonelog.filter_query_too_long",
                "message.itemmilestonelog.filter_invalid_kind",
                "message.itemmilestonelog.targets_filter_none",
                "message.itemmilestonelog.list_filter_none")) {
            assertNotNull(en.get(key), "Missing en_us key: " + key);
            assertFalse(en.get(key).isBlank(), "Blank en_us key: " + key);
            assertNotNull(ja.get(key), "Missing ja_jp key: " + key);
            assertFalse(ja.get(key).isBlank(), "Blank ja_jp key: " + key);
        }
    }

    @Test
    void searchFeatureKeysExistInBothLanguages() throws Exception {
        Map<String, String> en = read("en_us");
        Map<String, String> ja = read("ja_jp");
        for (String key : List.of(
                "message.itemmilestonelog.help.search",
                "message.itemmilestonelog.search_hit",
                "message.itemmilestonelog.search_invalid_scope",
                "message.itemmilestonelog.search_more",
                "message.itemmilestonelog.search_none",
                "message.itemmilestonelog.search_source_preset",
                "message.itemmilestonelog.search_source_record",
                "message.itemmilestonelog.search_source_target")) {
            assertNotNull(en.get(key), "Missing en_us key: " + key);
            assertFalse(en.get(key).isBlank(), "Blank en_us key: " + key);
            assertNotNull(ja.get(key), "Missing ja_jp key: " + key);
            assertFalse(ja.get(key).isBlank(), "Blank ja_jp key: " + key);
        }
    }

    @Test
    void rankingFeatureKeysExistInBothLanguages() throws Exception {
        Map<String, String> en = read("en_us");
        Map<String, String> ja = read("ja_jp");
        for (String key : List.of(
                "message.itemmilestonelog.presettop_header",
                "message.itemmilestonelog.presettop_entry",
                "message.itemmilestonelog.presettop_entry_done",
                "message.itemmilestonelog.presettop_self",
                "message.itemmilestonelog.presettop_self_none",
                "message.itemmilestonelog.presettop_empty",
                "message.itemmilestonelog.help.presettop",
                // F-150-10 (PR-3)
                "message.itemmilestonelog.firsts_close_header",
                "message.itemmilestonelog.firsts_close_entry",
                "message.itemmilestonelog.firsts_close_none",
                "message.itemmilestonelog.firsts_top_header",
                "message.itemmilestonelog.firsts_top_entry",
                "message.itemmilestonelog.firsts_top_empty",
                "message.itemmilestonelog.firsts_unclaimed_header",
                "message.itemmilestonelog.firsts_unclaimed_entry",
                "message.itemmilestonelog.firsts_unclaimed_entry_preset",
                "message.itemmilestonelog.firsts_unclaimed_none",
                "message.itemmilestonelog.firsts_unclaimed_more",
                "message.itemmilestonelog.firsts_unclaimed_invalid_scope",
                // F-150-7 / F-150-8 / F-150-9 (PR-2)
                "message.itemmilestonelog.stats_item_header",
                "message.itemmilestonelog.stats_item_values",
                "message.itemmilestonelog.stats_item_empty",
                "message.itemmilestonelog.help.statstag",
                "message.itemmilestonelog.top_item_entry_gaps",
                "message.itemmilestonelog.top_item_self_gaps",
                "message.itemmilestonelog.top_item_gaps_note",
                "message.itemmilestonelog.gap_none",
                "message.itemmilestonelog.whohas_header",
                "message.itemmilestonelog.whohas_recorded_entry",
                "message.itemmilestonelog.whohas_targeting_entry",
                "message.itemmilestonelog.whohas_empty",
                "message.itemmilestonelog.whohas_more",
                "message.itemmilestonelog.help.whohas",
                "message.itemmilestonelog.help.whohastag")) {
            assertNotNull(en.get(key), "Missing en_us key: " + key);
            assertFalse(en.get(key).isBlank(), "Blank en_us key: " + key);
            assertNotNull(ja.get(key), "Missing ja_jp key: " + key);
            assertFalse(ja.get(key).isBlank(), "Blank ja_jp key: " + key);
        }
    }

    @Test
    void translationsKeepTheSameNumberOfPlaceholdersAndValidFormatSpecifiers() throws Exception {
        Map<String, String> en = read("en_us");
        for (Map.Entry<String, String> entry : en.entrySet()) {
            extractFormatSlots(entry.getKey() + " (en_us)", entry.getValue());
        }
        for (String lang : bundledLanguages()) {
            if ("en_us".equals(lang)) {
                continue;
            }
            Map<String, String> translations = read(lang);
            validateLanguageAgainstEnglish(lang, en, translations, "ja_jp".equals(lang));
        }
    }

    @Test
    void validationRulesAllowPartialThirdLanguagesButRejectUnknownKeysAndMismatchedPlaceholders() {
        Map<String, String> en = Map.of(
                "message.itemmilestonelog.added", "Now tracking: %s",
                "message.itemmilestonelog.summary", "Playtime: %s | Milestones: %s / %s"
        );

        // Partial 3rd language (missing a key) with matching placeholders is allowed
        validateLanguageAgainstEnglish("de_de", en,
                Map.of("message.itemmilestonelog.added", "Verfolge jetzt: %s"),
                false);

        // Unknown key not in en_us fails even for partial languages
        assertThrows(AssertionError.class, () -> validateLanguageAgainstEnglish("de_de", en,
                Map.of("message.itemmilestonelog.unknown_extra", "Extra"),
                false));

        // Mismatched placeholder slots fail
        assertThrows(AssertionError.class, () -> validateLanguageAgainstEnglish("de_de", en,
                Map.of("message.itemmilestonelog.added", "Verfolge: %s (%s)"),
                false));

        // Non-%s format specifier fails
        assertThrows(AssertionError.class, () -> validateLanguageAgainstEnglish("de_de", en,
                Map.of("message.itemmilestonelog.added", "Verfolge: %d"),
                false));

        // Strict language (ja_jp) fails when a key is missing
        assertThrows(AssertionError.class, () -> validateLanguageAgainstEnglish("ja_jp", en,
                Map.of("message.itemmilestonelog.added", "追加しました: %s"),
                true));
    }

    private static void validateLanguageAgainstEnglish(
            String language,
            Map<String, String> en,
            Map<String, String> translations,
            boolean requireAllKeys) {
        Set<String> extra = new TreeSet<>(translations.keySet());
        extra.removeAll(en.keySet());
        assertTrue(extra.isEmpty(), "Keys in " + language + ".json absent from en_us.json: " + extra);

        if (requireAllKeys) {
            Set<String> missing = new TreeSet<>(en.keySet());
            missing.removeAll(translations.keySet());
            assertTrue(missing.isEmpty(), "Missing keys in " + language + ".json: " + missing);
        }

        for (Map.Entry<String, String> entry : translations.entrySet()) {
            String key = entry.getKey();
            String enVal = en.get(key);
            assertNotNull(enVal, "Missing English key for " + key);
            List<Integer> enSlots = extractFormatSlots(key + " (en_us)", enVal);
            List<Integer> langSlots = extractFormatSlots(key + " (" + language + ")", entry.getValue());
            assertEquals(enSlots, langSlots, "placeholder slots differ for " + key + " in " + language);
        }
    }

    private static List<Integer> extractFormatSlots(String label, String text) {
        Matcher matcher = ANY_FORMAT_SPECIFIER.matcher(text);
        List<Integer> slots = new ArrayList<>();
        int implicitIndex = 1;
        while (matcher.find()) {
            String conv = matcher.group(3);
            if ("%".equals(conv)) {
                continue;
            }
            assertEquals("s", conv, "Only %s format specifiers are supported in Minecraft lang files: " + label);
            String pos = matcher.group(1);
            int index;
            if (pos != null) {
                index = Integer.parseInt(pos.substring(0, pos.length() - 1));
            } else {
                index = implicitIndex++;
            }
            assertTrue(index >= 1, "Invalid placeholder index in " + label);
            slots.add(index);
        }
        slots.sort(Integer::compareTo);
        for (int i = 0; i < slots.size(); i++) {
            assertEquals(i + 1, slots.get(i), "Disjoint or duplicate placeholder indices in " + label + ": " + slots);
        }
        return slots;
    }

    private static Map<String, String> read(String language) throws Exception {
        String path = "/assets/" + ItemMilestoneLog.MOD_ID + "/lang/" + language + ".json";
        try (InputStream in = LangFilesTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path + " is missing");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                Map<String, String> map = new TreeMap<>();
                for (Map.Entry<String, JsonElement> e : json.entrySet()) {
                    map.put(e.getKey(), e.getValue().getAsString());
                }
                return map;
            }
        }
    }
}
