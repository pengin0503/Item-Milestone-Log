package com.pengin0503.itemmilestonelog.api;

import com.pengin0503.itemmilestonelog.MilestoneData;
import net.minecraft.server.MinecraftServer;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only server-side API for querying Item Milestone Log data from other mods or scripts.
 */
public final class ItemMilestoneLogApi {
    private ItemMilestoneLogApi() {
    }

    /**
     * Returns the recorded milestone for the given player and item ID (quantity 1), if recorded.
     */
    public static Optional<MilestoneRecord> getRecord(MinecraftServer server, UUID player, String itemId) {
        return getRecord(server, player, itemId, TargetKey.MIN_COUNT);
    }

    /**
     * Returns the recorded milestone for the given player, item ID, and quantity, if recorded.
     */
    public static Optional<MilestoneRecord> getRecord(MinecraftServer server, UUID player, String itemId, int count) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(player, "player");
        if (itemId == null || itemId.isEmpty() || itemId.indexOf('#') >= 0 || !TargetKey.isValidCount(count)) {
            return Optional.empty();
        }
        return getRecord(server, player, new TargetKey(itemId, count));
    }

    /**
     * Returns the recorded milestone for the given player and target key, if recorded.
     */
    public static Optional<MilestoneRecord> getRecord(MinecraftServer server, UUID player, TargetKey target) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(player, "player");
        if (target == null) {
            return Optional.empty();
        }
        MilestoneData.PlayerData pd = MilestoneData.get(server).playerOrNull(player);
        if (pd == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(pd.records.get(target));
    }

    /**
     * Returns an unmodifiable view of the player's stored personal target item IDs with quantity 1.
     *
     * <p>For all personal targets including quantity targets ({@code count > 1}), use
     * {@link #getTargetKeys(MinecraftServer, UUID)}.
     *
     * <p>Note: This returns only the player's saved personal targets and does not include
     * server-configured {@code defaultTargets}. Resolving default targets is the caller's
     * responsibility.
     */
    public static Set<String> getTargets(MinecraftServer server, UUID player) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(player, "player");
        MilestoneData.PlayerData pd = MilestoneData.get(server).playerOrNull(player);
        if (pd == null || pd.targets.isEmpty()) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (TargetKey key : pd.targets) {
            if (key.count() == TargetKey.MIN_COUNT) {
                ids.add(key.itemId());
            }
        }
        return Collections.unmodifiableSet(ids);
    }

    /**
     * Returns an unmodifiable view of all stored personal targets (item + quantity) for the player.
     *
     * <p>Note: This returns only the player's saved personal targets and does not include
     * server-configured {@code defaultTargets}. Resolving default targets is the caller's
     * responsibility.
     */
    public static Set<TargetKey> getTargetKeys(MinecraftServer server, UUID player) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(player, "player");
        MilestoneData.PlayerData pd = MilestoneData.get(server).playerOrNull(player);
        if (pd == null || pd.targets.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(pd.targets));
    }

    /**
     * Returns the server's world-first record for the given item ID (quantity 1), if one exists.
     */
    public static Optional<WorldFirstRecord> getWorldFirst(MinecraftServer server, String itemId) {
        return getWorldFirst(server, itemId, TargetKey.MIN_COUNT);
    }

    /**
     * Returns the server's world-first record for the given item ID and quantity, if one exists.
     */
    public static Optional<WorldFirstRecord> getWorldFirst(MinecraftServer server, String itemId, int count) {
        Objects.requireNonNull(server, "server");
        if (itemId == null || itemId.isEmpty() || itemId.indexOf('#') >= 0 || !TargetKey.isValidCount(count)) {
            return Optional.empty();
        }
        return getWorldFirst(server, new TargetKey(itemId, count));
    }

    /**
     * Returns the server's world-first record for the given target key, if one exists.
     */
    public static Optional<WorldFirstRecord> getWorldFirst(MinecraftServer server, TargetKey target) {
        Objects.requireNonNull(server, "server");
        if (target == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(MilestoneData.get(server).worldFirstOrNull(target));
    }
}
