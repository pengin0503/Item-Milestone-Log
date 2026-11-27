package com.pengin0503.itemmilestonelog;

import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.api.WorldFirstRecord;
import com.pengin0503.itemmilestonelog.network.PresetEntry;
import com.pengin0503.itemmilestonelog.network.SyncExtraPayload;
import com.pengin0503.itemmilestonelog.network.SyncMilestonesPayload;
import com.pengin0503.itemmilestonelog.network.TargetEntry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Shared server-side milestone operations and validation used by both
 * {@link MilestoneCommands} and network handlers for the GUI.
 */
public final class MilestoneService {
    public static final int MAX_PERSONAL_TARGETS = 1024;

    /**
     * @param undoable whether the operation stored an F23 undo snapshot (chat commands then append
     *                 {@code undo_hint}; GUI feedback ignores it)
     */
    public record ActionResult(boolean success, Component message, boolean undoable) {
        public ActionResult(boolean success, Component message) {
            this(success, message, false);
        }
    }

    /**
     * F23: per-player undo snapshots for the player's own resets (and F28 bulk removals).
     * In memory only; cleared on server stop. Admin operations on other players are not captured.
     */
    public static final UndoLogic.Store<TargetKey, MilestoneRecord> UNDO_SNAPSHOTS = new UndoLogic.Store<>();

    private MilestoneService() {
    }

    /**
     * Whether {@code itemId} names a registered item, or (F8b) a {@code '#'}-prefixed item tag that
     * exists and is not empty.
     */
    public static boolean isValidItemId(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return false;
        }
        if (TargetKey.isTagId(itemId)) {
            return TagTargets.isNonEmptyTag(itemId);
        }
        Identifier id = Identifier.tryParse(itemId);
        return id != null && BuiltInRegistries.ITEM.containsKey(id);
    }

    /** F8b: {@code "#" + tagId} for a tag ID written without {@code '#'} (as in the tag commands). */
    public static String tagKeyId(String tagId) {
        return "#" + tagId;
    }

    /** {@code invalid_tag} for a {@code '#'}-prefixed ID (F8b), {@code invalid_item} otherwise. */
    static Component invalidTargetMessage(@Nullable ServerPlayer player, @Nullable String rawId) {
        return ServerLang.tr(player, invalidTargetKey(rawId), String.valueOf(rawId));
    }

    /** Lang key for an invalid/unknown target ID: {@code invalid_tag} for tags (F8b), else {@code invalid_item}. */
    public static String invalidTargetKey(@Nullable String rawId) {
        return TargetKey.isTagId(rawId)
                ? "message.itemmilestonelog.invalid_tag"
                : "message.itemmilestonelog.invalid_item";
    }

    /**
     * Display name of a target ID. F8b: tag IDs use
     * {@code Component.translatableWithFallback(Tags.getTagTranslationKey(tag), "#ns:path")}.
     */
    public static Component itemName(String itemId) {
        if (TargetKey.isTagId(itemId)) {
            return TagTargets.displayName(itemId);
        }
        Identifier id = Identifier.tryParse(itemId);
        if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
            Item item = BuiltInRegistries.ITEM.getValue(id);
            return item.getName(new ItemStack(item));
        }
        return Component.literal(itemId == null ? "" : itemId);
    }

    /**
     * Formats a target display name, appending {@code " ×<count>"} when count > 1.
     * F12: carries a {@code show_item} hover when {@code chatItemHover} is enabled (see {@link #withItemHover}).
     */
    public static Component targetDisplayName(TargetKey key) {
        Component base = itemName(key.itemId());
        if (key.count() > TargetKey.MIN_COUNT) {
            base = base.copy().append(Component.literal(key.formatSuffix()));
        }
        return withItemHover(base, key.itemId());
    }

    public static Component targetDisplayName(String itemId, int count) {
        Component base = itemName(itemId);
        if (count > TargetKey.MIN_COUNT) {
            base = base.copy().append(Component.literal(" ×" + count));
        }
        return withItemHover(base, itemId);
    }

    /**
     * F12: returns {@code name} with a {@code show_item} hover of the default stack (count 1, no
     * components) of the item chosen by {@link ChatItemHover#hoverItemId}, or {@code name} unchanged
     * when the hover is disabled or no item applies. Only the style changes, so {@code getString()}
     * is identical either way.
     */
    static Component withItemHover(Component name, String targetId) {
        if (!Config.chatItemHover()) {
            return name; // checked first so the tag cache / registry are not touched when disabled
        }
        String hoverId = ChatItemHover.hoverItemId(true, targetId, TagTargets.index(),
                MilestoneService::isRegisteredNonAirItem);
        if (hoverId == null) {
            return name;
        }
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(hoverId)));
        HoverEvent hover = new HoverEvent.ShowItem(net.minecraft.world.item.ItemStackTemplate.fromNonEmptyStack(stack));
        return name.copy().withStyle(style -> style.withHoverEvent(hover));
    }

    /** F12: whether {@code itemId} (not a tag) is in the item registry and is not air. */
    private static boolean isRegisteredNonAirItem(String itemId) {
        Identifier id = Identifier.tryParse(itemId);
        return id != null && BuiltInRegistries.ITEM.containsKey(id) && BuiltInRegistries.ITEM.getValue(id) != Items.AIR;
    }

    /**
     * F54: the receiving player's client language code (e.g. {@code ja_jp}), or null when
     * {@code player} is null (console / RCON) — preset names then resolve with {@code en_us}
     * priority.
     */
    @Nullable
    public static String clientLanguage(@Nullable ServerPlayer player) {
        if (player == null) {
            return null;
        }
        return player.clientInformation() != null ? player.clientInformation().language() : null;
    }

    /**
     * Resolves a preset's display name for {@code recipient} (F5 / F54):
     * built-in presets translate {@code gui.itemmilestonelog.preset.<id>} per recipient,
     * while custom presets resolve their JSON {@code name} (a plain string, or F54 per-language
     * entries, with the ID as fallback) in the recipient's language and embed it as a literal
     * component (§1 constraint 12).
     */
    public static Component presetDisplayName(@Nullable ServerPlayer recipient, Presets.PresetDefinition preset) {
        if (preset.builtin()) {
            return ServerLang.tr(recipient, "gui.itemmilestonelog.preset." + preset.id());
        }
        return Component.literal(preset.resolveDisplayName(clientLanguage(recipient)));
    }

    /** Same as {@link #presetDisplayName} for a language code instead of a player (F54). */
    public static Component presetDisplayNameForLang(@Nullable String language, Presets.PresetDefinition preset) {
        if (preset.builtin()) {
            return ServerLang.trForLang(language, "gui.itemmilestonelog.preset." + preset.id());
        }
        return Component.literal(preset.resolveDisplayName(language));
    }

    public static boolean isPrivileged(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        boolean dedicated = server == null || server.isDedicatedServer();
        ServerPlayer player = source.getPlayer();
        boolean isPlayer = player != null;
        boolean singleplayerOwner = isPlayer && server != null && server.isSingleplayerOwner(player.nameAndId());
        boolean hasAdmin = source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        return PrivilegePolicy.isPrivileged(dedicated, isPlayer, singleplayerOwner, hasAdmin);
    }

    public static boolean isPrivileged(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        boolean dedicated = server == null || server.isDedicatedServer();
        boolean singleplayerOwner = server != null && server.isSingleplayerOwner(player.nameAndId());
        boolean hasAdmin = player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        return PrivilegePolicy.isPrivileged(dedicated, true, singleplayerOwner, hasAdmin);
    }

    public static boolean canManagePresets(CommandSourceStack source) {
        return MilestonePermissions.check(source, MilestonePermissions.PRESETS_MANAGE, isPrivileged(source));
    }

    public static boolean canManagePresets(ServerPlayer player) {
        return MilestonePermissions.check(player, MilestonePermissions.PRESETS_MANAGE, isPrivileged(player));
    }

    /**
     * Whether {@code normalizedId} is a registered non-air item, or (F8b) an existing non-empty item tag.
     * Used for adding targets (incl. the F32 API), applying presets, and the F5 / F16 R(S) judgment.
     */
    static boolean isKnownNonAirItem(String normalizedId) {
        if (TargetKey.isTagId(normalizedId)) {
            return TagTargets.isNonEmptyTag(normalizedId);
        }
        Identifier id = Identifier.tryParse(normalizedId);
        return id != null && BuiltInRegistries.ITEM.containsKey(id) && BuiltInRegistries.ITEM.getValue(id) != Items.AIR;
    }

    private enum AddTargetStatus {
        INVALID_COUNT,
        INVALID_ITEM,
        ALREADY_TARGET,
        LIMIT_REACHED,
        ADDED
    }

    private record AddTargetOutcome(AddTargetStatus status, @Nullable TargetKey key) {
    }

    private static AddTargetOutcome performAddTarget(
            MilestoneData data,
            UUID playerId,
            String itemId,
            int count,
            Predicate<String> isKnownNonAirItem) {
        if (!TargetKey.isValidCount(count)) {
            return new AddTargetOutcome(AddTargetStatus.INVALID_COUNT, null);
        }
        String normalizedId = TargetKey.normalizeItemId(itemId);
        if (normalizedId == null || !isKnownNonAirItem.test(normalizedId)) {
            return new AddTargetOutcome(AddTargetStatus.INVALID_ITEM, null);
        }
        TargetKey key = new TargetKey(normalizedId, count);
        MilestoneData.PlayerData pd = data.player(playerId);
        if (pd.targets.contains(key)) {
            return new AddTargetOutcome(AddTargetStatus.ALREADY_TARGET, key);
        }
        if (pd.targets.size() >= MAX_PERSONAL_TARGETS) {
            return new AddTargetOutcome(AddTargetStatus.LIMIT_REACHED, key);
        }
        pd.targets.add(key);
        data.setDirty();
        return new AddTargetOutcome(AddTargetStatus.ADDED, key);
    }

    public static ActionResult addTarget(ServerPlayer player, String itemId) {
        return addTarget(player, itemId, TargetKey.MIN_COUNT);
    }

    public static ActionResult addTarget(ServerPlayer player, String itemId, int count) {
        return addTarget(MilestoneData.get(player.level().getServer()), player.getUUID(), player, itemId, count,
                MilestoneService::isKnownNonAirItem, MilestoneService::targetDisplayName);
    }

    static ActionResult addTarget(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                                  String itemId, int count,
                                  Predicate<String> isKnownNonAirItem,
                                  Function<TargetKey, Component> displayName) {
        AddTargetOutcome outcome = performAddTarget(data, playerId, itemId, count, isKnownNonAirItem);
        return switch (outcome.status()) {
            case INVALID_COUNT -> new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.invalid_count",
                    TargetKey.MIN_COUNT, TargetKey.MAX_COUNT));
            case INVALID_ITEM -> new ActionResult(false, invalidTargetMessage(player, itemId));
            case ALREADY_TARGET -> new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.already_target",
                    displayName.apply(outcome.key())));
            case LIMIT_REACHED -> new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.targets_limit_reached",
                    MAX_PERSONAL_TARGETS));
            case ADDED -> new ActionResult(true, ServerLang.tr(player, "message.itemmilestonelog.added",
                    displayName.apply(outcome.key())));
        };
    }

    /**
     * F33 {@code /milestonelog admin <player> add <item> [<count>]}: adds a personal target for
     * {@code targetId} (online or offline) using the same validation and cap as {@link #addTarget},
     * re-syncs the target's GUI if online, and is not captured for F23 undo.
     */
    public static ActionResult adminAddTarget(MinecraftServer server, CommandSourceStack source,
                                              UUID targetId, String targetName,
                                              String itemId, int count) {
        MilestoneData data = MilestoneData.get(server);
        ActionResult result = adminAddTarget(data, targetId, targetName, source.getPlayer(), itemId, count,
                MilestoneService::isKnownNonAirItem, MilestoneService::targetDisplayName);
        if (result.success()) {
            ServerPlayer online = server.getPlayerList().getPlayer(targetId);
            if (online != null) {
                ensureLastKnownName(data, targetId, online.getGameProfile().name());
                syncPlayerIfOpen(online);
            }
        }
        return result;
    }

    static ActionResult adminAddTarget(MilestoneData data, UUID targetId, String targetName,
                                       @Nullable ServerPlayer executor,
                                       String itemId, int count,
                                       Predicate<String> isKnownNonAirItem,
                                       Function<TargetKey, Component> displayName) {
        AddTargetOutcome outcome = performAddTarget(data, targetId, itemId, count, isKnownNonAirItem);
        Component literalName = Component.literal(targetName);
        return switch (outcome.status()) {
            case INVALID_COUNT -> new ActionResult(false, ServerLang.tr(executor, "message.itemmilestonelog.invalid_count",
                    TargetKey.MIN_COUNT, TargetKey.MAX_COUNT));
            case INVALID_ITEM -> new ActionResult(false, invalidTargetMessage(executor, itemId));
            case ALREADY_TARGET -> new ActionResult(false, ServerLang.tr(executor, "message.itemmilestonelog.admin_already_target",
                    literalName, displayName.apply(outcome.key())));
            case LIMIT_REACHED -> new ActionResult(false, ServerLang.tr(executor, "message.itemmilestonelog.targets_limit_reached",
                    MAX_PERSONAL_TARGETS));
            case ADDED -> new ActionResult(true, ServerLang.tr(executor, "message.itemmilestonelog.admin_added",
                    literalName, displayName.apply(outcome.key())));
        };
    }

    private static void ensureLastKnownName(MilestoneData data, UUID playerId, @Nullable String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return;
        }
        MilestoneData.PlayerData pd = data.playerOrNull(playerId);
        if (pd != null && (pd.lastKnownName == null || pd.lastKnownName.isEmpty())) {
            pd.lastKnownName = profileName;
            data.setDirty();
        }
    }

    public static ActionResult addHandTarget(ServerPlayer player) {
        return addHandTarget(player, TargetKey.MIN_COUNT);
    }

    public static ActionResult addHandTarget(ServerPlayer player, int count) {
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.addhand_empty"));
        }
        String id = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        return addTarget(player, id, count);
    }

    private enum RemoveTargetStatus {
        INVALID_COUNT,
        INVALID_ITEM,
        NOT_TARGET,
        REMOVED
    }

    private record RemoveTargetOutcome(RemoveTargetStatus status, @Nullable TargetKey key) {
    }

    private static RemoveTargetOutcome performRemoveTarget(
            MilestoneData data,
            UUID playerId,
            String itemId,
            int count,
            Predicate<String> isKnownItem) {
        if (!TargetKey.isValidCount(count)) {
            return new RemoveTargetOutcome(RemoveTargetStatus.INVALID_COUNT, null);
        }
        if (!TargetKey.isValidItemId(itemId)) {
            return new RemoveTargetOutcome(RemoveTargetStatus.INVALID_ITEM, null);
        }
        String normalized = TargetKey.normalizeItemId(itemId);
        String normalizedId = normalized != null ? normalized : itemId;
        TargetKey key = new TargetKey(normalizedId, count);
        MilestoneData.PlayerData pd = data.playerOrNull(playerId);
        if (pd != null) {
            TargetKey rawKey = !normalizedId.equals(itemId) ? new TargetKey(itemId, count) : null;
            if (pd.targets.remove(key) || (rawKey != null && pd.targets.remove(rawKey))) {
                pd.progressNotified.remove(key);
                pd.targetMeta.remove(key.toEncodedString());
                if (rawKey != null) {
                    pd.progressNotified.remove(rawKey);
                    pd.targetMeta.remove(rawKey.toEncodedString());
                }
                data.setDirty();
                return new RemoveTargetOutcome(RemoveTargetStatus.REMOVED, key);
            }
        }
        if (!isKnownItem.test(normalizedId)) {
            return new RemoveTargetOutcome(RemoveTargetStatus.INVALID_ITEM, key);
        }
        return new RemoveTargetOutcome(RemoveTargetStatus.NOT_TARGET, key);
    }

    public static ActionResult removeTarget(ServerPlayer player, String itemId) {
        return removeTarget(player, itemId, TargetKey.MIN_COUNT);
    }

    public static ActionResult removeTarget(ServerPlayer player, String itemId, int count) {
        return removeTarget(MilestoneData.get(player.level().getServer()), player.getUUID(), player, itemId, count,
                MilestoneService::isValidItemId, MilestoneService::targetDisplayName);
    }

    static ActionResult removeTarget(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                                     String itemId, int count,
                                     Predicate<String> isKnownItem,
                                     Function<TargetKey, Component> displayName) {
        RemoveTargetOutcome outcome = performRemoveTarget(data, playerId, itemId, count, isKnownItem);
        return switch (outcome.status()) {
            case INVALID_COUNT -> new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.invalid_count",
                    TargetKey.MIN_COUNT, TargetKey.MAX_COUNT));
            case INVALID_ITEM -> new ActionResult(false, invalidTargetMessage(player, itemId));
            case NOT_TARGET -> new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.not_target",
                    displayName.apply(outcome.key())));
            case REMOVED -> new ActionResult(true, ServerLang.tr(player, "message.itemmilestonelog.removed",
                    displayName.apply(outcome.key())));
        };
    }

    /**
     * F33 {@code /milestonelog admin <player> remove <item> [<count>]}: removes a personal target for
     * {@code targetId} (online or offline) with the same validation and §1.1 {@code progressNotified}
     * cleanup as {@link #removeTarget}, re-syncs the target's GUI if online, and is not an F23 undo target.
     */
    public static ActionResult adminRemoveTarget(MinecraftServer server, CommandSourceStack source,
                                                 UUID targetId, String targetName,
                                                 String itemId, int count) {
        ActionResult result = adminRemoveTarget(MilestoneData.get(server), targetId, targetName, source.getPlayer(),
                itemId, count, MilestoneService::isValidItemId, MilestoneService::targetDisplayName);
        if (result.success()) {
            syncOnlinePlayerIfOpen(server, targetId);
        }
        return result;
    }

    static ActionResult adminRemoveTarget(MilestoneData data, UUID targetId, String targetName,
                                          @Nullable ServerPlayer executor,
                                          String itemId, int count,
                                          Predicate<String> isKnownItem,
                                          Function<TargetKey, Component> displayName) {
        RemoveTargetOutcome outcome = performRemoveTarget(data, targetId, itemId, count, isKnownItem);
        Component literalName = Component.literal(targetName);
        return switch (outcome.status()) {
            case INVALID_COUNT -> new ActionResult(false, ServerLang.tr(executor, "message.itemmilestonelog.invalid_count",
                    TargetKey.MIN_COUNT, TargetKey.MAX_COUNT));
            case INVALID_ITEM -> new ActionResult(false, invalidTargetMessage(executor, itemId));
            case NOT_TARGET -> new ActionResult(false, ServerLang.tr(executor, "message.itemmilestonelog.admin_not_target",
                    literalName, displayName.apply(outcome.key())));
            case REMOVED -> new ActionResult(true, ServerLang.tr(executor, "message.itemmilestonelog.admin_removed",
                    literalName, displayName.apply(outcome.key())));
        };
    }

    /** Display names are not shown for API calls; avoid registry lookups for the unused feedback message. */
    private static final Function<TargetKey, Component> API_DISPLAY_NAME = key -> Component.literal(key.formatDisplayId());

    /**
     * F32 {@code ItemMilestoneLogApi#addTarget}: adds {@code target} to the player's personal targets
     * with the same validation and limit as the {@code add} command, creating the player's data if
     * missing, and re-syncs the player's GUI if they are online. Callers check the server thread.
     */
    public static boolean addTargetFromApi(MinecraftServer server, UUID playerId, @Nullable TargetKey target) {
        boolean added = addTargetFromApi(MilestoneData.get(server), playerId, target, MilestoneService::isKnownNonAirItem);
        if (added) {
            syncOnlinePlayerIfOpen(server, playerId);
        }
        return added;
    }

    static boolean addTargetFromApi(MilestoneData data, UUID playerId, @Nullable TargetKey target,
                                    Predicate<String> isKnownNonAirItem) {
        if (target == null) {
            return false;
        }
        return addTarget(data, playerId, null, target.itemId(), target.count(), isKnownNonAirItem, API_DISPLAY_NAME)
                .success();
    }

    /**
     * F32 {@code ItemMilestoneLogApi#removeTarget}: removes {@code target} from the player's personal
     * targets with the same cleanup as the {@code remove} command (§1.1: drops the key's
     * {@code progressNotified} entry). Keys in {@code defaultTargets} cannot be removed. Re-syncs the
     * player's GUI if they are online. Callers check the server thread.
     */
    public static boolean removeTargetFromApi(MinecraftServer server, UUID playerId, @Nullable TargetKey target) {
        boolean removed = removeTargetFromApi(MilestoneData.get(server), playerId, target, Tracker.defaultTargets());
        if (removed) {
            syncOnlinePlayerIfOpen(server, playerId);
        }
        return removed;
    }

    static boolean removeTargetFromApi(MilestoneData data, UUID playerId, @Nullable TargetKey target,
                                       Set<TargetKey> defaultTargets) {
        if (target == null) {
            return false;
        }
        TargetKey normalized = TargetKey.normalize(target.itemId(), target.count());
        if (defaultTargets.contains(target) || (normalized != null && defaultTargets.contains(normalized))) {
            return false;
        }
        return removeTarget(data, playerId, null, target.itemId(), target.count(), id -> true, API_DISPLAY_NAME)
                .success();
    }

    /** Re-syncs the GUI of {@code playerId} if that player is online and has the GUI channel. */
    public static void syncOnlinePlayerIfOpen(MinecraftServer server, UUID playerId) {
        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        if (online != null) {
            syncPlayerIfOpen(online);
        }
    }

    private record PresetApplyCounts(int added, int addedWithCount, int skippedMissing, int skippedLimit,
                                     List<String> missingMods) {
        private boolean hasMissingMods() {
            return !missingMods.isEmpty();
        }
    }

    /**
     * F-140-9: whether the loader reports {@code modId} as loaded. The mod-presence API is
     * version-specific, so this tiny hook lives in the version tree; the pure missing-set
     * computation lives in {@link PresetRequiredModsLogic}.
     */
    static boolean isModLoaded(String modId) {
        return net.neoforged.fml.ModList.get().isLoaded(modId);
    }

    private static PresetApplyCounts applyPresetTargets(
            MilestoneData data,
            UUID playerId,
            Presets.PresetDefinition preset,
            Predicate<String> isKnownNonAirItem,
            Predicate<String> modLoaded) {
        // F-140-9: requiredMods is checked once per application; when anything is missing the
        // preset is skipped entirely (no partial application) and the caller reports the IDs.
        List<String> missingMods = PresetRequiredModsLogic.missingMods(preset.requiredMods(), modLoaded);
        if (!missingMods.isEmpty()) {
            return new PresetApplyCounts(0, 0, 0, 0, missingMods);
        }
        MilestoneData.PlayerData pd = data.player(playerId);
        int added = 0;
        int addedWithCount = 0;
        int skippedMissing = 0;
        int skippedLimit = 0;
        for (TargetKey entry : preset.items()) {
            TargetKey normalized = TargetKey.normalize(entry.itemId(), entry.count(), false, true);
            if (normalized == null || !isKnownNonAirItem.test(normalized.itemId())) {
                skippedMissing++;
                continue;
            }
            if (pd.targets.contains(normalized)) {
                continue;
            }
            if (pd.targets.size() >= MAX_PERSONAL_TARGETS) {
                skippedLimit++;
                continue;
            }
            if (pd.targets.add(normalized)) {
                added++;
                if (normalized.count() > TargetKey.MIN_COUNT) {
                    addedWithCount++;
                }
            }
        }
        if (added > 0) {
            data.setDirty();
        }
        return new PresetApplyCounts(added, addedWithCount, skippedMissing, skippedLimit, List.of());
    }

    /**
     * F-140-13: one next-preset auto-add result, used by the completion notification to report
     * the destination preset name and the number of targets added.
     */
    record NextPresetAddition(Presets.PresetDefinition preset, int added) {
    }

    /**
     * F-140-13: result of one completion evaluation. {@code completed} is the historical list of
     * newly completed presets; {@code nextAdditions} are the targets auto-added from their
     * {@code nextPresets} in the same evaluation.
     */
    record PresetEvaluation(List<Presets.PresetDefinition> completed, List<NextPresetAddition> nextAdditions) {
        static PresetEvaluation empty() {
            return new PresetEvaluation(List.of(), List.of());
        }
    }

    /** F-140-12: current state for a preset, or {@link PresetState#EMPTY} when absent. */
    static PresetState presetState(MilestoneData.PlayerData pd, String presetId) {
        if (pd == null || presetId == null) {
            return PresetState.EMPTY;
        }
        return pd.presetState.getOrDefault(presetId, PresetState.EMPTY);
    }

    /** F-140-12: records that {@code unlockAfterHours} auto-added this preset's targets (F-140-14). */
    static void recordPresetAutoApplied(MilestoneData data, MilestoneData.PlayerData pd, String presetId) {
        if (pd == null || presetId == null) {
            return;
        }
        PresetState current = presetState(pd, presetId);
        if (!current.autoApplied()) {
            pd.presetState.put(presetId, current.withAutoApplied(true));
            data.setDirty();
        }
    }

    /** F-140-12: records the preset version the player has been notified about (F-140-15). */
    static void recordPresetVersionSeen(MilestoneData data, MilestoneData.PlayerData pd,
                                        String presetId, int version) {
        if (pd == null || presetId == null || version < 0) {
            return;
        }
        PresetState current = presetState(pd, presetId);
        if (current.lastSeenVersion() != version) {
            pd.presetState.put(presetId, current.withLastSeenVersion(version));
            data.setDirty();
        }
    }

    /**
     * F-140-12 restore rule: merges {@code incoming}'s presetState into {@code target}
     * ({@code autoApplied} OR, {@code lastSeenVersion} max). The in-game backup restore is a manual
     * file replacement with no merge hook in this codebase, so this helper codifies the rule for
     * that path and for tests.
     */
    static void mergePresetState(MilestoneData.PlayerData target, MilestoneData.PlayerData incoming) {
        if (target == null || incoming == null) {
            return;
        }
        PresetStateLogic.mergeInto(target.presetState, incoming.presetState);
    }

    /**
     * F-140-13: applies the {@code nextPresets} of a newly completed preset through the normal
     * {@link #applyPresetTargets} path (requiredMods and target caps enforced). Unknown IDs are
     * skipped silently here; they are warned about once at startup by
     * {@link #warnUnknownNextPresets()}. Missing required mods skip quietly (F-140-9).
     */
    static List<NextPresetAddition> applyNextPresets(
            MilestoneData data,
            UUID playerId,
            Presets.PresetDefinition completed,
            Predicate<String> isKnownNonAirItem,
            Predicate<String> modLoaded) {
        if (data == null || playerId == null || completed == null || completed.nextPresets().isEmpty()) {
            return List.of();
        }
        MilestoneData.PlayerData pd = data.playerOrNull(playerId);
        List<NextPresetAddition> additions = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String nextId : completed.nextPresets()) {
            if (nextId == null || !seen.add(nextId)) {
                continue;
            }
            // F-140-13: an already completed destination does not fire again, which is what
            // stops A <-> B cycles without any explicit cycle detection.
            if (pd != null && pd.completedPresets.contains(nextId)) {
                continue;
            }
            Presets.PresetDefinition next = Presets.get(nextId);
            if (next == null) {
                continue;
            }
            PresetApplyCounts counts = applyPresetTargets(data, playerId, next, isKnownNonAirItem, modLoaded);
            if (counts.hasMissingMods()) {
                continue;
            }
            if (counts.added() > 0) {
                additions.add(new NextPresetAddition(next, counts.added()));
            }
        }
        return additions.isEmpty() ? List.of() : List.copyOf(additions);
    }

    /**
     * F-140-14: auto-applies presets whose {@code unlockAfterHours} has been reached and that have
     * not been auto-applied before. Missing required mods skip the preset without recording
     * {@code autoApplied}, so a later login re-evaluates it. Returns the presets that actually
     * added targets, for chat feedback.
     */
    public static List<NextPresetAddition> applyUnlockAfterHours(MilestoneData data, UUID playerId, int playTicks) {
        return applyUnlockAfterHours(data, playerId, playTicks,
                MilestoneService::isKnownNonAirItem, Config.PRESETS_ENABLED.get(), MilestoneService::isModLoaded);
    }

    static List<NextPresetAddition> applyUnlockAfterHours(
            MilestoneData data,
            UUID playerId,
            int playTicks,
            Predicate<String> isKnownNonAirItem,
            boolean presetsEnabled,
            Predicate<String> modLoaded) {
        if (!presetsEnabled || data == null || playerId == null) {
            return List.of();
        }
        MilestoneData.PlayerData pd = data.playerOrNull(playerId);
        if (pd == null) {
            return List.of();
        }
        List<NextPresetAddition> additions = new ArrayList<>();
        for (Presets.PresetDefinition preset : Presets.allWithUnlockAfterHours()) {
            PresetState state = presetState(pd, preset.id());
            if (state.autoApplied()) {
                continue;
            }
            if (!PresetStateLogic.isUnlockDue(preset.unlockAfterHours(), playTicks)) {
                continue;
            }
            PresetApplyCounts counts = applyPresetTargets(data, playerId, preset, isKnownNonAirItem, modLoaded);
            if (counts.hasMissingMods()) {
                continue;
            }
            recordPresetAutoApplied(data, pd, preset.id());
            if (counts.added() > 0) {
                additions.add(new NextPresetAddition(preset, counts.added()));
            }
        }
        return additions.isEmpty() ? List.of() : List.copyOf(additions);
    }

    /**
     * F-140-15: one-time version-change notices at login. A preset is "involved" when the player
     * has a presetState record or their current personal targets overlap its items. The first
     * involvement only initializes {@code lastSeenVersion}; later increases notify once with the
     * number of current items not in the player's targets and the current total. Unversioned
     * presets are out of scope. No targets are auto-applied.
     */
    static List<Component> notifyPresetVersionUpdates(
            MilestoneData data,
            UUID playerId,
            @Nullable ServerPlayer player,
            boolean presetsEnabled) {
        if (!presetsEnabled || data == null || playerId == null) {
            return List.of();
        }
        MilestoneData.PlayerData pd = data.playerOrNull(playerId);
        if (pd == null) {
            return List.of();
        }
        List<Component> messages = new ArrayList<>();
        for (Presets.PresetDefinition preset : Presets.all()) {
            Integer version = preset.version();
            if (version == null) {
                continue;
            }
            PresetState existing = pd.presetState.get(preset.id());
            PresetState state = presetState(pd, preset.id());
            boolean involved = existing != null || overlapsTargets(pd.targets, preset.items());
            if (!involved) {
                continue;
            }
            if (state == null || state.lastSeenVersion() == 0) {
                recordPresetVersionSeen(data, pd, preset.id(), version);
                continue;
            }
            if (!PresetStateLogic.isVersionNoticeDue(version, state)) {
                continue;
            }
            int total = preset.items().size();
            int newCount = countTargetsNotIn(pd.targets, preset.items());
            Component presetName = presetDisplayName(player, preset);
            messages.add(ServerLang.tr(player, "message.itemmilestonelog.preset_version_updated",
                    presetName, newCount, total));
            recordPresetVersionSeen(data, pd, preset.id(), version);
        }
        return messages;
    }

    private static boolean overlapsTargets(Set<TargetKey> targets, List<TargetKey> items) {
        for (TargetKey item : items) {
            if (targets.contains(item)) {
                return true;
            }
        }
        return false;
    }

    private static int countTargetsNotIn(Set<TargetKey> targets, List<TargetKey> items) {
        int count = 0;
        for (TargetKey item : items) {
            if (!targets.contains(item)) {
                count++;
            }
        }
        return count;
    }

    public static ActionResult applyPreset(ServerPlayer player, String name) {
        return applyPreset(MilestoneData.get(player.level().getServer()), player.getUUID(), player, name,
                MilestoneService::isKnownNonAirItem, Config.PRESETS_ENABLED.get(), MilestoneService::isModLoaded);
    }

    static ActionResult applyPreset(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                                    String name, Predicate<String> isKnownNonAirItem) {
        return applyPreset(data, playerId, player, name, isKnownNonAirItem, true, MilestoneService::isModLoaded);
    }

    static ActionResult applyPreset(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                                    String name, Predicate<String> isKnownNonAirItem, boolean presetsEnabled) {
        return applyPreset(data, playerId, player, name, isKnownNonAirItem, presetsEnabled, MilestoneService::isModLoaded);
    }

    static ActionResult applyPreset(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                                    String name, Predicate<String> isKnownNonAirItem, boolean presetsEnabled,
                                    Predicate<String> modLoaded) {
        if (!presetsEnabled) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.feature_disabled"));
        }
        Presets.PresetDefinition preset = Presets.get(name);
        if (preset == null) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.preset_unknown", name,
                    String.join(", ", Presets.names())));
        }
        PresetApplyCounts counts = applyPresetTargets(data, playerId, preset, isKnownNonAirItem, modLoaded);
        if (counts.hasMissingMods()) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.preset_missing_mods",
                    name, String.join(", ", counts.missingMods())));
        }
        Component result;
        if (counts.addedWithCount() > 0) {
            result = ServerLang.tr(player, "message.itemmilestonelog.preset_applied_with_counts",
                    name, counts.added(), counts.addedWithCount(), counts.skippedMissing());
        } else {
            result = ServerLang.tr(player, "message.itemmilestonelog.preset_applied",
                    name, counts.added(), counts.skippedMissing());
        }
        if (counts.skippedLimit() > 0) {
            result = result.copy().append(Component.literal("\n"))
                    .append(ServerLang.tr(player, "message.itemmilestonelog.preset_applied_limit", counts.skippedLimit()));
        }
        return new ActionResult(true, result);
    }

    /**
     * F33 {@code /milestonelog admin <player> preset <name>}: applies a preset to {@code targetId}
     * (online or offline) with the same validation and cap as {@link #applyPreset}, re-syncs the
     * target's GUI if online, and is not captured for F23 undo.
     */
    public static ActionResult adminApplyPreset(MinecraftServer server, CommandSourceStack source,
                                                UUID targetId, String targetName, String name) {
        MilestoneData data = MilestoneData.get(server);
        ActionResult result = adminApplyPreset(data, targetId, targetName, source.getPlayer(), name,
                MilestoneService::isKnownNonAirItem, Config.PRESETS_ENABLED.get(), MilestoneService::isModLoaded);
        if (result.success()) {
            ServerPlayer online = server.getPlayerList().getPlayer(targetId);
            if (online != null) {
                ensureLastKnownName(data, targetId, online.getGameProfile().name());
                syncPlayerIfOpen(online);
            }
        }
        return result;
    }

    static ActionResult adminApplyPreset(MilestoneData data, UUID targetId, String targetName,
                                         @Nullable ServerPlayer executor, String name,
                                         Predicate<String> isKnownNonAirItem, boolean presetsEnabled) {
        return adminApplyPreset(data, targetId, targetName, executor, name, isKnownNonAirItem, presetsEnabled,
                MilestoneService::isModLoaded);
    }

    static ActionResult adminApplyPreset(MilestoneData data, UUID targetId, String targetName,
                                         @Nullable ServerPlayer executor, String name,
                                         Predicate<String> isKnownNonAirItem, boolean presetsEnabled,
                                         Predicate<String> modLoaded) {
        if (!presetsEnabled) {
            return new ActionResult(false, ServerLang.tr(executor, "message.itemmilestonelog.feature_disabled"));
        }
        Presets.PresetDefinition preset = Presets.get(name);
        if (preset == null) {
            return new ActionResult(false, ServerLang.tr(executor, "message.itemmilestonelog.preset_unknown", name,
                    String.join(", ", Presets.names())));
        }
        PresetApplyCounts counts = applyPresetTargets(data, targetId, preset, isKnownNonAirItem, modLoaded);
        if (counts.hasMissingMods()) {
            return new ActionResult(false, ServerLang.tr(executor, "message.itemmilestonelog.preset_missing_mods",
                    preset.id(), String.join(", ", counts.missingMods())));
        }
        Component result = ServerLang.tr(executor, "message.itemmilestonelog.admin_preset_applied",
                preset.id(), Component.literal(targetName), counts.added(), counts.skippedMissing());
        if (counts.skippedLimit() > 0) {
            result = result.copy().append(Component.literal("\n"))
                    .append(ServerLang.tr(executor, "message.itemmilestonelog.preset_applied_limit", counts.skippedLimit()));
        }
        return new ActionResult(true, result);
    }

    /**
     * F40: applies {@link Config#FIRST_JOIN_PRESETS} as personal targets if {@code playerId} has
     * no {@link MilestoneData.PlayerData} yet, using the same target-addition logic as
     * {@link #applyPreset}. Unknown preset IDs are skipped silently (warned once at server startup
     * via {@link #warnUnknownFirstJoinPresets()}). Not an F23 undo target.
     *
     * <p>F-140-9: a preset whose {@code requiredMods} are not all loaded is skipped and logged;
     * there is deliberately no chat feedback here (it would repeat on every first join).
     *
     * @return total number of personal targets added across all listed presets
     */
    public static int applyFirstJoinPresets(MilestoneData data, UUID playerId) {
        return applyFirstJoinPresets(
                data,
                playerId,
                Config.FIRST_JOIN_PRESETS.get(),
                MilestoneService::isKnownNonAirItem,
                Config.PRESETS_ENABLED.get(),
                MilestoneService::isModLoaded);
    }

    static int applyFirstJoinPresets(
            MilestoneData data,
            UUID playerId,
            @Nullable List<? extends String> presetIds,
            Predicate<String> isKnownNonAirItem,
            boolean presetsEnabled) {
        return applyFirstJoinPresets(data, playerId, presetIds, isKnownNonAirItem, presetsEnabled, MilestoneService::isModLoaded);
    }

    static int applyFirstJoinPresets(
            MilestoneData data,
            UUID playerId,
            @Nullable List<? extends String> presetIds,
            Predicate<String> isKnownNonAirItem,
            boolean presetsEnabled,
            Predicate<String> modLoaded) {
        if (!presetsEnabled || presetIds == null || presetIds.isEmpty()) {
            return 0;
        }
        if (data.playerOrNull(playerId) != null) {
            return 0;
        }
        int totalAdded = 0;
        for (String id : presetIds) {
            if (id == null) {
                continue;
            }
            Presets.PresetDefinition preset = Presets.get(id);
            if (preset == null) {
                continue;
            }
            PresetApplyCounts counts = applyPresetTargets(data, playerId, preset, isKnownNonAirItem, modLoaded);
            if (counts.hasMissingMods()) {
                ItemMilestoneLog.LOGGER.warn(
                        "[ItemMilestoneLog] Skipping first-join preset '{}' for player {}: missing required mod(s): {}",
                        id, playerId, String.join(", ", counts.missingMods()));
                continue;
            }
            totalAdded += counts.added();
        }
        return totalAdded;
    }

    /**
     * F40: logs a single warning at server startup if {@link Config#FIRST_JOIN_PRESETS} references
     * any preset ID that is not currently loaded. Does nothing when {@link Config#PRESETS_ENABLED}
     * is false or all configured IDs exist.
     */
    public static void warnUnknownFirstJoinPresets() {
        if (!Config.PRESETS_ENABLED.get()) {
            return;
        }
        List<String> unknown = findUnknownFirstJoinPresets(Config.FIRST_JOIN_PRESETS.get());
        if (!unknown.isEmpty()) {
            ItemMilestoneLog.LOGGER.warn(
                    "[ItemMilestoneLog] Unknown preset ID(s) in firstJoinPresets will be skipped: {}",
                    String.join(", ", unknown));
        }
    }

    static List<String> findUnknownFirstJoinPresets(@Nullable List<? extends String> presetIds) {
        return findUnknownFirstJoinPresets(presetIds, id -> Presets.get(id) != null);
    }

    /**
     * F-140-13: logs a single startup warning when any loaded preset's {@code nextPresets}
     * references an ID that is not loaded (same style as {@link #warnUnknownFirstJoinPresets()}).
     */
    public static void warnUnknownNextPresets() {
        if (!Config.PRESETS_ENABLED.get()) {
            return;
        }
        List<String> unknown = findUnknownNextPresets();
        if (!unknown.isEmpty()) {
            ItemMilestoneLog.LOGGER.warn(
                    "[ItemMilestoneLog] Unknown preset ID(s) in nextPresets will be skipped: {}",
                    String.join(", ", unknown));
        }
    }

    static List<String> findUnknownNextPresets() {
        Set<String> unknown = new LinkedHashSet<>();
        for (Presets.PresetDefinition preset : Presets.all()) {
            for (String id : preset.nextPresets()) {
                if (id != null && Presets.get(id) == null) {
                    unknown.add(id);
                }
            }
        }
        return unknown.isEmpty() ? List.of() : List.copyOf(unknown);
    }

    static List<String> findUnknownFirstJoinPresets(
            @Nullable List<? extends String> presetIds,
            Predicate<String> presetExists) {
        if (presetIds == null || presetIds.isEmpty()) {
            return List.of();
        }
        Set<String> unknown = new LinkedHashSet<>();
        for (String id : presetIds) {
            if (id != null && !presetExists.test(id)) {
                unknown.add(id);
            }
        }
        return unknown.isEmpty() ? List.of() : List.copyOf(unknown);
    }

    /**
     * F28 {@code /milestonelog presetremove <name>}: removes all keys of the given preset from the
     * player's personal targets (after the same normalization as {@link #applyPreset}, including
     * keys whose item is not currently in the registry). Keeps records, default targets, and world
     * firsts, cleans {@code progressNotified} for removed keys (§1.1), and registers an F23 undo
     * snapshot when at least one target was removed.
     */
    public static ActionResult removePresetTargets(ServerPlayer player, String name) {
        ActionResult result = removePresetTargets(
                MilestoneData.get(player.level().getServer()), player.getUUID(), player, name,
                Config.PRESETS_ENABLED.get(), UNDO_SNAPSHOTS, System.nanoTime());
        if (result.success() && result.undoable()) {
            syncPlayerIfOpen(player);
        }
        return result;
    }

    static ActionResult removePresetTargets(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                                            String name, boolean presetsEnabled,
                                            @Nullable UndoLogic.Store<TargetKey, MilestoneRecord> undoStore,
                                            long nowNanos) {
        if (!presetsEnabled) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.feature_disabled"));
        }
        Presets.PresetDefinition preset = Presets.get(name);
        if (preset == null) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.preset_unknown", name,
                    String.join(", ", Presets.names())));
        }
        return removePresetTargets(data, playerId, player, preset, undoStore, nowNanos);
    }

    static ActionResult removePresetTargets(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                                            Presets.PresetDefinition preset,
                                            @Nullable UndoLogic.Store<TargetKey, MilestoneRecord> undoStore,
                                            long nowNanos) {
        MilestoneData.PlayerData pd = data.playerOrNull(playerId);
        if (pd == null || pd.targets.isEmpty()) {
            return new ActionResult(true, ServerLang.tr(player, "message.itemmilestonelog.presetremove_done",
                    preset.id(), 0), false);
        }
        Set<TargetKey> presetKeys = new LinkedHashSet<>();
        for (TargetKey entry : preset.items()) {
            if (entry == null) {
                continue;
            }
            TargetKey normalized = TargetKey.normalize(entry.itemId(), entry.count(), false, true);
            if (normalized != null) {
                presetKeys.add(normalized);
            }
        }
        List<TargetKey> removedTargets = new ArrayList<>();
        Map<TargetKey, Integer> removedProgress = new LinkedHashMap<>();
        if (!presetKeys.isEmpty()) {
            Iterator<TargetKey> it = pd.targets.iterator();
            while (it.hasNext()) {
                TargetKey existing = it.next();
                TargetKey normExisting = TargetKey.normalize(existing.itemId(), existing.count(), false, true);
                if (presetKeys.contains(existing) || (normExisting != null && presetKeys.contains(normExisting))) {
                    it.remove();
                    removedTargets.add(existing);
                    Integer prev = pd.progressNotified.remove(existing);
                    pd.targetMeta.remove(existing.toEncodedString());
                    if (prev != null) {
                        removedProgress.put(existing, prev);
                    }
                    if (normExisting != null && !normExisting.equals(existing)) {
                        Integer normPrev = pd.progressNotified.remove(normExisting);
                        pd.targetMeta.remove(normExisting.toEncodedString());
                        if (normPrev != null) {
                            removedProgress.put(normExisting, normPrev);
                        }
                    }
                }
            }
        }
        boolean undoable = false;
        if (!removedTargets.isEmpty()) {
            data.setDirty();
            if (undoStore != null) {
                undoable = undoStore.offer(playerId,
                        UndoLogic.Snapshot.bulkRemoval(removedTargets, removedProgress, nowNanos));
            }
        }
        return new ActionResult(true, ServerLang.tr(player, "message.itemmilestonelog.presetremove_done",
                preset.id(), removedTargets.size()), undoable);
    }

    /**
     * F28 {@code /milestonelog removeall}: removes all of the player's personal targets while
     * keeping records, default targets, and world firsts, cleaning {@code progressNotified} for
     * removed keys (§1.1), and registering an F23 undo snapshot when at least one target was removed.
     */
    public static ActionResult removeAllTargets(ServerPlayer player) {
        ActionResult result = removeAllTargets(
                MilestoneData.get(player.level().getServer()), player.getUUID(), player,
                UNDO_SNAPSHOTS, System.nanoTime());
        if (result.success() && result.undoable()) {
            syncPlayerIfOpen(player);
        }
        return result;
    }

    static ActionResult removeAllTargets(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                                         @Nullable UndoLogic.Store<TargetKey, MilestoneRecord> undoStore,
                                         long nowNanos) {
        MilestoneData.PlayerData pd = data.playerOrNull(playerId);
        if (pd == null || pd.targets.isEmpty()) {
            return new ActionResult(true, ServerLang.tr(player, "message.itemmilestonelog.removeall_done", 0), false);
        }
        List<TargetKey> removedTargets = new ArrayList<>(pd.targets);
        pd.targets.clear();
        Map<TargetKey, Integer> removedProgress = new LinkedHashMap<>();
        for (TargetKey removed : removedTargets) {
            Integer prev = pd.progressNotified.remove(removed);
            pd.targetMeta.remove(removed.toEncodedString());
            if (prev != null) {
                removedProgress.put(removed, prev);
            }
            TargetKey normRemoved = TargetKey.normalize(removed.itemId(), removed.count(), false, true);
            if (normRemoved != null && !normRemoved.equals(removed)) {
                Integer normPrev = pd.progressNotified.remove(normRemoved);
                if (normPrev != null) {
                    removedProgress.put(normRemoved, normPrev);
                }
            }
        }
        data.setDirty();
        boolean undoable = undoStore != null && undoStore.offer(playerId,
                UndoLogic.Snapshot.bulkRemoval(removedTargets, removedProgress, nowNanos));
        return new ActionResult(true, ServerLang.tr(player, "message.itemmilestonelog.removeall_done",
                removedTargets.size()), undoable);
    }

    public static ActionResult savePreset(ServerPlayer player, String rawName) {
        return savePreset(MilestoneData.get(player.level().getServer()), player.getUUID(), player,
                canManagePresets(player), rawName, Presets::presetsDir, Config.PRESETS_ENABLED.get());
    }

    static ActionResult savePreset(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                                   boolean allowed, String rawName, Supplier<Path> presetsDir) {
        return savePreset(data, playerId, player, allowed, rawName, presetsDir, true);
    }

    static ActionResult savePreset(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                                   boolean allowed, String rawName, Supplier<Path> presetsDir, boolean presetsEnabled) {
        if (!presetsEnabled) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.feature_disabled"));
        }
        if (!allowed) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.preset_no_permission"));
        }
        String name = rawName != null ? rawName.trim() : "";
        if (!Presets.isValidId(name)) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.preset_invalid_name", name));
        }
        if (Presets.isBuiltin(name)) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.preset_builtin_protected", name));
        }
        // F21: IDs that exist only as a data pack preset are read-only; save under a new ID or
        // hand-place a config-folder file with the same ID to shadow the data pack one.
        if (Presets.isDatapack(name)) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.preset_datapack_protected", name));
        }
        // F-150-40 (N178): protected custom presets refuse every overwrite path.
        if (Presets.isProtected(name)) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.preset_protected", name));
        }
        MilestoneData.PlayerData pd = data.playerOrNull(playerId);
        if (pd == null || pd.targets.isEmpty()) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.preset_no_personal_targets"));
        }
        List<TargetKey> items = List.copyOf(pd.targets);
        try {
            boolean overwritten = Presets.saveCustomTo(presetsDir.get(), name, items);
            String key = overwritten ? "message.itemmilestonelog.preset_overwritten" : "message.itemmilestonelog.preset_saved";
            return new ActionResult(true, ServerLang.tr(player, key, name, items.size()));
        } catch (IOException | IllegalArgumentException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Failed to save preset {}", name, e);
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.preset_save_failed", name));
        }
    }

    /**
     * F-140-18: imports an {@code IML1:} preset share code. Accepts player, console and RCON
     * sources (issue #97); the permission predicate and the save location/format match
     * {@link #savePreset(ServerPlayer, String)}. The decoded JSON is validated by the normal
     * preset parse path and existing IDs are never overwritten.
     */
    public static ActionResult importPresetCode(CommandSourceStack source, String code) {
        return importPresetCode(source, canManagePresets(source), code, Presets::presetsDir,
                Config.PRESETS_ENABLED.get());
    }

    static ActionResult importPresetCode(@Nullable CommandSourceStack source, boolean allowed, String code,
                                         Supplier<Path> presetsDir, boolean presetsEnabled) {
        if (!presetsEnabled) {
            return new ActionResult(false, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        if (!allowed) {
            return new ActionResult(false, ServerLang.tr(source, "message.itemmilestonelog.preset_no_permission"));
        }

        PresetShareLogic.Decoded decoded = PresetShareLogic.decode(code,
                id -> Presets.get(id) != null || Files.isRegularFile(presetsDir.get().resolve(id + ".json")));
        switch (decoded.status()) {
            case OK -> {
                // Continue below.
            }
            case INVALID_PREFIX -> {
                return new ActionResult(false,
                        ServerLang.tr(source, "message.itemmilestonelog.preset_importcode_invalid_prefix"));
            }
            case INVALID_CODE -> {
                return new ActionResult(false,
                        ServerLang.tr(source, "message.itemmilestonelog.preset_importcode_invalid_code"));
            }
            case INVALID_JSON -> {
                return new ActionResult(false,
                        ServerLang.tr(source, "message.itemmilestonelog.preset_importcode_invalid_json"));
            }
            case TOO_LONG -> {
                return new ActionResult(false, ServerLang.tr(source,
                        "message.itemmilestonelog.preset_importcode_too_long",
                        code != null ? code.length() : 0, PresetShareLogic.MAX_CODE_CHARS));
            }
            case TOO_MANY_ITEMS -> {
                return new ActionResult(false, ServerLang.tr(source,
                        "message.itemmilestonelog.preset_importcode_too_many_items",
                        PresetShareLogic.MAX_ITEMS));
            }
            case EMPTY_ITEMS -> {
                return new ActionResult(false,
                        ServerLang.tr(source, "message.itemmilestonelog.preset_importcode_empty"));
            }
            case INVALID_ID -> {
                return new ActionResult(false,
                        ServerLang.tr(source, "message.itemmilestonelog.preset_importcode_invalid_id",
                                String.valueOf(decoded.id())));
            }
            case ID_COLLISION -> {
                return new ActionResult(false,
                        ServerLang.tr(source, "message.itemmilestonelog.preset_importcode_collision",
                                String.valueOf(decoded.id())));
            }
        }

        String id = decoded.id();
        if (!Presets.isValidId(id)) {
            return new ActionResult(false, ServerLang.tr(source,
                    "message.itemmilestonelog.preset_importcode_invalid_id", String.valueOf(id)));
        }
        Presets.PresetDefinition parsed =
                Presets.parsePresetContent(decoded.json(), "IML1 share code", id, Presets.PresetKind.FILE);
        if (parsed == null || parsed.items().isEmpty()) {
            return new ActionResult(false,
                    ServerLang.tr(source, "message.itemmilestonelog.preset_importcode_invalid_json"));
        }
        if (parsed.items().size() > PresetShareLogic.MAX_ITEMS) {
            return new ActionResult(false, ServerLang.tr(source,
                    "message.itemmilestonelog.preset_importcode_too_many_items",
                    PresetShareLogic.MAX_ITEMS));
        }
        try {
            Presets.saveImportedTo(presetsDir.get(), parsed);
            return new ActionResult(true, ServerLang.tr(source, "message.itemmilestonelog.preset_saved",
                    id, parsed.items().size()));
        } catch (IOException | IllegalArgumentException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Failed to import preset {}", id, e);
            return new ActionResult(false, ServerLang.tr(source,
                    "message.itemmilestonelog.preset_save_failed", id));
        }
    }

    public static ActionResult deletePreset(CommandSourceStack source, String name) {
        return deletePreset(source, canManagePresets(source), name, Presets::presetsDir, Config.PRESETS_ENABLED.get());
    }

    static ActionResult deletePreset(@Nullable CommandSourceStack source, boolean allowed,
                                     String name, Supplier<Path> presetsDir) {
        return deletePreset(source, allowed, name, presetsDir, true);
    }

    static ActionResult deletePreset(@Nullable CommandSourceStack source, boolean allowed,
                                     String name, Supplier<Path> presetsDir, boolean presetsEnabled) {
        if (!presetsEnabled) {
            return new ActionResult(false, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        if (!allowed) {
            return new ActionResult(false, ServerLang.tr(source, "message.itemmilestonelog.preset_no_permission"));
        }
        if (Presets.isBuiltin(name)) {
            return new ActionResult(false, ServerLang.tr(source, "message.itemmilestonelog.preset_builtin_protected", name));
        }
        // F21: data pack presets cannot be deleted from in-game either.
        if (Presets.isDatapack(name)) {
            return new ActionResult(false, ServerLang.tr(source, "message.itemmilestonelog.preset_datapack_protected", name));
        }
        // F-150-40 (N178): protected custom presets refuse deletion.
        if (Presets.isProtected(name)) {
            return new ActionResult(false, ServerLang.tr(source, "message.itemmilestonelog.preset_protected", name));
        }
        if (!Presets.isValidId(name) || Presets.get(name) == null) {
            return new ActionResult(false, ServerLang.tr(source, "message.itemmilestonelog.preset_unknown",
                    String.valueOf(name), String.join(", ", Presets.names())));
        }
        try {
            if (Presets.deleteCustomFrom(presetsDir.get(), name)) {
                return new ActionResult(true, ServerLang.tr(source, "message.itemmilestonelog.preset_deleted", name));
            }
            return new ActionResult(false, ServerLang.tr(source, "message.itemmilestonelog.preset_unknown",
                    name, String.join(", ", Presets.names())));
        } catch (IOException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Failed to delete preset {}", name, e);
            return new ActionResult(false, ServerLang.tr(source, "message.itemmilestonelog.preset_delete_failed", name));
        }
    }

    public static ActionResult deletePreset(ServerPlayer player, String name) {
        return deletePreset(player.createCommandSourceStack(), name);
    }

    public static ActionResult resetRecord(ServerPlayer player, String itemId) {
        return resetRecord(player, itemId, TargetKey.MIN_COUNT);
    }

    public static ActionResult resetRecord(ServerPlayer player, String itemId, int count) {
        MinecraftServer server = player.level().getServer();
        ActionResult result = resetRecord(MilestoneData.get(server), player.getUUID(), player, itemId, count,
                MilestoneService::isValidItemId, MilestoneService::targetDisplayName,
                UNDO_SNAPSHOTS, System.nanoTime());
        if (result.success()) {
            ScoreboardSync.refreshScores(server, player.getUUID());
        }
        return result;
    }

    static ActionResult resetRecord(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                                    String itemId, int count,
                                    Predicate<String> isKnownItem,
                                    Function<TargetKey, Component> displayName) {
        return resetRecord(data, playerId, player, itemId, count, isKnownItem, displayName, null, 0L);
    }

    /**
     * @param undoStore where to store the F23 snapshot of what was removed ({@code null}: not captured)
     * @param nowNanos  {@code System.nanoTime()} used as the snapshot's capture time
     */
    static ActionResult resetRecord(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                                    String itemId, int count,
                                    Predicate<String> isKnownItem,
                                    Function<TargetKey, Component> displayName,
                                    @Nullable UndoLogic.Store<TargetKey, MilestoneRecord> undoStore,
                                    long nowNanos) {
        if (!TargetKey.isValidCount(count)) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.invalid_count",
                    TargetKey.MIN_COUNT, TargetKey.MAX_COUNT));
        }
        if (!TargetKey.isValidItemId(itemId)) {
            return new ActionResult(false, invalidTargetMessage(player, itemId));
        }
        String normalized = TargetKey.normalizeItemId(itemId);
        String normalizedId = normalized != null ? normalized : itemId;
        TargetKey key = new TargetKey(normalizedId, count);
        MilestoneData.PlayerData pd = data.playerOrNull(playerId);
        if (pd != null) {
            TargetKey rawKey = !normalizedId.equals(itemId) ? new TargetKey(itemId, count) : null;
            Map<TargetKey, MilestoneRecord> removedRecords = new LinkedHashMap<>();
            MilestoneRecord removed = pd.records.remove(key);
            if (removed != null) {
                removedRecords.put(key, removed);
            } else if (rawKey != null && (removed = pd.records.remove(rawKey)) != null) {
                removedRecords.put(rawKey, removed);
            }
            if (removed != null) {
                Map<TargetKey, Integer> removedProgress = new LinkedHashMap<>();
                Integer progress = pd.progressNotified.remove(key);
                if (progress != null) {
                    removedProgress.put(key, progress);
                }
                if (rawKey != null && (progress = pd.progressNotified.remove(rawKey)) != null) {
                    removedProgress.put(rawKey, progress);
                }
                data.setDirty();
                boolean undoable = undoStore != null && undoStore.offer(playerId,
                        UndoLogic.Snapshot.reset(removedRecords, Set.of(), removedProgress, Map.of(), nowNanos));
                return new ActionResult(true, ServerLang.tr(player, "message.itemmilestonelog.reset_done", displayName.apply(key)),
                        undoable);
            }
        }
        if (!isKnownItem.test(normalizedId)) {
            return new ActionResult(false, invalidTargetMessage(player, itemId));
        }
        return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.not_recorded", displayName.apply(key)));
    }

    /** Result of an admin {@code resetall}: the removed record count and whether anything changed. */
    record AdminResetAllResult(int recordCount, boolean changed) {
    }

    /**
     * #132: clears every per-player collection a {@code resetall} resets — records,
     * {@code progressNotified}, {@code completedPresets} and the F-140-9 {@code presetState} — so the
     * player's own reset and the admin variant leave identical state. Admin operations do not create
     * an F23 undo snapshot.
     *
     * @return whether anything was cleared
     */
    static boolean clearAllPlayerState(MilestoneData.PlayerData pd) {
        boolean changed = false;
        if (!pd.records.isEmpty()) {
            pd.records.clear();
            changed = true;
        }
        if (!pd.progressNotified.isEmpty()) {
            pd.progressNotified.clear();
            changed = true;
        }
        if (!pd.completedPresets.isEmpty()) {
            pd.completedPresets.clear();
            changed = true;
        }
        if (!pd.presetState.isEmpty()) {
            pd.presetState.clear();
            changed = true;
        }
        return changed;
    }

    /**
     * #132 {@code /milestonelog admin <player> resetall}: applies the same cleanup as the player's own
     * resetall, without snapshotting for undo (F23 excludes admin resets).
     */
    static AdminResetAllResult adminResetAll(MilestoneData data, UUID playerId) {
        MilestoneData.PlayerData pd = data.playerOrNull(playerId);
        int count = pd != null ? pd.records.size() : 0;
        boolean changed = pd != null && clearAllPlayerState(pd);
        if (changed) {
            data.setDirty();
        }
        return new AdminResetAllResult(count, changed);
    }

    public static ActionResult resetAllRecords(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        ActionResult result = resetAllRecords(MilestoneData.get(server), player.getUUID(), player,
                UNDO_SNAPSHOTS, System.nanoTime());
        if (result.success()) {
            ScoreboardSync.refreshScores(server, player.getUUID());
        }
        return result;
    }

    static ActionResult resetAllRecords(MilestoneData data, UUID playerId, @Nullable ServerPlayer player) {
        return resetAllRecords(data, playerId, player, null, 0L);
    }

    /**
     * @param undoStore where to store the F23 snapshot of what was removed ({@code null}: not captured).
     *                  A 0-record resetall stores nothing and keeps the previous snapshot.
     * @param nowNanos  {@code System.nanoTime()} used as the snapshot's capture time
     */
    static ActionResult resetAllRecords(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                                        @Nullable UndoLogic.Store<TargetKey, MilestoneRecord> undoStore,
                                        long nowNanos) {
        MilestoneData.PlayerData pd = data.playerOrNull(playerId);
        int count = pd != null ? pd.records.size() : 0;
        boolean changed = false;
        boolean undoable = false;
        if (pd != null) {
            if (undoStore != null) {
                undoable = undoStore.offer(playerId, UndoLogic.Snapshot.reset(
                        pd.records, pd.completedPresets, pd.progressNotified, pd.presetState, nowNanos));
            }
            changed = clearAllPlayerState(pd);
        }
        if (changed) {
            data.setDirty();
        }
        return new ActionResult(true, ServerLang.tr(player, "message.itemmilestonelog.resetall_done", count), undoable);
    }

    /**
     * F23 {@code /milestonelog undo}: restores the player's last reset (or F28 bulk removal) from
     * the past 10 minutes. Fires no events, notifications, Discord posts, or F5 judgments; only
     * re-syncs the player's GUI.
     */
    public static ActionResult undo(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        ActionResult result = undo(MilestoneData.get(server), player.getUUID(), player,
                UNDO_SNAPSHOTS, System.nanoTime());
        if (result.success()) {
            ScoreboardSync.refreshScores(server, player.getUUID());
            syncPlayerIfOpen(player);
        }
        return result;
    }

    /** F-150-25: undo a numbered generation (1 = newest) while keeping plain {@code undo} unchanged. */
    public static ActionResult undo(ServerPlayer player, int generation) {
        MinecraftServer server = player.level().getServer();
        ActionResult result = undo(MilestoneData.get(server), player.getUUID(), player,
                UNDO_SNAPSHOTS, System.nanoTime(), generation);
        if (result.success()) {
            ScoreboardSync.refreshScores(server, player.getUUID());
            syncPlayerIfOpen(player);
        }
        return result;
    }

    static ActionResult undo(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                             UndoLogic.Store<TargetKey, MilestoneRecord> undoStore, long nowNanos) {
        return undo(data, playerId, player, undoStore, nowNanos, 1);
    }

    /**
     * @param generation 1-based history slot (1 = newest, as in {@code undo list}); generations
     *                   beyond 1 also invalidate every newer snapshot through {@code Store.consume}.
     */
    static ActionResult undo(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                             UndoLogic.Store<TargetKey, MilestoneRecord> undoStore, long nowNanos,
                             int generation) {
        UndoLogic.Snapshot<TargetKey, MilestoneRecord> snapshot = generation <= 1
                ? undoStore.peekValid(playerId, nowNanos)
                : undoStore.get(playerId, generation, nowNanos);
        if (snapshot == null) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.undo_nothing"));
        }
        return applyUndo(data, playerId, player, undoStore, snapshot);
    }

    private static ActionResult applyUndo(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                                          UndoLogic.Store<TargetKey, MilestoneRecord> undoStore,
                                          UndoLogic.Snapshot<TargetKey, MilestoneRecord> snapshot) {
        MilestoneData.PlayerData pd = data.player(playerId);
        UndoLogic.Result<TargetKey, MilestoneRecord> restored = UndoLogic.restore(snapshot,
                pd.records, pd.completedPresets, pd.progressNotified, pd.presetState, pd.targets,
                MAX_PERSONAL_TARGETS, MilestoneRecord::playTicks);
        pd.records.clear();
        pd.records.putAll(restored.records());
        pd.completedPresets.clear();
        pd.completedPresets.addAll(restored.completedPresets());
        pd.progressNotified.clear();
        pd.progressNotified.putAll(restored.progressNotified());
        pd.presetState.clear();
        pd.presetState.putAll(restored.presetState());
        pd.targets.clear();
        pd.targets.addAll(restored.targets());
        data.setDirty();
        undoStore.consume(playerId, snapshot);

        if (snapshot.kind() == UndoLogic.Kind.BULK_REMOVAL) {
            Component message = ServerLang.tr(player, "message.itemmilestonelog.undo_done_targets", restored.restoredTargets());
            if (restored.skippedTargetsLimit() > 0) {
                message = message.copy().append(Component.literal("\n"))
                        .append(ServerLang.tr(player, "message.itemmilestonelog.preset_applied_limit",
                                restored.skippedTargetsLimit()));
            }
            return new ActionResult(true, message);
        }
        return new ActionResult(true, ServerLang.tr(player, "message.itemmilestonelog.undo_done", restored.restoredRecords()));
    }

    public static ActionResult toggleDiscordOptOut(ServerPlayer player) {
        return toggleDiscordOptOut(MilestoneData.get(player.level().getServer()), player.getUUID(), player,
                Config.ALLOW_DISCORD_OPT_OUT.get());
    }

    static ActionResult toggleDiscordOptOut(MilestoneData data, UUID playerId, @Nullable ServerPlayer player,
                                            boolean allowDiscordOptOut) {
        if (!allowDiscordOptOut) {
            return new ActionResult(false, ServerLang.tr(player, "message.itemmilestonelog.feature_disabled"));
        }
        MilestoneData.PlayerData pd = data.player(playerId);
        pd.excludeFromDiscord = !pd.excludeFromDiscord;
        data.setDirty();
        String key = pd.excludeFromDiscord
                ? "message.itemmilestonelog.discordoptout_on"
                : "message.itemmilestonelog.discordoptout_off";
        return new ActionResult(true, ServerLang.tr(player, key));
    }

    /**
     * F14: toggles {@code hideFromLookup} for the executing player. Only affects what other,
     * non-privileged players can see (lookup, top, recent, stats); no Config switch.
     */
    public static ActionResult togglePrivacy(ServerPlayer player) {
        return togglePrivacy(MilestoneData.get(player.level().getServer()), player.getUUID(), player);
    }

    static ActionResult togglePrivacy(MilestoneData data, UUID playerId, @Nullable ServerPlayer player) {
        MilestoneData.PlayerData pd = data.player(playerId);
        pd.hideFromLookup = !pd.hideFromLookup;
        data.setDirty();
        String key = pd.hideFromLookup
                ? "message.itemmilestonelog.privacy_on"
                : "message.itemmilestonelog.privacy_off";
        return new ActionResult(true, ServerLang.tr(player, key));
    }

    /** Builds the pure-Java snapshot inspected by {@link PruneDataLogic#isPruneTarget} (F37). */
    static PruneDataLogic.PlayerState pruneStateOf(MilestoneData.PlayerData pd) {
        return new PruneDataLogic.PlayerState(
                pd.records.size(),
                pd.targets.size(),
                pd.carriedFromCreative.isEmpty(),
                pd.muteBroadcasts,
                pd.hideFromLookup,
                pd.muteProgressAlerts,
                pd.excludeFromDiscord,
                pd.progressNotified.isEmpty(),
                pd.completedPresets.isEmpty(),
                pd.presetState.isEmpty()
        );
    }

    /**
     * F37: counts inactive empty players eligible for {@code /milestonelog prunedata}, deleting their
     * {@link MilestoneData.PlayerData} when {@code confirm} is true. World-first records are kept.
     */
    public static int pruneInactivePlayers(MinecraftServer server, int days, boolean confirm) {
        MilestoneData data = MilestoneData.get(server);
        Path playerDataDir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        return pruneInactivePlayers(
                data,
                uuid -> server.getPlayerList().getPlayer(uuid) != null,
                playerDataDir,
                System.currentTimeMillis(),
                days,
                confirm,
                uuid -> ScoreboardSync.deleteScores(server, uuid));
    }

    static int pruneInactivePlayers(
            MilestoneData data,
            Predicate<UUID> isOnline,
            @Nullable Path playerDataDir,
            long nowMillis,
            int days,
            boolean confirm) {
        return pruneInactivePlayers(data, isOnline, playerDataDir, nowMillis, days, confirm, null);
    }

    static int pruneInactivePlayers(
            MilestoneData data,
            Predicate<UUID> isOnline,
            @Nullable Path playerDataDir,
            long nowMillis,
            int days,
            boolean confirm,
            @Nullable Consumer<UUID> beforePurge) {
        List<UUID> targets = new ArrayList<>();
        for (Map.Entry<UUID, MilestoneData.PlayerData> entry : data.allPlayers().entrySet()) {
            UUID uuid = entry.getKey();
            MilestoneData.PlayerData pd = entry.getValue();
            PruneDataLogic.PlayerState state = pruneStateOf(pd);
            boolean online = isOnline.test(uuid);
            long lastSeenMillis = (online || !PruneDataLogic.isEmptyWithDefaultSettings(state))
                    ? PruneDataLogic.UNKNOWN_LAST_SEEN
                    : PruneDataLogic.resolveLastSeen(pd.lastSeen, playerDataDir, uuid);
            if (PruneDataLogic.isPruneTarget(state, online, lastSeenMillis, nowMillis, days)) {
                targets.add(uuid);
            }
        }
        if (confirm) {
            for (UUID uuid : targets) {
                if (beforePurge != null) {
                    beforePurge.accept(uuid);
                }
                data.purgePlayer(uuid);
            }
        }
        return targets.size();
    }

    // --------------------------------------------------------- F24 renameitem

    /**
     * F24: counts returned by {@link #renameItemInData(MilestoneData, String, String, boolean)}.
     *
     * @param players           number of players whose {@link MilestoneData.PlayerData} changes
     * @param targets           number of personal target entries rewritten across all players
     * @param records           number of milestone record entries rewritten across all players
     * @param worldFirsts       number of world-first entries rewritten
     * @param affectedPlayers   UUIDs of players whose {@link MilestoneData.PlayerData} changes
     * @param scoreboardPlayers UUIDs of players whose F17 scoreboard counts (records or world firsts) may change
     */
    public record RenameCounts(
            int players,
            int targets,
            int records,
            int worldFirsts,
            Set<UUID> affectedPlayers,
            Set<UUID> scoreboardPlayers) {
        public static final RenameCounts EMPTY = new RenameCounts(0, 0, 0, 0, Set.of(), Set.of());

        public RenameCounts {
            affectedPlayers = Collections.unmodifiableSet(new LinkedHashSet<>(affectedPlayers));
            scoreboardPlayers = Collections.unmodifiableSet(new LinkedHashSet<>(scoreboardPlayers));
        }

        public boolean hasChanges() {
            return players > 0 || worldFirsts > 0;
        }
    }

    record RenameOutcome(
            ActionResult result,
            @Nullable String oldId,
            @Nullable String newId,
            @Nullable RenameCounts counts) {
    }

    /** F24: applies an item-ID rewrite to {@code data} in place and returns the change counts. */
    public static RenameCounts renameItemInData(MilestoneData data, String oldId, String newId) {
        return renameItemInData(data, oldId, newId, true);
    }

    /**
     * F24: computes (and when {@code apply} is {@code true}, commits) the item-ID rewrite
     * {@code oldId -> newId} on {@code data}. When {@code apply} is {@code false}, {@code data}
     * and its dirty flag are left untouched.
     */
    public static RenameCounts renameItemInData(MilestoneData data, String oldId, String newId, boolean apply) {
        if (data == null
                || oldId == null || newId == null
                || oldId.equals(newId)
                || !TargetKey.isValidItemId(oldId) || !TargetKey.isValidItemId(newId)
                || TargetKey.isTagId(oldId) || TargetKey.isTagId(newId)) {
            return RenameCounts.EMPTY;
        }

        Predicate<TargetKey> isOld = key -> oldId.equals(key.itemId());
        Predicate<TargetKey> isNew = key -> newId.equals(key.itemId());
        Function<TargetKey, TargetKey> toNew = key -> new TargetKey(newId, key.count());
        Function<TargetKey, TargetKey> newToOld = key -> new TargetKey(oldId, key.count());

        int totalTargets = 0;
        int totalRecords = 0;
        Set<UUID> affectedPlayers = new LinkedHashSet<>();
        Set<UUID> scoreboardPlayers = new LinkedHashSet<>();
        boolean anyPlayerMutated = false;

        for (Map.Entry<UUID, MilestoneData.PlayerData> entry : data.allPlayers().entrySet()) {
            UUID uuid = entry.getKey();
            MilestoneData.PlayerData pd = entry.getValue();

            RenameItemLogic.SetRewrite<TargetKey> targetsRes =
                    RenameItemLogic.rewriteTargets(pd.targets, isOld, toNew);
            RenameItemLogic.MapRewrite<TargetKey, MilestoneRecord> recordsRes =
                    RenameItemLogic.rewriteRecords(pd.records, isOld, isNew, toNew, MilestoneRecord::playTicks);
            RenameItemLogic.ProgressRewrite<TargetKey> progressRes =
                    RenameItemLogic.rewriteProgressNotified(
                            pd.progressNotified,
                            pd.records.keySet(),
                            recordsRes.values().keySet(),
                            isOld,
                            isNew,
                            toNew,
                            newToOld);
            RenameItemLogic.SetRewrite<String> carriedRes =
                    RenameItemLogic.rewriteCarriedFromCreative(pd.carriedFromCreative, oldId, newId);

            boolean playerChanged = targetsRes.changedCount() > 0
                    || recordsRes.changedCount() > 0
                    || progressRes.changed()
                    || carriedRes.changedCount() > 0;
            if (!playerChanged) {
                continue;
            }

            affectedPlayers.add(uuid);
            scoreboardPlayers.add(uuid);
            totalTargets += targetsRes.changedCount();
            totalRecords += recordsRes.changedCount();

            if (apply) {
                if (targetsRes.changedCount() > 0) {
                    pd.targets.clear();
                    pd.targets.addAll(targetsRes.values());
                }
                if (recordsRes.changedCount() > 0) {
                    pd.records.clear();
                    pd.records.putAll(recordsRes.values());
                }
                if (progressRes.changed()) {
                    pd.progressNotified.clear();
                    pd.progressNotified.putAll(progressRes.values());
                }
                if (carriedRes.changedCount() > 0) {
                    pd.carriedFromCreative.clear();
                    pd.carriedFromCreative.addAll(carriedRes.values());
                }
                anyPlayerMutated = true;
            }
        }

        RenameItemLogic.WorldFirstRewrite<TargetKey, WorldFirstRecord> firstsRes =
                RenameItemLogic.rewriteWorldFirsts(
                        data.worldFirsts(),
                        isOld,
                        isNew,
                        toNew,
                        wf -> new WorldFirstRecord(
                                newId,
                                wf.count(),
                                wf.playerUuid(),
                                wf.playerName(),
                                wf.playTicks(),
                                wf.day(),
                                wf.epochMillis()),
                        WorldFirstRecord::epochMillis,
                        WorldFirstRecord::playerUuid);

        scoreboardPlayers.addAll(firstsRes.affectedPlayers());

        if (apply) {
            if (firstsRes.changedCount() > 0) {
                data.replaceWorldFirsts(firstsRes.values());
            } else if (anyPlayerMutated) {
                data.setDirty();
            }
        }

        return new RenameCounts(
                affectedPlayers.size(),
                totalTargets,
                totalRecords,
                firstsRes.changedCount(),
                affectedPlayers,
                scoreboardPlayers);
    }

    /** F24: whether {@code itemId} appears in {@code defaultTargets} or any loaded preset (at any count). */
    static boolean containsItemIdInConfigOrPresets(
            String itemId,
            @Nullable Collection<TargetKey> defaultTargets,
            @Nullable Collection<Presets.PresetDefinition> presets) {
        if (itemId == null || itemId.isEmpty()) {
            return false;
        }
        if (defaultTargets != null) {
            for (TargetKey key : defaultTargets) {
                if (key != null && itemId.equals(key.itemId())) {
                    return true;
                }
            }
        }
        if (presets != null) {
            for (Presets.PresetDefinition preset : presets) {
                if (preset == null || preset.items() == null) {
                    continue;
                }
                for (TargetKey key : preset.items()) {
                    if (key != null && itemId.equals(key.itemId())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * F24 {@code /milestonelog renameitem <oldId> <newId> [confirm]}: previews or applies an
     * item-ID rewrite across all players' targets, records, {@code progressNotified},
     * {@code carriedFromCreative}, and world-first records.
     *
     * <p>When {@code confirm} is {@code true}, writes an F10 backup first (ignoring
     * {@code autoBackupEnabled} and the 1-hour rate limit) unless {@link MilestoneData#isEmpty()},
     * aborts without modifying anything if the backup fails, then logs an info line, re-syncs
     * online GUI players, and refreshes F17 scoreboard scores once. Fires no
     * events, notifications, or F7 commands.
     */
    public static ActionResult renameItem(
            MinecraftServer server,
            CommandSourceStack source,
            String oldId,
            String newId,
            boolean confirm) {
        MilestoneData data = MilestoneData.get(server);
        RenameOutcome outcome = executeRenameItem(
                data,
                source.getPlayer(),
                oldId,
                newId,
                confirm,
                MilestoneService::isRegisteredNonAirItem,
                () -> MilestoneBackup.writeBackupNow(server),
                Tracker.defaultTargets(),
                Presets.all());
        if (confirm && outcome.result().success() && outcome.counts() != null) {
            RenameCounts counts = outcome.counts();
            ItemMilestoneLog.LOGGER.info(
                    "[ItemMilestoneLog] Admin {} renamed item {} -> {} ({} player(s): {} target(s), {} record(s), {} world first(s))",
                    source.getTextName(), outcome.oldId(), outcome.newId(),
                    counts.players(), counts.targets(), counts.records(), counts.worldFirsts());
            if (counts.hasChanges()) {
                syncAllOnlineGuiPlayers(server, null);
                // One full refresh instead of an all-players scan per affected player (O(P^2)).
                ScoreboardSync.refreshAll(server);
            }
        }
        return outcome.result();
    }

    static RenameOutcome executeRenameItem(
            MilestoneData data,
            @Nullable ServerPlayer executor,
            String oldId,
            String newId,
            boolean confirm,
            Predicate<String> isRegisteredNonAirItem,
            BooleanSupplier backupWriter,
            @Nullable Collection<TargetKey> defaultTargets,
            @Nullable Collection<Presets.PresetDefinition> presets) {
        String oldNorm = TargetKey.normalizeItemId(oldId, false);
        if (oldNorm == null || TargetKey.isTagId(oldNorm) || "minecraft:air".equals(oldNorm)) {
            return new RenameOutcome(
                    new ActionResult(false, ServerLang.tr(executor, "message.itemmilestonelog.invalid_item",
                            Component.literal(String.valueOf(oldId)))),
                    null, null, null);
        }
        String newNorm = TargetKey.normalizeItemId(newId, false);
        if (newNorm == null || TargetKey.isTagId(newNorm) || "minecraft:air".equals(newNorm)) {
            return new RenameOutcome(
                    new ActionResult(false, ServerLang.tr(executor, "message.itemmilestonelog.invalid_item",
                            Component.literal(String.valueOf(newId)))),
                    oldNorm, null, null);
        }
        if (oldNorm.equals(newNorm)) {
            return new RenameOutcome(
                    new ActionResult(false, ServerLang.tr(executor, "message.itemmilestonelog.renameitem_same")),
                    oldNorm, newNorm, null);
        }
        if (!isRegisteredNonAirItem.test(newNorm)) {
            return new RenameOutcome(
                    new ActionResult(false, ServerLang.tr(executor, "message.itemmilestonelog.invalid_item",
                            Component.literal(newNorm))),
                    oldNorm, newNorm, null);
        }
        if (confirm && !data.isEmpty() && !backupWriter.getAsBoolean()) {
            return new RenameOutcome(
                    new ActionResult(false, ServerLang.tr(executor, "message.itemmilestonelog.renameitem_backup_failed")),
                    oldNorm, newNorm, null);
        }

        RenameCounts counts = renameItemInData(data, oldNorm, newNorm, confirm);
        String messageKey = confirm
                ? "message.itemmilestonelog.renameitem_done"
                : "message.itemmilestonelog.renameitem_preview";
        Component message = ServerLang.tr(executor, messageKey,
                Component.literal(oldNorm),
                Component.literal(newNorm),
                counts.players(),
                counts.targets(),
                counts.records(),
                counts.worldFirsts());
        if (containsItemIdInConfigOrPresets(oldNorm, defaultTargets, presets)) {
            message = message.copy().append(Component.literal("\n"))
                    .append(ServerLang.tr(executor, "message.itemmilestonelog.renameitem_config_note",
                            Component.literal(oldNorm)));
        }
        return new RenameOutcome(new ActionResult(true, message), oldNorm, newNorm, counts);
    }

    // --------------------------------------------------------- F-140-19 renametag

    /**
     * F-140-19: counts returned by {@link #renameTagInData(MilestoneData, String, String, boolean)}.
     *
     * @param players           number of players whose {@link MilestoneData.PlayerData} changes
     * @param targets           number of personal target entries rewritten across all players
     * @param records           number of milestone record entries rewritten across all players
     * @param worldFirsts       number of world-first entries rewritten
     * @param affectedPlayers   UUIDs of players whose {@link MilestoneData.PlayerData} changes
     * @param scoreboardPlayers UUIDs of players whose F17 scoreboard counts (records or world firsts) may change
     */
    public record TagRenameCounts(
            int players,
            int targets,
            int records,
            int worldFirsts,
            Set<UUID> affectedPlayers,
            Set<UUID> scoreboardPlayers) {
        public static final TagRenameCounts EMPTY = new TagRenameCounts(0, 0, 0, 0, Set.of(), Set.of());

        public TagRenameCounts {
            affectedPlayers = Collections.unmodifiableSet(new LinkedHashSet<>(affectedPlayers));
            scoreboardPlayers = Collections.unmodifiableSet(new LinkedHashSet<>(scoreboardPlayers));
        }

        public boolean hasChanges() {
            return players > 0 || worldFirsts > 0;
        }
    }

    record RenameTagOutcome(
            ActionResult result,
            @Nullable String oldTag,
            @Nullable String newTag,
            @Nullable TagRenameCounts counts) {
    }

    /** F-140-19: applies a tag-ID rewrite to {@code data} in place and returns the change counts. */
    public static TagRenameCounts renameTagInData(MilestoneData data, String oldTag, String newTag) {
        return renameTagInData(data, oldTag, newTag, true);
    }

    /**
     * F-140-19: computes (and when {@code apply} is {@code true}, commits) the tag-ID rewrite
     * {@code oldTag -> newTag} across every player's {@code targets}, {@code records} and
     * {@code progressNotified}, plus the server-wide {@code worldFirsts}. Tag targets are recorded
     * under the tag key itself, so moving only {@code targets} would make the new tag pending again
     * and re-fire notifications, Discord posts, rewards and world firsts (issue #128). Collisions
     * with an existing destination key follow the same F24 rules as {@code renameItemInData}.
     * Presets and Config are intentionally untouched. When {@code apply} is {@code false},
     * {@code data} and its dirty flag are left untouched.
     */
    public static TagRenameCounts renameTagInData(MilestoneData data, String oldTag, String newTag, boolean apply) {
        String oldNorm = normalizeTagId(oldTag);
        String newNorm = normalizeTagId(newTag);
        if (data == null || oldNorm == null || newNorm == null || oldNorm.equals(newNorm)) {
            return TagRenameCounts.EMPTY;
        }

        Predicate<TargetKey> isOld = key -> oldNorm.equals(key.itemId());
        Predicate<TargetKey> isNew = key -> newNorm.equals(key.itemId());
        Function<TargetKey, TargetKey> toNew = key -> new TargetKey(newNorm, key.count());
        Function<TargetKey, TargetKey> newToOld = key -> new TargetKey(oldNorm, key.count());

        int totalTargets = 0;
        int totalRecords = 0;
        Set<UUID> affectedPlayers = new LinkedHashSet<>();
        Set<UUID> scoreboardPlayers = new LinkedHashSet<>();
        boolean anyPlayerMutated = false;

        for (Map.Entry<UUID, MilestoneData.PlayerData> entry : data.allPlayers().entrySet()) {
            UUID uuid = entry.getKey();
            MilestoneData.PlayerData pd = entry.getValue();
            RenameItemLogic.SetRewrite<TargetKey> targetsRes =
                    RenameItemLogic.rewriteTargets(pd.targets, isOld, toNew);
            RenameItemLogic.MapRewrite<TargetKey, MilestoneRecord> recordsRes =
                    RenameItemLogic.rewriteRecords(pd.records, isOld, isNew, toNew, MilestoneRecord::playTicks);
            RenameItemLogic.ProgressRewrite<TargetKey> progressRes =
                    RenameItemLogic.rewriteProgressNotified(
                            pd.progressNotified,
                            pd.records.keySet(),
                            recordsRes.values().keySet(),
                            isOld,
                            isNew,
                            toNew,
                            newToOld);

            boolean playerChanged = targetsRes.changedCount() > 0
                    || recordsRes.changedCount() > 0
                    || progressRes.changed();
            if (!playerChanged) {
                continue;
            }

            affectedPlayers.add(uuid);
            scoreboardPlayers.add(uuid);
            totalTargets += targetsRes.changedCount();
            totalRecords += recordsRes.changedCount();

            if (apply) {
                if (targetsRes.changedCount() > 0) {
                    pd.targets.clear();
                    pd.targets.addAll(targetsRes.values());
                }
                if (recordsRes.changedCount() > 0) {
                    pd.records.clear();
                    pd.records.putAll(recordsRes.values());
                }
                if (progressRes.changed()) {
                    pd.progressNotified.clear();
                    pd.progressNotified.putAll(progressRes.values());
                }
                anyPlayerMutated = true;
            }
        }

        RenameItemLogic.WorldFirstRewrite<TargetKey, WorldFirstRecord> firstsRes =
                RenameItemLogic.rewriteWorldFirsts(
                        data.worldFirsts(),
                        isOld,
                        isNew,
                        toNew,
                        wf -> new WorldFirstRecord(
                                newNorm,
                                wf.count(),
                                wf.playerUuid(),
                                wf.playerName(),
                                wf.playTicks(),
                                wf.day(),
                                wf.epochMillis()),
                        WorldFirstRecord::epochMillis,
                        WorldFirstRecord::playerUuid);

        scoreboardPlayers.addAll(firstsRes.affectedPlayers());

        if (apply) {
            if (firstsRes.changedCount() > 0) {
                data.replaceWorldFirsts(firstsRes.values());
            } else if (anyPlayerMutated) {
                data.setDirty();
            }
        }

        return new TagRenameCounts(
                affectedPlayers.size(),
                totalTargets,
                totalRecords,
                firstsRes.changedCount(),
                affectedPlayers,
                scoreboardPlayers);
    }

    /** F-140-19: {@code "#namespace:path"} normalization for tag arguments, or {@code null} if invalid. */
    @Nullable
    static String normalizeTagId(@Nullable String raw) {
        return TargetKey.isTagId(raw) ? TargetKey.normalizeItemId(raw, true) : null;
    }

    /**
     * F-140-19 {@code /milestonelog renametag <oldTag> <newTag> [confirm]}: previews or applies a
     * tag-ID rewrite across every player's {@code targets}, {@code records},
     * {@code progressNotified} and the server-wide {@code worldFirsts}.
     *
     * <p>When {@code confirm} is {@code true}, writes an F10 backup first (ignoring
     * {@code autoBackupEnabled} and the 1-hour rate limit) unless {@link MilestoneData#isEmpty()},
     * aborts without modifying anything if the backup fails, then logs an info line, re-syncs
     * online GUI players and refreshes F17 scoreboard counts. Presets and Config are never
     * rewritten.
     */
    public static ActionResult renameTag(
            MinecraftServer server,
            CommandSourceStack source,
            String oldTag,
            String newTag,
            boolean confirm) {
        MilestoneData data = MilestoneData.get(server);
        RenameTagOutcome outcome = executeRenameTag(
                data,
                source.getPlayer(),
                oldTag,
                newTag,
                confirm,
                TagTargets::isNonEmptyTag,
                () -> MilestoneBackup.writeBackupNow(server),
                Tracker.defaultTargets(),
                Presets.all());
        if (confirm && outcome.result().success() && outcome.counts() != null) {
            TagRenameCounts counts = outcome.counts();
            ItemMilestoneLog.LOGGER.info(
                    "[ItemMilestoneLog] Admin {} renamed tag {} -> {} ({} player(s): {} target(s), {} record(s), {} world first(s))",
                    source.getTextName(), outcome.oldTag(), outcome.newTag(),
                    counts.players(), counts.targets(), counts.records(), counts.worldFirsts());
            if (counts.hasChanges()) {
                syncAllOnlineGuiPlayers(server, null);
                // One full refresh instead of an all-players scan per affected player (O(P^2)).
                ScoreboardSync.refreshAll(server);
            }
        }
        return outcome.result();
    }

    static RenameTagOutcome executeRenameTag(
            MilestoneData data,
            @Nullable ServerPlayer executor,
            String oldTag,
            String newTag,
            boolean confirm,
            Predicate<String> isRegisteredTag,
            BooleanSupplier backupWriter,
            @Nullable Collection<TargetKey> defaultTargets,
            @Nullable Collection<Presets.PresetDefinition> presets) {
        String oldNorm = normalizeTagId(oldTag);
        if (oldNorm == null) {
            return new RenameTagOutcome(
                    new ActionResult(false, ServerLang.tr(executor, "message.itemmilestonelog.invalid_tag",
                            Component.literal(String.valueOf(oldTag)))),
                    null, null, null);
        }
        String newNorm = normalizeTagId(newTag);
        if (newNorm == null) {
            return new RenameTagOutcome(
                    new ActionResult(false, ServerLang.tr(executor, "message.itemmilestonelog.invalid_tag",
                            Component.literal(String.valueOf(newTag)))),
                    oldNorm, null, null);
        }
        if (oldNorm.equals(newNorm)) {
            return new RenameTagOutcome(
                    new ActionResult(false, ServerLang.tr(executor, "message.itemmilestonelog.renametag_same")),
                    oldNorm, newNorm, null);
        }
        if (confirm && !data.isEmpty() && !backupWriter.getAsBoolean()) {
            return new RenameTagOutcome(
                    new ActionResult(false, ServerLang.tr(executor, "message.itemmilestonelog.renametag_backup_failed")),
                    oldNorm, newNorm, null);
        }

        TagRenameCounts counts = renameTagInData(data, oldNorm, newNorm, confirm);
        String messageKey = confirm
                ? "message.itemmilestonelog.renametag_done"
                : "message.itemmilestonelog.renametag_preview";
        Component message = ServerLang.tr(executor, messageKey,
                Component.literal(oldNorm),
                Component.literal(newNorm),
                counts.players(),
                counts.targets(),
                counts.records(),
                counts.worldFirsts());
        message = message.copy().append(Component.literal("\n"))
                .append(ServerLang.tr(executor, "message.itemmilestonelog.renametag_scope_note"));
        if (!isRegisteredTag.test(oldNorm)) {
            message = message.copy().append(Component.literal("\n"))
                    .append(ServerLang.tr(executor, "message.itemmilestonelog.renametag_unknown_tag",
                            Component.literal(oldNorm)));
        }
        if (!isRegisteredTag.test(newNorm)) {
            message = message.copy().append(Component.literal("\n"))
                    .append(ServerLang.tr(executor, "message.itemmilestonelog.renametag_unknown_tag",
                            Component.literal(newNorm)));
        }
        if (containsItemIdInConfigOrPresets(oldNorm, defaultTargets, presets)) {
            message = message.copy().append(Component.literal("\n"))
                    .append(ServerLang.tr(executor, "message.itemmilestonelog.renametag_config_note",
                            Component.literal(oldNorm)));
        }
        return new RenameTagOutcome(new ActionResult(true, message), oldNorm, newNorm, counts);
    }

    /**
     * F25: sends the optional {@link SyncExtraPayload} (only when the client supports its channel)
     * followed immediately by {@link SyncMilestonesPayload}, so the extra metadata arrives before
     * the client opens or refreshes the screen.
     */
    public static void sendSync(ServerPlayer player, boolean openScreen, @Nullable Component feedback) {
        if (player.connection == null || !player.connection.hasChannel(SyncMilestonesPayload.TYPE)) {
            return;
        }
        if (player.connection.hasChannel(SyncExtraPayload.TYPE)) {
            SyncExtraPayload extra = createSyncExtraPayload(player);
            ItemMilestoneLog.LOGGER.debug(
                    "[ItemMilestoneLog] Sending SyncExtraPayload to {} (worldFirsts={}, readOnlyPresets={})",
                    player.getUUID(), extra.worldFirsts().size(), extra.readOnlyPresets().size());
            PacketDistributor.sendToPlayer(player, extra);
        } else {
            ItemMilestoneLog.LOGGER.debug(
                    "[ItemMilestoneLog] Not sending SyncExtraPayload to {}: client does not support the channel",
                    player.getUUID());
        }
        PacketDistributor.sendToPlayer(player, createSyncPayload(player, openScreen, feedback));
    }

    public static void syncPlayerIfOpen(ServerPlayer player) {
        sendSync(player, false, null);
    }

    public static void syncAllOnlineGuiPlayers(MinecraftServer server, @Nullable ServerPlayer exclude) {
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other != exclude) {
                syncPlayerIfOpen(other);
            }
        }
    }

    /**
     * F25: builds the optional {@link SyncExtraPayload} for {@code player}.
     * When {@link Config#GUI_WORLD_FIRST_MARK} is false, {@code worldFirsts} is sent empty while
     * {@code readOnlyPresets} (F21: IDs that currently resolve to data pack presets) is still sent.
     */
    public static SyncExtraPayload createSyncExtraPayload(ServerPlayer player) {
        MilestoneData data = MilestoneData.get(player.level().getServer());
        return buildSyncExtraPayload(data, player.getUUID(), Config.guiWorldFirstMark(), Presets.datapackPresetIds());
    }

    static SyncExtraPayload buildSyncExtraPayload(
            @Nullable MilestoneData data,
            @Nullable UUID playerId,
            boolean guiWorldFirstMark,
            @Nullable Collection<String> readOnlyPresetIds) {
        Set<TargetKey> heldWorldFirsts = new LinkedHashSet<>();
        if (guiWorldFirstMark && data != null && playerId != null) {
            for (Map.Entry<TargetKey, WorldFirstRecord> e : data.worldFirsts().entrySet()) {
                WorldFirstRecord wf = e.getValue();
                if (wf != null && playerId.equals(wf.playerUuid())) {
                    heldWorldFirsts.add(e.getKey());
                }
            }
        }
        Set<String> readOnly = new LinkedHashSet<>();
        if (readOnlyPresetIds != null) {
            for (String id : readOnlyPresetIds) {
                if (Presets.isValidId(id)) {
                    readOnly.add(id);
                }
            }
        }
        if (heldWorldFirsts.isEmpty() && readOnly.isEmpty()) {
            return SyncExtraPayload.EMPTY;
        }
        return new SyncExtraPayload(heldWorldFirsts, readOnly);
    }

    /**
     * Builds a complete snapshot of the player's milestone state to send to the client GUI.
     * F54: custom preset names are resolved in the receiving player's language.
     */
    public static SyncMilestonesPayload createSyncPayload(ServerPlayer player, boolean openScreen, @Nullable Component feedback) {
        MilestoneData data = MilestoneData.get(player.level().getServer());
        MilestoneData.PlayerData pd = data.playerOrNull(player.getUUID());
        return buildSyncPayload(
                pd,
                Tracker.defaultTargets(),
                Tracker.carriedFromCreative(pd),
                itemIds -> Tracker.countItemsInPossession(player, itemIds),
                Playtime.currentTicks(player),
                canManagePresets(player),
                openScreen,
                feedback,
                Config.PRESETS_ENABLED.get(),
                clientLanguage(player)
        );
    }

    static SyncMilestonesPayload buildSyncPayload(
            @Nullable MilestoneData.PlayerData pd,
            Set<TargetKey> defaultSet,
            Set<String> carried,
            Function<Set<String>, Map<String, Integer>> possessionCounter,
            int currentPlaytime,
            boolean canManagePresets,
            boolean openScreen,
            @Nullable Component feedback) {
        return buildSyncPayload(pd, defaultSet, carried, possessionCounter, currentPlaytime,
                canManagePresets, openScreen, feedback, true, null);
    }

    static SyncMilestonesPayload buildSyncPayload(
            @Nullable MilestoneData.PlayerData pd,
            Set<TargetKey> defaultSet,
            Set<String> carried,
            Function<Set<String>, Map<String, Integer>> possessionCounter,
            int currentPlaytime,
            boolean canManagePresets,
            boolean openScreen,
            @Nullable Component feedback,
            boolean presetsEnabled) {
        return buildSyncPayload(pd, defaultSet, carried, possessionCounter, currentPlaytime,
                canManagePresets, openScreen, feedback, presetsEnabled, null);
    }

    /**
     * F54: {@code language} is the receiving player's client language code; null resolves
     * custom preset names with {@code en_us} priority.
     */
    static SyncMilestonesPayload buildSyncPayload(
            @Nullable MilestoneData.PlayerData pd,
            Set<TargetKey> defaultSet,
            Set<String> carried,
            Function<Set<String>, Map<String, Integer>> possessionCounter,
            int currentPlaytime,
            boolean canManagePresets,
            boolean openScreen,
            @Nullable Component feedback,
            boolean presetsEnabled,
            @Nullable String language) {
        Set<TargetKey> personalSet = pd != null ? pd.targets : Set.of();

        Set<TargetKey> effective = Tracker.effectiveTargets(defaultSet, pd);
        List<TargetKey> sortedTargets = new ArrayList<>(effective);
        sortedTargets.sort(TargetKey::compareTo);

        List<TargetEntry> targetEntries = new ArrayList<>(sortedTargets.size());
        for (TargetKey key : sortedTargets) {
            targetEntries.add(new TargetEntry(key.itemId(), key.count(), personalSet.contains(key), defaultSet.contains(key)));
        }

        Map<TargetKey, MilestoneRecord> records = new LinkedHashMap<>();
        if (pd != null) {
            records.putAll(pd.records);
        }

        Set<String> progressItemIds = new java.util.HashSet<>();
        for (TargetKey key : sortedTargets) {
            if (key.count() > TargetKey.MIN_COUNT && !records.containsKey(key) && !carried.contains(key.itemId())) {
                progressItemIds.add(key.itemId());
            }
        }
        Map<TargetKey, Integer> progress = new LinkedHashMap<>();
        Map<String, Integer> counts = progressItemIds.isEmpty()
                ? Map.of()
                : possessionCounter.apply(progressItemIds);
        for (TargetKey key : sortedTargets) {
            if (key.count() > TargetKey.MIN_COUNT && !records.containsKey(key)) {
                int current = carried.contains(key.itemId()) ? 0 : counts.getOrDefault(key.itemId(), 0);
                progress.put(key, current);
            }
        }

        List<PresetEntry> presetEntries = new ArrayList<>();
        if (presetsEnabled) {
            for (Presets.PresetDefinition p : Presets.all()) {
                // F54: custom presets resolve their name in the receiving player's language;
                // built-ins still travel empty (the client translates them via their lang key).
                presetEntries.add(new PresetEntry(
                        p.id(),
                        p.builtin() ? "" : p.resolveDisplayName(language),
                        p.items().size(),
                        p.builtin()
                ));
            }
        }

        return new SyncMilestonesPayload(
                targetEntries,
                records,
                progress,
                currentPlaytime,
                presetEntries,
                presetsEnabled && canManagePresets,
                openScreen,
                feedback
        );
    }
}
