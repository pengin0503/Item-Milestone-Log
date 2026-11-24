package com.pengin0503.itemmilestonelog;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

import javax.annotation.Nullable;
import java.util.List;

/**
 * F43: boolean NeoForge permission nodes for the mod's admin-gated commands.
 *
 * <p>Nodes are registered in {@link PermissionGatherEvent.Nodes}; each node's default resolver is
 * "the player has permission level 2" so servers without a permission mod keep the old behavior.
 * {@link #check(CommandSourceStack, PermissionNode, boolean)} is the single decision point used by
 * command {@code requires} predicates and the {@code isPrivileged}-based sites:
 * <ul>
 *   <li>player source: the call site's legacy check OR the node being true;</li>
 *   <li>non-player source (console / RCON / command block / functions): the legacy check only,
 *       and {@code PermissionAPI} is never queried.</li>
 * </ul>
 * Because the node is ORed with the legacy check, a node can grant access but cannot revoke OP
 * access. Node names live in the shared, unit-tested {@link MilestonePermissionLogic} table.
 */
public final class MilestonePermissions {
    private static final PermissionNode.PermissionResolver<Boolean> DEFAULT_RESOLVER =
            (player, playerUuid, context) -> player != null
                    && player.hasPermissions(Commands.LEVEL_GAMEMASTERS);

    public static final PermissionNode<Boolean> ADMIN = node(MilestonePermissionLogic.ADMIN);
    public static final PermissionNode<Boolean> FIRSTS_RESET = node(MilestonePermissionLogic.FIRSTS_RESET);
    public static final PermissionNode<Boolean> EXPORT_ALL = node(MilestonePermissionLogic.EXPORT_ALL);
    public static final PermissionNode<Boolean> IMPORT_ALL = node(MilestonePermissionLogic.IMPORT_ALL);
    public static final PermissionNode<Boolean> PRUNEDATA = node(MilestonePermissionLogic.PRUNEDATA);
    public static final PermissionNode<Boolean> RENAMEITEM = node(MilestonePermissionLogic.RENAMEITEM);
    public static final PermissionNode<Boolean> DISCORDTEST = node(MilestonePermissionLogic.DISCORDTEST);
    public static final PermissionNode<Boolean> PRESETS_MANAGE = node(MilestonePermissionLogic.PRESETS_MANAGE);
    public static final PermissionNode<Boolean> VIEWHIDDEN = node(MilestonePermissionLogic.VIEWHIDDEN);

    /** Every node this mod registers, in the F43 table order. */
    public static final List<PermissionNode<?>> NODES = List.of(
            ADMIN,
            FIRSTS_RESET,
            EXPORT_ALL,
            IMPORT_ALL,
            PRUNEDATA,
            RENAMEITEM,
            DISCORDTEST,
            PRESETS_MANAGE,
            VIEWHIDDEN);

    private MilestonePermissions() {
    }

    private static PermissionNode<Boolean> node(String fullName) {
        int dot = fullName.indexOf('.');
        return new PermissionNode<>(fullName.substring(0, dot), fullName.substring(dot + 1),
                PermissionTypes.BOOLEAN, DEFAULT_RESOLVER);
    }

    /** Registers every F43 node; called from the event bus during permission API initialization. */
    public static void register(PermissionGatherEvent.Nodes event) {
        event.addNodes(NODES);
    }

    /**
     * F43 decision for a command source: legacy check OR node for players; legacy check only for
     * non-player sources (never calls the NeoForge permission API without a {@link ServerPlayer}).
     */
    public static boolean check(@Nullable CommandSourceStack source,
                                PermissionNode<Boolean> node,
                                boolean legacyAllowed) {
        return check(source != null ? source.getPlayer() : null, node, legacyAllowed);
    }

    /** F43 decision for a player-only site (GUI preset save/delete, sync payload capability). */
    public static boolean check(@Nullable ServerPlayer player,
                                PermissionNode<Boolean> node,
                                boolean legacyAllowed) {
        boolean playerSource = player != null;
        // Non-player sources have no node to query; pass a neutral gate so the shared check
        // reduces to the legacy permission-level result only.
        boolean nodeGranted = !playerSource || (!legacyAllowed && isNodeGranted(player, node));
        return MilestonePermissionLogic.check(playerSource, legacyAllowed, nodeGranted);
    }

    private static boolean isNodeGranted(ServerPlayer player, PermissionNode<Boolean> node) {
        if (PermissionAPI.getActivePermissionHandler() == null) {
            // The permission API is initialized when the server starts; never query it before then.
            return false;
        }
        return Boolean.TRUE.equals(PermissionAPI.getPermission(player, node));
    }
}
