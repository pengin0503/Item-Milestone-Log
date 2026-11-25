package com.pengin0503.itemmilestonelog.network;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModNetworkingRateLimitTest {
    private static int capacity() throws Exception {
        Field f = ModNetworking.class.getDeclaredField("MAX_BURST_TOKENS");
        f.setAccessible(true);
        return f.getInt(null);
    }

    private static long refillIntervalNanos() throws Exception {
        Field f = ModNetworking.class.getDeclaredField("REFILL_INTERVAL_NANOS");
        f.setAccessible(true);
        return f.getLong(null);
    }

    private static int maxEntries() throws Exception {
        Field f = ModNetworking.class.getDeclaredField("MAX_RATE_LIMIT_ENTRIES");
        f.setAccessible(true);
        return f.getInt(null);
    }

    private static long idleNanos() throws Exception {
        Field f = ModNetworking.class.getDeclaredField("RATE_LIMIT_IDLE_NANOS");
        f.setAccessible(true);
        return f.getLong(null);
    }

    private static long cleanupCooldownNanos() throws Exception {
        Field f = ModNetworking.class.getDeclaredField("CLEANUP_COOLDOWN_NANOS");
        f.setAccessible(true);
        return f.getLong(null);
    }

    private static Field lastCleanupNanosField() throws Exception {
        Field f = ModNetworking.class.getDeclaredField("lastCleanupNanos");
        f.setAccessible(true);
        return f;
    }

    private static Field lastNanosField(Object state) throws Exception {
        Field f = state.getClass().getDeclaredField("lastNanos");
        f.setAccessible(true);
        return f;
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> buckets() throws Exception {
        Field f = ModNetworking.class.getDeclaredField("RATE_LIMITS");
        f.setAccessible(true);
        return (Map<UUID, Object>) f.get(null);
    }

    private static int drain(UUID id) throws Exception {
        int granted = 0;
        for (int i = 0; i < capacity() + 5; i++) {
            if (ModNetworking.tryAcquireRateLimit(id)) {
                granted++;
            }
        }
        return granted;
    }

    @Test
    void allowsABurstUpToCapacityThenDenies() throws Exception {
        UUID id = UUID.randomUUID();
        try {
            int cap = capacity();
            for (int i = 0; i < cap; i++) {
                assertTrue(ModNetworking.tryAcquireRateLimit(id), "burst request " + i + " should be allowed");
            }
            assertFalse(ModNetworking.tryAcquireRateLimit(id), "request beyond capacity should be denied");
        } finally {
            ModNetworking.removeRateLimit(id);
        }
    }

    @Test
    void bucketsAreIndependentPerPlayer() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        try {
            drain(a);
            assertFalse(ModNetworking.tryAcquireRateLimit(a));
            assertTrue(ModNetworking.tryAcquireRateLimit(b));
        } finally {
            ModNetworking.removeRateLimit(a);
            ModNetworking.removeRateLimit(b);
        }
    }

    @Test
    void removeRateLimitEvictsEntryAndResetsThePlayersBucket() throws Exception {
        UUID id = UUID.randomUUID();
        try {
            drain(id);
            assertTrue(buckets().containsKey(id));
            assertFalse(ModNetworking.tryAcquireRateLimit(id));
            ModNetworking.removeRateLimit(id);
            assertFalse(buckets().containsKey(id), "logout removal should evict the entry from the map");
            for (int i = 0; i < capacity(); i++) {
                assertTrue(ModNetworking.tryAcquireRateLimit(id), "full burst expected after removal, failed at " + i);
            }
        } finally {
            ModNetworking.removeRateLimit(id);
        }
    }

    @Test
    void partialRefillPreservesFractionalRemainder() throws Exception {
        UUID id = UUID.randomUUID();
        try {
            drain(id);
            assertFalse(ModNetworking.tryAcquireRateLimit(id));

            long interval = refillIntervalNanos();
            Object state = buckets().get(id);
            Field last = lastNanosField(state);

            // Elapsed = 1.5 intervals -> refills 1 token and keeps ~0.5 interval remainder.
            long startLast = System.nanoTime() - (interval + interval / 2);
            last.setLong(state, startLast);

            assertTrue(ModNetworking.tryAcquireRateLimit(id), "1.5 intervals should grant 1 token");
            long afterFirstRefill = last.getLong(state);
            assertEquals(startLast + interval, afterFirstRefill,
                    "partial refill must advance lastNanos by refill * interval so the fractional remainder is preserved");
            assertFalse(ModNetworking.tryAcquireRateLimit(id), "remaining ~0.5 interval should not grant a second token yet");

            // Shift back by 0.6 interval: combined with the ~0.5 remainder this exceeds 1.0 interval,
            // whereas dropping the remainder on the previous refill would only leave 0.6 < 1.0 interval.
            last.setLong(state, afterFirstRefill - (interval * 3 / 5));
            assertTrue(ModNetworking.tryAcquireRateLimit(id), "preserved remainder + 0.6 interval should grant the next token");
        } finally {
            ModNetworking.removeRateLimit(id);
        }
    }

    @Test
    void idleEntryIsTreatedAsFreshAndCappedAtCapacity() throws Exception {
        UUID id = UUID.randomUUID();
        try {
            drain(id);
            assertFalse(ModNetworking.tryAcquireRateLimit(id));

            // Age the bucket well past the idle threshold (60s) so it must refill up to capacity and cap there.
            Object state = buckets().get(id);
            Field last = lastNanosField(state);
            last.setLong(state, System.nanoTime() - 120_000_000_000L);

            for (int i = 0; i < capacity(); i++) {
                assertTrue(ModNetworking.tryAcquireRateLimit(id), "idle bucket should be refilled, failed at " + i);
            }
            assertFalse(ModNetworking.tryAcquireRateLimit(id), "refill must not bank tokens beyond capacity");
        } finally {
            ModNetworking.removeRateLimit(id);
        }
    }

    @Test
    void cleanupEvictsIdleEntriesWhileKeepingActiveEntries() throws Exception {
        List<UUID> created = new ArrayList<>();
        Field lastCleanup = lastCleanupNanosField();
        long originalCleanup = lastCleanup.getLong(null);
        try {
            // Keep cleanup suppressed while populating above MAX_RATE_LIMIT_ENTRIES.
            lastCleanup.setLong(null, System.nanoTime());
            int count = maxEntries() + 1;
            for (int i = 0; i < count; i++) {
                UUID u = UUID.randomUUID();
                created.add(u);
                assertTrue(ModNetworking.tryAcquireRateLimit(u));
            }

            // Mark the first 10 entries as idle past RATE_LIMIT_IDLE_NANOS.
            long staleTimestamp = System.nanoTime() - idleNanos() - 10_000_000_000L;
            List<UUID> idleIds = new ArrayList<>(created.subList(0, 10));
            for (UUID idleId : idleIds) {
                Object state = buckets().get(idleId);
                lastNanosField(state).setLong(state, staleTimestamp);
            }

            // Drain one active entry so we can verify cleanup does not reset active players' limits.
            UUID activeDrained = created.get(10);
            drain(activeDrained);
            assertFalse(ModNetworking.tryAcquireRateLimit(activeDrained));

            // Expire cleanup cooldown and trigger cleanup with a new request.
            lastCleanup.setLong(null, System.nanoTime() - cleanupCooldownNanos() - 1_000_000_000L);
            UUID trigger = UUID.randomUUID();
            created.add(trigger);
            assertTrue(ModNetworking.tryAcquireRateLimit(trigger));

            for (UUID idleId : idleIds) {
                assertFalse(buckets().containsKey(idleId), "idle entry should have been evicted: " + idleId);
            }
            assertTrue(buckets().containsKey(activeDrained), "active entry should be kept");
            assertFalse(ModNetworking.tryAcquireRateLimit(activeDrained),
                    "active drained player must remain rate-limited after idle cleanup");
        } finally {
            for (UUID u : created) {
                ModNetworking.removeRateLimit(u);
            }
            lastCleanup.setLong(null, originalCleanup);
        }
    }

    @Test
    void cleanupRespectsCooldownInterval() throws Exception {
        List<UUID> created = new ArrayList<>();
        Field lastCleanup = lastCleanupNanosField();
        long originalCleanup = lastCleanup.getLong(null);
        try {
            lastCleanup.setLong(null, System.nanoTime());
            int count = maxEntries() + 1;
            for (int i = 0; i < count; i++) {
                UUID u = UUID.randomUUID();
                created.add(u);
                assertTrue(ModNetworking.tryAcquireRateLimit(u));
            }

            UUID idleId = created.get(0);
            Object idleState = buckets().get(idleId);
            lastNanosField(idleState).setLong(idleState, System.nanoTime() - idleNanos() - 10_000_000_000L);

            // Cooldown has not elapsed yet: cleanup must not run even though size > MAX_RATE_LIMIT_ENTRIES.
            UUID triggerDuringCooldown = UUID.randomUUID();
            created.add(triggerDuringCooldown);
            assertTrue(ModNetworking.tryAcquireRateLimit(triggerDuringCooldown));
            assertTrue(buckets().containsKey(idleId), "idle entry must not be evicted before cleanup cooldown elapses");

            // Once cooldown elapses, cleanup runs and evicts the idle entry.
            long expiredCleanup = System.nanoTime() - cleanupCooldownNanos() - 1_000_000_000L;
            lastCleanup.setLong(null, expiredCleanup);
            UUID triggerAfterCooldown = UUID.randomUUID();
            created.add(triggerAfterCooldown);
            assertTrue(ModNetworking.tryAcquireRateLimit(triggerAfterCooldown));
            assertFalse(buckets().containsKey(idleId), "idle entry should be evicted once cleanup cooldown has elapsed");
            assertTrue(lastCleanup.getLong(null) > expiredCleanup, "cleanup should update lastCleanupNanos");
        } finally {
            for (UUID u : created) {
                ModNetworking.removeRateLimit(u);
            }
            lastCleanup.setLong(null, originalCleanup);
        }
    }
}
