package com.pengin0503.itemmilestonelog;

import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F43 static checks: the set of nodes actually offered to {@link PermissionGatherEvent.Nodes}
 * equals the specification table, and every {@link PermissionNode} constant defined by
 * {@link MilestonePermissions} is one of those registered nodes.
 */
class MilestonePermissionsTest {
    /** The F43 table from docs/FEATURE_SPEC_1.2.0.md, pinned independently of production code. */
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
                "every PermissionNode constant must be in the F43 table");
    }
}
