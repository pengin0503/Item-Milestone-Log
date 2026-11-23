package com.pengin0503.itemmilestonelog;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Pure-Java RFC 4180 CSV renderer for F26
 * ({@code /milestonelog exportcsv} and {@code /milestonelog exportcsvall}).
 *
 * <p>Produces UTF-8 output with a leading BOM ({@code U+FEFF}, bytes {@code EF BB BF}) and
 * CRLF ({@code \r\n}) line endings. Text cells apply RFC 4180 quoting and formula-injection
 * mitigation via {@link TextEscaping#escapeCsvTextCell(String)}; numeric and boolean columns
 * are exempt from formula prefixing.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class CsvExportRenderer {
    public static final String ALL_PLAYERS_CSV_FILE = HtmlExportRenderer.ALL_PLAYERS_BASE_NAME + ".csv";
    public static final char UTF8_BOM = '\uFEFF';
    public static final String CRLF = "\r\n";

    public static final List<String> HEADER_COLUMNS = List.of(
            "player_uuid",
            "player_name",
            "item_id",
            "count",
            "item_name",
            "play_ticks",
            "playtime",
            "day",
            "epoch_millis",
            "date",
            "world_first"
    );

    public static final String HEADER_LINE = String.join(",", HEADER_COLUMNS);

    private CsvExportRenderer() {
    }

    /**
     * One CSV row representing a single milestone record.
     *
     * @param playerUuid  player UUID string
     * @param playerName  player name (may be empty if unknown)
     * @param itemId      canonical item ID (e.g. {@code minecraft:diamond})
     * @param count       required quantity (>= 1)
     * @param itemName    resolved display name from server-side {@code Component.getString()}, or empty if unknown
     * @param playTicks   cumulative playtime ticks
     * @param playtime    formatted cumulative playtime from {@code Playtime.format}
     * @param displayDay  1-based display day ({@code storedDay + 1}), or {@code null} / {@code <= 0} when unknown
     * @param epochMillis wall-clock timestamp in milliseconds since Unix epoch, or {@code null} / {@code <= 0} when unknown
     * @param date        formatted date ({@code yyyy-MM-dd} in {@code displayTimeZone}), or empty when unknown
     * @param worldFirst  whether this player holds the world-first record for this target key
     */
    public record Row(
            String playerUuid,
            String playerName,
            String itemId,
            int count,
            String itemName,
            int playTicks,
            String playtime,
            Long displayDay,
            Long epochMillis,
            String date,
            boolean worldFirst) {
        public Row {
            playerUuid = playerUuid != null ? playerUuid : "";
            playerName = playerName != null ? playerName : "";
            itemId = itemId != null ? itemId : "";
            itemName = itemName != null ? itemName : "";
            playtime = playtime != null ? playtime : "";
            date = date != null ? date : "";
        }
    }

    /** Formats a single data row without a trailing line break. */
    public static String formatRow(Row row) {
        if (row == null) {
            return ",,,,,,,,,,";
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append(TextEscaping.escapeCsvTextCell(row.playerUuid())).append(',');
        sb.append(TextEscaping.escapeCsvTextCell(row.playerName())).append(',');
        sb.append(TextEscaping.escapeCsvTextCell(row.itemId())).append(',');
        sb.append(row.count()).append(',');
        sb.append(TextEscaping.escapeCsvTextCell(row.itemName())).append(',');
        sb.append(row.playTicks()).append(',');
        sb.append(TextEscaping.escapeCsvTextCell(row.playtime())).append(',');
        if (row.displayDay() != null && row.displayDay() > 0L) {
            sb.append(row.displayDay());
        }
        sb.append(',');
        if (row.epochMillis() != null && row.epochMillis() > 0L) {
            sb.append(row.epochMillis());
        }
        sb.append(',');
        sb.append(TextEscaping.escapeCsvTextCell(row.date())).append(',');
        sb.append(row.worldFirst());
        return sb.toString();
    }

    /**
     * Renders the given rows into a UTF-8 BOM-prefixed, CRLF-delimited RFC 4180 CSV document
     * with the F26 header row. Rows are emitted in the order provided.
     */
    public static String render(List<Row> rows) {
        List<Row> safeRows = rows != null ? rows : List.of();
        StringBuilder sb = new StringBuilder(128 + safeRows.size() * 128);
        sb.append(UTF8_BOM);
        sb.append(HEADER_LINE).append(CRLF);
        for (Row row : safeRows) {
            if (row != null) {
                sb.append(formatRow(row)).append(CRLF);
            }
        }
        return sb.toString();
    }

    /** Encodes {@link #render(List)} as UTF-8 bytes starting with the 3-byte BOM ({@code EF BB BF}). */
    public static byte[] renderUtf8Bytes(List<Row> rows) {
        return render(rows).getBytes(StandardCharsets.UTF_8);
    }
}
