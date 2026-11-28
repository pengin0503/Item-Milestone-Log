package com.pengin0503.itemmilestonelog.compat.client;

/**
 * F-150-69 (N31): client particle emission hook with a version-neutral signature.
 *
 * <p>Every Minecraft particle call is reflective and lives in a profile-scoped implementation,
 * so shared/merged-jar code never links against one version's particle API (PR-3 lesson 2).
 * A missing or incompatible implementation is a no-op.
 */
public interface ParticleCompat {
    /** Emits one short-lived particle at the player's position; returns false on any failure. */
    boolean spawn(double x, double y, double z, String particleName);
}
