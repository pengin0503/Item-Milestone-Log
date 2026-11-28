package com.pengin0503.itemmilestonelog;

/**
 * F-150-86 (N199): pure policy for the optional low-power mode.
 *
 * <p>The mode is a client display preference only. Every decision here is purely subtractive:
 * it can skip a cosmetic rebuild, lengthen a local-file debounce or suppress a particle, but it
 * never edits recorded data, changes which data is displayed, or touches server communication.
 * The default is OFF ({@code false}): calling every method with {@code lowPower == false} returns
 * exactly the shipped behaviour.
 */
public final class LowPowerModePolicy {
    /** Four client ticks (200 ms at 20 TPS) between coalesced HUD row-cache rebuilds. */
    public static final int HUD_REBUILD_EVERY_TICKS = 4;
    /** Five seconds between OBS status file updates while low power is enabled. */
    public static final long STATUS_DEBOUNCE_MILLIS = 5_000L;

    private LowPowerModePolicy() {
    }

    /**
     * Whether a pending HUD row-cache rebuild may run on this tick. With the mode off the caller
     * rebuilds immediately on every sync/pin event (unchanged); with it on at most one rebuild
     * happens per four-tick window. A backwards tick counter is treated as due.
     */
    public static boolean hudRebuildDue(boolean lowPower, long ticksSinceLastRebuild,
                                        boolean rebuildPending) {
        return lowPower && rebuildPending
                && (ticksSinceLastRebuild < 0 || ticksSinceLastRebuild >= HUD_REBUILD_EVERY_TICKS);
    }

    /** Debounce for client-local status files: 1 s normally, 5 s in low-power mode. */
    public static long statusDebounceMillis(boolean lowPower) {
        return lowPower ? STATUS_DEBOUNCE_MILLIS : StreamStatusWriter.DEBOUNCE_MILLIS;
    }

    /** Particles are cosmetic: low-power mode and reduced motion both suppress them. */
    public static boolean particlesAllowed(boolean lowPower, boolean effectsEnabled,
                                           boolean reducedMotion) {
        return !lowPower && effectsEnabled && !reducedMotion;
    }
}
