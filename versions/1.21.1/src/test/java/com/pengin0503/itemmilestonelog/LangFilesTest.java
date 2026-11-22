package com.pengin0503.itemmilestonelog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Keeps the shared translation files in step with each other. */
class LangFilesTest {
    private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+\\$)?s");

    @Test
    void japaneseHasExactlyTheEnglishKeys() throws Exception {
        Map<String, String> en = read("en_us");
        Map<String, String> ja = read("ja_jp");
        assertEquals(en.keySet(), ja.keySet());
    }

    @Test
    void translationsKeepTheSameNumberOfPlaceholders() throws Exception {
        Map<String, String> en = read("en_us");
        Map<String, String> ja = read("ja_jp");
        for (Map.Entry<String, String> entry : en.entrySet()) {
            String translated = ja.get(entry.getKey());
            if (translated != null) {
                assertEquals(placeholders(entry.getValue()), placeholders(translated),
                        "placeholder count differs for " + entry.getKey());
            }
        }
    }

    private static int placeholders(String text) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
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
