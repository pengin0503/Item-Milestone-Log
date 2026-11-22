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
     * Returns the recorded milestone for the given player and item ID, if recorded.
     */
    public static Optional<MilestoneRecord> getRecord(MinecraftServer server, UUID player, String itemId) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(player, "player");
        if (itemId == null || itemId.isEmpty()) {
            return Optional.empty();
        }
        MilestoneData.PlayerData pd = MilestoneData.get(server).playerOrNull(player);
        if (pd == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(pd.records.get(itemId));
    }

    /**
     * Returns an unmodifiable view of the player's stored personal target item IDs.
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
        return Collections.unmodifiableSet(new LinkedHashSet<>(pd.targets));
    }

    /**
     * Returns the server's world-first record for the given item ID, if one exists.
     */
    public static Optional<WorldFirstRecord> getWorldFirst(MinecraftServer server, String itemId) {
        Objects.requireNonNull(server, "server");
        if (itemId == null || itemId.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(MilestoneData.get(server).worldFirstOrNull(itemId));
    }
}
