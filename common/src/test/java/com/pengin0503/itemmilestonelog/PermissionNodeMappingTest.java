package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the node-to-command mapping of spec-150-operations.md §0 note 20 / STALE-12 without changing
 * a single call site in PR-1. There is no unit harness for a live command tree, so the existing
 * {@code requires} wiring is checked against the version source exposed by Gradle / the hand-built
 * runner through {@code itemmilestonelog.mainSourceDir}, the same mechanism used by the other
 * source-wiring tests. In particular, a node is not assumed to be 1:1 with a command: the
 * {@code renameitem} node also gates {@code renametag}, {@code admin} gates the whole admin tree,
 * {@code import.all} gates both {@code importall} and importing another player's file, and
 * {@code discordtest} gates both the main and world-first test targets.
 */
class PermissionNodeMappingTest {
    private static final String COMMANDS_REL =
            "com/pengin0503/itemmilestonelog/MilestoneCommands.java";
    private static final String SERVICE_REL =
            "com/pengin0503/itemmilestonelog/MilestoneService.java";
    private static final String PERMISSIONS_REL =
            "com/pengin0503/itemmilestonelog/MilestonePermissions.java";

    private static Path resolve(String rel) {
        String mainSourceDir = System.getProperty("itemmilestonelog.mainSourceDir");
        if (mainSourceDir != null && !mainSourceDir.isBlank()) {
            Path cur = Path.of(mainSourceDir).toAbsolutePath().normalize();
            for (int i = 0; i < 6 && cur != null; i++) {
                Path candidate = cur.resolve(rel);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
                cur = cur.getParent();
            }
        }
        for (String prefix : new String[]{"src/main/java", "../src/main/java"}) {
            Path candidate = Path.of(prefix).resolve(rel);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError(rel + " source not found; "
                + "set -Ditemmilestonelog.mainSourceDir=<version>/src/main/java");
    }

    private static int count(String src, String needle) {
        int n = 0;
        for (int at = src.indexOf(needle); at >= 0; at = src.indexOf(needle, at + 1)) {
            n++;
        }
        return n;
    }

    private static void requiresWithin(String src, String literal, String predicate) {
        int at = src.indexOf("literal(\"" + literal + "\")");
        assertTrue(at >= 0, "missing command literal: " + literal);
        int windowEnd = Math.min(src.length(), at + 200);
        assertTrue(src.substring(at, windowEnd).contains(".requires(" + predicate + ")"),
                literal + " must still be gated by " + predicate);
    }

    @Test
    void existingRequirementPredicatesStillUseTheOriginalNineNodes() throws IOException {
        String commands = Files.readString(resolve(COMMANDS_REL));
        String[][] expected = {
                {"adminRequirementWithNode", "ADMIN"},
                {"firstsResetRequirement", "FIRSTS_RESET"},
                {"exportAllRequirement", "EXPORT_ALL"},
                {"importAllRequirement", "IMPORT_ALL"},
                {"pruneDataRequirement", "PRUNEDATA"},
                {"renameItemRequirement", "RENAMEITEM"},
                {"discordTestRequirement", "DISCORDTEST"},
        };
        for (String[] pair : expected) {
            assertTrue(commands.contains("check(src, MilestonePermissions."
                            + pair[1] + ", adminRequirement.test(src))"),
                    pair[0] + " must keep using " + pair[1] + " with the unchanged legacy check");
            assertTrue(commands.contains(pair[0] + " = src ->"),
                    pair[0] + " must remain the same local predicate");
        }
        String service = Files.readString(resolve(SERVICE_REL));
        assertTrue(count(service, "MilestonePermissions.PRESETS_MANAGE") >= 2,
                "preset save/delete must keep using the presets.manage node");
        assertTrue(commands.contains("MilestonePermissions.VIEWHIDDEN"),
                "hidden-record lookups must keep using the viewhidden node");
    }

    @Test
    void knownNonOneToOneMappingsArePreserved() throws IOException {
        String commands = Files.readString(resolve(COMMANDS_REL));
        // renameitem AND renametag both use renameItemRequirement (the F43 table lists only
        // renameitem; widening it must not be "fixed" by moving renametag to a new node).
        requiresWithin(commands, "renameitem", "renameItemRequirement");
        requiresWithin(commands, "renametag", "renameItemRequirement");
        assertEqualsCountAtLeast(2, count(commands, ".requires(renameItemRequirement)"),
                "renameItemRequirement must gate both renameitem and renametag");

        // The whole admin tree is gated by the ADMIN node from its parent literal.
        requiresWithin(commands, "admin", "adminRequirementWithNode");
        // discordtest and its worldfirst child share the DISCORDTEST node.
        requiresWithin(commands, "discordtest", "discordTestRequirement");
        assertTrue(commands.contains("literal(\"worldfirst\")"),
                "the worldfirst child must remain under the same discordtest gate");
        // IMPORT_ALL gates importall/import <other>'s file at the predicate and two direct sites.
        assertTrue(count(commands, "MilestonePermissions.IMPORT_ALL") >= 3,
                "importAllRequirement plus both direct import checks must keep using IMPORT_ALL");
    }

    private static void assertEqualsCountAtLeast(int expected, int actual, String message) {
        assertTrue(actual >= expected, message + " (found " + actual + ")");
    }

    @Test
    void newNodesAreRegisteredAndDiagnosticsNodesGateTheirCommands() throws IOException {
        String commands = Files.readString(resolve(COMMANDS_REL));
        String service = Files.readString(resolve(SERVICE_REL));

        // PR-2 consumes exactly two of the seven nodes registered in PR-1, on exactly the
        // commands the spec assigns them: DOCTOR gates the standalone `doctor` literal
        // (F-150-52) and DEBUG gates the `admin debug` child (F-150-53). Both keep the
        // legacy admin check as the player-side OR branch (§1.2 rule 2).
        assertTrue(commands.contains("MilestonePermissions.check(src, MilestonePermissions.DOCTOR, "
                        + "adminRequirement.test(src))"),
                "doctor must keep the F-150-52 DOCTOR gate with the legacy admin check");
        assertTrue(commands.contains("MilestonePermissions.check(src, MilestonePermissions.DEBUG, "
                        + "adminRequirement.test(src))"),
                "admin debug must keep the F-150-53 DEBUG gate with the legacy admin check");
        requiresWithin(commands, "doctor", "doctorRequirement");
        requiresWithin(commands, "debug", "debugRequirement");
        assertEqualsCountAtLeast(1, count(commands, ".requires(doctorRequirement)"),
                "doctorRequirement must gate the doctor command");
        assertEqualsCountAtLeast(1, count(commands, ".requires(debugRequirement)"),
                "debugRequirement must gate the admin debug child");
        assertFalse(service.contains("MilestonePermissions.DOCTOR"),
                "the DOCTOR node belongs in the command gate, not in MilestoneService");
        assertFalse(service.contains("MilestonePermissions.DEBUG"),
                "the DEBUG node belongs in the command gate, not in MilestoneService");

        // PR-3 consumes exactly the two Discord nodes. The standalone status view is gated by
        // DISCORD_STATUS and its digest child by DISCORD_DIGEST; the parent uses an OR of both
        // because Brigadier gates a child through its parent, so either node alone can still
        // reach its own subcommand. The bare handler re-checks DISCORD_STATUS (see below).
        assertTrue(commands.contains("MilestonePermissions.check(src, MilestonePermissions.DISCORD_STATUS, "
                        + "adminRequirement.test(src))"),
                "discordstatus must keep the F-150-54 DISCORD_STATUS gate with the legacy admin check");
        assertTrue(commands.contains("MilestonePermissions.check(src, MilestonePermissions.DISCORD_DIGEST, "
                        + "adminRequirement.test(src))"),
                "discordstatus digest must keep the F-150-54 DISCORD_DIGEST gate with the legacy admin check");
        assertTrue(commands.contains(".requires(src -> discordStatusRequirement.test(src)"),
                "the discordstatus parent must include the status-node check");
        requiresWithin(commands, "digest", "discordDigestRequirement");
        assertEqualsCountAtLeast(1, count(commands, ".requires(discordDigestRequirement)"),
                "discordDigestRequirement must gate the digest child");
        assertTrue(commands.contains("MilestonePermissions.check(source, MilestonePermissions.DISCORD_STATUS,"),
                "the bare status handler must re-check its own node");
        assertEquals(2, count(commands, "literal(\"payload\")"),
                "discordtest payload and discordtest worldfirst payload share the existing DISCORDTEST gate");
        requiresWithin(commands, "discordtest", "discordTestRequirement");

        // PR-4 consumes BACKUP_MANAGE / BACKUP_VIEW on their new command tree; AUDIT_VIEW is
        // still reserved for the audit-log PR. No new node may replace an existing call site.
        assertFalse(commands.contains("MilestonePermissions.AUDIT_VIEW"),
                "AUDIT_VIEW must not replace an existing call site before its PR");
        assertFalse(service.contains("MilestonePermissions.AUDIT_VIEW"),
                "AUDIT_VIEW must not replace an existing call site before its PR");
        String permissions = Files.readString(resolve(PERMISSIONS_REL));
        for (String name : MilestonePermissionLogic.NODE_NAMES) {
            assertTrue(permissions.contains("MilestonePermissionLogic."
                    + constantName(name) + ")"), name + " must have a version-side constant");
        }
        assertTrue(count(permissions, "DEFAULT_RESOLVER =") == 1,
                "all nodes must share the single level-2 default resolver");
        assertTrue(count(permissions, "documentedNode(") == 8,
                "the seven new nodes must use the shared documented-node helper");
    }

    @Test
    void backupNodesGateTheWriteAndReadChildren() throws IOException {
        String commands = Files.readString(resolve(COMMANDS_REL));
        assertTrue(commands.contains("MilestonePermissions.check(src, MilestonePermissions.BACKUP_MANAGE, "
                        + "adminRequirement.test(src))"),
                "backup now must keep the BACKUP_MANAGE gate with the legacy admin check");
        assertTrue(commands.contains("MilestonePermissions.check(src, MilestonePermissions.BACKUP_VIEW, "
                        + "adminRequirement.test(src))"),
                "backup list/verify must keep the BACKUP_VIEW gate with the legacy admin check");
        assertTrue(commands.contains(".requires(src -> backupManageRequirement.test(src)"
                        + " || backupViewRequirement.test(src))"),
                "the backup parent must accept either node so either child stays reachable");
        requiresWithin(commands, "now", "backupManageRequirement");
        requiresWithin(commands, "verify", "backupViewRequirement");
        assertTrue(commands.contains(".requires(backupViewRequirement)\n"
                        + "                                .executes(ctx -> backupList(ctx, 1))"),
                "backup list must be gated by BACKUP_VIEW");
        assertEquals(1, count(commands, ".requires(backupManageRequirement)"),
                "BACKUP_MANAGE must gate only backup now");
        assertEquals(2, count(commands, ".requires(backupViewRequirement)"),
                "BACKUP_VIEW must gate backup list and backup verify");
    }

    private static String constantName(String nodeName) {
        String suffix = nodeName.substring("itemmilestonelog.".length());
        return switch (suffix) {
            case "doctor" -> "DOCTOR";
            case "debug" -> "DEBUG";
            case "discordstatus" -> "DISCORD_STATUS";
            case "discorddigest" -> "DISCORD_DIGEST";
            case "backup.manage" -> "BACKUP_MANAGE";
            case "backup.view" -> "BACKUP_VIEW";
            case "audit.view" -> "AUDIT_VIEW";
            case "admin" -> "ADMIN";
            case "firsts.reset" -> "FIRSTS_RESET";
            case "export.all" -> "EXPORT_ALL";
            case "import.all" -> "IMPORT_ALL";
            case "prunedata" -> "PRUNEDATA";
            case "renameitem" -> "RENAMEITEM";
            case "discordtest" -> "DISCORDTEST";
            case "presets.manage" -> "PRESETS_MANAGE";
            case "viewhidden" -> "VIEWHIDDEN";
            default -> throw new AssertionError("unexpected node: " + nodeName);
        };
    }
}
