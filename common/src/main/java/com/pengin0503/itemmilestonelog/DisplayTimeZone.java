package com.pengin0503.itemmilestonelog;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Pure-Java validation and resolution for the {@code displayTimeZone} config option (F11).
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class DisplayTimeZone {
    private record CachedZone(String raw, ZoneId zone) {
    }

    private static volatile CachedZone cached = new CachedZone("", null);

    private DisplayTimeZone() {
    }

    /**
     * Validates a {@code displayTimeZone} config value: empty/blank (use server default) or
     * any string accepted by {@link ZoneId#of(String)} (e.g. {@code "UTC"}, {@code "Asia/Tokyo"},
     * {@code "+09:00"}).
     */
    public static boolean isValidConfigValue(Object obj) {
        if (!(obj instanceof String s)) {
            return false;
        }
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        try {
            ZoneId.of(trimmed);
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }

    /**
     * Resolves a configured timezone string to a {@link ZoneId}.
     * {@code null}, blank, or invalid values fall back to {@link ZoneId#systemDefault()}.
     * Non-empty valid values are cached by string so config reloads take effect immediately.
     */
    public static ZoneId resolve(String raw) {
        if (raw == null) {
            return ZoneId.systemDefault();
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return ZoneId.systemDefault();
        }
        CachedZone snapshot = cached;
        if (Objects.equals(snapshot.raw(), trimmed) && snapshot.zone() != null) {
            return snapshot.zone();
        }
        try {
            ZoneId resolved = ZoneId.of(trimmed);
            cached = new CachedZone(trimmed, resolved);
            return resolved;
        } catch (DateTimeException e) {
            return ZoneId.systemDefault();
        }
    }
}
