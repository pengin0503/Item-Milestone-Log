package com.pengin0503.itemmilestonelog;

/**
 * Pure-Java scheduling rules for the periodic HTML/JSON export (step 7 PR-1, F-150-90,
 * D-150-254/255/293). No threads are created: the tick hook lives on the existing
 * {@code ServerTickEvent.Post} path and uses the same one-hour tick throttle as the digest path.
 * Kept Minecraft-free and Java 17 compatible.
 */
public final class AutoExportSchedule {
    /** One hour at 20 TPS, matching {@code DiscordDigestLogic.CHECK_INTERVAL_TICKS}. */
    public static final int CHECK_INTERVAL_TICKS = 72_000;
    public static final int DEFAULT_INTERVAL_HOURS = 24;
    public static final int MIN_INTERVAL_HOURS = 1;
    public static final int MAX_INTERVAL_HOURS = 168;
    private static final long MILLIS_PER_HOUR = 3_600_000L;

    /** Outcome of a tick decision. */
    public enum Decision {
        SKIP,
        COLLECT
    }

    private AutoExportSchedule() {
    }

    public static int clampIntervalHours(int hours) {
        return Math.max(MIN_INTERVAL_HOURS, Math.min(MAX_INTERVAL_HOURS, hours));
    }

    /**
     * A zero-based last-run time means "never run in this session", so the first throttled tick
     * collects. Exactly {@code intervalHours} hours must have elapsed before another collection.
     */
    public static Decision decide(long nowMillis, long lastRunMillis, int intervalHours) {
        if (lastRunMillis <= 0L) {
            return Decision.COLLECT;
        }
        long elapsed = nowMillis - lastRunMillis;
        if (elapsed < 0L) {
            return Decision.SKIP;
        }
        return elapsed >= clampIntervalHours(intervalHours) * MILLIS_PER_HOUR
                ? Decision.COLLECT : Decision.SKIP;
    }

    /** Server-thread tick counter; fires once per {@code intervalTicks} ticks (tick-only thinning). */
    public static final class Throttle {
        private final int intervalTicks;
        private int ticks;

        public Throttle() {
            this(CHECK_INTERVAL_TICKS);
        }

        public Throttle(int intervalTicks) {
            this.intervalTicks = Math.max(1, intervalTicks);
        }

        public boolean tick() {
            ticks++;
            if (ticks >= intervalTicks) {
                ticks = 0;
                return true;
            }
            return false;
        }

        public void reset() {
            ticks = 0;
        }

        int currentTicks() {
            return ticks;
        }
    }
}
