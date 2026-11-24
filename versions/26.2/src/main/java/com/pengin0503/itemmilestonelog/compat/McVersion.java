package com.pengin0503.itemmilestonelog.compat;

import net.minecraft.SharedConstants;

/**
 * N251: detects the running Minecraft version once, for compat implementation
 * selection (docs/DESIGN_1.3.0.md). Unknown future versions resolve to the closest
 * known variant; support for a new version is only declared after verification.
 */
public enum McVersion {
    V26_1("26.1"),
    V26_2("26.2"),
    V26_3("26.3"),
    UNKNOWN("");

    private static final McVersion CURRENT = detect();

    private final String prefix;

    McVersion(String prefix) {
        this.prefix = prefix;
    }

    public static McVersion current() {
        return CURRENT;
    }

    /** Package fragment of this version's compat implementations, e.g. "v26_1". */
    public String packageSuffix() {
        return switch (this) {
            case V26_1 -> "v26_1";
            case V26_2 -> "v26_2";
            case V26_3, UNKNOWN -> "v26_3";
        };
    }

    private static McVersion detect() {
        String name;
        try {
            name = SharedConstants.getCurrentVersion().name();
        } catch (RuntimeException e) {
            return UNKNOWN;
        }
        for (McVersion v : values()) {
            if (v != UNKNOWN && (name.equals(v.prefix) || name.startsWith(v.prefix + "."))) {
                return v;
            }
        }
        return UNKNOWN;
    }
}
