package com.pengin0503.itemmilestonelog;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java argument handling for {@code /milestonelog renametag <oldTag> <newTag> [confirm]}
 * (G10a / issue #146).
 *
 * <p>The command uses one vanilla {@code StringArgumentType.greedyString()} node instead of a
 * custom {@code ArgumentType}: Minecraft only serializes argument classes known to its vanilla
 * argument-type registry, so an unregistered class breaks command-tree sync for OP players.
 * Tag IDs never contain whitespace, so the greedy remainder can be split into two tokens and
 * an optional trailing {@code confirm}; one pair of surrounding quotes is removed per token.
 */
public final class RenameTagArguments {
    /** Literal that turns the preview into an apply, as in the pre-G10a command tree. */
    public static final String CONFIRM = "confirm";

    /** A whitespace-delimited slice of the raw input. {@code text} keeps the original quoting. */
    public record Token(String value, String text, int start, int end) {
    }

    /** A valid renametag argument line: two tag IDs and the resolved preview/apply flag. */
    public record Parsed(String oldTag, String newTag, boolean confirm) {
    }

    /** Where the caret is while completing the tag IDs of a renametag command. */
    public record Cursor(int tokenIndex, int tokenStart, String prefix, char quote) {
    }

    private RenameTagArguments() {
    }

    /** Splits on whitespace and strips one surrounding {@code "} or {@code '} pair per token. */
    public static List<Token> split(String raw) {
        List<Token> tokens = new ArrayList<>();
        if (raw == null) {
            return List.copyOf(tokens);
        }
        int i = 0;
        while (i < raw.length()) {
            while (i < raw.length() && Character.isWhitespace(raw.charAt(i))) {
                i++;
            }
            if (i >= raw.length()) {
                break;
            }
            int start = i;
            while (i < raw.length() && !Character.isWhitespace(raw.charAt(i))) {
                i++;
            }
            String text = raw.substring(start, i);
            tokens.add(new Token(unquote(text), text, start, i));
        }
        return List.copyOf(tokens);
    }

    /**
     * Parses exactly two tokens (preview) or two tokens followed by the literal {@code confirm}
     * (apply). Returns {@code null} for 0, 1, 3+ tokens or a third token that is not
     * {@code confirm}; callers report that with the localized renametag error message.
     */
    public static Parsed parse(String raw) {
        List<Token> tokens = split(raw);
        if (tokens.size() == 2) {
            return new Parsed(tokens.get(0).value(), tokens.get(1).value(), false);
        }
        if (tokens.size() == 3 && CONFIRM.equals(tokens.get(2).text())) {
            return new Parsed(tokens.get(0).value(), tokens.get(1).value(), true);
        }
        return null;
    }

    /**
     * Suggestion caret for the raw greedy string: trailing whitespace starts the next token,
     * otherwise the last token is being edited. {@code quote} is the opening quote of that
     * token (or 0), and {@code prefix} excludes it so tag-ID candidates can be filtered.
     */
    public static Cursor cursorForSuggestions(String raw) {
        List<Token> tokens = split(raw);
        boolean trailingWhitespace = raw == null || raw.isEmpty()
                || Character.isWhitespace(raw.charAt(raw.length() - 1));
        int index = trailingWhitespace ? tokens.size() : Math.max(0, tokens.size() - 1);
        if (index >= tokens.size()) {
            return new Cursor(index, raw == null ? 0 : raw.length(), "", (char) 0);
        }
        Token token = tokens.get(index);
        String text = token.text();
        char quote = text.isEmpty() ? 0 : text.charAt(0);
        if (quote != '"' && quote != '\'') {
            quote = 0;
        }
        String prefix = token.value();
        if (quote != 0 && prefix.startsWith(String.valueOf(quote))) {
            prefix = prefix.substring(1);
            if (prefix.endsWith(String.valueOf(quote))) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
        }
        return new Cursor(index, token.start(), prefix, quote);
    }

    private static String unquote(String text) {
        if (text.length() >= 2) {
            char quote = text.charAt(0);
            if ((quote == '"' || quote == '\'') && text.charAt(text.length() - 1) == quote) {
                return text.substring(1, text.length() - 1);
            }
        }
        return text;
    }
}
