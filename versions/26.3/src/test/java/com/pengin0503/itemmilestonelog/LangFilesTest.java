package com.pengin0503.itemmilestonelog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps the shared translation files in step with each other. */
class LangFilesTest {
    private static final Pattern ANY_FORMAT_SPECIFIER = Pattern.compile("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?([a-zA-Z%])");

    @Test
    void japaneseHasExactlyTheEnglishKeys() throws Exception {
        Map<String, String> en = read("en_us");
        Map<String, String> ja = read("ja_jp");
        assertEquals(en.keySet(), ja.keySet());
    }

    @Test
    void translationsKeepTheSameNumberOfPlaceholdersAndValidFormatSpecifiers() throws Exception {
        Map<String, String> en = read("en_us");
        Map<String, String> ja = read("ja_jp");
        for (Map.Entry<String, String> entry : en.entrySet()) {
            String key = entry.getKey();
            String enVal = entry.getValue();
            String jaVal = ja.get(key);
            assertNotNull(jaVal, "Missing Japanese translation for " + key);
            List<Integer> enSlots = extractFormatSlots(key + " (en_us)", enVal);
            List<Integer> jaSlots = extractFormatSlots(key + " (ja_jp)", jaVal);
            assertEquals(enSlots, jaSlots, "placeholder slots differ for " + key);
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
