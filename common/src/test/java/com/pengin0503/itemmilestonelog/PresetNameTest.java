package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresetNameTest {
    private static Map<String, String> linked(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    void plainNameResolvesToItselfForEveryLanguage() {
        PresetName plain = PresetName.ofPlain("100% Pack %s Goals");

        assertTrue(plain.present());
        assertFalse(plain.localizedForm());
        assertEquals("100% Pack %s Goals", plain.plainValue());
        assertTrue(plain.localizedValues().isEmpty());

        // A plain string name is used for every language, unchanged (historical behavior)
        assertEquals("100% Pack %s Goals", plain.resolve("en_us", "fallback_id"));
        assertEquals("100% Pack %s Goals", plain.resolve("ja_jp", "fallback_id"));
        assertEquals("100% Pack %s Goals", plain.resolve("JA_JP", "fallback_id"));
        assertEquals("100% Pack %s Goals", plain.resolve("fr_fr", "fallback_id"));
        assertEquals("100% Pack %s Goals", plain.resolve(null, "fallback_id"));
        assertEquals("100% Pack %s Goals", plain.resolve("", "fallback_id"));
        assertEquals("100% Pack %s Goals", plain.resolve("   ", "fallback_id"));
    }

    @Test
    void plainNameIsTrimmedCappedAndBlankValuesAreAbsent() {
        assertEquals("Starter Kit", PresetName.ofPlain("  Starter Kit  ").plainValue());

        assertEquals("A".repeat(256), PresetName.ofPlain("A".repeat(300)).plainValue());
        // Never splits a surrogate pair at the cap
        String straddling = "A".repeat(255) + "\uD83D\uDC8E" + "tail";
        assertEquals("A".repeat(255), PresetName.ofPlain(straddling).plainValue());

        PresetName blank = PresetName.ofPlain("   ");
        assertFalse(blank.present());
        assertNull(blank.plainValue());
        assertEquals("fallback_id", blank.resolve("en_us", "fallback_id"));

        PresetName nil = PresetName.ofPlain(null);
        assertFalse(nil.present());
        assertEquals("fallback_id", nil.resolve("ja_jp", "fallback_id"));

        // Value equality: two separately built names with the same content are equal
        assertEquals(PresetName.ofPlain("Starter Kit"), PresetName.ofPlain("Starter Kit"));
        assertEquals(PresetName.absent(), PresetName.ofPlain(null));
        assertEquals(PresetName.ofLocalized(Map.of("en_us", "A", "ja_jp", "B")),
                PresetName.ofLocalized(Map.of("ja_jp", "B", "en_us", "A")));
        assertFalse(PresetName.ofPlain("A").equals(PresetName.ofLocalized(Map.of("en_us", "A"))));
    }

    @Test
    void localizedNameResolvesExactMatchThenEnUsThenFirstThenFallback() {
        PresetName both = PresetName.ofLocalized(linked(
                "ja_jp", "スターターキット",
                "en_us", "Starter Kit"));

        assertTrue(both.present());
        assertTrue(both.localizedForm());

        // 1. Exact (case-insensitive) language match
        assertEquals("Starter Kit", both.resolve("en_us", "id"));
        assertEquals("Starter Kit", both.resolve("EN_US", "id"));
        assertEquals("スターターキット", both.resolve("ja_jp", "id"));
        assertEquals("スターターキット", both.resolve("JA_JP", "id"));

        // 2. No exact match -> en_us
        assertEquals("Starter Kit", both.resolve("de_de", "id"));
        assertEquals("Starter Kit", both.resolve(null, "id"));
        assertEquals("Starter Kit", both.resolve("", "id"));
        assertEquals("Starter Kit", both.resolve("   ", "id"));

        // 3. No exact match and no en_us -> first valid value in insertion order
        PresetName noEn = PresetName.ofLocalized(linked(
                "ja_jp", "日本語",
                "fr_fr", "Français"));
        assertEquals("日本語", noEn.resolve("en_us", "id"));
        assertEquals("日本語", noEn.resolve("de_de", "id"));
        assertEquals("日本語", noEn.resolve(null, "id"));
        assertEquals("Français", noEn.resolve("fr_fr", "id"));

        // 4. Absent name -> fallback (the preset ID)
        PresetName absent = PresetName.absent();
        assertFalse(absent.present());
        assertEquals("fallback_id", absent.resolve("en_us", "fallback_id"));
        assertEquals("fallback_id", absent.resolve(null, "fallback_id"));
        assertEquals("", absent.resolve("ja_jp", null));
    }

    @Test
    void localizedNameNormalizesKeysToLowerCaseAndKeepsInsertionOrder() {
        PresetName name = PresetName.ofLocalized(linked(
                "  EN_US  ", "Starter Kit",
                "Ja_JP", "スターターキット"));

        // Keys are stored normalized, so an upper-cased query still matches exactly
        assertEquals(Map.of("en_us", "Starter Kit", "ja_jp", "スターターキット"), name.localizedValues());
        assertEquals("Starter Kit", name.resolve("en_us", "id"));
        assertEquals("スターターキット", name.resolve("ja_JP", "id"));

        // Spec F54 priority: exact language, then "en_us", then first value, then ID.
        // (An earlier draft of this test expected insertion order to beat "en_us";
        // the spec's priority list puts "en_us" second, so de_de resolves to English.)
        PresetName reversed = PresetName.ofLocalized(linked(
                "ja_jp", "日本語",
                "en_us", "English"));
        assertEquals(java.util.List.of("ja_jp", "en_us"),
                java.util.List.copyOf(reversed.localizedValues().keySet()));
        assertEquals("English", reversed.resolve("de_de", "id"));

        // Without an "en_us" entry, insertion order decides the fallback
        PresetName noEnglish = PresetName.ofLocalized(linked(
                "ja_jp", "日本語",
                "de_de", "Deutsch"));
        assertEquals("日本語", noEnglish.resolve("fr_fr", "id"));

        // A repeated language keeps its first position and takes the later value
        PresetName repeated = PresetName.ofLocalized(linked(
                "en_us", "First",
                "EN_US", "Second"));
        assertEquals(java.util.List.of("en_us"), java.util.List.copyOf(repeated.localizedValues().keySet()));
        assertEquals("Second", repeated.resolve("de_de", "id"));

        // The value map handed out is unmodifiable
        assertThrows(UnsupportedOperationException.class,
                () -> name.localizedValues().put("fr_fr", "nope"));
    }

    @Test
    void localizedNameIgnoresBlankKeysAndUnusableValues() {
        PresetName name = PresetName.ofLocalized(linked(
                "", "blank key",
                "   ", "blank key too",
                null, "null key",
                "en_us", "   ",
                "ja_jp", (String) null,
                "fr_fr", "Kit de départ"));

        // Only the one valid entry survives; blanks and nulls never resolve
        assertEquals(Map.of("fr_fr", "Kit de départ"), name.localizedValues());
        assertEquals("Kit de départ", name.resolve("fr_fr", "id"));
        assertEquals("Kit de départ", name.resolve("en_us", "id"));

        // No valid entry at all -> absent (display name = preset ID)
        PresetName allInvalid = PresetName.ofLocalized(linked(
                "", "x",
                "de_de", "   ",
                "ja_jp", (String) null));
        assertFalse(allInvalid.present());
        assertEquals("fallback_id", allInvalid.resolve("ja_jp", "fallback_id"));

        // Null and empty maps are absent, and null keys/values never throw
        assertFalse(PresetName.ofLocalized(null).present());
        assertFalse(PresetName.ofLocalized(Map.of()).present());
        assertFalse(PresetName.ofLocalized(linked("en_us", (String) null)).present());
    }

    @Test
    void localizedNameAppliesThe256CharCapPerLanguageValue() {
        PresetName name = PresetName.ofLocalized(linked(
                "en_us", "E".repeat(300),
                "ja_jp", "J".repeat(10),
                "fr_fr", "F".repeat(256)));

        // The cap applies per language value, not to the whole object
        assertEquals("E".repeat(256), name.resolve("en_us", "id"));
        assertEquals("J".repeat(10), name.resolve("ja_jp", "id"));
        assertEquals("F".repeat(256), name.resolve("fr_fr", "id"));

        // Exactly 256 stays unchanged; the cap never splits a surrogate pair
        PresetName exact = PresetName.ofLocalized(Map.of("en_us", "A".repeat(256)));
        assertEquals("A".repeat(256), exact.resolve("en_us", "id"));
        PresetName straddling = PresetName.ofLocalized(Map.of("ja_jp", "日".repeat(255) + "\uD83D\uDC8E" + "tail"));
        String resolved = straddling.resolve("ja_jp", "id");
        assertEquals(255, resolved.length());
        assertFalse(Character.isHighSurrogate(resolved.charAt(resolved.length() - 1)));
    }
}
