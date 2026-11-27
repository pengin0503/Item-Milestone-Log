package com.pengin0503.itemmilestonelog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.api.WorldFirstRecord;
import net.minecraft.server.MinecraftServer;

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
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;

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
    /**
     * SF-10: bounded window given to the stop-time flush before {@code shutdownNow}. Keeping this
     * in seconds preserves the order of magnitude of the old 2 s termination wait.
     */
    static final long STOP_DELIVERY_WINDOW_MILLIS = 5_000L;

    /** SF-10: every rejected or discarded post is counted so drops are observable. */
    private static final AtomicLong DROPPED_POSTS = new AtomicLong();

    static final int COLOR_NORMAL = 0x3498DB;
    static final int COLOR_WORLD_FIRST = 0xF1C40F;
    static final int COLOR_PRESET_COMPLETED = 0x2ECC71;

    /** Discord Execute Webhook message flag: suppress push and desktop notifications (F53). */
    static final int SUPPRESS_NOTIFICATIONS = 1 << 12;

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

    @FunctionalInterface
    interface DestinationSender {
        void send(DiscordRouting.Destination destination, URI uri, String json);
    }

    /**
     * Per-destination delivery state (F46): each configured webhook destination keeps its own
     * F35 dead-webhook tracker and F39 env-warning deduplication state while sharing the single
     * sender thread, HTTP client, and payload builders.
     */
    static final class DestinationState {
        final DiscordRouting.Destination destination;
        final DiscordDeliveryPolicy.DeadWebhookTracker deadWebhooks =
                new DiscordDeliveryPolicy.DeadWebhookTracker();
        final DiscordWebhookUrl.EnvWarnTracker envWarnings =
                new DiscordWebhookUrl.EnvWarnTracker();

        DestinationState(DiscordRouting.Destination destination) {
            this.destination = destination;
        }

        void reset() {
            deadWebhooks.reset();
            envWarnings.reset();
        }
    }

    /**
     * Outcome of resolving one webhook destination: {@code resolved} indicates whether the URL
     * resolved to a valid HTTPS URI (used for F46 routing), while {@code deliverableUri} is
     * non-null only when the resolved URL is also not suppressed by F35 dead-webhook state.
     */
    record DestinationResolution(
            DiscordRouting.Destination destination,
            boolean resolved,
            @Nullable URI deliverableUri) {
        static DestinationResolution empty(DiscordRouting.Destination destination) {
            return new DestinationResolution(destination, false, null);
        }
    }

    /** Snapshot of both Discord webhook destinations for flushing a batch of records. */
    record ResolvedDestinations(
            DestinationResolution main,
            DestinationResolution worldFirst) {
        static final ResolvedDestinations NONE = new ResolvedDestinations(
                DestinationResolution.empty(DiscordRouting.Destination.MAIN),
                DestinationResolution.empty(DiscordRouting.Destination.WORLD_FIRST));
    }

    private static final DestinationState MAIN_STATE =
            new DestinationState(DiscordRouting.Destination.MAIN);
    private static final DestinationState WORLD_FIRST_STATE =
            new DestinationState(DiscordRouting.Destination.WORLD_FIRST);
    private static final AtomicBoolean TEST_IN_PROGRESS = new AtomicBoolean(false);
    private static final DiscordDigestLogic.TickThrottler DIGEST_THROTTLER =
            new DiscordDigestLogic.TickThrottler();

    /** Volatile so completion callbacks can check liveness without contending with shutdown(). */
    private static volatile ExecutorService executor;
    private static HttpClient httpClient;

    private DiscordNotifier() {
    }

    /** SF-10: total posts rejected or discarded since the last test reset. */
    static long droppedPostCount() {
        return DROPPED_POSTS.get();
    }

    /**
     * F-150-53 (N147): read-only snapshot of the private bounded send queue for {@code admin debug}.
     * Reads the existing executor's queue size only; no metric infrastructure, event subscription,
     * enqueue, flush or state change is introduced. Returns 0 before the sender is started.
     */
    static int pendingSendCount() {
        ExecutorService exec = executor;
        return exec instanceof ThreadPoolExecutor pool ? pool.getQueue().size() : 0;
    }

    static void resetDropCountForTest() {
        DROPPED_POSTS.set(0);
    }

    private static void noteDropped(String reason, int count) {
        if (count <= 0) {
            return;
        }
        long total = DROPPED_POSTS.addAndGet(count);
        ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Discord dropped {} post(s) ({}; total dropped: {})",
                count, reason, total);
    }

    /** MN-3: false once the sender has been shut down (or was never started). */
    static boolean isExecutorAlive() {
        ExecutorService exec = executor;
        return exec != null && !exec.isShutdown() && !exec.isTerminated();
    }

    static DestinationState stateFor(DiscordRouting.Destination destination) {
        return destination == DiscordRouting.Destination.WORLD_FIRST ? WORLD_FIRST_STATE : MAIN_STATE;
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

    /**
     * F53: returns {@code true} iff a single milestone record post should emit
     * {@code "flags": SUPPRESS_NOTIFICATIONS} (normal records only; never world firsts).
     */
    static boolean shouldSuppressRecordNotifications(boolean silentRecords, boolean worldFirst) {
        return silentRecords && !worldFirst;
    }

    /**
     * F53: returns {@code true} iff a grouped milestone batch (F27) contains no world first and
     * should emit {@code "flags": SUPPRESS_NOTIFICATIONS}.
     */
    static boolean shouldSuppressGroupedNotifications(
            boolean silentRecords,
            @Nullable List<MilestoneEntry> entries) {
        if (!silentRecords || entries == null || entries.isEmpty()) {
            return false;
        }
        for (MilestoneEntry entry : entries) {
            if (entry != null && entry.worldFirst()) {
                return false;
            }
        }
        return true;
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
        notifyMilestone(
                Config.ALLOW_DISCORD_OPT_OUT.get(),
                playerData,
                Config.DISCORD_WEBHOOK_URL.get(),
                Config.discordWorldFirstWebhookUrl(),
                System::getenv,
                Config.DISCORD_NOTIFY_SCOPE.get(),
                playerName,
                target,
                DiscordNotifier::serverItemDisplayName,
                record,
                worldFirst,
                Config.displayTimeZone(),
                Config.DISCORD_USE_EMBEDS.get(),
                Config.DISCORD_LANGUAGE.get(),
                Config.discordSilentRecords(),
                msg -> ItemMilestoneLog.LOGGER.warn(msg),
                DiscordNotifier::enqueueSend);
    }

    /**
     * Testable F46-routed single milestone dispatch helper (defaults {@code silentRecords} to {@code false}).
     *
     * @return {@code true} if a message was handed to {@code sender}, {@code false} if suppressed
     */
    static boolean notifyMilestone(
            boolean allowDiscordOptOut,
            @Nullable MilestoneData.PlayerData playerData,
            @Nullable String mainConfiguredUrl,
            @Nullable String worldFirstConfiguredUrl,
            Function<String, String> envLookup,
            String notifyScope,
            String playerName,
            TargetKey target,
            @Nullable Function<TargetKey, String> itemDisplayNameResolver,
            MilestoneRecord record,
            boolean worldFirst,
            ZoneId zone,
            boolean useEmbeds,
            String language,
            Consumer<String> warnLogger,
            DestinationSender sender) {
        return notifyMilestone(
                allowDiscordOptOut,
                playerData,
                mainConfiguredUrl,
                worldFirstConfiguredUrl,
                envLookup,
                notifyScope,
                playerName,
                target,
                itemDisplayNameResolver,
                record,
                worldFirst,
                zone,
                useEmbeds,
                language,
                false,
                warnLogger,
                sender);
    }

    /**
     * Testable F46 + F53 single milestone dispatch helper.
     *
     * @return {@code true} if a message was handed to {@code sender}, {@code false} if suppressed
     */
    static boolean notifyMilestone(
            boolean allowDiscordOptOut,
            @Nullable MilestoneData.PlayerData playerData,
            @Nullable String mainConfiguredUrl,
            @Nullable String worldFirstConfiguredUrl,
            Function<String, String> envLookup,
            String notifyScope,
            String playerName,
            TargetKey target,
            @Nullable Function<TargetKey, String> itemDisplayNameResolver,
            MilestoneRecord record,
            boolean worldFirst,
            ZoneId zone,
            boolean useEmbeds,
            String language,
            boolean silentRecords,
            Consumer<String> warnLogger,
            DestinationSender sender) {
        if (!shouldPostToDiscord(allowDiscordOptOut, playerData)) {
            return false;
        }
        if (!worldFirst && "world_first".equals(notifyScope)) {
            return false;
        }
        DestinationResolution wfRes = worldFirst
                ? resolveDestination(DiscordRouting.Destination.WORLD_FIRST, worldFirstConfiguredUrl, envLookup, warnLogger)
                : DestinationResolution.empty(DiscordRouting.Destination.WORLD_FIRST);
        DestinationResolution mainRes = (!worldFirst || !wfRes.resolved())
                ? resolveDestination(DiscordRouting.Destination.MAIN, mainConfiguredUrl, envLookup, warnLogger)
                : DestinationResolution.empty(DiscordRouting.Destination.MAIN);
        DiscordRouting.Destination dest = DiscordRouting.routeRecord(
                worldFirst, mainRes.resolved(), wfRes.resolved(), notifyScope);
        if (dest == null) {
            return false;
        }
        URI uri = dest == DiscordRouting.Destination.WORLD_FIRST
                ? wfRes.deliverableUri()
                : mainRes.deliverableUri();
        if (uri == null) {
            return false;
        }
        String itemDisplayName = useEmbeds && itemDisplayNameResolver != null
                ? itemDisplayNameResolver.apply(target)
                : null;
        boolean suppressNotifications = shouldSuppressRecordNotifications(silentRecords, worldFirst);
        String json = buildSinglePayloadJson(
                playerName, target, itemDisplayName, record, worldFirst, zone, useEmbeds, language,
                suppressNotifications);
        if (json.isEmpty()) {
            return false;
        }
        sender.send(dest, uri, json);
        return true;
    }

    /**
     * Sends a single milestone notification directly to a pre-resolved F46 destination
     * (used when flushing individual items from a pre-routed F27 batch).
     */
    static void notifyMilestoneToDestination(
            @Nullable MilestoneData.PlayerData playerData,
            String playerName,
            TargetKey target,
            MilestoneRecord record,
            boolean worldFirst,
            @Nullable DestinationResolution destination) {
        if (!shouldPostToDiscord(playerData) || destination == null || destination.deliverableUri() == null) {
            return;
        }
        notifyMilestoneToDestination(
                Config.ALLOW_DISCORD_OPT_OUT.get(),
                playerData,
                playerName,
                target,
                DiscordNotifier::serverItemDisplayName,
                record,
                worldFirst,
                Config.displayTimeZone(),
                Config.DISCORD_USE_EMBEDS.get(),
                Config.DISCORD_LANGUAGE.get(),
                Config.discordSilentRecords(),
                destination,
                DiscordNotifier::enqueueSend);
    }

    /**
     * Testable F46 + F53 helper for sending an individual record to a pre-resolved destination.
     *
     * @return {@code true} if a message was handed to {@code sender}, {@code false} if suppressed
     */
    static boolean notifyMilestoneToDestination(
            boolean allowDiscordOptOut,
            @Nullable MilestoneData.PlayerData playerData,
            String playerName,
            TargetKey target,
            @Nullable Function<TargetKey, String> itemDisplayNameResolver,
            MilestoneRecord record,
            boolean worldFirst,
            ZoneId zone,
            boolean useEmbeds,
            String language,
            boolean silentRecords,
            @Nullable DestinationResolution destination,
            DestinationSender sender) {
        if (!shouldPostToDiscord(allowDiscordOptOut, playerData)
                || destination == null || destination.deliverableUri() == null) {
            return false;
        }
        String itemDisplayName = useEmbeds && itemDisplayNameResolver != null
                ? itemDisplayNameResolver.apply(target)
                : null;
        boolean suppressNotifications = shouldSuppressRecordNotifications(silentRecords, worldFirst);
        String json = buildSinglePayloadJson(
                playerName, target, itemDisplayName, record, worldFirst, zone, useEmbeds, language,
                suppressNotifications);
        if (json.isEmpty()) {
            return false;
        }
        sender.send(destination.destination(), destination.deliverableUri(), json);
        return true;
    }

    /**
     * Sends a single grouped Discord notification for multiple simultaneous milestone records (F27)
     * to the primary webhook. {@code entries} must already be filtered for the destination and
     * sorted in {@link TargetKey} order.
     */
    public static void notifyMilestonesGrouped(
            @Nullable MilestoneData.PlayerData playerData,
            String playerName,
            List<MilestoneEntry> entries) {
        if (!shouldPostToDiscord(playerData) || entries == null || entries.isEmpty()) {
            return;
        }
        DestinationResolution main = resolveDestination(
                DiscordRouting.Destination.MAIN,
                Config.DISCORD_WEBHOOK_URL.get(),
                System::getenv,
                msg -> ItemMilestoneLog.LOGGER.warn(msg));
        notifyMilestonesGroupedToDestination(playerData, playerName, entries, main);
    }

    /**
     * Sends a grouped Discord notification directly to a pre-resolved F46 destination (F27 + F46).
     */
    static void notifyMilestonesGroupedToDestination(
            @Nullable MilestoneData.PlayerData playerData,
            String playerName,
            List<MilestoneEntry> entries,
            @Nullable DestinationResolution destination) {
        if (!shouldPostToDiscord(playerData)
                || entries == null || entries.isEmpty()
                || destination == null || destination.deliverableUri() == null) {
            return;
        }
        notifyMilestonesGroupedToDestination(
                Config.ALLOW_DISCORD_OPT_OUT.get(),
                playerData,
                playerName,
                entries,
                Config.displayTimeZone(),
                Config.DISCORD_USE_EMBEDS.get(),
                Config.DISCORD_LANGUAGE.get(),
                Config.discordSilentRecords(),
                DiscordNotifier::serverItemDisplayName,
                destination,
                DiscordNotifier::enqueueSend);
    }

    /**
     * Testable F27 + F46 + F53 helper for sending a grouped batch to a pre-resolved destination.
     *
     * @return {@code true} if a message was handed to {@code sender}, {@code false} if suppressed
     */
    static boolean notifyMilestonesGroupedToDestination(
            boolean allowDiscordOptOut,
            @Nullable MilestoneData.PlayerData playerData,
            String playerName,
            List<MilestoneEntry> entries,
            ZoneId zone,
            boolean useEmbeds,
            String language,
            boolean silentRecords,
            @Nullable Function<TargetKey, String> itemDisplayNameResolver,
            @Nullable DestinationResolution destination,
            DestinationSender sender) {
        if (!shouldPostToDiscord(allowDiscordOptOut, playerData)
                || entries == null || entries.isEmpty()
                || destination == null || destination.deliverableUri() == null) {
            return false;
        }
        boolean suppressNotifications = shouldSuppressGroupedNotifications(silentRecords, entries);
        String json = buildGroupedPayloadJson(
                playerName, entries, zone, useEmbeds, language, itemDisplayNameResolver, suppressNotifications);
        if (json.isEmpty()) {
            return false;
        }
        sender.send(destination.destination(), destination.deliverableUri(), json);
        return true;
    }

    /**
     * Resolves only the Discord webhook destinations that could receive records from a queued
     * F27 batch with the given normal/world-first composition and {@code discordNotifyScope}.
     */
    static ResolvedDestinations resolveDestinationsForBatch(
            boolean anyNormal,
            boolean anyWorldFirst,
            String discordScope) {
        return resolveDestinationsForBatch(
                anyNormal,
                anyWorldFirst,
                discordScope,
                Config.DISCORD_WEBHOOK_URL.get(),
                Config.discordWorldFirstWebhookUrl(),
                System::getenv,
                msg -> ItemMilestoneLog.LOGGER.warn(msg));
    }

    static ResolvedDestinations resolveDestinationsForBatch(
            boolean anyNormal,
            boolean anyWorldFirst,
            String discordScope,
            @Nullable String mainConfiguredUrl,
            @Nullable String worldFirstConfiguredUrl,
            Function<String, String> envLookup,
            Consumer<String> warnLogger) {
        DestinationResolution wf = anyWorldFirst
                ? resolveDestination(DiscordRouting.Destination.WORLD_FIRST, worldFirstConfiguredUrl, envLookup, warnLogger)
                : DestinationResolution.empty(DiscordRouting.Destination.WORLD_FIRST);
        boolean needMain = (anyNormal && !"world_first".equals(discordScope))
                || (anyWorldFirst && !wf.resolved());
        DestinationResolution main = needMain
                ? resolveDestination(DiscordRouting.Destination.MAIN, mainConfiguredUrl, envLookup, warnLogger)
                : DestinationResolution.empty(DiscordRouting.Destination.MAIN);
        return new ResolvedDestinations(main, wf);
    }

    /**
     * Posts a preset completion notification to Discord (F19) when
     * {@link Config#DISCORD_NOTIFY_PRESET_COMPLETION} is enabled and the primary webhook is configured.
     * Independent of {@code announcePresetCompletion} and {@code discordNotifyScope};
     * always uses {@link Config#DISCORD_WEBHOOK_URL} (never F46's world-first webhook) and
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
                (uri, json) -> enqueueSend(DiscordRouting.Destination.MAIN, uri, json));
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
        return buildSinglePayloadJson(
                playerName, target, itemDisplayName, record, worldFirst, zone, useEmbeds, language, false);
    }

    /**
     * Pure payload builder for a single milestone Discord notification (F4 + F18 + F53).
     * {@code suppressNotifications} only takes effect when {@code worldFirst} is {@code false}.
     */
    static String buildSinglePayloadJson(
            String playerName,
            TargetKey target,
            @Nullable String itemDisplayName,
            MilestoneRecord record,
            boolean worldFirst,
            ZoneId zone,
            boolean useEmbeds,
            String language,
            boolean suppressNotifications) {
        if (target == null || record == null) {
            return "";
        }
        boolean suppress = shouldSuppressRecordNotifications(suppressNotifications, worldFirst);
        if (useEmbeds) {
            String displayName = (itemDisplayName != null && !itemDisplayName.isEmpty())
                    ? itemDisplayName
                    : target.formatDisplayId();
            return buildSingleEmbedJson(playerName, displayName, record, worldFirst, zone, language, suppress);
        }
        return buildPlainTextJson(
                buildSinglePlainTextContent(playerName, target, record, worldFirst, zone, language),
                suppress);
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
        return buildSingleEmbedJson(playerName, itemDisplayName, record, worldFirst, zone, language, false);
    }

    static String buildSingleEmbedJson(
            String playerName,
            String itemDisplayName,
            MilestoneRecord record,
            boolean worldFirst,
            ZoneId zone,
            String language,
            boolean suppressNotifications) {
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
        return buildEmbedsJson(embed, shouldSuppressRecordNotifications(suppressNotifications, worldFirst));
    }

    /**
     * Pure payload builder for grouped Discord notifications (F27), defaulting to plain-text mode.
     */
    static String buildGroupedPayloadJson(String playerName, List<MilestoneEntry> entries, ZoneId zone) {
        return buildGroupedPayloadJson(
                playerName, entries, zone, false, ServerLang.DEFAULT_LANG, TargetKey::formatDisplayId, false);
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
            @Nullable Function<TargetKey, String> itemDisplayNameResolver) {
        return buildGroupedPayloadJson(
                playerName, entries, zone, useEmbeds, language, itemDisplayNameResolver, false);
    }

    /**
     * Pure payload builder for grouped Discord notifications (F4 + F18 + F27 + F53).
     * {@code suppressNotifications} only takes effect when {@code entries} contains no world first.
     */
    static String buildGroupedPayloadJson(
            String playerName,
            List<MilestoneEntry> entries,
            ZoneId zone,
            boolean useEmbeds,
            String language,
            @Nullable Function<TargetKey, String> itemDisplayNameResolver,
            boolean suppressNotifications) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        boolean suppress = shouldSuppressGroupedNotifications(suppressNotifications, entries);
        if (useEmbeds) {
            return buildGroupedEmbedJson(playerName, entries, zone, language, itemDisplayNameResolver, suppress);
        }
        String content = buildGroupedPlainTextContent(playerName, entries, zone, language);
        if (content.isEmpty()) {
            return "";
        }
        return buildPlainTextJson(content, suppress);
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
            @Nullable Function<TargetKey, String> itemDisplayNameResolver) {
        return buildGroupedEmbedJson(playerName, entries, zone, language, itemDisplayNameResolver, false);
    }

    static String buildGroupedEmbedJson(
            String playerName,
            List<MilestoneEntry> entries,
            ZoneId zone,
            String language,
            @Nullable Function<TargetKey, String> itemDisplayNameResolver,
            boolean suppressNotifications) {
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
        return buildEmbedsJson(embed, suppressNotifications && !anyWorldFirst);
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
        return buildPlainTextJson(content, false);
    }

    static String buildPlainTextJson(String content, boolean suppressNotifications) {
        JsonObject body = new JsonObject();
        body.addProperty("content", TextEscaping.truncateUtf16(content != null ? content : "", PLAIN_CONTENT_MAX_CHARS));
        JsonObject allowedMentions = new JsonObject();
        allowedMentions.add("parse", new JsonArray());
        body.add("allowed_mentions", allowedMentions);
        if (suppressNotifications) {
            body.addProperty("flags", SUPPRESS_NOTIFICATIONS);
        }
        return body.toString();
    }

    static String buildEmbedsJson(JsonObject embed) {
        return buildEmbedsJson(embed, false);
    }

    static String buildEmbedsJson(JsonObject embed, boolean suppressNotifications) {
        JsonObject body = new JsonObject();
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        body.add("embeds", embeds);
        JsonObject allowedMentions = new JsonObject();
        allowedMentions.add("parse", new JsonArray());
        body.add("allowed_mentions", allowedMentions);
        if (suppressNotifications) {
            body.addProperty("flags", SUPPRESS_NOTIFICATIONS);
        }
        return body.toString();
    }

    /**
     * Resolves a preset's display name in {@code language} for Discord notifications (F19):
     * built-in presets look up {@code gui.itemmilestonelog.preset.<id>} in the bundled language
     * table (falling back to English), while custom presets resolve their JSON {@code name}
     * (a plain string, or F54 per-language entries, with the ID as fallback) in {@code language}
     * (F18's {@code discordLanguage}).
     */
    static String resolvePresetDisplayName(@Nullable Presets.PresetDefinition preset, @Nullable String language) {
        if (preset == null) {
            return "";
        }
        if (preset.builtin()) {
            String id = preset.id() != null ? preset.id() : "";
            return ServerLang.formatForLang(language, "gui.itemmilestonelog.preset." + id);
        }
        return preset.resolveDisplayName(language);
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
     * F9: resets the periodic tick counter and runs one digest check at server start.
     */
    public static void checkDigestOnServerStart(@Nullable MinecraftServer server) {
        DIGEST_THROTTLER.reset();
        checkAndSendDigest(server);
    }

    /**
     * F9: called on {@code ServerTickEvent.Post}; runs a digest check roughly once per hour
     * ({@value DiscordDigestLogic#CHECK_INTERVAL_TICKS} ticks).
     */
    public static void tickDigest(@Nullable MinecraftServer server) {
        if (server == null) {
            return;
        }
        if (DIGEST_THROTTLER.tick()) {
            checkAndSendDigest(server);
        }
    }

    /**
     * F9: checks whether a periodic Discord digest should initialize its timestamp or send now.
     * Sends only to {@link Config#DISCORD_WEBHOOK_URL} (never to F46's world-first webhook).
     */
    public static void checkAndSendDigest(@Nullable MinecraftServer server) {
        if (server == null) {
            return;
        }
        if (!Config.discordDigestEnabled()) {
            return;
        }
        String configuredUrl = Config.DISCORD_WEBHOOK_URL.get();
        if (configuredUrl == null || configuredUrl.isBlank()) {
            return;
        }
        MilestoneData data = MilestoneData.get(server);
        checkAndSendDigest(
                data,
                true,
                Config.discordDigestIntervalDays(),
                configuredUrl,
                System::getenv,
                Config.DISCORD_NOTIFY_SCOPE.get(),
                Config.ALLOW_DISCORD_OPT_OUT.get(),
                Config.DISCORD_USE_EMBEDS.get(),
                Config.DISCORD_LANGUAGE.get(),
                Config.displayTimeZone(),
                System.currentTimeMillis(),
                DiscordNotifier::serverItemDisplayName,
                msg -> ItemMilestoneLog.LOGGER.warn(msg),
                (uri, json) -> enqueueSend(DiscordRouting.Destination.MAIN, uri, json));
    }

    /** Outcome of one F-150-54 manual digest attempt, also used by the future audit hook. */
    public record ManualDigestResult(int entries, boolean sent, boolean attempted) {
    }

    /**
     * Optional observer/test sink for one completed F-150-54 manual digest attempt. F-150-57
     * writes {@code DISCORD_MANUAL_DIGEST} from the server-thread command handler, where the
     * acting player's UUID is known; this sink itself stays a non-file-writing test seam.
     */
    @Nullable
    static Consumer<ManualDigestResult> manualDigestAuditSink;

    /** F-150-54: one explicit digest check from the server thread; only enabled/interval are bypassed. */
    static ManualDigestResult sendManualDigest(@Nullable MinecraftServer server) {
        if (server == null) {
            return new ManualDigestResult(0, false, false);
        }
        MilestoneData data = MilestoneData.get(server);
        long now = System.currentTimeMillis();
        ManualDigestResult result = checkAndSendDigestManual(
                data,
                false, // digestEnabled is deliberately ignored by the explicit decision
                Config.discordDigestIntervalDays(),
                Config.DISCORD_WEBHOOK_URL.get(),
                System::getenv,
                Config.DISCORD_NOTIFY_SCOPE.get(),
                Config.ALLOW_DISCORD_OPT_OUT.get(),
                Config.DISCORD_USE_EMBEDS.get(),
                Config.DISCORD_LANGUAGE.get(),
                Config.displayTimeZone(),
                now,
                DiscordNotifier::serverItemDisplayName,
                msg -> ItemMilestoneLog.LOGGER.warn(msg),
                (uri, json) -> enqueueSend(DiscordRouting.Destination.MAIN, uri, json));
        Consumer<ManualDigestResult> audit = manualDigestAuditSink;
        if (audit != null) {
            audit.accept(result);
        }
        return result;
    }

    /**
     * Testable F9 digest check and dispatch helper. Periodic behaviour is unchanged.
     *
     * @return {@code true} if a digest message was queued to {@code sender}, {@code false} otherwise
     */
    static boolean checkAndSendDigest(
            @Nullable MilestoneData data,
            boolean digestEnabled,
            int intervalDays,
            @Nullable String configuredUrl,
            Function<String, String> envLookup,
            String notifyScope,
            boolean allowDiscordOptOut,
            boolean useEmbeds,
            String language,
            ZoneId zone,
            long nowMillis,
            @Nullable Function<TargetKey, String> itemDisplayNameResolver,
            Consumer<String> warnLogger,
            BiConsumer<URI, String> sender) {
        return digestCheck(data, digestEnabled, intervalDays, configuredUrl, envLookup, notifyScope,
                allowDiscordOptOut, useEmbeds, language, zone, nowMillis, itemDisplayNameResolver,
                warnLogger, sender, false).sent();
    }

    /** F-150-54: same path as the periodic check, but the enabled/interval decision is bypassed. */
    static ManualDigestResult checkAndSendDigestManual(
            @Nullable MilestoneData data,
            boolean digestEnabled,
            int intervalDays,
            @Nullable String configuredUrl,
            Function<String, String> envLookup,
            String notifyScope,
            boolean allowDiscordOptOut,
            boolean useEmbeds,
            String language,
            ZoneId zone,
            long nowMillis,
            @Nullable Function<TargetKey, String> itemDisplayNameResolver,
            Consumer<String> warnLogger,
            BiConsumer<URI, String> sender) {
        return digestCheck(data, digestEnabled, intervalDays, configuredUrl, envLookup, notifyScope,
                allowDiscordOptOut, useEmbeds, language, zone, nowMillis, itemDisplayNameResolver,
                warnLogger, sender, true);
    }

    private static ManualDigestResult digestCheck(
            @Nullable MilestoneData data,
            boolean digestEnabled,
            int intervalDays,
            @Nullable String configuredUrl,
            Function<String, String> envLookup,
            String notifyScope,
            boolean allowDiscordOptOut,
            boolean useEmbeds,
            String language,
            ZoneId zone,
            long nowMillis,
            @Nullable Function<TargetKey, String> itemDisplayNameResolver,
            Consumer<String> warnLogger,
            BiConsumer<URI, String> sender,
            boolean explicit) {
        if (data == null || nowMillis <= 0L) {
            return new ManualDigestResult(0, false, false);
        }
        boolean hasConfiguredUrl = configuredUrl != null && !configuredUrl.isBlank();
        int clampedDays = DiscordDigestLogic.clampIntervalDays(intervalDays);
        long lastDigestEpoch = data.lastDigestEpochMillis();
        DiscordDigestLogic.CheckAction action = explicit
                ? DiscordDigestLogic.decideActionForManual(hasConfiguredUrl, lastDigestEpoch, nowMillis)
                : DiscordDigestLogic.decideAction(
                        digestEnabled, hasConfiguredUrl, lastDigestEpoch, nowMillis, clampedDays);
        if (action == DiscordDigestLogic.CheckAction.SKIP) {
            return new ManualDigestResult(0, false, false);
        }
        URI uri = resolveWebhookUri(configuredUrl, envLookup, warnLogger);
        if (uri == null) {
            return new ManualDigestResult(0, false, false);
        }
        if (action == DiscordDigestLogic.CheckAction.INIT_TIMESTAMP_ONLY) {
            data.setLastDigestEpochMillis(nowMillis);
            return new ManualDigestResult(0, false, true);
        }
        List<DiscordDigestLogic.DigestEntry> entries = extractDigestEntries(
                data, lastDigestEpoch, nowMillis, notifyScope, allowDiscordOptOut);
        if (entries.isEmpty()) {
            data.setLastDigestEpochMillis(nowMillis);
            return new ManualDigestResult(0, false, true);
        }
        String json = buildDigestPayloadJson(
                entries, clampedDays, zone, nowMillis, useEmbeds, language, itemDisplayNameResolver);
        if (json.isEmpty()) {
            data.setLastDigestEpochMillis(nowMillis);
            return new ManualDigestResult(entries.size(), false, true);
        }
        sender.accept(uri, json);
        data.setLastDigestEpochMillis(nowMillis);
        return new ManualDigestResult(entries.size(), true, true);
    }

    /**
     * Extracts qualifying F9 digest entries from {@link MilestoneData} in
     * {@code (lastDigestEpoch, nowMillis]}, delegating filtering and sorting to
     * {@link DiscordDigestLogic#extractEntries}.
     */
    static List<DiscordDigestLogic.DigestEntry> extractDigestEntries(
            @Nullable MilestoneData data,
            long lastDigestEpoch,
            long nowMillis,
            String notifyScope,
            boolean allowDiscordOptOut) {
        if (data == null) {
            return List.of();
        }
        List<DiscordDigestLogic.PlayerSnapshot> players = new ArrayList<>(data.allPlayers().size());
        for (Map.Entry<UUID, MilestoneData.PlayerData> e : data.allPlayers().entrySet()) {
            MilestoneData.PlayerData pd = e.getValue();
            if (pd == null) {
                continue;
            }
            List<DiscordDigestLogic.RecordSnapshot> records = new ArrayList<>(pd.records.size());
            for (Map.Entry<TargetKey, MilestoneRecord> r : pd.records.entrySet()) {
                TargetKey key = r.getKey();
                MilestoneRecord rec = r.getValue();
                if (key != null && rec != null) {
                    records.add(new DiscordDigestLogic.RecordSnapshot(
                            key.itemId(), key.count(), rec.playTicks(), rec.day(), rec.epochMillis()));
                }
            }
            players.add(new DiscordDigestLogic.PlayerSnapshot(
                    e.getKey(), pd.lastKnownName, pd.excludeFromDiscord, records));
        }
        List<DiscordDigestLogic.WorldFirstSnapshot> worldFirsts = new ArrayList<>(data.worldFirsts().size());
        for (WorldFirstRecord wf : data.worldFirsts().values()) {
            if (wf != null) {
                worldFirsts.add(new DiscordDigestLogic.WorldFirstSnapshot(
                        wf.itemId(), wf.count(), wf.playerUuid(), wf.playerName(),
                        wf.playTicks(), wf.day(), wf.epochMillis()));
            }
        }
        return DiscordDigestLogic.extractEntries(
                players, worldFirsts, lastDigestEpoch, nowMillis, notifyScope, allowDiscordOptOut);
    }

    /**
     * Pure payload builder for F9 periodic Discord digest notifications (F4 + F11 + F18).
     */
    static String buildDigestPayloadJson(
            List<DiscordDigestLogic.DigestEntry> entries,
            int intervalDays,
            ZoneId zone,
            long nowMillis,
            boolean useEmbeds,
            String language,
            @Nullable Function<TargetKey, String> itemDisplayNameResolver) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        int clampedDays = DiscordDigestLogic.clampIntervalDays(intervalDays);
        Function<TargetKey, String> resolver = itemDisplayNameResolver != null
                ? itemDisplayNameResolver
                : TargetKey::formatDisplayId;

        boolean anyWorldFirst = false;
        List<String> lines = new ArrayList<>(entries.size());
        for (DiscordDigestLogic.DigestEntry entry : entries) {
            if (entry.worldFirst()) {
                anyWorldFirst = true;
            }
            TargetKey key = TargetKey.of(entry.itemId(), entry.count());
            MilestoneRecord rec = new MilestoneRecord(entry.playTicks(), entry.day(), entry.epochMillis());
            String formattedPlaytime = Playtime.formatRecord(rec, zone);
            String itemLabel;
            if (useEmbeds) {
                String resolved = resolver.apply(key);
                itemLabel = (resolved != null && !resolved.isEmpty()) ? resolved : key.formatDisplayId();
            } else {
                itemLabel = key.formatDisplayId();
            }
            String escapedName = TextEscaping.escapeDiscordMarkdown(entry.playerName());
            String escapedItem = TextEscaping.escapeDiscordMarkdown(itemLabel);
            String formatted = ServerLang.formatForLang(
                    language, "discord.itemmilestonelog.digest.line", escapedName, escapedItem, formattedPlaytime);
            lines.add(entry.worldFirst() ? DiscordDigestLogic.WORLD_FIRST_PREFIX + formatted : formatted);
        }

        String title = ServerLang.formatForLang(language, "discord.itemmilestonelog.digest.title", clampedDays);
        IntFunction<String> moreFormatter = remaining ->
                ServerLang.formatForLang(language, "discord.itemmilestonelog.digest.more", remaining);

        if (useEmbeds) {
            String description = DiscordDigestLogic.assembleEmbedDescription(lines, moreFormatter);
            JsonObject embed = new JsonObject();
            embed.addProperty("title", TextEscaping.truncateUtf16(title, EMBED_TITLE_MAX_CHARS));
            embed.addProperty("description", TextEscaping.truncateUtf16(description, EMBED_DESCRIPTION_MAX_CHARS));
            embed.addProperty("color", anyWorldFirst ? COLOR_WORLD_FIRST : COLOR_NORMAL);
            if (nowMillis > 0L) {
                embed.addProperty("timestamp", Instant.ofEpochMilli(nowMillis).toString());
            }
            return buildEmbedsJson(embed);
        }
        String content = DiscordDigestLogic.assemblePlainText(title, lines, moreFormatter);
        return buildPlainTextJson(content);
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
     * Validates the configured primary webhook URL and, if valid and no test send is already in flight,
     * queues a fixed test message on the shared Discord sender thread (F49).
     *
     * <p>Bypasses F35 dead-webhook suppression so admins can verify a repaired webhook; on
     * HTTP 2xx success, clears that destination's dead-webhook stop so normal notifications resume.
     *
     * @param onComplete invoked on the Discord sender thread with the classified send result
     *                   when this method returns {@code null}
     * @return a synchronous failure result ({@code NOT_CONFIGURED}, {@code ENV_MISSING},
     *         {@code INVALID_URL}, or {@code IN_PROGRESS}) if the test was not started, or
     *         {@code null} if the test send was accepted and queued
     */
    @Nullable
    public static DiscordTestResult sendTestMessage(Consumer<DiscordTestResult> onComplete) {
        return sendTestMessage(DiscordRouting.Destination.MAIN, onComplete);
    }

    /**
     * Validates the configured webhook URL for {@code destination} ({@link DiscordRouting.Destination#MAIN}
     * for {@code /milestonelog discordtest}, {@link DiscordRouting.Destination#WORLD_FIRST} for
     * {@code /milestonelog discordtest worldfirst}) and queues a fixed test message (F49 + F46).
     */
    @Nullable
    public static DiscordTestResult sendTestMessage(
            DiscordRouting.Destination destination,
            Consumer<DiscordTestResult> onComplete) {
        return sendTestMessage(destination, onComplete, null);
    }

    /**
     * MN-3: same as above, but {@code onAbandoned} is invoked when the queued test task is discarded
     * (executor shut down or queue full) and no completion can ever be delivered.
     */
    @Nullable
    public static DiscordTestResult sendTestMessage(
            DiscordRouting.Destination destination,
            Consumer<DiscordTestResult> onComplete,
            @Nullable Consumer<DiscordTestResult> onAbandoned) {
        String configuredUrl = destination == DiscordRouting.Destination.WORLD_FIRST
                ? Config.discordWorldFirstWebhookUrl()
                : Config.DISCORD_WEBHOOK_URL.get();
        return sendTestMessage(
                destination,
                configuredUrl,
                System::getenv,
                Config.DISCORD_USE_EMBEDS.get(),
                Config.DISCORD_LANGUAGE.get(),
                Instant.now(),
                onComplete,
                onAbandoned);
    }

    @Nullable
    static DiscordTestResult sendTestMessage(
            @Nullable String configuredUrl,
            Function<String, String> envLookup,
            boolean useEmbeds,
            String language,
            Instant timestamp,
            Consumer<DiscordTestResult> onComplete) {
        return sendTestMessage(
                DiscordRouting.Destination.MAIN, configuredUrl, envLookup, useEmbeds, language, timestamp, onComplete);
    }

    @Nullable
    static DiscordTestResult sendTestMessage(
            DiscordRouting.Destination destination,
            @Nullable String configuredUrl,
            Function<String, String> envLookup,
            boolean useEmbeds,
            String language,
            Instant timestamp,
            Consumer<DiscordTestResult> onComplete) {
        return sendTestMessage(destination, configuredUrl, envLookup, useEmbeds, language, timestamp,
                onComplete, null);
    }

    @Nullable
    static DiscordTestResult sendTestMessage(
            DiscordRouting.Destination destination,
            @Nullable String configuredUrl,
            Function<String, String> envLookup,
            boolean useEmbeds,
            String language,
            Instant timestamp,
            Consumer<DiscordTestResult> onComplete,
            @Nullable Consumer<DiscordTestResult> onAbandoned) {
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
            enqueueTestSend(destination, uri, json, onComplete, onAbandoned);
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
        return sendTestMessage(
                DiscordRouting.Destination.MAIN,
                configuredUrl, envLookup, useEmbeds, language, timestamp,
                targetExecutor, transport, sleeper, warnLogger, onComplete);
    }

    @Nullable
    static DiscordTestResult sendTestMessage(
            DiscordRouting.Destination destination,
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
                DiscordTestResult result = sendTestWithTransport(destination, uri, json, transport, sleeper, warnLogger);
                if (result != null && onComplete != null) {
                    onComplete.accept(result);
                }
            }, null);
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
     * Clears dead-webhook suppression across all destinations.
     */
    static void resetDeadWebhook() {
        MAIN_STATE.deadWebhooks.reset();
        WORLD_FIRST_STATE.deadWebhooks.reset();
    }

    /**
     * Clears dead-webhook suppression for a single destination (F49 seam).
     */
    static void resetDeadWebhook(DiscordRouting.Destination destination) {
        stateFor(destination).deadWebhooks.reset();
    }

    static void resetEnvWarnings() {
        MAIN_STATE.envWarnings.reset();
        WORLD_FIRST_STATE.envWarnings.reset();
    }

    static boolean isTestInProgress() {
        return TEST_IN_PROGRESS.get();
    }

    static void resetTestInProgress() {
        TEST_IN_PROGRESS.set(false);
    }

    public static void resetDigestThrottler() {
        DIGEST_THROTTLER.reset();
    }

    static boolean isDeadWebhook(@Nullable String resolvedUrl) {
        return MAIN_STATE.deadWebhooks.isDead(resolvedUrl)
                || WORLD_FIRST_STATE.deadWebhooks.isDead(resolvedUrl);
    }

    static boolean isDeadWebhook(DiscordRouting.Destination destination, @Nullable String resolvedUrl) {
        return stateFor(destination).deadWebhooks.isDead(resolvedUrl);
    }

    /**
     * Logs a one-time warning when opening a world on an integrated server (singleplayer / LAN)
     * while either Discord webhook URL resolves, including via {@code env:} (F39 + F46).
     */
    public static void warnIfWebhookConfiguredInSingleplayer() {
        warnIfWebhookConfiguredInSingleplayer(
                Config.DISCORD_WEBHOOK_URL.get(),
                Config.discordWorldFirstWebhookUrl(),
                System::getenv,
                msg -> ItemMilestoneLog.LOGGER.warn(msg));
    }

    static boolean warnIfWebhookConfiguredInSingleplayer(
            @Nullable String configuredUrl,
            Function<String, String> envLookup,
            Consumer<String> warnLogger) {
        return warnIfWebhookConfiguredInSingleplayer(configuredUrl, null, envLookup, warnLogger);
    }

    static boolean warnIfWebhookConfiguredInSingleplayer(
            @Nullable String mainConfiguredUrl,
            @Nullable String worldFirstConfiguredUrl,
            Function<String, String> envLookup,
            Consumer<String> warnLogger) {
        boolean mainResolved = DiscordWebhookUrl.resolve(mainConfiguredUrl, envLookup).isResolved();
        boolean worldFirstResolved = !mainResolved
                && DiscordWebhookUrl.resolve(worldFirstConfiguredUrl, envLookup).isResolved();
        if (!mainResolved && !worldFirstResolved) {
            return false;
        }
        warnLogger.accept(DiscordWebhookUrl.SINGLEPLAYER_WEBHOOK_WARNING);
        return true;
    }

    @Nullable
    static URI resolveWebhookUri(@Nullable String configuredUrl, Consumer<String> warnLogger) {
        return resolveWebhookUri(DiscordRouting.Destination.MAIN, configuredUrl, System::getenv, warnLogger);
    }

    @Nullable
    static URI resolveWebhookUri(
            @Nullable String configuredUrl,
            Function<String, String> envLookup,
            Consumer<String> warnLogger) {
        return resolveWebhookUri(DiscordRouting.Destination.MAIN, configuredUrl, envLookup, warnLogger);
    }

    @Nullable
    static URI resolveWebhookUri(
            DiscordRouting.Destination destination,
            @Nullable String configuredUrl,
            Function<String, String> envLookup,
            Consumer<String> warnLogger) {
        return resolveDestination(destination, configuredUrl, envLookup, warnLogger).deliverableUri();
    }

    static DestinationResolution resolveDestination(
            DiscordRouting.Destination destination,
            @Nullable String configuredUrl,
            Function<String, String> envLookup,
            Consumer<String> warnLogger) {
        DestinationState state = stateFor(destination);
        DiscordWebhookUrl.Resolution resolution = DiscordWebhookUrl.resolve(configuredUrl, envLookup);
        if (resolution.isEnvResolutionError()) {
            if (state.envWarnings.shouldWarn(resolution)) {
                warnLogger.accept(resolution.warningMessage());
            }
            state.deadWebhooks.syncCurrentUrlAndCheckDead(null);
            return DestinationResolution.empty(destination);
        }
        state.envWarnings.shouldWarn(resolution);
        if (resolution.status() == DiscordWebhookUrl.Status.EMPTY) {
            state.deadWebhooks.syncCurrentUrlAndCheckDead(null);
            return DestinationResolution.empty(destination);
        }
        if (!resolution.isResolved()) {
            state.deadWebhooks.syncCurrentUrlAndCheckDead(null);
            String warning = resolution.warningMessage();
            if (warning != null) {
                warnLogger.accept(warning);
            }
            return DestinationResolution.empty(destination);
        }
        if (state.deadWebhooks.syncCurrentUrlAndCheckDead(resolution.resolvedUrl())) {
            return new DestinationResolution(destination, true, null);
        }
        return new DestinationResolution(destination, true, resolution.uri());
    }

    private static void enqueueSend(DiscordRouting.Destination destination, URI uri, String json) {
        synchronized (DiscordNotifier.class) {
            ExecutorService exec = ensureExecutor();
            HttpClient client = ensureClient();
            submitPost(exec, () -> send(client, destination, uri, json));
        }
    }

    /** SF-10 test seam: enqueue a no-network post with the same accounting as a real notification. */
    static void submitPostForTest(Runnable post) {
        synchronized (DiscordNotifier.class) {
            submitPost(ensureExecutor(), post);
        }
    }

    /** R2-SF-2 test seam: queue a test task directly, without claiming {@code TEST_IN_PROGRESS}. */
    static void enqueueTestTaskForTest(Runnable action, @Nullable Consumer<DiscordTestResult> onAbandoned) {
        synchronized (DiscordNotifier.class) {
            ensureExecutor().execute(new TestSendTask(action, onAbandoned));
        }
    }

    private static void submitPost(ExecutorService exec, Runnable post) {
        try {
            exec.execute(post);
        } catch (RejectedExecutionException e) {
            noteDropped("queue rejected a post", 1);
        }
    }

    private static void enqueueTestSend(
            DiscordRouting.Destination destination,
            URI uri,
            String json,
            Consumer<DiscordTestResult> onComplete) {
        enqueueTestSend(destination, uri, json, onComplete, null);
    }

    private static void enqueueTestSend(
            DiscordRouting.Destination destination,
            URI uri,
            String json,
            Consumer<DiscordTestResult> onComplete,
            @Nullable Consumer<DiscordTestResult> onAbandoned) {
        synchronized (DiscordNotifier.class) {
            ExecutorService exec = ensureExecutor();
            HttpClient client = ensureClient();
            TestSendTask task = new TestSendTask(() -> {
                DiscordTestResult result = sendTestWithTransport(
                        destination,
                        uri,
                        json,
                        (targetUri, payload) -> executePost(client, targetUri, payload),
                        Thread::sleep,
                        msg -> ItemMilestoneLog.LOGGER.warn(msg));
                if (result != null && onComplete != null) {
                    onComplete.accept(result);
                }
            }, onAbandoned);
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
        @Nullable
        private final Consumer<DiscordTestResult> onAbandoned;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private TestSendTask(Runnable action, @Nullable Consumer<DiscordTestResult> onAbandoned) {
            this.action = action;
            this.onAbandoned = onAbandoned;
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
            if (released.compareAndSet(false, true)) {
                TEST_IN_PROGRESS.set(false);
                if (onAbandoned != null) {
                    onAbandoned.accept(DiscordTestResult.abandoned());
                }
            }
        }

        private void release() {
            if (released.compareAndSet(false, true)) {
                TEST_IN_PROGRESS.set(false);
            }
        }
    }

    private static void send(HttpClient client, DiscordRouting.Destination destination, URI uri, String json) {
        sendWithTransport(
                destination,
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
        sendWithTransport(DiscordRouting.Destination.MAIN, uri, json, transport, sleeper, warnLogger);
    }

    static void sendWithTransport(
            DiscordRouting.Destination destination,
            URI uri,
            String json,
            HttpPostTransport transport,
            RetrySleeper sleeper,
            Consumer<String> warnLogger) {
        executeSend(destination, uri, json, false, transport, sleeper, warnLogger);
    }

    @Nullable
    static DiscordTestResult sendTestWithTransport(
            URI uri,
            String json,
            HttpPostTransport transport,
            RetrySleeper sleeper,
            Consumer<String> warnLogger) {
        return sendTestWithTransport(DiscordRouting.Destination.MAIN, uri, json, transport, sleeper, warnLogger);
    }

    @Nullable
    static DiscordTestResult sendTestWithTransport(
            DiscordRouting.Destination destination,
            URI uri,
            String json,
            HttpPostTransport transport,
            RetrySleeper sleeper,
            Consumer<String> warnLogger) {
        return executeSend(destination, uri, json, true, transport, sleeper, warnLogger);
    }

    @Nullable
    private static DiscordTestResult executeSend(
            DiscordRouting.Destination destination,
            URI uri,
            String json,
            boolean isTest,
            HttpPostTransport transport,
            RetrySleeper sleeper,
            Consumer<String> warnLogger) {
        DestinationState state = stateFor(destination);
        String urlKey = uri.toString();
        if (!isTest && state.deadWebhooks.isDead(urlKey)) {
            return null;
        }
        try {
            HttpPostResult first = transport.post(uri, json);
            if (first.statusCode() != 429) {
                return handleTerminalStatus(state, urlKey, first.statusCode(), false, isTest, warnLogger);
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
            if (!isTest && state.deadWebhooks.isDead(urlKey)) {
                return null;
            }
            HttpPostResult retry = transport.post(uri, json);
            return handleTerminalStatus(state, urlKey, retry.statusCode(), true, isTest, warnLogger);
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
            DestinationState state,
            String urlKey,
            int status,
            boolean wasRetry,
            boolean isTest,
            Consumer<String> warnLogger) {
        if (DiscordDeliveryPolicy.isSuccessStatus(status)) {
            if (isTest) {
                state.deadWebhooks.reset();
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
            if (state.deadWebhooks.markDead(urlKey)) {
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
                            noteDropped("executor shut down", 1);
                            if (r instanceof TestSendTask testTask) {
                                testTask.cancelDropped();
                            }
                            return;
                        }
                        // Bounded queue full: make room by discarding the oldest post, and count
                        // every post we throw away instead of dropping it silently (SF-10).
                        Runnable dropped = e.getQueue().poll();
                        if (dropped instanceof TestSendTask testTask) {
                            // Keep the single in-flight discordtest handoff alive; the incoming
                            // post is the one that gets dropped in this branch. The re-queued test
                            // task was not discarded, so it must not be counted as a drop.
                            if (!e.getQueue().offer(testTask)) {
                                noteDropped("queue full, test result discarded", 1);
                                testTask.cancelDropped();
                            }
                            noteDropped("queue full, incoming post discarded", 1);
                            // R2-SF-2: a discarded incoming test task must report ABANDONED to its
                            // callback (and release TEST_IN_PROGRESS) instead of vanishing.
                            if (r instanceof TestSendTask incomingTest) {
                                incomingTest.cancelDropped();
                            }
                            return;
                        }
                        if (dropped != null) {
                            noteDropped("queue full, oldest discarded", 1);
                        }
                        try {
                            e.execute(r);
                        } catch (RejectedExecutionException rejected) {
                            noteDropped("queue full, incoming post rejected", 1);
                            if (r instanceof TestSendTask testTask) {
                                testTask.cancelDropped();
                            }
                        }
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
        shutdown(STOP_DELIVERY_WINDOW_MILLIS);
    }

    /**
     * SF-10: gives the stop-time flushed batch a bounded window to actually deliver before the
     * remaining queued posts are cancelled. The window is finite, so server stop still completes
     * promptly; every undelivered post is counted and logged.
     */
    static synchronized void shutdown(long windowMillis) {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(Math.max(0L, windowMillis), TimeUnit.MILLISECONDS)) {
                    discardUndelivered(executor.shutdownNow());
                }
            } catch (InterruptedException e) {
                discardUndelivered(executor.shutdownNow());
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
        // 1.20.1 targets Java 17: java.net.http.HttpClient is not AutoCloseable there.
        httpClient = null;
        MAIN_STATE.reset();
        WORLD_FIRST_STATE.reset();
        TEST_IN_PROGRESS.set(false);
        DIGEST_THROTTLER.reset();
    }

    private static void discardUndelivered(List<Runnable> undelivered) {
        noteDropped("server stop, undelivered posts cancelled", undelivered.size());
        for (Runnable post : undelivered) {
            if (post instanceof TestSendTask testTask) {
                testTask.cancelDropped();
            }
        }
    }
}
