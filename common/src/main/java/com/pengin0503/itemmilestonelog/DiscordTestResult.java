package com.pengin0503.itemmilestonelog;

import java.util.Objects;

/**
 * Pure-Java result classification for {@code /milestonelog discordtest} (F49).
 *
 * <p>Classifies synchronous webhook URL pre-checks, HTTP status codes, and network
 * exceptions into stable outcomes and translation keys. Never embeds the configured
 * or resolved webhook URL (or any exception message) in any field or string form.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public record DiscordTestResult(Kind kind, String detail) {
    /** Outcome category for a {@code /milestonelog discordtest} invocation. */
    public enum Kind {
        NOT_CONFIGURED("message.itemmilestonelog.discordtest_not_configured", false),
        ENV_MISSING("message.itemmilestonelog.discordtest_env_missing", false),
        INVALID_URL("message.itemmilestonelog.discordtest_invalid_url", false),
        IN_PROGRESS("message.itemmilestonelog.discordtest_in_progress", false),
        SUCCESS("message.itemmilestonelog.discordtest_success", true),
        INVALID_WEBHOOK("message.itemmilestonelog.discordtest_invalid_webhook", false),
        RATE_LIMITED("message.itemmilestonelog.discordtest_rate_limited", false),
        HTTP_ERROR("message.itemmilestonelog.discordtest_http_error", false),
        NETWORK_ERROR("message.itemmilestonelog.discordtest_network_error", false),
        /** MN-3: the result could not be delivered (executor gone / inline shutdown handoff). */
        ABANDONED("message.itemmilestonelog.discordtest_abandoned", false);

        private final String messageKey;
        private final boolean success;

        Kind(String messageKey, boolean success) {
            this.messageKey = messageKey;
            this.success = success;
        }

        public String messageKey() {
            return messageKey;
        }

        public boolean isSuccess() {
            return success;
        }
    }

    public DiscordTestResult {
        Objects.requireNonNull(kind, "kind");
    }

    public String messageKey() {
        return kind.messageKey();
    }

    public boolean isSuccess() {
        return kind.isSuccess();
    }

    /** Returns {@code true} if the translation template for this result takes a single {@code %s} argument. */
    public boolean hasDetail() {
        return detail != null;
    }

    /**
     * Classifies a synchronous webhook URL pre-check before sending.
     *
     * @return {@code null} if {@code resolution} is {@link DiscordWebhookUrl.Status#RESOLVED}
     *         (pre-check passed); otherwise the synchronous failure result.
     */
    public static DiscordTestResult classifyPreCheck(DiscordWebhookUrl.Resolution resolution) {
        if (resolution == null || resolution.status() == DiscordWebhookUrl.Status.EMPTY) {
            return new DiscordTestResult(Kind.NOT_CONFIGURED, null);
        }
        return switch (resolution.status()) {
            case RESOLVED -> null;
            case EMPTY -> new DiscordTestResult(Kind.NOT_CONFIGURED, null);
            case MISSING_ENV_VAR, EMPTY_ENV_VAR ->
                    new DiscordTestResult(Kind.ENV_MISSING, resolution.envVarName());
            case INVALID_ENV_VAR_NAME, INVALID_SCHEME, MALFORMED_URL ->
                    new DiscordTestResult(Kind.INVALID_URL, null);
        };
    }

    /** Returns the synchronous result when another {@code discordtest} is already in flight. */
    public static DiscordTestResult inProgress() {
        return new DiscordTestResult(Kind.IN_PROGRESS, null);
    }

    /**
     * MN-3: explicit outcome used when a completion cannot be handed to the server thread (the
     * server or Discord executor is gone, or {@code server.execute} ran inline during shutdown).
     * Never carries a URL or an exception message.
     */
    public static DiscordTestResult abandoned() {
        return new DiscordTestResult(Kind.ABANDONED, null);
    }

    /**
     * Pure classifier for an HTTP response status code returned by Discord.
     *
     * <ul>
     *   <li>2xx &rarr; {@link Kind#SUCCESS} with status code</li>
     *   <li>401 / 404 &rarr; {@link Kind#INVALID_WEBHOOK} with status code</li>
     *   <li>429 &rarr; {@link Kind#RATE_LIMITED} (no detail)</li>
     *   <li>Any other code &rarr; {@link Kind#HTTP_ERROR} with status code</li>
     * </ul>
     */
    public static DiscordTestResult classifyHttpStatus(int statusCode) {
        if (DiscordDeliveryPolicy.isSuccessStatus(statusCode)) {
            return new DiscordTestResult(Kind.SUCCESS, String.valueOf(statusCode));
        }
        if (DiscordDeliveryPolicy.isDeadWebhookStatus(statusCode)) {
            return new DiscordTestResult(Kind.INVALID_WEBHOOK, String.valueOf(statusCode));
        }
        if (statusCode == 429) {
            return new DiscordTestResult(Kind.RATE_LIMITED, null);
        }
        return new DiscordTestResult(Kind.HTTP_ERROR, String.valueOf(statusCode));
    }

    /**
     * Pure classifier for a transport/network exception.
     *
     * <p>Records only the exception's simple class name &mdash; never {@code getMessage()},
     * stack traces, or causes &mdash; so a webhook URL embedded in an exception message can
     * never leak into command output or logs.
     */
    public static DiscordTestResult classifyException(Throwable throwable) {
        return new DiscordTestResult(Kind.NETWORK_ERROR, safeExceptionName(throwable));
    }

    /**
     * Extracts a non-empty simple class name without ever inspecting {@link Throwable#getMessage()}.
     */
    public static String safeExceptionName(Throwable throwable) {
        if (throwable == null) {
            return "Exception";
        }
        Class<?> cls = throwable.getClass();
        while (cls != null) {
            String simple = cls.getSimpleName();
            if (!simple.isEmpty()) {
                return simple;
            }
            cls = cls.getSuperclass();
        }
        return "Exception";
    }
}
