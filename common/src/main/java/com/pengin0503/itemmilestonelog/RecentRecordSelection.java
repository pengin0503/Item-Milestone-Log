package com.pengin0503.itemmilestonelog;

import java.util.List;
import java.util.Objects;

/**
 * F-150-71 (N68): pure selection of the newest locally known milestone record for the
 * copy-recent key.
 *
 * <p>An {@code epochMillis} value always beats a day-only value; when both candidates carry an
 * epoch the larger one wins, and two day-only candidates are compared by day. Ties keep the first
 * occurrence so the selection is stable. No Minecraft type, clipboard call or network access is
 * involved: the per-tree key handler only maps its records to {@link Candidate}s and formats the
 * selected one with the existing {@code Playtime.formatRecordComponent} path.
 */
public final class RecentRecordSelection {
    private RecentRecordSelection() {
    }

    /** The two timestamp forms a record may carry, mirror-only (no item id or player data). */
    public record Candidate(long epochMillis, boolean hasEpoch, long day, boolean hasDay) {
        public Candidate {
            if (hasEpoch && epochMillis <= 0L) {
                hasEpoch = false;
            }
        }
    }

    /**
     * Index of the newest usable candidate, or {@code -1} when every entry lacks both timestamps.
     */
    public static int selectIndex(List<Candidate> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        int best = -1;
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            if (!candidate.hasEpoch() && !candidate.hasDay()) {
                continue;
            }
            if (best < 0 || compare(candidate, candidates.get(best)) > 0) {
                best = i;
            }
        }
        return best;
    }

    /** Positive when {@code a} is newer than {@code b}; zero keeps the earlier entry. */
    public static int compare(Candidate a, Candidate b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        if (a.hasEpoch() != b.hasEpoch()) {
            return a.hasEpoch() ? 1 : -1;
        }
        if (a.hasEpoch()) {
            return Long.compare(a.epochMillis(), b.epochMillis());
        }
        return Long.compare(a.day(), b.day());
    }
}
