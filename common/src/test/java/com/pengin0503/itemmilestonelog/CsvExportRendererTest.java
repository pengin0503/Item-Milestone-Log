package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvExportRendererTest {
    private static final String EXPECTED_HEADER =
            "player_uuid,player_name,item_id,count,item_name,play_ticks,playtime,day,epoch_millis,date,world_first";

    @Test
    void rendersUtf8BomHeaderAndCrlfForEmptyRows() {
        String csv = CsvExportRenderer.render(List.of());
        assertEquals("\uFEFF" + EXPECTED_HEADER + "\r\n", csv);

        String nullCsv = CsvExportRenderer.render(null);
        assertEquals(csv, nullCsv);

        byte[] bytes = CsvExportRenderer.renderUtf8Bytes(List.of());
        assertTrue(bytes.length >= 3);
        assertEquals((byte) 0xEF, bytes[0]);
        assertEquals((byte) 0xBB, bytes[1]);
        assertEquals((byte) 0xBF, bytes[2]);
        assertEquals(csv, new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    void rendersRowsWithAllColumnsUnknownMetadataAndJapaneseText() {
        CsvExportRenderer.Row complete = new CsvExportRenderer.Row(
                "11111111-1111-1111-1111-111111111111",
                "Alice",
                "minecraft:diamond",
                64,
                "ダイヤモンド",
                72000,
                "1h 00m 00s",
                5L,
                1795500000000L,
                "2026-11-24",
                true
        );
        CsvExportRenderer.Row legacyUnknown = new CsvExportRenderer.Row(
                "22222222-2222-2222-2222-222222222222",
                "Bob",
                "removedmod:relic",
                1,
                "",
                1200,
                "0h 01m 00s",
                null,
                null,
                "",
                false
        );
        CsvExportRenderer.Row zeroUnknown = new CsvExportRenderer.Row(
                "22222222-2222-2222-2222-222222222222",
                "Bob",
                "minecraft:emerald",
                2,
                "Emerald",
                2400,
                "0h 02m 00s",
                0L,
                0L,
                "",
                false
        );

        String csv = CsvExportRenderer.render(List.of(complete, legacyUnknown, zeroUnknown));
        String expected = "\uFEFF"
                + EXPECTED_HEADER + "\r\n"
                + "11111111-1111-1111-1111-111111111111,Alice,minecraft:diamond,64,ダイヤモンド,72000,1h 00m 00s,5,1795500000000,2026-11-24,true\r\n"
                + "22222222-2222-2222-2222-222222222222,Bob,removedmod:relic,1,,1200,0h 01m 00s,,,,false\r\n"
                + "22222222-2222-2222-2222-222222222222,Bob,minecraft:emerald,2,Emerald,2400,0h 02m 00s,,,,false\r\n";
        assertEquals(expected, csv);

        // No bare LF anywhere in rows without embedded newlines.
        assertFalse(csv.replace("\r\n", "").contains("\n"));
    }

    @Test
    void appliesRfc4180QuotingForCommaQuoteAndNewlines() {
        CsvExportRenderer.Row row = new CsvExportRenderer.Row(
                "11111111-1111-1111-1111-111111111111",
                "Alice, \"The Great\"",
                "mod:item",
                1,
                "Line1\nLine2 \"quoted\"\r\nLine3",
                20,
                "0h 00m 01s",
                1L,
                1000L,
                "1970-01-01",
                false
        );

        String formatted = CsvExportRenderer.formatRow(row);
        assertEquals(
                "11111111-1111-1111-1111-111111111111,"
                        + "\"Alice, \"\"The Great\"\"\","
                        + "mod:item,1,"
                        + "\"Line1\nLine2 \"\"quoted\"\"\r\nLine3\","
                        + "20,0h 00m 01s,1,1000,1970-01-01,false",
                formatted
        );
    }

    @Test
    void neutralizesFormulaTriggersInStringCellsButNotNumericColumns() {
        String[] triggers = {"=SUM(A1)", "+1+2", "-cmd", "@evil", "\tindented", "\rcarriage"};
        String[] expectedCells = {"'=SUM(A1)", "'+1+2", "'-cmd", "'@evil", "'\tindented", "\"'\rcarriage\""};

        for (int i = 0; i < triggers.length; i++) {
            CsvExportRenderer.Row row = new CsvExportRenderer.Row(
                    "11111111-1111-1111-1111-111111111111",
                    triggers[i],
                    "minecraft:diamond",
                    1,
                    triggers[i],
                    100,
                    "0h 00m 05s",
                    1L,
                    1000L,
                    "1970-01-01",
                    false
            );
            String formatted = CsvExportRenderer.formatRow(row);
            String expected = "11111111-1111-1111-1111-111111111111,"
                    + expectedCells[i] + ",minecraft:diamond,1,"
                    + expectedCells[i] + ",100,0h 00m 05s,1,1000,1970-01-01,false";
            assertEquals(expected, formatted);
        }

        // Combined formula trigger + comma + quote must both neutralize and RFC-4180 quote.
        CsvExportRenderer.Row combined = new CsvExportRenderer.Row(
                "11111111-1111-1111-1111-111111111111",
                "=HYPERLINK(\"http://x\",\"y\")",
                "minecraft:diamond",
                1,
                "+A1,B1",
                200,
                "0h 00m 10s",
                2L,
                2000L,
                "1970-01-01",
                true
        );
        assertEquals(
                "11111111-1111-1111-1111-111111111111,"
                        + "\"'=HYPERLINK(\"\"http://x\"\",\"\"y\"\")\","
                        + "minecraft:diamond,1,"
                        + "\"'+A1,B1\","
                        + "200,0h 00m 10s,2,2000,1970-01-01,true",
                CsvExportRenderer.formatRow(combined)
        );

        // Numeric columns are exempt from formula-injection prefixing (even if negative).
        CsvExportRenderer.Row negativeNumeric = new CsvExportRenderer.Row(
                "11111111-1111-1111-1111-111111111111",
                "Steve",
                "minecraft:diamond",
                -1,
                "Diamond",
                -5,
                "0h 00m 00s",
                1L,
                1000L,
                "1970-01-01",
                false
        );
        assertEquals(
                "11111111-1111-1111-1111-111111111111,Steve,minecraft:diamond,-1,Diamond,-5,0h 00m 00s,1,1000,1970-01-01,false",
                CsvExportRenderer.formatRow(negativeNumeric)
        );
    }
}
