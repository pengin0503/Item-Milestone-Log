package com.pengin0503.itemmilestonelog;

/**
 * F-150-73 (N71): pure gate for the optional Jade / WTHIT / TOP tooltip line.
 *
 * <p>No external mod class or API is referenced here (D-150-207). Mod presence is detected once
 * by the client entry point with {@code ModList.isLoaded} and passed in as three booleans. The
 * gate returns at most one extra line even when all three mods are installed (D-150-208), and
 * never when the ordinary F42 line for the same item is already present.
 */
public final class ExternalIntegrationGate {
    private ExternalIntegrationGate() {
    }

    /** OR condition: any one of the three supported mods is enough. */
    public static boolean anyLoaded(boolean jade, boolean wthit, boolean top) {
        return jade || wthit || top;
    }

    /** Whether exactly one extra line should be appended to an external info panel. */
    public static boolean addLine(boolean enabled, boolean jade, boolean wthit, boolean top,
                                  boolean f42LineAlreadyPresent) {
        return enabled && !f42LineAlreadyPresent && anyLoaded(jade, wthit, top);
    }

    /** Explicit count form used by the pure test: always 0 or 1, never one per mod. */
    public static int addedLineCount(boolean enabled, boolean jade, boolean wthit, boolean top,
                                     boolean f42LineAlreadyPresent) {
        return addLine(enabled, jade, wthit, top, f42LineAlreadyPresent) ? 1 : 0;
    }
}
