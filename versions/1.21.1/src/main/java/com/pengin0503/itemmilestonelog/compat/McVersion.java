package com.pengin0503.itemmilestonelog.compat;

import net.minecraft.SharedConstants;

/**
 * N252: detects the running Minecraft version of the 1.21.x line once, for compat
 * implementation selection (docs/DESIGN_1.3.0.md). Each constant is an API profile:
 * the first patch release it covers and the package of its compat implementations.
 * Unknown or newer 1.21.x versions clamp to the nearest lower known profile; anything
 * that is not 1.21.x resolves to {@link #V121_1}.
 */
public enum McVersion {
    /** 1.21, 1.21.1. */
    V121_1(0, "v121_1"),
    /** 1.21.2, 1.21.3. */
    V121_2(2, "v121_2"),
    /** 1.21.4. */
    V121_4(4, "v121_4"),
    /** 1.21.5. */
    V121_5(5, "v121_5"),
    /** 1.21.6, 1.21.7. */
    V121_6(6, "v121_6"),
    /** 1.21.8. */
    V121_8(8, "v121_8"),
    /** 1.21.9, 1.21.10. */
    V121_9(9, "v121_9"),
    /** 1.21.11. */
    V121_11(11, "v121_11");

    private static final String LINE_PREFIX = "1.21";
    private static final McVersion CURRENT = detect();

    private final int firstPatch;
    private final String packageSuffix;

    McVersion(int firstPatch, String packageSuffix) {
        this.firstPatch = firstPatch;
        this.packageSuffix = packageSuffix;
    }

    public static McVersion current() {
        return CURRENT;
    }

    /** Package fragment of this version's compat implementations, e.g. "v121_1". */
    public String packageSuffix() {
        return packageSuffix;
    }

    private static McVersion detect() {
        String name = versionName();
        if (name == null) {
            return V121_1;
        }
        int patch = patchOf(name);
        if (patch < 0) {
            return V121_1;
        }
        McVersion result = V121_1;
        for (McVersion v : values()) {
            if (patch >= v.firstPatch) {
                result = v;
            }
        }
        return result;
    }

    /**
     * Name of the running Minecraft version, or null when it cannot be determined.
     * Resolved reflectively because each accessor exists only in part of the 1.21.x line:
     * {@code SharedConstants.VERSION_STRING} up to 1.21.8 (removed in 1.21.9, so even a
     * direct reference fails to compile there), and the {@code WorldVersion} accessor is
     * {@code getName()} up to 1.21.5 but {@code name()} from 1.21.6.
     */
    private static String versionName() {
        try {
            return (String) SharedConstants.class.getField("VERSION_STRING").get(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            // 1.21.9+ removed the field; fall through to the WorldVersion accessor.
        }
        try {
            Object version = SharedConstants.getCurrentVersion();
            try {
                return (String) version.getClass().getMethod("name").invoke(version);
            } catch (NoSuchMethodException e) {
                return (String) version.getClass().getMethod("getName").invoke(version);
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /**
     * Patch number of a 1.21.x version name ("1.21" -> 0, "1.21.5" -> 5, "1.21.5-pre1" -> 5),
     * or -1 when {@code name} is not a 1.21.x version.
     */
    private static int patchOf(String name) {
        if (name == null || !name.startsWith(LINE_PREFIX)) {
            return -1;
        }
        String rest = name.substring(LINE_PREFIX.length());
        if (rest.isEmpty()) {
            return 0; // "1.21"
        }
        if (Character.isDigit(rest.charAt(0))) {
            return -1; // e.g. "1.210": not 1.21.x
        }
        if (rest.charAt(0) != '.') {
            return 0; // e.g. "1.21-pre1"
        }
        int end = 1;
        while (end < rest.length() && Character.isDigit(rest.charAt(end))) {
            end++;
        }
        if (end == 1) {
            return 0;
        }
        try {
            return Integer.parseInt(rest.substring(1, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
