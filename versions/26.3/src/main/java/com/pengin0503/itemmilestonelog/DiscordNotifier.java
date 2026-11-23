package com.pengin0503.itemmilestonelog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;

import javax.annotation.Nullable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Asynchronous Discord webhook sender for milestone notifications.
 *
 * <p>Uses a dedicated single-thread executor and {@link HttpClient} so server ticks
 * are never blocked. The configured webhook URL is treated as a secret and is never
 * printed to logs, chat, or exception messages.
 */
public final class DiscordNotifier {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int QUEUE_CAPACITY = 100;

    static final int COLOR_NORMAL = 0x3498DB;
    static final int COLOR_WORLD_FIRST = 0xF1C40F;
    static final int COLOR_PRESET_COMPLETED = 0x2ECC71;

    static final int EMBED_TITLE_MAX_CHARS = 256;
    static final int EMBED_FIELD_NAME_MAX_CHARS = 256;
    static final int EMBED_FIELD_VALUE_MAX_CHARS = 1024;
    static final int EMBED_FOOTER_MAX_CHARS = 2048;
    static final int EMBED_DESCRIPTION_MAX_CHARS = NotificationGrouping.DISCORD_EMBED_DESCRIPTION_MAX_CHARS;
    static final int PLAIN_CONTENT_MAX_CHARS = NotificationGrouping.DISCORD_PLAIN_MAX_CHARS;

    /** Single milestone record entry passed to grouped Discord notifications (F27). */
    public record MilestoneEntry(TargetKey target, MilestoneRecord record, boolean worldFirst) {
    }

    record HttpPostResult(int statusCode, @Nullable String retryAfterHeader) {
    }

    @FunctionalInterface
    interface HttpPostTransport {
        HttpPostResult post(URI uri, String json) throws Exception;
    }

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private static final DiscordDeliveryPolicy.DeadWebhookTracker DEAD_WEBHOOKS =
            new DiscordDeliveryPolicy.DeadWebhookTracker();
    private static final DiscordWebhookUrl.EnvWarnTracker ENV_WARNINGS =
            new DiscordWebhookUrl.EnvWarnTracker();
    private static final AtomicBoolean TEST_IN_PROGRESS = new AtomicBoolean(false);

    private static ExecutorService executor;
    private static HttpClient httpClient;

    private DiscordNotifier() {
    }

    /**
     * Shared decision function for per-player Discord opt-out (F45).
     *
     * <p>Combines {@link Config#ALLOW_DISCORD_OPT_OUT} with the player's
     * {@link MilestoneData.PlayerData#excludeFromDiscord} preference. Every Discord send site
     * for player records (single posts, F27 grouped queueing/flushing, and F19 preset
     * completion posts) must gate on this function.
     */
    public static boolean shouldPostToDiscord(@Nullable MilestoneData.PlayerData playerData) {
        return shouldPostToDiscord(Config.ALLOW_DISCORD_OPT_OUT.get(), playerData);
    }

    /**
     * Shared decision function for per-player Discord opt-out (F45) by player UUID.
     * Does not create {@link MilestoneData.PlayerData} if absent.
     */
    public static boolean shouldPostToDiscord(@Nullable MilestoneData data, @Nullable UUID playerId) {
        return shouldPostToDiscord(Config.ALLOW_DISCORD_OPT_OUT.get(), data, playerId);
    }

    static boolean shouldPostToDiscord(boolean allowDiscordOptOut, @Nullable MilestoneData.PlayerData playerData) {
        return DiscordDeliveryPolicy.shouldPostToDiscord(
                allowDiscordOptOut,
                playerData != null && playerData.excludeFromDiscord);
    }

    static boolean shouldPostToDiscord(
            boolean allowDiscordOptOut,
            @Nullable MilestoneData data,
            @Nullable UUID playerId) {
        MilestoneData.PlayerData pd = (data != null && playerId != null) ? data.playerOrNull(playerId) : null;
        return shouldPostToDiscord(allowDiscordOptOut, pd);
    }

    public static void notifyMilestone(
            @Nullable MilestoneData.PlayerData playerData,
            String playerName,
            TargetKey target,
            MilestoneRecord record,
            boolean worldFirst) {
        if (!shouldPostToDiscord(playerData)) {
            return;
        }
        String scope = Config.DISCORD_NOTIFY_SCOPE.get();
        if ("world_first".equals(scope) && !worldFirst) {
            return;
        }
        URI uri = resolveWebhookUri();
        if (uri == null) {
            return;
        }

        boolean useEmbeds = Config.DISCORD_USE_EMBEDS.get();
        String language = Config.DISCORD_LANGUAGE.get();
        ZoneId zone = Config.displayTimeZone();
        String itemDisplayName = useEmbeds ? serverItemDisplayName(target) : null;
        String json = buildSinglePayloadJson(
                playerName, target, itemDisplayName, record, worldFirst, zone, useEmbeds, language);
        if (json.isEmpty()) {
            return;
        }
        enqueueSend(uri, json);
    }

    /**
     * Sends a single grouped Discord notification for multiple simultaneous milestone records (F27).
     * {@code entries} must already be filtered according to {@code discordNotifyScope} and sorted
     * in {@link TargetKey} order.
     */
    public static void notifyMilestonesGrouped(
            @Nullable MilestoneData.PlayerData playerData,
            String playerName,
            List<MilestoneEntry> entries) {
        if (!shouldPostToDiscord(playerData)) {
            return;
        }
        if (entries == null || entries.isEmpty()) {
            return;
        }
        URI uri = resolveWebhookUri();
        if (uri == null) {
            return;
        }

        boolean useEmbeds = Config.DISCORD_USE_EMBEDS.get();
        String language = Config.DISCORD_LANGUAGE.get();
        ZoneId zone = Config.displayTimeZone();
        String json = buildGroupedPayloadJson(
                playerName, entries, zone, useEmbeds, language, DiscordNotifier::serverItemDisplayName);
        if (json.isEmpty()) {
            return;
        }
        enqueueSend(uri, json);
    }

    /**
     * Posts a preset completion notification to Discord (F19) when
     * {@link Config#DISCORD_NOTIFY_PRESET_COMPLETION} is enabled and a webhook is configured.
     * Independent of {@code announcePresetCompletion} and {@code discordNotifyScope};
     * respects per-player opt-out via {@link #shouldPostToDiscord(MilestoneData.PlayerData)} (F45).
     */
    public static void notifyPresetCompletion(
            @Nullable MilestoneData.PlayerData playerData,
            String playerName,
            @Nullable Presets.PresetDefinition preset,
            long epochMillis) {
        if (!Config.DISCORD_NOTIFY_PRESET_COMPLETION.get()) {
            return;
        }
        notifyPresetCompletion(
                true,
                Config.ALLOW_DISCORD_OPT_OUT.get(),
                playerData,
                Config.DISCORD_WEBHOOK_URL.get(),
                System::getenv,
                playerName,
                preset,
                epochMillis,
                Config.DISCORD_USE_EMBEDS.get(),
                Config.DISCORD_LANGUAGE.get(),
                msg -> ItemMilestoneLog.LOGGER.warn(msg),
                DiscordNotifier::enqueueSend);
    }

    /**
     * Testable dispatch helper for F19 preset completion notifications.
     *
     * @return {@code true} if a message was handed to {@code sender}, {@code false} if suppressed
     */
    static boolean notifyPresetCompletion(
            boolean notifyPresetCompletion,
            boolean allowDiscordOptOut,
            @Nullable MilestoneData.PlayerData playerData,
            @Nullable String configuredUrl,
            Function<String, String> envLookup,
            String playerName,
            @Nullable Presets.PresetDefinition preset,
            long epochMillis,
            boolean useEmbeds,
            String language,
            Consumer<String> warnLogger,
            BiConsumer<URI, String> sender) {
        if (!notifyPresetCompletion || preset == null) {
            return false;
        }
        if (!shouldPostToDiscord(allowDiscordOptOut, playerData)) {
            return false;
        }
        URI uri = resolveWebhookUri(configuredUrl, envLookup, warnLogger);
        if (uri == null) {
            return false;
        }
        String json = buildPresetCompletionPayloadJson(playerName, preset, epochMillis, useEmbeds, language);
        if (json.isEmpty()) {
            return false;
        }
        sender.accept(uri, json);
        return true;
    }

    private static String serverItemDisplayName(TargetKey target) {
        String name = MilestoneService.targetDisplayName(target).getString();
        return (name == null || name.isEmpty()) ? target.formatDisplayId() : name;
    }

    /**
     * Pure payload builder for a single milestone Discord notification (F4 + F18).
     */
    static String buildSinglePayloadJson(
            String playerName,
            TargetKey target,
            @Nullable String itemDisplayName,
            MilestoneRecord record,
            boolean worldFirst,
            ZoneId zone,
            boolean useEmbeds,
            String language) {
        if (target == null || record == null) {
            return "";
        }
        if (useEmbeds) {
            String displayName = (itemDisplayName != null && !itemDisplayName.isEmpty())
                    ? itemDisplayName
                    : target.formatDisplayId();
            return buildSingleEmbedJson(playerName, displayName, record, worldFirst, zone, language);
        }
        return buildPlainTextJson(buildSinglePlainTextContent(playerName, target, record, worldFirst, zone, language));
    }

    static String buildSinglePlainTextContent(
            String playerName,
            TargetKey target,
            MilestoneRecord record,
            boolean worldFirst,
            ZoneId zone,
            String language) {
        String escapedName = TextEscaping.escapeDiscordMarkdown(playerName != null ? playerName : "");
        String escapedItem = TextEscaping.escapeDiscordMarkdown(target.formatDisplayId());
        String formattedTime = Playtime.formatRecord(record, zone);
        String key = worldFirst
                ? "discord.itemmilestonelog.world_first"
                : "discord.itemmilestonelog.recorded";
        String content = ServerLang.formatForLang(language, key, escapedName, escapedItem, formattedTime);
        return TextEscaping.truncateUtf16(content, PLAIN_CONTENT_MAX_CHARS);
    }

    static String buildSingleEmbedJson(
            String playerName,
            String itemDisplayName,
            MilestoneRecord record,
            boolean worldFirst,
            ZoneId zone,
            String language) {
        String safeName = playerName != null ? playerName : "";
        String safeItem = itemDisplayName != null ? itemDisplayName : "";
        String escapedName = TextEscaping.escapeDiscordMarkdown(safeName);
        String escapedItem = TextEscaping.escapeDiscordMarkdown(safeItem);

        String title = TextEscaping.truncateUtf16(safeItem, EMBED_TITLE_MAX_CHARS);
        String descKey = worldFirst
                ? "discord.itemmilestonelog.embed.world_first"
                : "discord.itemmilestonelog.embed.recorded";
        String description = TextEscaping.truncateUtf16(
                ServerLang.formatForLang(language, descKey, escapedName, escapedItem),
                EMBED_DESCRIPTION_MAX_CHARS);
        int color = worldFirst ? COLOR_WORLD_FIRST : COLOR_NORMAL;

        JsonObject embed = buildRecordEmbed(title, description, color, safeName, record, zone, language);
        return buildEmbedsJson(embed);
    }

    /**
     * Pure payload builder for grouped Discord notifications (F27), defaulting to plain-text mode.
     */
    static String buildGroupedPayloadJson(String playerName, List<MilestoneEntry> entries, ZoneId zone) {
        return buildGroupedPayloadJson(
                playerName, entries, zone, false, ServerLang.DEFAULT_LANG, TargetKey::formatDisplayId);
    }

    /**
     * Pure payload builder for grouped Discord notifications (F4 + F18 + F27).
     */
    static String buildGroupedPayloadJson(
            String playerName,
            List<MilestoneEntry> entries,
            ZoneId zone,
            boolean useEmbeds,
            String language,
            Function<TargetKey, String> itemDisplayNameResolver) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        if (useEmbeds) {
            return buildGroupedEmbedJson(playerName, entries, zone, language, itemDisplayNameResolver);
        }
        String content = buildGroupedPlainTextContent(playerName, entries, zone, language);
        if (content.isEmpty()) {
            return "";
        }
        return buildPlainTextJson(content);
    }

    static String buildGroupedPlainTextContent(
            String playerName,
            List<MilestoneEntry> entries,
            ZoneId zone,
            String language) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        String escapedName = TextEscaping.escapeDiscordMarkdown(playerName != null ? playerName : "");
        String formattedTime = Playtime.formatRecord(entries.get(0).record(), zone);
        String header = ServerLang.formatForLang(
                language, "discord.itemmilestonelog.recorded_group", escapedName, entries.size(), formattedTime);
        List<String> itemLines = new ArrayList<>(entries.size());
        for (MilestoneEntry entry : entries) {
            String escapedItem = TextEscaping.escapeDiscordMarkdown(entry.target().formatDisplayId());
            String lineKey = entry.worldFirst()
                    ? "discord.itemmilestonelog.group_line_world_first"
                    : "discord.itemmilestonelog.group_line";
            itemLines.add(ServerLang.formatForLang(language, lineKey, escapedItem));
        }
        return NotificationGrouping.assembleDiscordBody(
                header,
                itemLines,
                remaining -> ServerLang.formatForLang(language, "discord.itemmilestonelog.digest.more", remaining),
                NotificationGrouping.DISCORD_MAX_LINES,
                PLAIN_CONTENT_MAX_CHARS);
    }

    static String buildGroupedEmbedJson(
            String playerName,
            List<MilestoneEntry> entries,
            ZoneId zone,
            String language,
            Function<TargetKey, String> itemDisplayNameResolver) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        String safeName = playerName != null ? playerName : "";
        Function<TargetKey, String> resolver = itemDisplayNameResolver != null
                ? itemDisplayNameResolver
                : TargetKey::formatDisplayId;

        boolean anyWorldFirst = false;
        List<String> itemLines = new ArrayList<>(entries.size());
        for (MilestoneEntry entry : entries) {
            if (entry.worldFirst()) {
                anyWorldFirst = true;
            }
            String resolved = resolver.apply(entry.target());
            String displayName = (resolved != null && !resolved.isEmpty())
                    ? resolved
                    : entry.target().formatDisplayId();
            String escapedItem = TextEscaping.escapeDiscordMarkdown(displayName);
            String lineKey = entry.worldFirst()
                    ? "discord.itemmilestonelog.group_line_world_first"
                    : "discord.itemmilestonelog.group_line";
            itemLines.add(ServerLang.formatForLang(language, lineKey, escapedItem));
        }

        String title = TextEscaping.truncateUtf16(
                ServerLang.formatForLang(language, "discord.itemmilestonelog.embed.group_title",
                        safeName, entries.size()),
                EMBED_TITLE_MAX_CHARS);
        String description = NotificationGrouping.assembleDiscordBody(
                null,
                itemLines,
                remaining -> ServerLang.formatForLang(language, "discord.itemmilestonelog.digest.more", remaining),
                NotificationGrouping.DISCORD_MAX_LINES,
                EMBED_DESCRIPTION_MAX_CHARS);
        int color = anyWorldFirst ? COLOR_WORLD_FIRST : COLOR_NORMAL;

        JsonObject embed = buildRecordEmbed(
                title, description, color, safeName, entries.get(0).record(), zone, language);
        return buildEmbedsJson(embed);
    }

    private static JsonObject buildRecordEmbed(
            String title,
            String description,
            int color,
            String playerName,
            MilestoneRecord record,
            ZoneId zone,
            String language) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", TextEscaping.truncateUtf16(title, EMBED_TITLE_MAX_CHARS));
        embed.addProperty("description", TextEscaping.truncateUtf16(description, EMBED_DESCRIPTION_MAX_CHARS));
        embed.addProperty("color", color);

        JsonArray fields = new JsonArray();
        fields.add(buildInlineField(
                ServerLang.formatForLang(language, "discord.itemmilestonelog.field.playtime"),
                Playtime.format(record.playTicks())));
        if (record.hasDay()) {
            fields.add(buildInlineField(
                    ServerLang.formatForLang(language, "discord.itemmilestonelog.field.day"),
                    String.valueOf(record.day() + 1L)));
        }
        if (record.hasEpoch()) {
            fields.add(buildInlineField(
                    ServerLang.formatForLang(language, "discord.itemmilestonelog.field.date"),
                    Playtime.formatDate(record.epochMillis(), zone)));
        }
        embed.add("fields", fields);

        if (playerName != null && !playerName.isEmpty()) {
            JsonObject footer = new JsonObject();
            footer.addProperty("text", TextEscaping.truncateUtf16(playerName, EMBED_FOOTER_MAX_CHARS));
            embed.add("footer", footer);
        }

        if (record.hasEpoch()) {
            embed.addProperty("timestamp", Instant.ofEpochMilli(record.epochMillis()).toString());
        }
        return embed;
    }

    static JsonObject buildInlineField(String name, String value) {
        JsonObject field = new JsonObject();
        field.addProperty("name", TextEscaping.truncateUtf16(name != null ? name : "", EMBED_FIELD_NAME_MAX_CHARS));
        field.addProperty("value", TextEscaping.truncateUtf16(value != null ? value : "", EMBED_FIELD_VALUE_MAX_CHARS));
        field.addProperty("inline", true);
        return field;
    }

    static String buildPlainTextJson(String content) {
        JsonObject body = new JsonObject();
        body.addProperty("content", TextEscaping.truncateUtf16(content != null ? content : "", PLAIN_CONTENT_MAX_CHARS));
        JsonObject allowedMentions = new JsonObject();
        allowedMentions.add("parse", new JsonArray());
        body.add("allowed_mentions", allowedMentions);
        return body.toString();
    }

    static String buildEmbedsJson(JsonObject embed) {
        JsonObject body = new JsonObject();
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        body.add("embeds", embeds);
        JsonObject allowedMentions = new JsonObject();
        allowedMentions.add("parse", new JsonArray());
        body.add("allowed_mentions", allowedMentions);
        return body.toString();
    }

    /**
     * Resolves a preset's display name in {@code language} for Discord notifications (F19):
     * built-in presets look up {@code gui.itemmilestonelog.preset.<id>} in the bundled language
     * table (falling back to English), while custom presets use their JSON {@code name} (or ID
     * when absent/blank).
     */
    static String resolvePresetDisplayName(@Nullable Presets.PresetDefinition preset, @Nullable String language) {
        if (preset == null) {
            return "";
        }
        if (preset.builtin()) {
            String id = preset.id() != null ? preset.id() : "";
            return ServerLang.formatForLang(language, "gui.itemmilestonelog.preset." + id);
        }
        String displayName = preset.displayName();
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        return preset.id() != null ? preset.id() : "";
    }

    /**
     * Pure payload builder for a preset completion Discord notification (F19 + F4 + F18).
     */
    static String buildPresetCompletionPayloadJson(
            String playerName,
            @Nullable Presets.PresetDefinition preset,
            long epochMillis,
            boolean useEmbeds,
            String language) {
        if (preset == null) {
            return "";
        }
        if (useEmbeds) {
            return buildPresetCompletionEmbedJson(playerName, preset, epochMillis, language);
        }
        return buildPlainTextJson(buildPresetCompletionPlainTextContent(playerName, preset, language));
    }

    static String buildPresetCompletionPlainTextContent(
            String playerName,
            @Nullable Presets.PresetDefinition preset,
            String language) {
        if (preset == null) {
            return "";
        }
        String presetDisplayName = resolvePresetDisplayName(preset, language);
        String escapedName = TextEscaping.escapeDiscordMarkdown(playerName != null ? playerName : "");
        String escapedPreset = TextEscaping.escapeDiscordMarkdown(presetDisplayName);
        String content = ServerLang.formatForLang(
                language, "discord.itemmilestonelog.preset_completed", escapedName, escapedPreset);
        return TextEscaping.truncateUtf16(content, PLAIN_CONTENT_MAX_CHARS);
    }

    static String buildPresetCompletionEmbedJson(
            String playerName,
            @Nullable Presets.PresetDefinition preset,
            long epochMillis,
            String language) {
        if (preset == null) {
            return "";
        }
        String safeName = playerName != null ? playerName : "";
        String presetDisplayName = resolvePresetDisplayName(preset, language);
        String escapedName = TextEscaping.escapeDiscordMarkdown(safeName);
        String escapedPreset = TextEscaping.escapeDiscordMarkdown(presetDisplayName);

        String title = TextEscaping.truncateUtf16(presetDisplayName, EMBED_TITLE_MAX_CHARS);
        String description = TextEscaping.truncateUtf16(
                ServerLang.formatForLang(language, "discord.itemmilestonelog.preset_completed",
                        escapedName, escapedPreset),
                EMBED_DESCRIPTION_MAX_CHARS);

        JsonObject embed = new JsonObject();
        embed.addProperty("title", title);
        embed.addProperty("description", description);
        embed.addProperty("color", COLOR_PRESET_COMPLETED);
        if (!safeName.isEmpty()) {
            JsonObject footer = new JsonObject();
            footer.addProperty("text", TextEscaping.truncateUtf16(safeName, EMBED_FOOTER_MAX_CHARS));
            embed.add("footer", footer);
        }
        if (epochMillis > 0L) {
            embed.addProperty("timestamp", Instant.ofEpochMilli(epochMillis).toString());
        }
        return buildEmbedsJson(embed);
    }

    /**
     * Pure payload builder for {@code /milestonelog discordtest} (F49), honoring F18 language
     * and F4 embed mode.
     */
    static String buildTestPayloadJson(boolean useEmbeds, String language, @Nullable Instant timestamp) {
        String message = ServerLang.formatForLang(language, "discord.itemmilestonelog.test");
        if (!useEmbeds) {
            return buildPlainTextJson(message);
        }
        String title = ServerLang.formatForLang(language, "discord.itemmilestonelog.test.title");
        JsonObject embed = new JsonObject();
        embed.addProperty("title", TextEscaping.truncateUtf16(title, EMBED_TITLE_MAX_CHARS));
        embed.addProperty("description", TextEscaping.truncateUtf16(message, EMBED_DESCRIPTION_MAX_CHARS));
        embed.addProperty("color", COLOR_NORMAL);
        Instant sendTime = timestamp != null ? timestamp : Instant.now();
        embed.addProperty("timestamp", sendTime.toString());
        return buildEmbedsJson(embed);
    }

    /**
     * Validates the configured webhook URL and, if valid and no test send is already in flight,
     * queues a fixed test message on the shared Discord sender thread (F49).
     *
     * <p>Bypasses F35 dead-webhook suppression so admins can verify a repaired webhook; on
     * HTTP 2xx success, clears the dead-webhook stop so normal notifications resume.
     *
     * @param onComplete invoked on the Discord sender thread with the classified send result
     *                   when this method returns {@code null}
     * @return a synchronous failure result ({@code NOT_CONFIGURED}, {@code ENV_MISSING},
     *         {@code INVALID_URL}, or {@code IN_PROGRESS}) if the test was not started, or
     *         {@code null} if the test send was accepted and queued
     */
    @Nullable
    public static DiscordTestResult sendTestMessage(Consumer<DiscordTestResult> onComplete) {
        return sendTestMessage(
                Config.DISCORD_WEBHOOK_URL.get(),
                System::getenv,
                Config.DISCORD_USE_EMBEDS.get(),
                Config.DISCORD_LANGUAGE.get(),
                Instant.now(),
                onComplete);
    }

    @Nullable
    static DiscordTestResult sendTestMessage(
            @Nullable String configuredUrl,
            Function<String, String> envLookup,
            boolean useEmbeds,
            String language,
            Instant timestamp,
            Consumer<DiscordTestResult> onComplete) {
        if (TEST_IN_PROGRESS.get()) {
            return DiscordTestResult.inProgress();
        }
        DiscordWebhookUrl.Resolution resolution = DiscordWebhookUrl.resolve(configuredUrl, envLookup);
        DiscordTestResult preCheckFailure = DiscordTestResult.classifyPreCheck(resolution);
        if (preCheckFailure != null) {
            return preCheckFailure;
        }
        if (!TEST_IN_PROGRESS.compareAndSet(false, true)) {
            return DiscordTestResult.inProgress();
        }
        boolean handedOff = false;
        try {
            URI uri = resolution.uri();
            String json = buildTestPayloadJson(useEmbeds, language, timestamp);
            enqueueTestSend(uri, json, onComplete);
            handedOff = true;
            return null;
        } finally {
            if (!handedOff) {
                TEST_IN_PROGRESS.set(false);
            }
        }
    }

    @Nullable
    static DiscordTestResult sendTestMessage(
            @Nullable String configuredUrl,
            Function<String, String> envLookup,
            boolean useEmbeds,
            String language,
            Instant timestamp,
            Executor targetExecutor,
            HttpPostTransport transport,
            RetrySleeper sleeper,
            Consumer<String> warnLogger,
            Consumer<DiscordTestResult> onComplete) {
        if (TEST_IN_PROGRESS.get()) {
            return DiscordTestResult.inProgress();
        }
        DiscordWebhookUrl.Resolution resolution = DiscordWebhookUrl.resolve(configuredUrl, envLookup);
        DiscordTestResult preCheckFailure = DiscordTestResult.classifyPreCheck(resolution);
        if (preCheckFailure != null) {
            return preCheckFailure;
        }
        if (!TEST_IN_PROGRESS.compareAndSet(false, true)) {
            return DiscordTestResult.inProgress();
        }
        boolean handedOff = false;
        try {
            URI uri = resolution.uri();
            String json = buildTestPayloadJson(useEmbeds, language, timestamp);
            TestSendTask task = new TestSendTask(() -> {
                DiscordTestResult result = sendTestWithTransport(uri, json, transport, sleeper, warnLogger);
                if (result != null && onComplete != null) {
                    onComplete.accept(result);
                }
            });
            try {
                targetExecutor.execute(task);
            } catch (RuntimeException e) {
                task.cancelDropped();
                throw e;
            }
            handedOff = true;
            return null;
        } finally {
            if (!handedOff) {
                TEST_IN_PROGRESS.set(false);
            }
        }
    }

    /**
     * Clears dead-webhook suppression so sending resumes for the configured URL.
     *
     * <p>F49 seam: call when {@code /milestonelog discordtest} succeeds against a
     * previously suppressed webhook URL.
     */
    static void resetDeadWebhook() {
        DEAD_WEBHOOKS.reset();
    }

    static void resetEnvWarnings() {
        ENV_WARNINGS.reset();
    }

    static boolean isTestInProgress() {
        return TEST_IN_PROGRESS.get();
    }

    static void resetTestInProgress() {
        TEST_IN_PROGRESS.set(false);
    }

    static boolean isDeadWebhook(@Nullable String resolvedUrl) {
        return DEAD_WEBHOOKS.isDead(resolvedUrl);
    }

    /**
     * Logs a one-time warning when opening a world on an integrated server (singleplayer / LAN)
     * while a Discord webhook URL resolves, including via {@code env:} (F39).
     */
    public static void warnIfWebhookConfiguredInSingleplayer() {
        warnIfWebhookConfiguredInSingleplayer(
                Config.DISCORD_WEBHOOK_URL.get(),
                System::getenv,
                msg -> ItemMilestoneLog.LOGGER.warn(msg));
    }

    static boolean warnIfWebhookConfiguredInSingleplayer(
            @Nullable String configuredUrl,
            Function<String, String> envLookup,
            Consumer<String> warnLogger) {
        DiscordWebhookUrl.Resolution resolution = DiscordWebhookUrl.resolve(configuredUrl, envLookup);
        if (!resolution.isResolved()) {
            return false;
        }
        warnLogger.accept(DiscordWebhookUrl.SINGLEPLAYER_WEBHOOK_WARNING);
        return true;
    }

    @Nullable
    private static URI resolveWebhookUri() {
        return resolveWebhookUri(
                Config.DISCORD_WEBHOOK_URL.get(),
                System::getenv,
                msg -> ItemMilestoneLog.LOGGER.warn(msg));
    }

    @Nullable
    static URI resolveWebhookUri(@Nullable String configuredUrl, Consumer<String> warnLogger) {
        return resolveWebhookUri(configuredUrl, System::getenv, warnLogger);
    }

    @Nullable
    static URI resolveWebhookUri(
            @Nullable String configuredUrl,
            Function<String, String> envLookup,
            Consumer<String> warnLogger) {
        DiscordWebhookUrl.Resolution resolution = DiscordWebhookUrl.resolve(configuredUrl, envLookup);
        if (resolution.isEnvResolutionError()) {
            if (ENV_WARNINGS.shouldWarn(resolution)) {
                warnLogger.accept(resolution.warningMessage());
            }
            DEAD_WEBHOOKS.syncCurrentUrlAndCheckDead(null);
            return null;
        }
        ENV_WARNINGS.shouldWarn(resolution);
        if (resolution.status() == DiscordWebhookUrl.Status.EMPTY) {
            DEAD_WEBHOOKS.syncCurrentUrlAndCheckDead(null);
            return null;
        }
        if (!resolution.isResolved()) {
            DEAD_WEBHOOKS.syncCurrentUrlAndCheckDead(null);
            String warning = resolution.warningMessage();
            if (warning != null) {
                warnLogger.accept(warning);
            }
            return null;
        }
        if (DEAD_WEBHOOKS.syncCurrentUrlAndCheckDead(resolution.resolvedUrl())) {
            return null;
        }
        return resolution.uri();
    }

    private static void enqueueSend(URI uri, String json) {
        synchronized (DiscordNotifier.class) {
            ExecutorService exec = ensureExecutor();
            HttpClient client = ensureClient();
            try {
                exec.execute(() -> send(client, uri, json));
            } catch (RejectedExecutionException ignored) {
            }
        }
    }

    private static void enqueueTestSend(URI uri, String json, Consumer<DiscordTestResult> onComplete) {
        synchronized (DiscordNotifier.class) {
            ExecutorService exec = ensureExecutor();
            HttpClient client = ensureClient();
            TestSendTask task = new TestSendTask(() -> {
                DiscordTestResult result = sendTestWithTransport(
                        uri,
                        json,
                        (targetUri, payload) -> executePost(client, targetUri, payload),
                        Thread::sleep,
                        msg -> ItemMilestoneLog.LOGGER.warn(msg));
                if (result != null && onComplete != null) {
                    onComplete.accept(result);
                }
            });
            try {
                exec.execute(task);
            } catch (RuntimeException e) {
                task.cancelDropped();
                throw e;
            }
        }
    }

    private static final class TestSendTask implements Runnable {
        private final Runnable action;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private TestSendTask(Runnable action) {
            this.action = action;
        }

        @Override
        public void run() {
            try {
                action.run();
            } finally {
                release();
            }
        }

        void cancelDropped() {
            release();
        }

        private void release() {
            if (released.compareAndSet(false, true)) {
                TEST_IN_PROGRESS.set(false);
            }
        }
    }

    private static void send(HttpClient client, URI uri, String json) {
        sendWithTransport(
                uri,
                json,
                (targetUri, payload) -> executePost(client, targetUri, payload),
                Thread::sleep,
                msg -> ItemMilestoneLog.LOGGER.warn(msg));
    }

    private static HttpPostResult executePost(HttpClient client, URI uri, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
        return new HttpPostResult(response.statusCode(), retryAfter);
    }

    static void sendWithTransport(
            URI uri,
            String json,
            HttpPostTransport transport,
            RetrySleeper sleeper,
            Consumer<String> warnLogger) {
        executeSend(uri, json, false, transport, sleeper, warnLogger);
    }

    @Nullable
    static DiscordTestResult sendTestWithTransport(
            URI uri,
            String json,
            HttpPostTransport transport,
            RetrySleeper sleeper,
            Consumer<String> warnLogger) {
        return executeSend(uri, json, true, transport, sleeper, warnLogger);
    }

    @Nullable
    private static DiscordTestResult executeSend(
            URI uri,
            String json,
            boolean isTest,
            HttpPostTransport transport,
            RetrySleeper sleeper,
            Consumer<String> warnLogger) {
        String urlKey = uri.toString();
        if (!isTest && DEAD_WEBHOOKS.isDead(urlKey)) {
            return null;
        }
        try {
            HttpPostResult first = transport.post(uri, json);
            if (first.statusCode() != 429) {
                return handleTerminalStatus(urlKey, first.statusCode(), false, isTest, warnLogger);
            }
            OptionalLong retryDelayMs = DiscordDeliveryPolicy.parseRetryAfterMillis(first.retryAfterHeader());
            if (retryDelayMs.isEmpty()) {
                warnLogger.accept("[ItemMilestoneLog] Discord webhook rate limited (HTTP 429); dropping message");
                return isTest ? DiscordTestResult.classifyHttpStatus(429) : null;
            }
            long delayMs = retryDelayMs.getAsLong();
            if (delayMs > 0L) {
                sleeper.sleep(delayMs);
            } else if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            if (!isTest && DEAD_WEBHOOKS.isDead(urlKey)) {
                return null;
            }
            HttpPostResult retry = transport.post(uri, json);
            return handleTerminalStatus(urlKey, retry.statusCode(), true, isTest, warnLogger);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            // Never log e.getMessage() or the exception itself in case it embeds the webhook URL.
            String simpleName = DiscordTestResult.safeExceptionName(e);
            warnLogger.accept("[ItemMilestoneLog] Discord webhook request failed (" + simpleName + ")");
            return isTest ? DiscordTestResult.classifyException(e) : null;
        }
    }

    @Nullable
    private static DiscordTestResult handleTerminalStatus(
            String urlKey,
            int status,
            boolean wasRetry,
            boolean isTest,
            Consumer<String> warnLogger) {
        if (DiscordDeliveryPolicy.isSuccessStatus(status)) {
            if (isTest) {
                resetDeadWebhook();
            }
            return isTest ? DiscordTestResult.classifyHttpStatus(status) : null;
        }
        if (status == 429) {
            if (wasRetry) {
                warnLogger.accept("[ItemMilestoneLog] Discord webhook rate limited (HTTP 429) on retry; dropping message");
            } else {
                warnLogger.accept("[ItemMilestoneLog] Discord webhook rate limited (HTTP 429); dropping message");
            }
            return isTest ? DiscordTestResult.classifyHttpStatus(status) : null;
        }
        if (DiscordDeliveryPolicy.isDeadWebhookStatus(status)) {
            if (DEAD_WEBHOOKS.markDead(urlKey)) {
                warnLogger.accept("[ItemMilestoneLog] Discord webhook returned HTTP " + status
                        + "; webhook may be deleted or the URL is wrong; sending resumes when the config is fixed");
            }
            return isTest ? DiscordTestResult.classifyHttpStatus(status) : null;
        }
        warnLogger.accept("[ItemMilestoneLog] Discord webhook returned HTTP " + status);
        return isTest ? DiscordTestResult.classifyHttpStatus(status) : null;
    }

    private static synchronized ExecutorService ensureExecutor() {
        if (executor == null || executor.isShutdown()) {
            executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                    r -> {
                        Thread t = new Thread(r, "itemmilestonelog-discord");
                        t.setDaemon(true);
                        return t;
                    },
                    (r, e) -> {
                        if (e.isShutdown()) {
                            if (r instanceof TestSendTask testTask) {
                                testTask.cancelDropped();
                            }
                            return;
                        }
                        Runnable dropped = e.getQueue().poll();
                        if (dropped instanceof TestSendTask testTask) {
                            if (!e.getQueue().offer(testTask)) {
                                testTask.cancelDropped();
                            }
                            return;
                        }
                        e.execute(r);
                    });
        }
        return executor;
    }

    private static synchronized HttpClient ensureClient() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .build();
        }
        return httpClient;
    }

    public static synchronized void shutdown() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
        if (httpClient != null) {
            httpClient.close();
            httpClient = null;
        }
        DEAD_WEBHOOKS.reset();
        ENV_WARNINGS.reset();
        TEST_IN_PROGRESS.set(false);
    }
}
