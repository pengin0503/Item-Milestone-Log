package com.pengin0503.itemmilestonelog;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Asynchronous Discord webhook sender for F5.
 *
 * <p>Uses a dedicated single-thread executor and {@link HttpClient} so server ticks
 * are never blocked. The configured webhook URL is treated as a secret and is never
 * printed to logs, chat, or exception messages.
 */
public final class DiscordNotifier {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int QUEUE_CAPACITY = 100;

    private static ExecutorService executor;
    private static HttpClient httpClient;

    private DiscordNotifier() {
    }

    public static void notifyMilestone(String playerName, TargetKey target, MilestoneRecord record, boolean worldFirst) {
        String url = Config.DISCORD_WEBHOOK_URL.get();
        if (url == null || url.isBlank()) {
            return;
        }
        String scope = Config.DISCORD_NOTIFY_SCOPE.get();
        if ("world_first".equals(scope) && !worldFirst) {
            return;
        }

        URI uri;
        try {
            uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme)) {
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Discord webhook skipped: invalid URL scheme (https required)");
                return;
            }
        } catch (IllegalArgumentException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Discord webhook skipped: malformed URL");
            return;
        }

        String formattedTime = Playtime.formatRecord(record, Config.displayTimeZone());
        String itemLabel = target.formatDisplayId();
        String content = worldFirst
                ? String.format("★ [World First] %s obtained %s at %s", playerName, itemLabel, formattedTime)
                : String.format("★ Milestone recorded: %s obtained %s at %s", playerName, itemLabel, formattedTime);

        JsonObject body = new JsonObject();
        body.addProperty("content", content);
        JsonObject allowedMentions = new JsonObject();
        allowedMentions.add("parse", new JsonArray());
        body.add("allowed_mentions", allowedMentions);
        String json = body.toString();

        synchronized (DiscordNotifier.class) {
            ExecutorService exec = ensureExecutor();
            HttpClient client = ensureClient();
            try {
                exec.execute(() -> send(client, uri, json));
            } catch (RejectedExecutionException ignored) {
            }
        }
    }

    private static void send(HttpClient client, URI uri, String json) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status == 429) {
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Discord webhook rate limited (HTTP 429); dropping message");
                return;
            }
            if (status < 200 || status >= 300) {
                ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Discord webhook returned HTTP {}", status);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Never log e.getMessage() or the exception itself in case it embeds the webhook URL.
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Discord webhook request failed ({})",
                    e.getClass().getSimpleName());
        }
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
                    new ThreadPoolExecutor.DiscardOldestPolicy());
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
    }
}
