package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The migration-compatibility matrix of spec-150-operations.md §1.2 at the deepest seam (the single
 * implementation is {@link MilestonePermissionLogic#check(boolean, boolean, boolean)}). Also pins
 * the node table: the nine F43 nodes must remain a byte-identical prefix and the seven F-150-51
 * nodes must be appended at the tail, in that order.
 */
class MilestonePermissionLogicTest {
    private static final int ADMIN_LEVEL = 2;
    private static final int[] LEVELS = {0, 1, 2, 3, 4};

    /** The original nine F43 nodes, in their unchanged order. */
    private static final List<String> LEGACY_NODES = List.of(
            "itemmilestonelog.admin",
            "itemmilestonelog.firsts.reset",
            "itemmilestonelog.export.all",
            "itemmilestonelog.import.all",
            "itemmilestonelog.prunedata",
            "itemmilestonelog.renameitem",
            "itemmilestonelog.discordtest",
            "itemmilestonelog.presets.manage",
            "itemmilestonelog.viewhidden");

    /** The seven F-150-51 nodes, appended after the legacy nine. */
    private static final List<String> STEP5_NODES = List.of(
            "itemmilestonelog.doctor",
            "itemmilestonelog.debug",
            "itemmilestonelog.discordstatus",
            "itemmilestonelog.discorddigest",
            "itemmilestonelog.backup.manage",
            "itemmilestonelog.backup.view",
            "itemmilestonelog.audit.view");

    private static final List<String> SPEC_NODES = concat(LEGACY_NODES, STEP5_NODES);

    private static List<String> concat(List<String> a, List<String> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }

    @Test
    void nodeTableKeepsLegacyNineAndAppendsSeven() {
        assertEquals(SPEC_NODES, MilestonePermissionLogic.NODE_NAMES);
        assertEquals(Set.copyOf(SPEC_NODES), Set.copyOf(MilestonePermissionLogic.NODE_NAMES),
                "all sixteen nodes must be distinct");
        assertEquals(LEGACY_NODES, MilestonePermissionLogic.NODE_NAMES.subList(0, 9),
                "the nine F43 nodes must keep their names and order");
        assertEquals(STEP5_NODES, MilestonePermissionLogic.NODE_NAMES.subList(9, 16),
                "the seven new nodes must be appended at the tail");
    }

    @Test
    void nonPlayerSourcesUseTheLegacyLevelCheckOnly() {
        // Non-player (console / RCON / command block / function) x level 0-4 x node: the node value
        // must never add access, and the implementation must not need to query it.
        for (int level : LEVELS) {
            boolean legacy = level >= ADMIN_LEVEL;
            assertFalse(MilestonePermissionLogic.check(false, legacy, false), "level " + level);
            assertEquals(legacy, MilestonePermissionLogic.check(false, legacy, true), "level " + level);
        }
        // A non-player never gains access from a node alone, at any level.
        assertFalse(MilestonePermissionLogic.check(false, false, true));
        assertTrue(MilestonePermissionLogic.check(false, true, true));
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
    void fullCompatMatrixNeverRevokesExistingAccess() {
        // (player source, legacy level 2, node granted) -> allowed. Eight rows, no shortcuts.
        assertFalse(MilestonePermissionLogic.check(true, false, false), "player, neither");
        assertTrue(MilestonePermissionLogic.check(true, false, true), "player, node only gains access");
        assertTrue(MilestonePermissionLogic.check(true, true, false), "player, legacy-only keeps access");
        assertTrue(MilestonePermissionLogic.check(true, true, true), "player, both");
        assertFalse(MilestonePermissionLogic.check(false, false, false), "non-player, neither");
        assertFalse(MilestonePermissionLogic.check(false, false, true), "non-player, node is ignored");
        // Non-player callers pass nodeGranted=true so the shared check reduces to legacy only;
        // passing false models an explicit denial and must not grant access.
        assertFalse(MilestonePermissionLogic.check(false, true, false), "non-player, explicit denial");
        assertTrue(MilestonePermissionLogic.check(false, true, true), "non-player, legacy-only keeps access");

        // For every node name in the table, a legacy-permitted player stays permitted whether the
        // permission handler says true, false, or has no handler at all (nodeGranted = false).
        for (String node : MilestonePermissionLogic.NODE_NAMES) {
            assertTrue(MilestonePermissionLogic.check(true, true, false), "legacy holder, " + node);
            assertTrue(MilestonePermissionLogic.check(true, true, true), "both holders, " + node);
        }
    }
}
