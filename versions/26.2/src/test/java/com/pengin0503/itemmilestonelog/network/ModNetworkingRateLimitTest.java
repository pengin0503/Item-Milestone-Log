package com.pengin0503.itemmilestonelog.network;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModNetworkingRateLimitTest {
    private static int capacity() throws Exception {
        Field f = ModNetworking.class.getDeclaredField("MAX_BURST_TOKENS");
        f.setAccessible(true);
        return f.getInt(null);
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
    void removeRateLimitResetsThePlayersBucket() throws Exception {
        UUID id = UUID.randomUUID();
        try {
            drain(id);
            assertFalse(ModNetworking.tryAcquireRateLimit(id));
            ModNetworking.removeRateLimit(id);
            for (int i = 0; i < capacity(); i++) {
                assertTrue(ModNetworking.tryAcquireRateLimit(id), "full burst expected after removal, failed at " + i);
            }
        } finally {
            ModNetworking.removeRateLimit(id);
        }
    }

    @Test
    void idleEntryIsTreatedAsFresh() throws Exception {
        UUID id = UUID.randomUUID();
        try {
            drain(id);
            assertFalse(ModNetworking.tryAcquireRateLimit(id));

            // Age the bucket well past the idle threshold (60s) so it must behave like a fresh one.
            Object state = buckets().get(id);
            Field last = state.getClass().getDeclaredField("lastNanos");
            last.setAccessible(true);
            last.setLong(state, System.nanoTime() - 120_000_000_000L);

            for (int i = 0; i < capacity(); i++) {
                assertTrue(ModNetworking.tryAcquireRateLimit(id), "idle bucket should be refilled, failed at " + i);
            }
            assertFalse(ModNetworking.tryAcquireRateLimit(id));
        } finally {
            ModNetworking.removeRateLimit(id);
        }
    }
}
