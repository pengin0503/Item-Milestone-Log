package com.pengin0503.itemmilestonelog;

/**
 * F-140-12 (D-140-8): the per-player, per-preset bookkeeping persisted as
 * {@code PlayerData.presetState}.
 *
 * <p>One record combines the two pieces of G-C state so they are stored together:
 * <ul>
 *   <li>{@link #autoApplied()} — F-140-14 (N102): the {@code unlockAfterHours} targets were
 *       auto-added once. It stays {@code true} after the player removes the targets, so they are
 *       never re-added.</li>
 *   <li>{@link #lastSeenVersion()} — F-140-15 (N65): the preset {@code version} the player has
 *       already been notified about. {@code 0} means "not initialized yet".</li>
 * </ul>
 *
 * <p>Must remain Java 21 compatible and free of Minecraft/NeoForge dependencies.
 */
public record PresetState(boolean autoApplied, int lastSeenVersion) {
    /** The initial state for a preset the player has no record of. */
    public static final PresetState EMPTY = new PresetState(false, 0);

    /** Returns a copy with {@code autoApplied} replaced. */
    public PresetState withAutoApplied(boolean value) {
        return new PresetState(value, lastSeenVersion);
    }

    /** Returns a copy with {@code lastSeenVersion} replaced. */
    public PresetState withLastSeenVersion(int value) {
        return new PresetState(autoApplied, value);
    }
}
