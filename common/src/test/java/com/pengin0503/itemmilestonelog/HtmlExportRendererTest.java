package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlExportRendererTest {
    @Test
    void computesCompletionAcrossPersonalAndDefaultTargets() {
        HtmlExportRenderer.CompletionSummary empty = HtmlExportRenderer.computeCompletion(
                List.of(), List.of(), Set.of());
        assertEquals(0, empty.completedTargets());
        assertEquals(0, empty.totalTargets());
        assertEquals(0, empty.totalRecords());
        assertEquals(0.0, empty.percentage(), 1e-9);
        assertEquals(0, empty.floorPercent());
        assertEquals("0 / 0 (\u2014)", empty.formatSummary());

        // Overlapping personal and default targets are deduplicated; records outside effective targets
        // count toward totalRecords without inflating completedTargets above totalTargets.
        HtmlExportRenderer.CompletionSummary partial = HtmlExportRenderer.computeCompletion(
                List.of("minecraft:diamond", "minecraft:emerald", "minecraft:iron_ingot"),
                List.of("minecraft:diamond", "minecraft:netherite_ingot"),
                Set.of("minecraft:diamond", "minecraft:netherite_ingot", "minecraft:gold_ingot"));
        assertEquals(2, partial.completedTargets());
        assertEquals(4, partial.totalTargets());
        assertEquals(3, partial.totalRecords());
        assertEquals(50.0, partial.percentage(), 1e-9);
        assertEquals(50, partial.floorPercent());
        assertEquals("2 / 4 (50.0%)", partial.formatSummary());

        HtmlExportRenderer.CompletionSummary full = HtmlExportRenderer.computeCompletion(
                List.of("minecraft:diamond"),
                List.of("minecraft:emerald"),
                Set.of("minecraft:diamond", "minecraft:emerald"));
        assertEquals(2, full.completedTargets());
        assertEquals(2, full.totalTargets());
        assertEquals(100.0, full.percentage(), 1e-9);
        assertEquals(100, full.floorPercent());
        assertEquals("2 / 2 (100.0%)", full.formatSummary());

        // Null collections are handled safely.
        HtmlExportRenderer.CompletionSummary nulls = HtmlExportRenderer.computeCompletion(null, null, null);
        assertEquals(0, nulls.completedTargets());
        assertEquals(0, nulls.totalTargets());
        assertEquals(0, nulls.totalRecords());
    }

    @Test
    void formatsGenerationTimestampUsingDisplayTimeZone() {
        Instant instant = Instant.parse("2026-11-24T00:15:30Z");
        assertEquals("2026-11-24 00:15:30 UTC",
                HtmlExportRenderer.formatGeneratedAt(instant, ZoneId.of("UTC")));
        assertEquals("2026-11-24 09:15:30 Asia/Tokyo",
                HtmlExportRenderer.formatGeneratedAt(instant, ZoneId.of("Asia/Tokyo")));
        assertEquals("2026-11-23 19:15:30 -05:00",
                HtmlExportRenderer.formatGeneratedAt(instant, ZoneId.of("-05:00")));
    }

    @Test
    void rendersStandaloneSinglePlayerHtmlWithEscapingAndUnresolvableItems() {
        UUID uuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
        HtmlExportRenderer.PlayerReport report = new HtmlExportRenderer.PlayerReport(
                uuid,
                "<Alice & \"Bob\" 'Evil'>",
                new HtmlExportRenderer.CompletionSummary(2, 3, 2),
                List.of(
                        new HtmlExportRenderer.RecordRow(
                                "Diamond <Sparkle> & 'Gem'",
                                "minecraft:diamond",
                                64,
                                "1h 02m 03s",
                                "Day 5",
                                "2026-11-24",
                                true),
                        new HtmlExportRenderer.RecordRow(
                                "",
                                "removedmod:ancient_relic",
                                1,
                                "2h 00m 00s",
                                "",
                                "",
                                false)
                )
        );

        String html = HtmlExportRenderer.renderSinglePlayer(
                report, Instant.parse("2026-11-24T12:34:56Z"), ZoneId.of("UTC"));

        // Standalone constraints: UTF-8, inline CSS, no JS, no external resources.
        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains("<meta charset=\"utf-8\">"));
        assertTrue(html.contains("<style>"));
        assertFalse(html.toLowerCase().contains("<script"));
        assertFalse(html.contains("http://"));
        assertFalse(html.contains("https://"));

        // Escaped player name and item name.
        assertTrue(html.contains("&lt;Alice &amp; &quot;Bob&quot; &#39;Evil&#39;&gt;"));
        assertFalse(html.contains("<Alice"));
        assertTrue(html.contains("Diamond &lt;Sparkle&gt; &amp; &#39;Gem&#39;"));
        assertFalse(html.contains("<Sparkle>"));

        // Timestamp, completion summary, count, playtime, Day, date, world-first mark.
        assertTrue(html.contains("2026-11-24 12:34:56 UTC"));
        assertTrue(html.contains("2 / 3 (66.7%)"));
        assertTrue(html.contains("<code>minecraft:diamond</code>"));
        assertTrue(html.contains(">64<"));
        assertTrue(html.contains("1h 02m 03s"));
        assertTrue(html.contains("Day 5"));
        assertTrue(html.contains("2026-11-24"));
        assertTrue(html.contains("&#9733; World First"));

        // Unresolvable item shows ID only (not duplicated as the item name).
        assertTrue(html.contains("<code>removedmod:ancient_relic</code>"));
        assertEquals(1, countOccurrences(html, "removedmod:ancient_relic"));
    }

    @Test
    void rendersAllPlayersHtmlWithSafeAnchorsAndEmptyState() {
        String emptyHtml = HtmlExportRenderer.renderAllPlayers(
                List.of(), Instant.parse("2026-11-24T00:00:00Z"), ZoneId.of("UTC"));
        assertTrue(emptyHtml.contains("<meta charset=\"utf-8\">"));
        assertTrue(emptyHtml.contains("No player milestone data recorded yet."));
        assertFalse(emptyHtml.toLowerCase().contains("<script"));

        UUID aliceId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID bobId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        List<HtmlExportRenderer.PlayerReport> reports = List.of(
                new HtmlExportRenderer.PlayerReport(
                        aliceId,
                        "Alice<1>",
                        new HtmlExportRenderer.CompletionSummary(1, 2, 1),
                        List.of(new HtmlExportRenderer.RecordRow(
                                "Diamond", "minecraft:diamond", 1, "0h 10m 00s", "Day 1", "2026-11-24", true))),
                new HtmlExportRenderer.PlayerReport(
                        bobId,
                        "Bob&Co",
                        new HtmlExportRenderer.CompletionSummary(0, 2, 0),
                        List.of())
        );

        String html = HtmlExportRenderer.renderAllPlayers(
                reports, Instant.parse("2026-11-24T00:00:00Z"), ZoneId.of("Asia/Tokyo"));

        assertTrue(html.contains("2026-11-24 09:00:00 Asia/Tokyo"));
        assertTrue(html.contains("Players: 2"));
        assertTrue(html.contains("Total records: 1"));
        assertTrue(html.contains("href=\"#player-0\""));
        assertTrue(html.contains("href=\"#player-1\""));
        assertTrue(html.contains("id=\"player-0\""));
        assertTrue(html.contains("id=\"player-1\""));
        assertTrue(html.contains("Alice&lt;1&gt;"));
        assertTrue(html.contains("Bob&amp;Co"));
        assertTrue(html.contains("No milestones recorded yet."));
        assertFalse(html.toLowerCase().contains("<script"));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
