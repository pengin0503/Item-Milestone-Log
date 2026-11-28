package com.pengin0503.itemmilestonelog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * F-150-75 (N118): prefix completion for the item picker's existing search field.
 *
 * <p>Pure Java and case-folded with {@link Locale#ROOT} (the same policy as the picker catalog).
 * The picker renders the suggestions through its existing {@code EditBox.setHint} and confirms the
 * first one on Tab; no dropdown widget is added (FL-5 / D-150-211). The catalog order is kept,
 * suggestions are capped at five, and {@code @mod} queries never complete so the namespace filter
 * keeps working.
 */
public final class ItemNameCompletion {
    /** Maximum number of names shown in the hint (spec F-150-75 item 1). */
    public static final int MAX_SUGGESTIONS = 5;
    /** Shortest query that produces suggestions. */
    public static final int MIN_QUERY_LENGTH = 2;

    private ItemNameCompletion() {
    }

    /** One catalog row; both comparison strings are lowercased with {@link Locale#ROOT}. */
    public record Candidate(String itemId, String displayName, String lowerName, String lowerId) {
        public Candidate {
            Objects.requireNonNull(itemId, "itemId");
            Objects.requireNonNull(displayName, "displayName");
            lowerName = lowerName == null ? "" : lowerName.toLowerCase(Locale.ROOT);
            lowerId = lowerId == null ? "" : lowerId.toLowerCase(Locale.ROOT);
        }

        public static Candidate of(String itemId, String displayName) {
            return new Candidate(itemId, displayName,
                    displayName.toLowerCase(Locale.ROOT), itemId.toLowerCase(Locale.ROOT));
        }
    }

    /** Returns at most {@code limit} (never more than {@value #MAX_SUGGESTIONS}) prefix matches. */
    public static List<Candidate> complete(List<Candidate> catalog, String rawQuery, int limit) {
        if (catalog == null || catalog.isEmpty() || rawQuery == null) {
            return List.of();
        }
        String query = rawQuery.trim().toLowerCase(Locale.ROOT);
        if (query.length() < MIN_QUERY_LENGTH || query.contains("@")) {
            return List.of();
        }
        int cap = Math.max(0, Math.min(limit, MAX_SUGGESTIONS));
        if (cap == 0) {
            return List.of();
        }
        List<Candidate> matches = new ArrayList<>();
        for (Candidate candidate : catalog) {
            if (candidate.lowerName().startsWith(query) || candidate.lowerId().startsWith(query)) {
                matches.add(candidate);
                if (matches.size() >= cap) {
                    break;
                }
            }
        }
        return List.copyOf(matches);
    }

    /** "a / b / c" text for the existing hint component. */
    public static String formatHint(List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Candidate candidate : candidates) {
            if (builder.length() > 0) {
                builder.append(" / ");
            }
            builder.append(candidate.displayName());
        }
        return builder.toString();
    }
}
