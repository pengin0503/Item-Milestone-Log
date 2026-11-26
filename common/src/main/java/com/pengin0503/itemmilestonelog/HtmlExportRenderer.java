package com.pengin0503.itemmilestonelog;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Pure-Java HTML summary renderer and completion-rate calculator for F3
 * ({@code /milestonelog exporthtml} and {@code /milestonelog exporthtmlall}).
 *
 * <p>Produces standalone UTF-8 HTML documents with inline CSS, no JavaScript, and no external
 * resources. Every externally-derived string (player names, item names, item IDs, timestamps)
 * is escaped through {@link TextEscaping#escapeHtml(String)}.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class HtmlExportRenderer {
    public static final String ALL_PLAYERS_BASE_NAME = "all-players";
    public static final String ALL_PLAYERS_HTML_FILE = ALL_PLAYERS_BASE_NAME + ".html";

    private static final DateTimeFormatter GENERATED_AT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss VV", Locale.ROOT);

    private static final String INLINE_CSS = """
            :root {
              color-scheme: light dark;
              --bg: #f6f8fa;
              --surface: #ffffff;
              --border: #d0d7de;
              --text: #1f2328;
              --muted: #656d76;
              --accent: #0969da;
              --bar-bg: #eaeef2;
              --wf-bg: #fff8c5;
              --wf-text: #9a6700;
              --wf-border: #d4a72c;
              --code-bg: #eff1f3;
            }
            @media (prefers-color-scheme: dark) {
              :root {
                --bg: #0d1117;
                --surface: #161b22;
                --border: #30363d;
                --text: #e6edf3;
                --muted: #8b949e;
                --accent: #58a6ff;
                --bar-bg: #21262d;
                --wf-bg: #3b2e00;
                --wf-text: #f2cc60;
                --wf-border: #9e6a03;
                --code-bg: #21262d;
              }
            }
            * { box-sizing: border-box; }
            body {
              margin: 0;
              padding: 24px 16px 48px;
              background: var(--bg);
              color: var(--text);
              font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
              line-height: 1.5;
            }
            .container {
              max-width: 1120px;
              margin: 0 auto;
            }
            header.page-header {
              margin-bottom: 24px;
              padding-bottom: 16px;
              border-bottom: 1px solid var(--border);
            }
            header.page-header h1 {
              margin: 0 0 6px;
              font-size: 1.75rem;
            }
            .meta {
              margin: 0;
              color: var(--muted);
              font-size: 0.9rem;
            }
            .card {
              background: var(--surface);
              border: 1px solid var(--border);
              border-radius: 8px;
              padding: 20px;
              margin-bottom: 24px;
            }
            .player-header {
              display: flex;
              flex-wrap: wrap;
              justify-content: space-between;
              align-items: baseline;
              gap: 8px 16px;
              margin-bottom: 12px;
            }
            .player-header h2 {
              margin: 0;
              font-size: 1.35rem;
            }
            .uuid {
              font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
              font-size: 0.8rem;
              color: var(--muted);
            }
            .summary-grid {
              display: flex;
              flex-wrap: wrap;
              gap: 12px;
              margin-bottom: 14px;
            }
            .stat {
              background: var(--bg);
              border: 1px solid var(--border);
              border-radius: 6px;
              padding: 8px 12px;
              min-width: 160px;
            }
            .stat-label {
              font-size: 0.78rem;
              color: var(--muted);
              text-transform: uppercase;
              letter-spacing: 0.03em;
            }
            .stat-value {
              font-size: 1.05rem;
              font-weight: 600;
            }
            .progress-track {
              height: 8px;
              background: var(--bar-bg);
              border-radius: 999px;
              overflow: hidden;
              margin-bottom: 16px;
            }
            .progress-fill {
              height: 100%;
              background: var(--accent);
            }
            .toc {
              list-style: none;
              padding: 0;
              margin: 12px 0 0;
              display: flex;
              flex-wrap: wrap;
              gap: 8px;
            }
            .toc a {
              display: inline-block;
              padding: 4px 10px;
              border: 1px solid var(--border);
              border-radius: 999px;
              color: var(--accent);
              text-decoration: none;
              font-size: 0.9rem;
              background: var(--bg);
            }
            .toc a:hover {
              text-decoration: underline;
            }
            .table-wrap {
              overflow-x: auto;
            }
            table {
              width: 100%;
              border-collapse: collapse;
              font-size: 0.92rem;
            }
            th, td {
              padding: 8px 10px;
              border-bottom: 1px solid var(--border);
              text-align: left;
              vertical-align: middle;
            }
            th {
              font-weight: 600;
              color: var(--muted);
              font-size: 0.82rem;
              text-transform: uppercase;
              letter-spacing: 0.03em;
              white-space: nowrap;
            }
            tbody tr:last-child td {
              border-bottom: none;
            }
            td.num, th.num {
              text-align: right;
              font-variant-numeric: tabular-nums;
              white-space: nowrap;
            }
            td.nowrap {
              white-space: nowrap;
              font-variant-numeric: tabular-nums;
            }
            code {
              font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
              font-size: 0.85em;
              background: var(--code-bg);
              padding: 2px 5px;
              border-radius: 4px;
            }
            .muted {
              color: var(--muted);
            }
            .wf {
              display: inline-block;
              padding: 2px 8px;
              border-radius: 999px;
              background: var(--wf-bg);
              color: var(--wf-text);
              border: 1px solid var(--wf-border);
              font-size: 0.8rem;
              font-weight: 600;
              white-space: nowrap;
            }
            .empty {
              margin: 8px 0 0;
              color: var(--muted);
              font-style: italic;
            }
            """;

    private HtmlExportRenderer() {
    }

    /**
     * Completion-rate summary for a single player: recorded targets out of effective targets
     * ({@code personalTargets ∪ defaultTargets}), plus total recorded milestones.
     * Possession progress is intentionally omitted (offline players cannot be counted).
     */
    public record CompletionSummary(int completedTargets, int totalTargets, int totalRecords) {
        /** Completion percentage in {@code [0.0, 100.0]}, or {@code 0.0} when {@code totalTargets == 0}. */
        public double percentage() {
            if (totalTargets <= 0) {
                return 0.0;
            }
            return (completedTargets * 100.0) / totalTargets;
        }

        /** Integer floor percentage in {@code [0, 100]}, or {@code 0} when {@code totalTargets == 0}. */
        public int floorPercent() {
            if (totalTargets <= 0) {
                return 0;
            }
            return (int) (((long) completedTargets * 100L) / totalTargets);
        }

        /** Human-readable completion summary such as {@code "3 / 4 (75.0%)"} or {@code "0 / 0 (—)"}. */
        public String formatSummary() {
            if (totalTargets <= 0) {
                return "0 / 0 (\u2014)";
            }
            return String.format(Locale.ROOT, "%d / %d (%.1f%%)", completedTargets, totalTargets, percentage());
        }
    }

    /**
     * One row in a player's HTML records table.
     *
     * @param itemName   resolved display name from server-side {@code Component.getString()}, or
     *                   empty/{@code itemId} when the item cannot be resolved (mod removed)
     * @param itemId     canonical item ID (e.g. {@code minecraft:diamond})
     * @param count      required quantity (>= 1)
     * @param playtime   formatted cumulative playtime (e.g. {@code 1h 23m 45s})
     * @param dayText    formatted in-game day (e.g. {@code Day 12}), or empty if unknown
     * @param dateText   formatted real-world date in {@code displayTimeZone} (e.g. {@code 2026-11-24}), or empty if unknown
     * @param worldFirst {@code true} when this player holds the world-first record for this target
     */
    public record RecordRow(
            String itemName,
            String itemId,
            int count,
            String playtime,
            String dayText,
            String dateText,
            boolean worldFirst) {
        public RecordRow {
            itemName = itemName != null ? itemName : "";
            itemId = itemId != null ? itemId : "";
            playtime = playtime != null ? playtime : "";
            dayText = dayText != null ? dayText : "";
            dateText = dateText != null ? dateText : "";
        }
    }

    /** All data rendered for one player's section in an HTML summary export. */
    public record PlayerReport(
            UUID uuid,
            String playerName,
            CompletionSummary completion,
            List<RecordRow> rows) {
        public PlayerReport {
            if (playerName == null || playerName.isBlank()) {
                playerName = uuid != null ? uuid.toString() : "player";
            }
            completion = completion != null ? completion : new CompletionSummary(0, 0, 0);
            rows = rows != null ? List.copyOf(rows) : List.of();
        }
    }

    /**
     * Computes target completion against the player's effective targets
     * ({@code personalTargets ∪ defaultTargets}). Records for keys no longer in the effective
     * target set are reflected in {@link CompletionSummary#totalRecords()} without pushing
     * {@link CompletionSummary#completedTargets()} above {@link CompletionSummary#totalTargets()}.
     */
    public static <K> CompletionSummary computeCompletion(
            Collection<K> personalTargets,
            Collection<K> defaultTargets,
            Set<K> recordedKeys) {
        Set<K> effectiveTargets = new LinkedHashSet<>();
        if (defaultTargets != null) {
            for (K key : defaultTargets) {
                if (key != null) {
                    effectiveTargets.add(key);
                }
            }
        }
        if (personalTargets != null) {
            for (K key : personalTargets) {
                if (key != null) {
                    effectiveTargets.add(key);
                }
            }
        }
        Set<K> recorded = recordedKeys != null ? recordedKeys : Set.of();
        int completed = 0;
        for (K target : effectiveTargets) {
            if (recorded.contains(target)) {
                completed++;
            }
        }
        return new CompletionSummary(completed, effectiveTargets.size(), recorded.size());
    }

    /** Formats the HTML generation timestamp in {@code zone} (or the system default when null). */
    public static String formatGeneratedAt(Instant generatedAt, ZoneId zone) {
        Instant effectiveInstant = generatedAt != null ? generatedAt : Instant.now();
        ZoneId effectiveZone = zone != null ? zone : ZoneId.systemDefault();
        return GENERATED_AT_FORMATTER.withZone(effectiveZone).format(effectiveInstant);
    }

    /** Renders a standalone HTML summary document for a single player. */
    public static String renderSinglePlayer(PlayerReport report, Instant generatedAt, ZoneId zone) {
        PlayerReport safeReport = report != null
                ? report
                : new PlayerReport(null, "player", new CompletionSummary(0, 0, 0), List.of());
        String escapedName = TextEscaping.escapeHtml(safeReport.playerName());
        String generatedText = TextEscaping.escapeHtml(formatGeneratedAt(generatedAt, zone));

        StringBuilder sb = new StringBuilder(2048);
        appendDocumentStart(sb, "Item Milestone Log — " + safeReport.playerName());
        sb.append("<div class=\"container\">\n");
        sb.append("  <header class=\"page-header\">\n");
        sb.append("    <h1>Item Milestone Log &mdash; ").append(escapedName).append("</h1>\n");
        sb.append("    <p class=\"meta\">Generated: ").append(generatedText).append("</p>\n");
        sb.append("  </header>\n");
        appendPlayerSection(sb, safeReport, null);
        sb.append("</div>\n");
        appendDocumentEnd(sb);
        return sb.toString();
    }

    /** Renders a standalone HTML summary document containing all players' sections. */
    public static String renderAllPlayers(List<PlayerReport> reports, Instant generatedAt, ZoneId zone) {
        List<PlayerReport> safeReports = reports != null
                ? reports.stream().filter(report -> report != null).toList()
                : List.of();
        String generatedText = TextEscaping.escapeHtml(formatGeneratedAt(generatedAt, zone));
        int totalRecords = 0;
        for (PlayerReport r : safeReports) {
            if (r != null) {
                totalRecords += r.rows().size();
            }
        }

        StringBuilder sb = new StringBuilder(4096);
        appendDocumentStart(sb, "Item Milestone Log — All Players");
        sb.append("<div class=\"container\">\n");
        sb.append("  <header class=\"page-header\">\n");
        sb.append("    <h1>Item Milestone Log &mdash; All Players</h1>\n");
        sb.append("    <p class=\"meta\">Generated: ").append(generatedText)
                .append(" &middot; Players: ").append(safeReports.size())
                .append(" &middot; Total records: ").append(totalRecords).append("</p>\n");
        sb.append("  </header>\n");

        if (safeReports.isEmpty()) {
            sb.append("  <section class=\"card\">\n");
            sb.append("    <p class=\"empty\">No player milestone data recorded yet.</p>\n");
            sb.append("  </section>\n");
        } else {
            if (safeReports.size() > 1) {
                sb.append("  <nav class=\"card\" aria-label=\"Players\">\n");
                sb.append("    <div class=\"stat-label\">Players</div>\n");
                sb.append("    <ul class=\"toc\">\n");
                for (int i = 0; i < safeReports.size(); i++) {
                    PlayerReport r = safeReports.get(i);
                    if (r == null) {
                        continue;
                    }
                    sb.append("      <li><a href=\"#player-").append(i).append("\">")
                            .append(TextEscaping.escapeHtml(r.playerName()))
                            .append(" (").append(TextEscaping.escapeHtml(r.completion().formatSummary()))
                            .append(")</a></li>\n");
                }
                sb.append("    </ul>\n");
                sb.append("  </nav>\n");
            }
            for (int i = 0; i < safeReports.size(); i++) {
                PlayerReport r = safeReports.get(i);
                if (r != null) {
                    appendPlayerSection(sb, r, "player-" + i);
                }
            }
        }

        sb.append("</div>\n");
        appendDocumentEnd(sb);
        return sb.toString();
    }

    private static void appendDocumentStart(StringBuilder sb, String title) {
        sb.append("<!DOCTYPE html>\n");
        sb.append("<html lang=\"en\">\n");
        sb.append("<head>\n");
        sb.append("  <meta charset=\"utf-8\">\n");
        sb.append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        sb.append("  <title>").append(TextEscaping.escapeHtml(title)).append("</title>\n");
        sb.append("  <style>\n");
        sb.append(INLINE_CSS);
        sb.append("  </style>\n");
        sb.append("</head>\n");
        sb.append("<body>\n");
    }

    private static void appendDocumentEnd(StringBuilder sb) {
        sb.append("</body>\n");
        sb.append("</html>\n");
    }

    private static void appendPlayerSection(StringBuilder sb, PlayerReport report, String sectionId) {
        int worldFirstCount = 0;
        for (RecordRow row : report.rows()) {
            if (row.worldFirst()) {
                worldFirstCount++;
            }
        }
        CompletionSummary completion = report.completion();

        sb.append("  <section class=\"card\"");
        if (sectionId != null && !sectionId.isEmpty()) {
            sb.append(" id=\"").append(TextEscaping.escapeHtml(sectionId)).append("\"");
        }
        sb.append(">\n");
        sb.append("    <div class=\"player-header\">\n");
        sb.append("      <h2>").append(TextEscaping.escapeHtml(report.playerName())).append("</h2>\n");
        if (report.uuid() != null) {
            sb.append("      <span class=\"uuid\">").append(TextEscaping.escapeHtml(report.uuid().toString())).append("</span>\n");
        }
        sb.append("    </div>\n");

        sb.append("    <div class=\"summary-grid\">\n");
        sb.append("      <div class=\"stat\"><div class=\"stat-label\">Target Completion</div><div class=\"stat-value\">")
                .append(TextEscaping.escapeHtml(completion.formatSummary())).append("</div></div>\n");
        sb.append("      <div class=\"stat\"><div class=\"stat-label\">Recorded Milestones</div><div class=\"stat-value\">")
                .append(completion.totalRecords()).append("</div></div>\n");
        sb.append("      <div class=\"stat\"><div class=\"stat-label\">World Firsts</div><div class=\"stat-value\">")
                .append(worldFirstCount).append("</div></div>\n");
        sb.append("    </div>\n");

        if (completion.totalTargets() > 0) {
            int barWidth = Math.max(0, Math.min(100, completion.floorPercent()));
            sb.append("    <div class=\"progress-track\" role=\"progressbar\" aria-valuenow=\"")
                    .append(barWidth).append("\" aria-valuemin=\"0\" aria-valuemax=\"100\">")
                    .append("<div class=\"progress-fill\" style=\"width: ").append(barWidth).append("%\"></div></div>\n");
        }

        if (report.rows().isEmpty()) {
            sb.append("    <p class=\"empty\">No milestones recorded yet.</p>\n");
        } else {
            sb.append("    <div class=\"table-wrap\">\n");
            sb.append("      <table>\n");
            sb.append("        <thead>\n");
            sb.append("          <tr>\n");
            sb.append("            <th>Item</th>\n");
            sb.append("            <th>Item ID</th>\n");
            sb.append("            <th class=\"num\">Qty</th>\n");
            sb.append("            <th>Playtime</th>\n");
            sb.append("            <th>Day</th>\n");
            sb.append("            <th>Date</th>\n");
            sb.append("            <th>World First</th>\n");
            sb.append("          </tr>\n");
            sb.append("        </thead>\n");
            sb.append("        <tbody>\n");
            for (RecordRow row : report.rows()) {
                sb.append("          <tr>\n");
                // Unresolvable items (e.g. removed mods) show the ID only (dash in the Item Name column).
                boolean hasResolvedName = !row.itemName().isBlank() && !row.itemName().equals(row.itemId());
                sb.append("            <td>")
                        .append(hasResolvedName ? TextEscaping.escapeHtml(row.itemName()) : "<span class=\"muted\">&mdash;</span>")
                        .append("</td>\n");
                sb.append("            <td><code>").append(TextEscaping.escapeHtml(row.itemId())).append("</code></td>\n");
                sb.append("            <td class=\"num\">").append(row.count()).append("</td>\n");
                sb.append("            <td class=\"nowrap\">").append(TextEscaping.escapeHtml(row.playtime())).append("</td>\n");
                sb.append("            <td class=\"nowrap\">")
                        .append(row.dayText().isEmpty() ? "<span class=\"muted\">&mdash;</span>" : TextEscaping.escapeHtml(row.dayText()))
                        .append("</td>\n");
                sb.append("            <td class=\"nowrap\">")
                        .append(row.dateText().isEmpty() ? "<span class=\"muted\">&mdash;</span>" : TextEscaping.escapeHtml(row.dateText()))
                        .append("</td>\n");
                sb.append("            <td>")
                        .append(row.worldFirst() ? "<span class=\"wf\" title=\"World First\">&#9733; World First</span>" : "")
                        .append("</td>\n");
                sb.append("          </tr>\n");
            }
            sb.append("        </tbody>\n");
            sb.append("      </table>\n");
            sb.append("    </div>\n");
        }
        sb.append("  </section>\n");
    }
}
