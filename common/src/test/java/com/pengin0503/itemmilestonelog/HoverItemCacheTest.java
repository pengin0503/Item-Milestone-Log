package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-150-59: 500 ms expiry, empty-stack handling, and reference separation from the tooltip stack. */
class HoverItemCacheTest {
    private static HoverItemCache.StackView view(String itemId, int count) {
        return new HoverItemCache.StackView() {
            @Override
            public String itemId() {
                return itemId;
            }

            @Override
            public int count() {
                return count;
            }
        };
    }

    @Test
    void captureIsReadableInsideTheFiveHundredMillisecondWindow() {
        HoverItemCache cache = new HoverItemCache();
        cache.capture(view("minecraft:diamond", 7), 10_000L);
        assertEquals(new HoverItemCache.Entry("minecraft:diamond", 7), cache.get(10_000L));
        assertEquals(new HoverItemCache.Entry("minecraft:diamond", 7), cache.get(10_500L));
        assertNull(cache.get(10_501L), "expired captures must not add an item");
        assertNull(cache.get(9_999L), "a backwards clock is treated as expired");
    }

    @Test
    void expiryClearsTheStaleEntryAndClearDropsItImmediately() {
        HoverItemCache cache = new HoverItemCache();
        cache.capture(view("minecraft:iron_ingot", 1), 0L);
        assertNull(cache.get(HoverItemCache.TTL_MILLIS + 1L));
        assertNull(cache.get(1L), "the expired entry must not become readable again");

        cache.capture(view("minecraft:iron_ingot", 1), 100L);
        cache.clear();
        assertNull(cache.get(100L));
    }

    @Test
    void emptyBlankAndNullStacksNeverProduceAnEntry() {
        HoverItemCache cache = new HoverItemCache();
        cache.capture(null, 0L);
        assertNull(cache.get(0L));
        cache.capture(view("", 4), 0L);
        assertNull(cache.get(0L));
        cache.capture(view("minecraft:stone", 0), 0L);
        assertNull(cache.get(0L));
        cache.capture(view("minecraft:stone", -1), 0L);
        assertNull(cache.get(0L));
    }

    @Test
    void snapshotDoesNotChangeWhenTheSourceStackChangesLater() {
        // Models ItemStack.copy(): the listener copies the stack before passing its fields here.
        final class MutableStack implements HoverItemCache.StackView {
            private String itemId = "minecraft:diamond";
            private int count = 3;

            @Override
            public String itemId() {
                return itemId;
            }

            @Override
            public int count() {
                return count;
            }
        }
        MutableStack source = new MutableStack();
        HoverItemCache cache = new HoverItemCache();
        cache.capture(new HoverItemCache.StackView() {
            @Override
            public String itemId() {
                return source.itemId();
            }

            @Override
            public int count() {
                return source.count();
            }
        }, 0L);
        source.itemId = "minecraft:dirt";
        source.count = 64;

        HoverItemCache.Entry entry = cache.get(100L);
        assertEquals("minecraft:diamond", entry.itemId());
        assertEquals(3, entry.count());
        assertTrue(!entry.isEmpty());
    }
}
