package com.pengin0503.itemmilestonelog.api;

import com.pengin0503.itemmilestonelog.MilestoneData;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F32: argument and thread checks of the write API that run before any server state is touched. */
class ItemMilestoneLogApiTest {
    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final TargetKey DIAMOND = TargetKey.of("minecraft:diamond");

    @Test
    void requireServerThreadThrowsIllegalStateExceptionOffThread() {
        assertDoesNotThrow(() -> ItemMilestoneLogApi.requireServerThread(true, "addTarget"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ItemMilestoneLogApi.requireServerThread(false, "recordNow"));
        assertTrue(e.getMessage().contains("recordNow"));
        assertTrue(e.getMessage().contains("server thread"));
    }

    @Test
    void writeMethodsThrowNullPointerExceptionForNullServerOrPlayer() {
        assertThrows(NullPointerException.class, () -> ItemMilestoneLogApi.addTarget(null, PLAYER, DIAMOND));
        assertThrows(NullPointerException.class, () -> ItemMilestoneLogApi.removeTarget(null, PLAYER, DIAMOND));
        assertThrows(NullPointerException.class, () -> ItemMilestoneLogApi.recordNow(null, DIAMOND));
        // null target with null server/player still reports the missing context first
        assertThrows(NullPointerException.class, () -> ItemMilestoneLogApi.addTarget(null, PLAYER, null));
        assertThrows(NullPointerException.class, () -> ItemMilestoneLogApi.recordNow(null, null));
    }

    @Test
    void nestingDepthLimitIsSixteen() {
        assertEquals(16, ItemMilestoneLogApi.MAX_RECORD_NOW_DEPTH);
    }

    @Test
    void tagKeysAreRepresentedWithIsTagAndTagId() {
        TargetKey logs = new TargetKey("#minecraft:logs", 1);
        assertTrue(logs.isTag());
        assertEquals("minecraft:logs", logs.tagId());
        assertEquals(logs, TargetKey.normalize("#minecraft:logs", 1));
        assertFalse(DIAMOND.isTag());
        assertNull(DIAMOND.tagId());

        assertThrows(IllegalArgumentException.class, () -> new TargetKey("##minecraft:logs", 1));
        assertNull(TargetKey.normalize("#:logs", 1));
    }

    @Test
    void readApiNormalizesImplicitMinecraftNamespaceLikeWriteApi() {
        MilestoneData data = new MilestoneData();
        MilestoneData.PlayerData pd = data.player(PLAYER);
        TargetKey stored = TargetKey.normalize("diamond", 1);
        assertEquals(DIAMOND, stored);
        MilestoneRecord rec = new MilestoneRecord(1200, 3L, 1_700_000_000_000L);
        pd.records.put(stored, rec);
        data.recordWorldFirstIfAbsent(stored, PLAYER, "Alice", rec);

        // Read APIs use the same implicit-namespace normalization as addTarget/recordNow.
        assertEquals(Optional.of(rec), ItemMilestoneLogApi.recordFor(pd, TargetKey.of("diamond")));
        assertEquals(Optional.of(rec), ItemMilestoneLogApi.recordFor(pd, DIAMOND));
        assertEquals(Optional.of(data.worldFirstOrNull(stored)),
                ItemMilestoneLogApi.worldFirstFor(data, TargetKey.of("diamond")));
        assertEquals(Optional.of(data.worldFirstOrNull(stored)),
                ItemMilestoneLogApi.worldFirstFor(data, DIAMOND));

        // Only the normalized key is stored; the read path does not rewrite SavedData.
        assertTrue(pd.records.containsKey(DIAMOND));
        assertFalse(pd.records.containsKey(TargetKey.of("diamond")));
        assertEquals(Optional.empty(), ItemMilestoneLogApi.recordFor(pd, TargetKey.of("diamond", 2)));
        assertEquals(Optional.empty(), ItemMilestoneLogApi.worldFirstFor(null, DIAMOND));
    }

}
