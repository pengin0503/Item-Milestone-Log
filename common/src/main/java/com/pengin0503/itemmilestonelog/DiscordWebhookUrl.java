package com.pengin0503.itemmilestonelog;

import java.net.URI;
import java.util.function.Function;

/**
 * Pure-Java resolution and validation for the {@code discordWebhookUrl} config option (F39).
 *
 * <p>Supports plain HTTPS webhook URLs as well as {@code env:<VAR_NAME>} references
 * (lowercase {@code env:} prefix only, where {@code <VAR_NAME>} matches
 * {@code ^[A-Za-z_][A-Za-z0-9_]*$}). Resolved URL values are treated as secrets and
 * are never embedded in warning messages.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class DiscordWebhookUrl {
    /** Exact lowercase prefix indicating an environment variable reference. */
    public static final String ENV_PREFIX = "env:";

    /**
     * One-time server-log warning emitted on {@code ServerStartedEvent} for integrated servers
     * (singleplayer / LAN) when a Discord webhook URL resolves (F39).
     */
    public static final String SINGLEPLAYER_WEBHOOK_WARNING =
            "[ItemMilestoneLog] A Discord webhook is configured. In singleplayer, this config file "
                    + "is shared across all worlds and will be distributed if the config folder is "
                    + "exported with a modpack.";

    private DiscordWebhookUrl() {
    }

    /** Outcome of resolving and validating a configured {@code discordWebhookUrl} value. */
    public enum Status {
        /** Configured value is null or blank (Discord notifications disabled). */
        EMPTY,
        /** Resolved to a valid HTTPS URI (either literal or via {@code env:}). */
        RESOLVED,
        /** Value starts with {@code env:} but the variable name does not match {@code ^[A-Za-z_][A-Za-z0-9_]*$}. */
        INVALID_ENV_VAR_NAME,
        /** Value references a valid environment variable name that is not set. */
        MISSING_ENV_VAR,
        /** Value references a valid environment variable whose value is empty or blank. */
        EMPTY_ENV_VAR,
        /** Resolved value parses as a URI, but its scheme is not {@code https}. */
        INVALID_SCHEME,
        /** Resolved value is not a syntactically valid URI. */
        MALFORMED_URL
    }

    /**
     * Immutable result of {@link #resolve(String, Function)}.
     *
     * @param status        resolution/validation status
     * @param uri           validated HTTPS {@link URI} when {@code status == Status.RESOLVED}, else {@code null}
     * @param resolvedUrl   canonical string of {@code uri} when {@code status == Status.RESOLVED}, else {@code null}
     * @param envVarName    valid environment variable name when {@code fromEnv} and name is valid, else {@code null}
     * @param fromEnv       {@code true} if the trimmed configured value started with lowercase {@code env:}
     * @param configuredKey trimmed configured value (empty string when {@code EMPTY})
     */
    public record Resolution(
            Status status,
            URI uri,
            String resolvedUrl,
            String envVarName,
            boolean fromEnv,
            String configuredKey) {

        /** Returns {@code true} when resolution succeeded and produced a valid HTTPS webhook URI. */
        public boolean isResolved() {
            return status == Status.RESOLVED && uri != null;
        }

        /**
         * Returns {@code true} when an {@code env:} reference failed before URL validation
         * (invalid name, missing variable, or empty value).
         */
        public boolean isEnvResolutionError() {
            return status == Status.INVALID_ENV_VAR_NAME
                    || status == Status.MISSING_ENV_VAR
                    || status == Status.EMPTY_ENV_VAR;
        }

        /**
         * Returns a safe server-log warning message for invalid/unresolvable configurations,
         * or {@code null} for {@link Status#EMPTY} and {@link Status#RESOLVED}.
         *
         * <p>The resolved URL value (and any invalid text following {@code env:}) is never
         * included in the returned message. Valid variable names may be included.
         */
        public String warningMessage() {
            return switch (status) {
                case EMPTY, RESOLVED -> null;
                case INVALID_ENV_VAR_NAME ->
                        "[ItemMilestoneLog] Discord webhook skipped: invalid environment variable name in env: reference (must match [A-Za-z_][A-Za-z0-9_]*)";
                case MISSING_ENV_VAR ->
                        "[ItemMilestoneLog] Discord webhook skipped: environment variable '" + envVarName + "' is not set";
                case EMPTY_ENV_VAR ->
                        "[ItemMilestoneLog] Discord webhook skipped: environment variable '" + envVarName + "' is empty";
                case INVALID_SCHEME ->
                        "[ItemMilestoneLog] Discord webhook skipped: invalid URL scheme (https required)";
                case MALFORMED_URL ->
                        "[ItemMilestoneLog] Discord webhook skipped: malformed URL";
            };
        }
    }

    /** Returns {@code true} iff {@code name} matches {@code ^[A-Za-z_][A-Za-z0-9_]*$}. */
    public static boolean isValidEnvVarName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (!isAsciiLetterOrUnderscore(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!isAsciiLetterDigitOrUnderscore(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiLetterOrUnderscore(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
    }

    private static boolean isAsciiLetterDigitOrUnderscore(char c) {
        return isAsciiLetterOrUnderscore(c) || (c >= '0' && c <= '9');
    }

    /**
     * Pure resolution and HTTPS validation of {@code discordWebhookUrl}.
     *
     * <p>After trimming {@code configuredValue}:
     * <ul>
     *   <li>Empty/blank &rarr; {@link Status#EMPTY}.</li>
     *   <li>Starts with lowercase {@code "env:"} &rarr; validates the remainder against
     *       {@code ^[A-Za-z_][A-Za-z0-9_]*$}, looks up the variable via {@code envLookup},
     *       and validates the resulting trimmed value as an HTTPS URI.</li>
     *   <li>Otherwise (including uppercase {@code "ENV:"}) &rarr; validates the trimmed value
     *       directly as an HTTPS URI without invoking {@code envLookup}.</li>
     * </ul>
     */
    public static Resolution resolve(String configuredValue, Function<String, String> envLookup) {
        if (configuredValue == null) {
            return new Resolution(Status.EMPTY, null, null, null, false, "");
        }
        String trimmed = configuredValue.trim();
        if (trimmed.isEmpty()) {
            return new Resolution(Status.EMPTY, null, null, null, false, "");
        }

        final String candidateUrl;
        final String envVarName;
        final boolean fromEnv;
        if (trimmed.startsWith(ENV_PREFIX)) {
            fromEnv = true;
            String rawVarName = trimmed.substring(ENV_PREFIX.length());
            if (!isValidEnvVarName(rawVarName)) {
                return new Resolution(Status.INVALID_ENV_VAR_NAME, null, null, null, true, trimmed);
            }
            envVarName = rawVarName;
            Function<String, String> lookup = envLookup != null ? envLookup : System::getenv;
            String envValue = lookup.apply(envVarName);
            if (envValue == null) {
                return new Resolution(Status.MISSING_ENV_VAR, null, null, envVarName, true, trimmed);
            }
            String trimmedEnvValue = envValue.trim();
            if (trimmedEnvValue.isEmpty()) {
                return new Resolution(Status.EMPTY_ENV_VAR, null, null, envVarName, true, trimmed);
            }
            candidateUrl = trimmedEnvValue;
        } else {
            fromEnv = false;
            envVarName = null;
            candidateUrl = trimmed;
        }

        try {
            URI uri = URI.create(candidateUrl);
            String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme)) {
                return new Resolution(Status.INVALID_SCHEME, null, null, envVarName, fromEnv, trimmed);
            }
            return new Resolution(Status.RESOLVED, uri, uri.toString(), envVarName, fromEnv, trimmed);
        } catch (IllegalArgumentException e) {
            return new Resolution(Status.MALFORMED_URL, null, null, envVarName, fromEnv, trimmed);
        }
    }

    /**
     * Thread-safe tracker that ensures {@code env:} resolution warnings (missing variable,
     * empty value, or invalid name) are logged at most once until the configured value changes.
     */
    public static final class EnvWarnTracker {
        private String lastWarnedConfigured;

        /**
         * Updates tracking state for {@code resolution} and returns {@code true} if a warning
         * should be emitted for this {@code env:} resolution failure.
         */
        public synchronized boolean shouldWarn(Resolution resolution) {
            if (resolution == null || !resolution.isEnvResolutionError()) {
                lastWarnedConfigured = null;
                return false;
            }
            String key = resolution.configuredKey();
            if (key != null && key.equals(lastWarnedConfigured)) {
                return false;
            }
            lastWarnedConfigured = key;
            return true;
        }

        /** Clears the remembered configured value (e.g. on server shutdown or between tests). */
        public synchronized void reset() {
            lastWarnedConfigured = null;
        }
    }
}
