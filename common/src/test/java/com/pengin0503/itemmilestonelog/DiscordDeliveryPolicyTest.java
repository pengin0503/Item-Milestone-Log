package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordDeliveryPolicyTest {
    @Test
    void parseRetryAfterAcceptsValidIntegerAndFractionalSecondsUpToCap() {
        assertEquals(OptionalLong.of(0L), DiscordDeliveryPolicy.parseRetryAfterMillis("0"));
        assertEquals(OptionalLong.of(0L), DiscordDeliveryPolicy.parseRetryAfterMillis("0.0"));
        assertEquals(OptionalLong.of(0L), DiscordDeliveryPolicy.parseRetryAfterMillis("0.000"));
        assertEquals(OptionalLong.of(1L), DiscordDeliveryPolicy.parseRetryAfterMillis("0.0001"));
        assertEquals(OptionalLong.of(500L), DiscordDeliveryPolicy.parseRetryAfterMillis("0.5"));
        assertEquals(OptionalLong.of(1000L), DiscordDeliveryPolicy.parseRetryAfterMillis("1"));
        assertEquals(OptionalLong.of(1250L), DiscordDeliveryPolicy.parseRetryAfterMillis("1.25"));
        assertEquals(OptionalLong.of(1235L), DiscordDeliveryPolicy.parseRetryAfterMillis("1.2345"));
        assertEquals(OptionalLong.of(4999L), DiscordDeliveryPolicy.parseRetryAfterMillis("4.999"));
        assertEquals(OptionalLong.of(5000L), DiscordDeliveryPolicy.parseRetryAfterMillis("4.9991"));
        assertEquals(OptionalLong.of(5000L), DiscordDeliveryPolicy.parseRetryAfterMillis("5"));
        assertEquals(OptionalLong.of(5000L), DiscordDeliveryPolicy.parseRetryAfterMillis("5.0"));
        assertEquals(OptionalLong.of(5000L), DiscordDeliveryPolicy.parseRetryAfterMillis("5.000"));
        assertEquals(OptionalLong.of(2500L), DiscordDeliveryPolicy.parseRetryAfterMillis("  2.5 \t"));
        assertEquals(OptionalLong.of(5000L), DiscordDeliveryPolicy.parseRetryAfterMillis("005.00"));
    }

    @Test
    void parseRetryAfterRejectsValuesExceedingFiveSecondCap() {
        assertEquals(OptionalLong.empty(), DiscordDeliveryPolicy.parseRetryAfterMillis("5.0000000001"));
        assertEquals(OptionalLong.empty(), DiscordDeliveryPolicy.parseRetryAfterMillis("5.001"));
        assertEquals(OptionalLong.empty(), DiscordDeliveryPolicy.parseRetryAfterMillis("5.1"));
        assertEquals(OptionalLong.empty(), DiscordDeliveryPolicy.parseRetryAfterMillis("6"));
        assertEquals(OptionalLong.empty(), DiscordDeliveryPolicy.parseRetryAfterMillis("65"));
        assertEquals(OptionalLong.empty(), DiscordDeliveryPolicy.parseRetryAfterMillis("999999999999999999999999"));
    }

    @Test
    void parseRetryAfterRejectsMissingAndInvalidHeaders() {
        assertEquals(OptionalLong.empty(), DiscordDeliveryPolicy.parseRetryAfterMillis(null));
        assertEquals(OptionalLong.empty(), DiscordDeliveryPolicy.parseRetryAfterMillis(""));
        assertEquals(OptionalLong.empty(), DiscordDeliveryPolicy.parseRetryAfterMillis("   "));
        assertEquals(OptionalLong.empty(), DiscordDeliveryPolicy.parseRetryAfterMillis("-1"));
        assertEquals(OptionalLong.empty(), DiscordDeliveryPolicy.parseRetryAfterMillis("-0"));
        assertEquals(OptionalLong.empty(), DiscordDeliveryPolicy.parseRetryAfterMillis("-0.5"));
        assertEquals(OptionalLong.empty(), DiscordDeliveryPolicy.parseRetryAfterMillis("+1"));
        assertEquals(OptionalLong.empty(), DiscordDeliveryPolicy.parseRetryAfterMillis("abc"));
        assertEquals(OptionalLong.empty(), DiscordDeliveryPolicy.parseRetryAfterMillis("NaN"));
        assertEquals(OptionalLong.empty(), DiscordDeliveryPolicy.parseRetryAfterMillis("Infinity"));
        assertEquals(OptionalLong.empty(), DiscordDeliveryPolicy.parseRetryAfterMillis("-Infinity"));
        assertEquals(OptionalLong.empty(), DiscordDeliveryPolicy.parseRetryAfterMillis("1e1"));
        assertEquals(OptionalLong.empty(), DiscordDeliveryPolicy.parseRetryAfterMillis("1.5d"));
        assertEquals(OptionalLong.empty(), DiscordDeliveryPolicy.parseRetryAfterMillis("1.5f"));
        assertEquals(OptionalLong.empty(), DiscordDeliveryPolicy.parseRetryAfterMillis("1."));
        assertEquals(OptionalLong.empty(), DiscordDeliveryPolicy.parseRetryAfterMillis(".5"));
        assertEquals(OptionalLong.empty(), DiscordDeliveryPolicy.parseRetryAfterMillis("."));
        assertEquals(OptionalLong.empty(), DiscordDeliveryPolicy.parseRetryAfterMillis("1.2.3"));
        assertEquals(OptionalLong.empty(), DiscordDeliveryPolicy.parseRetryAfterMillis("Wed, 21 Oct 2015 07:28:00 GMT"));
        assertEquals(OptionalLong.empty(), DiscordDeliveryPolicy.parseRetryAfterMillis("\uFF11"));
    }

    @Test
    void statusClassifiersMatchSpec() {
        assertTrue(DiscordDeliveryPolicy.isDeadWebhookStatus(404));
        assertTrue(DiscordDeliveryPolicy.isDeadWebhookStatus(401));
        assertFalse(DiscordDeliveryPolicy.isDeadWebhookStatus(403));
        assertFalse(DiscordDeliveryPolicy.isDeadWebhookStatus(429));
        assertFalse(DiscordDeliveryPolicy.isDeadWebhookStatus(500));
        assertFalse(DiscordDeliveryPolicy.isDeadWebhookStatus(204));

        assertTrue(DiscordDeliveryPolicy.isSuccessStatus(200));
        assertTrue(DiscordDeliveryPolicy.isSuccessStatus(204));
        assertTrue(DiscordDeliveryPolicy.isSuccessStatus(299));
        assertFalse(DiscordDeliveryPolicy.isSuccessStatus(199));
        assertFalse(DiscordDeliveryPolicy.isSuccessStatus(300));
        assertFalse(DiscordDeliveryPolicy.isSuccessStatus(404));
    }

    @Test
    void deadWebhookTrackerSuppressesOnceAndResumesOnUrlChangeOrReset() {
        String url1 = "https://discord.com/api/webhooks/111/secret1";
        String url2 = "https://discord.com/api/webhooks/222/secret2";

        DiscordDeliveryPolicy.DeadWebhookTracker tracker = new DiscordDeliveryPolicy.DeadWebhookTracker();
        assertFalse(tracker.isDead(url1));
        assertFalse(tracker.syncCurrentUrlAndCheckDead(url1));

        // Null/empty URL cannot be marked dead.
        assertFalse(tracker.markDead(null));
        assertFalse(tracker.markDead(""));

        // First mark transitions live -> dead (returns true once); repeat returns false.
        assertTrue(tracker.markDead(url1));
        assertFalse(tracker.markDead(url1));
        assertTrue(tracker.isDead(url1));
        assertFalse(tracker.isDead(url2));
        assertTrue(tracker.syncCurrentUrlAndCheckDead(url1));

        // Changing the resolved URL clears suppression and resumes sending.
        assertFalse(tracker.syncCurrentUrlAndCheckDead(url2));
        assertFalse(tracker.isDead(url1));
        assertFalse(tracker.isDead(url2));

        // Marking again after reset/change transitions again.
        assertTrue(tracker.markDead(url1));
        assertTrue(tracker.syncCurrentUrlAndCheckDead(url1));

        // Clearing config to empty/null also clears dead state.
        assertFalse(tracker.syncCurrentUrlAndCheckDead(null));
        assertFalse(tracker.isDead(url1));

        // Explicit reset (F49 discordtest seam) resumes the same URL.
        assertTrue(tracker.markDead(url1));
        assertTrue(tracker.syncCurrentUrlAndCheckDead(url1));
        tracker.reset();
        assertFalse(tracker.isDead(url1));
        assertFalse(tracker.syncCurrentUrlAndCheckDead(url1));
    }

    @Test
    void shouldPostToDiscordRespectsAllowOptOutSwitchAndPlayerPreference() {
        assertTrue(DiscordDeliveryPolicy.shouldPostToDiscord(true, false));
        assertFalse(DiscordDeliveryPolicy.shouldPostToDiscord(true, true));
        assertTrue(DiscordDeliveryPolicy.shouldPostToDiscord(false, false));
        assertTrue(DiscordDeliveryPolicy.shouldPostToDiscord(false, true));
    }
}
