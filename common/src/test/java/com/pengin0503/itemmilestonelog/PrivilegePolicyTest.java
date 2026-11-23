package com.pengin0503.itemmilestonelog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrivilegePolicyTest {
    @Test
    void dedicatedServerRequiresAdminPermissionRegardlessOfPlayerOrOwnerFlags() {
        // Regular player on dedicated server
        assertFalse(PrivilegePolicy.isPrivileged(true, true, false, false));
        assertFalse(PrivilegePolicy.isPrivileged(true, true, false, 0));
        assertFalse(PrivilegePolicy.isPrivileged(true, true, false, 1));
        assertTrue(PrivilegePolicy.isPrivileged(true, true, false, true));
        assertTrue(PrivilegePolicy.isPrivileged(true, true, false, 2));
        assertTrue(PrivilegePolicy.isPrivileged(true, true, false, 4));

        // Even if singleplayerOwner were somehow true on a dedicated server, require admin permission
        assertFalse(PrivilegePolicy.isPrivileged(true, true, true, false));
        assertFalse(PrivilegePolicy.isPrivileged(true, true, true, 1));
        assertTrue(PrivilegePolicy.isPrivileged(true, true, true, 2));
    }

    @Test
    void integratedServerAllowsHostAlwaysAndLanGuestsOnlyWithAdminPermission() {
        // Singleplayer / LAN host (owner) is always privileged, even with cheats off (level 0)
        assertTrue(PrivilegePolicy.isPrivileged(false, true, true, false));
        assertTrue(PrivilegePolicy.isPrivileged(false, true, true, 0));
        assertTrue(PrivilegePolicy.isPrivileged(false, true, true, 1));
        assertTrue(PrivilegePolicy.isPrivileged(false, true, true, true));
        assertTrue(PrivilegePolicy.isPrivileged(false, true, true, 2));
        assertTrue(PrivilegePolicy.isPrivileged(false, true, true, 4));

        // LAN guest without cheats (level < 2) is NOT privileged (G1)
        assertFalse(PrivilegePolicy.isPrivileged(false, true, false, false));
        assertFalse(PrivilegePolicy.isPrivileged(false, true, false, 0));
        assertFalse(PrivilegePolicy.isPrivileged(false, true, false, 1));

        // LAN guest with cheats / gamemaster permission (level >= 2) IS privileged
        assertTrue(PrivilegePolicy.isPrivileged(false, true, false, true));
        assertTrue(PrivilegePolicy.isPrivileged(false, true, false, 2));
        assertTrue(PrivilegePolicy.isPrivileged(false, true, false, 3));
        assertTrue(PrivilegePolicy.isPrivileged(false, true, false, 4));
    }

    @Test
    void nonPlayerSourcesRequireAdminPermissionOnBothDedicatedAndIntegratedServers() {
        // Console / RCON / command block on dedicated server
        assertFalse(PrivilegePolicy.isPrivileged(true, false, false, false));
        assertFalse(PrivilegePolicy.isPrivileged(true, false, false, 1));
        assertTrue(PrivilegePolicy.isPrivileged(true, false, false, true));
        assertTrue(PrivilegePolicy.isPrivileged(true, false, false, 2));
        assertTrue(PrivilegePolicy.isPrivileged(true, false, false, 4));

        // Non-player source on integrated server (e.g. command block / function)
        assertFalse(PrivilegePolicy.isPrivileged(false, false, false, false));
        assertFalse(PrivilegePolicy.isPrivileged(false, false, true, false));
        assertFalse(PrivilegePolicy.isPrivileged(false, false, false, 1));
        assertTrue(PrivilegePolicy.isPrivileged(false, false, false, true));
        assertTrue(PrivilegePolicy.isPrivileged(false, false, false, 2));
    }
}
