package com.pengin0503.itemmilestonelog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.OptionalLong;

/**
 * Pure-Java delivery policy helpers for Discord webhook requests (F35):
 * {@code Retry-After} header parsing and dead-webhook (HTTP 404/401) state tracking.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class DiscordDeliveryPolicy {
    /** Maximum {@code Retry-After} wait allowed before dropping a rate-limited post (seconds). */
    public static final int MAX_RETRY_AFTER_SECONDS = 5;
    /** Maximum {@code Retry-After} wait in milliseconds ({@value #MAX_RETRY_AFTER_SECONDS} seconds). */
    public static final long MAX_RETRY_AFTER_MILLIS = MAX_RETRY_AFTER_SECONDS * 1000L;

    private static final BigDecimal MAX_RETRY_AFTER_SECONDS_BD = BigDecimal.valueOf(MAX_RETRY_AFTER_SECONDS);

    private DiscordDeliveryPolicy() {
    }

    /**
     * Parses a Discord HTTP 429 {@code Retry-After} header value in seconds (integer or fractional).
     *
     * <p>Returns the wait duration in milliseconds (rounded up so the sender never retries earlier
     * than requested) when the value is a valid non-negative decimal number {@code <= 5} seconds.
     * Returns {@link OptionalLong#empty()} if the header is missing, blank, unparseable (e.g.
     * negative, non-numeric, HTTP-date, scientific notation), or exceeds {@value #MAX_RETRY_AFTER_SECONDS} seconds.
     */
    public static OptionalLong parseRetryAfterMillis(String headerValue) {
        if (headerValue == null) {
            return OptionalLong.empty();
        }
        String trimmed = headerValue.trim();
        if (!isNonNegativeDecimal(trimmed)) {
            return OptionalLong.empty();
        }
        final BigDecimal seconds;
        try {
            seconds = new BigDecimal(trimmed);
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
        if (seconds.compareTo(MAX_RETRY_AFTER_SECONDS_BD) > 0) {
            return OptionalLong.empty();
        }
        long millis = seconds.movePointRight(3).setScale(0, RoundingMode.CEILING).longValue();
        if (millis < 0L || millis > MAX_RETRY_AFTER_MILLIS) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(millis);
    }

    /** Returns {@code true} for HTTP status codes indicating a deleted or invalid webhook (404 or 401). */
    public static boolean isDeadWebhookStatus(int statusCode) {
        return statusCode == 404 || statusCode == 401;
    }

    /** Returns {@code true} for HTTP 2xx success status codes. */
    public static boolean isSuccessStatus(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    /**
     * Pure decision helper for per-player Discord opt-out (F45).
     *
     * <p>Returns {@code false} only when {@code allowDiscordOptOut} is {@code true} and the
     * player has opted out ({@code excludeFromDiscord} is {@code true}). When
     * {@code allowDiscordOptOut} is {@code false}, saved opt-out preferences are ignored
     * (but preserved).
     */
    public static boolean shouldPostToDiscord(boolean allowDiscordOptOut, boolean excludeFromDiscord) {
        return !(allowDiscordOptOut && excludeFromDiscord);
    }

    private static boolean isNonNegativeDecimal(String value) {
        if (value.isEmpty()) {
            return false;
        }
        int dotIndex = -1;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '.') {
                if (dotIndex >= 0) {
                    return false;
                }
                dotIndex = i;
            } else if (c < '0' || c > '9') {
                return false;
            }
        }
        return dotIndex != 0 && dotIndex != value.length() - 1;
    }

    /**
     * Thread-safe tracker for a webhook URL that returned HTTP 404 or 401 (F35).
     *
     * <p>Sending to the dead URL is suppressed after logging one warning, and resumes
     * automatically when the resolved webhook URL changes or when {@link #reset()} is called
     * (e.g. after a successful {@code discordtest} in F49).
     */
    public static final class DeadWebhookTracker {
        private String deadUrl;

        /**
         * Synchronizes against the currently resolved webhook URL and reports whether sending
         * to it is currently suppressed. If the resolved URL differs from the previously marked
         * dead URL (including becoming {@code null} or empty), the dead state is cleared.
         */
        public synchronized boolean syncCurrentUrlAndCheckDead(String resolvedUrl) {
            if (deadUrl == null) {
                return false;
            }
            if (resolvedUrl != null && deadUrl.equals(resolvedUrl)) {
                return true;
            }
            deadUrl = null;
            return false;
        }

        /**
         * Returns {@code true} if {@code resolvedUrl} is currently marked dead without altering
         * state for other URLs. Used on the sender thread before dispatching queued tasks.
         */
        public synchronized boolean isDead(String resolvedUrl) {
            return deadUrl != null && deadUrl.equals(resolvedUrl);
        }

        /**
         * Marks {@code resolvedUrl} as dead after HTTP 404 or 401.
         *
         * @return {@code true} if this call newly marked the URL as dead (caller should log once);
         *         {@code false} if it was already marked dead or the URL is null/empty.
         */
        public synchronized boolean markDead(String resolvedUrl) {
            if (resolvedUrl == null || resolvedUrl.isEmpty()) {
                return false;
            }
            if (resolvedUrl.equals(deadUrl)) {
                return false;
            }
            deadUrl = resolvedUrl;
            return true;
        }

        /** Clears dead-webhook suppression (e.g. on successful F49 {@code discordtest} or shutdown). */
        public synchronized void reset() {
            deadUrl = null;
        }
    }
}
