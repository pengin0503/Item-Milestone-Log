package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextEscapingTest {
    @Test
    void escapeHtmlHandlesNullEmptyAndSpecialCharacters() {
        assertEquals("", TextEscaping.escapeHtml(null));
        assertEquals("", TextEscaping.escapeHtml(""));
        assertEquals("Diamond", TextEscaping.escapeHtml("Diamond"));
        assertEquals(
                "&lt;script&gt;alert(&quot;x&quot; &amp; &#39;y&#39;)&lt;/script&gt;",
                TextEscaping.escapeHtml("<script>alert(\"x\" & 'y')</script>")
        );
        assertEquals(
                "&amp;&lt;&gt;&quot;&#39; ★ ダイヤモンド \uD83D\uDC8E",
                TextEscaping.escapeHtml("&<>\"' ★ ダイヤモンド \uD83D\uDC8E")
        );
    }

    @Test
    void escapeDiscordMarkdownEscapesAllSpecialCharacters() {
        assertEquals("", TextEscaping.escapeDiscordMarkdown(null));
        assertEquals("", TextEscaping.escapeDiscordMarkdown(""));
        assertEquals("Steve123", TextEscaping.escapeDiscordMarkdown("Steve123"));
        assertEquals("\\_Steve\\_", TextEscaping.escapeDiscordMarkdown("_Steve_"));
        assertEquals(
                "\\\\\\*\\_\\~\\`\\|\\>\\#\\-\\[\\]\\(\\)",
                TextEscaping.escapeDiscordMarkdown("\\*_~`|> #-[]()".replace(" ", ""))
        );
        assertEquals("\\[Link\\]\\(https://example.com\\)",
                TextEscaping.escapeDiscordMarkdown("[Link](https://example.com)"));
    }

    @Test
    void truncateUtf16RespectsLimitAndNeverSplitsSurrogatePairs() {
        assertEquals("", TextEscaping.truncateUtf16(null, 10));
        assertEquals("", TextEscaping.truncateUtf16("abc", 0));
        assertEquals("", TextEscaping.truncateUtf16("abc", -5));
        assertEquals("abc", TextEscaping.truncateUtf16("abc", 3));
        assertEquals("abc", TextEscaping.truncateUtf16("abc", 10));
        assertEquals("ab", TextEscaping.truncateUtf16("abcd", 2));

        // U+1F48E (GEM STONE) occupies 2 UTF-16 code units.
        String withEmoji = "A\uD83D\uDC8EB";
        assertEquals("A", TextEscaping.truncateUtf16(withEmoji, 2));
        assertEquals("A\uD83D\uDC8E", TextEscaping.truncateUtf16(withEmoji, 3));
        assertEquals(withEmoji, TextEscaping.truncateUtf16(withEmoji, 4));
        assertEquals("", TextEscaping.truncateUtf16("\uD83D\uDC8E", 1));

        // Discord cap boundaries (256, 1024, 2000, 4096) with a surrogate pair straddling the cut.
        for (int cap : new int[]{256, 1024, 2000, 4096}) {
            String straddling = "a".repeat(cap - 1) + "\uD83D\uDC8E" + "z";
            String truncated = TextEscaping.truncateUtf16(straddling, cap);
            assertEquals(cap - 1, truncated.length());
            assertEquals("a".repeat(cap - 1), truncated);
        }
    }

    @Test
    void escapeCsvCellQuotesPerRfc4180AndNeutralizesFormulas() {
        assertEquals("", TextEscaping.escapeCsvTextCell(null));
        assertEquals("", TextEscaping.escapeCsvTextCell(""));
        assertEquals("Diamond", TextEscaping.escapeCsvTextCell("Diamond"));
        assertEquals("\"Diamond, Shiny\"", TextEscaping.escapeCsvTextCell("Diamond, Shiny"));
        assertEquals("\"Say \"\"hi\"\"\"", TextEscaping.escapeCsvTextCell("Say \"hi\""));
        assertEquals("\"line1\nline2\"", TextEscaping.escapeCsvTextCell("line1\nline2"));

        assertEquals("'=SUM(A1)", TextEscaping.escapeCsvTextCell("=SUM(A1)"));
        assertEquals("'+1", TextEscaping.escapeCsvTextCell("+1"));
        assertEquals("'-cmd", TextEscaping.escapeCsvTextCell("-cmd"));
        assertEquals("'@evil", TextEscaping.escapeCsvTextCell("@evil"));
        assertEquals("'\tindented", TextEscaping.escapeCsvTextCell("\tindented"));
        assertEquals("\"'\rcr\"", TextEscaping.escapeCsvTextCell("\rcr"));
        assertEquals("\"'=A1,B1\"", TextEscaping.escapeCsvTextCell("=A1,B1"));

        // Raw cells skip formula neutralization while keeping RFC 4180 quoting.
        assertEquals("-1", TextEscaping.escapeCsvRawCell("-1"));
        assertEquals("\"a,b\"", TextEscaping.escapeCsvRawCell("a,b"));
    }
}
