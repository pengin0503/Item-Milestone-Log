package com.pengin0503.itemmilestonelog;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Pure-Java preset display name shared across all Minecraft versions via
 * {@code common/src/main/java} (spec 1.2.0 F54).
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 *
 * <p>An F54 preset display name is either:
 * <ul>
 *   <li>a single plain string used for every language (the historical JSON
 *       {@code "name": "Starter Kit"} form), or</li>
 *   <li>an insertion-ordered language code to name map (the JSON
 *       {@code "name": {"en_us": "Starter Kit", "ja_jp": "スターターキット"}} form).
 *       Language keys are normalized to lower case; entries with blank keys or blank
 *       values are dropped; each value is capped at {@value #MAX_NAME_CHARS}
 *       UTF-16 code units.</li>
 * </ul>
 * A name with no valid entry at all is "absent": every resolution falls back to the
 * preset ID, exactly as a preset with no {@code name} field.
 */
public final class PresetName {
    /**
     * Cap applied to each stored value (and to a plain name), matching the sync payload's
     * display-name limit so a hand-written preset cannot break the sync for clients.
     * F54 applies it per language value.
     */
    public static final int MAX_NAME_CHARS = 256;

    /** Fallback language of the resolution order (spec F54 priority 2). */
    private static final String DEFAULT_LANGUAGE = "en_us";

    /** The plain all-languages name, or null when the name is localized or absent. */
    private final String plain;
    /** Insertion-ordered language code to name map; empty unless the name is localized. */
    private final Map<String, String> localized;

    private PresetName(String plain, Map<String, String> localized) {
        this.plain = plain;
        this.localized = localized;
    }

    /** The name meaning "not specified": every resolution falls back to the preset ID. */
    public static PresetName absent() {
        return new PresetName(null, Map.of());
    }

    /**
     * A name that is the same trimmed string for every language. Null, empty and
     * blank-only values are absent.
     */
    public static PresetName ofPlain(String raw) {
        String value = normalizeValue(raw);
        return value == null ? absent() : new PresetName(value, Map.of());
    }

    /**
     * A per-language name built from {@code values} in iteration order (JSON writing order,
     * kept via a {@link LinkedHashMap}). Keys are trimmed and lower-cased; entries with blank
     * keys or blank values are dropped; each surviving value is trimmed and capped. A map
     * with no surviving entry is absent.
     */
    public static PresetName ofLocalized(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return absent();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = normalizeKey(entry.getKey());
            String value = normalizeValue(entry.getValue());
            if (key == null || value == null) {
                continue;
            }
            // A repeated language keeps its first insertion position and takes the later
            // value, matching how a JSON object merges duplicate keys.
            normalized.put(key, value);
        }
        return normalized.isEmpty() ? absent() : new PresetName(null, Collections.unmodifiableMap(normalized));
    }

    /** Whether any name is stored at all (absent names resolve to the preset ID). */
    public boolean present() {
        return plain != null || !localized.isEmpty();
    }

    /** Whether the name is the per-language (object) form rather than a plain string. */
    public boolean localizedForm() {
        return plain == null && !localized.isEmpty();
    }

    /** The plain all-languages name, or null when the name is localized or absent. */
    public String plainValue() {
        return plain;
    }

    /** The insertion-ordered language code to name map; empty unless {@link #localizedForm()}. */
    public Map<String, String> localizedValues() {
        return localized;
    }

    /**
     * Resolves the display name for {@code languageCode} (spec F54 priority order):
     * <ol>
     *   <li>the value whose language code (lower-cased) equals the lower-cased
     *       {@code languageCode},</li>
     *   <li>the {@code "en_us"} value,</li>
     *   <li>the first value in insertion order,</li>
     *   <li>{@code fallback} (the preset ID).</li>
     * </ol>
     * A plain name resolves to itself for every language. A null or blank language code
     * (console / RCON) skips the exact match, so it resolves with {@code en_us} priority.
     */
    public String resolve(String languageCode, String fallback) {
        if (plain != null) {
            return plain;
        }
        if (!localized.isEmpty()) {
            if (languageCode != null && !languageCode.isBlank()) {
                String match = localized.get(languageCode.trim().toLowerCase(Locale.ROOT));
                if (match != null) {
                    return match;
                }
            }
            String enUs = localized.get(DEFAULT_LANGUAGE);
            if (enUs != null) {
                return enUs;
            }
            String first = localized.values().iterator().next();
            if (first != null) {
                return first;
            }
        }
        return fallback != null ? fallback : "";
    }

    /** Value equality, so {@code PresetDefinition} stays a value type like before F54. */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof PresetName other)) {
            return false;
        }
        return Objects.equals(plain, other.plain) && localized.equals(other.localized);
    }

    @Override
    public int hashCode() {
        return Objects.hash(plain, localized);
    }

    /** A language key: trimmed, non-blank, lower-cased — or null when unusable. */
    private static String normalizeKey(String raw) {
        if (raw == null) {
            return null;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        return key.isEmpty() ? null : key;
    }

    /** A name value: trimmed, capped at {@value #MAX_NAME_CHARS} code units — or null when unusable. */
    private static String normalizeValue(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        return TextEscaping.truncateUtf16(value, MAX_NAME_CHARS);
    }
}
