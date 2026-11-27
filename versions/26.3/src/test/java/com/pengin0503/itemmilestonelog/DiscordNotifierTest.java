package com.pengin0503.itemmilestonelog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
        DiscordNotifier.resetDigestThrottler();
        // Clear interrupt flag in case an interruption test left it set.
        Thread.interrupted();
        // SF-10: never let a test executor/client leak into the next test.
        DiscordNotifier.shutdown(0L);
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
        try {
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

            allowFinish.countDown(); // release before waiting; finally below covers early failures
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertEquals(new DiscordTestResult(DiscordTestResult.Kind.SUCCESS, "204"), delivered.get());

            // Wait briefly for the worker thread's finally block to clear TEST_IN_PROGRESS.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (DiscordNotifier.isTestInProgress() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertFalse(DiscordNotifier.isTestInProgress());
        } finally {
            // #123: an assertion failure above must never leave the worker waiting 5 s.
            allowFinish.countDown();
        }

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

        // F54: an object-form name resolves in the notification language (discordLanguage)
        Presets.PresetDefinition localizedNamed = new Presets.PresetDefinition(
                "my_pack",
                PresetName.ofLocalized(Map.of("en_us", "My _Super_ [Pack]", "ja_jp", "私の_スーパー_[パック]")),
                List.of(TargetKey.of("minecraft:diamond")),
                Presets.PresetKind.FILE);
        JsonObject localizedJa = JsonParser.parseString(DiscordNotifier.buildPresetCompletionPayloadJson(
                "_Steve_", localizedNamed, EPOCH_2023_11_14, false, "ja_jp")).getAsJsonObject();
        assertEquals("★ \\_Steve\\_ が 私の\\_スーパー\\_\\[パック\\] の目標をすべて達成しました！",
                localizedJa.get("content").getAsString());
        // Unbundled languages fall back to the object's en_us entry
        JsonObject localizedFr = JsonParser.parseString(DiscordNotifier.buildPresetCompletionPayloadJson(
                "_Steve_", localizedNamed, EPOCH_2023_11_14, false, "fr_fr")).getAsJsonObject();
        assertEquals("★ \\_Steve\\_ completed all targets of My \\_Super\\_ \\[Pack\\]!",
                localizedFr.get("content").getAsString());

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

        // F54: an object-form name resolves the embed title in the notification language
        Presets.PresetDefinition localizedNamed = new Presets.PresetDefinition(
                "my_pack",
                PresetName.ofLocalized(Map.of("en_us", "My _Super_ *Pack*", "ja_jp", "私の_スーパー_*パック*")),
                List.of(TargetKey.of("minecraft:diamond")),
                Presets.PresetKind.FILE);
        JsonObject localizedJaEmbed = JsonParser.parseString(DiscordNotifier.buildPresetCompletionPayloadJson(
                "Alice", localizedNamed, EPOCH_2023_11_14, true, "ja_jp"))
                .getAsJsonObject().getAsJsonArray("embeds").get(0).getAsJsonObject();
        // Titles stay literal (no Markdown escaping), resolved per language
        assertEquals("私の_スーパー_*パック*", localizedJaEmbed.get("title").getAsString());
        assertEquals("★ Alice が 私の\\_スーパー\\_\\*パック\\* の目標をすべて達成しました！",
                localizedJaEmbed.get("description").getAsString());
        assertEquals(0x2ECC71, localizedJaEmbed.get("color").getAsInt());

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

    @Test
    void digestConfigDefaultsAndLifecycleHonorsFirstRunWindowEmptyPeriodScopeOptOutAndF47Names() {
        assertEquals(Boolean.FALSE, Config.DISCORD_DIGEST_ENABLED.getDefault());
        assertEquals(7, Config.DISCORD_DIGEST_INTERVAL_DAYS.getDefault());

        UUID aliceId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID bobId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID charlieId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        long t0 = EPOCH_2023_11_14;
        long dayMs = DiscordDigestLogic.MILLIS_PER_DAY;
        Map<String, String> env = Map.of("IML_HOOK", SECRET_WEBHOOK_1);
        List<String> warns = new ArrayList<>();
        List<URI> sentUris = new ArrayList<>();
        List<String> sentPayloads = new ArrayList<>();

        MilestoneData data = new MilestoneData();
        MilestoneData.PlayerData pdAlice = data.player(aliceId);
        pdAlice.lastKnownName = "_Alice_";
        MilestoneRecord oldRec = new MilestoneRecord(72_000, 1L, t0 - dayMs);
        pdAlice.records.put(TargetKey.of("minecraft:coal"), oldRec);

        // 1. Disabled or empty webhook does nothing and does NOT initialize lastDigestEpoch.
        assertFalse(DiscordNotifier.checkAndSendDigest(
                data, false, 7, SECRET_WEBHOOK_1, env::get, "all", true, false, "en_us", ZoneOffset.UTC,
                t0, TargetKey::formatDisplayId, warns::add, (u, p) -> {
                    sentUris.add(u);
                    sentPayloads.add(p);
                }));
        assertEquals(0L, data.lastDigestEpochMillis());

        assertFalse(DiscordNotifier.checkAndSendDigest(
                data, true, 7, "", env::get, "all", true, false, "en_us", ZoneOffset.UTC,
                t0, TargetKey::formatDisplayId, warns::add, (u, p) -> {
                    sentUris.add(u);
                    sentPayloads.add(p);
                }));
        assertEquals(0L, data.lastDigestEpochMillis());
        assertTrue(sentPayloads.isEmpty());

        // 2. First run (lastDigestEpoch == 0): records current time and sends NOTHING even though old records exist.
        assertFalse(DiscordNotifier.checkAndSendDigest(
                data, true, 7, "env:IML_HOOK", env::get, "all", true, false, "en_us", ZoneOffset.UTC,
                t0, TargetKey::formatDisplayId, warns::add, (u, p) -> {
                    sentUris.add(u);
                    sentPayloads.add(p);
                }));
        assertEquals(t0, data.lastDigestEpochMillis());
        assertTrue(sentPayloads.isEmpty());

        // 3. Before 7 days elapse: does nothing and preserves timestamp.
        assertFalse(DiscordNotifier.checkAndSendDigest(
                data, true, 7, "env:IML_HOOK", env::get, "all", true, false, "en_us", ZoneOffset.UTC,
                t0 + 7L * dayMs - 1L, TargetKey::formatDisplayId, warns::add, (u, p) -> {
                    sentUris.add(u);
                    sentPayloads.add(p);
                }));
        assertEquals(t0, data.lastDigestEpochMillis());
        assertTrue(sentPayloads.isEmpty());

        // 4. After 7 days with 0 qualifying records: updates timestamp only and sends nothing.
        long t1 = t0 + 7L * dayMs;
        assertFalse(DiscordNotifier.checkAndSendDigest(
                data, true, 7, "env:IML_HOOK", env::get, "all", true, false, "en_us", ZoneOffset.UTC,
                t1, TargetKey::formatDisplayId, warns::add, (u, p) -> {
                    sentUris.add(u);
                    sentPayloads.add(p);
                }));
        assertEquals(t1, data.lastDigestEpochMillis());
        assertTrue(sentPayloads.isEmpty());

        // 5. Add records in (t1, t2]:
        //    - Alice (renamed from OldAlice) gets world-first on diamond and non-WF on tag #c:ingots/iron#64
        //    - Alice also has an epoch-0 legacy record (excluded) and a record at exact t1 (excluded)
        //    - Bob opted out (F45) -> excluded when allowDiscordOptOut = true
        //    - Charlie purged (no PlayerData) holds world-first on emerald -> included via F47 rule 3
        long t2 = t1 + 7L * dayMs;
        MilestoneRecord recLegacyZero = MilestoneRecord.legacy(1200);
        MilestoneRecord recAtLowerBound = new MilestoneRecord(2400, 1L, t1);
        MilestoneRecord recAliceWf = new MilestoneRecord(72_000, 1L, t1 + 1000L);
        MilestoneRecord recAliceTag = new MilestoneRecord(144_000, 2L, t1 + 3000L);
        pdAlice.records.put(TargetKey.of("minecraft:dirt"), recLegacyZero);
        pdAlice.records.put(TargetKey.of("minecraft:stone"), recAtLowerBound);
        pdAlice.records.put(TargetKey.of("minecraft:diamond"), recAliceWf);
        pdAlice.records.put(TargetKey.of("#c:ingots/iron", 64), recAliceTag);
        data.recordWorldFirstIfAbsent(TargetKey.of("minecraft:diamond"), aliceId, "OldAlice", recAliceWf);

        MilestoneData.PlayerData pdBob = data.player(bobId);
        pdBob.lastKnownName = "OptOutBob";
        pdBob.excludeFromDiscord = true;
        MilestoneRecord recBob = new MilestoneRecord(36_000, 1L, t1 + 2000L);
        pdBob.records.put(TargetKey.of("minecraft:gold_ingot"), recBob);
        data.recordWorldFirstIfAbsent(TargetKey.of("minecraft:gold_ingot"), bobId, "OptOutBob", recBob);

        MilestoneRecord recCharlieWf = new MilestoneRecord(18_000, 2L, t2); // exact upper bound included
        data.recordWorldFirstIfAbsent(TargetKey.of("minecraft:emerald"), charlieId, "PurgedCharlie", recCharlieWf);

        // Send plain-text digest in en_us with scope "all"
        assertTrue(DiscordNotifier.checkAndSendDigest(
                data, true, 7, "env:IML_HOOK", env::get, "all", true, false, "en_us", ZoneOffset.UTC,
                t2, TargetKey::formatDisplayId, warns::add, (u, p) -> {
                    sentUris.add(u);
                    sentPayloads.add(p);
                }));
        assertEquals(t2, data.lastDigestEpochMillis(), "timestamp updates immediately on queue");
        assertEquals(1, sentPayloads.size());
        assertEquals(URI.create(SECRET_WEBHOOK_1), sentUris.get(0));

        JsonObject plainBody = JsonParser.parseString(sentPayloads.get(0)).getAsJsonObject();
        assertFalse(plainBody.has("embeds"));
        assertEquals(0, plainBody.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());
        String[] plainLines = plainBody.get("content").getAsString().split("\n");
        assertEquals(4, plainLines.length);
        assertEquals("Milestones in the last 7 day(s)", plainLines[0]);
        assertEquals("★ \\_Alice\\_ — minecraft:diamond (" + Playtime.formatRecord(recAliceWf, ZoneOffset.UTC) + ")",
                plainLines[1]);
        assertFalse(plainLines[1].contains("OldAlice"), "digest must omit (was ...) suffix");
        assertEquals("\\_Alice\\_ — \\#c:ingots/iron ×64 (" + Playtime.formatRecord(recAliceTag, ZoneOffset.UTC) + ")",
                plainLines[2]);
        assertEquals("★ PurgedCharlie — minecraft:emerald (" + Playtime.formatRecord(recCharlieWf, ZoneOffset.UTC) + ")",
                plainLines[3]);
        assertFalse(plainBody.get("content").getAsString().contains("OptOutBob"));

        // Re-check world_first scope in ja_jp embed mode with Asia/Tokyo timezone and display name resolver
        data.setLastDigestEpochMillis(t1);
        Map<TargetKey, String> displayNames = Map.of(
                TargetKey.of("minecraft:diamond"), "Diamond",
                TargetKey.of("minecraft:emerald"), "Super_Emerald [Rare]"
        );
        assertTrue(DiscordNotifier.checkAndSendDigest(
                data, true, 7, SECRET_WEBHOOK_1, env::get, "world_first", true, true, "ja_jp",
                ZoneId.of("Asia/Tokyo"), t2, displayNames::get, warns::add, (u, p) -> {
                    sentUris.add(u);
                    sentPayloads.add(p);
                }));
        assertEquals(2, sentPayloads.size());
        JsonObject embedBody = JsonParser.parseString(sentPayloads.get(1)).getAsJsonObject();
        assertFalse(embedBody.has("content"));
        assertEquals(0, embedBody.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());
        JsonObject embed = embedBody.getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals("直近 7 日間のマイルストーン", embed.get("title").getAsString());
        assertEquals(DiscordNotifier.COLOR_WORLD_FIRST, embed.get("color").getAsInt());
        assertEquals(Instant.ofEpochMilli(t2).toString(), embed.get("timestamp").getAsString());
        String[] embedLines = embed.get("description").getAsString().split("\n");
        assertEquals(2, embedLines.length);
        assertEquals("★ \\_Alice\\_ — Diamond（" + Playtime.formatRecord(recAliceWf, ZoneId.of("Asia/Tokyo")) + "）",
                embedLines[0]);
        assertEquals("★ PurgedCharlie — Super\\_Emerald \\[Rare\\]（"
                + Playtime.formatRecord(recCharlieWf, ZoneId.of("Asia/Tokyo")) + "）",
                embedLines[1]);
    }

    @Test
    void digestPayloadCapsAt20LinesAndDropsTrailingLinesWhenCharLimitsExceeded() {
        UUID aliceId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        List<DiscordDigestLogic.DigestEntry> twentyFive = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            twentyFive.add(new DiscordDigestLogic.DigestEntry(
                    aliceId, "Alice", "minecraft:item_" + i, 1, 72_000, 1L, EPOCH_2023_11_14 + i * 1000L, false));
        }

        // Plain en_us: title + 20 lines + "...and 5 more"
        JsonObject plainBody = JsonParser.parseString(DiscordNotifier.buildDigestPayloadJson(
                twentyFive, 7, ZoneOffset.UTC, EPOCH_2023_11_14 + 30_000L, false, "en_us", TargetKey::formatDisplayId))
                .getAsJsonObject();
        String[] plainLines = plainBody.get("content").getAsString().split("\n");
        assertEquals(22, plainLines.length);
        assertEquals("Milestones in the last 7 day(s)", plainLines[0]);
        assertEquals("...and 5 more", plainLines[21]);
        assertTrue(plainBody.get("content").getAsString().length() <= DiscordNotifier.PLAIN_CONTENT_MAX_CHARS);

        // Embed ja_jp with no world-first -> blue color, 20 lines + "…ほか 5 件"
        JsonObject embedBody = JsonParser.parseString(DiscordNotifier.buildDigestPayloadJson(
                twentyFive, 14, ZoneOffset.UTC, EPOCH_2023_11_14 + 30_000L, true, "ja_jp", k -> "Item " + k.itemId()))
                .getAsJsonObject();
        JsonObject embed = embedBody.getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals("直近 14 日間のマイルストーン", embed.get("title").getAsString());
        assertEquals(DiscordNotifier.COLOR_NORMAL, embed.get("color").getAsInt());
        String[] embedLines = embed.get("description").getAsString().split("\n");
        assertEquals(21, embedLines.length);
        assertEquals("…ほか 5 件", embedLines[20]);
        assertTrue(embed.get("description").getAsString().length() <= DiscordNotifier.EMBED_DESCRIPTION_MAX_CHARS);

        // Very long item names forcing char-limit truncation below 20 lines in both plain (2000) and embed (4096)
        List<DiscordDigestLogic.DigestEntry> twentyLong = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            twentyLong.add(new DiscordDigestLogic.DigestEntry(
                    aliceId, "A".repeat(120), "minecraft:item_" + i, 1, 72_000, 1L, EPOCH_2023_11_14 + i * 1000L, false));
        }
        String cappedPlain = JsonParser.parseString(DiscordNotifier.buildDigestPayloadJson(
                twentyLong, 7, ZoneOffset.UTC, EPOCH_2023_11_14 + 30_000L, false, "en_us", TargetKey::formatDisplayId))
                .getAsJsonObject().get("content").getAsString();
        assertTrue(cappedPlain.length() <= DiscordNotifier.PLAIN_CONTENT_MAX_CHARS);
        String[] cappedPlainLines = cappedPlain.split("\n");
        int shownPlain = cappedPlainLines.length - 2;
        assertTrue(shownPlain > 0 && shownPlain < 20);
        assertEquals("...and " + (20 - shownPlain) + " more", cappedPlainLines[cappedPlainLines.length - 1]);

        String cappedEmbedDesc = JsonParser.parseString(DiscordNotifier.buildDigestPayloadJson(
                twentyLong, 7, ZoneOffset.UTC, EPOCH_2023_11_14 + 30_000L, true, "en_us", k -> "ItemName".repeat(25)))
                .getAsJsonObject().getAsJsonArray("embeds").get(0).getAsJsonObject().get("description").getAsString();
        assertTrue(cappedEmbedDesc.length() <= DiscordNotifier.EMBED_DESCRIPTION_MAX_CHARS);
        String[] cappedEmbedLines = cappedEmbedDesc.split("\n");
        int shownEmbed = cappedEmbedLines.length - 1;
        assertTrue(shownEmbed > 0 && shownEmbed < 20);
        assertEquals("...and " + (20 - shownEmbed) + " more", cappedEmbedLines[cappedEmbedLines.length - 1]);
    }

    @Test
    void f46WorldFirstWebhookRoutesSingleRecordsHonorsOptOutAndPreservesPayloadFormat() {
        assertEquals("", Config.DISCORD_WORLD_FIRST_WEBHOOK_URL.getDefault());

        MilestoneRecord rec = new MilestoneRecord(72_000, 1L, EPOCH_2023_11_14);
        TargetKey diamond = TargetKey.of("minecraft:diamond");
        Map<String, String> env = Map.of(
                "IML_MAIN", SECRET_WEBHOOK_1,
                "IML_WF", SECRET_WEBHOOK_2);

        MilestoneData data = new MilestoneData();
        MilestoneData.PlayerData normalPd = data.player(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        MilestoneData.PlayerData optedOutPd = data.player(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        optedOutPd.excludeFromDiscord = true;

        record Sent(DiscordRouting.Destination dest, URI uri, String json) {
        }
        List<Sent> sent = new ArrayList<>();
        List<String> warns = new ArrayList<>();

        // 1. Both URLs set, scope=all: world-first goes ONLY to WORLD_FIRST (unchanged payload), normal goes to MAIN.
        assertTrue(DiscordNotifier.notifyMilestone(
                true, normalPd, "env:IML_MAIN", "env:IML_WF", env::get, "all",
                "Alice", diamond, k -> "Diamond", rec, true, ZoneOffset.UTC, true, "en_us",
                warns::add, (d, u, j) -> sent.add(new Sent(d, u, j))));
        assertEquals(1, sent.size());
        assertEquals(DiscordRouting.Destination.WORLD_FIRST, sent.get(0).dest());
        assertEquals(URI.create(SECRET_WEBHOOK_2), sent.get(0).uri());
        JsonObject wfEmbed = JsonParser.parseString(sent.get(0).json()).getAsJsonObject()
                .getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals("★ World First — Alice obtained Diamond", wfEmbed.get("description").getAsString());
        assertEquals(DiscordNotifier.COLOR_WORLD_FIRST, wfEmbed.get("color").getAsInt());

        assertTrue(DiscordNotifier.notifyMilestone(
                true, normalPd, "env:IML_MAIN", "env:IML_WF", env::get, "all",
                "Alice", diamond, k -> "Diamond", rec, false, ZoneOffset.UTC, false, "en_us",
                warns::add, (d, u, j) -> sent.add(new Sent(d, u, j))));
        assertEquals(2, sent.size());
        assertEquals(DiscordRouting.Destination.MAIN, sent.get(1).dest());
        assertEquals(URI.create(SECRET_WEBHOOK_1), sent.get(1).uri());

        // 2. Both URLs set, scope=world_first: world-first goes to WORLD_FIRST, normal is suppressed (main gets no records).
        assertFalse(DiscordNotifier.notifyMilestone(
                true, normalPd, "env:IML_MAIN", "env:IML_WF", env::get, "world_first",
                "Alice", diamond, k -> "Diamond", rec, false, ZoneOffset.UTC, false, "en_us",
                warns::add, (d, u, j) -> sent.add(new Sent(d, u, j))));
        assertTrue(DiscordNotifier.notifyMilestone(
                true, normalPd, "env:IML_MAIN", "env:IML_WF", env::get, "world_first",
                "Alice", diamond, k -> "Diamond", rec, true, ZoneOffset.UTC, false, "en_us",
                warns::add, (d, u, j) -> sent.add(new Sent(d, u, j))));
        assertEquals(3, sent.size());
        assertEquals(DiscordRouting.Destination.WORLD_FIRST, sent.get(2).dest());
        assertEquals(URI.create(SECRET_WEBHOOK_2), sent.get(2).uri());

        // 3. Only world-first URL set: only world firsts are posted.
        assertFalse(DiscordNotifier.notifyMilestone(
                true, normalPd, "", "env:IML_WF", env::get, "all",
                "Alice", diamond, k -> "Diamond", rec, false, ZoneOffset.UTC, false, "en_us",
                warns::add, (d, u, j) -> sent.add(new Sent(d, u, j))));
        assertTrue(DiscordNotifier.notifyMilestone(
                true, normalPd, "", "env:IML_WF", env::get, "all",
                "Alice", diamond, k -> "Diamond", rec, true, ZoneOffset.UTC, false, "en_us",
                warns::add, (d, u, j) -> sent.add(new Sent(d, u, j))));
        assertEquals(4, sent.size());
        assertEquals(DiscordRouting.Destination.WORLD_FIRST, sent.get(3).dest());

        // 4. Unresolvable world-first env var falls back to MAIN and warns once.
        assertTrue(DiscordNotifier.notifyMilestone(
                true, normalPd, "env:IML_MAIN", "env:IML_WF_MISSING", env::get, "all",
                "Alice", diamond, k -> "Diamond", rec, true, ZoneOffset.UTC, false, "en_us",
                warns::add, (d, u, j) -> sent.add(new Sent(d, u, j))));
        assertEquals(5, sent.size());
        assertEquals(DiscordRouting.Destination.MAIN, sent.get(4).dest());
        assertEquals(URI.create(SECRET_WEBHOOK_1), sent.get(4).uri());
        assertEquals(1, warns.size());
        assertTrue(warns.get(0).contains("IML_WF_MISSING"));

        // 5. F45 opt-out applies to BOTH destinations and suppresses before resolving URLs.
        warns.clear();
        assertFalse(DiscordNotifier.notifyMilestone(
                true, optedOutPd, "http://invalid-main", "http://invalid-wf", env::get, "all",
                "Bob", diamond, k -> "Diamond", rec, false, ZoneOffset.UTC, false, "en_us",
                warns::add, (d, u, j) -> sent.add(new Sent(d, u, j))));
        assertFalse(DiscordNotifier.notifyMilestone(
                true, optedOutPd, "http://invalid-main", "http://invalid-wf", env::get, "all",
                "Bob", diamond, k -> "Diamond", rec, true, ZoneOffset.UTC, false, "en_us",
                warns::add, (d, u, j) -> sent.add(new Sent(d, u, j))));
        assertTrue(warns.isEmpty(), "F45 opt-out must suppress before resolving either URL");

        // 6. allowDiscordOptOut = false ignores saved opt-out for WORLD_FIRST too.
        assertTrue(DiscordNotifier.notifyMilestone(
                false, optedOutPd, "env:IML_MAIN", "env:IML_WF", env::get, "all",
                "Bob", diamond, k -> "Diamond", rec, true, ZoneOffset.UTC, false, "en_us",
                warns::add, (d, u, j) -> sent.add(new Sent(d, u, j))));
        assertEquals(6, sent.size());
        assertEquals(DiscordRouting.Destination.WORLD_FIRST, sent.get(5).dest());
    }

    @Test
    void f46PerDestinationDeadStateEnvWarningsAndDiscordTestWorldFirstAreIndependent() {
        URI mainUri = URI.create(SECRET_WEBHOOK_1);
        URI wfUri = URI.create(SECRET_WEBHOOK_2);
        String wfUrlReplacement = "https://discord.com/api/webhooks/333333/super-secret-token-3";
        MilestoneRecord rec = new MilestoneRecord(72_000, 1L, EPOCH_2023_11_14);
        TargetKey diamond = TargetKey.of("minecraft:diamond");
        List<String> warns = new ArrayList<>();
        AtomicInteger mainAttempts = new AtomicInteger();
        AtomicInteger wfAttempts = new AtomicInteger();

        // 1. Mark MAIN dead via HTTP 404 -> WORLD_FIRST remains alive and continues sending.
        DiscordNotifier.sendWithTransport(
                DiscordRouting.Destination.MAIN, mainUri, "{\"content\":\"m1\"}",
                (u, b) -> {
                    mainAttempts.incrementAndGet();
                    return new DiscordNotifier.HttpPostResult(404, null);
                },
                d -> {}, warns::add);
        assertTrue(DiscordNotifier.isDeadWebhook(DiscordRouting.Destination.MAIN, SECRET_WEBHOOK_1));
        assertFalse(DiscordNotifier.isDeadWebhook(DiscordRouting.Destination.WORLD_FIRST, SECRET_WEBHOOK_2));
        assertNull(DiscordNotifier.resolveWebhookUri(
                DiscordRouting.Destination.MAIN, SECRET_WEBHOOK_1, k -> null, warns::add));
        assertEquals(wfUri, DiscordNotifier.resolveWebhookUri(
                DiscordRouting.Destination.WORLD_FIRST, SECRET_WEBHOOK_2, k -> null, warns::add));

        DiscordNotifier.sendWithTransport(
                DiscordRouting.Destination.WORLD_FIRST, wfUri, "{\"content\":\"w1\"}",
                (u, b) -> {
                    wfAttempts.incrementAndGet();
                    return new DiscordNotifier.HttpPostResult(204, null);
                },
                d -> {}, warns::add);
        assertEquals(1, wfAttempts.get());

        // 2. Mark WORLD_FIRST dead via HTTP 429 -> 401 on retry; MAIN remains dead independently.
        List<Long> sleeps = new ArrayList<>();
        Queue<DiscordNotifier.HttpPostResult> wfResponses = new ArrayDeque<>(List.of(
                new DiscordNotifier.HttpPostResult(429, "0.25"),
                new DiscordNotifier.HttpPostResult(401, null)));
        DiscordNotifier.sendWithTransport(
                DiscordRouting.Destination.WORLD_FIRST, wfUri, "{\"content\":\"w2\"}",
                (u, b) -> {
                    wfAttempts.incrementAndGet();
                    return wfResponses.remove();
                },
                sleeps::add, warns::add);
        assertEquals(List.of(250L), sleeps);
        assertTrue(DiscordNotifier.isDeadWebhook(DiscordRouting.Destination.WORLD_FIRST, SECRET_WEBHOOK_2));
        assertTrue(DiscordNotifier.isDeadWebhook(DiscordRouting.Destination.MAIN, SECRET_WEBHOOK_1));
        assertNull(DiscordNotifier.resolveWebhookUri(
                DiscordRouting.Destination.WORLD_FIRST, SECRET_WEBHOOK_2, k -> null, warns::add));

        // 3. discordtest (MAIN) success resumes ONLY MAIN; WORLD_FIRST stays dead.
        AtomicReference<DiscordTestResult> mainTestRes = new AtomicReference<>();
        assertNull(DiscordNotifier.sendTestMessage(
                DiscordRouting.Destination.MAIN, SECRET_WEBHOOK_1, k -> null, false, "en_us",
                Instant.ofEpochMilli(EPOCH_2023_11_14), Runnable::run,
                (u, b) -> new DiscordNotifier.HttpPostResult(204, null),
                d -> {}, warns::add, mainTestRes::set));
        assertEquals(new DiscordTestResult(DiscordTestResult.Kind.SUCCESS, "204"), mainTestRes.get());
        assertFalse(DiscordNotifier.isDeadWebhook(DiscordRouting.Destination.MAIN, SECRET_WEBHOOK_1));
        assertTrue(DiscordNotifier.isDeadWebhook(DiscordRouting.Destination.WORLD_FIRST, SECRET_WEBHOOK_2));

        // 4. While WORLD_FIRST is dead and MAIN is alive, a world-first post must NOT leak to MAIN,
        //    while normal posts, F19 preset completions, and F9 digests keep going to MAIN.
        List<URI> sentUris = new ArrayList<>();
        assertFalse(DiscordNotifier.notifyMilestone(
                true, null, SECRET_WEBHOOK_1, SECRET_WEBHOOK_2, k -> null, "all",
                "Alice", diamond, k -> "Diamond", rec, true, ZoneOffset.UTC, false, "en_us",
                warns::add, (d, u, j) -> sentUris.add(u)));
        assertTrue(sentUris.isEmpty(), "dead WORLD_FIRST webhook must suppress rather than leak to MAIN");

        assertTrue(DiscordNotifier.notifyMilestone(
                true, null, SECRET_WEBHOOK_1, SECRET_WEBHOOK_2, k -> null, "all",
                "Alice", diamond, k -> "Diamond", rec, false, ZoneOffset.UTC, false, "en_us",
                warns::add, (d, u, j) -> sentUris.add(u)));
        assertEquals(List.of(mainUri), sentUris);

        Presets.PresetDefinition vanillaBuiltin = Presets.get("vanilla_endgame");
        assertNotNull(vanillaBuiltin);
        assertTrue(DiscordNotifier.notifyPresetCompletion(
                true, true, null, SECRET_WEBHOOK_1, k -> null,
                "Alice", vanillaBuiltin, EPOCH_2023_11_14, false, "en_us",
                warns::add, (u, j) -> sentUris.add(u)));
        assertEquals(List.of(mainUri, mainUri), sentUris);

        // 5. discordtest worldfirst (F49) bypasses dead stop and resumes WORLD_FIRST on HTTP 2xx.
        AtomicReference<DiscordTestResult> wfTestRes = new AtomicReference<>();
        assertNull(DiscordNotifier.sendTestMessage(
                DiscordRouting.Destination.WORLD_FIRST, SECRET_WEBHOOK_2, k -> null, true, "ja_jp",
                Instant.ofEpochMilli(EPOCH_2023_11_14), Runnable::run,
                (u, b) -> {
                    assertEquals(wfUri, u);
                    return new DiscordNotifier.HttpPostResult(200, null);
                },
                d -> {}, warns::add, wfTestRes::set));
        assertEquals(new DiscordTestResult(DiscordTestResult.Kind.SUCCESS, "200"), wfTestRes.get());
        assertFalse(DiscordNotifier.isDeadWebhook(DiscordRouting.Destination.WORLD_FIRST, SECRET_WEBHOOK_2));
        assertEquals(wfUri, DiscordNotifier.resolveWebhookUri(
                DiscordRouting.Destination.WORLD_FIRST, SECRET_WEBHOOK_2, k -> null, warns::add));

        // 6. Changing WORLD_FIRST URL clears only WORLD_FIRST dead state (MAIN stays dead if marked dead).
        DiscordNotifier.sendWithTransport(
                DiscordRouting.Destination.MAIN, mainUri, "{\"content\":\"m-dead\"}",
                (u, b) -> new DiscordNotifier.HttpPostResult(404, null), d -> {}, warns::add);
        DiscordNotifier.sendWithTransport(
                DiscordRouting.Destination.WORLD_FIRST, wfUri, "{\"content\":\"w-dead\"}",
                (u, b) -> new DiscordNotifier.HttpPostResult(404, null), d -> {}, warns::add);
        assertEquals(URI.create(wfUrlReplacement), DiscordNotifier.resolveWebhookUri(
                DiscordRouting.Destination.WORLD_FIRST, wfUrlReplacement, k -> null, warns::add));
        assertFalse(DiscordNotifier.isDeadWebhook(DiscordRouting.Destination.WORLD_FIRST, SECRET_WEBHOOK_2));
        assertTrue(DiscordNotifier.isDeadWebhook(DiscordRouting.Destination.MAIN, SECRET_WEBHOOK_1));

        // 7. F49 discordtest worldfirst runs identical pre-checks without touching MAIN.
        Executor neverRun = r -> {
            throw new AssertionError("executor must not run on pre-check failure");
        };
        assertEquals(DiscordTestResult.Kind.NOT_CONFIGURED,
                DiscordNotifier.sendTestMessage(
                        DiscordRouting.Destination.WORLD_FIRST, "", k -> null, false, "en_us",
                        Instant.ofEpochMilli(EPOCH_2023_11_14), neverRun,
                        (u, b) -> new DiscordNotifier.HttpPostResult(204, null),
                        d -> {}, warns::add, r -> {}).kind());
        assertEquals(new DiscordTestResult(DiscordTestResult.Kind.ENV_MISSING, "WF_MISSING"),
                DiscordNotifier.sendTestMessage(
                        DiscordRouting.Destination.WORLD_FIRST, "env:WF_MISSING", k -> null, false, "en_us",
                        Instant.ofEpochMilli(EPOCH_2023_11_14), neverRun,
                        (u, b) -> new DiscordNotifier.HttpPostResult(204, null),
                        d -> {}, warns::add, r -> {}));

        // 8. Independent env: warning deduplication: alternating missing MAIN and WORLD_FIRST env vars warns ONCE each.
        warns.clear();
        DiscordNotifier.resetEnvWarnings();
        for (int i = 0; i < 3; i++) {
            assertNull(DiscordNotifier.resolveWebhookUri(
                    DiscordRouting.Destination.MAIN, "env:MISS_MAIN", k -> null, warns::add));
            assertNull(DiscordNotifier.resolveWebhookUri(
                    DiscordRouting.Destination.WORLD_FIRST, "env:MISS_WF", k -> null, warns::add));
        }
        assertEquals(2, warns.size());
        assertTrue(warns.get(0).contains("MISS_MAIN"));
        assertTrue(warns.get(1).contains("MISS_WF"));

        // 9. Singleplayer startup warning fires once if ONLY world-first URL resolves, and once if BOTH resolve.
        warns.clear();
        assertTrue(DiscordNotifier.warnIfWebhookConfiguredInSingleplayer("", SECRET_WEBHOOK_2, k -> null, warns::add));
        assertTrue(DiscordNotifier.warnIfWebhookConfiguredInSingleplayer(SECRET_WEBHOOK_1, SECRET_WEBHOOK_2, k -> null, warns::add));
        assertFalse(DiscordNotifier.warnIfWebhookConfiguredInSingleplayer("", "", k -> null, warns::add));
        assertEquals(2, warns.size());
    }

    @Test
    void resolveDestinationsForBatchResolvesOnlyDestinationsEligibleForBatch() {
        List<String> warns = new ArrayList<>();

        // Normal-only batch under scope=world_first resolves NEITHER destination.
        DiscordNotifier.ResolvedDestinations none = DiscordNotifier.resolveDestinationsForBatch(
                true, false, "world_first", "http://bad-main", "http://bad-wf", k -> null, warns::add);
        assertFalse(none.main().resolved());
        assertFalse(none.worldFirst().resolved());
        assertTrue(warns.isEmpty());

        // World-first-only batch when WF resolves skips MAIN resolution completely.
        DiscordNotifier.ResolvedDestinations wfOnly = DiscordNotifier.resolveDestinationsForBatch(
                false, true, "all", "http://bad-main", SECRET_WEBHOOK_2, k -> null, warns::add);
        assertFalse(wfOnly.main().resolved());
        assertTrue(wfOnly.worldFirst().resolved());
        assertEquals(URI.create(SECRET_WEBHOOK_2), wfOnly.worldFirst().deliverableUri());
        assertTrue(warns.isEmpty());

        // World-first-only batch when WF is empty falls back to resolving MAIN.
        DiscordNotifier.ResolvedDestinations wfFallback = DiscordNotifier.resolveDestinationsForBatch(
                false, true, "world_first", SECRET_WEBHOOK_1, "", k -> null, warns::add);
        assertTrue(wfFallback.main().resolved());
        assertEquals(URI.create(SECRET_WEBHOOK_1), wfFallback.main().deliverableUri());
        assertFalse(wfFallback.worldFirst().resolved());

        // Mixed batch under scope=all resolves both MAIN and WORLD_FIRST.
        DiscordNotifier.ResolvedDestinations mixed = DiscordNotifier.resolveDestinationsForBatch(
                true, true, "all", SECRET_WEBHOOK_1, SECRET_WEBHOOK_2, k -> null, warns::add);
        assertTrue(mixed.main().resolved());
        assertEquals(URI.create(SECRET_WEBHOOK_1), mixed.main().deliverableUri());
        assertTrue(mixed.worldFirst().resolved());
        assertEquals(URI.create(SECRET_WEBHOOK_2), mixed.worldFirst().deliverableUri());
    }

    @Test
    void discordTestWorldFirstSupportsEnvPreChecksConcurrencyErrorClassificationAndOmitsFlags() throws Exception {
        Instant sendTime = Instant.ofEpochMilli(EPOCH_2023_11_14);
        Map<String, String> env = Map.of(
                "IML_WF_GOOD", SECRET_WEBHOOK_2,
                "IML_WF_EMPTY", "   ",
                "IML_WF_HTTP", "http://discord.com/api/webhooks/222222/super-secret-http"
        );
        List<String> warns = new ArrayList<>();
        Executor neverRun = r -> {
            throw new AssertionError("executor must not run on pre-check failure");
        };

        // 1. Pre-checks for WORLD_FIRST mirror MAIN (empty, env missing/empty, invalid URL) without leaking URLs.
        assertEquals(DiscordTestResult.Kind.NOT_CONFIGURED,
                DiscordNotifier.sendTestMessage(
                        DiscordRouting.Destination.WORLD_FIRST, "   ", env::get, false, "en_us", sendTime,
                        neverRun, (u, b) -> new DiscordNotifier.HttpPostResult(204, null),
                        d -> {}, warns::add, r -> {}).kind());
        assertEquals(new DiscordTestResult(DiscordTestResult.Kind.ENV_MISSING, "IML_WF_EMPTY"),
                DiscordNotifier.sendTestMessage(
                        DiscordRouting.Destination.WORLD_FIRST, "env:IML_WF_EMPTY", env::get, false, "en_us", sendTime,
                        neverRun, (u, b) -> new DiscordNotifier.HttpPostResult(204, null),
                        d -> {}, warns::add, r -> {}));
        for (String invalid : new String[]{"env:bad-name", "env:IML_WF_HTTP", "http://discord.com/api/webhooks/2/secret"}) {
            DiscordTestResult res = DiscordNotifier.sendTestMessage(
                    DiscordRouting.Destination.WORLD_FIRST, invalid, env::get, false, "en_us", sendTime,
                    neverRun, (u, b) -> new DiscordNotifier.HttpPostResult(204, null),
                    d -> {}, warns::add, r -> {});
            assertNotNull(res);
            assertEquals(DiscordTestResult.Kind.INVALID_URL, res.kind(), "invalid=" + invalid);
            assertNull(res.detail());
        }
        assertFalse(DiscordNotifier.isTestInProgress());

        // 2. Valid env: resolution sends plain & embed test payloads to WORLD_FIRST URI with NO F53 flags property.
        for (boolean useEmbeds : new boolean[]{false, true}) {
            AtomicReference<URI> capturedUri = new AtomicReference<>();
            AtomicReference<String> capturedBody = new AtomicReference<>();
            AtomicReference<DiscordTestResult> result = new AtomicReference<>();
            assertNull(DiscordNotifier.sendTestMessage(
                    DiscordRouting.Destination.WORLD_FIRST, "env:IML_WF_GOOD", env::get, useEmbeds, "en_us", sendTime,
                    Runnable::run,
                    (u, body) -> {
                        capturedUri.set(u);
                        capturedBody.set(body);
                        return new DiscordNotifier.HttpPostResult(204, null);
                    },
                    d -> {}, warns::add, result::set));
            assertEquals(URI.create(SECRET_WEBHOOK_2), capturedUri.get());
            assertEquals(new DiscordTestResult(DiscordTestResult.Kind.SUCCESS, "204"), result.get());
            JsonObject parsed = JsonParser.parseString(capturedBody.get()).getAsJsonObject();
            assertFalse(parsed.has("flags"), "test send must not set flags (useEmbeds=" + useEmbeds + ")");
            assertEquals(useEmbeds, parsed.has("embeds"));
            assertEquals(!useEmbeds, parsed.has("content"));
        }

        // 3. Concurrency guard is shared across MAIN and WORLD_FIRST: in-flight WORLD_FIRST blocks MAIN.
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch allowFinish = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        Executor bgThread = r -> {
            Thread t = new Thread(r, "test-discord-wf-worker");
            t.setDaemon(true);
            t.start();
        };
        try {
            assertNull(DiscordNotifier.sendTestMessage(
                    DiscordRouting.Destination.WORLD_FIRST, "env:IML_WF_GOOD", env::get, false, "en_us", sendTime,
                    bgThread,
                    (u, body) -> {
                        entered.countDown();
                        assertTrue(allowFinish.await(5, TimeUnit.SECONDS));
                        return new DiscordNotifier.HttpPostResult(204, null);
                    },
                    d -> {}, warns::add, r -> finished.countDown()));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(DiscordTestResult.inProgress(), DiscordNotifier.sendTestMessage(
                    DiscordRouting.Destination.MAIN, SECRET_WEBHOOK_1, env::get, false, "en_us", sendTime,
                    neverRun, (u, b) -> new DiscordNotifier.HttpPostResult(204, null),
                    d -> {}, warns::add, r -> {
                        throw new AssertionError("callback must not run for IN_PROGRESS");
                    }));
            allowFinish.countDown(); // release before waiting; finally below covers early failures
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (DiscordNotifier.isTestInProgress() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertFalse(DiscordNotifier.isTestInProgress());
        } finally {
            // #123: an assertion failure above must never leave the worker waiting 5 s.
            allowFinish.countDown();
        }

        // 4. WORLD_FIRST error classification (404 marks only WORLD_FIRST dead; 429, 500, and network error match F49).
        AtomicReference<DiscordTestResult> err404 = new AtomicReference<>();
        assertNull(DiscordNotifier.sendTestMessage(
                DiscordRouting.Destination.WORLD_FIRST, SECRET_WEBHOOK_2, env::get, false, "en_us", sendTime,
                Runnable::run, (u, b) -> new DiscordNotifier.HttpPostResult(404, null),
                d -> {}, warns::add, err404::set));
        assertEquals(new DiscordTestResult(DiscordTestResult.Kind.INVALID_WEBHOOK, "404"), err404.get());
        assertTrue(DiscordNotifier.isDeadWebhook(DiscordRouting.Destination.WORLD_FIRST, SECRET_WEBHOOK_2));
        assertFalse(DiscordNotifier.isDeadWebhook(DiscordRouting.Destination.MAIN, SECRET_WEBHOOK_1));

        AtomicReference<DiscordTestResult> err429 = new AtomicReference<>();
        assertNull(DiscordNotifier.sendTestMessage(
                DiscordRouting.Destination.WORLD_FIRST, SECRET_WEBHOOK_2, env::get, false, "en_us", sendTime,
                Runnable::run, (u, b) -> new DiscordNotifier.HttpPostResult(429, null),
                d -> {}, warns::add, err429::set));
        assertEquals(new DiscordTestResult(DiscordTestResult.Kind.RATE_LIMITED, null), err429.get());

        AtomicReference<DiscordTestResult> err500 = new AtomicReference<>();
        assertNull(DiscordNotifier.sendTestMessage(
                DiscordRouting.Destination.WORLD_FIRST, SECRET_WEBHOOK_2, env::get, false, "en_us", sendTime,
                Runnable::run, (u, b) -> new DiscordNotifier.HttpPostResult(500, null),
                d -> {}, warns::add, err500::set));
        assertEquals(new DiscordTestResult(DiscordTestResult.Kind.HTTP_ERROR, "500"), err500.get());

        AtomicReference<DiscordTestResult> netErr = new AtomicReference<>();
        assertNull(DiscordNotifier.sendTestMessage(
                DiscordRouting.Destination.WORLD_FIRST, SECRET_WEBHOOK_2, env::get, false, "en_us", sendTime,
                Runnable::run,
                (u, b) -> {
                    throw new IOException("failed posting to " + SECRET_WEBHOOK_2);
                },
                d -> {}, warns::add, netErr::set));
        assertEquals(new DiscordTestResult(DiscordTestResult.Kind.NETWORK_ERROR, "IOException"), netErr.get());
        for (String w : warns) {
            assertFalse(w.contains(SECRET_WEBHOOK_2));
            assertFalse(w.contains("super-secret-token-2"));
        }
    }

    @Test
    void f53SilentRecordsConfigDefaultConstantAndDecisionHelpers() {
        assertEquals(Boolean.FALSE, Config.DISCORD_SILENT_RECORDS.getDefault());
        assertFalse(Config.discordSilentRecords());
        assertEquals(1 << 12, DiscordNotifier.SUPPRESS_NOTIFICATIONS);
        assertEquals(4096, DiscordNotifier.SUPPRESS_NOTIFICATIONS);

        assertFalse(DiscordNotifier.shouldSuppressRecordNotifications(false, false));
        assertFalse(DiscordNotifier.shouldSuppressRecordNotifications(false, true));
        assertTrue(DiscordNotifier.shouldSuppressRecordNotifications(true, false));
        assertFalse(DiscordNotifier.shouldSuppressRecordNotifications(true, true));

        MilestoneRecord rec = new MilestoneRecord(72_000, 1L, EPOCH_2023_11_14);
        List<DiscordNotifier.MilestoneEntry> normalOnly = List.of(
                new DiscordNotifier.MilestoneEntry(TargetKey.of("minecraft:coal"), rec, false),
                new DiscordNotifier.MilestoneEntry(TargetKey.of("minecraft:iron_ingot"), rec, false),
                new DiscordNotifier.MilestoneEntry(TargetKey.of("minecraft:gold_ingot"), rec, false));
        List<DiscordNotifier.MilestoneEntry> mixed = List.of(
                new DiscordNotifier.MilestoneEntry(TargetKey.of("minecraft:coal"), rec, false),
                new DiscordNotifier.MilestoneEntry(TargetKey.of("minecraft:diamond"), rec, true),
                new DiscordNotifier.MilestoneEntry(TargetKey.of("minecraft:emerald"), rec, false));
        List<DiscordNotifier.MilestoneEntry> wfOnly = List.of(
                new DiscordNotifier.MilestoneEntry(TargetKey.of("minecraft:diamond"), rec, true),
                new DiscordNotifier.MilestoneEntry(TargetKey.of("minecraft:emerald"), rec, true),
                new DiscordNotifier.MilestoneEntry(TargetKey.of("minecraft:netherite_ingot"), rec, true));

        assertFalse(DiscordNotifier.shouldSuppressGroupedNotifications(false, normalOnly));
        assertFalse(DiscordNotifier.shouldSuppressGroupedNotifications(true, null));
        assertFalse(DiscordNotifier.shouldSuppressGroupedNotifications(true, List.of()));
        assertTrue(DiscordNotifier.shouldSuppressGroupedNotifications(true, normalOnly));
        assertFalse(DiscordNotifier.shouldSuppressGroupedNotifications(true, mixed));
        assertFalse(DiscordNotifier.shouldSuppressGroupedNotifications(true, wfOnly));
    }

    @Test
    void f53SilentRecordsDisabledOmitsFlagsEverywhereAndProducesByteIdenticalPayloads() {
        MilestoneRecord rec = new MilestoneRecord(72_000, 1L, EPOCH_2023_11_14);
        TargetKey coal = TargetKey.of("minecraft:coal");
        TargetKey diamond = TargetKey.of("minecraft:diamond");
        TargetKey emerald = TargetKey.of("minecraft:emerald");
        List<DiscordNotifier.MilestoneEntry> normalGroup = List.of(
                new DiscordNotifier.MilestoneEntry(coal, rec, false),
                new DiscordNotifier.MilestoneEntry(diamond, rec, false),
                new DiscordNotifier.MilestoneEntry(emerald, rec, false));
        List<DiscordNotifier.MilestoneEntry> mixedGroup = List.of(
                new DiscordNotifier.MilestoneEntry(coal, rec, false),
                new DiscordNotifier.MilestoneEntry(diamond, rec, true),
                new DiscordNotifier.MilestoneEntry(emerald, rec, false));

        for (boolean useEmbeds : new boolean[]{false, true}) {
            String legacySingleNormal = DiscordNotifier.buildSinglePayloadJson(
                    "Alice", coal, "Coal", rec, false, ZoneOffset.UTC, useEmbeds, "en_us");
            String silentFalseSingleNormal = DiscordNotifier.buildSinglePayloadJson(
                    "Alice", coal, "Coal", rec, false, ZoneOffset.UTC, useEmbeds, "en_us", false);
            assertEquals(legacySingleNormal, silentFalseSingleNormal);
            assertFalse(JsonParser.parseString(silentFalseSingleNormal).getAsJsonObject().has("flags"));

            String legacySingleWf = DiscordNotifier.buildSinglePayloadJson(
                    "Alice", diamond, "Diamond", rec, true, ZoneOffset.UTC, useEmbeds, "en_us");
            String silentFalseSingleWf = DiscordNotifier.buildSinglePayloadJson(
                    "Alice", diamond, "Diamond", rec, true, ZoneOffset.UTC, useEmbeds, "en_us", false);
            assertEquals(legacySingleWf, silentFalseSingleWf);
            assertFalse(JsonParser.parseString(silentFalseSingleWf).getAsJsonObject().has("flags"));

            String legacyGroupNormal = DiscordNotifier.buildGroupedPayloadJson(
                    "Alice", normalGroup, ZoneOffset.UTC, useEmbeds, "en_us", TargetKey::formatDisplayId);
            String silentFalseGroupNormal = DiscordNotifier.buildGroupedPayloadJson(
                    "Alice", normalGroup, ZoneOffset.UTC, useEmbeds, "en_us", TargetKey::formatDisplayId, false);
            assertEquals(legacyGroupNormal, silentFalseGroupNormal);
            assertFalse(JsonParser.parseString(silentFalseGroupNormal).getAsJsonObject().has("flags"));

            String legacyGroupMixed = DiscordNotifier.buildGroupedPayloadJson(
                    "Alice", mixedGroup, ZoneOffset.UTC, useEmbeds, "en_us", TargetKey::formatDisplayId);
            String silentFalseGroupMixed = DiscordNotifier.buildGroupedPayloadJson(
                    "Alice", mixedGroup, ZoneOffset.UTC, useEmbeds, "en_us", TargetKey::formatDisplayId, false);
            assertEquals(legacyGroupMixed, silentFalseGroupMixed);
            assertFalse(JsonParser.parseString(silentFalseGroupMixed).getAsJsonObject().has("flags"));

            // Dispatch paths with silentRecords = false emit no flags on any destination
            List<String> sent = new ArrayList<>();
            List<String> warns = new ArrayList<>();
            assertTrue(DiscordNotifier.notifyMilestone(
                    true, null, SECRET_WEBHOOK_1, SECRET_WEBHOOK_2, k -> null, "all",
                    "Alice", coal, k -> "Coal", rec, false, ZoneOffset.UTC, useEmbeds, "en_us", false,
                    warns::add, (d, u, j) -> sent.add(j)));
            assertTrue(DiscordNotifier.notifyMilestone(
                    true, null, SECRET_WEBHOOK_1, SECRET_WEBHOOK_2, k -> null, "all",
                    "Alice", diamond, k -> "Diamond", rec, true, ZoneOffset.UTC, useEmbeds, "en_us", false,
                    warns::add, (d, u, j) -> sent.add(j)));

            DiscordNotifier.DestinationResolution mainDest = new DiscordNotifier.DestinationResolution(
                    DiscordRouting.Destination.MAIN, true, URI.create(SECRET_WEBHOOK_1));
            assertTrue(DiscordNotifier.notifyMilestoneToDestination(
                    true, null, "Alice", coal, k -> "Coal", rec, false,
                    ZoneOffset.UTC, useEmbeds, "en_us", false, mainDest, (d, u, j) -> sent.add(j)));
            assertTrue(DiscordNotifier.notifyMilestonesGroupedToDestination(
                    true, null, "Alice", normalGroup,
                    ZoneOffset.UTC, useEmbeds, "en_us", false, TargetKey::formatDisplayId, mainDest,
                    (d, u, j) -> sent.add(j)));

            assertEquals(4, sent.size());
            for (String json : sent) {
                assertFalse(JsonParser.parseString(json).getAsJsonObject().has("flags"),
                        "silentRecords=false must never emit flags: " + json);
            }
        }
    }

    @Test
    void f53SilentRecordsEnabledSetsFlagsOnlyOnNormalSingleAndWorldFirstFreeGroupedPayloads() {
        MilestoneRecord rec = new MilestoneRecord(72_000, 1L, EPOCH_2023_11_14);
        TargetKey coal = TargetKey.of("minecraft:coal");
        TargetKey diamond = TargetKey.of("minecraft:diamond");
        TargetKey emerald = TargetKey.of("minecraft:emerald");
        List<DiscordNotifier.MilestoneEntry> normalGroup = List.of(
                new DiscordNotifier.MilestoneEntry(coal, rec, false),
                new DiscordNotifier.MilestoneEntry(diamond, rec, false),
                new DiscordNotifier.MilestoneEntry(emerald, rec, false));
        List<DiscordNotifier.MilestoneEntry> mixedGroup = List.of(
                new DiscordNotifier.MilestoneEntry(coal, rec, false),
                new DiscordNotifier.MilestoneEntry(diamond, rec, true),
                new DiscordNotifier.MilestoneEntry(emerald, rec, false));
        List<DiscordNotifier.MilestoneEntry> wfOnlyGroup = List.of(
                new DiscordNotifier.MilestoneEntry(coal, rec, true),
                new DiscordNotifier.MilestoneEntry(diamond, rec, true),
                new DiscordNotifier.MilestoneEntry(emerald, rec, true));

        Presets.PresetDefinition vanillaBuiltin = Presets.get("vanilla_endgame");
        assertNotNull(vanillaBuiltin);
        UUID aliceId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        List<DiscordDigestLogic.DigestEntry> normalDigestEntries = List.of(
                new DiscordDigestLogic.DigestEntry(
                        aliceId, "Alice", "minecraft:coal", 1, 72_000, 1L, EPOCH_2023_11_14, false));
        List<DiscordDigestLogic.DigestEntry> wfDigestEntries = List.of(
                new DiscordDigestLogic.DigestEntry(
                        aliceId, "Alice", "minecraft:diamond", 1, 72_000, 1L, EPOCH_2023_11_14, true));
        Instant sendTime = Instant.ofEpochMilli(EPOCH_2023_11_14);

        for (boolean useEmbeds : new boolean[]{false, true}) {
            // 1. Normal single record -> flags: 4096 (plain text and embed alike)
            JsonObject singleNormal = JsonParser.parseString(DiscordNotifier.buildSinglePayloadJson(
                    "Alice", coal, "Coal", rec, false, ZoneOffset.UTC, useEmbeds, "en_us", true)).getAsJsonObject();
            assertTrue(singleNormal.has("flags"), "normal single must emit flags (useEmbeds=" + useEmbeds + ")");
            assertEquals(DiscordNotifier.SUPPRESS_NOTIFICATIONS, singleNormal.get("flags").getAsInt());

            // 2. World-first single record -> NO flags even when suppressNotifications=true is passed
            JsonObject singleWf = JsonParser.parseString(DiscordNotifier.buildSinglePayloadJson(
                    "Alice", diamond, "Diamond", rec, true, ZoneOffset.UTC, useEmbeds, "en_us", true)).getAsJsonObject();
            assertFalse(singleWf.has("flags"), "world-first single must never emit flags (useEmbeds=" + useEmbeds + ")");

            // 3. F27 grouped batch with NO world first -> flags: 4096
            JsonObject groupedNormal = JsonParser.parseString(DiscordNotifier.buildGroupedPayloadJson(
                    "Alice", normalGroup, ZoneOffset.UTC, useEmbeds, "en_us", TargetKey::formatDisplayId, true))
                    .getAsJsonObject();
            assertTrue(groupedNormal.has("flags"), "normal-only group must emit flags (useEmbeds=" + useEmbeds + ")");
            assertEquals(DiscordNotifier.SUPPRESS_NOTIFICATIONS, groupedNormal.get("flags").getAsInt());

            // 4. F27 grouped batch containing >= 1 world first (mixed and wf-only) -> NO flags
            JsonObject groupedMixed = JsonParser.parseString(DiscordNotifier.buildGroupedPayloadJson(
                    "Alice", mixedGroup, ZoneOffset.UTC, useEmbeds, "en_us", TargetKey::formatDisplayId, true))
                    .getAsJsonObject();
            assertFalse(groupedMixed.has("flags"), "mixed group must never emit flags (useEmbeds=" + useEmbeds + ")");

            JsonObject groupedWfOnly = JsonParser.parseString(DiscordNotifier.buildGroupedPayloadJson(
                    "Alice", wfOnlyGroup, ZoneOffset.UTC, useEmbeds, "en_us", TargetKey::formatDisplayId, true))
                    .getAsJsonObject();
            assertFalse(groupedWfOnly.has("flags"), "wf-only group must never emit flags (useEmbeds=" + useEmbeds + ")");

            // 5. F19 preset completion -> NEVER has flags
            JsonObject presetPayload = JsonParser.parseString(DiscordNotifier.buildPresetCompletionPayloadJson(
                    "Alice", vanillaBuiltin, EPOCH_2023_11_14, useEmbeds, "en_us")).getAsJsonObject();
            assertFalse(presetPayload.has("flags"), "F19 preset completion must never emit flags");

            // 6. F9 periodic digest (both normal-only and world-first entries) -> NEVER has flags
            JsonObject normalDigest = JsonParser.parseString(DiscordNotifier.buildDigestPayloadJson(
                    normalDigestEntries, 7, ZoneOffset.UTC, EPOCH_2023_11_14 + 1000L, useEmbeds, "en_us",
                    TargetKey::formatDisplayId)).getAsJsonObject();
            assertFalse(normalDigest.has("flags"), "F9 digest (normal entries) must never emit flags");

            JsonObject wfDigest = JsonParser.parseString(DiscordNotifier.buildDigestPayloadJson(
                    wfDigestEntries, 7, ZoneOffset.UTC, EPOCH_2023_11_14 + 1000L, useEmbeds, "en_us",
                    TargetKey::formatDisplayId)).getAsJsonObject();
            assertFalse(wfDigest.has("flags"), "F9 digest (wf entries) must never emit flags");

            // 7. F49 discordtest (both MAIN and WORLD_FIRST variants) -> NEVER has flags
            JsonObject testPayload = JsonParser.parseString(DiscordNotifier.buildTestPayloadJson(
                    useEmbeds, "en_us", sendTime)).getAsJsonObject();
            assertFalse(testPayload.has("flags"), "F49 test payload must never emit flags");

            for (DiscordRouting.Destination dest : DiscordRouting.Destination.values()) {
                String url = dest == DiscordRouting.Destination.WORLD_FIRST ? SECRET_WEBHOOK_2 : SECRET_WEBHOOK_1;
                AtomicReference<String> captured = new AtomicReference<>();
                assertNull(DiscordNotifier.sendTestMessage(
                        dest, url, k -> null, useEmbeds, "en_us", sendTime,
                        Runnable::run,
                        (u, body) -> {
                            captured.set(body);
                            return new DiscordNotifier.HttpPostResult(204, null);
                        },
                        d -> {}, msg -> {}, res -> {}));
                assertNotNull(captured.get());
                assertFalse(JsonParser.parseString(captured.get()).getAsJsonObject().has("flags"),
                        "F49 " + dest + " test send must never emit flags (useEmbeds=" + useEmbeds + ")");
            }
        }

        // Direct single/grouped embed builder overloads also respect worldFirst invariant
        JsonObject directNormalEmbed = JsonParser.parseString(DiscordNotifier.buildSingleEmbedJson(
                "Alice", "Coal", rec, false, ZoneOffset.UTC, "en_us", true)).getAsJsonObject();
        assertEquals(DiscordNotifier.SUPPRESS_NOTIFICATIONS, directNormalEmbed.get("flags").getAsInt());
        JsonObject directWfEmbed = JsonParser.parseString(DiscordNotifier.buildSingleEmbedJson(
                "Alice", "Diamond", rec, true, ZoneOffset.UTC, "en_us", true)).getAsJsonObject();
        assertFalse(directWfEmbed.has("flags"));

        JsonObject directNormalGroupEmbed = JsonParser.parseString(DiscordNotifier.buildGroupedEmbedJson(
                "Alice", normalGroup, ZoneOffset.UTC, "en_us", TargetKey::formatDisplayId, true)).getAsJsonObject();
        assertEquals(DiscordNotifier.SUPPRESS_NOTIFICATIONS, directNormalGroupEmbed.get("flags").getAsInt());
        JsonObject directMixedGroupEmbed = JsonParser.parseString(DiscordNotifier.buildGroupedEmbedJson(
                "Alice", mixedGroup, ZoneOffset.UTC, "en_us", TargetKey::formatDisplayId, true)).getAsJsonObject();
        assertFalse(directMixedGroupEmbed.has("flags"));
    }

    @Test
    void f53WithF46DualWebhooksSetsFlagsOnAllMainRecordPostsAndNeverOnWorldFirstPosts() {
        MilestoneRecord rec = new MilestoneRecord(72_000, 1L, EPOCH_2023_11_14);
        TargetKey coal = TargetKey.of("minecraft:coal");
        TargetKey iron = TargetKey.of("minecraft:iron_ingot");
        TargetKey gold = TargetKey.of("minecraft:gold_ingot");
        TargetKey diamond = TargetKey.of("minecraft:diamond");
        TargetKey emerald = TargetKey.of("minecraft:emerald");
        TargetKey netherite = TargetKey.of("minecraft:netherite_ingot");

        record Sent(DiscordRouting.Destination dest, URI uri, String json) {
        }

        for (boolean useEmbeds : new boolean[]{false, true}) {
            List<Sent> sent = new ArrayList<>();
            List<String> warns = new ArrayList<>();

            // 1. Single dispatch with F46 dual webhooks + silentRecords = true:
            //    normal -> MAIN with flags: 4096; world-first -> WORLD_FIRST with NO flags.
            assertTrue(DiscordNotifier.notifyMilestone(
                    true, null, SECRET_WEBHOOK_1, SECRET_WEBHOOK_2, k -> null, "all",
                    "Alice", coal, k -> "Coal", rec, false, ZoneOffset.UTC, useEmbeds, "en_us", true,
                    warns::add, (d, u, j) -> sent.add(new Sent(d, u, j))));
            assertTrue(DiscordNotifier.notifyMilestone(
                    true, null, SECRET_WEBHOOK_1, SECRET_WEBHOOK_2, k -> null, "all",
                    "Alice", diamond, k -> "Diamond", rec, true, ZoneOffset.UTC, useEmbeds, "en_us", true,
                    warns::add, (d, u, j) -> sent.add(new Sent(d, u, j))));

            assertEquals(2, sent.size());
            assertEquals(DiscordRouting.Destination.MAIN, sent.get(0).dest());
            JsonObject mainSingle = JsonParser.parseString(sent.get(0).json()).getAsJsonObject();
            assertEquals(DiscordNotifier.SUPPRESS_NOTIFICATIONS, mainSingle.get("flags").getAsInt());

            assertEquals(DiscordRouting.Destination.WORLD_FIRST, sent.get(1).dest());
            JsonObject wfSingle = JsonParser.parseString(sent.get(1).json()).getAsJsonObject();
            assertFalse(wfSingle.has("flags"));

            // 2. Single dispatch without F46 worldFirstUrl: world-first falls back to MAIN with NO flags.
            sent.clear();
            assertTrue(DiscordNotifier.notifyMilestone(
                    true, null, SECRET_WEBHOOK_1, "", k -> null, "all",
                    "Alice", diamond, k -> "Diamond", rec, true, ZoneOffset.UTC, useEmbeds, "en_us", true,
                    warns::add, (d, u, j) -> sent.add(new Sent(d, u, j))));
            assertEquals(1, sent.size());
            assertEquals(DiscordRouting.Destination.MAIN, sent.get(0).dest());
            assertFalse(JsonParser.parseString(sent.get(0).json()).getAsJsonObject().has("flags"));

            // 3. F27 + F46 batch (3 normal + 2 world-first): MAIN gets 1 grouped post (flags: 4096),
            //    WORLD_FIRST gets 2 individual posts (NO flags).
            List<DiscordNotifier.MilestoneEntry> batch3Normal2Wf = List.of(
                    new DiscordNotifier.MilestoneEntry(coal, rec, false),
                    new DiscordNotifier.MilestoneEntry(iron, rec, false),
                    new DiscordNotifier.MilestoneEntry(gold, rec, false),
                    new DiscordNotifier.MilestoneEntry(diamond, rec, true),
                    new DiscordNotifier.MilestoneEntry(emerald, rec, true));
            NotificationGrouping.FlushPlan<DiscordNotifier.MilestoneEntry> planDual1 = NotificationGrouping.planFlush(
                    batch3Normal2Wf, batch3Normal2Wf,
                    DiscordNotifier.MilestoneEntry::worldFirst,
                    Comparator.comparing(DiscordNotifier.MilestoneEntry::target),
                    false, false, false, false, true, "all", true, true);
            assertEquals(NotificationGrouping.SendMode.GROUPED, planDual1.discord().mode());
            assertEquals(NotificationGrouping.SendMode.INDIVIDUAL, planDual1.discordWorldFirst().mode());

            DiscordNotifier.DestinationResolution mainDest = new DiscordNotifier.DestinationResolution(
                    DiscordRouting.Destination.MAIN, true, URI.create(SECRET_WEBHOOK_1));
            DiscordNotifier.DestinationResolution wfDest = new DiscordNotifier.DestinationResolution(
                    DiscordRouting.Destination.WORLD_FIRST, true, URI.create(SECRET_WEBHOOK_2));

            sent.clear();
            assertTrue(DiscordNotifier.notifyMilestonesGroupedToDestination(
                    true, null, "Alice", planDual1.discord().items(),
                    ZoneOffset.UTC, useEmbeds, "en_us", true, TargetKey::formatDisplayId, mainDest,
                    (d, u, j) -> sent.add(new Sent(d, u, j))));
            for (DiscordNotifier.MilestoneEntry wfEntry : planDual1.discordWorldFirst().items()) {
                assertTrue(DiscordNotifier.notifyMilestoneToDestination(
                        true, null, "Alice", wfEntry.target(), TargetKey::formatDisplayId,
                        wfEntry.record(), wfEntry.worldFirst(),
                        ZoneOffset.UTC, useEmbeds, "en_us", true, wfDest,
                        (d, u, j) -> sent.add(new Sent(d, u, j))));
            }
            assertEquals(3, sent.size());
            assertEquals(DiscordRouting.Destination.MAIN, sent.get(0).dest());
            assertEquals(DiscordNotifier.SUPPRESS_NOTIFICATIONS,
                    JsonParser.parseString(sent.get(0).json()).getAsJsonObject().get("flags").getAsInt());
            assertEquals(DiscordRouting.Destination.WORLD_FIRST, sent.get(1).dest());
            assertFalse(JsonParser.parseString(sent.get(1).json()).getAsJsonObject().has("flags"));
            assertEquals(DiscordRouting.Destination.WORLD_FIRST, sent.get(2).dest());
            assertFalse(JsonParser.parseString(sent.get(2).json()).getAsJsonObject().has("flags"));

            // 4. F27 + F46 batch (2 normal + 3 world-first): MAIN gets 2 individual posts (both flags: 4096),
            //    WORLD_FIRST gets 1 grouped post (NO flags).
            List<DiscordNotifier.MilestoneEntry> batch2Normal3Wf = List.of(
                    new DiscordNotifier.MilestoneEntry(coal, rec, false),
                    new DiscordNotifier.MilestoneEntry(iron, rec, false),
                    new DiscordNotifier.MilestoneEntry(diamond, rec, true),
                    new DiscordNotifier.MilestoneEntry(emerald, rec, true),
                    new DiscordNotifier.MilestoneEntry(netherite, rec, true));
            NotificationGrouping.FlushPlan<DiscordNotifier.MilestoneEntry> planDual2 = NotificationGrouping.planFlush(
                    batch2Normal3Wf, batch2Normal3Wf,
                    DiscordNotifier.MilestoneEntry::worldFirst,
                    Comparator.comparing(DiscordNotifier.MilestoneEntry::target),
                    false, false, false, false, true, "all", true, true);
            assertEquals(NotificationGrouping.SendMode.INDIVIDUAL, planDual2.discord().mode());
            assertEquals(NotificationGrouping.SendMode.GROUPED, planDual2.discordWorldFirst().mode());

            sent.clear();
            for (DiscordNotifier.MilestoneEntry normalEntry : planDual2.discord().items()) {
                assertTrue(DiscordNotifier.notifyMilestoneToDestination(
                        true, null, "Alice", normalEntry.target(), TargetKey::formatDisplayId,
                        normalEntry.record(), normalEntry.worldFirst(),
                        ZoneOffset.UTC, useEmbeds, "en_us", true, mainDest,
                        (d, u, j) -> sent.add(new Sent(d, u, j))));
            }
            assertTrue(DiscordNotifier.notifyMilestonesGroupedToDestination(
                    true, null, "Alice", planDual2.discordWorldFirst().items(),
                    ZoneOffset.UTC, useEmbeds, "en_us", true, TargetKey::formatDisplayId, wfDest,
                    (d, u, j) -> sent.add(new Sent(d, u, j))));

            assertEquals(3, sent.size());
            assertEquals(DiscordRouting.Destination.MAIN, sent.get(0).dest());
            assertEquals(DiscordNotifier.SUPPRESS_NOTIFICATIONS,
                    JsonParser.parseString(sent.get(0).json()).getAsJsonObject().get("flags").getAsInt());
            assertEquals(DiscordRouting.Destination.MAIN, sent.get(1).dest());
            assertEquals(DiscordNotifier.SUPPRESS_NOTIFICATIONS,
                    JsonParser.parseString(sent.get(1).json()).getAsJsonObject().get("flags").getAsInt());
            assertEquals(DiscordRouting.Destination.WORLD_FIRST, sent.get(2).dest());
            assertFalse(JsonParser.parseString(sent.get(2).json()).getAsJsonObject().has("flags"));

            // 5. Same mixed batch WITHOUT F46 worldFirstUrl: MAIN gets 1 mixed grouped post -> NO flags.
            NotificationGrouping.FlushPlan<DiscordNotifier.MilestoneEntry> planSingleUrl = NotificationGrouping.planFlush(
                    batch2Normal3Wf, batch2Normal3Wf,
                    DiscordNotifier.MilestoneEntry::worldFirst,
                    Comparator.comparing(DiscordNotifier.MilestoneEntry::target),
                    false, false, false, false, true, "all", true, false);
            assertEquals(NotificationGrouping.SendMode.GROUPED, planSingleUrl.discord().mode());
            assertEquals(NotificationGrouping.SendMode.NONE, planSingleUrl.discordWorldFirst().mode());

            sent.clear();
            assertTrue(DiscordNotifier.notifyMilestonesGroupedToDestination(
                    true, null, "Alice", planSingleUrl.discord().items(),
                    ZoneOffset.UTC, useEmbeds, "en_us", true, TargetKey::formatDisplayId, mainDest,
                    (d, u, j) -> sent.add(new Sent(d, u, j))));
            assertEquals(1, sent.size());
            assertFalse(JsonParser.parseString(sent.get(0).json()).getAsJsonObject().has("flags"));
        }
    }

    @Test
    void shutdownCancelsHttpClientOperationsBeforeTheBlockingClose() throws Exception {
        FakeHttpClient fake = new FakeHttpClient();
        Field field = DiscordNotifier.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(null, fake);

        long start = System.nanoTime();
        DiscordNotifier.shutdown();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(fake.shutdownNowCalled.get(), "shutdown must call httpClient.shutdownNow()");
        assertTrue(fake.closeCalled.get(), "shutdown must still close the client");
        assertTrue(fake.closeSawShutdownNow.get(), "close must run after cancellation");
        assertTrue(elapsedMillis < 500L,
                "shutdown must not block on a hung HttpClient.close(): " + elapsedMillis + " ms");
    }

    /**
     * Mimics a Java 21+ HttpClient whose close() blocks until the client is shut down. If shutdown()
     * calls close() without shutdownNow() first, this takes two seconds and the test fails.
     */
    private static final class FakeHttpClient extends HttpClient {
        private final java.util.concurrent.atomic.AtomicBoolean shutdownNowCalled =
                new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicBoolean closeCalled =
                new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicBoolean closeSawShutdownNow =
                new java.util.concurrent.atomic.AtomicBoolean();

        @Override
        public void shutdownNow() {
            shutdownNowCalled.set(true);
        }

        @Override
        public void close() {
            closeCalled.set(true);
            if (!shutdownNowCalled.get()) {
                try {
                    Thread.sleep(2_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            closeSawShutdownNow.set(true);
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException("fake client");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException("fake client");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException("fake client");
        }
    }

    private static void assertField(JsonObject field, String expectedName, String expectedValue) {
        assertEquals(expectedName, field.get("name").getAsString());
        assertEquals(expectedValue, field.get("value").getAsString());
        assertTrue(field.get("inline").getAsBoolean());
    }

    // SF-10: a bounded-queue rejection must be counted, not silently discarded.
    @Test
    void queueFullRejectionsAreCountedAsDroppedPosts() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Runnable blocked = () -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        DiscordNotifier.resetDropCountForTest();
        try {
            // The single worker takes one post; the bounded queue holds 100 more. Everything
            // beyond that is rejected by the queue-full handler and must be accounted for.
            for (int i = 0; i < 150; i++) {
                DiscordNotifier.submitPostForTest(blocked);
            }
            assertTrue(DiscordNotifier.droppedPostCount() >= 40,
                    "queue-full rejections must be counted: " + DiscordNotifier.droppedPostCount());
        } finally {
            release.countDown();
        }
    }

    // SF-10: undelivered posts cancelled at stop time must be counted as drops.
    @Test
    void shutdownWithAFullQueueCountsTheDiscardedPosts() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Runnable blocked = () -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        DiscordNotifier.resetDropCountForTest();
        try {
            for (int i = 0; i < 50; i++) {
                DiscordNotifier.submitPostForTest(blocked);
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            // Zero window: stop must not wait when there is no delivery chance left.
            DiscordNotifier.shutdown(0L);
            assertTrue(DiscordNotifier.droppedPostCount() >= 40,
                    "shutdown must count every queued post it discards: " + DiscordNotifier.droppedPostCount());
        } finally {
            release.countDown();
        }
    }

    // R2-SF-2: the queue-full handler must report an ABANDONED result for a discarded incoming test.
    @Test
    void queueFullDiscardOfAnIncomingTestReportsAbandoned() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Runnable blocked = () -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        List<DiscordTestResult> abandoned = new ArrayList<>();
        DiscordNotifier.resetTestInProgress();
        DiscordNotifier.resetDropCountForTest();
        try {
            DiscordNotifier.submitPostForTest(blocked); // the single worker takes this one
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            // The oldest queued post is a test task: the handler re-queues it and discards the
            // incoming test task instead of letting its callback vanish.
            DiscordNotifier.enqueueTestTaskForTest(() -> {}, r -> {});
            for (int i = 0; i < 99; i++) {
                DiscordNotifier.submitPostForTest(blocked);
            }
            assertNull(DiscordNotifier.sendTestMessage(
                    DiscordRouting.Destination.MAIN, SECRET_WEBHOOK_1, key -> "", false, "en_us",
                    Instant.ofEpochMilli(EPOCH_2023_11_14),
                    r -> {
                        throw new AssertionError("a discarded test must never complete normally");
                    },
                    abandoned::add));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (abandoned.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(1, abandoned.size(), "the discarded test must report abandonment");
            assertEquals(DiscordTestResult.Kind.ABANDONED, abandoned.get(0).kind());
            assertFalse(DiscordNotifier.isTestInProgress(), "the abandoned task must release the slot");
        } finally {
            release.countDown();
        }
    }

    // SF-10: the flushed batch really gets the bounded window to deliver before shutdownNow.
    @Test
    void flushedBatchIsDeliveredInsideTheBoundedStopWindow() {
        DiscordNotifier.resetDropCountForTest();
        int posts = 20;
        AtomicInteger delivered = new AtomicInteger();
        for (int i = 0; i < posts; i++) {
            DiscordNotifier.submitPostForTest(delivered::incrementAndGet);
        }
        long start = System.nanoTime();
        DiscordNotifier.shutdown(DiscordNotifier.STOP_DELIVERY_WINDOW_MILLIS);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertEquals(posts, delivered.get(), "the flushed batch must be delivered during the window");
        assertEquals(0, DiscordNotifier.droppedPostCount(), "nothing should be dropped inside the window");
        assertTrue(elapsedMillis < DiscordNotifier.STOP_DELIVERY_WINDOW_MILLIS,
                "the stop window must stay bounded: " + elapsedMillis + " ms");
    }


}
