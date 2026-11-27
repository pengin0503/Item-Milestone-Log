package com.pengin0503.itemmilestonelog;

import java.util.List;

/**
 * Pure-Java part of F43 (permission-mod nodes), shared across all Minecraft versions via
 * {@code common/src/main/java}: the node table and the source/legacy/node decision.
 *
 * <p>The version-specific {@code MilestonePermissions} class builds the NeoForge
 * {@code PermissionNode} constants from these names, registers them, and queries the NeoForge
 * permission API only for online player sources. Non-player sources (console, RCON, command
 * blocks, functions) must never reach {@code PermissionAPI}.
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public final class MilestonePermissionLogic {
    /** Mod ID that prefixes every permission node. */
    public static final String MOD_ID = "itemmilestonelog";

    /** F43 node names (the table in {@code docs/FEATURE_SPEC_1.2.0.md}). */
    public static final String ADMIN = MOD_ID + ".admin";
    public static final String FIRSTS_RESET = MOD_ID + ".firsts.reset";
    public static final String EXPORT_ALL = MOD_ID + ".export.all";
    public static final String IMPORT_ALL = MOD_ID + ".import.all";
    public static final String PRUNEDATA = MOD_ID + ".prunedata";
    public static final String RENAMEITEM = MOD_ID + ".renameitem";
    public static final String DISCORDTEST = MOD_ID + ".discordtest";
    public static final String PRESETS_MANAGE = MOD_ID + ".presets.manage";
    public static final String VIEWHIDDEN = MOD_ID + ".viewhidden";

    // F-150-51 (N89): seven step-5 nodes appended after the nine F43 nodes. The nine names,
    // constants and ordering above are deliberately untouched so an existing permission-mod
    // setup keeps exactly the access it has today. See spec-150-operations.md §1.2 (the single
    // definition of the migration-compatibility rule) for how all sixteen nodes are checked.
    public static final String DOCTOR = MOD_ID + ".doctor";
    public static final String DEBUG = MOD_ID + ".debug";
    public static final String DISCORD_STATUS = MOD_ID + ".discordstatus";
    public static final String DISCORD_DIGEST = MOD_ID + ".discorddigest";
    public static final String BACKUP_MANAGE = MOD_ID + ".backup.manage";
    public static final String BACKUP_VIEW = MOD_ID + ".backup.view";
    public static final String AUDIT_VIEW = MOD_ID + ".audit.view";

    /** The complete table, in specification order: the nine F43 nodes, then the seven step-5 nodes. */
    public static final List<String> NODE_NAMES = List.of(
            ADMIN,
            FIRSTS_RESET,
            EXPORT_ALL,
            IMPORT_ALL,
            PRUNEDATA,
            RENAMEITEM,
            DISCORDTEST,
            PRESETS_MANAGE,
            VIEWHIDDEN,
            DOCTOR,
            DEBUG,
            DISCORD_STATUS,
            DISCORD_DIGEST,
            BACKUP_MANAGE,
            BACKUP_VIEW,
            AUDIT_VIEW);

    private MilestonePermissionLogic() {
    }

    /**
     * The one implementation of the migration-compatibility rule defined in
     * {@code spec-150-operations.md} §1.2; callers must use this method instead of restating it.
     *
     * <p>Test seam for the F43 / F-150-51 check matrix.
     *
     * <ul>
     *   <li>Player sources: the legacy check (level-2 {@code requires} or {@code isPrivileged},
     *       whichever the call site used) <strong>or</strong> the node being true.</li>
     *   <li>Non-player sources: there is no player, so no permission node is queried. Callers
     *       pass {@code true} for {@code nodeGranted} (no node gate), which reduces this branch
     *       to the legacy check alone. {@code false} models an explicit denial.</li>
     * </ul>
     *
     * <p>For players the "or" means a node can only add access; it cannot take OP access away.
     * A node value can never grant access to a non-player source.
     */
    public static boolean check(boolean playerSource, boolean legacyAllowed, boolean nodeGranted) {
        if (playerSource) {
            return legacyAllowed || nodeGranted;
        }
        return legacyAllowed && nodeGranted;
    }
}
