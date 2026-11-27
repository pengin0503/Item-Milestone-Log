package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordTestResultTest {
    private static final String SECRET_URL = "https://discord.com/api/webhooks/123456789/top-secret-token";
    private static final String SECRET_HTTP_URL = "http://discord.com/api/webhooks/123456789/top-secret-http";
    private static final String SECRET_MALFORMED_URL = "https://discord.com/api/webhooks/bad space/top-secret-bad";

    @Test
    void classifyHttpStatusCovers2xx401404429AndOtherCodes() {
        for (int code : new int[]{200, 201, 204, 299}) {
            DiscordTestResult res = DiscordTestResult.classifyHttpStatus(code);
            assertEquals(DiscordTestResult.Kind.SUCCESS, res.kind(), "code=" + code);
            assertTrue(res.isSuccess(), "code=" + code);
            assertTrue(res.hasDetail(), "code=" + code);
            assertEquals(String.valueOf(code), res.detail());
            assertEquals("message.itemmilestonelog.discordtest_success", res.messageKey());
        }

        for (int code : new int[]{401, 404}) {
            DiscordTestResult res = DiscordTestResult.classifyHttpStatus(code);
            assertEquals(DiscordTestResult.Kind.INVALID_WEBHOOK, res.kind(), "code=" + code);
            assertFalse(res.isSuccess(), "code=" + code);
            assertTrue(res.hasDetail(), "code=" + code);
            assertEquals(String.valueOf(code), res.detail());
            assertEquals("message.itemmilestonelog.discordtest_invalid_webhook", res.messageKey());
        }

        DiscordTestResult rateLimited = DiscordTestResult.classifyHttpStatus(429);
        assertEquals(DiscordTestResult.Kind.RATE_LIMITED, rateLimited.kind());
        assertFalse(rateLimited.isSuccess());
        assertFalse(rateLimited.hasDetail());
        assertNull(rateLimited.detail());
        assertEquals("message.itemmilestonelog.discordtest_rate_limited", rateLimited.messageKey());

        for (int code : new int[]{199, 300, 302, 400, 403, 500, 502, 503}) {
            DiscordTestResult res = DiscordTestResult.classifyHttpStatus(code);
            assertEquals(DiscordTestResult.Kind.HTTP_ERROR, res.kind(), "code=" + code);
            assertFalse(res.isSuccess(), "code=" + code);
            assertTrue(res.hasDetail(), "code=" + code);
            assertEquals(String.valueOf(code), res.detail());
            assertEquals("message.itemmilestonelog.discordtest_http_error", res.messageKey());
        }
    }

    @Test
    void classifyExceptionUsesOnlySimpleClassNameAndNeverLeaksWebhookUrl() {
        IOException ioe = new IOException("Connection reset while posting to " + SECRET_URL);
        DiscordTestResult ioRes = DiscordTestResult.classifyException(ioe);
        assertEquals(DiscordTestResult.Kind.NETWORK_ERROR, ioRes.kind());
        assertFalse(ioRes.isSuccess());
        assertEquals("IOException", ioRes.detail());
        assertEquals("message.itemmilestonelog.discordtest_network_error", ioRes.messageKey());
        assertNoUrlLeak(ioRes);

        SocketTimeoutException ste = new SocketTimeoutException("Read timed out: " + SECRET_URL);
        DiscordTestResult steRes = DiscordTestResult.classifyException(ste);
        assertEquals("SocketTimeoutException", steRes.detail());
        assertNoUrlLeak(steRes);

        ConnectException ce = new ConnectException("Failed to connect to " + SECRET_URL);
        DiscordTestResult ceRes = DiscordTestResult.classifyException(ce);
        assertEquals("ConnectException", ceRes.detail());
        assertNoUrlLeak(ceRes);

        // Anonymous subclass has empty Class#getSimpleName(); must fall back to superclass name.
        IOException anonymous = new IOException("Secret in anonymous exception: " + SECRET_URL) {
        };
        DiscordTestResult anonRes = DiscordTestResult.classifyException(anonymous);
        assertEquals("IOException", anonRes.detail());
        assertNoUrlLeak(anonRes);

        // Null throwable falls back safely.
        DiscordTestResult nullRes = DiscordTestResult.classifyException(null);
        assertEquals(DiscordTestResult.Kind.NETWORK_ERROR, nullRes.kind());
        assertEquals("Exception", nullRes.detail());
    }

    @Test
    void classifyPreCheckCoversAllResolutionStatusesWithoutLeakingWebhookUrl() {
        Map<String, String> env = Map.of(
                "IML_GOOD", SECRET_URL,
                "IML_EMPTY", "   ",
                "IML_HTTP", SECRET_HTTP_URL,
                "IML_BAD", SECRET_MALFORMED_URL
        );

        // Valid plain URL and valid env: URL pass pre-check (null result).
        assertNull(DiscordTestResult.classifyPreCheck(DiscordWebhookUrl.resolve(SECRET_URL, env::get)));
        assertNull(DiscordTestResult.classifyPreCheck(DiscordWebhookUrl.resolve("env:IML_GOOD", env::get)));

        // Null or blank config -> NOT_CONFIGURED.
        assertEquals(DiscordTestResult.Kind.NOT_CONFIGURED, DiscordTestResult.classifyPreCheck(null).kind());
        for (String blank : new String[]{null, "", "   "}) {
            DiscordTestResult res = DiscordTestResult.classifyPreCheck(DiscordWebhookUrl.resolve(blank, env::get));
            assertNotNull(res);
            assertEquals(DiscordTestResult.Kind.NOT_CONFIGURED, res.kind());
            assertFalse(res.isSuccess());
            assertNull(res.detail());
            assertEquals("message.itemmilestonelog.discordtest_not_configured", res.messageKey());
        }

        // Missing or empty env variable -> ENV_MISSING with ONLY the variable name.
        DiscordTestResult missingEnv = DiscordTestResult.classifyPreCheck(
                DiscordWebhookUrl.resolve("env:IML_MISSING", env::get));
        assertNotNull(missingEnv);
        assertEquals(DiscordTestResult.Kind.ENV_MISSING, missingEnv.kind());
        assertEquals("IML_MISSING", missingEnv.detail());
        assertEquals("message.itemmilestonelog.discordtest_env_missing", missingEnv.messageKey());
        assertNoUrlLeak(missingEnv);

        DiscordTestResult emptyEnv = DiscordTestResult.classifyPreCheck(
                DiscordWebhookUrl.resolve("env:IML_EMPTY", env::get));
        assertNotNull(emptyEnv);
        assertEquals(DiscordTestResult.Kind.ENV_MISSING, emptyEnv.kind());
        assertEquals("IML_EMPTY", emptyEnv.detail());
        assertNoUrlLeak(emptyEnv);

        // Invalid env var name (including accidental secret URL after env:), non-https, and malformed URI -> INVALID_URL with NO detail.
        String[] invalidConfigs = {
                "env:",
                "env:bad-name",
                "env:" + SECRET_URL,
                "ENV:IML_GOOD",
                SECRET_HTTP_URL,
                SECRET_MALFORMED_URL,
                "env:IML_HTTP",
                "env:IML_BAD"
        };
        for (String cfg : invalidConfigs) {
            DiscordTestResult res = DiscordTestResult.classifyPreCheck(DiscordWebhookUrl.resolve(cfg, env::get));
            assertNotNull(res, "cfg=" + cfg);
            assertEquals(DiscordTestResult.Kind.INVALID_URL, res.kind(), "cfg=" + cfg);
            assertFalse(res.isSuccess(), "cfg=" + cfg);
            assertNull(res.detail(), "cfg=" + cfg);
            assertEquals("message.itemmilestonelog.discordtest_invalid_url", res.messageKey());
            assertNoUrlLeak(res);
        }
    }

    @Test
    void inProgressReturnsExpectedKindAndKey() {
        DiscordTestResult res = DiscordTestResult.inProgress();
        assertEquals(DiscordTestResult.Kind.IN_PROGRESS, res.kind());
        assertFalse(res.isSuccess());
        assertNull(res.detail());
        assertEquals("message.itemmilestonelog.discordtest_in_progress", res.messageKey());
    }

    // MN-3: the explicit abandoned outcome must be a failure with no detail or URL/token leak.
    @Test
    void abandonedOutcomeHasNoDetailAndIsNotASuccess() {
        DiscordTestResult res = DiscordTestResult.abandoned();
        assertEquals(DiscordTestResult.Kind.ABANDONED, res.kind());
        assertFalse(res.isSuccess());
        assertFalse(res.hasDetail());
        assertNull(res.detail());
        assertEquals("message.itemmilestonelog.discordtest_abandoned", res.messageKey());
        assertNoUrlLeak(res);
    }

    private static void assertNoUrlLeak(DiscordTestResult result) {
        String rendered = result.toString();
        assertFalse(rendered.contains(SECRET_URL), "must never leak SECRET_URL: " + rendered);
        assertFalse(rendered.contains(SECRET_HTTP_URL), "must never leak SECRET_HTTP_URL: " + rendered);
        assertFalse(rendered.contains(SECRET_MALFORMED_URL), "must never leak SECRET_MALFORMED_URL: " + rendered);
        assertFalse(rendered.contains("top-secret-token"), "must never leak token: " + rendered);
        assertFalse(rendered.contains("top-secret-http"), "must never leak http token: " + rendered);
        assertFalse(rendered.contains("top-secret-bad"), "must never leak malformed token: " + rendered);
        if (result.detail() != null) {
            assertFalse(result.detail().contains("https://"));
            assertFalse(result.detail().contains("http://"));
            assertFalse(result.detail().contains("top-secret"));
        }
    }
}
