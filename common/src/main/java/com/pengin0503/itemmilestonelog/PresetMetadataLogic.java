package com.pengin0503.itemmilestonelog;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Pure-Java validation/normalization of preset metadata values (spec 1.4.0 F-140-7 / F-140-8 /
 * F-140-9 / F-140-14), shared across all Minecraft versions.
 *
 * <p>The JSON parser passes the raw scalar text here so the edge cases (integer overflow,
 * fractional numbers, control characters, blank values, NaN/Infinity) are unit-testable
 * without Minecraft or Gson.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class PresetMetadataLogic {
    /** Maximum stored length of {@code category}, in UTF-16 code units. */
    public static final int MAX_CATEGORY_CHARS = 64;

    /** Maximum length of a mod ID accepted in {@code requiredMods}. */
    public static final int MAX_MOD_ID_CHARS = 64;

    private static final Pattern MOD_ID_PATTERN = Pattern.compile("^[a-z0-9_.-]{1,64}$");

    private PresetMetadataLogic() {
    }

    /**
     * Normalizes a {@code category}: removes ISO control characters, trims the ends, caps the
     * result at {@value #MAX_CATEGORY_CHARS} code units, and returns {@code null} when nothing
     * usable remains.
     */
    public static String normalizeCategory(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder cleaned = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!Character.isISOControl(c)) {
                cleaned.append(c);
            }
        }
        String value = cleaned.toString().trim();
        if (value.isEmpty()) {
            return null;
        }
        return TextEscaping.truncateUtf16(value, MAX_CATEGORY_CHARS);
    }

    /** Parses an {@code order} integer; returns {@code null} for anything that is not a plain int. */
    public static Integer parseOrder(String raw) {
        return parseStrictInt(raw);
    }

    /** Parses a {@code version} (int {@code >= 1}); anything else returns {@code null} (unversioned). */
    public static Integer parseVersion(String raw) {
        Integer parsed = parseStrictInt(raw);
        return parsed != null && parsed >= 1 ? parsed : null;
    }

    /**
     * Parses {@code unlockAfterHours} (a finite double {@code >= 0}); negative, non-numeric,
     * NaN and infinite values return {@code null} (unset).
     */
    public static Double parseUnlockAfterHours(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            double value = Double.parseDouble(raw.trim());
            if (Double.isNaN(value) || Double.isInfinite(value) || value < 0.0d) {
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Whether {@code modId} is a plausible lower-case mod ID accepted by {@code requiredMods}. */
    public static boolean isValidModId(String modId) {
        if (modId == null || modId.isEmpty() || modId.length() > MAX_MOD_ID_CHARS) {
            return false;
        }
        return MOD_ID_PATTERN.matcher(modId).matches();
    }

    /**
     * An integral JSON number text (optional leading {@code -}), or {@code null} when invalid,
     * fractional or out of int range. Draft 2020-12 treats integral decimals and exponents such
     * as {@code 1.0} and {@code 1e0} as integers, so they are accepted here as well as plain
     * decimal integers (SF-6). Non-finite values and pathological exponents are rejected.
     */
    static Integer parseStrictInt(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty() || value.charAt(0) == '+') {
            return null;
        }
        try {
            BigDecimal number = new BigDecimal(value);
            // Bound the scale before intValueExact materializes any large power of ten.
            if (number.scale() < -10 || number.scale() > 10) {
                return null;
            }
            return number.intValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }
}
