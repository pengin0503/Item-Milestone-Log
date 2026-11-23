package com.pengin0503.itemmilestonelog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordNotifierTest {
    // 2023-11-14T22:13:20Z -> UTC: 2023-11-14, Asia/Tokyo (+09:00): 2023-11-15
    private static final long EPOCH_2023_11_14 = 1_700_000_000_000L;
    private static final String SECRET_WEBHOOK_1 = "https://discord.com/api/webhooks/111111/super-secret-token-1";
    private static final String SECRET_WEBHOOK_2 = "https://discord.com/api/webhooks/222222/super-secret-token-2";

    @AfterEach
    void resetDeadWebhookState() {
        DiscordNotifier.resetDeadWebhook();
        DiscordNotifier.resetEnvWarnings();
        DiscordNotifier.resetTestInProgress();
        // Clear interrupt flag in case an interruption test left it set.
        Thread.interrupted();
    }

    @Test
    void singlePlainTextEscapesMarkdownPreservesLegacyFormatAndCapsAt2000() {
        MilestoneRecord rec = new MilestoneRecord(72_000, 1L, EPOCH_2023_11_14);

        String normalJson = DiscordNotifier.buildSinglePayloadJson(
                "Alice", TargetKey.of("minecraft:diamond"), null, rec, false, ZoneOffset.UTC, false, "en_us");
        JsonObject normal = JsonParser.parseString(normalJson).getAsJsonObject();
        assertFalse(normal.has("embeds"));
        assertEquals("★ Milestone recorded: Alice obtained minecraft:diamond at 1h 00m 00s (Day 2, 2023-11-14)",
                normal.get("content").getAsString());
        assertEquals(0, normal.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());

        String escapedJson = DiscordNotifier.buildSinglePayloadJson(
                "_Steve_ [OP]", TargetKey.of("mod_a:super-item_1", 64), null, rec, true, ZoneOffset.UTC, false, "en_us");
        JsonObject escaped = JsonParser.parseString(escapedJson).getAsJsonObject();
        assertEquals("★ [World First] \\_Steve\\_ \\[OP\\] obtained mod\\_a:super\\-item\\_1 ×64 at 1h 00m 00s (Day 2, 2023-11-14)",
                escaped.get("content").getAsString());

        // Plain content cap (2000 UTF-16 code units, surrogate-pair-safe)
        String longEmojiName = "\uD83D\uDC8E".repeat(1200);
        String cappedJson = DiscordNotifier.buildSinglePayloadJson(
                longEmojiName, TargetKey.of("minecraft:diamond"), null, rec, false, ZoneOffset.UTC, false, "en_us");
        String cappedContent = JsonParser.parseString(cappedJson).getAsJsonObject().get("content").getAsString();
        assertTrue(cappedContent.length() <= DiscordNotifier.PLAIN_CONTENT_MAX_CHARS);
        assertFalse(Character.isHighSurrogate(cappedContent.charAt(cappedContent.length() - 1)));
    }

    @Test
    void singleEmbedNormalAndWorldFirstJsonIncludesFieldsFooterTimestampAndEscaping() {
        MilestoneRecord rec = new MilestoneRecord(72_000, 1L, EPOCH_2023_11_14);

        // Normal single record (count = 1, UTC)
        String normalJson = DiscordNotifier.buildSinglePayloadJson(
                "Alice", TargetKey.of("minecraft:diamond"), "Diamond", rec, false, ZoneOffset.UTC, true, "en_us");
        JsonObject normalBody = JsonParser.parseString(normalJson).getAsJsonObject();
        assertFalse(normalBody.has("content"));
        assertEquals(0, normalBody.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());
        JsonArray embeds = normalBody.getAsJsonArray("embeds");
        assertEquals(1, embeds.size());

        JsonObject embed = embeds.get(0).getAsJsonObject();
        assertEquals("Diamond", embed.get("title").getAsString());
        assertEquals("Alice obtained Diamond", embed.get("description").getAsString());
        assertEquals(DiscordNotifier.COLOR_NORMAL, embed.get("color").getAsInt());
        assertEquals("Alice", embed.getAsJsonObject("footer").get("text").getAsString());
        assertEquals("2023-11-14T22:13:20Z", embed.get("timestamp").getAsString());

        JsonArray fields = embed.getAsJsonArray("fields");
        assertEquals(3, fields.size());
        assertField(fields.get(0).getAsJsonObject(), "Playtime", "1h 00m 00s");
        assertField(fields.get(1).getAsJsonObject(), "Day", "2");
        assertField(fields.get(2).getAsJsonObject(), "Date", "2023-11-14");

        // World-first record with quantity, markdown chars, Asia/Tokyo timezone, and Japanese templates
        String wfJson = DiscordNotifier.buildSinglePayloadJson(
                "_Steve_", TargetKey.of("minecraft:diamond", 64), "Super_Sword *v2* ×64",
                rec, true, ZoneId.of("Asia/Tokyo"), true, "ja_jp");
        JsonObject wfEmbed = JsonParser.parseString(wfJson).getAsJsonObject()
                .getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals("Super_Sword *v2* ×64", wfEmbed.get("title").getAsString());
        assertEquals("★ 世界初 — \\_Steve\\_ が Super\\_Sword \\*v2\\* ×64 を入手",
                wfEmbed.get("description").getAsString());
        assertEquals(DiscordNotifier.COLOR_WORLD_FIRST, wfEmbed.get("color").getAsInt());
        assertEquals("_Steve_", wfEmbed.getAsJsonObject("footer").get("text").getAsString());
        assertEquals("2023-11-14T22:13:20Z", wfEmbed.get("timestamp").getAsString());

        JsonArray wfFields = wfEmbed.getAsJsonArray("fields");
        assertEquals(3, wfFields.size());
        assertField(wfFields.get(0).getAsJsonObject(), "プレイ時間", "1h 00m 00s");
        assertField(wfFields.get(1).getAsJsonObject(), "ゲーム内日数", "2");
        assertField(wfFields.get(2).getAsJsonObject(), "日付", "2023-11-15");

        // English world-first description
        String wfEnJson = DiscordNotifier.buildSinglePayloadJson(
                "Alice", TargetKey.of("minecraft:diamond", 64), "Diamond ×64",
                rec, true, ZoneOffset.UTC, true, "en_us");
        JsonObject wfEnEmbed = JsonParser.parseString(wfEnJson).getAsJsonObject()
                .getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals("Diamond ×64", wfEnEmbed.get("title").getAsString());
        assertEquals("★ World First — Alice obtained Diamond ×64", wfEnEmbed.get("description").getAsString());
        assertEquals(DiscordNotifier.COLOR_WORLD_FIRST, wfEnEmbed.get("color").getAsInt());
    }

    @Test
    void singleEmbedOmitsUnknownDayDateAndTimestamp() {
        // Legacy record: both Day and epoch unknown
        MilestoneRecord legacy = MilestoneRecord.legacy(3600);
        JsonObject legacyEmbed = JsonParser.parseString(DiscordNotifier.buildSinglePayloadJson(
                "Alice", TargetKey.of("minecraft:diamond"), "Diamond", legacy, false, ZoneOffset.UTC, true, "en_us"))
                .getAsJsonObject().getAsJsonArray("embeds").get(0).getAsJsonObject();
        JsonArray legacyFields = legacyEmbed.getAsJsonArray("fields");
        assertEquals(1, legacyFields.size());
        assertField(legacyFields.get(0).getAsJsonObject(), "Playtime", "0h 03m 00s");
        assertFalse(legacyEmbed.has("timestamp"));

        // Known Day, unknown epoch -> Day present, Date & timestamp omitted
        MilestoneRecord dayOnly = new MilestoneRecord(3600, 4L, MilestoneRecord.UNKNOWN_EPOCH);
        JsonObject dayOnlyEmbed = JsonParser.parseString(DiscordNotifier.buildSinglePayloadJson(
                "Alice", TargetKey.of("minecraft:diamond"), "Diamond", dayOnly, false, ZoneOffset.UTC, true, "en_us"))
                .getAsJsonObject().getAsJsonArray("embeds").get(0).getAsJsonObject();
        JsonArray dayOnlyFields = dayOnlyEmbed.getAsJsonArray("fields");
        assertEquals(2, dayOnlyFields.size());
        assertField(dayOnlyFields.get(0).getAsJsonObject(), "Playtime", "0h 03m 00s");
        assertField(dayOnlyFields.get(1).getAsJsonObject(), "Day", "5");
        assertFalse(dayOnlyEmbed.has("timestamp"));

        // Unknown Day, known epoch -> Day omitted, Date & timestamp present
        MilestoneRecord epochOnly = new MilestoneRecord(3600, MilestoneRecord.UNKNOWN_DAY, EPOCH_2023_11_14);
        JsonObject epochOnlyEmbed = JsonParser.parseString(DiscordNotifier.buildSinglePayloadJson(
                "Alice", TargetKey.of("minecraft:diamond"), "Diamond", epochOnly, false, ZoneOffset.UTC, true, "en_us"))
                .getAsJsonObject().getAsJsonArray("embeds").get(0).getAsJsonObject();
        JsonArray epochOnlyFields = epochOnlyEmbed.getAsJsonArray("fields");
        assertEquals(2, epochOnlyFields.size());
        assertField(epochOnlyFields.get(0).getAsJsonObject(), "Playtime", "0h 03m 00s");
        assertField(epochOnlyFields.get(1).getAsJsonObject(), "Date", "2023-11-14");
        assertEquals("2023-11-14T22:13:20Z", epochOnlyEmbed.get("timestamp").getAsString());
    }

    @Test
    void embedCapsTitleDescriptionAndFieldValueWithoutSplittingSurrogatePairs() {
        MilestoneRecord rec = new MilestoneRecord(72_000, 1L, EPOCH_2023_11_14);
        String longTitle = "A".repeat(255) + "\uD83D\uDC8E" + "tail";
        String longName = "P".repeat(4095) + "\uD83D\uDC8E";

        JsonObject embed = JsonParser.parseString(DiscordNotifier.buildSingleEmbedJson(
                longName, longTitle, rec, false, ZoneOffset.UTC, "en_us"))
                .getAsJsonObject().getAsJsonArray("embeds").get(0).getAsJsonObject();

        String title = embed.get("title").getAsString();
        assertEquals(255, title.length());
        assertFalse(Character.isHighSurrogate(title.charAt(title.length() - 1)));

        String description = embed.get("description").getAsString();
        assertTrue(description.length() <= DiscordNotifier.EMBED_DESCRIPTION_MAX_CHARS);
        assertFalse(Character.isHighSurrogate(description.charAt(description.length() - 1)));

        String longFieldVal = "V".repeat(1023) + "\uD83D\uDC8E" + "more";
        JsonObject field = DiscordNotifier.buildInlineField("Playtime", longFieldVal);
        String fieldValue = field.get("value").getAsString();
        assertEquals(1023, fieldValue.length());
        assertFalse(Character.isHighSurrogate(fieldValue.charAt(fieldValue.length() - 1)));
    }

    @Test
    void groupedEmbedBuildsSingleEmbedWithColorDisplayNamesFieldsAndLineCaps() {
        MilestoneRecord rec = new MilestoneRecord(72_000, 1L, EPOCH_2023_11_14);
        TargetKey coal = TargetKey.of("minecraft:coal");
        TargetKey diamond64 = TargetKey.of("minecraft:diamond", 64);
        TargetKey emerald = TargetKey.of("minecraft:emerald");
        Map<TargetKey, String> names = Map.of(
                coal, "Coal",
                diamond64, "Diamond ×64",
                emerald, "Super_Emerald [Rare]"
        );

        // No world-first in group -> blue color
        List<DiscordNotifier.MilestoneEntry> normalGroup = List.of(
                new DiscordNotifier.MilestoneEntry(coal, rec, false),
                new DiscordNotifier.MilestoneEntry(diamond64, rec, false),
                new DiscordNotifier.MilestoneEntry(emerald, rec, false));
        JsonObject normalBody = JsonParser.parseString(DiscordNotifier.buildGroupedPayloadJson(
                "Alice", normalGroup, ZoneOffset.UTC, true, "en_us", names::get)).getAsJsonObject();
        assertFalse(normalBody.has("content"));
        assertEquals(0, normalBody.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());
        assertEquals(1, normalBody.getAsJsonArray("embeds").size());

        JsonObject normalEmbed = normalBody.getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals("Alice recorded 3 milestones", normalEmbed.get("title").getAsString());
        assertEquals(String.join("\n",
                "- Coal",
                "- Diamond ×64",
                "- Super\\_Emerald \\[Rare\\]"), normalEmbed.get("description").getAsString());
        assertEquals(DiscordNotifier.COLOR_NORMAL, normalEmbed.get("color").getAsInt());
        assertEquals("Alice", normalEmbed.getAsJsonObject("footer").get("text").getAsString());
        assertEquals("2023-11-14T22:13:20Z", normalEmbed.get("timestamp").getAsString());
        JsonArray fields = normalEmbed.getAsJsonArray("fields");
        assertEquals(3, fields.size());
        assertField(fields.get(0).getAsJsonObject(), "Playtime", "1h 00m 00s");
        assertField(fields.get(1).getAsJsonObject(), "Day", "2");
        assertField(fields.get(2).getAsJsonObject(), "Date", "2023-11-14");

        // At least one world-first in group -> gold color; Japanese templates; unknown Day/epoch omitted
        MilestoneRecord legacy = MilestoneRecord.legacy(72_000);
        List<DiscordNotifier.MilestoneEntry> wfGroup = List.of(
                new DiscordNotifier.MilestoneEntry(coal, legacy, false),
                new DiscordNotifier.MilestoneEntry(diamond64, legacy, true),
                new DiscordNotifier.MilestoneEntry(emerald, legacy, false));
        JsonObject wfEmbed = JsonParser.parseString(DiscordNotifier.buildGroupedPayloadJson(
                "Alice", wfGroup, ZoneOffset.UTC, true, "ja_jp", names::get))
                .getAsJsonObject().getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals("Alice が 3 件を記録", wfEmbed.get("title").getAsString());
        assertEquals(String.join("\n",
                "- Coal",
                "- ★ [世界初] Diamond ×64",
                "- Super\\_Emerald \\[Rare\\]"), wfEmbed.get("description").getAsString());
        assertEquals(DiscordNotifier.COLOR_WORLD_FIRST, wfEmbed.get("color").getAsInt());
        assertEquals(1, wfEmbed.getAsJsonArray("fields").size());
        assertField(wfEmbed.getAsJsonArray("fields").get(0).getAsJsonObject(), "プレイ時間", "1h 00m 00s");
        assertFalse(wfEmbed.has("timestamp"));

        // 25 entries cap at 20 lines + "...and 5 more" within 4096 chars
        List<DiscordNotifier.MilestoneEntry> twentyFive = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            twentyFive.add(new DiscordNotifier.MilestoneEntry(TargetKey.of("minecraft:item_" + i), rec, i == 25));
        }
        JsonObject cappedEmbed = JsonParser.parseString(DiscordNotifier.buildGroupedPayloadJson(
                "Alice", twentyFive, ZoneOffset.UTC, true, "en_us", k -> "Item " + k.itemId().substring("minecraft:".length())))
                .getAsJsonObject().getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals(DiscordNotifier.COLOR_WORLD_FIRST, cappedEmbed.get("color").getAsInt());
        String desc = cappedEmbed.get("description").getAsString();
        String[] lines = desc.split("\n");
        assertEquals(21, lines.length);
        assertEquals("- Item item\\_1", lines[0]);
        assertEquals("- Item item\\_20", lines[19]);
        assertEquals("...and 5 more", lines[20]);
        assertTrue(desc.length() <= DiscordNotifier.EMBED_DESCRIPTION_MAX_CHARS);

        // Grouped plain-text mode escapes player name and item IDs when embeds are off
        String plainGroupJson = DiscordNotifier.buildGroupedPayloadJson(
                "_Bob_", normalGroup, ZoneOffset.UTC, false, "en_us", names::get);
        JsonObject plainGroup = JsonParser.parseString(plainGroupJson).getAsJsonObject();
        assertFalse(plainGroup.has("embeds"));
        assertTrue(plainGroup.get("content").getAsString().startsWith("★ Milestones recorded: \\_Bob\\_ obtained 3 targets at "));
    }

    @Test
    void enUsPlainTextMatchesExact101FormatResult() {
        MilestoneRecord rec = new MilestoneRecord(72_000, 1L, EPOCH_2023_11_14);
        String formattedTime = Playtime.formatRecord(rec, ZoneOffset.UTC);

        TargetKey single = TargetKey.of("minecraft:diamond");
        String expectedNormal = String.format("★ Milestone recorded: %s obtained %s at %s",
                "Alice", single.formatDisplayId(), formattedTime);
        String actualNormal = JsonParser.parseString(DiscordNotifier.buildSinglePayloadJson(
                "Alice", single, null, rec, false, ZoneOffset.UTC, false, "en_us"))
                .getAsJsonObject().get("content").getAsString();
        assertEquals(expectedNormal, actualNormal);

        TargetKey quantity = TargetKey.of("minecraft:diamond", 100);
        String expectedWorldFirst = String.format("★ [World First] %s obtained %s at %s",
                "Alice", quantity.formatDisplayId(), formattedTime);
        String actualWorldFirst = JsonParser.parseString(DiscordNotifier.buildSinglePayloadJson(
                "Alice", quantity, null, rec, true, ZoneOffset.UTC, false, "en_us"))
                .getAsJsonObject().get("content").getAsString();
        assertEquals(expectedWorldFirst, actualWorldFirst);
    }

    @Test
    void plainTextUsesConfiguredLanguageAndEscapesValuesBeforeFormatting() {
        MilestoneRecord rec = new MilestoneRecord(72_000, 1L, EPOCH_2023_11_14);
        TargetKey target = TargetKey.of("mod_a:super-item_1", 64);

        // ja_jp single normal & world-first: values are Markdown-escaped, template punctuation is not
        String jaNormal = JsonParser.parseString(DiscordNotifier.buildSinglePayloadJson(
                "_Steve_ [OP]", target, null, rec, false, ZoneOffset.UTC, false, "ja_jp"))
                .getAsJsonObject().get("content").getAsString();
        assertEquals("★ マイルストーン記録: \\_Steve\\_ \\[OP\\] が mod\\_a:super\\-item\\_1 ×64 を入手（1h 00m 00s (Day 2, 2023-11-14)）",
                jaNormal);

        String jaWorldFirst = JsonParser.parseString(DiscordNotifier.buildSinglePayloadJson(
                "_Steve_ [OP]", target, null, rec, true, ZoneOffset.UTC, false, "ja_jp"))
                .getAsJsonObject().get("content").getAsString();
        assertEquals("★ [世界初] \\_Steve\\_ \\[OP\\] が mod\\_a:super\\-item\\_1 ×64 を入手（1h 00m 00s (Day 2, 2023-11-14)）",
                jaWorldFirst);

        // ja_jp grouped plain text with >20 items uses ja_jp header, world-first marker, and digest.more
        List<DiscordNotifier.MilestoneEntry> twentyFive = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            twentyFive.add(new DiscordNotifier.MilestoneEntry(TargetKey.of("minecraft:item_" + i), rec, i == 1));
        }
        String jaGrouped = JsonParser.parseString(DiscordNotifier.buildGroupedPayloadJson(
                "_Bob_", twentyFive, ZoneOffset.UTC, false, "ja_jp", TargetKey::formatDisplayId))
                .getAsJsonObject().get("content").getAsString();
        String[] jaLines = jaGrouped.split("\n");
        assertEquals(22, jaLines.length);
        assertEquals("★ マイルストーン記録: \\_Bob\\_ が 25 件を入手（1h 00m 00s (Day 2, 2023-11-14)）", jaLines[0]);
        assertEquals("- ★ [世界初] minecraft:item\\_1", jaLines[1]);
        assertEquals("- minecraft:item\\_20", jaLines[20]);
        assertEquals("…ほか 5 件", jaLines[21]);

        // Unbundled language falls back to en_us
        String fallback = JsonParser.parseString(DiscordNotifier.buildSinglePayloadJson(
                "Alice", TargetKey.of("minecraft:diamond"), null, rec, false, ZoneOffset.UTC, false, "fr_fr"))
                .getAsJsonObject().get("content").getAsString();
        assertEquals("★ Milestone recorded: Alice obtained minecraft:diamond at 1h 00m 00s (Day 2, 2023-11-14)",
                fallback);
    }

    @Test
    void configDiscordLanguageValidatorAcceptsOnlyBundledLanguages() {
        assertEquals("en_us", Config.DISCORD_LANGUAGE.getDefault());
        for (String bundled : ServerLang.BUNDLED_LANGUAGES) {
            assertTrue(Config.validateDiscordLanguage(bundled), "expected bundled language to be valid: " + bundled);
        }
        assertTrue(Config.validateDiscordLanguage("en_us"));
        assertTrue(Config.validateDiscordLanguage("ja_jp"));
        assertFalse(Config.validateDiscordLanguage("fr_fr"));
        assertFalse(Config.validateDiscordLanguage("EN_US"));
        assertFalse(Config.validateDiscordLanguage("ja_JP"));
        assertFalse(Config.validateDiscordLanguage(""));
        assertFalse(Config.validateDiscordLanguage(null));
        assertFalse(Config.validateDiscordLanguage(123));
    }

    @Test
    void http429WithValidRetryAfterWaitsAndRetriesOnce() {
        URI uri = URI.create(SECRET_WEBHOOK_1);
        List<Long> sleeps = new ArrayList<>();
        List<String> warns = new ArrayList<>();
        Queue<DiscordNotifier.HttpPostResult> responses = new ArrayDeque<>(List.of(
                new DiscordNotifier.HttpPostResult(429, "1.5"),
                new DiscordNotifier.HttpPostResult(204, null)
        ));
        AtomicInteger attempts = new AtomicInteger();

        DiscordNotifier.sendWithTransport(
                uri,
                "{\"content\":\"hi\"}",
                (u, body) -> {
                    attempts.incrementAndGet();
                    return responses.remove();
                },
                sleeps::add,
                warns::add);

        assertEquals(2, attempts.get());
        assertEquals(List.of(1500L), sleeps);
        assertTrue(warns.isEmpty());
        assertFalse(DiscordNotifier.isDeadWebhook(SECRET_WEBHOOK_1));

        // Zero-second Retry-After retries immediately without sleeping.
        sleeps.clear();
        attempts.set(0);
        Queue<DiscordNotifier.HttpPostResult> zeroResponses = new ArrayDeque<>(List.of(
                new DiscordNotifier.HttpPostResult(429, "0"),
                new DiscordNotifier.HttpPostResult(204, null)
        ));
        DiscordNotifier.sendWithTransport(
                uri,
                "{\"content\":\"hi\"}",
                (u, body) -> {
                    attempts.incrementAndGet();
                    return zeroResponses.remove();
                },
                sleeps::add,
                warns::add);
        assertEquals(2, attempts.get());
        assertTrue(sleeps.isEmpty());
        assertTrue(warns.isEmpty());

        // Exact 5-second cap is retried.
        sleeps.clear();
        attempts.set(0);
        Queue<DiscordNotifier.HttpPostResult> capResponses = new ArrayDeque<>(List.of(
                new DiscordNotifier.HttpPostResult(429, "5"),
                new DiscordNotifier.HttpPostResult(200, null)
        ));
        DiscordNotifier.sendWithTransport(
                uri,
                "{\"content\":\"hi\"}",
                (u, body) -> {
                    attempts.incrementAndGet();
                    return capResponses.remove();
                },
                sleeps::add,
                warns::add);
        assertEquals(2, attempts.get());
        assertEquals(List.of(5000L), sleeps);
        assertTrue(warns.isEmpty());
    }

    @Test
    void http429DropsWithWarnWhenHeaderMissingInvalidOverCapOrSecond429() {
        URI uri = URI.create(SECRET_WEBHOOK_1);

        // Missing, invalid, and >5s headers drop immediately with no sleep and no retry.
        for (String header : new String[]{null, "", "invalid", "5.001", "10", "-1"}) {
            List<Long> sleeps = new ArrayList<>();
            List<String> warns = new ArrayList<>();
            AtomicInteger attempts = new AtomicInteger();

            DiscordNotifier.sendWithTransport(
                    uri,
                    "{\"content\":\"hi\"}",
                    (u, body) -> {
                        attempts.incrementAndGet();
                        return new DiscordNotifier.HttpPostResult(429, header);
                    },
                    sleeps::add,
                    warns::add);

            assertEquals(1, attempts.get(), "header=" + header);
            assertTrue(sleeps.isEmpty(), "header=" + header);
            assertEquals(1, warns.size(), "header=" + header);
            assertTrue(warns.get(0).contains("429"), "header=" + header);
            assertFalse(warns.get(0).contains(SECRET_WEBHOOK_1));
        }

        // Second 429 on retry drops with warn and never retries a third time.
        List<Long> sleeps = new ArrayList<>();
        List<String> warns = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        DiscordNotifier.sendWithTransport(
                uri,
                "{\"content\":\"hi\"}",
                (u, body) -> {
                    attempts.incrementAndGet();
                    return new DiscordNotifier.HttpPostResult(429, "1");
                },
                sleeps::add,
                warns::add);

        assertEquals(2, attempts.get());
        assertEquals(List.of(1000L), sleeps);
        assertEquals(1, warns.size());
        assertTrue(warns.get(0).contains("429"));
        assertFalse(warns.get(0).contains(SECRET_WEBHOOK_1));
    }

    @Test
    void http429InterruptedDuringRetryWaitDropsWithoutRetrying() {
        URI uri = URI.create(SECRET_WEBHOOK_1);
        List<String> warns = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();

        DiscordNotifier.sendWithTransport(
                uri,
                "{\"content\":\"hi\"}",
                (u, body) -> {
                    attempts.incrementAndGet();
                    return new DiscordNotifier.HttpPostResult(429, "3");
                },
                delayMs -> {
                    throw new InterruptedException("shutdown");
                },
                warns::add);

        assertEquals(1, attempts.get());
        assertTrue(Thread.interrupted(), "interrupt status should be restored");
        assertTrue(warns.isEmpty());
    }

    @Test
    void http404And401StopSendingLogOneWarnAndResumeOnUrlChangeOrReset() {
        for (int deadStatus : new int[]{404, 401}) {
            DiscordNotifier.resetDeadWebhook();
            URI uri1 = URI.create(SECRET_WEBHOOK_1);
            List<String> warns = new ArrayList<>();
            AtomicInteger attempts = new AtomicInteger();

            // First post gets 404/401 -> marks URL dead and logs ONE warn without the URL.
            DiscordNotifier.sendWithTransport(
                    uri1,
                    "{\"content\":\"first\"}",
                    (u, body) -> {
                        attempts.incrementAndGet();
                        return new DiscordNotifier.HttpPostResult(deadStatus, null);
                    },
                    delayMs -> {},
                    warns::add);

            assertEquals(1, attempts.get());
            assertTrue(DiscordNotifier.isDeadWebhook(SECRET_WEBHOOK_1));
            assertEquals(1, warns.size());
            assertTrue(warns.get(0).contains(String.valueOf(deadStatus)));
            assertTrue(warns.get(0).contains("webhook may be deleted or the URL is wrong"));
            assertFalse(warns.get(0).contains(SECRET_WEBHOOK_1));
            assertFalse(warns.get(0).contains("super-secret-token-1"));

            // Already-queued post to same URL is skipped with 0 HTTP attempts and 0 extra warns.
            DiscordNotifier.sendWithTransport(
                    uri1,
                    "{\"content\":\"queued\"}",
                    (u, body) -> {
                        attempts.incrementAndGet();
                        return new DiscordNotifier.HttpPostResult(204, null);
                    },
                    delayMs -> {},
                    warns::add);
            assertEquals(1, attempts.get());
            assertEquals(1, warns.size());

            // Subsequent resolveWebhookUri for the same URL returns null silently.
            assertNull(DiscordNotifier.resolveWebhookUri(SECRET_WEBHOOK_1, warns::add));
            assertEquals(1, warns.size());

            // Changing the resolved URL clears dead state and resumes sending.
            URI resolved2 = DiscordNotifier.resolveWebhookUri(SECRET_WEBHOOK_2, warns::add);
            assertNotNull(resolved2);
            assertEquals(SECRET_WEBHOOK_2, resolved2.toString());
            assertFalse(DiscordNotifier.isDeadWebhook(SECRET_WEBHOOK_1));
            DiscordNotifier.sendWithTransport(
                    resolved2,
                    "{\"content\":\"resumed\"}",
                    (u, body) -> {
                        attempts.incrementAndGet();
                        return new DiscordNotifier.HttpPostResult(204, null);
                    },
                    delayMs -> {},
                    warns::add);
            assertEquals(2, attempts.get());
            assertEquals(1, warns.size());

            // Mark dead again, then verify resetDeadWebhook() (F49 seam) resumes the same URL.
            DiscordNotifier.sendWithTransport(
                    uri1,
                    "{\"content\":\"dead-again\"}",
                    (u, body) -> {
                        attempts.incrementAndGet();
                        return new DiscordNotifier.HttpPostResult(deadStatus, null);
                    },
                    delayMs -> {},
                    warns::add);
            assertTrue(DiscordNotifier.isDeadWebhook(SECRET_WEBHOOK_1));
            assertNull(DiscordNotifier.resolveWebhookUri(SECRET_WEBHOOK_1, warns::add));

            DiscordNotifier.resetDeadWebhook();
            assertFalse(DiscordNotifier.isDeadWebhook(SECRET_WEBHOOK_1));
            assertNotNull(DiscordNotifier.resolveWebhookUri(SECRET_WEBHOOK_1, warns::add));
        }
    }

    @Test
    void http429FollowedBy404OnRetryMarksWebhookDeadOnce() {
        URI uri = URI.create(SECRET_WEBHOOK_1);
        List<Long> sleeps = new ArrayList<>();
        List<String> warns = new ArrayList<>();
        Queue<DiscordNotifier.HttpPostResult> responses = new ArrayDeque<>(List.of(
                new DiscordNotifier.HttpPostResult(429, "0.5"),
                new DiscordNotifier.HttpPostResult(404, null)
        ));
        AtomicInteger attempts = new AtomicInteger();

        DiscordNotifier.sendWithTransport(
                uri,
                "{\"content\":\"hi\"}",
                (u, body) -> {
                    attempts.incrementAndGet();
                    return responses.remove();
                },
                sleeps::add,
                warns::add);

        assertEquals(2, attempts.get());
        assertEquals(List.of(500L), sleeps);
        assertTrue(DiscordNotifier.isDeadWebhook(SECRET_WEBHOOK_1));
        assertEquals(1, warns.size());
        assertTrue(warns.get(0).contains("404"));
        assertFalse(warns.get(0).contains(SECRET_WEBHOOK_1));
    }

    @Test
    void nonDeadErrorsAndExceptionsDoNotMarkDeadOrLeakWebhookUrl() {
        URI uri = URI.create(SECRET_WEBHOOK_1);
        List<String> warns = new ArrayList<>();

        // HTTP 500 logs status, does not mark webhook dead.
        DiscordNotifier.sendWithTransport(
                uri,
                "{\"content\":\"hi\"}",
                (u, body) -> new DiscordNotifier.HttpPostResult(500, null),
                delayMs -> {},
                warns::add);
        assertFalse(DiscordNotifier.isDeadWebhook(SECRET_WEBHOOK_1));
        assertEquals(1, warns.size());
        assertTrue(warns.get(0).contains("500"));
        assertFalse(warns.get(0).contains(SECRET_WEBHOOK_1));

        // Exception whose message embeds the secret URL must log only the simple class name.
        DiscordNotifier.sendWithTransport(
                uri,
                "{\"content\":\"hi\"}",
                (u, body) -> {
                    throw new IOException("connection reset while posting to " + SECRET_WEBHOOK_1);
                },
                delayMs -> {},
                warns::add);
        assertFalse(DiscordNotifier.isDeadWebhook(SECRET_WEBHOOK_1));
        assertEquals(2, warns.size());
        assertTrue(warns.get(1).contains("IOException"));
        assertFalse(warns.get(1).contains(SECRET_WEBHOOK_1));
        assertFalse(warns.get(1).contains("super-secret-token-1"));
    }

    @Test
    void envReferenceResolvesAndValidatesHttpsWithoutLeakingValue() {
        Map<String, String> env = Map.of(
                "IML_GOOD", "  " + SECRET_WEBHOOK_1 + "  ",
                "IML_EMPTY", "   ",
                "IML_HTTP", "http://discord.com/api/webhooks/111111/super-secret-http",
                "IML_BAD_URI", "https://discord.com/api/webhooks/bad space/super-secret-uri"
        );
        List<String> warns = new ArrayList<>();

        // Present valid HTTPS env var resolves cleanly with 0 warns.
        URI uri = DiscordNotifier.resolveWebhookUri("  env:IML_GOOD  ", env::get, warns::add);
        assertNotNull(uri);
        assertEquals(SECRET_WEBHOOK_1, uri.toString());
        assertTrue(warns.isEmpty());

        // Missing env var warns ONCE per distinct configured value (var name allowed, no value).
        assertNull(DiscordNotifier.resolveWebhookUri("env:IML_MISSING", env::get, warns::add));
        assertNull(DiscordNotifier.resolveWebhookUri("env:IML_MISSING", env::get, warns::add));
        assertNull(DiscordNotifier.resolveWebhookUri("  env:IML_MISSING  ", env::get, warns::add));
        assertEquals(1, warns.size());
        assertTrue(warns.get(0).contains("IML_MISSING"));

        // Different configured value (empty env var) warns ONCE.
        assertNull(DiscordNotifier.resolveWebhookUri("env:IML_EMPTY", env::get, warns::add));
        assertNull(DiscordNotifier.resolveWebhookUri("env:IML_EMPTY", env::get, warns::add));
        assertEquals(2, warns.size());
        assertTrue(warns.get(1).contains("IML_EMPTY"));

        // Invalid env var name warns ONCE and never leaks accidental URL content.
        String accidentalSecret = "env:" + SECRET_WEBHOOK_1;
        assertNull(DiscordNotifier.resolveWebhookUri(accidentalSecret, env::get, warns::add));
        assertNull(DiscordNotifier.resolveWebhookUri(accidentalSecret, env::get, warns::add));
        assertEquals(3, warns.size());
        assertFalse(warns.get(2).contains(SECRET_WEBHOOK_1));
        assertFalse(warns.get(2).contains("super-secret-token-1"));

        // Uppercase ENV: is NOT treated as an env reference and fails https check.
        assertNull(DiscordNotifier.resolveWebhookUri("ENV:IML_GOOD", env::get, warns::add));
        assertEquals(4, warns.size());
        assertTrue(warns.get(3).contains("https"));
        assertFalse(warns.get(3).contains(SECRET_WEBHOOK_1));

        // HTTPS and URI validation apply to env var values without leaking them.
        assertNull(DiscordNotifier.resolveWebhookUri("env:IML_HTTP", env::get, warns::add));
        assertEquals(5, warns.size());
        assertTrue(warns.get(4).contains("https"));
        assertFalse(warns.get(4).contains("super-secret-http"));

        assertNull(DiscordNotifier.resolveWebhookUri("env:IML_BAD_URI", env::get, warns::add));
        assertEquals(6, warns.size());
        assertFalse(warns.get(5).contains("super-secret-uri"));
    }

    @Test
    void deadWebhookTrackingComparesResolvedUrlAcrossEnvReferences() {
        Map<String, String> env = Map.of(
                "HOOK_A", SECRET_WEBHOOK_1,
                "HOOK_B_SAME", SECRET_WEBHOOK_1,
                "HOOK_C_NEW", SECRET_WEBHOOK_2
        );
        List<String> warns = new ArrayList<>();

        // Resolve via env:HOOK_A and mark dead via HTTP 404.
        URI uri1 = DiscordNotifier.resolveWebhookUri("env:HOOK_A", env::get, warns::add);
        assertNotNull(uri1);
        DiscordNotifier.sendWithTransport(
                uri1,
                "{\"content\":\"first\"}",
                (u, body) -> new DiscordNotifier.HttpPostResult(404, null),
                delayMs -> {},
                warns::add);
        assertTrue(DiscordNotifier.isDeadWebhook(SECRET_WEBHOOK_1));
        assertEquals(1, warns.size());

        // Same env var stays suppressed silently.
        assertNull(DiscordNotifier.resolveWebhookUri("env:HOOK_A", env::get, warns::add));
        assertEquals(1, warns.size());

        // Different env var resolving to the SAME URL stays suppressed silently.
        assertNull(DiscordNotifier.resolveWebhookUri("env:HOOK_B_SAME", env::get, warns::add));
        assertEquals(1, warns.size());

        // Plain URL matching the SAME resolved URL also stays suppressed silently.
        assertNull(DiscordNotifier.resolveWebhookUri(SECRET_WEBHOOK_1, env::get, warns::add));
        assertEquals(1, warns.size());

        // Env var resolving to a DIFFERENT URL clears dead state and resumes sending.
        URI uri2 = DiscordNotifier.resolveWebhookUri("env:HOOK_C_NEW", env::get, warns::add);
        assertNotNull(uri2);
        assertEquals(SECRET_WEBHOOK_2, uri2.toString());
        assertFalse(DiscordNotifier.isDeadWebhook(SECRET_WEBHOOK_1));
        assertEquals(1, warns.size());
    }

    @Test
    void singleplayerStartupWarningFiresOnlyWhenWebhookResolvesAndNeverLeaksUrl() {
        Map<String, String> env = Map.of(
                "IML_GOOD", SECRET_WEBHOOK_1,
                "IML_HTTP", "http://discord.com/api/webhooks/111111/super-secret-http"
        );
        List<String> warns = new ArrayList<>();

        // Plain valid HTTPS URL warns once without leaking URL.
        assertTrue(DiscordNotifier.warnIfWebhookConfiguredInSingleplayer(SECRET_WEBHOOK_1, env::get, warns::add));
        assertEquals(1, warns.size());
        assertTrue(warns.get(0).contains("singleplayer"));
        assertTrue(warns.get(0).contains("modpack"));
        assertFalse(warns.get(0).contains(SECRET_WEBHOOK_1));
        assertFalse(warns.get(0).contains("super-secret-token-1"));

        // Valid env: reference also warns once without leaking URL.
        assertTrue(DiscordNotifier.warnIfWebhookConfiguredInSingleplayer("env:IML_GOOD", env::get, warns::add));
        assertEquals(2, warns.size());
        assertFalse(warns.get(1).contains(SECRET_WEBHOOK_1));
        assertFalse(warns.get(1).contains("super-secret-token-1"));

        // Empty, missing env, invalid name, uppercase ENV:, and non-https do NOT trigger singleplayer warning.
        assertFalse(DiscordNotifier.warnIfWebhookConfiguredInSingleplayer("", env::get, warns::add));
        assertFalse(DiscordNotifier.warnIfWebhookConfiguredInSingleplayer("env:IML_MISSING", env::get, warns::add));
        assertFalse(DiscordNotifier.warnIfWebhookConfiguredInSingleplayer("env:bad-name", env::get, warns::add));
        assertFalse(DiscordNotifier.warnIfWebhookConfiguredInSingleplayer("ENV:IML_GOOD", env::get, warns::add));
        assertFalse(DiscordNotifier.warnIfWebhookConfiguredInSingleplayer("env:IML_HTTP", env::get, warns::add));
        assertEquals(2, warns.size());
    }

    @Test
    void shouldPostToDiscordCombinesAllowOptOutConfigAndPlayerPreference() {
        assertEquals(Boolean.TRUE, Config.ALLOW_DISCORD_OPT_OUT.getDefault());

        UUID playerA = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID playerB = UUID.fromString("22222222-2222-2222-2222-222222222222");
        MilestoneData data = new MilestoneData();
        MilestoneData.PlayerData pdA = data.player(playerA);
        pdA.excludeFromDiscord = true;

        // PlayerData overload
        assertTrue(DiscordNotifier.shouldPostToDiscord(true, (MilestoneData.PlayerData) null));
        assertTrue(DiscordNotifier.shouldPostToDiscord(false, (MilestoneData.PlayerData) null));
        assertFalse(DiscordNotifier.shouldPostToDiscord(true, pdA));
        assertTrue(DiscordNotifier.shouldPostToDiscord(false, pdA));
        pdA.excludeFromDiscord = false;
        assertTrue(DiscordNotifier.shouldPostToDiscord(true, pdA));
        pdA.excludeFromDiscord = true;

        // MilestoneData + UUID overload does not create PlayerData for unknown players
        assertTrue(DiscordNotifier.shouldPostToDiscord(true, null, playerA));
        assertTrue(DiscordNotifier.shouldPostToDiscord(true, data, null));
        assertTrue(DiscordNotifier.shouldPostToDiscord(true, data, playerB));
        assertNull(data.playerOrNull(playerB), "shouldPostToDiscord must not create PlayerData");
        assertFalse(DiscordNotifier.shouldPostToDiscord(true, data, playerA));
        assertTrue(DiscordNotifier.shouldPostToDiscord(false, data, playerA));
    }

    @Test
    void testPayloadBuildsPlainTextAndEmbedForConfiguredLanguage() {
        Instant sendTime = Instant.ofEpochMilli(EPOCH_2023_11_14);

        // Plain text en_us
        JsonObject plainEn = JsonParser.parseString(
                DiscordNotifier.buildTestPayloadJson(false, "en_us", sendTime)).getAsJsonObject();
        assertFalse(plainEn.has("embeds"));
        assertEquals("Test message from Item Milestone Log. Your Discord webhook is working.",
                plainEn.get("content").getAsString());
        assertEquals(0, plainEn.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());

        // Plain text ja_jp
        JsonObject plainJa = JsonParser.parseString(
                DiscordNotifier.buildTestPayloadJson(false, "ja_jp", sendTime)).getAsJsonObject();
        assertFalse(plainJa.has("embeds"));
        assertEquals("Item Milestone Log からのテスト送信です。Discord Webhook は正常に動作しています。",
                plainJa.get("content").getAsString());
        assertEquals(0, plainJa.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());

        // Embed en_us
        JsonObject embedEnBody = JsonParser.parseString(
                DiscordNotifier.buildTestPayloadJson(true, "en_us", sendTime)).getAsJsonObject();
        assertFalse(embedEnBody.has("content"));
        assertEquals(0, embedEnBody.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());
        JsonArray enEmbeds = embedEnBody.getAsJsonArray("embeds");
        assertEquals(1, enEmbeds.size());
        JsonObject enEmbed = enEmbeds.get(0).getAsJsonObject();
        assertEquals("Webhook Test", enEmbed.get("title").getAsString());
        assertEquals("Test message from Item Milestone Log. Your Discord webhook is working.",
                enEmbed.get("description").getAsString());
        assertEquals(0x3498DB, enEmbed.get("color").getAsInt());
        assertEquals("2023-11-14T22:13:20Z", enEmbed.get("timestamp").getAsString());

        // Embed ja_jp
        JsonObject jaEmbed = JsonParser.parseString(
                DiscordNotifier.buildTestPayloadJson(true, "ja_jp", sendTime)).getAsJsonObject()
                .getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals("Webhook テスト", jaEmbed.get("title").getAsString());
        assertEquals("Item Milestone Log からのテスト送信です。Discord Webhook は正常に動作しています。",
                jaEmbed.get("description").getAsString());
        assertEquals(0x3498DB, jaEmbed.get("color").getAsInt());
        assertEquals("2023-11-14T22:13:20Z", jaEmbed.get("timestamp").getAsString());

        // Unbundled language falls back to en_us
        JsonObject fallbackEmbed = JsonParser.parseString(
                DiscordNotifier.buildTestPayloadJson(true, "fr_fr", sendTime)).getAsJsonObject()
                .getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals("Webhook Test", fallbackEmbed.get("title").getAsString());
        assertEquals("Test message from Item Milestone Log. Your Discord webhook is working.",
                fallbackEmbed.get("description").getAsString());
    }

    @Test
    void testSendBypassesDeadWebhookStopAndClearsStopOnlyOnSuccess() {
        URI uri = URI.create(SECRET_WEBHOOK_1);
        List<String> warns = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();

        // Mark URL dead via a normal 404 send.
        DiscordNotifier.sendWithTransport(
                uri,
                "{\"content\":\"normal\"}",
                (u, body) -> new DiscordNotifier.HttpPostResult(404, null),
                delayMs -> {},
                warns::add);
        assertTrue(DiscordNotifier.isDeadWebhook(SECRET_WEBHOOK_1));
        assertNull(DiscordNotifier.resolveWebhookUri(SECRET_WEBHOOK_1, warns::add));

        // Failing test sends (500, 404, 429, network exception) bypass the dead check to probe,
        // but keep the webhook marked dead.
        DiscordTestResult err500 = DiscordNotifier.sendTestWithTransport(
                uri, "{\"content\":\"test\"}",
                (u, body) -> {
                    attempts.incrementAndGet();
                    return new DiscordNotifier.HttpPostResult(500, null);
                },
                delayMs -> {}, warns::add);
        assertEquals(new DiscordTestResult(DiscordTestResult.Kind.HTTP_ERROR, "500"), err500);
        assertEquals(1, attempts.get());
        assertTrue(DiscordNotifier.isDeadWebhook(SECRET_WEBHOOK_1));

        DiscordTestResult err404 = DiscordNotifier.sendTestWithTransport(
                uri, "{\"content\":\"test\"}",
                (u, body) -> {
                    attempts.incrementAndGet();
                    return new DiscordNotifier.HttpPostResult(404, null);
                },
                delayMs -> {}, warns::add);
        assertEquals(new DiscordTestResult(DiscordTestResult.Kind.INVALID_WEBHOOK, "404"), err404);
        assertEquals(2, attempts.get());
        assertTrue(DiscordNotifier.isDeadWebhook(SECRET_WEBHOOK_1));

        DiscordTestResult err429 = DiscordNotifier.sendTestWithTransport(
                uri, "{\"content\":\"test\"}",
                (u, body) -> {
                    attempts.incrementAndGet();
                    return new DiscordNotifier.HttpPostResult(429, null);
                },
                delayMs -> {}, warns::add);
        assertEquals(new DiscordTestResult(DiscordTestResult.Kind.RATE_LIMITED, null), err429);
        assertEquals(3, attempts.get());
        assertTrue(DiscordNotifier.isDeadWebhook(SECRET_WEBHOOK_1));

        DiscordTestResult netErr = DiscordNotifier.sendTestWithTransport(
                uri, "{\"content\":\"test\"}",
                (u, body) -> {
                    attempts.incrementAndGet();
                    throw new IOException("unreachable: " + SECRET_WEBHOOK_1);
                },
                delayMs -> {}, warns::add);
        assertEquals(new DiscordTestResult(DiscordTestResult.Kind.NETWORK_ERROR, "IOException"), netErr);
        assertEquals(4, attempts.get());
        assertTrue(DiscordNotifier.isDeadWebhook(SECRET_WEBHOOK_1));
        for (String w : warns) {
            assertFalse(w.contains(SECRET_WEBHOOK_1));
            assertFalse(w.contains("super-secret-token-1"));
        }

        // Successful test send (HTTP 204) bypasses the stop and clears it so normal sends resume.
        DiscordTestResult ok = DiscordNotifier.sendTestWithTransport(
                uri, "{\"content\":\"test\"}",
                (u, body) -> {
                    attempts.incrementAndGet();
                    return new DiscordNotifier.HttpPostResult(204, null);
                },
                delayMs -> {}, warns::add);
        assertEquals(new DiscordTestResult(DiscordTestResult.Kind.SUCCESS, "204"), ok);
        assertEquals(5, attempts.get());
        assertFalse(DiscordNotifier.isDeadWebhook(SECRET_WEBHOOK_1));
        assertNotNull(DiscordNotifier.resolveWebhookUri(SECRET_WEBHOOK_1, warns::add));

        // Mark dead again and verify 429 + valid Retry-After followed by 200 retry also bypasses
        // both dead checks and clears dead state on retry success.
        DiscordNotifier.sendWithTransport(
                uri, "{\"content\":\"normal\"}",
                (u, body) -> new DiscordNotifier.HttpPostResult(401, null),
                delayMs -> {}, warns::add);
        assertTrue(DiscordNotifier.isDeadWebhook(SECRET_WEBHOOK_1));
        List<Long> sleeps = new ArrayList<>();
        Queue<DiscordNotifier.HttpPostResult> responses = new ArrayDeque<>(List.of(
                new DiscordNotifier.HttpPostResult(429, "0.5"),
                new DiscordNotifier.HttpPostResult(200, null)
        ));
        DiscordTestResult retryOk = DiscordNotifier.sendTestWithTransport(
                uri, "{\"content\":\"test\"}",
                (u, body) -> responses.remove(),
                sleeps::add, warns::add);
        assertEquals(new DiscordTestResult(DiscordTestResult.Kind.SUCCESS, "200"), retryOk);
        assertEquals(List.of(500L), sleeps);
        assertFalse(DiscordNotifier.isDeadWebhook(SECRET_WEBHOOK_1));
    }

    @Test
    void testSendEnforcesSingleInFlightConcurrencyAndAlwaysReleasesInFinally() throws Exception {
        Instant sendTime = Instant.ofEpochMilli(EPOCH_2023_11_14);
        Map<String, String> env = Map.of(
                "IML_GOOD", SECRET_WEBHOOK_1,
                "IML_EMPTY", "   "
        );
        List<String> warns = new ArrayList<>();

        // Pre-check failures return synchronously and never acquire TEST_IN_PROGRESS.
        Executor neverRun = r -> {
            throw new AssertionError("executor must not be called on pre-check failure");
        };
        assertEquals(DiscordTestResult.Kind.NOT_CONFIGURED,
                DiscordNotifier.sendTestMessage("", env::get, false, "en_us", sendTime,
                        neverRun, (u, b) -> new DiscordNotifier.HttpPostResult(204, null),
                        d -> {}, warns::add, r -> {}).kind());
        assertEquals(new DiscordTestResult(DiscordTestResult.Kind.ENV_MISSING, "IML_MISSING"),
                DiscordNotifier.sendTestMessage("env:IML_MISSING", env::get, false, "en_us", sendTime,
                        neverRun, (u, b) -> new DiscordNotifier.HttpPostResult(204, null),
                        d -> {}, warns::add, r -> {}));
        assertEquals(new DiscordTestResult(DiscordTestResult.Kind.ENV_MISSING, "IML_EMPTY"),
                DiscordNotifier.sendTestMessage("env:IML_EMPTY", env::get, false, "en_us", sendTime,
                        neverRun, (u, b) -> new DiscordNotifier.HttpPostResult(204, null),
                        d -> {}, warns::add, r -> {}));
        assertEquals(DiscordTestResult.Kind.INVALID_URL,
                DiscordNotifier.sendTestMessage("http://discord.com/api/webhooks/1/secret", env::get, false, "en_us", sendTime,
                        neverRun, (u, b) -> new DiscordNotifier.HttpPostResult(204, null),
                        d -> {}, warns::add, r -> {}).kind());
        assertFalse(DiscordNotifier.isTestInProgress());

        // First test blocks inside transport; concurrent second test immediately returns IN_PROGRESS.
        CountDownLatch enteredTransport = new CountDownLatch(1);
        CountDownLatch allowFinish = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<DiscordTestResult> delivered = new AtomicReference<>();

        Executor bgThread = r -> {
            Thread t = new Thread(r, "test-discord-worker");
            t.setDaemon(true);
            t.start();
        };
        DiscordTestResult firstImmediate = DiscordNotifier.sendTestMessage(
                "env:IML_GOOD", env::get, true, "en_us", sendTime,
                bgThread,
                (u, body) -> {
                    enteredTransport.countDown();
                    assertTrue(allowFinish.await(5, TimeUnit.SECONDS));
                    return new DiscordNotifier.HttpPostResult(204, null);
                },
                d -> {},
                warns::add,
                res -> {
                    delivered.set(res);
                    completed.countDown();
                });
        assertNull(firstImmediate);
        assertTrue(enteredTransport.await(5, TimeUnit.SECONDS));
        assertTrue(DiscordNotifier.isTestInProgress());

        DiscordTestResult concurrentImmediate = DiscordNotifier.sendTestMessage(
                "env:IML_GOOD", env::get, false, "en_us", sendTime,
                neverRun,
                (u, body) -> new DiscordNotifier.HttpPostResult(204, null),
                d -> {},
                warns::add,
                r -> {
                    throw new AssertionError("callback must not run for IN_PROGRESS");
                });
        assertEquals(DiscordTestResult.inProgress(), concurrentImmediate);

        allowFinish.countDown();
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertEquals(new DiscordTestResult(DiscordTestResult.Kind.SUCCESS, "204"), delivered.get());

        // Wait briefly for the worker thread's finally block to clear TEST_IN_PROGRESS.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (DiscordNotifier.isTestInProgress() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertFalse(DiscordNotifier.isTestInProgress());

        // Synchronous executor failure releases TEST_IN_PROGRESS immediately.
        assertThrows(RejectedExecutionException.class, () -> DiscordNotifier.sendTestMessage(
                SECRET_WEBHOOK_1, env::get, false, "en_us", sendTime,
                r -> {
                    throw new RejectedExecutionException("shutdown");
                },
                (u, body) -> new DiscordNotifier.HttpPostResult(204, null),
                d -> {},
                warns::add,
                r -> {}));
        assertFalse(DiscordNotifier.isTestInProgress());

        // Even an unchecked callback failure inside the worker releases TEST_IN_PROGRESS in finally.
        assertThrows(IllegalStateException.class, () -> DiscordNotifier.sendTestMessage(
                SECRET_WEBHOOK_1, env::get, false, "en_us", sendTime,
                Runnable::run,
                (u, body) -> new DiscordNotifier.HttpPostResult(204, null),
                d -> {},
                warns::add,
                r -> {
                    throw new IllegalStateException("boom");
                }));
        assertFalse(DiscordNotifier.isTestInProgress());

        // Subsequent test succeeds once released.
        AtomicReference<DiscordTestResult> afterRelease = new AtomicReference<>();
        assertNull(DiscordNotifier.sendTestMessage(
                SECRET_WEBHOOK_1, env::get, false, "en_us", sendTime,
                Runnable::run,
                (u, body) -> new DiscordNotifier.HttpPostResult(200, null),
                d -> {},
                warns::add,
                afterRelease::set));
        assertEquals(new DiscordTestResult(DiscordTestResult.Kind.SUCCESS, "200"), afterRelease.get());
        assertFalse(DiscordNotifier.isTestInProgress());
    }

    @Test
    void renderedDiscordTestResultStringsMatchSpecAndNeverContainWebhookUrl() {
        List<DiscordTestResult> results = List.of(
                DiscordTestResult.classifyPreCheck(DiscordWebhookUrl.resolve("", k -> null)),
                DiscordTestResult.classifyPreCheck(DiscordWebhookUrl.resolve("env:IML_MISSING_HOOK", k -> null)),
                DiscordTestResult.classifyPreCheck(DiscordWebhookUrl.resolve("env:" + SECRET_WEBHOOK_1, k -> null)),
                DiscordTestResult.classifyPreCheck(DiscordWebhookUrl.resolve("http://discord.com/api/webhooks/1/super-secret-token-1", k -> null)),
                DiscordTestResult.inProgress(),
                DiscordTestResult.classifyHttpStatus(204),
                DiscordTestResult.classifyHttpStatus(404),
                DiscordTestResult.classifyHttpStatus(401),
                DiscordTestResult.classifyHttpStatus(429),
                DiscordTestResult.classifyHttpStatus(500),
                DiscordTestResult.classifyException(new IOException("POST failed for " + SECRET_WEBHOOK_1))
        );

        for (DiscordTestResult res : results) {
            String en = res.hasDetail()
                    ? ServerLang.formatForLang("en_us", res.messageKey(), res.detail())
                    : ServerLang.formatForLang("en_us", res.messageKey());
            String ja = res.hasDetail()
                    ? ServerLang.formatForLang("ja_jp", res.messageKey(), res.detail())
                    : ServerLang.formatForLang("ja_jp", res.messageKey());
            assertFalse(en.contains(SECRET_WEBHOOK_1), "en leaked URL: " + en);
            assertFalse(en.contains("super-secret-token-1"), "en leaked token: " + en);
            assertFalse(ja.contains(SECRET_WEBHOOK_1), "ja leaked URL: " + ja);
            assertFalse(ja.contains("super-secret-token-1"), "ja leaked token: " + ja);
            assertFalse(en.startsWith("message.itemmilestonelog."), "missing en_us key: " + en);
            assertFalse(ja.startsWith("message.itemmilestonelog."), "missing ja_jp key: " + ja);
        }

        // Spot-check exact verbatim texts from F49 spec
        assertEquals("Sending a test message to Discord...",
                ServerLang.formatForLang("en_us", "message.itemmilestonelog.discordtest_sending"));
        assertEquals("Discord へテスト送信しています…",
                ServerLang.formatForLang("ja_jp", "message.itemmilestonelog.discordtest_sending"));
        assertEquals("A Discord test message is already being sent. Please wait.",
                ServerLang.formatForLang("en_us", "message.itemmilestonelog.discordtest_in_progress"));
        assertEquals("Discord へのテスト送信がすでに実行中です。完了まで待ってください。",
                ServerLang.formatForLang("ja_jp", "message.itemmilestonelog.discordtest_in_progress"));
        assertEquals("Discord webhook URL is not configured.",
                ServerLang.formatForLang("en_us", "message.itemmilestonelog.discordtest_not_configured"));
        assertEquals("Discord の Webhook URL が設定されていません。",
                ServerLang.formatForLang("ja_jp", "message.itemmilestonelog.discordtest_not_configured"));
        assertEquals("Environment variable 'IML_MISSING_HOOK' is not set or is empty.",
                ServerLang.formatForLang("en_us", "message.itemmilestonelog.discordtest_env_missing", "IML_MISSING_HOOK"));
        assertEquals("環境変数「IML_MISSING_HOOK」が設定されていないか、空です。",
                ServerLang.formatForLang("ja_jp", "message.itemmilestonelog.discordtest_env_missing", "IML_MISSING_HOOK"));
        assertEquals("Discord webhook URL is invalid (it must start with https://).",
                ServerLang.formatForLang("en_us", "message.itemmilestonelog.discordtest_invalid_url"));
        assertEquals("Discord の Webhook URL が不正です（https:// で始まる必要があります）。",
                ServerLang.formatForLang("ja_jp", "message.itemmilestonelog.discordtest_invalid_url"));
        assertEquals("Discord test message sent (HTTP 204).",
                ServerLang.formatForLang("en_us", "message.itemmilestonelog.discordtest_success", "204"));
        assertEquals("Discord へテスト送信しました（HTTP 204）。",
                ServerLang.formatForLang("ja_jp", "message.itemmilestonelog.discordtest_success", "204"));
        assertEquals("Discord rejected the webhook (HTTP 404). The webhook may have been deleted or the URL is wrong.",
                ServerLang.formatForLang("en_us", "message.itemmilestonelog.discordtest_invalid_webhook", "404"));
        assertEquals("Discord に拒否されました（HTTP 404）。Webhook が削除されたか、URL が誤っている可能性があります。",
                ServerLang.formatForLang("ja_jp", "message.itemmilestonelog.discordtest_invalid_webhook", "404"));
        assertEquals("Discord rate-limited the request (HTTP 429). Try again shortly.",
                ServerLang.formatForLang("en_us", "message.itemmilestonelog.discordtest_rate_limited"));
        assertEquals("Discord のレート制限に達しました（HTTP 429）。少し待ってから試してください。",
                ServerLang.formatForLang("ja_jp", "message.itemmilestonelog.discordtest_rate_limited"));
        assertEquals("Discord returned HTTP 500.",
                ServerLang.formatForLang("en_us", "message.itemmilestonelog.discordtest_http_error", "500"));
        assertEquals("Discord が HTTP 500 を返しました。",
                ServerLang.formatForLang("ja_jp", "message.itemmilestonelog.discordtest_http_error", "500"));
        assertEquals("Failed to reach Discord (IOException).",
                ServerLang.formatForLang("en_us", "message.itemmilestonelog.discordtest_network_error", "IOException"));
        assertEquals("Discord への通信に失敗しました（IOException）。",
                ServerLang.formatForLang("ja_jp", "message.itemmilestonelog.discordtest_network_error", "IOException"));
    }

    @Test
    void presetCompletionPlainTextUsesLanguageBuiltinAndCustomNamesEscapesMarkdownAndCapsAt2000() {
        Presets.PresetDefinition vanillaBuiltin = Presets.get("vanilla_endgame");
        Presets.PresetDefinition atmBuiltin = Presets.get("atm_star");
        assertNotNull(vanillaBuiltin);
        assertNotNull(atmBuiltin);

        // Builtin preset in en_us
        JsonObject enBuiltin = JsonParser.parseString(DiscordNotifier.buildPresetCompletionPayloadJson(
                "Alice", vanillaBuiltin, EPOCH_2023_11_14, false, "en_us")).getAsJsonObject();
        assertFalse(enBuiltin.has("embeds"));
        assertEquals("★ Alice completed all targets of Preset: Vanilla Endgame!",
                enBuiltin.get("content").getAsString());
        assertEquals(0, enBuiltin.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());

        // Builtin preset in ja_jp resolves gui.itemmilestonelog.preset.<id> from ja_jp table
        JsonObject jaBuiltin = JsonParser.parseString(DiscordNotifier.buildPresetCompletionPayloadJson(
                "Alice", atmBuiltin, EPOCH_2023_11_14, false, "ja_jp")).getAsJsonObject();
        assertEquals("★ Alice が プリセット: ATM Star の目標をすべて達成しました！",
                jaBuiltin.get("content").getAsString());

        // Unbundled language falls back to en_us for both template and builtin preset display name
        JsonObject fallbackBuiltin = JsonParser.parseString(DiscordNotifier.buildPresetCompletionPayloadJson(
                "Alice", vanillaBuiltin, EPOCH_2023_11_14, false, "fr_fr")).getAsJsonObject();
        assertEquals("★ Alice completed all targets of Preset: Vanilla Endgame!",
                fallbackBuiltin.get("content").getAsString());

        // Custom preset with JSON display name containing Markdown chars and literal %s
        Presets.PresetDefinition customNamed = new Presets.PresetDefinition(
                "my_pack", "My _Super_ [Pack] %s", List.of(TargetKey.of("minecraft:diamond")), false);
        JsonObject customEn = JsonParser.parseString(DiscordNotifier.buildPresetCompletionPayloadJson(
                "_Steve_ [OP]", customNamed, EPOCH_2023_11_14, false, "en_us")).getAsJsonObject();
        assertEquals("★ \\_Steve\\_ \\[OP\\] completed all targets of My \\_Super\\_ \\[Pack\\] %s!",
                customEn.get("content").getAsString());

        JsonObject customJa = JsonParser.parseString(DiscordNotifier.buildPresetCompletionPayloadJson(
                "_Steve_ [OP]", customNamed, EPOCH_2023_11_14, false, "ja_jp")).getAsJsonObject();
        assertEquals("★ \\_Steve\\_ \\[OP\\] が My \\_Super\\_ \\[Pack\\] %s の目標をすべて達成しました！",
                customJa.get("content").getAsString());

        // Custom preset with missing/blank displayName falls back to ID (Markdown-escaped)
        Presets.PresetDefinition customFallbackId = new Presets.PresetDefinition(
                "my_pack", "", List.of(TargetKey.of("minecraft:diamond")), false);
        JsonObject customIdJson = JsonParser.parseString(DiscordNotifier.buildPresetCompletionPayloadJson(
                "Alice", customFallbackId, EPOCH_2023_11_14, false, "en_us")).getAsJsonObject();
        assertEquals("★ Alice completed all targets of my\\_pack!",
                customIdJson.get("content").getAsString());

        // Plain content cap (2000 UTF-16 code units, surrogate-pair-safe)
        String longEmojiName = "\uD83D\uDC8E".repeat(1200);
        String cappedContent = JsonParser.parseString(DiscordNotifier.buildPresetCompletionPayloadJson(
                longEmojiName, vanillaBuiltin, EPOCH_2023_11_14, false, "en_us"))
                .getAsJsonObject().get("content").getAsString();
        assertTrue(cappedContent.length() <= DiscordNotifier.PLAIN_CONTENT_MAX_CHARS);
        assertFalse(Character.isHighSurrogate(cappedContent.charAt(cappedContent.length() - 1)));

        // Null preset returns empty string
        assertEquals("", DiscordNotifier.buildPresetCompletionPayloadJson(
                "Alice", null, EPOCH_2023_11_14, false, "en_us"));
    }

    @Test
    void presetCompletionEmbedIncludesTitleDescriptionGreenColorFooterTimestampEscapingAndCaps() {
        Presets.PresetDefinition vanillaBuiltin = Presets.get("vanilla_endgame");
        assertNotNull(vanillaBuiltin);

        // Builtin preset in en_us
        JsonObject enBody = JsonParser.parseString(DiscordNotifier.buildPresetCompletionPayloadJson(
                "Alice", vanillaBuiltin, EPOCH_2023_11_14, true, "en_us")).getAsJsonObject();
        assertFalse(enBody.has("content"));
        assertEquals(0, enBody.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());
        JsonArray enEmbeds = enBody.getAsJsonArray("embeds");
        assertEquals(1, enEmbeds.size());

        JsonObject enEmbed = enEmbeds.get(0).getAsJsonObject();
        assertEquals("Preset: Vanilla Endgame", enEmbed.get("title").getAsString());
        assertEquals("★ Alice completed all targets of Preset: Vanilla Endgame!",
                enEmbed.get("description").getAsString());
        assertEquals(0x2ECC71, enEmbed.get("color").getAsInt());
        assertEquals("Alice", enEmbed.getAsJsonObject("footer").get("text").getAsString());
        assertEquals("2023-11-14T22:13:20Z", enEmbed.get("timestamp").getAsString());
        assertFalse(enEmbed.has("fields"));

        // Builtin preset in ja_jp
        JsonObject jaEmbed = JsonParser.parseString(DiscordNotifier.buildPresetCompletionPayloadJson(
                "Alice", vanillaBuiltin, EPOCH_2023_11_14, true, "ja_jp"))
                .getAsJsonObject().getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals("プリセット: Vanilla Endgame", jaEmbed.get("title").getAsString());
        assertEquals("★ Alice が プリセット: Vanilla Endgame の目標をすべて達成しました！",
                jaEmbed.get("description").getAsString());
        assertEquals(0x2ECC71, jaEmbed.get("color").getAsInt());

        // Custom preset with Markdown chars: title & footer stay literal, description escapes Markdown
        Presets.PresetDefinition customNamed = new Presets.PresetDefinition(
                "my_pack", "My _Super_ *Pack*", List.of(TargetKey.of("minecraft:diamond")), false);
        JsonObject customEmbed = JsonParser.parseString(DiscordNotifier.buildPresetCompletionPayloadJson(
                "_Steve_", customNamed, EPOCH_2023_11_14, true, "en_us"))
                .getAsJsonObject().getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals("My _Super_ *Pack*", customEmbed.get("title").getAsString());
        assertEquals("★ \\_Steve\\_ completed all targets of My \\_Super\\_ \\*Pack\\*!",
                customEmbed.get("description").getAsString());
        assertEquals(0x2ECC71, customEmbed.get("color").getAsInt());
        assertEquals("_Steve_", customEmbed.getAsJsonObject("footer").get("text").getAsString());
        assertEquals("2023-11-14T22:13:20Z", customEmbed.get("timestamp").getAsString());

        // Custom preset with null displayName falls back to ID; unknown epoch omits timestamp; blank player omits footer
        Presets.PresetDefinition customFallbackId = new Presets.PresetDefinition(
                "my_pack", null, List.of(TargetKey.of("minecraft:diamond")), false);
        JsonObject minimalEmbed = JsonParser.parseString(DiscordNotifier.buildPresetCompletionPayloadJson(
                "", customFallbackId, MilestoneRecord.UNKNOWN_EPOCH, true, "en_us"))
                .getAsJsonObject().getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals("my_pack", minimalEmbed.get("title").getAsString());
        assertEquals("★  completed all targets of my\\_pack!", minimalEmbed.get("description").getAsString());
        assertFalse(minimalEmbed.has("footer"));
        assertFalse(minimalEmbed.has("timestamp"));

        // Title (256), description (4096), and footer (2048) caps never split surrogate pairs
        String longTitle = "T".repeat(255) + "\uD83D\uDC8E" + "tail";
        String longName = "P".repeat(4095) + "\uD83D\uDC8E";
        Presets.PresetDefinition longPreset = new Presets.PresetDefinition(
                "long_pack", longTitle, List.of(TargetKey.of("minecraft:diamond")), false);
        JsonObject cappedEmbed = JsonParser.parseString(DiscordNotifier.buildPresetCompletionPayloadJson(
                longName, longPreset, EPOCH_2023_11_14, true, "en_us"))
                .getAsJsonObject().getAsJsonArray("embeds").get(0).getAsJsonObject();
        String title = cappedEmbed.get("title").getAsString();
        assertEquals(255, title.length());
        assertFalse(Character.isHighSurrogate(title.charAt(title.length() - 1)));
        String description = cappedEmbed.get("description").getAsString();
        assertTrue(description.length() <= DiscordNotifier.EMBED_DESCRIPTION_MAX_CHARS);
        assertFalse(Character.isHighSurrogate(description.charAt(description.length() - 1)));
        String footerText = cappedEmbed.getAsJsonObject("footer").get("text").getAsString();
        assertEquals(DiscordNotifier.EMBED_FOOTER_MAX_CHARS, footerText.length());
        assertFalse(Character.isHighSurrogate(footerText.charAt(footerText.length() - 1)));
    }

    @Test
    void presetCompletionDispatchHonorsConfigDefaultWebhookAndF45OptOutDecision() {
        assertEquals(Boolean.FALSE, Config.DISCORD_NOTIFY_PRESET_COMPLETION.getDefault());

        Presets.PresetDefinition vanillaBuiltin = Presets.get("vanilla_endgame");
        assertNotNull(vanillaBuiltin);
        Map<String, String> env = Map.of("IML_HOOK", SECRET_WEBHOOK_1);
        List<String> warns = new ArrayList<>();
        List<URI> sentUris = new ArrayList<>();
        List<String> sentPayloads = new ArrayList<>();

        MilestoneData data = new MilestoneData();
        MilestoneData.PlayerData normalPd = data.player(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        MilestoneData.PlayerData optedOutPd = data.player(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        optedOutPd.excludeFromDiscord = true;

        // 1. Disabled via discordNotifyPresetCompletion = false -> suppressed
        assertFalse(DiscordNotifier.notifyPresetCompletion(
                false, true, normalPd, SECRET_WEBHOOK_1, env::get,
                "Alice", vanillaBuiltin, EPOCH_2023_11_14, false, "en_us",
                warns::add, (u, p) -> {
                    sentUris.add(u);
                    sentPayloads.add(p);
                }));
        assertTrue(sentPayloads.isEmpty());
        assertTrue(warns.isEmpty());

        // 2. Enabled, but webhook not configured -> suppressed
        assertFalse(DiscordNotifier.notifyPresetCompletion(
                true, true, normalPd, "", env::get,
                "Alice", vanillaBuiltin, EPOCH_2023_11_14, false, "en_us",
                warns::add, (u, p) -> {
                    sentUris.add(u);
                    sentPayloads.add(p);
                }));
        assertTrue(sentPayloads.isEmpty());

        // 3. F45 opt-out: opted-out player with allowDiscordOptOut = true -> suppressed before webhook resolution
        assertFalse(DiscordNotifier.notifyPresetCompletion(
                true, true, optedOutPd, "http://invalid-should-not-warn", env::get,
                "Bob", vanillaBuiltin, EPOCH_2023_11_14, true, "en_us",
                warns::add, (u, p) -> {
                    sentUris.add(u);
                    sentPayloads.add(p);
                }));
        assertTrue(sentPayloads.isEmpty());
        assertTrue(warns.isEmpty(), "opted-out player must be suppressed before resolving webhook URL");

        // 4. F45 opt-out ignored when allowDiscordOptOut = false -> posts ONE embed message via env: webhook
        assertTrue(DiscordNotifier.notifyPresetCompletion(
                true, false, optedOutPd, "env:IML_HOOK", env::get,
                "Bob", vanillaBuiltin, EPOCH_2023_11_14, true, "ja_jp",
                warns::add, (u, p) -> {
                    sentUris.add(u);
                    sentPayloads.add(p);
                }));
        assertEquals(1, sentUris.size());
        assertEquals(URI.create(SECRET_WEBHOOK_1), sentUris.get(0));
        JsonObject embed = JsonParser.parseString(sentPayloads.get(0)).getAsJsonObject()
                .getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals("プリセット: Vanilla Endgame", embed.get("title").getAsString());
        assertEquals(0x2ECC71, embed.get("color").getAsInt());
        assertEquals("Bob", embed.getAsJsonObject("footer").get("text").getAsString());

        // 5. Normal player with plain text -> posts ONE plain-text message
        assertTrue(DiscordNotifier.notifyPresetCompletion(
                true, true, normalPd, SECRET_WEBHOOK_1, env::get,
                "Alice", vanillaBuiltin, EPOCH_2023_11_14, false, "en_us",
                warns::add, (u, p) -> {
                    sentUris.add(u);
                    sentPayloads.add(p);
                }));
        assertEquals(2, sentPayloads.size());
        assertEquals("★ Alice completed all targets of Preset: Vanilla Endgame!",
                JsonParser.parseString(sentPayloads.get(1)).getAsJsonObject().get("content").getAsString());

        // 6. Dead webhook (F35) suppresses preset completion posts too
        DiscordNotifier.sendWithTransport(
                URI.create(SECRET_WEBHOOK_1),
                "{\"content\":\"probe\"}",
                (u, body) -> new DiscordNotifier.HttpPostResult(404, null),
                d -> {},
                warns::add);
        assertFalse(DiscordNotifier.notifyPresetCompletion(
                true, true, normalPd, SECRET_WEBHOOK_1, env::get,
                "Alice", vanillaBuiltin, EPOCH_2023_11_14, false, "en_us",
                warns::add, (u, p) -> {
                    sentUris.add(u);
                    sentPayloads.add(p);
                }));
        assertEquals(2, sentPayloads.size());
    }

    private static void assertField(JsonObject field, String expectedName, String expectedValue) {
        assertEquals(expectedName, field.get("name").getAsString());
        assertEquals(expectedValue, field.get("value").getAsString());
        assertTrue(field.get("inline").getAsBoolean());
    }
}
