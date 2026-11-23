package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordWebhookUrlTest {
    private static final String SECRET_URL = "https://discord.com/api/webhooks/123456/top-secret-token";
    private static final String SECRET_HTTP_URL = "http://discord.com/api/webhooks/123456/top-secret-http-token";
    private static final String SECRET_MALFORMED_URL = "https://discord.com/api/webhooks/bad space/top-secret-bad";

    @Test
    void emptyAndBlankConfiguredValuesResolveAsEmptyWithoutWarning() {
        AtomicInteger lookups = new AtomicInteger();
        for (String input : new String[]{null, "", "   ", "\t\n"}) {
            DiscordWebhookUrl.Resolution res = DiscordWebhookUrl.resolve(input, k -> {
                lookups.incrementAndGet();
                return SECRET_URL;
            });
            assertEquals(DiscordWebhookUrl.Status.EMPTY, res.status());
            assertFalse(res.isResolved());
            assertFalse(res.isEnvResolutionError());
            assertNull(res.uri());
            assertNull(res.resolvedUrl());
            assertNull(res.warningMessage());
        }
        assertEquals(0, lookups.get());
    }

    @Test
    void plainHttpsUrlResolvesWithoutInvokingEnvLookup() {
        AtomicInteger lookups = new AtomicInteger();
        DiscordWebhookUrl.Resolution res = DiscordWebhookUrl.resolve("  " + SECRET_URL + "  ", k -> {
            lookups.incrementAndGet();
            return null;
        });

        assertEquals(DiscordWebhookUrl.Status.RESOLVED, res.status());
        assertTrue(res.isResolved());
        assertFalse(res.fromEnv());
        assertNull(res.envVarName());
        assertEquals(SECRET_URL, res.resolvedUrl());
        assertEquals(SECRET_URL, res.uri().toString());
        assertNull(res.warningMessage());
        assertEquals(0, lookups.get());
    }

    @Test
    void envReferenceResolvesWhenVariableIsPresent() {
        Map<String, String> env = Map.of(
                "IML_DISCORD_WEBHOOK", "  " + SECRET_URL + " \t",
                "_hook_2", "HTTPS://discord.com/api/webhooks/999/upper-scheme?thread_id=42"
        );

        DiscordWebhookUrl.Resolution res1 = DiscordWebhookUrl.resolve("  env:IML_DISCORD_WEBHOOK  ", env::get);
        assertEquals(DiscordWebhookUrl.Status.RESOLVED, res1.status());
        assertTrue(res1.isResolved());
        assertTrue(res1.fromEnv());
        assertEquals("IML_DISCORD_WEBHOOK", res1.envVarName());
        assertEquals(SECRET_URL, res1.resolvedUrl());
        assertEquals("env:IML_DISCORD_WEBHOOK", res1.configuredKey());
        assertNull(res1.warningMessage());

        DiscordWebhookUrl.Resolution res2 = DiscordWebhookUrl.resolve("env:_hook_2", env::get);
        assertEquals(DiscordWebhookUrl.Status.RESOLVED, res2.status());
        assertTrue(res2.isResolved());
        assertEquals("_hook_2", res2.envVarName());
        assertEquals("HTTPS://discord.com/api/webhooks/999/upper-scheme?thread_id=42", res2.resolvedUrl());
    }

    @Test
    void envReferenceMissingVariableReturnsMissingStatusAndSafeWarning() {
        DiscordWebhookUrl.Resolution res = DiscordWebhookUrl.resolve("env:IML_MISSING_HOOK", k -> null);

        assertEquals(DiscordWebhookUrl.Status.MISSING_ENV_VAR, res.status());
        assertFalse(res.isResolved());
        assertTrue(res.isEnvResolutionError());
        assertTrue(res.fromEnv());
        assertEquals("IML_MISSING_HOOK", res.envVarName());
        assertNull(res.uri());
        assertNull(res.resolvedUrl());
        assertNotNull(res.warningMessage());
        assertTrue(res.warningMessage().contains("IML_MISSING_HOOK"));
    }

    @Test
    void envReferenceEmptyOrBlankVariableReturnsEmptyStatusAndSafeWarning() {
        for (String value : new String[]{"", "   ", "\t\n"}) {
            DiscordWebhookUrl.Resolution res = DiscordWebhookUrl.resolve("env:IML_EMPTY_HOOK", k -> value);

            assertEquals(DiscordWebhookUrl.Status.EMPTY_ENV_VAR, res.status());
            assertFalse(res.isResolved());
            assertTrue(res.isEnvResolutionError());
            assertTrue(res.fromEnv());
            assertEquals("IML_EMPTY_HOOK", res.envVarName());
            assertNull(res.uri());
            assertNull(res.resolvedUrl());
            assertNotNull(res.warningMessage());
            assertTrue(res.warningMessage().contains("IML_EMPTY_HOOK"));
        }
    }

    @Test
    void envReferenceInvalidVariableNamesAreRejectedWithoutCallingLookupOrLeakingInput() {
        String accidentalSecret = "env:" + SECRET_URL;
        String[] invalidRefs = {
                "env:",
                "env:1LEADING_DIGIT",
                "env:HAS-HYPHEN",
                "env:HAS.DOT",
                "env: HAS_LEADING_SPACE",
                "env:HAS SPACE",
                "env:NON_ASCII_\u3042",
                "env:VAR$NAME",
                accidentalSecret
        };

        AtomicInteger lookups = new AtomicInteger();
        for (String ref : invalidRefs) {
            DiscordWebhookUrl.Resolution res = DiscordWebhookUrl.resolve(ref, k -> {
                lookups.incrementAndGet();
                return SECRET_URL;
            });

            assertEquals(DiscordWebhookUrl.Status.INVALID_ENV_VAR_NAME, res.status(), "ref=" + ref);
            assertFalse(res.isResolved(), "ref=" + ref);
            assertTrue(res.isEnvResolutionError(), "ref=" + ref);
            assertTrue(res.fromEnv(), "ref=" + ref);
            assertNull(res.envVarName(), "ref=" + ref);
            assertNull(res.uri(), "ref=" + ref);
            assertNull(res.resolvedUrl(), "ref=" + ref);
            String warn = res.warningMessage();
            assertNotNull(warn, "ref=" + ref);
            assertFalse(warn.contains(SECRET_URL), "warn must never contain secret URL");
            assertFalse(warn.contains("top-secret-token"), "warn must never contain secret token");
        }
        assertEquals(0, lookups.get());
    }

    @Test
    void uppercaseAndMixedCaseEnvPrefixAreNotTreatedAsEnvReference() {
        AtomicInteger lookups = new AtomicInteger();
        for (String ref : new String[]{"ENV:IML_DISCORD_WEBHOOK", "Env:IML_DISCORD_WEBHOOK", "eNv:IML_DISCORD_WEBHOOK"}) {
            DiscordWebhookUrl.Resolution res = DiscordWebhookUrl.resolve(ref, k -> {
                lookups.incrementAndGet();
                return SECRET_URL;
            });

            assertEquals(DiscordWebhookUrl.Status.INVALID_SCHEME, res.status(), "ref=" + ref);
            assertFalse(res.isResolved(), "ref=" + ref);
            assertFalse(res.fromEnv(), "ref=" + ref);
            assertFalse(res.isEnvResolutionError(), "ref=" + ref);
            assertNull(res.envVarName(), "ref=" + ref);
            assertNull(res.uri(), "ref=" + ref);
            String warn = res.warningMessage();
            assertNotNull(warn);
            assertTrue(warn.contains("https"));
            assertFalse(warn.contains(SECRET_URL));
            assertFalse(warn.contains("IML_DISCORD_WEBHOOK"));
        }
        assertEquals(0, lookups.get());
    }

    @Test
    void httpsValidationAppliesToEnvValueAndWarnNeverContainsSecretValue() {
        // Non-https scheme in env var value
        DiscordWebhookUrl.Resolution httpRes = DiscordWebhookUrl.resolve("env:IML_HTTP_HOOK", k -> SECRET_HTTP_URL);
        assertEquals(DiscordWebhookUrl.Status.INVALID_SCHEME, httpRes.status());
        assertFalse(httpRes.isResolved());
        assertTrue(httpRes.fromEnv());
        assertEquals("IML_HTTP_HOOK", httpRes.envVarName());
        assertNull(httpRes.uri());
        assertNotNull(httpRes.warningMessage());
        assertTrue(httpRes.warningMessage().contains("https"));
        assertFalse(httpRes.warningMessage().contains(SECRET_HTTP_URL));
        assertFalse(httpRes.warningMessage().contains("top-secret-http-token"));

        // Schemeless URL in env var value
        String schemelessSecret = "discord.com/api/webhooks/123/secret-no-scheme";
        DiscordWebhookUrl.Resolution noSchemeRes = DiscordWebhookUrl.resolve("env:IML_NOSCHEME", k -> schemelessSecret);
        assertEquals(DiscordWebhookUrl.Status.INVALID_SCHEME, noSchemeRes.status());
        assertFalse(noSchemeRes.isResolved());
        assertNotNull(noSchemeRes.warningMessage());
        assertFalse(noSchemeRes.warningMessage().contains(schemelessSecret));
        assertFalse(noSchemeRes.warningMessage().contains("secret-no-scheme"));

        // Malformed URI in env var value
        DiscordWebhookUrl.Resolution malformedRes = DiscordWebhookUrl.resolve("env:IML_BAD_URI", k -> SECRET_MALFORMED_URL);
        assertEquals(DiscordWebhookUrl.Status.MALFORMED_URL, malformedRes.status());
        assertFalse(malformedRes.isResolved());
        assertTrue(malformedRes.fromEnv());
        assertNull(malformedRes.uri());
        assertNotNull(malformedRes.warningMessage());
        assertFalse(malformedRes.warningMessage().contains(SECRET_MALFORMED_URL));
        assertFalse(malformedRes.warningMessage().contains("top-secret-bad"));

        // Plain non-https URL also fails without leaking value
        DiscordWebhookUrl.Resolution plainHttpRes = DiscordWebhookUrl.resolve(SECRET_HTTP_URL, k -> null);
        assertEquals(DiscordWebhookUrl.Status.INVALID_SCHEME, plainHttpRes.status());
        assertNotNull(plainHttpRes.warningMessage());
        assertFalse(plainHttpRes.warningMessage().contains(SECRET_HTTP_URL));
        assertFalse(plainHttpRes.warningMessage().contains("top-secret-http-token"));
    }

    @Test
    void isValidEnvVarNameMatchesStrictAsciiIdentifierPattern() {
        assertTrue(DiscordWebhookUrl.isValidEnvVarName("A"));
        assertTrue(DiscordWebhookUrl.isValidEnvVarName("_"));
        assertTrue(DiscordWebhookUrl.isValidEnvVarName("IML_DISCORD_WEBHOOK_1"));
        assertTrue(DiscordWebhookUrl.isValidEnvVarName("_a0_Z9"));

        assertFalse(DiscordWebhookUrl.isValidEnvVarName(null));
        assertFalse(DiscordWebhookUrl.isValidEnvVarName(""));
        assertFalse(DiscordWebhookUrl.isValidEnvVarName("0ABC"));
        assertFalse(DiscordWebhookUrl.isValidEnvVarName("A-B"));
        assertFalse(DiscordWebhookUrl.isValidEnvVarName("A.B"));
        assertFalse(DiscordWebhookUrl.isValidEnvVarName("A B"));
        assertFalse(DiscordWebhookUrl.isValidEnvVarName("A\n"));
        assertFalse(DiscordWebhookUrl.isValidEnvVarName("\uFF21"));
    }

    @Test
    void envWarnTrackerLogsOncePerDistinctConfiguredValueUntilChanged() {
        DiscordWebhookUrl.EnvWarnTracker tracker = new DiscordWebhookUrl.EnvWarnTracker();

        DiscordWebhookUrl.Resolution missingA = DiscordWebhookUrl.resolve("env:MISSING_A", k -> null);
        DiscordWebhookUrl.Resolution missingAPadded = DiscordWebhookUrl.resolve("  env:MISSING_A  ", k -> null);
        DiscordWebhookUrl.Resolution missingB = DiscordWebhookUrl.resolve("env:MISSING_B", k -> null);
        DiscordWebhookUrl.Resolution valid = DiscordWebhookUrl.resolve("env:GOOD", k -> SECRET_URL);
        DiscordWebhookUrl.Resolution empty = DiscordWebhookUrl.resolve("", k -> null);

        assertTrue(tracker.shouldWarn(missingA));
        assertFalse(tracker.shouldWarn(missingA));
        assertFalse(tracker.shouldWarn(missingAPadded));

        // Different configured value emits a new warning once.
        assertTrue(tracker.shouldWarn(missingB));
        assertFalse(tracker.shouldWarn(missingB));

        // Switching to valid or empty resets suppression so a future reversion warns once again.
        assertFalse(tracker.shouldWarn(valid));
        assertTrue(tracker.shouldWarn(missingA));
        assertFalse(tracker.shouldWarn(missingA));

        assertFalse(tracker.shouldWarn(empty));
        assertTrue(tracker.shouldWarn(missingA));
    }
}
