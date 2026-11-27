package com.pengin0503.itemmilestonelog;

import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F43 / F-150-51 static checks: the set of nodes offered to {@link PermissionGatherEvent.Nodes}
 * equals the sixteen-name table, all nodes share the level-2 default resolver, non-player sources
 * follow the legacy level only, and only the seven newly appended nodes carry localized metadata.
 */
class MilestonePermissionsTest {
    /** The table from spec-150-operations.md F-150-51, pinned independently of production code. */
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

    private static final List<String> STEP5_NODES = List.of(
            "itemmilestonelog.doctor",
            "itemmilestonelog.debug",
            "itemmilestonelog.discordstatus",
            "itemmilestonelog.discorddigest",
            "itemmilestonelog.backup.manage",
            "itemmilestonelog.backup.view",
            "itemmilestonelog.audit.view");

    private static final List<String> SPEC_NODES_LIST = java.util.stream.Stream
            .concat(LEGACY_NODES.stream(), STEP5_NODES.stream()).toList();
    private static final Set<String> SPEC_NODES = Set.copyOf(SPEC_NODES_LIST);

    @Test
    void registeredNodeSetEqualsTheSpecTable() {
        PermissionGatherEvent.Nodes event = new PermissionGatherEvent.Nodes();
        MilestonePermissions.register(event);

        Set<String> registered = new TreeSet<>();
        for (PermissionNode<?> node : event.getNodes()) {
            assertSame(PermissionTypes.BOOLEAN, node.getType(), node.getNodeName() + " must be boolean");
            registered.add(node.getNodeName());
        }

        assertEquals(SPEC_NODES, registered);
        assertEquals(SPEC_NODES, new TreeSet<>(MilestonePermissionLogic.NODE_NAMES));
        assertEquals(MilestonePermissionLogic.NODE_NAMES,
                MilestonePermissions.NODES.stream().map(PermissionNode::getNodeName).toList());
        assertEquals(16, MilestonePermissions.NODES.size());
        assertEquals(SPEC_NODES_LIST, MilestonePermissions.NODES.stream()
                .map(PermissionNode::getNodeName).toList());
        assertEquals(LEGACY_NODES, MilestonePermissionLogic.NODE_NAMES.subList(0, 9));
        assertEquals(STEP5_NODES, MilestonePermissionLogic.NODE_NAMES.subList(9, 16));
    }

    @Test
    void everyPermissionNodeConstantIsInTheTableAndRegistered() throws ReflectiveOperationException {
        Set<String> constantNames = new TreeSet<>();
        for (Field field : MilestonePermissions.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    || !PermissionNode.class.isAssignableFrom(field.getType())) {
                continue;
            }
            PermissionNode<?> node = (PermissionNode<?>) field.get(null);
            constantNames.add(node.getNodeName());
            assertTrue(MilestonePermissions.NODES.contains(node),
                    field.getName() + " must be in MilestonePermissions.NODES");
        }

        assertEquals(SPEC_NODES, constantNames,
                "every PermissionNode constant must be in the sixteen-node table");
    }

    @Test
    void allSixteenNodesShareTheSingleLevelTwoDefaultResolver() throws ReflectiveOperationException {
        Field field = MilestonePermissions.class.getDeclaredField("DEFAULT_RESOLVER");
        field.setAccessible(true);
        Object resolver = field.get(null);
        assertNotNull(resolver, "a single resolver must exist");
        for (PermissionNode<?> node : MilestonePermissions.NODES) {
            assertSame(resolver, node.getDefaultResolver(),
                    node.getNodeName() + " must not get a special default resolver");
        }
        @SuppressWarnings("unchecked")
        PermissionNode.PermissionResolver<Boolean> typed =
                (PermissionNode.PermissionResolver<Boolean>) resolver;
        assertFalse(typed.resolve(null, UUID.randomUUID()),
                "a resolver call without an online player must stay false");
    }

    @Test
    void nonPlayerSourcesFollowTheLegacyLevelOnly() {
        // A null source has no ServerPlayer, so the check must reduce to the legacy level and must
        // never reach the permission API, regardless of which of the sixteen nodes is requested.
        for (PermissionNode<Boolean> node : typedNodes()) {
            assertTrue(MilestonePermissions.check((CommandSourceStack) null, node, true),
                    node.getNodeName() + " console/legacy");
            assertFalse(MilestonePermissions.check((CommandSourceStack) null, node, false),
                    node.getNodeName() + " console/no legacy");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<PermissionNode<Boolean>> typedNodes() {
        return MilestonePermissions.NODES.stream()
                .map(node -> (PermissionNode<Boolean>) node).toList();
    }

    @Test
    void onlyTheSevenAppendedNodesCarryLocalizedDescriptions() {
        for (PermissionNode<?> node : MilestonePermissions.NODES) {
            boolean appended = STEP5_NODES.contains(node.getNodeName());
            assertEquals(appended, node.getReadableName() != null,
                    node.getNodeName() + " readable name");
            assertEquals(appended, node.getDescription() != null,
                    node.getNodeName() + " description");
        }
    }
}
