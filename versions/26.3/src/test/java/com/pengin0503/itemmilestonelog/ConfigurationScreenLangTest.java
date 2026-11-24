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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F44: every setting shown on NeoForge's generic configuration screen must have a display name
 * and a tooltip in every bundled language. The webhook URLs are intentionally hidden from the
 * screen (F39/F44), so they have no screen text.
 */
class ConfigurationScreenLangTest {
    private static final Set<String> HIDDEN_FROM_SCREEN = Set.of(
            "discordWebhookUrl",
            "discordWorldFirstWebhookUrl");

    @Test
    void everySettingShownOnTheConfigurationScreenHasATranslatedNameAndTooltip() throws Exception {
        // Enumerate Config.SPEC programmatically so a newly added setting cannot be forgotten.
        Set<String> specKeys = new TreeSet<>(Config.SPEC.getValues().valueMap().keySet());

        assertTrue(specKeys.containsAll(HIDDEN_FROM_SCREEN),
                "F44 hides the webhook URLs from the configuration screen, but Config.SPEC is missing: "
                        + HIDDEN_FROM_SCREEN);
        specKeys.removeAll(HIDDEN_FROM_SCREEN);
        assertFalse(specKeys.isEmpty(), "Config.SPEC must contain settings to show on the configuration screen");

        for (String language : new String[] { "en_us", "ja_jp" }) {
            Map<String, String> translations = read(language);
            Set<String> missing = new TreeSet<>();
            for (String key : specKeys) {
                String nameKey = "itemmilestonelog.configuration." + key;
                if (!translations.containsKey(nameKey)) {
                    missing.add(nameKey);
                }
                if (!translations.containsKey(nameKey + ".tooltip")) {
                    missing.add(nameKey + ".tooltip");
                }
            }
            if (!translations.containsKey("itemmilestonelog.configuration.title")) {
                missing.add("itemmilestonelog.configuration.title");
            }
            assertTrue(missing.isEmpty(), "Missing configuration screen keys in " + language + ".json: " + missing);
        }
    }

    private static Map<String, String> read(String language) throws Exception {
        String path = "/assets/" + ItemMilestoneLog.MOD_ID + "/lang/" + language + ".json";
        try (InputStream in = ConfigurationScreenLangTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path + " is missing");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                Map<String, String> map = new TreeMap<>();
                for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                    map.put(entry.getKey(), entry.getValue().getAsString());
                }
                return map;
            }
        }
    }
}
