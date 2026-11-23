package com.pengin0503.itemmilestonelog.api;

import org.junit.jupiter.api.Test;

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
}
