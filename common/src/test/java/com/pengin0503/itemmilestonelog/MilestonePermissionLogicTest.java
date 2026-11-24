package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F43 check matrix at the deepest seam: non-player sources use only the legacy level check, while
 * player sources use the legacy check OR the permission node. Also pins the node table itself.
 */
class MilestonePermissionLogicTest {
    private static final int ADMIN_LEVEL = 2;
    private static final int[] LEVELS = {0, 1, 2, 3, 4};

    private static final Set<String> SPEC_NODES = Set.of(
            "itemmilestonelog.admin",
            "itemmilestonelog.firsts.reset",
            "itemmilestonelog.export.all",
            "itemmilestonelog.import.all",
            "itemmilestonelog.prunedata",
            "itemmilestonelog.renameitem",
            "itemmilestonelog.discordtest",
            "itemmilestonelog.presets.manage",
            "itemmilestonelog.viewhidden");

    @Test
    void nonPlayerSourcesUseTheLegacyLevelCheckOnly() {
        // Non-player (console / RCON / command block / function) x level 0-4 x node: the node value
        // must never add access, and the implementation must not need to query it.
        for (int level : LEVELS) {
            boolean legacy = level >= ADMIN_LEVEL;
            assertFalse(MilestonePermissionLogic.check(false, legacy, false), "level " + level);
            assertEquals(legacy, MilestonePermissionLogic.check(false, legacy, true), "level " + level);
        }
    }

    @Test
    void playerSourcesUseLegacyLevelOrNode() {
        // Player x level 0-4 x node true/false.
        for (int level : LEVELS) {
            boolean legacy = level >= ADMIN_LEVEL;
            assertEquals(legacy, MilestonePermissionLogic.check(true, legacy, false), "level " + level);
            assertTrue(MilestonePermissionLogic.check(true, legacy, true), "level " + level);
        }
        // Explicit false/true matrix for the two permission levels that matter most.
        assertFalse(MilestonePermissionLogic.check(true, false, false));
        assertTrue(MilestonePermissionLogic.check(true, false, true));
        assertTrue(MilestonePermissionLogic.check(true, true, false));
        assertTrue(MilestonePermissionLogic.check(true, true, true));
    }

    @Test
    void nodeTableMatchesTheSpecification() {
        assertEquals(SPEC_NODES, Set.copyOf(MilestonePermissionLogic.NODE_NAMES));
        assertEquals(List.of(
                        "itemmilestonelog.admin",
                        "itemmilestonelog.firsts.reset",
                        "itemmilestonelog.export.all",
                        "itemmilestonelog.import.all",
                        "itemmilestonelog.prunedata",
                        "itemmilestonelog.renameitem",
                        "itemmilestonelog.discordtest",
                        "itemmilestonelog.presets.manage",
                        "itemmilestonelog.viewhidden"),
                MilestonePermissionLogic.NODE_NAMES);
    }
}
