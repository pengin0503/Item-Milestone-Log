package com.pengin0503.itemmilestonelog.compat;

/**
 * 1.20.1-forge: this tree is a single Minecraft version, so there is only one compat
 * implementation profile ({@code compat.v120_1}). Kept as a tiny accessor so the reflective
 * loader in {@link McCompat} stays identical in shape to the 1.21.x tree.
 */
public enum McVersion {
    /** Minecraft 1.20.1 / Forge 47.x. */
    V120_1("v120_1");

    private final String packageSuffix;

    McVersion(String packageSuffix) {
        this.packageSuffix = packageSuffix;
    }

    public static McVersion current() {
        return V120_1;
    }

    /** Package fragment of this version's compat implementations, i.e. {@code "v120_1"}. */
    public String packageSuffix() {
        return packageSuffix;
    }
}
