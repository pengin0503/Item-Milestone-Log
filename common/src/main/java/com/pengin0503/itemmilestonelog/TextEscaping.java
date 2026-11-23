package com.pengin0503.itemmilestonelog;

/**
 * Pure-Java text escaping and truncation helpers shared across all Minecraft versions
 * via {@code common/src/main/java}.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 * Used for HTML export (F3), Discord Markdown formatting and length caps (F4),
 * and RFC 4180 CSV export with formula-injection mitigation (F26).
 */
public final class TextEscaping {
    private TextEscaping() {
    }

    /**
     * Escapes HTML special characters ({@code &}, {@code <}, {@code >}, {@code "}, {@code '})
     * so external strings can be safely embedded in HTML text or attributes.
     */
    public static String escapeHtml(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Escapes Discord Markdown meta-characters ({@code \}, {@code *}, {@code _}, {@code ~},
     * {@code `}, {@code |}, {@code >}, {@code #}, {@code -}, {@code [}, {@code ]}, {@code (}, {@code )})
     * by prefixing each with a backslash.
     */
    public static String escapeDiscordMarkdown(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (isDiscordMarkdownChar(c)) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isDiscordMarkdownChar(char c) {
        return switch (c) {
            case '\\', '*', '_', '~', '`', '|', '>', '#', '-', '[', ']', '(', ')' -> true;
            default -> false;
        };
    }

    /**
     * Truncates {@code value} to at most {@code maxLength} UTF-16 code units without splitting
     * a UTF-16 surrogate pair.
     */
    public static String truncateUtf16(String value, int maxLength) {
        if (value == null || maxLength <= 0) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        int end = maxLength;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * Formats a single CSV text cell according to RFC 4180, neutralizing leading spreadsheet
     * formula characters ({@code =}, {@code +}, {@code -}, {@code @}, tab, CR) with a leading
     * single quote.
     */
    public static String escapeCsvTextCell(String value) {
        return escapeCsvCell(value, true);
    }

    /**
     * Formats a single CSV cell according to RFC 4180 without formula-injection prefixing
     * (intended for trusted numeric/boolean columns).
     */
    public static String escapeCsvRawCell(String value) {
        return escapeCsvCell(value, false);
    }

    /**
     * Formats a single CSV cell according to RFC 4180.
     *
     * @param value             cell text ({@code null} is treated as empty string)
     * @param neutralizeFormula when {@code true}, values starting with {@code =}, {@code +},
     *                          {@code -}, {@code @}, {@code \t}, or {@code \r} are prefixed
     *                          with {@code '}
     */
    public static String escapeCsvCell(String value, boolean neutralizeFormula) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String text = value;
        if (neutralizeFormula && startsWithFormulaTrigger(text)) {
            text = "'" + text;
        }
        boolean needsQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ',' || c == '"' || c == '\n' || c == '\r') {
                needsQuotes = true;
                break;
            }
        }
        if (!needsQuotes) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length() + 4);
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                sb.append("\"\"");
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static boolean startsWithFormulaTrigger(String text) {
        char first = text.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@'
                || first == '\t' || first == '\r';
    }
}
