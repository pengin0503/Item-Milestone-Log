package com.pengin0503.itemmilestonelog.api;

import com.pengin0503.itemmilestonelog.MilestoneData;
import com.pengin0503.itemmilestonelog.MilestoneService;
import com.pengin0503.itemmilestonelog.Tracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side API for querying and updating Item Milestone Log data from other mods or scripts.
 *
 * <p><strong>Thread-safety:</strong> All methods in this class access {@link MilestoneData}
 * backed by the server's {@code SavedDataStorage} and must be called on the logical server
 * thread only. The write methods ({@link #addTarget}, {@link #removeTarget}, {@link #recordNow})
 * enforce this and throw {@link IllegalStateException} when called from another thread; the read
 * methods do not check.
 *
 * <p><strong>Null handling:</strong> Required context arguments ({@code server} and {@code player})
 * must not be {@code null} and throw {@link NullPointerException} if {@code null}. Query filter
 * arguments ({@code itemId} and {@code target}) tolerate {@code null} (or invalid values) and
 * return {@link Optional#empty()}; the write methods return {@code false} for a {@code null}
 * {@code target}.
 *
 * <p><strong>Tag keys:</strong> {@link TargetKey} may represent an item tag whose
 * {@link TargetKey#itemId()} starts with {@code '#'} (e.g. {@code "#c:ingots/iron"}). IDs returned
 * by this API (such as from {@link #getTargets}, {@link #getTargetKeys}, or {@link WorldFirstRecord#itemId()})
 * may therefore start with {@code '#'}; callers should check {@link TargetKey#isTag()} (or
 * {@link TargetKey#isTagId(String)}) before treating an ID as a Minecraft {@code Identifier} /
 * {@code ResourceLocation}.
 *
 * <p>Example KubeJS server script recording a milestone when a quest is completed:
 * <pre>{@code
 * const Api = Java.loadClass('com.pengin0503.itemmilestonelog.api.ItemMilestoneLogApi')
 * const TargetKey = Java.loadClass('com.pengin0503.itemmilestonelog.api.TargetKey')
 * // Call from your quest mod's completion callback, on the server thread:
 * Api.recordNow(player, TargetKey.of('minecraft:nether_star'))
 * }</pre>
 */
public final class ItemMilestoneLogApi {
    /** Maximum nesting depth of {@link #recordNow} calls made from {@link MilestoneRecordedEvent} listeners. */
    public static final int MAX_RECORD_NOW_DEPTH = 16;

    private ItemMilestoneLogApi() {
    }

    /**
     * Adds {@code target} to the player's personal targets, with the same validation and limit as the
     * {@code /milestonelog add} command: the item must exist in the registry and must not be air (for a
     * tag key, the item tag must exist and contain at least one item, as with {@code addtag}), and a
     * player can have at most 1024 personal targets. Creates the player's saved data if missing. If the
     * player is online, their milestone GUI is re-synced.
     *
     * @param server the running server
     * @param player UUID of the player (online or offline)
     * @param target the target to add (an item or a {@code '#'}-prefixed tag key); {@code null} returns {@code false}
     * @return {@code true} if the target was added; {@code false} if {@code target} is {@code null},
     *         invalid, an unknown item, an unknown or empty tag, already a personal target, or the limit is reached
     * @throws NullPointerException  if {@code server} or {@code player} is {@code null}
     * @throws IllegalStateException if not called on the server thread
     */
    public static boolean addTarget(MinecraftServer server, UUID player, TargetKey target) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(player, "player");
        requireServerThread(server.isSameThread(), "addTarget");
        return MilestoneService.addTargetFromApi(server, player, target);
    }

    /**
     * Removes {@code target} from the player's personal targets, with the same cleanup as the
     * {@code /milestonelog remove} command (the stored progress-alert state for that key is cleared;
     * the record, if any, is kept). Keys listed in the server's {@code defaultTargets} cannot be
     * removed. If the player is online, their milestone GUI is re-synced.
     *
     * @param server the running server
     * @param player UUID of the player (online or offline)
     * @param target the target to remove (an item or a {@code '#'}-prefixed tag key); {@code null} returns {@code false}
     * @return {@code true} if the target was removed; {@code false} if {@code target} is {@code null},
     *         a {@code defaultTargets} key, or not a personal target
     * @throws NullPointerException  if {@code server} or {@code player} is {@code null}
     * @throws IllegalStateException if not called on the server thread
     */
    public static boolean removeTarget(MinecraftServer server, UUID player, TargetKey target) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(player, "player");
        requireServerThread(server.isSameThread(), "removeTarget");
        return MilestoneService.removeTargetFromApi(server, player, target);
    }

    /**
     * Records {@code target} for {@code player} now, without checking that the player possesses the
     * items. The record goes through the normal record path: notifications (including grouped
     * notifications), world-first, {@link MilestoneRecordedEvent} with
     * {@link RecordCause#API}, Discord, progress-alert cleanup, and preset completion
     * ({@link PresetCompletedEvent}). The player's GUI is re-synced.
     *
     * <p>May be called from a {@link MilestoneRecordedEvent} listener to chain records. Calls nested
     * deeper than {@value #MAX_RECORD_NOW_DEPTH} levels return {@code false} and log a warning.
     *
     * @param player the online player to record for
     * @param target the target to record (an item or a {@code '#'}-prefixed tag key); {@code null} returns {@code false}
     * @return {@code true} if a new record was created; {@code false} if {@code target} is
     *         {@code null}, not one of the player's effective targets (personal +
     *         {@code defaultTargets}), already recorded, recording is paused for the player
     *         (creative/spectator with {@code ignoreCreativeAndSpectator}), the player is a
     *         {@code FakePlayer}, or the nesting depth limit was exceeded
     * @throws NullPointerException  if {@code player} is {@code null}
     * @throws IllegalStateException if not called on the server thread
     */
    public static boolean recordNow(ServerPlayer player, TargetKey target) {
        Objects.requireNonNull(player, "player");
        MinecraftServer server = player.level().getServer();
        requireServerThread(server != null && server.isSameThread(), "recordNow");
        if (target == null) {
            return false;
        }
        return Tracker.recordNow(player, target);
    }

    /** Throws {@link IllegalStateException} unless {@code onServerThread} is {@code true}. */
    static void requireServerThread(boolean onServerThread, String method) {
        if (!onServerThread) {
            throw new IllegalStateException(
                    "ItemMilestoneLogApi." + method + " must be called on the server thread (called from "
                            + Thread.currentThread().getName() + ")");
        }
    }

    /**
     * Returns the recorded milestone for the given player and item ID (quantity 1), if recorded.
     *
     * @throws NullPointerException if {@code server} or {@code player} is {@code null}
     */
    public static Optional<MilestoneRecord> getRecord(MinecraftServer server, UUID player, String itemId) {
        return getRecord(server, player, itemId, TargetKey.MIN_COUNT);
    }

    /**
     * Returns the recorded milestone for the given player, item ID, and quantity, if recorded.
     *
     * @throws NullPointerException if {@code server} or {@code player} is {@code null}
     */
    public static Optional<MilestoneRecord> getRecord(MinecraftServer server, UUID player, String itemId, int count) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(player, "player");
        if (!TargetKey.isValidItemId(itemId) || !TargetKey.isValidCount(count)) {
            return Optional.empty();
        }
        return getRecord(server, player, new TargetKey(itemId, count));
    }

    /**
     * Returns the recorded milestone for the given player and target key, if recorded.
     *
     * @throws NullPointerException if {@code server} or {@code player} is {@code null}
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
     * Returns an unmodifiable view of the player's stored personal target IDs with quantity 1.
     *
     * <p>Returned IDs may start with {@code '#'} for item tag targets (e.g. {@code "#c:ingots/iron"});
     * check {@link TargetKey#isTagId(String)} (or use {@link #getTargetKeys(MinecraftServer, UUID)}
     * and {@link TargetKey#isTag()}) before treating an entry as an {@code Identifier} /
     * {@code ResourceLocation}.
     *
     * <p>For all personal targets including quantity targets ({@code count > 1}), use
     * {@link #getTargetKeys(MinecraftServer, UUID)}.
     *
     * <p>Note: This returns only the player's saved personal targets and does not include
     * server-configured {@code defaultTargets}. Resolving default targets is the caller's
     * responsibility.
     *
     * @throws NullPointerException if {@code server} or {@code player} is {@code null}
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
     * Returns an unmodifiable view of all stored personal targets (item or tag + quantity) for the player.
     *
     * <p>Check {@link TargetKey#isTag()} before treating {@link TargetKey#itemId()} as an item
     * {@code Identifier} / {@code ResourceLocation}.
     *
     * <p>Note: This returns only the player's saved personal targets and does not include
     * server-configured {@code defaultTargets}. Resolving default targets is the caller's
     * responsibility.
     *
     * @throws NullPointerException if {@code server} or {@code player} is {@code null}
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
     *
     * @throws NullPointerException if {@code server} is {@code null}
     */
    public static Optional<WorldFirstRecord> getWorldFirst(MinecraftServer server, String itemId) {
        return getWorldFirst(server, itemId, TargetKey.MIN_COUNT);
    }

    /**
     * Returns the server's world-first record for the given item ID and quantity, if one exists.
     *
     * @throws NullPointerException if {@code server} is {@code null}
     */
    public static Optional<WorldFirstRecord> getWorldFirst(MinecraftServer server, String itemId, int count) {
        Objects.requireNonNull(server, "server");
        if (!TargetKey.isValidItemId(itemId) || !TargetKey.isValidCount(count)) {
            return Optional.empty();
        }
        return getWorldFirst(server, new TargetKey(itemId, count));
    }

    /**
     * Returns the server's world-first record for the given target key, if one exists.
     *
     * @throws NullPointerException if {@code server} is {@code null}
     */
    public static Optional<WorldFirstRecord> getWorldFirst(MinecraftServer server, TargetKey target) {
        Objects.requireNonNull(server, "server");
        if (target == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(MilestoneData.get(server).worldFirstOrNull(target));
    }
}
