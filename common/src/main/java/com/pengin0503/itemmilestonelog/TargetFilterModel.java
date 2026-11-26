package com.pengin0503.itemmilestonelog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Pure-Java filtering foundation shared by every Minecraft version (F-150-1 / F-150-2).
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies. The version
 * trees convert their {@code TargetKey} into {@link TargetView} values before calling here.
 *
 * <p>Keyword rules (D-150-1 / D-150-2 / D-150-3): tokens are whitespace separated, case-insensitive
 * ({@link Locale#ROOT}), combined with AND, and matched only against the item/tag ID parts
 * ({@code full}, {@code path}, and {@code @namespace}). Display names are deliberately never
 * consulted. No regex or wildcards are used; a bare {@code @} token is a no-op.
 */
public final class TargetFilterModel {
    /** Maximum number of characters in a raw query (D-150-9). */
    public static final int MAX_QUERY_LENGTH = 128;
    /** Maximum number of whitespace-separated tokens in a query (D-150-9). */
    public static final int MAX_TOKENS = 8;

    private TargetFilterModel() {
    }

    /** Immutable value view of a target, built from a version tree's {@code TargetKey}. */
    public record TargetView(String itemId, int count) {
        public TargetView {
            Objects.requireNonNull(itemId, "itemId");
        }
    }

    /** Target kinds (N96). These overlap: a tagged count target is both TAGS and COUNTS. */
    public enum Kind {
        TAGS,
        ITEMS,
        COUNTS,
        DEFAULT
    }

    /** Why a raw query was rejected, so the command layer can choose the correct lang key. */
    public enum QueryIssue {
        EMPTY,
        TOO_LONG
    }

    /** Signals an invalid query; {@link #issue()} maps to {@code filter_empty_query} / {@code filter_query_too_long}. */
    public static final class QueryValidationException extends IllegalArgumentException {
        private final QueryIssue issue;

        public QueryValidationException(QueryIssue issue, String message) {
            super(message);
            this.issue = Objects.requireNonNull(issue, "issue");
        }

        public QueryIssue issue() {
            return issue;
        }
    }

    /**
     * Normalizes a raw query: trim, lowercase with {@link Locale#ROOT}, split on {@code \\s+},
     * and drop empty tokens. No limit checks here (see {@link #validateQuery(String)}).
     */
    public static List<String> tokens(String raw) {
        if (raw == null) {
            return List.of();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : normalized.split("\\s+")) {
            if (!part.isEmpty()) {
                result.add(part);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Validates a raw query. Empty/blank queries raise {@link QueryIssue#EMPTY}; a raw query longer
     * than {@value #MAX_QUERY_LENGTH} characters or with more than {@value #MAX_TOKENS} tokens
     * raises {@link QueryIssue#TOO_LONG} (both oversize cases share one lang key).
     */
    public static void validateQuery(String raw) {
        List<String> parsed = tokens(raw);
        if (parsed.isEmpty()) {
            throw new QueryValidationException(QueryIssue.EMPTY, "filter query is empty");
        }
        if ((raw != null && raw.length() > MAX_QUERY_LENGTH) || parsed.size() > MAX_TOKENS) {
            throw new QueryValidationException(QueryIssue.TOO_LONG,
                    "filter query exceeds " + MAX_QUERY_LENGTH + " chars or " + MAX_TOKENS + " tokens");
        }
    }

    /** Returns true when every token matches the view (AND); an empty token list matches all. */
    public static boolean matchesKeyword(TargetView view, List<String> tokens) {
        Objects.requireNonNull(view, "view");
        if (tokens == null || tokens.isEmpty()) {
            return true;
        }
        String full = view.itemId().toLowerCase(Locale.ROOT);
        String body = full.startsWith("#") ? full.substring(1) : full;
        int colon = body.indexOf(':');
        String namespace = colon >= 0 ? body.substring(0, colon) : body;
        String path = colon >= 0 ? body.substring(colon + 1) : "";
        for (String rawToken : tokens) {
            if (rawToken == null) {
                continue;
            }
            String token = rawToken.toLowerCase(Locale.ROOT);
            if (token.startsWith("@")) {
                String namespaceQuery = token.substring(1);
                // A bare '@' matches everything, matching the existing GUI behaviour.
                if (!namespaceQuery.isEmpty() && !namespace.contains(namespaceQuery)) {
                    return false;
                }
            } else if (!full.contains(token) && !path.contains(token)) {
                return false;
            }
        }
        return true;
    }

    /** Returns true when the view belongs to {@code kind}; {@code null} kind matches all. */
    public static boolean matchesKind(TargetView view, Kind kind, Set<TargetView> defaults) {
        Objects.requireNonNull(view, "view");
        if (kind == null) {
            return true;
        }
        return switch (kind) {
            case TAGS -> view.itemId().startsWith("#");
            case ITEMS -> !view.itemId().startsWith("#") && view.count() == 1;
            case COUNTS -> view.count() > 1;
            case DEFAULT -> defaults != null && defaults.contains(view);
        };
    }

    /**
     * Filters {@code in} by keyword and kind, preserving order and always returning a new mutable
     * list. With empty tokens and a null kind every element passes, so the result is equal to the
     * input (the byte-identical unfiltered path).
     */
    public static <T> List<T> apply(List<T> in,
                                    Function<T, TargetView> view,
                                    List<String> tokens,
                                    Kind kind,
                                    Set<TargetView> defaults) {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(view, "view");
        List<T> result = new ArrayList<>();
        for (T item : in) {
            TargetView targetView = view.apply(item);
            if (matchesKeyword(targetView, tokens) && matchesKind(targetView, kind, defaults)) {
                result.add(item);
            }
        }
        return result;
    }
}
