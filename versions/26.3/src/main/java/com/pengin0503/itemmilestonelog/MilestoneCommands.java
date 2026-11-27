package com.pengin0503.itemmilestonelog;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.pengin0503.itemmilestonelog.api.MilestoneRecord;
import com.pengin0503.itemmilestonelog.api.TargetKey;
import com.pengin0503.itemmilestonelog.api.WorldFirstRecord;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.PlayerTeam;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import javax.annotation.Nullable;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.concurrent.ThreadLocalRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * /milestonelog (alias /iml) - manage personal target items, view records, presets,
 * world-firsts, exports/imports, and OP admin tools.
 */
public final class MilestoneCommands {
    private MilestoneCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandBuildContext buildContext = event.getBuildContext();
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(root(buildContext, dispatcher));
        // A redirect only forwards what follows "/iml", so the alias needs its own
        // executes() for "/iml" alone to show the summary like "/milestonelog".
        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("iml")
                .executes(MilestoneCommands::summary)
                .redirect(dispatcher.getRoot().getChild("milestonelog")));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    private static <T> RequiredArgumentBuilder<CommandSourceStack, T> argument(String name, ArgumentType<T> type) {
        return RequiredArgumentBuilder.argument(name, type);
    }

    /** Item IDs of the non-tag keys (tag keys cannot be typed into the item commands, F8b). */
    private static Set<String> itemIdsOf(Set<TargetKey> keys) {
        Set<String> ids = new LinkedHashSet<>();
        for (TargetKey key : keys) {
            if (!key.isTag()) {
                ids.add(key.itemId());
            }
        }
        return ids;
    }

    /**
     * #153: imports still accept the legacy {@code <playerName>.json} export as well as the
     * current {@code <playerName>-<uuid>.json} form, so a non-OP player should be offered both
     * forms when the corresponding files actually exist. Deduplicated (the two names coincide
     * when no UUID is available); the import acceptance rules are unchanged.
     */
    static List<String> ownImportFileSuggestions(
            List<String> files, @Nullable String playerName, @Nullable UUID uuid) {
        LinkedHashSet<String> own = new LinkedHashSet<>();
        String current = MilestoneExport.sanitizePlayerFileName(playerName, uuid);
        if (files.contains(current)) {
            own.add(current);
        }
        String legacy = MilestoneExport.sanitizePlayerFileName(playerName);
        if (files.contains(legacy)) {
            own.add(legacy);
        }
        return List.copyOf(own);
    }

    /** F24: non-tag item IDs present anywhere in {@code data} (for {@code renameitem <oldId>} suggestions). */
    static Set<String> allStoredItemIds(MilestoneData data) {
        Set<String> ids = new LinkedHashSet<>();
        for (MilestoneData.PlayerData pd : data.allPlayers().values()) {
            for (TargetKey key : pd.targets) {
                if (!key.isTag()) {
                    ids.add(key.itemId());
                }
            }
            for (TargetKey key : pd.records.keySet()) {
                if (!key.isTag()) {
                    ids.add(key.itemId());
                }
            }
            for (TargetKey key : pd.progressNotified.keySet()) {
                if (!key.isTag()) {
                    ids.add(key.itemId());
                }
            }
            for (String carriedId : pd.carriedFromCreative) {
                if (carriedId != null && !carriedId.isEmpty() && !TargetKey.isTagId(carriedId)) {
                    ids.add(carriedId);
                }
            }
        }
        for (TargetKey key : data.worldFirsts().keySet()) {
            if (!key.isTag()) {
                ids.add(key.itemId());
            }
        }
        return ids;
    }

    /** F-140-19: tag IDs (with the leading {@code '#'}) present in any player's targets. */
    static Set<String> allStoredTagIds(MilestoneData data) {
        Set<String> ids = new LinkedHashSet<>();
        for (MilestoneData.PlayerData pd : data.allPlayers().values()) {
            for (TargetKey key : pd.targets) {
                if (key.isTag()) {
                    ids.add(key.itemId());
                }
            }
        }
        return ids;
    }

    /** F8b: tag IDs (without the leading {@code '#'}) of the tag keys, as typed into the tag commands. */
    static Set<String> tagIdsOf(Collection<TargetKey> keys) {
        Set<String> ids = new LinkedHashSet<>();
        for (TargetKey key : keys) {
            if (key.isTag()) {
                ids.add(key.tagId());
            }
        }
        return ids;
    }

    /** Non-tag IDs among {@code ids}. */
    private static List<String> nonTagIds(Collection<String> ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            if (!TargetKey.isTagId(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /** F8b: tag IDs (without the leading {@code '#'}) among {@code ids}. */
    private static List<String> tagIdsAmong(Collection<String> ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            if (TargetKey.isTagId(id)) {
                out.add(id.substring(1));
            }
        }
        return out;
    }

    /** F8b: the {@code <tag>} argument (written without {@code '#'}) as a {@code '#'}-prefixed target ID. */
    private static String tagArg(CommandContext<CommandSourceStack> ctx) {
        return MilestoneService.tagKeyId(IdentifierArgument.getId(ctx, "tag").toString());
    }

    /** F8b: the {@code <tag>} argument type ({@code IdentifierArgument} / {@code ResourceLocationArgument}). */
    private static ArgumentType<?> tagArgumentType() {
        return IdentifierArgument.id();
    }

    /** PR-2: the item-id argument type for analytics commands, version-abstracted like {@link #tagArgumentType()}. */
    private static ArgumentType<?> itemIdentifierArgumentType() {
        return IdentifierArgument.id();
    }

    /** PR-2: reads an analytics item-id argument as the stored ID string. */
    private static String itemIdentifierArg(CommandContext<CommandSourceStack> ctx, String name) {
        return IdentifierArgument.getId(ctx, name).toString();
    }

    @Nullable
    private static MilestoneData.PlayerData sourcePlayerData(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer sp = ctx.getSource().getPlayer();
        MinecraftServer server = ctx.getSource().getServer();
        if (sp == null || server == null) {
            return null;
        }
        return MilestoneData.get(server).playerOrNull(sp.getUUID());
    }

    @Nullable
    private static MilestoneData.PlayerData adminTargetData(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        if (server == null) {
            return null;
        }
        ResolvedPlayer resolved = resolvePlayerData(server, StringArgumentType.getString(ctx, "player"));
        return resolved.entry != null ? resolved.entry.getValue() : null;
    }

    private static Set<String> countsForItem(Set<TargetKey> keys, String itemId) {
        Set<String> counts = new LinkedHashSet<>();
        for (TargetKey key : keys) {
            if (key.itemId().equals(itemId)) {
                counts.add(String.valueOf(key.count()));
            }
        }
        return counts;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root(
            CommandBuildContext buildContext,
            CommandDispatcher<CommandSourceStack> dispatcher) {
        return buildCommandTree(() -> ItemArgument.item(buildContext),
                Commands.hasPermission(Commands.LEVEL_GAMEMASTERS), dispatcher);
    }

    /** PR-2 (F-150-29): the shared trailing {@code axis <value> [days <1-30>]} branch. */
    private static LiteralArgumentBuilder<CommandSourceStack> axisBranch(
            SuggestionProvider<CommandSourceStack> suggestions, AxisExecutor executor) {
        return literal("axis")
                .then(argument("axis", StringArgumentType.word())
                        .suggests(suggestions)
                        .executes(ctx -> executor.run(ctx, StringArgumentType.getString(ctx, "axis"), null))
                        .then(literal("days")
                                .then(argument("days", IntegerArgumentType.integer(1, 30))
                                        .executes(ctx -> executor.run(ctx,
                                                StringArgumentType.getString(ctx, "axis"),
                                                IntegerArgumentType.getInteger(ctx, "days"))))));
    }

    @FunctionalInterface
    private interface AxisExecutor {
        int run(CommandContext<CommandSourceStack> ctx, String axis, @Nullable Integer days)
                throws CommandSyntaxException;
    }

    /** PR-2 (F-150-33): one {@code compare <player> <branch> [<page>]} literal. */
    private static LiteralArgumentBuilder<CommandSourceStack> compareBranchNode(String branch) {
        return literal(branch)
                .executes(ctx -> compare(ctx, StringArgumentType.getString(ctx, "player"), branch, null))
                .then(argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> compare(ctx, StringArgumentType.getString(ctx, "player"), branch,
                                IntegerArgumentType.getInteger(ctx, "page"))));
    }

    /** Package-private so unit tests can inspect the real registered Brigadier command tree without a registry. */
    static LiteralArgumentBuilder<CommandSourceStack> buildCommandTree(
            Supplier<ArgumentType<?>> itemArg,
            Predicate<CommandSourceStack> adminRequirement,
            CommandDispatcher<CommandSourceStack> dispatcher) {
        // F43: every admin-gated branch keeps its old level-2 legacy check and additionally accepts
        // the matching permission node for player sources. Non-player sources use the legacy check.
        Predicate<CommandSourceStack> adminRequirementWithNode = src ->
                MilestonePermissions.check(src, MilestonePermissions.ADMIN, adminRequirement.test(src));
        Predicate<CommandSourceStack> firstsResetRequirement = src ->
                MilestonePermissions.check(src, MilestonePermissions.FIRSTS_RESET, adminRequirement.test(src));
        Predicate<CommandSourceStack> exportAllRequirement = src ->
                MilestonePermissions.check(src, MilestonePermissions.EXPORT_ALL, adminRequirement.test(src));
        Predicate<CommandSourceStack> importAllRequirement = src ->
                MilestonePermissions.check(src, MilestonePermissions.IMPORT_ALL, adminRequirement.test(src));
        Predicate<CommandSourceStack> pruneDataRequirement = src ->
                MilestonePermissions.check(src, MilestonePermissions.PRUNEDATA, adminRequirement.test(src));
        Predicate<CommandSourceStack> renameItemRequirement = src ->
                MilestonePermissions.check(src, MilestonePermissions.RENAMEITEM, adminRequirement.test(src));
        Predicate<CommandSourceStack> discordTestRequirement = src ->
                MilestonePermissions.check(src, MilestonePermissions.DISCORDTEST, adminRequirement.test(src));

        SuggestionProvider<CommandSourceStack> presetSuggestions = (ctx, builder) -> {
            if (!Config.PRESETS_ENABLED.get()) {
                return builder.buildFuture();
            }
            return SharedSuggestionProvider.suggest(Presets.names(), builder);
        };
        SuggestionProvider<CommandSourceStack> customPresetSuggestions = (ctx, builder) -> {
            if (!Config.PRESETS_ENABLED.get()) {
                return builder.buildFuture();
            }
            List<String> custom = Presets.all().stream()
                    .filter(p -> !p.builtin())
                    .map(Presets.PresetDefinition::id)
                    .toList();
            return SharedSuggestionProvider.suggest(custom, builder);
        };
        SuggestionProvider<CommandSourceStack> personalTargetSuggestions = (ctx, builder) -> {
            ServerPlayer sp = ctx.getSource().getPlayer();
            if (sp == null) {
                return builder.buildFuture();
            }
            MilestoneData.PlayerData pd = MilestoneData.get(sp.level().getServer()).playerOrNull(sp.getUUID());
            return SharedSuggestionProvider.suggest(pd != null ? itemIdsOf(pd.targets) : Set.of(), builder);
        };
        // F-150-2 (N96): vanilla suggestion provider for the four target kinds (D-150-5).
        SuggestionProvider<CommandSourceStack> kindSuggestions = (ctx, builder) ->
                SharedSuggestionProvider.suggest(List.of("tags", "items", "counts", "default"), builder);
        // F-150-3 (N100): vanilla suggestion provider for the four search scopes.
        SuggestionProvider<CommandSourceStack> scopeSuggestions = (ctx, builder) ->
                SharedSuggestionProvider.suggest(List.of("all", "targets", "records", "presets"), builder);
        // PR-1 (N57/N58/N93/N159/N160): fixed vanilla suggestion sets for the opt-in arguments.
        SuggestionProvider<CommandSourceStack> listSortSuggestions = (ctx, builder) ->
                SharedSuggestionProvider.suggest(List.of("recent", "name", "day"), builder);
        SuggestionProvider<CommandSourceStack> settingToggleSuggestions = (ctx, builder) ->
                SharedSuggestionProvider.suggest(List.of("broadcast", "progress", "discord", "privacy"), builder);
        SuggestionProvider<CommandSourceStack> randomTargetKindSuggestions = (ctx, builder) ->
                SharedSuggestionProvider.suggest(List.of("item", "tag", "both"), builder);
        SuggestionProvider<CommandSourceStack> trendDaysSuggestions = (ctx, builder) ->
                SharedSuggestionProvider.suggest(List.of("7", "30", "90"), builder);
        SuggestionProvider<CommandSourceStack> recapKindSuggestions = (ctx, builder) ->
                SharedSuggestionProvider.suggest(List.of("return", "daily", "legacy"), builder);
        // PR-2: all opt-in value sets are plain vanilla word suggestions (D-150-51/73/82).
        SuggestionProvider<CommandSourceStack> compareBranchSuggestions = (ctx, builder) ->
                SharedSuggestionProvider.suggest(List.of("missing", "route", "rival"), builder);
        SuggestionProvider<CommandSourceStack> sessionHoursSuggestions = (ctx, builder) ->
                SharedSuggestionProvider.suggest(List.of("1", "6", "24", "72"), builder);
        SuggestionProvider<CommandSourceStack> paceScopeSuggestions = (ctx, builder) ->
                SharedSuggestionProvider.suggest(List.of("all", "targets", "presets"), builder);
        SuggestionProvider<CommandSourceStack> rankingAxisSuggestions = (ctx, builder) ->
                SharedSuggestionProvider.suggest(List.of("all", "period", "online", "team"), builder);
        // F-150-6 (N05): vanilla suggestion provider for the three unclaimed scopes (D-150-21).
        SuggestionProvider<CommandSourceStack> unclaimedScopeSuggestions = (ctx, builder) ->
                SharedSuggestionProvider.suggest(List.of("targets", "presets", "all"), builder);
        SuggestionProvider<CommandSourceStack> personalTargetCountSuggestions = (ctx, builder) -> {
            ServerPlayer sp = ctx.getSource().getPlayer();
            if (sp == null) {
                return builder.buildFuture();
            }
            MilestoneData.PlayerData pd = MilestoneData.get(sp.level().getServer()).playerOrNull(sp.getUUID());
            if (pd == null) {
                return builder.buildFuture();
            }
            String itemId = IdentifierArgument.getId(ctx, "item").toString();
            return SharedSuggestionProvider.suggest(countsForItem(pd.targets, itemId), builder);
        };
        SuggestionProvider<CommandSourceStack> recordedSuggestions = (ctx, builder) -> {
            ServerPlayer sp = ctx.getSource().getPlayer();
            if (sp == null) {
                return builder.buildFuture();
            }
            MilestoneData.PlayerData pd = MilestoneData.get(sp.level().getServer()).playerOrNull(sp.getUUID());
            return SharedSuggestionProvider.suggest(pd != null ? itemIdsOf(pd.records.keySet()) : Set.of(), builder);
        };
        SuggestionProvider<CommandSourceStack> recordedCountSuggestions = (ctx, builder) -> {
            ServerPlayer sp = ctx.getSource().getPlayer();
            if (sp == null) {
                return builder.buildFuture();
            }
            MilestoneData.PlayerData pd = MilestoneData.get(sp.level().getServer()).playerOrNull(sp.getUUID());
            if (pd == null) {
                return builder.buildFuture();
            }
            String itemId = IdentifierArgument.getId(ctx, "item").toString();
            return SharedSuggestionProvider.suggest(countsForItem(pd.records.keySet(), itemId), builder);
        };
        SuggestionProvider<CommandSourceStack> worldFirstSuggestions = (ctx, builder) -> {
            MinecraftServer server = ctx.getSource().getServer();
            if (server == null) {
                return builder.buildFuture();
            }
            return SharedSuggestionProvider.suggest(itemIdsOf(MilestoneData.get(server).worldFirsts().keySet()), builder);
        };
        SuggestionProvider<CommandSourceStack> worldFirstCountSuggestions = (ctx, builder) -> {
            MinecraftServer server = ctx.getSource().getServer();
            if (server == null) {
                return builder.buildFuture();
            }
            String itemId = IdentifierArgument.getId(ctx, "item").toString();
            return SharedSuggestionProvider.suggest(countsForItem(MilestoneData.get(server).worldFirsts().keySet(), itemId), builder);
        };
        SuggestionProvider<CommandSourceStack> exportFileSuggestions = (ctx, builder) -> {
            if (!Config.EXPORT_IMPORT_ENABLED.get()) {
                return builder.buildFuture();
            }
            MinecraftServer server = ctx.getSource().getServer();
            if (server == null) {
                return builder.buildFuture();
            }
            List<String> files = MilestoneExport.listExportFiles(server);
            CommandSourceStack source = ctx.getSource();
            if (MilestonePermissions.check(source, MilestonePermissions.IMPORT_ALL,
                    Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source))) {
                return SharedSuggestionProvider.suggest(files, builder);
            }
            ServerPlayer sp = ctx.getSource().getPlayer();
            if (sp == null) {
                return builder.buildFuture();
            }
            return SharedSuggestionProvider.suggest(
                    ownImportFileSuggestions(files, sp.getGameProfile().name(), sp.getUUID()), builder);
        };
        SuggestionProvider<CommandSourceStack> adminPlayerSuggestions = (ctx, builder) -> {
            MinecraftServer server = ctx.getSource().getServer();
            if (server == null) {
                return builder.buildFuture();
            }
            Set<String> names = new LinkedHashSet<>();
            for (Map.Entry<UUID, MilestoneData.PlayerData> e : MilestoneData.get(server).allPlayers().entrySet()) {
                String n = e.getValue().lastKnownName;
                if (n != null && !n.isBlank()) {
                    names.add(n);
                } else {
                    names.add(e.getKey().toString());
                }
            }
            return SharedSuggestionProvider.suggest(names, builder);
        };
        SuggestionProvider<CommandSourceStack> adminPersonalTargetSuggestions = (ctx, builder) -> {
            MinecraftServer server = ctx.getSource().getServer();
            if (server == null) {
                return builder.buildFuture();
            }
            String query = StringArgumentType.getString(ctx, "player");
            ResolvedPlayer resolved = resolvePlayerData(server, query);
            if (resolved.entry == null) {
                return builder.buildFuture();
            }
            return SharedSuggestionProvider.suggest(itemIdsOf(resolved.entry.getValue().targets), builder);
        };
        SuggestionProvider<CommandSourceStack> adminPersonalTargetCountSuggestions = (ctx, builder) -> {
            MinecraftServer server = ctx.getSource().getServer();
            if (server == null) {
                return builder.buildFuture();
            }
            String query = StringArgumentType.getString(ctx, "player");
            ResolvedPlayer resolved = resolvePlayerData(server, query);
            if (resolved.entry == null) {
                return builder.buildFuture();
            }
            String itemId = IdentifierArgument.getId(ctx, "item").toString();
            return SharedSuggestionProvider.suggest(countsForItem(resolved.entry.getValue().targets, itemId), builder);
        };
        SuggestionProvider<CommandSourceStack> adminRecordedSuggestions = (ctx, builder) -> {
            MinecraftServer server = ctx.getSource().getServer();
            if (server == null) {
                return builder.buildFuture();
            }
            String query = StringArgumentType.getString(ctx, "player");
            ResolvedPlayer resolved = resolvePlayerData(server, query);
            if (resolved.entry == null) {
                return builder.buildFuture();
            }
            return SharedSuggestionProvider.suggest(itemIdsOf(resolved.entry.getValue().records.keySet()), builder);
        };
        SuggestionProvider<CommandSourceStack> adminRecordedCountSuggestions = (ctx, builder) -> {
            MinecraftServer server = ctx.getSource().getServer();
            if (server == null) {
                return builder.buildFuture();
            }
            String query = StringArgumentType.getString(ctx, "player");
            ResolvedPlayer resolved = resolvePlayerData(server, query);
            if (resolved.entry == null) {
                return builder.buildFuture();
            }
            String itemId = IdentifierArgument.getId(ctx, "item").toString();
            return SharedSuggestionProvider.suggest(countsForItem(resolved.entry.getValue().records.keySet(), itemId), builder);
        };
        SuggestionProvider<CommandSourceStack> lookupPlayerSuggestions = (ctx, builder) -> {
            CommandSourceStack source = ctx.getSource();
            MinecraftServer server = source.getServer();
            if (server == null) {
                return builder.buildFuture();
            }
            return SharedSuggestionProvider.suggest(lookupSuggestionNames(source, server), builder);
        };
        SuggestionProvider<CommandSourceStack> topItemSuggestions = (ctx, builder) -> {
            CommandSourceStack source = ctx.getSource();
            MinecraftServer server = source.getServer();
            if (server == null || !canUseRankings(source)) {
                return builder.buildFuture();
            }
            return SharedSuggestionProvider.suggest(nonTagIds(
                    MilestoneRankings.recordedItemIds(MilestoneData.get(server).allPlayers(), rankingViewer(source))), builder);
        };
        SuggestionProvider<CommandSourceStack> topItemCountSuggestions = (ctx, builder) -> {
            CommandSourceStack source = ctx.getSource();
            MinecraftServer server = source.getServer();
            if (server == null || !canUseRankings(source)) {
                return builder.buildFuture();
            }
            String itemId = IdentifierArgument.getId(ctx, "item").toString();
            List<String> counts = MilestoneRankings.recordedCounts(MilestoneData.get(server).allPlayers(), rankingViewer(source), itemId)
                    .stream().map(String::valueOf).toList();
            return SharedSuggestionProvider.suggest(counts, builder);
        };
        SuggestionProvider<CommandSourceStack> helpSubcommandSuggestions = (ctx, builder) -> {
            List<String> names = visibleHelpEntries(ctx.getSource(), dispatcher)
                    .stream()
                    .map(CommandHelp.Entry::name)
                    .toList();
            return SharedSuggestionProvider.suggest(names, builder);
        };
        // F8b: <tag> is written without '#'; suggestions are tag IDs without '#'.
        SuggestionProvider<CommandSourceStack> allTagSuggestions = (ctx, builder) ->
                SharedSuggestionProvider.suggest(TagTargets.suggestableTagIds(), builder);
        SuggestionProvider<CommandSourceStack> personalTagTargetSuggestions = (ctx, builder) -> {
            MilestoneData.PlayerData pd = sourcePlayerData(ctx);
            return SharedSuggestionProvider.suggest(pd != null ? tagIdsOf(pd.targets) : Set.of(), builder);
        };
        SuggestionProvider<CommandSourceStack> personalTagTargetCountSuggestions = (ctx, builder) -> {
            MilestoneData.PlayerData pd = sourcePlayerData(ctx);
            return SharedSuggestionProvider.suggest(pd != null ? countsForItem(pd.targets, tagArg(ctx)) : Set.of(), builder);
        };
        SuggestionProvider<CommandSourceStack> recordedTagSuggestions = (ctx, builder) -> {
            MilestoneData.PlayerData pd = sourcePlayerData(ctx);
            return SharedSuggestionProvider.suggest(pd != null ? tagIdsOf(pd.records.keySet()) : Set.of(), builder);
        };
        SuggestionProvider<CommandSourceStack> recordedTagCountSuggestions = (ctx, builder) -> {
            MilestoneData.PlayerData pd = sourcePlayerData(ctx);
            return SharedSuggestionProvider.suggest(
                    pd != null ? countsForItem(pd.records.keySet(), tagArg(ctx)) : Set.of(), builder);
        };
        SuggestionProvider<CommandSourceStack> worldFirstTagSuggestions = (ctx, builder) -> {
            MinecraftServer server = ctx.getSource().getServer();
            if (server == null) {
                return builder.buildFuture();
            }
            return SharedSuggestionProvider.suggest(tagIdsOf(MilestoneData.get(server).worldFirsts().keySet()), builder);
        };
        SuggestionProvider<CommandSourceStack> worldFirstTagCountSuggestions = (ctx, builder) -> {
            MinecraftServer server = ctx.getSource().getServer();
            if (server == null) {
                return builder.buildFuture();
            }
            return SharedSuggestionProvider.suggest(
                    countsForItem(MilestoneData.get(server).worldFirsts().keySet(), tagArg(ctx)), builder);
        };
        SuggestionProvider<CommandSourceStack> adminRecordedTagSuggestions = (ctx, builder) -> {
            MilestoneData.PlayerData pd = adminTargetData(ctx);
            return SharedSuggestionProvider.suggest(pd != null ? tagIdsOf(pd.records.keySet()) : Set.of(), builder);
        };
        SuggestionProvider<CommandSourceStack> adminRecordedTagCountSuggestions = (ctx, builder) -> {
            MilestoneData.PlayerData pd = adminTargetData(ctx);
            return SharedSuggestionProvider.suggest(
                    pd != null ? countsForItem(pd.records.keySet(), tagArg(ctx)) : Set.of(), builder);
        };
        SuggestionProvider<CommandSourceStack> topTagSuggestions = (ctx, builder) -> {
            CommandSourceStack source = ctx.getSource();
            MinecraftServer server = source.getServer();
            if (server == null || !canUseRankings(source)) {
                return builder.buildFuture();
            }
            return SharedSuggestionProvider.suggest(tagIdsAmong(
                    MilestoneRankings.recordedItemIds(MilestoneData.get(server).allPlayers(), rankingViewer(source))), builder);
        };
        SuggestionProvider<CommandSourceStack> topTagCountSuggestions = (ctx, builder) -> {
            CommandSourceStack source = ctx.getSource();
            MinecraftServer server = source.getServer();
            if (server == null || !canUseRankings(source)) {
                return builder.buildFuture();
            }
            List<String> counts = MilestoneRankings.recordedCounts(MilestoneData.get(server).allPlayers(), rankingViewer(source), tagArg(ctx))
                    .stream().map(String::valueOf).toList();
            return SharedSuggestionProvider.suggest(counts, builder);
        };
        SuggestionProvider<CommandSourceStack> renameOldItemSuggestions = (ctx, builder) -> {
            MinecraftServer server = ctx.getSource().getServer();
            if (server == null) {
                return builder.buildFuture();
            }
            return SharedSuggestionProvider.suggest(allStoredItemIds(MilestoneData.get(server)), builder);
        };
        SuggestionProvider<CommandSourceStack> renameNewItemSuggestions = (ctx, builder) -> {
            List<String> items = BuiltInRegistries.ITEM.keySet().stream()
                    .map(Object::toString)
                    .filter(id -> !"minecraft:air".equals(id))
                    .toList();
            return SharedSuggestionProvider.suggest(items, builder);
        };
        // G10a: one greedy string carries both tag IDs. Complete whichever token the caret
        // is in; a quoted token keeps its quotes around the suggested tag ID.
        SuggestionProvider<CommandSourceStack> renameTagSuggestions = (ctx, builder) -> {
            MinecraftServer server = ctx.getSource().getServer();
            if (server == null) {
                return builder.buildFuture();
            }
            Set<String> knownTags = new LinkedHashSet<>(allStoredTagIds(MilestoneData.get(server)));
            TagTargets.suggestableTagIds().forEach(id -> knownTags.add("#" + id));
            String raw = builder.getInput().substring(builder.getStart());
            RenameTagArguments.Cursor cursor = RenameTagArguments.cursorForSuggestions(raw);
            Set<String> tags = knownTags;
            if (cursor.quote() != 0) {
                Set<String> quoted = new LinkedHashSet<>();
                for (String tag : tags) {
                    quoted.add(cursor.quote() + tag + cursor.quote());
                }
                tags = quoted;
            }
            SuggestionsBuilder tokenBuilder = new SuggestionsBuilder(
                    builder.getInput(), builder.getStart() + cursor.tokenStart());
            return SharedSuggestionProvider.suggest(tags, tokenBuilder);
        };
        // F51: own effective targets + own records + items recorded by anyone the F6 gate allows.
        SuggestionProvider<CommandSourceStack> infoItemSuggestions = (ctx, builder) -> {
            ServerPlayer sp = ctx.getSource().getPlayer();
            if (sp == null) {
                return builder.buildFuture();
            }
            MilestoneData data = MilestoneData.get(sp.level().getServer());
            MilestoneData.PlayerData pd = data.playerOrNull(sp.getUUID());
            return SharedSuggestionProvider.suggest(
                    ItemInfoLogic.suggestionItemIds(
                            data.allPlayers(),
                            rankingViewer(ctx.getSource()),
                            Config.ALLOW_LOOKUP.get(),
                            Tracker.effectiveTargets(pd),
                            pd != null ? pd.records : Map.of()),
                    builder);
        };
        SuggestionProvider<CommandSourceStack> infoItemCountSuggestions = (ctx, builder) -> {
            ServerPlayer sp = ctx.getSource().getPlayer();
            if (sp == null) {
                return builder.buildFuture();
            }
            MilestoneData.PlayerData pd = MilestoneData.get(sp.level().getServer()).playerOrNull(sp.getUUID());
            String itemId = IdentifierArgument.getId(ctx, "item").toString();
            // Default targets still provide count suggestions before the player's data exists.
            Set<String> counts = new LinkedHashSet<>(countsForItem(Tracker.effectiveTargets(pd), itemId));
            counts.addAll(countsForItem(pd != null ? pd.records.keySet() : Set.of(), itemId));
            return SharedSuggestionProvider.suggest(counts, builder);
        };

        return literal("milestonelog")
                .executes(MilestoneCommands::summary)
                .then(literal("playtime").executes(MilestoneCommands::playtime))
                .then(literal("add")
                        .then(argument("item", itemArg.get())
                                .executes(ctx -> add(ctx, itemId(ctx), TargetKey.MIN_COUNT))
                                .then(argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                        .executes(ctx -> add(ctx, itemId(ctx), IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(literal("addhand")
                        .executes(ctx -> addHand(ctx, TargetKey.MIN_COUNT))
                        .then(argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                .executes(ctx -> addHand(ctx, IntegerArgumentType.getInteger(ctx, "count")))))
                .then(literal("addtag")
                        .then(argument("tag", tagArgumentType())
                                .suggests(allTagSuggestions)
                                .executes(ctx -> add(ctx, tagArg(ctx), TargetKey.MIN_COUNT))
                                .then(argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                        .executes(ctx -> add(ctx, tagArg(ctx), IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(literal("removetag")
                        .then(argument("tag", tagArgumentType())
                                .suggests(personalTagTargetSuggestions)
                                .executes(ctx -> remove(ctx, tagArg(ctx), TargetKey.MIN_COUNT))
                                .then(argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                        .suggests(personalTagTargetCountSuggestions)
                                        .executes(ctx -> remove(ctx, tagArg(ctx), IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(literal("resettag")
                        .then(argument("tag", tagArgumentType())
                                .suggests(recordedTagSuggestions)
                                .executes(ctx -> reset(ctx, tagArg(ctx), TargetKey.MIN_COUNT))
                                .then(argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                        .suggests(recordedTagCountSuggestions)
                                        .executes(ctx -> reset(ctx, tagArg(ctx), IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(literal("toptag")
                        .then(argument("tag", tagArgumentType())
                                .suggests(topTagSuggestions)
                                .executes(ctx -> topItem(ctx, tagArg(ctx), TargetKey.MIN_COUNT, false))
                                .then(literal("gaps")
                                        .executes(ctx -> topItem(ctx, tagArg(ctx), TargetKey.MIN_COUNT, true)))
                                .then(argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                        .suggests(topTagCountSuggestions)
                                        .executes(ctx -> topItem(ctx, tagArg(ctx),
                                                IntegerArgumentType.getInteger(ctx, "count"), false))
                                        .then(literal("gaps")
                                                .executes(ctx -> topItem(ctx, tagArg(ctx),
                                                        IntegerArgumentType.getInteger(ctx, "count"), true))))))
                .then(literal("remove")
                        .then(argument("item", IdentifierArgument.id())
                                .suggests(personalTargetSuggestions)
                                .executes(ctx -> remove(ctx, IdentifierArgument.getId(ctx, "item").toString(), TargetKey.MIN_COUNT))
                                .then(argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                        .suggests(personalTargetCountSuggestions)
                                        .executes(ctx -> remove(ctx, IdentifierArgument.getId(ctx, "item").toString(),
                                                IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(literal("removeall").executes(MilestoneCommands::removeAll))
                .then(targetsBranch(kindSuggestions))
                .then(listBranch(listSortSuggestions))
                .then(searchBranch(scopeSuggestions))
                .then(literal("preset")
                        .then(literal("list").executes(MilestoneCommands::presetList))
                        .then(literal("save")
                                .executes(MilestoneCommands::presetSaveMissingName)
                                .then(argument("name", StringArgumentType.word())
                                        .executes(ctx -> presetSave(ctx, StringArgumentType.getString(ctx, "name")))))
                        .then(literal("delete")
                                .executes(MilestoneCommands::presetDeleteMissingName)
                                .then(argument("name", StringArgumentType.word())
                                        .suggests(customPresetSuggestions)
                                        .executes(ctx -> presetDelete(ctx, StringArgumentType.getString(ctx, "name")))))
                        .then(literal("reload")
                                .executes(MilestoneCommands::presetReload))
                        .then(literal("show")
                                .then(argument("name", StringArgumentType.word())
                                        .suggests(presetSuggestions)
                                        .executes(ctx -> presetShow(ctx, StringArgumentType.getString(ctx, "name")))))
                        .then(literal("diff")
                                .then(argument("nameA", StringArgumentType.word())
                                        .suggests(presetSuggestions)
                                        .then(argument("nameB", StringArgumentType.word())
                                                .suggests(presetSuggestions)
                                                .executes(ctx -> presetDiff(ctx,
                                                        StringArgumentType.getString(ctx, "nameA"),
                                                        StringArgumentType.getString(ctx, "nameB"))))))
                        .then(literal("share")
                                .then(argument("name", StringArgumentType.word())
                                        .suggests(presetSuggestions)
                                        .executes(ctx -> presetShare(ctx, StringArgumentType.getString(ctx, "name")))))
                        .then(literal("importcode")
                                .then(argument("code", StringArgumentType.greedyString())
                                        .executes(ctx -> presetImportCode(ctx, StringArgumentType.getString(ctx, "code")))))
                        // F-150-40 (N178): literals, not preset IDs (D-150-97 adds them to
                        // Presets.RESERVED_IDS). Permission is checked in the handler like the
                        // existing preset save/delete/reload branches.
                        .then(literal("edit")
                                .then(argument("name", StringArgumentType.word())
                                        .suggests(customPresetSuggestions)
                                        .then(literal("add")
                                                .then(argument("item", IdentifierArgument.id())
                                                        .executes(ctx -> presetEdit(ctx,
                                                                StringArgumentType.getString(ctx, "name"),
                                                                IdentifierArgument.getId(ctx, "item").toString(),
                                                                TargetKey.MIN_COUNT, false))
                                                        .then(argument("count", IntegerArgumentType.integer(
                                                                TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                                                .executes(ctx -> presetEdit(ctx,
                                                                        StringArgumentType.getString(ctx, "name"),
                                                                        IdentifierArgument.getId(ctx, "item").toString(),
                                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                                        false)))))
                                        .then(literal("remove")
                                                .then(argument("item", IdentifierArgument.id())
                                                        .executes(ctx -> presetEdit(ctx,
                                                                StringArgumentType.getString(ctx, "name"),
                                                                IdentifierArgument.getId(ctx, "item").toString(),
                                                                TargetKey.MIN_COUNT, true))
                                                        .then(argument("count", IntegerArgumentType.integer(
                                                                TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                                                .executes(ctx -> presetEdit(ctx,
                                                                        StringArgumentType.getString(ctx, "name"),
                                                                        IdentifierArgument.getId(ctx, "item").toString(),
                                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                                        true)))))))
                        .then(literal("protect")
                                .then(argument("name", StringArgumentType.word())
                                        .suggests(customPresetSuggestions)
                                        .executes(ctx -> presetProtect(ctx,
                                                StringArgumentType.getString(ctx, "name"), true, false))
                                        .then(literal("confirm")
                                                .executes(ctx -> presetProtect(ctx,
                                                        StringArgumentType.getString(ctx, "name"), true, true)))))
                        .then(literal("unprotect")
                                .then(argument("name", StringArgumentType.word())
                                        .suggests(customPresetSuggestions)
                                        .executes(ctx -> presetProtect(ctx,
                                                StringArgumentType.getString(ctx, "name"), false, false))
                                        .then(literal("confirm")
                                                .executes(ctx -> presetProtect(ctx,
                                                        StringArgumentType.getString(ctx, "name"), false, true)))))
                        .then(argument("name", StringArgumentType.word())
                                .suggests(presetSuggestions)
                                .executes(ctx -> preset(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(literal("presetprogress")
                        .then(argument("name", StringArgumentType.word())
                                .suggests(presetSuggestions)
                                .executes(ctx -> presetProgress(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(literal("presetremove")
                        .then(argument("name", StringArgumentType.word())
                                .suggests(presetSuggestions)
                                .executes(ctx -> presetRemove(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(literal("reset")
                        .then(argument("item", IdentifierArgument.id())
                                .suggests(recordedSuggestions)
                                .executes(ctx -> reset(ctx, IdentifierArgument.getId(ctx, "item").toString(), TargetKey.MIN_COUNT))
                                .then(argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                        .suggests(recordedCountSuggestions)
                                        .executes(ctx -> reset(ctx, IdentifierArgument.getId(ctx, "item").toString(),
                                                IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(literal("resetall").executes(MilestoneCommands::resetAll))
                .then(literal("undo")
                        .executes(MilestoneCommands::undo)
                        .then(literal("list").executes(MilestoneCommands::undoList))
                        .then(argument("generation", IntegerArgumentType.integer(1, UndoLogic.UNDO_HISTORY_DEPTH))
                                .executes(ctx -> undoGeneration(ctx,
                                        IntegerArgumentType.getInteger(ctx, "generation")))))
                .then(literal("firsts")
                        .executes(ctx -> firstsList(ctx, null))
                        .then(argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> firstsList(ctx, IntegerArgumentType.getInteger(ctx, "page"))))
                        .then(literal("top")
                                .executes(ctx -> firstsTop(ctx, null))
                                .then(argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> firstsTop(ctx, IntegerArgumentType.getInteger(ctx, "page")))))
                        .then(literal("close")
                                .requires(MilestoneCommands::canUseRankingsForTree)
                                .executes(ctx -> firstsClose(ctx, null))
                                .then(argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> firstsClose(ctx, IntegerArgumentType.getInteger(ctx, "page")))))
                        // F-150-38 (N167): history rows are personal records (Gate.LOOKUP), but the
                        // parent `firsts` entry stays public (D-150-93). Do NOT inherit the reset
                        // requirement from the sibling reset/resettag/resetall branches.
                        .then(literal("history")
                                .requires(MilestoneCommands::canUseRankingsForTree)
                                .then(argument("item", IdentifierArgument.id())
                                        .suggests(worldFirstSuggestions)
                                        .executes(ctx -> firstsHistory(ctx,
                                                IdentifierArgument.getId(ctx, "item").toString(),
                                                TargetKey.MIN_COUNT, null))
                                        .then(argument("count", IntegerArgumentType.integer(
                                                TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                                .suggests(worldFirstCountSuggestions)
                                                .executes(ctx -> firstsHistory(ctx,
                                                        IdentifierArgument.getId(ctx, "item").toString(),
                                                        IntegerArgumentType.getInteger(ctx, "count"), null))
                                                .then(argument("page", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> firstsHistory(ctx,
                                                                IdentifierArgument.getId(ctx, "item").toString(),
                                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                                IntegerArgumentType.getInteger(ctx, "page")))))
                                        .then(argument("page", IntegerArgumentType.integer(1))
                                                .executes(ctx -> firstsHistory(ctx,
                                                        IdentifierArgument.getId(ctx, "item").toString(),
                                                        TargetKey.MIN_COUNT,
                                                        IntegerArgumentType.getInteger(ctx, "page"))))))
                        .then(literal("unclaimed")
                                .executes(ctx -> firstsUnclaimed(ctx, null, null))
                                .then(literal("scope")
                                        .then(argument("scope", StringArgumentType.word())
                                                .suggests(unclaimedScopeSuggestions)
                                                .executes(ctx -> firstsUnclaimed(ctx,
                                                        StringArgumentType.getString(ctx, "scope"), null))
                                                .then(argument("page", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> firstsUnclaimed(ctx,
                                                                StringArgumentType.getString(ctx, "scope"),
                                                                IntegerArgumentType.getInteger(ctx, "page"))))))
                                .then(argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> firstsUnclaimed(ctx, null,
                                                IntegerArgumentType.getInteger(ctx, "page")))))
                        .then(literal("reset")
                                .requires(firstsResetRequirement)
                                .then(argument("item", IdentifierArgument.id())
                                        .suggests(worldFirstSuggestions)
                                        .executes(ctx -> firstsReset(ctx, IdentifierArgument.getId(ctx, "item").toString(), TargetKey.MIN_COUNT))
                                        .then(argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                                .suggests(worldFirstCountSuggestions)
                                                .executes(ctx -> firstsReset(ctx, IdentifierArgument.getId(ctx, "item").toString(),
                                                        IntegerArgumentType.getInteger(ctx, "count"))))))
                        .then(literal("resettag")
                                .requires(firstsResetRequirement)
                                .then(argument("tag", tagArgumentType())
                                        .suggests(worldFirstTagSuggestions)
                                        .executes(ctx -> firstsReset(ctx, tagArg(ctx), TargetKey.MIN_COUNT))
                                        .then(argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                                .suggests(worldFirstTagCountSuggestions)
                                                .executes(ctx -> firstsReset(ctx, tagArg(ctx),
                                                        IntegerArgumentType.getInteger(ctx, "count"))))))
                        .then(literal("resetall")
                                .requires(firstsResetRequirement)
                                .executes(MilestoneCommands::firstsResetAll)))
                // F-150-38 (N167): same rules as `firsts history`, with a tag argument that is
                // stored with its leading '#'. New top-level entry (Gate.LOOKUP).
                .then(literal("firstshistorytag")
                        .requires(MilestoneCommands::canUseRankingsForTree)
                        .then(argument("tag", tagArgumentType())
                                .suggests(worldFirstTagSuggestions)
                                .executes(ctx -> firstsHistory(ctx, tagArg(ctx), TargetKey.MIN_COUNT, null))
                                .then(argument("count", IntegerArgumentType.integer(
                                        TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                        .suggests(worldFirstTagCountSuggestions)
                                        .executes(ctx -> firstsHistory(ctx, tagArg(ctx),
                                                IntegerArgumentType.getInteger(ctx, "count"), null))
                                        .then(argument("page", IntegerArgumentType.integer(1))
                                                .executes(ctx -> firstsHistory(ctx, tagArg(ctx),
                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                        IntegerArgumentType.getInteger(ctx, "page")))))
                                .then(argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> firstsHistory(ctx, tagArg(ctx), TargetKey.MIN_COUNT,
                                                IntegerArgumentType.getInteger(ctx, "page"))))))
                .then(literal("presettop")
                        .then(argument("preset", StringArgumentType.word())
                                .suggests(presetSuggestions)
                                .executes(ctx -> presettop(ctx, StringArgumentType.getString(ctx, "preset"), null))
                                .then(axisBranch(rankingAxisSuggestions,
                                        (ctx, axis, days) -> axisRanking(ctx, axis, days, "presettop",
                                                StringArgumentType.getString(ctx, "preset"), 1)))
                                .then(argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> presettop(ctx, StringArgumentType.getString(ctx, "preset"),
                                                IntegerArgumentType.getInteger(ctx, "page")))
                                        .then(axisBranch(rankingAxisSuggestions,
                                                (ctx, axis, days) -> axisRanking(ctx, axis, days, "presettop",
                                                        StringArgumentType.getString(ctx, "preset"), 1))))))
                .then(literal("info")
                        .then(argument("item", IdentifierArgument.id())
                                .suggests(infoItemSuggestions)
                                .executes(ctx -> infoItem(ctx, IdentifierArgument.getId(ctx, "item").toString(), TargetKey.MIN_COUNT, false))
                                .then(argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                        .suggests(infoItemCountSuggestions)
                                        .executes(ctx -> infoItem(ctx, IdentifierArgument.getId(ctx, "item").toString(),
                                                IntegerArgumentType.getInteger(ctx, "count"), false))
                                        .then(literal("preview")
                                                .executes(ctx -> infoItem(ctx, IdentifierArgument.getId(ctx, "item").toString(),
                                                        IntegerArgumentType.getInteger(ctx, "count"), true))))
                                .then(literal("preview")
                                        .executes(ctx -> infoItem(ctx, IdentifierArgument.getId(ctx, "item").toString(),
                                                TargetKey.MIN_COUNT, true))))
                        .then(literal("hand")
                                .executes(ctx -> infoHand(ctx, false))
                                .then(literal("preview").executes(ctx -> infoHand(ctx, true)))))
                .then(literal("export").executes(MilestoneCommands::exportSelf))
                .then(literal("exporthtml").executes(MilestoneCommands::exportHtmlSelf))
                .then(literal("exportcsv").executes(MilestoneCommands::exportCsvSelf))
                .then(literal("import")
                        .then(argument("fileName", StringArgumentType.word())
                                .suggests(exportFileSuggestions)
                                .executes(ctx -> importSelf(ctx, StringArgumentType.getString(ctx, "fileName"), false))
                                .then(literal("dryrun")
                                        .executes(ctx -> importSelf(ctx,
                                                StringArgumentType.getString(ctx, "fileName"), true)))))
                .then(literal("exportall")
                        .requires(exportAllRequirement)
                        .executes(MilestoneCommands::exportAll))
                .then(literal("exporthtmlall")
                        .requires(exportAllRequirement)
                        .executes(MilestoneCommands::exportHtmlAll))
                .then(literal("exportcsvall")
                        .requires(exportAllRequirement)
                        .executes(MilestoneCommands::exportCsvAll))
                .then(literal("importall")
                        .requires(importAllRequirement)
                        .then(argument("fileName", StringArgumentType.word())
                                .suggests(exportFileSuggestions)
                                .executes(ctx -> importAll(ctx, StringArgumentType.getString(ctx, "fileName"), false))
                                .then(literal("dryrun")
                                        .executes(ctx -> importAll(ctx,
                                                StringArgumentType.getString(ctx, "fileName"), true)))))
                .then(literal("mutebroadcast").executes(MilestoneCommands::muteBroadcast))
                .then(literal("muteprogress").executes(MilestoneCommands::muteProgress))
                .then(literal("progressdest")
                        .executes(MilestoneCommands::progressDestQuery)
                        .then(literal("chat").executes(ctx -> progressDestSet(ctx, NotificationDestinationLogic.Choice.CHAT)))
                        .then(literal("actionbar").executes(ctx -> progressDestSet(ctx, NotificationDestinationLogic.Choice.ACTION_BAR)))
                        .then(literal("both").executes(ctx -> progressDestSet(ctx, NotificationDestinationLogic.Choice.BOTH)))
                        .then(literal("off").executes(ctx -> progressDestSet(ctx, NotificationDestinationLogic.Choice.OFF)))
                        .then(literal("server").executes(ctx -> progressDestSet(ctx, NotificationDestinationLogic.Choice.FOLLOW_SERVER))))
                .then(literal("discordoptout").executes(MilestoneCommands::discordOptOut))
                .then(literal("privacy").executes(MilestoneCommands::privacy))
                .then(literal("discordtest")
                        .requires(discordTestRequirement)
                        .executes(ctx -> discordTest(ctx, DiscordRouting.Destination.MAIN))
                        .then(literal("worldfirst")
                                .executes(ctx -> discordTest(ctx, DiscordRouting.Destination.WORLD_FIRST))))
                .then(literal("lookup")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(lookupPlayerSuggestions)
                                .executes(ctx -> lookup(ctx, StringArgumentType.getString(ctx, "player"), null))
                                .then(argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> lookup(ctx, StringArgumentType.getString(ctx, "player"),
                                                IntegerArgumentType.getInteger(ctx, "page"))))))
                // PR-2 (F-150-11/33/12/18/24/39/20): read-only analytics, all opt-in literals.
                .then(literal("compare")
                        .requires(MilestoneCommands::canUseRankingsForTree)
                        .then(argument("player", StringArgumentType.word())
                                .suggests(lookupPlayerSuggestions)
                                .executes(ctx -> compare(ctx, StringArgumentType.getString(ctx, "player"), null, null))
                                .then(compareBranchNode("missing"))
                                .then(compareBranchNode("route"))
                                .then(compareBranchNode("rival"))
                                .then(argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> compare(ctx, StringArgumentType.getString(ctx, "player"),
                                                null, IntegerArgumentType.getInteger(ctx, "page"))))))
                .then(literal("timeline")
                        .then(argument("item", itemIdentifierArgumentType())
                                .executes(ctx -> timeline(ctx, itemIdentifierArg(ctx, "item"),
                                        TargetKey.MIN_COUNT, null, false))
                                .then(argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                        .executes(ctx -> timeline(ctx, itemIdentifierArg(ctx, "item"),
                                                IntegerArgumentType.getInteger(ctx, "count"), null, false))
                                        .then(argument("page", IntegerArgumentType.integer(1))
                                                .executes(ctx -> timeline(ctx, itemIdentifierArg(ctx, "item"),
                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                        IntegerArgumentType.getInteger(ctx, "page"), false))))
                                .then(argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> timeline(ctx, itemIdentifierArg(ctx, "item"),
                                                TargetKey.MIN_COUNT,
                                                IntegerArgumentType.getInteger(ctx, "page"), false)))))
                .then(literal("timelinetag")
                        .then(argument("tag", tagArgumentType())
                                .suggests(allTagSuggestions)
                                .executes(ctx -> timeline(ctx, tagArg(ctx), TargetKey.MIN_COUNT, null, true))
                                .then(argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                        .executes(ctx -> timeline(ctx, tagArg(ctx),
                                                IntegerArgumentType.getInteger(ctx, "count"), null, true))
                                        .then(argument("page", IntegerArgumentType.integer(1))
                                                .executes(ctx -> timeline(ctx, tagArg(ctx),
                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                        IntegerArgumentType.getInteger(ctx, "page"), true))))
                                .then(argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> timeline(ctx, tagArg(ctx), TargetKey.MIN_COUNT,
                                                IntegerArgumentType.getInteger(ctx, "page"), true)))))
                .then(literal("session")
                        .executes(ctx -> session(ctx, 24, null, false))
                        .then(literal("summary").executes(ctx -> session(ctx, 24, null, true)))
                        .then(literal("page")
                                .then(argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> session(ctx, 24,
                                                IntegerArgumentType.getInteger(ctx, "page"), false))))
                        .then(literal("hours")
                                .then(argument("hours", StringArgumentType.word())
                                        .suggests(sessionHoursSuggestions)
                                        .executes(ctx -> session(ctx, StringArgumentType.getString(ctx, "hours"),
                                                null, false))
                                        .then(literal("page")
                                                .then(argument("page", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> session(ctx,
                                                                StringArgumentType.getString(ctx, "hours"),
                                                                IntegerArgumentType.getInteger(ctx, "page"), false)))))))
                .then(literal("modstats")
                        .executes(ctx -> modstats(ctx, null))
                        .then(argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> modstats(ctx, IntegerArgumentType.getInteger(ctx, "page")))))
                .then(literal("presetmatrix")
                        .executes(ctx -> presetMatrix(ctx, null))
                        .then(argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> presetMatrix(ctx, IntegerArgumentType.getInteger(ctx, "page")))))
                .then(literal("presetstats")
                        .executes(ctx -> presetStats(ctx, null))
                        .then(argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> presetStats(ctx, IntegerArgumentType.getInteger(ctx, "page"))))
                        .then(literal("predict")
                                .then(argument("preset", StringArgumentType.word())
                                        .suggests(presetSuggestions)
                                        .executes(ctx -> presetStatsPredict(ctx,
                                                StringArgumentType.getString(ctx, "preset"))))))
                .then(literal("top")
                        .executes(MilestoneCommands::top)
                        .then(axisBranch(rankingAxisSuggestions,
                                (ctx, axis, days) -> axisRanking(ctx, axis, days, "top", null, 1)))
                        .then(argument("item", IdentifierArgument.id())
                                .suggests(topItemSuggestions)
                                .executes(ctx -> topItem(ctx, itemIdentifierArg(ctx, "item"), TargetKey.MIN_COUNT, false))
                                .then(axisBranch(rankingAxisSuggestions,
                                        (ctx, axis, days) -> axisRanking(ctx, axis, days, "top",
                                                itemIdentifierArg(ctx, "item"), TargetKey.MIN_COUNT)))
                                .then(literal("gaps")
                                        .executes(ctx -> topItem(ctx, itemIdentifierArg(ctx, "item"),
                                                TargetKey.MIN_COUNT, true)))
                                .then(argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                        .suggests(topItemCountSuggestions)
                                        .executes(ctx -> topItem(ctx, itemIdentifierArg(ctx, "item"),
                                                IntegerArgumentType.getInteger(ctx, "count"), false))
                                        .then(axisBranch(rankingAxisSuggestions,
                                                (ctx, axis, days) -> axisRanking(ctx, axis, days, "top",
                                                        itemIdentifierArg(ctx, "item"),
                                                        IntegerArgumentType.getInteger(ctx, "count"))))
                                        .then(literal("gaps")
                                                .executes(ctx -> topItem(ctx, itemIdentifierArg(ctx, "item"),
                                                        IntegerArgumentType.getInteger(ctx, "count"), true))))))
                .then(literal("recent")
                        .executes(ctx -> recent(ctx, MilestoneRankings.RECENT_DEFAULT))
                        .then(argument("n", IntegerArgumentType.integer(MilestoneRankings.RECENT_MIN, MilestoneRankings.RECENT_MAX))
                                .executes(ctx -> recent(ctx, IntegerArgumentType.getInteger(ctx, "n")))))
                .then(literal("stats")
                        .executes(MilestoneCommands::stats)
                        .then(axisBranch(rankingAxisSuggestions,
                                (ctx, axis, days) -> axisRanking(ctx, axis, days, "stats", null, 1)))
                        .then(literal("page")
                                .then(argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> axisRanking(ctx, "all", null, "stats", null, 1))))
                        .then(literal("pace")
                                .executes(ctx -> statsPace(ctx, null, null))
                                .then(argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> statsPace(ctx, null,
                                                IntegerArgumentType.getInteger(ctx, "page"))))
                                .then(literal("scope")
                                        .then(argument("scope", StringArgumentType.word())
                                                .suggests(paceScopeSuggestions)
                                                .executes(ctx -> statsPace(ctx,
                                                        StringArgumentType.getString(ctx, "scope"), null)))))
                        .then(argument("item", IdentifierArgument.id())
                                .suggests(topItemSuggestions)
                                .executes(ctx -> statsItem(ctx, itemIdentifierArg(ctx, "item"), TargetKey.MIN_COUNT))
                                .then(argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                        .suggests(topItemCountSuggestions)
                                        .executes(ctx -> statsItem(ctx, itemIdentifierArg(ctx, "item"),
                                                IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(literal("statstag")
                        .then(argument("tag", tagArgumentType())
                                .suggests(topTagSuggestions)
                                .executes(ctx -> statsItem(ctx, tagArg(ctx), TargetKey.MIN_COUNT))
                                .then(argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                        .suggests(topTagCountSuggestions)
                                        .executes(ctx -> statsItem(ctx, tagArg(ctx),
                                                IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(literal("whohas")
                        .then(argument("item", IdentifierArgument.id())
                                .suggests(topItemSuggestions)
                                .executes(ctx -> whohas(ctx, IdentifierArgument.getId(ctx, "item").toString(),
                                        TargetKey.MIN_COUNT, null))
                                .then(argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                        .suggests(topItemCountSuggestions)
                                        .executes(ctx -> whohas(ctx, IdentifierArgument.getId(ctx, "item").toString(),
                                                IntegerArgumentType.getInteger(ctx, "count"), null))
                                        .then(argument("page", IntegerArgumentType.integer(1))
                                                .executes(ctx -> whohas(ctx, IdentifierArgument.getId(ctx, "item").toString(),
                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                        IntegerArgumentType.getInteger(ctx, "page")))))))
                .then(literal("whohastag")
                        .then(argument("tag", tagArgumentType())
                                .suggests(topTagSuggestions)
                                .executes(ctx -> whohas(ctx, tagArg(ctx), TargetKey.MIN_COUNT, null))
                                .then(argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                        .suggests(topTagCountSuggestions)
                                        .executes(ctx -> whohas(ctx, tagArg(ctx),
                                                IntegerArgumentType.getInteger(ctx, "count"), null))
                                        .then(argument("page", IntegerArgumentType.integer(1))
                                                .executes(ctx -> whohas(ctx, tagArg(ctx),
                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                        IntegerArgumentType.getInteger(ctx, "page")))))))
                .then(literal("prunedata")
                        .requires(pruneDataRequirement)
                        .then(argument("days", IntegerArgumentType.integer(PruneDataLogic.MIN_DAYS, PruneDataLogic.MAX_DAYS))
                                .executes(ctx -> pruneData(ctx, IntegerArgumentType.getInteger(ctx, "days"), false))
                                .then(literal("confirm")
                                        .executes(ctx -> pruneData(ctx, IntegerArgumentType.getInteger(ctx, "days"), true)))))
                .then(literal("renameitem")
                        .requires(renameItemRequirement)
                        .then(argument("oldId", IdentifierArgument.id())
                                .suggests(renameOldItemSuggestions)
                                .then(argument("newId", IdentifierArgument.id())
                                        .suggests(renameNewItemSuggestions)
                                        .executes(ctx -> renameItem(ctx,
                                                IdentifierArgument.getId(ctx, "oldId").toString(),
                                                IdentifierArgument.getId(ctx, "newId").toString(),
                                                false))
                                        .then(literal("confirm")
                                                .executes(ctx -> renameItem(ctx,
                                                        IdentifierArgument.getId(ctx, "oldId").toString(),
                                                        IdentifierArgument.getId(ctx, "newId").toString(),
                                                        true))))))
                .then(literal("renametag")
                        .requires(renameItemRequirement)
                        .then(argument("tags", StringArgumentType.greedyString())
                                .suggests(renameTagSuggestions)
                                .executes(MilestoneCommands::renameTag)))
                // PR-3 write operations: opt-in literals; existing syntax untouched.
                .then(literal("copytargets")
                        .requires(MilestoneCommands::canUseRankingsForTree)
                        .then(argument("player", StringArgumentType.word())
                                .suggests(lookupPlayerSuggestions)
                                .executes(ctx -> copyTargets(ctx, StringArgumentType.getString(ctx, "player"), false))
                                .then(literal("confirm")
                                        .executes(ctx -> copyTargets(ctx, StringArgumentType.getString(ctx, "player"), true)))))
                .then(literal("sharetargets")
                        .then(literal("offer")
                                .requires(MilestoneCommands::canUseRankingsForTree)
                                .then(argument("player", StringArgumentType.word())
                                        .suggests(lookupPlayerSuggestions)
                                        .executes(ctx -> shareTargetsOffer(ctx, StringArgumentType.getString(ctx, "player")))))
                        .then(literal("accept")
                                .then(argument("code", StringArgumentType.string())
                                        .executes(ctx -> shareTargetsAccept(ctx, StringArgumentType.getString(ctx, "code"), false))
                                        .then(literal("confirm")
                                                .executes(ctx -> shareTargetsAccept(ctx,
                                                        StringArgumentType.getString(ctx, "code"), true)))))
                        .then(literal("revoke").executes(MilestoneCommands::shareTargetsRevoke)))
                .then(literal("addsteps")
                        .then(literal("tag")
                                .then(argument("tag", tagArgumentType())
                                        .suggests(allTagSuggestions)
                                        .then(argument("steps", StringArgumentType.string())
                                                .executes(ctx -> addSteps(ctx, tagArg(ctx),
                                                        StringArgumentType.getString(ctx, "steps"), false, false))
                                                .then(literal("confirm")
                                                        .executes(ctx -> addSteps(ctx, tagArg(ctx),
                                                                StringArgumentType.getString(ctx, "steps"), true, false))))))
                        .then(argument("item", itemIdentifierArgumentType())
                                .then(argument("steps", StringArgumentType.string())
                                        .executes(ctx -> addSteps(ctx, itemIdentifierArg(ctx, "item"),
                                                StringArgumentType.getString(ctx, "steps"), false, false))
                                        .then(literal("confirm")
                                                .executes(ctx -> addSteps(ctx, itemIdentifierArg(ctx, "item"),
                                                        StringArgumentType.getString(ctx, "steps"), true, false))))))
                .then(literal("bulk")
                        .then(literal("preview").executes(MilestoneCommands::bulkPreview))
                        .then(literal("addsteps")
                                .then(literal("tag")
                                        .then(argument("tag", tagArgumentType())
                                                .suggests(allTagSuggestions)
                                                .then(argument("steps", StringArgumentType.string())
                                                        .executes(ctx -> addSteps(ctx, tagArg(ctx),
                                                                StringArgumentType.getString(ctx, "steps"), false, true))
                                                        .then(literal("confirm")
                                                                .executes(ctx -> addSteps(ctx, tagArg(ctx),
                                                                        StringArgumentType.getString(ctx, "steps"), true, true))))))
                                .then(argument("item", itemIdentifierArgumentType())
                                        .then(argument("steps", StringArgumentType.string())
                                                .executes(ctx -> addSteps(ctx, itemIdentifierArg(ctx, "item"),
                                                        StringArgumentType.getString(ctx, "steps"), false, true))
                                                .then(literal("confirm")
                                                        .executes(ctx -> addSteps(ctx, itemIdentifierArg(ctx, "item"),
                                                                StringArgumentType.getString(ctx, "steps"), true, true))))))
                        .then(literal("expandtag")
                                .then(argument("tag", tagArgumentType())
                                        .suggests(allTagSuggestions)
                                        .executes(ctx -> bulkExpandTag(ctx, tagArg(ctx), false))
                                        .then(literal("confirm")
                                                .executes(ctx -> bulkExpandTag(ctx, tagArg(ctx), true)))))
                        .then(literal("cleanup")
                                .executes(ctx -> bulkCleanup(ctx, false))
                                .then(literal("confirm").executes(ctx -> bulkCleanup(ctx, true)))))
                .then(literal("pinned").executes(MilestoneCommands::pinned))
                .then(literal("admin")
                        .requires(adminRequirementWithNode)
                        .then(literal("players")
                                .executes(ctx -> adminPlayers(ctx, null))
                                .then(argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> adminPlayers(ctx, IntegerArgumentType.getInteger(ctx, "page")))))
                        .then(argument("player", StringArgumentType.word())
                                .suggests(adminPlayerSuggestions)
                                .then(literal("list")
                                        .executes(ctx -> adminList(ctx, StringArgumentType.getString(ctx, "player"), null))
                                        .then(argument("page", IntegerArgumentType.integer(1))
                                                .executes(ctx -> adminList(ctx, StringArgumentType.getString(ctx, "player"),
                                                        IntegerArgumentType.getInteger(ctx, "page")))))
                                .then(literal("targets")
                                        .executes(ctx -> adminTargets(ctx, StringArgumentType.getString(ctx, "player"), null))
                                        .then(argument("page", IntegerArgumentType.integer(1))
                                                .executes(ctx -> adminTargets(ctx, StringArgumentType.getString(ctx, "player"),
                                                        IntegerArgumentType.getInteger(ctx, "page")))))
                                .then(literal("add")
                                        .then(argument("item", itemArg.get())
                                                .executes(ctx -> adminAdd(ctx, StringArgumentType.getString(ctx, "player"),
                                                        itemId(ctx), TargetKey.MIN_COUNT))
                                                .then(argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                                        .executes(ctx -> adminAdd(ctx, StringArgumentType.getString(ctx, "player"),
                                                                itemId(ctx), IntegerArgumentType.getInteger(ctx, "count"))))))
                                .then(literal("remove")
                                        .then(argument("item", IdentifierArgument.id())
                                                .suggests(adminPersonalTargetSuggestions)
                                                .executes(ctx -> adminRemove(ctx, StringArgumentType.getString(ctx, "player"),
                                                        IdentifierArgument.getId(ctx, "item").toString(), TargetKey.MIN_COUNT))
                                                .then(argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                                        .suggests(adminPersonalTargetCountSuggestions)
                                                        .executes(ctx -> adminRemove(ctx, StringArgumentType.getString(ctx, "player"),
                                                                IdentifierArgument.getId(ctx, "item").toString(),
                                                                IntegerArgumentType.getInteger(ctx, "count"))))))
                                .then(literal("preset")
                                        .then(argument("name", StringArgumentType.word())
                                                .suggests(presetSuggestions)
                                                .executes(ctx -> adminPreset(ctx, StringArgumentType.getString(ctx, "player"),
                                                        StringArgumentType.getString(ctx, "name")))))
                                .then(literal("copyfrom")
                                        .then(argument("source", StringArgumentType.word())
                                                .suggests(lookupPlayerSuggestions)
                                                .executes(ctx -> adminCopyFrom(ctx, StringArgumentType.getString(ctx, "player"),
                                                        StringArgumentType.getString(ctx, "source"), false))
                                                .then(literal("confirm")
                                                        .executes(ctx -> adminCopyFrom(ctx, StringArgumentType.getString(ctx, "player"),
                                                                StringArgumentType.getString(ctx, "source"), true)))))
                                .then(literal("addtag")
                                        .then(argument("tag", tagArgumentType())
                                                .suggests(allTagSuggestions)
                                                .executes(ctx -> adminAdd(ctx, StringArgumentType.getString(ctx, "player"),
                                                        tagArg(ctx), TargetKey.MIN_COUNT))
                                                .then(argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                                        .executes(ctx -> adminAdd(ctx, StringArgumentType.getString(ctx, "player"),
                                                                tagArg(ctx), IntegerArgumentType.getInteger(ctx, "count"))))))
                                .then(literal("removetag")
                                        .then(argument("tag", tagArgumentType())
                                                .suggests(allTagSuggestions)
                                                .executes(ctx -> adminRemove(ctx, StringArgumentType.getString(ctx, "player"),
                                                        tagArg(ctx), TargetKey.MIN_COUNT))
                                                .then(argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                                        .executes(ctx -> adminRemove(ctx, StringArgumentType.getString(ctx, "player"),
                                                                tagArg(ctx), IntegerArgumentType.getInteger(ctx, "count"))))))
                                .then(literal("granttag")
                                        .then(argument("tag", tagArgumentType())
                                                .suggests(allTagSuggestions)
                                                .executes(ctx -> adminGrantTag(ctx, StringArgumentType.getString(ctx, "player"),
                                                        tagArg(ctx), TargetKey.MIN_COUNT))
                                                .then(argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                                        .executes(ctx -> adminGrantTag(ctx, StringArgumentType.getString(ctx, "player"),
                                                                tagArg(ctx), IntegerArgumentType.getInteger(ctx, "count"))))))
                                .then(literal("grant")
                                        .then(argument("item", itemArg.get())
                                                .executes(ctx -> adminGrant(ctx, StringArgumentType.getString(ctx, "player"),
                                                        itemId(ctx), TargetKey.MIN_COUNT))
                                                .then(argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                                        .executes(ctx -> adminGrant(ctx, StringArgumentType.getString(ctx, "player"),
                                                                itemId(ctx), IntegerArgumentType.getInteger(ctx, "count"))))))
                                .then(literal("reset")
                                        .then(argument("item", IdentifierArgument.id())
                                                .suggests(adminRecordedSuggestions)
                                                .executes(ctx -> adminReset(ctx, StringArgumentType.getString(ctx, "player"),
                                                        IdentifierArgument.getId(ctx, "item").toString(), TargetKey.MIN_COUNT))
                                                .then(argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                                        .suggests(adminRecordedCountSuggestions)
                                                        .executes(ctx -> adminReset(ctx, StringArgumentType.getString(ctx, "player"),
                                                                IdentifierArgument.getId(ctx, "item").toString(),
                                                                IntegerArgumentType.getInteger(ctx, "count"))))))
                                .then(literal("resettag")
                                        .then(argument("tag", tagArgumentType())
                                                .suggests(adminRecordedTagSuggestions)
                                                .executes(ctx -> adminReset(ctx, StringArgumentType.getString(ctx, "player"),
                                                        tagArg(ctx), TargetKey.MIN_COUNT))
                                                .then(argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                                        .suggests(adminRecordedTagCountSuggestions)
                                                        .executes(ctx -> adminReset(ctx, StringArgumentType.getString(ctx, "player"),
                                                                tagArg(ctx), IntegerArgumentType.getInteger(ctx, "count"))))))
                                .then(literal("resetall").executes(ctx -> adminResetAll(ctx, StringArgumentType.getString(ctx, "player"))))
                                .then(literal("purge").executes(ctx -> adminPurge(ctx, StringArgumentType.getString(ctx, "player"))))))
                .then(literal("settings")
                        .executes(MilestoneCommands::settings)
                        .then(argument("toggle", StringArgumentType.word())
                                .suggests(settingToggleSuggestions)
                                .then(literal("on").executes(ctx -> settingsChange(ctx, true)))
                                .then(literal("off").executes(ctx -> settingsChange(ctx, false)))))
                .then(literal("randomtarget")
                        .executes(ctx -> randomTarget(ctx, null))
                        .then(literal("kind")
                                .then(argument("kind", StringArgumentType.word())
                                        .suggests(randomTargetKindSuggestions)
                                        .executes(ctx -> randomTarget(ctx,
                                                StringArgumentType.getString(ctx, "kind"))))))
                .then(literal("trends")
                        .executes(ctx -> trends(ctx, null, null))
                        .then(literal("days")
                                .then(argument("days", StringArgumentType.word())
                                        .suggests(trendDaysSuggestions)
                                        .executes(ctx -> trends(ctx,
                                                StringArgumentType.getString(ctx, "days"), null))
                                        .then(literal("page")
                                                .then(argument("page", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> trends(ctx,
                                                                StringArgumentType.getString(ctx, "days"),
                                                                IntegerArgumentType.getInteger(ctx, "page")))))))
                        .then(literal("page")
                                .then(argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> trends(ctx, null,
                                                IntegerArgumentType.getInteger(ctx, "page"))))))
                .then(literal("recap")
                        .executes(ctx -> recap(ctx, null))
                        .then(literal("kind")
                                .then(argument("kind", StringArgumentType.word())
                                        .suggests(recapKindSuggestions)
                                        .executes(ctx -> recap(ctx,
                                                StringArgumentType.getString(ctx, "kind"))))))
                .then(literal("help")
                        .executes(ctx -> helpList(ctx, dispatcher))
                        .then(argument("subcommand", StringArgumentType.word())
                                .suggests(helpSubcommandSuggestions)
                                .executes(ctx -> helpCommand(ctx, dispatcher, StringArgumentType.getString(ctx, "subcommand")))));
    }

    // ------------------------------------------------------------- helpers

    private static ServerPlayer player(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return ctx.getSource().getPlayerOrException();
    }

    @Nullable
    private static MilestoneData.PlayerData dataOrNull(ServerPlayer player) {
        return MilestoneData.get(player.level().getServer()).playerOrNull(player.getUUID());
    }

    private static String itemId(CommandContext<CommandSourceStack> ctx) {
        Item item = ItemArgument.getItem(ctx, "item").item().value();
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static int send(CommandContext<CommandSourceStack> ctx, Component msg) {
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, Component msg) {
        ctx.getSource().sendFailure(msg);
        return 0;
    }

    private static int respond(CommandContext<CommandSourceStack> ctx, MilestoneService.ActionResult result) {
        return result.success() ? send(ctx, result.message()) : fail(ctx, result.message());
    }

    private static void sendPageFooter(CommandSourceStack source, Pagination.PageSlice<?> slice, String commandPrefix) {
        MutableComponent footer = ServerLang.tr(source, "message.itemmilestonelog.page_footer",
                slice.page(), slice.totalPages());
        if (slice.hasPrevious()) {
            footer.append(Component.literal(" "));
            footer.append(clickablePageButton(source, "message.itemmilestonelog.page_prev",
                    Pagination.pageCommand(commandPrefix, slice.page() - 1)));
        }
        if (slice.hasNext()) {
            footer.append(Component.literal(" "));
            footer.append(clickablePageButton(source, "message.itemmilestonelog.page_next",
                    Pagination.pageCommand(commandPrefix, slice.page() + 1)));
        }
        source.sendSuccess(() -> footer, false);
    }

    private static MutableComponent clickablePageButton(CommandSourceStack source, String langKey, String command) {
        return ServerLang.tr(source, langKey)
                .withStyle(style -> style
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand(command)));
    }

    private static ClickEvent suggestCommandEvent(String command) {
        return new ClickEvent.SuggestCommand(command);
    }

    /** Package-private test helper for inspecting the SUGGEST_COMMAND payload across MC versions. */
    @Nullable
    static String suggestCommandValue(@Nullable ClickEvent event) {
        return event instanceof ClickEvent.SuggestCommand suggest ? suggest.command() : null;
    }

    static MutableComponent clickableHelpCommand(CommandHelp.Entry entry) {
        return Component.literal(entry.displayCommand())
                .withStyle(style -> style.withClickEvent(suggestCommandEvent(entry.suggestCommand())));
    }

    // ------------------------------------------------------------ commands

    private static int summary(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        MilestoneData.PlayerData pd = dataOrNull(player);
        Set<TargetKey> targets = Tracker.effectiveTargets(pd);
        Map<TargetKey, MilestoneRecord> records = pd != null ? pd.records : Map.of();
        int done = (int) targets.stream().filter(records::containsKey).count();
        send(ctx, ServerLang.tr(player, "message.itemmilestonelog.summary",
                Playtime.formatComponent(player, Playtime.currentTicks(player)), done, targets.size()));
        if (Tracker.recordingPaused(player)) {
            send(ctx, ServerLang.tr(player, "message.itemmilestonelog.summary_creative_paused"));
        }
        return 1;
    }

    private static int playtime(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        return send(ctx, ServerLang.tr(player, "message.itemmilestonelog.playtime",
                Playtime.formatComponent(player, Playtime.currentTicks(player))));
    }

    private static int add(CommandContext<CommandSourceStack> ctx, String itemId, int count) throws CommandSyntaxException {
        return respond(ctx, MilestoneService.addTarget(player(ctx), itemId, count));
    }

    private static int addHand(CommandContext<CommandSourceStack> ctx, int count) throws CommandSyntaxException {
        return respond(ctx, MilestoneService.addHandTarget(player(ctx), count));
    }

    private static int remove(CommandContext<CommandSourceStack> ctx, String itemId, int count) throws CommandSyntaxException {
        return respond(ctx, MilestoneService.removeTarget(player(ctx), itemId, count));
    }

    private static int removeAll(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return respondWithUndoHint(ctx, MilestoneService.removeAllTargets(player(ctx)));
    }

    // ------------------------------------------------- F-150-1 / F-150-2 filtering foundation

    /** Parsed (and validated) filter criteria shared by the targets/list handlers. */
    record FilterCriteria(List<String> tokens,
                                  @Nullable TargetFilterModel.Kind kind,
                                  @Nullable String rawQuery,
                                  @Nullable String kindArg) {
        boolean active() {
            return rawQuery != null || kindArg != null;
        }

        Set<TargetFilterModel.TargetView> defaults() {
            Set<TargetFilterModel.TargetView> views = new LinkedHashSet<>();
            for (TargetKey key : Tracker.defaultTargets()) {
                views.add(new TargetFilterModel.TargetView(key.itemId(), key.count()));
            }
            return views;
        }

        String targetSuffix() {
            if (!active()) {
                return "";
            }
            StringBuilder suffix = new StringBuilder();
            if (rawQuery != null) {
                suffix.append("filter \"").append(Pagination.escapeQuotedArgument(rawQuery)).append('"');
            }
            if (kindArg != null) {
                if (suffix.length() > 0) {
                    suffix.append(' ');
                }
                suffix.append("kind ").append(kindArg);
            }
            return suffix.toString();
        }

        String listSuffix() {
            return rawQuery == null ? "" : "filter \"" + Pagination.escapeQuotedArgument(rawQuery) + '"';
        }
    }

    /** Returns null after sending the appropriate failure message when the query/kind is invalid. */
    @Nullable
    private static FilterCriteria parseCriteria(CommandContext<CommandSourceStack> ctx,
                                                ServerPlayer player,
                                                @Nullable String rawQuery,
                                                @Nullable String kindArg) {
        List<String> tokens = List.of();
        if (rawQuery != null) {
            try {
                TargetFilterModel.validateQuery(rawQuery);
                tokens = TargetFilterModel.tokens(rawQuery);
            } catch (TargetFilterModel.QueryValidationException e) {
                String key = e.issue() == TargetFilterModel.QueryIssue.EMPTY
                        ? "message.itemmilestonelog.filter_empty_query"
                        : "message.itemmilestonelog.filter_query_too_long";
                fail(ctx, ServerLang.tr(player, key));
                return null;
            }
        }
        TargetFilterModel.Kind kind = null;
        if (kindArg != null) {
            kind = switch (kindArg) {
                case "tags" -> TargetFilterModel.Kind.TAGS;
                case "items" -> TargetFilterModel.Kind.ITEMS;
                case "counts" -> TargetFilterModel.Kind.COUNTS;
                case "default" -> TargetFilterModel.Kind.DEFAULT;
                default -> null;
            };
            if (kind == null) {
                fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.filter_invalid_kind"));
                return null;
            }
        }
        return new FilterCriteria(tokens, kind, rawQuery, kindArg);
    }

    // ------------------------------------------------------------- PR-1 opt-in display branches

    /** F-150-19/27: the existing targets tree plus a leading {@code compact} and a {@code near} child. */
    private static LiteralArgumentBuilder<CommandSourceStack> targetsBranch(
            SuggestionProvider<CommandSourceStack> kindSuggestions) {
        LiteralArgumentBuilder<CommandSourceStack> node = literal("targets");
        attachTargetsChildren(node, kindSuggestions, false);
        LiteralArgumentBuilder<CommandSourceStack> compact = literal("compact");
        attachTargetsChildren(compact, kindSuggestions, true);
        node.then(compact);
        node.then(literal("near")
                .executes(ctx -> targetsNear(ctx, CommandAnalysisModel.DEFAULT_NEAR_THRESHOLD, null))
                .then(literal("threshold")
                        .then(argument("threshold", IntegerArgumentType.integer(1, 99))
                                .executes(ctx -> targetsNear(ctx,
                                        IntegerArgumentType.getInteger(ctx, "threshold"), null))
                                .then(argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> targetsNear(ctx,
                                                IntegerArgumentType.getInteger(ctx, "threshold"),
                                                IntegerArgumentType.getInteger(ctx, "page"))))))
                .then(argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> targetsNear(ctx, CommandAnalysisModel.DEFAULT_NEAR_THRESHOLD,
                                IntegerArgumentType.getInteger(ctx, "page")))));
        node.then(targetMetaNode());
        return node;
    }

    private static void attachTargetsChildren(LiteralArgumentBuilder<CommandSourceStack> node,
                                              SuggestionProvider<CommandSourceStack> kindSuggestions,
                                              boolean compact) {
        node.executes(ctx -> targets(ctx, Pagination.TargetFilter.ALL, null, compact));
        node.then(literal("pending")
                .executes(ctx -> targets(ctx, Pagination.TargetFilter.PENDING, null, compact))
                .then(argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> targets(ctx, Pagination.TargetFilter.PENDING,
                                IntegerArgumentType.getInteger(ctx, "page"), compact)))
                .then(filterBranch(Pagination.TargetFilter.PENDING, kindSuggestions, compact))
                .then(kindBranch(Pagination.TargetFilter.PENDING, kindSuggestions, compact)));
        node.then(literal("done")
                .executes(ctx -> targets(ctx, Pagination.TargetFilter.DONE, null, compact))
                .then(argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> targets(ctx, Pagination.TargetFilter.DONE,
                                IntegerArgumentType.getInteger(ctx, "page"), compact)))
                .then(filterBranch(Pagination.TargetFilter.DONE, kindSuggestions, compact))
                .then(kindBranch(Pagination.TargetFilter.DONE, kindSuggestions, compact)));
        node.then(argument("page", IntegerArgumentType.integer(1))
                .executes(ctx -> targets(ctx, Pagination.TargetFilter.ALL,
                        IntegerArgumentType.getInteger(ctx, "page"), compact)));
        node.then(filterBranch(Pagination.TargetFilter.ALL, kindSuggestions, compact));
        node.then(kindBranch(Pagination.TargetFilter.ALL, kindSuggestions, compact));
    }

    /** F-150-21/27: {@code sort} may appear before or after {@code filter}; both work under compact. */
    private static LiteralArgumentBuilder<CommandSourceStack> listBranch(
            SuggestionProvider<CommandSourceStack> sortSuggestions) {
        LiteralArgumentBuilder<CommandSourceStack> node = literal("list");
        attachListChildren(node, false, false, false, sortSuggestions);
        LiteralArgumentBuilder<CommandSourceStack> compact = literal("compact");
        attachListChildren(compact, true, false, false, sortSuggestions);
        node.then(compact);
        return node;
    }

    private static void attachListChildren(ArgumentBuilder<CommandSourceStack, ?> node,
                                           boolean compact, boolean sortAlready, boolean queryAlready,
                                           SuggestionProvider<CommandSourceStack> sortSuggestions) {
        node.executes(ctx -> list(ctx, null,
                queryAlready ? StringArgumentType.getString(ctx, "query") : null,
                sortAlready ? StringArgumentType.getString(ctx, "sort") : null, compact));
        node.then(argument("page", IntegerArgumentType.integer(1))
                .executes(ctx -> list(ctx, IntegerArgumentType.getInteger(ctx, "page"),
                        queryAlready ? StringArgumentType.getString(ctx, "query") : null,
                        sortAlready ? StringArgumentType.getString(ctx, "sort") : null, compact)));
        if (!sortAlready) {
            node.then(literal("sort").then(listSortNode(compact, queryAlready, sortSuggestions)));
        }
        if (!queryAlready) {
            node.then(literal("filter").then(listQueryNode(compact, sortAlready, sortSuggestions)));
        }
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> listSortNode(
            boolean compact, boolean queryAlready, SuggestionProvider<CommandSourceStack> sortSuggestions) {
        RequiredArgumentBuilder<CommandSourceStack, String> sort = argument("sort", StringArgumentType.word())
                .suggests(sortSuggestions)
                .executes(ctx -> list(ctx, null,
                        queryAlready ? StringArgumentType.getString(ctx, "query") : null,
                        StringArgumentType.getString(ctx, "sort"), compact));
        sort.then(argument("page", IntegerArgumentType.integer(1))
                .executes(ctx -> list(ctx, IntegerArgumentType.getInteger(ctx, "page"),
                        queryAlready ? StringArgumentType.getString(ctx, "query") : null,
                        StringArgumentType.getString(ctx, "sort"), compact)));
        if (!queryAlready) {
            sort.then(literal("filter").then(listQueryNode(compact, true, sortSuggestions)));
        }
        return sort;
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> listQueryNode(
            boolean compact, boolean sortAlready, SuggestionProvider<CommandSourceStack> sortSuggestions) {
        RequiredArgumentBuilder<CommandSourceStack, String> query = argument("query", StringArgumentType.string())
                .executes(ctx -> list(ctx, null, StringArgumentType.getString(ctx, "query"),
                        sortAlready ? StringArgumentType.getString(ctx, "sort") : null, compact));
        query.then(argument("page", IntegerArgumentType.integer(1))
                .executes(ctx -> list(ctx, IntegerArgumentType.getInteger(ctx, "page"),
                        StringArgumentType.getString(ctx, "query"),
                        sortAlready ? StringArgumentType.getString(ctx, "sort") : null, compact)));
        if (!sortAlready) {
            query.then(literal("sort").then(listSortNode(compact, true, sortSuggestions)));
        }
        return query;
    }

    /** Fresh {@code filter <query> [kind <kind>] [<page>]} branch for one record-state filter. */
    private static LiteralArgumentBuilder<CommandSourceStack> filterBranch(
            Pagination.TargetFilter filter, SuggestionProvider<CommandSourceStack> kindSuggestions,
            boolean compact) {
        return literal("filter")
                .then(argument("query", StringArgumentType.string())
                        .executes(ctx -> targets(ctx, filter, null, StringArgumentType.getString(ctx, "query"), null, compact))
                        .then(argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> targets(ctx, filter, IntegerArgumentType.getInteger(ctx, "page"),
                                        StringArgumentType.getString(ctx, "query"), null, compact)))
                        .then(literal("kind")
                                .then(argument("kind", StringArgumentType.word())
                                        .suggests(kindSuggestions)
                                        .executes(ctx -> targets(ctx, filter, null,
                                                StringArgumentType.getString(ctx, "query"),
                                                StringArgumentType.getString(ctx, "kind"), compact))
                                        .then(argument("page", IntegerArgumentType.integer(1))
                                                .executes(ctx -> targets(ctx, filter,
                                                        IntegerArgumentType.getInteger(ctx, "page"),
                                                        StringArgumentType.getString(ctx, "query"),
                                                        StringArgumentType.getString(ctx, "kind"), compact))))));
    }

    /** Fresh standalone {@code kind <kind> [<page>]} branch for one record-state filter. */
    private static LiteralArgumentBuilder<CommandSourceStack> kindBranch(
            Pagination.TargetFilter filter, SuggestionProvider<CommandSourceStack> kindSuggestions,
            boolean compact) {
        return literal("kind")
                .then(argument("kind", StringArgumentType.word())
                        .suggests(kindSuggestions)
                        .executes(ctx -> targets(ctx, filter, null, null, StringArgumentType.getString(ctx, "kind"), compact))
                        .then(argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> targets(ctx, filter, IntegerArgumentType.getInteger(ctx, "page"),
                                        null, StringArgumentType.getString(ctx, "kind"), compact))));
    }

    /** F-150-3: additive {@code search <query> [scope <scope>] [<page>]} branch. */
    private static LiteralArgumentBuilder<CommandSourceStack> searchBranch(
            SuggestionProvider<CommandSourceStack> scopeSuggestions) {
        return literal("search")
                .then(argument("query", StringArgumentType.string())
                        .executes(ctx -> search(ctx, StringArgumentType.getString(ctx, "query"), null, null))
                        .then(argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> search(ctx, StringArgumentType.getString(ctx, "query"), null,
                                        IntegerArgumentType.getInteger(ctx, "page"))))
                        .then(literal("scope")
                                .then(argument("scope", StringArgumentType.word())
                                        .suggests(scopeSuggestions)
                                        .executes(ctx -> search(ctx, StringArgumentType.getString(ctx, "query"),
                                                StringArgumentType.getString(ctx, "scope"), null))
                                        .then(argument("page", IntegerArgumentType.integer(1))
                                                .executes(ctx -> search(ctx, StringArgumentType.getString(ctx, "query"),
                                                        StringArgumentType.getString(ctx, "scope"),
                                                        IntegerArgumentType.getInteger(ctx, "page")))))));
    }

    private static int targets(CommandContext<CommandSourceStack> ctx,
                               Pagination.TargetFilter filter,
                               @Nullable Integer page) throws CommandSyntaxException {
        return targets(ctx, filter, page, false);
    }

    private static int targets(CommandContext<CommandSourceStack> ctx,
                               Pagination.TargetFilter filter,
                               @Nullable Integer page,
                               boolean compact) throws CommandSyntaxException {
        return targets(ctx, filter, page, null, null, compact);
    }

    private static int targets(CommandContext<CommandSourceStack> ctx,
                               Pagination.TargetFilter filter,
                               @Nullable Integer page,
                               @Nullable String rawQuery,
                               @Nullable String kindArg) throws CommandSyntaxException {
        return targets(ctx, filter, page, rawQuery, kindArg, false);
    }

    private static int targets(CommandContext<CommandSourceStack> ctx,
                               Pagination.TargetFilter filter,
                               @Nullable Integer page,
                               @Nullable String rawQuery,
                               @Nullable String kindArg,
                               boolean compact) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        FilterCriteria criteria = parseCriteria(ctx, player, rawQuery, kindArg);
        if (criteria == null) {
            return 0;
        }
        MilestoneData.PlayerData pd = dataOrNull(player);
        Set<TargetKey> targets = Tracker.effectiveTargets(pd);
        if (targets.isEmpty()) {
            String emptyKey = compact ? "message.itemmilestonelog.compact_empty"
                    : criteria.active() ? "message.itemmilestonelog.targets_filter_none"
                    : "message.itemmilestonelog.no_targets";
            return send(ctx, ServerLang.tr(player, emptyKey));
        }
        Map<TargetKey, MilestoneRecord> records = pd != null ? pd.records : Map.of();
        List<TargetKey> sorted = new ArrayList<>();
        for (TargetKey key : targets) {
            boolean recorded = records.containsKey(key);
            if (filter == Pagination.TargetFilter.ALL
                    || (filter == Pagination.TargetFilter.PENDING && !recorded)
                    || (filter == Pagination.TargetFilter.DONE && recorded)) {
                sorted.add(key);
            }
        }
        // F-150-1 / F-150-2: shared keyword + kind filtering before sorting and slicing.
        if (criteria.active()) {
            sorted = new ArrayList<>(TargetFilterModel.apply(sorted,
                    key -> new TargetFilterModel.TargetView(key.itemId(), key.count()),
                    criteria.tokens(), criteria.kind(), criteria.defaults()));
        }
        if (sorted.isEmpty()) {
            if (compact) {
                return send(ctx, ServerLang.tr(player, "message.itemmilestonelog.compact_empty"));
            }
            if (criteria.active()) {
                return send(ctx, ServerLang.tr(player, "message.itemmilestonelog.targets_filter_none"));
            }
            String emptyKey = filter == Pagination.TargetFilter.PENDING
                    ? "message.itemmilestonelog.targets_pending_none"
                    : "message.itemmilestonelog.targets_done_none";
            return send(ctx, ServerLang.tr(player, emptyKey));
        }
        sorted.sort(TargetKey::compareTo);
        if (compact) {
            // F-150-27: only the shared dedupe/order helper is used; row rendering stays here.
            sorted = CommandAnalysisModel.compactKeys(sorted.stream()
                    .map(k -> new CommandAnalysisModel.TargetRef(k.itemId(), k.count())).toList()).stream()
                    .map(k -> new TargetKey(k.itemId(), k.count())).toList();
        }

        Pagination.PageSlice<TargetKey> slice = null;
        List<TargetKey> displayed;
        if (page != null) {
            slice = Pagination.slice(sorted, page);
            if (slice.isOutOfRange()) {
                return fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.page_out_of_range",
                        page, slice.totalPages()));
            }
            displayed = slice.items();
        } else {
            displayed = sorted;
        }

        Set<String> carried = Tracker.carriedFromCreative(pd);
        TagIndex tagIndex = TagTargets.index();
        Set<String> progressItemIds = new LinkedHashSet<>();
        if (!compact) {
            for (TargetKey key : displayed) {
                // Tag totals already exclude creative-carried members (F8b), so tags are always counted.
                if (key.count() > TargetKey.MIN_COUNT && !records.containsKey(key)
                        && (key.isTag() || !carried.contains(key.itemId()))) {
                    progressItemIds.add(key.itemId());
                }
            }
        }
        Map<String, Integer> possessionCounts = progressItemIds.isEmpty()
                ? Map.of()
                : Tracker.countItemsInPossession(player, progressItemIds);

        if (!compact) {
            String headerKey = switch (filter) {
                case ALL -> "message.itemmilestonelog.targets_header";
                case PENDING -> "message.itemmilestonelog.targets_pending_header";
                case DONE -> "message.itemmilestonelog.targets_done_header";
            };
            int headerCount = sorted.size();
            ctx.getSource().sendSuccess(() -> ServerLang.tr(player, headerKey, headerCount), false);
        }
        for (TargetKey key : displayed) {
            if (compact) {
                Component line = ServerLang.tr(player, "message.itemmilestonelog.compact_entry",
                        Component.literal(key.formatDisplayId()));
                ctx.getSource().sendSuccess(() -> line, false);
                continue;
            }
            MilestoneRecord rec = records.get(key);
            Component line;
            if (rec != null) {
                line = ServerLang.tr(player, "message.itemmilestonelog.target_done",
                        MilestoneService.targetDisplayName(key), Playtime.formatComponent(player, rec.playTicks()));
            } else {
                // F50: a tag target gets the note when any of its members is carried out of creative.
                boolean isCarried = Tracker.isCreativeCarriedTarget(key, carried, tagIndex);
                Component label = MilestoneService.targetDisplayName(key);
                if (key.count() > TargetKey.MIN_COUNT) {
                    int current = (isCarried && !key.isTag()) ? 0 : possessionCounts.getOrDefault(key.itemId(), 0);
                    label = ServerLang.tr(player, "message.itemmilestonelog.target_progress", label, current);
                }
                MutableComponent pendingLine = ServerLang.tr(player, "message.itemmilestonelog.target_pending", label);
                if (isCarried) {
                    pendingLine.append(ServerLang.tr(player, "message.itemmilestonelog.target_creative_carried"));
                }
                line = pendingLine;
            }
            ctx.getSource().sendSuccess(() -> line, false);
        }
        if (slice != null) {
            sendPageFooter(ctx.getSource(), slice,
                    Pagination.targetsCommandPrefix(filter, compact, criteria.targetSuffix()));
        }
        return displayed.size();
    }

    /** F-150-19: one possession query for all candidates, then the shared near-complete analysis. */
    private static int targetsNear(CommandContext<CommandSourceStack> ctx, int threshold,
                                   @Nullable Integer page) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        if (!CommandAnalysisModel.isValidNearThreshold(threshold)) {
            return fail(ctx, ServerLang.tr(player,
                    "message.itemmilestonelog.targets_near_invalid_threshold", 1, 99));
        }
        MilestoneData.PlayerData pd = dataOrNull(player);
        Set<TargetKey> effective = Tracker.effectiveTargets(pd);
        Map<TargetKey, MilestoneRecord> records = pd != null ? pd.records : Map.of();
        Set<String> carried = Tracker.carriedFromCreative(pd);
        Set<String> queryIds = new LinkedHashSet<>();
        for (TargetKey key : effective) {
            if (records.containsKey(key) || key.count() <= TargetKey.MIN_COUNT) {
                continue;
            }
            if (!key.isTag() && carried.contains(key.itemId())) {
                continue;
            }
            queryIds.add(key.itemId());
        }
        Map<String, Integer> possession = queryIds.isEmpty()
                ? Map.of()
                : Tracker.countItemsInPossession(player, queryIds);
        List<CommandAnalysisModel.NearCandidate> candidates = new ArrayList<>();
        for (TargetKey key : effective) {
            if (records.containsKey(key) || key.count() <= TargetKey.MIN_COUNT) {
                continue;
            }
            if (!key.isTag() && carried.contains(key.itemId())) {
                continue;
            }
            candidates.add(new CommandAnalysisModel.NearCandidate(key.itemId(), key.count(),
                    possession.getOrDefault(key.itemId(), 0), key.isTag()));
        }
        CommandAnalysisModel.NearResult result = CommandAnalysisModel.nearTargets(candidates, threshold);
        ctx.getSource().sendSuccess(() -> ServerLang.tr(player,
                "message.itemmilestonelog.targets_near_header",
                result.entries().size() + result.moreCount()), false);
        if (result.entries().isEmpty()) {
            return send(ctx, ServerLang.tr(player, "message.itemmilestonelog.targets_near_none"));
        }
        List<CommandAnalysisModel.NearEntry> rows = result.entries();
        Pagination.PageSlice<CommandAnalysisModel.NearEntry> slice = null;
        if (page != null) {
            slice = Pagination.slice(rows, page);
            if (slice.isOutOfRange()) {
                return fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.page_out_of_range",
                        page, slice.totalPages()));
            }
            rows = slice.items();
        }
        for (CommandAnalysisModel.NearEntry row : rows) {
            Component line = ServerLang.tr(player, "message.itemmilestonelog.targets_near_entry",
                    MilestoneService.targetDisplayName(new TargetKey(row.itemId(), row.count())),
                    row.current(), row.count(), row.progressPercent());
            ctx.getSource().sendSuccess(() -> line, false);
        }
        if (page == null && result.moreCount() > 0) {
            ctx.getSource().sendSuccess(() -> ServerLang.tr(player,
                    "message.itemmilestonelog.targets_near_more", result.moreCount()), false);
        }
        if (slice != null) {
            sendPageFooter(ctx.getSource(), slice, Pagination.targetsNearCommandPrefix(threshold));
        }
        return rows.size();
    }

    private static int list(CommandContext<CommandSourceStack> ctx, @Nullable Integer page) throws CommandSyntaxException {
        return list(ctx, page, null, null, false);
    }

    private static int list(CommandContext<CommandSourceStack> ctx, @Nullable Integer page,
                            @Nullable String rawQuery) throws CommandSyntaxException {
        return list(ctx, page, rawQuery, null, false);
    }

    private static int list(CommandContext<CommandSourceStack> ctx, @Nullable Integer page,
                            @Nullable String rawQuery, @Nullable String rawSort,
                            boolean compact) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        FilterCriteria criteria = parseCriteria(ctx, player, rawQuery, null);
        if (criteria == null) {
            return 0;
        }
        CommandAnalysisModel.RecordSort sort = CommandAnalysisModel.RecordSort.parse(rawSort);
        if (rawSort != null && sort == null) {
            return fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.list_invalid_sort", rawSort));
        }
        MilestoneData.PlayerData pd = dataOrNull(player);
        if (pd == null || pd.records.isEmpty()) {
            return send(ctx, ServerLang.tr(player, compact
                    ? "message.itemmilestonelog.compact_empty"
                    : criteria.active() ? "message.itemmilestonelog.list_filter_none"
                    : "message.itemmilestonelog.no_records"));
        }
        List<Map.Entry<TargetKey, MilestoneRecord>> entries = new ArrayList<>(pd.records.entrySet());
        entries.sort(Comparator.comparingInt((Map.Entry<TargetKey, MilestoneRecord> e) -> e.getValue().playTicks())
                .thenComparing(Map.Entry::getKey));

        // F-150-1: shared keyword filtering of record keys before slicing (records have no kind filter).
        if (criteria.active()) {
            entries = new ArrayList<>(TargetFilterModel.apply(entries,
                    e -> new TargetFilterModel.TargetView(e.getKey().itemId(), e.getKey().count()),
                    criteria.tokens(), null, Set.of()));
        }
        if (entries.isEmpty()) {
            return send(ctx, ServerLang.tr(player, compact
                    ? "message.itemmilestonelog.compact_empty"
                    : "message.itemmilestonelog.list_filter_none"));
        }
        // F-150-21: re-sort the filtered list only for explicit non-recent sort keys.
        if (sort == CommandAnalysisModel.RecordSort.NAME || sort == CommandAnalysisModel.RecordSort.DAY) {
            Map<TargetKey, MilestoneRecord> byKey = new HashMap<>();
            for (Map.Entry<TargetKey, MilestoneRecord> e : entries) {
                byKey.put(e.getKey(), e.getValue());
            }
            List<CommandAnalysisModel.RecordValue> values = new ArrayList<>();
            for (Map.Entry<TargetKey, MilestoneRecord> e : entries) {
                MilestoneRecord r = e.getValue();
                values.add(new CommandAnalysisModel.RecordValue(e.getKey().itemId(), e.getKey().count(),
                        r.playTicks(), r.day(), r.epochMillis()));
            }
            entries = CommandAnalysisModel.sortRecords(values, sort).stream()
                    .map(v -> Map.entry(new TargetKey(v.itemId(), v.count()),
                            byKey.get(new TargetKey(v.itemId(), v.count()))))
                    .toList();
        }
        if (compact) {
            entries = CommandAnalysisModel.compactKeys(entries.stream()
                    .map(e -> new CommandAnalysisModel.TargetRef(e.getKey().itemId(), e.getKey().count())).toList())
                    .stream().map(k -> Map.entry(new TargetKey(k.itemId(), k.count()),
                            pd.records.get(new TargetKey(k.itemId(), k.count())))).toList();
        }

        Pagination.PageSlice<Map.Entry<TargetKey, MilestoneRecord>> slice = null;
        List<Map.Entry<TargetKey, MilestoneRecord>> displayed;
        if (page != null) {
            slice = Pagination.slice(entries, page);
            if (slice.isOutOfRange()) {
                return fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.page_out_of_range",
                        page, slice.totalPages()));
            }
            displayed = slice.items();
        } else {
            displayed = entries;
        }

        if (!compact) {
            int headerCount = entries.size();
            ctx.getSource().sendSuccess(() -> ServerLang.tr(player,
                    "message.itemmilestonelog.records_header", headerCount), false);
        }
        ZoneId zone = Config.displayTimeZone();
        for (var e : displayed) {
            Component line = compact
                    ? ServerLang.tr(player, "message.itemmilestonelog.compact_entry",
                            Component.literal(e.getKey().formatDisplayId()))
                    : ServerLang.tr(player, "message.itemmilestonelog.target_done",
                            MilestoneService.targetDisplayName(e.getKey()),
                            Playtime.formatRecordComponent(player, e.getValue(), zone));
            ctx.getSource().sendSuccess(() -> line, false);
        }
        if (slice != null) {
            String suffix = (rawSort == null ? "" : "sort " + rawSort)
                    + (criteria.listSuffix().isEmpty() ? "" :
                    (rawSort == null ? "" : " ") + criteria.listSuffix());
            sendPageFooter(ctx.getSource(), slice, Pagination.listCommandPrefix(compact, suffix));
        }
        return displayed.size();
    }

    // --------------------------------------------------------- PR-1 F-150-22 settings

    /** Read-only view of the four existing personal flags; never writes anything. */
    private static int settings(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        List<CommandAnalysisModel.SettingLine> lines = settingsLinesFor(
                MilestoneData.get(player.level().getServer()), player.getUUID());
        ctx.getSource().sendSuccess(() -> ServerLang.tr(player,
                "message.itemmilestonelog.settings_header"), false);
        for (CommandAnalysisModel.SettingLine line : lines) {
            Component state = ServerLang.tr(player, line.enabled()
                    ? "message.itemmilestonelog.settings_on"
                    : "message.itemmilestonelog.settings_off");
            ctx.getSource().sendSuccess(() -> ServerLang.tr(player,
                    "message.itemmilestonelog.settings_line", Component.literal(line.name()), state), false);
        }
        return lines.size() + 1;
    }

    /** SF-9: read-only settings view; a data-less player gets all-off defaults and no PlayerData. */
    static List<CommandAnalysisModel.SettingLine> settingsLinesFor(MilestoneData data, UUID playerId) {
        MilestoneData.PlayerData pd = data.playerOrNull(playerId);
        return CommandAnalysisModel.settingsSnapshot(
                pd != null && pd.muteBroadcasts,
                pd != null && pd.muteProgressAlerts,
                pd != null && pd.excludeFromDiscord,
                pd != null && pd.hideFromLookup);
    }

    /** Sets one existing flag through the legacy handlers; an already-equal value is a no-op. */
    private static int settingsChange(CommandContext<CommandSourceStack> ctx, boolean desired)
            throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        String raw = StringArgumentType.getString(ctx, "toggle");
        CommandAnalysisModel.Toggle toggle = CommandAnalysisModel.Toggle.parse(raw);
        if (toggle == null) {
            return fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.settings_unknown", raw));
        }
        if (!CommandAnalysisModel.settingsToggleAllowed(toggle, Config.ALLOW_DISCORD_OPT_OUT.get())) {
            return fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.feature_disabled"));
        }
        // R2-SF-1: a missing PlayerData has all-off defaults. Read only here; the legacy handler
        // creates the PlayerData when a value actually changes, never on a no-op.
        boolean needsWrite = settingsToggleNeedsWrite(
                MilestoneData.get(player.level().getServer()), player.getUUID(), toggle, desired);
        return switch (toggle) {
            case BROADCAST -> !needsWrite
                    ? send(ctx, ServerLang.tr(player, desired
                            ? "message.itemmilestonelog.mutebroadcast_on"
                            : "message.itemmilestonelog.mutebroadcast_off"))
                    : muteBroadcast(ctx);
            case PROGRESS -> !needsWrite
                    ? send(ctx, ServerLang.tr(player, desired
                            ? "message.itemmilestonelog.muteprogress_on"
                            : "message.itemmilestonelog.muteprogress_off"))
                    : muteProgress(ctx);
            case DISCORD -> !needsWrite
                    ? send(ctx, ServerLang.tr(player, desired
                            ? "message.itemmilestonelog.discordoptout_on"
                            : "message.itemmilestonelog.discordoptout_off"))
                    : respond(ctx, MilestoneService.toggleDiscordOptOut(player));
            case PRIVACY -> !needsWrite
                    ? send(ctx, ServerLang.tr(player, desired
                            ? "message.itemmilestonelog.privacy_on"
                            : "message.itemmilestonelog.privacy_off"))
                    : respond(ctx, MilestoneService.togglePrivacy(player));
        };
    }

    /**
     * R2-SF-1: read-only decision for a settings toggle. A data-less player has all flags {@code false};
     * {@code true} therefore needs a write and {@code false} is an exact no-op.
     */
    static boolean settingsToggleNeedsWrite(MilestoneData data, UUID playerId,
                                            CommandAnalysisModel.Toggle toggle, boolean desired) {
        MilestoneData.PlayerData pd = data.playerOrNull(playerId);
        if (pd == null) {
            return desired;
        }
        return switch (toggle) {
            case BROADCAST -> pd.muteBroadcasts != desired;
            case PROGRESS -> pd.muteProgressAlerts != desired;
            case DISCORD -> pd.excludeFromDiscord != desired;
            case PRIVACY -> pd.hideFromLookup != desired;
        };
    }

    // --------------------------------------------------------- PR-1 F-150-23 randomtarget

    private static int randomTarget(CommandContext<CommandSourceStack> ctx, @Nullable String rawKind)
            throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        CommandAnalysisModel.TargetKind kind = rawKind == null
                ? CommandAnalysisModel.TargetKind.BOTH : CommandAnalysisModel.TargetKind.parse(rawKind);
        if (kind == null) {
            return fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.randomtarget_invalid_kind", rawKind));
        }
        MilestoneData.PlayerData pd = dataOrNull(player);
        Set<TargetKey> effective = Tracker.effectiveTargets(pd);
        Map<TargetKey, MilestoneRecord> records = pd != null ? pd.records : Map.of();
        Set<String> carried = Tracker.carriedFromCreative(pd);
        List<CommandAnalysisModel.TargetRef> candidates = CommandAnalysisModel.randomTargetCandidates(
                effective.stream().map(k -> new CommandAnalysisModel.TargetRef(k.itemId(), k.count())).toList(),
                records.keySet().stream().map(k -> new CommandAnalysisModel.TargetRef(k.itemId(), k.count())).toList(),
                carried, kind);
        CommandAnalysisModel.TargetRef picked = CommandAnalysisModel.pickRandomTarget(
                candidates, ThreadLocalRandom.current());
        if (picked == null) {
            return send(ctx, ServerLang.tr(player, "message.itemmilestonelog.randomtarget_none"));
        }
        TargetKey key = new TargetKey(picked.itemId(), picked.count());
        Component label = MilestoneService.targetDisplayName(key);
        ctx.getSource().sendSuccess(() -> ServerLang.tr(player,
                "message.itemmilestonelog.randomtarget_suggestion", label), false);
        int current = Tracker.countItemsInPossession(player, Set.of(picked.itemId()))
                .getOrDefault(picked.itemId(), 0);
        return send(ctx, ServerLang.tr(player, "message.itemmilestonelog.target_progress", label, current));
    }

    // --------------------------------------------------------- PR-1 F-150-35 trends

    private static int trends(CommandContext<CommandSourceStack> ctx, @Nullable String rawDays,
                              @Nullable Integer page) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        int days = CommandAnalysisModel.DEFAULT_TREND_DAYS;
        if (rawDays != null) {
            try {
                int parsed = Integer.parseInt(rawDays);
                if (parsed == 7 || parsed == 30 || parsed == 90) {
                    days = parsed;
                }
            } catch (NumberFormatException ignored) {
                // Suggestions only offer the three documented windows; unknown words use the default.
            }
        }
        MilestoneData.PlayerData pd = dataOrNull(player);
        List<CommandAnalysisModel.RecordValue> records = new ArrayList<>();
        if (pd != null) {
            for (Map.Entry<TargetKey, MilestoneRecord> e : pd.records.entrySet()) {
                MilestoneRecord r = e.getValue();
                records.add(new CommandAnalysisModel.RecordValue(e.getKey().itemId(), e.getKey().count(),
                        r.playTicks(), r.day(), r.epochMillis()));
            }
        }
        CommandAnalysisModel.TrendsResult result = CommandAnalysisModel.trends(
                records, Config.displayTimeZone(), days, System.currentTimeMillis());
        ZoneId zone = Config.displayTimeZone();
        final int effectiveDays = days;
        ctx.getSource().sendSuccess(() -> ServerLang.tr(player,
                "message.itemmilestonelog.trends_header", effectiveDays), false);
        List<Component> rows = new ArrayList<>();
        if (result.datedInWindow() == 0) {
            rows.add(ServerLang.tr(player, "message.itemmilestonelog.trends_empty"));
        } else {
            for (CommandAnalysisModel.TrendDay day : result.days()) {
                String pace = day.milestonesPerHour() <= 0.0
                        ? "0" : String.valueOf(Math.round(day.milestonesPerHour()));
                rows.add(ServerLang.tr(player, "message.itemmilestonelog.trends_day",
                        day.date().toString(), day.count(), pace));
            }
            String glyphs = "▁▁▃▅▇";
            StringBuilder chart = new StringBuilder();
            for (CommandAnalysisModel.TrendDay day : result.days()) {
                chart.append(glyphs.charAt(day.level()));
            }
            rows.add(Component.literal(chart.toString()));
        }
        Pagination.PageSlice<Component> slice = null;
        if (page != null) {
            slice = Pagination.slice(rows, page);
            if (slice.isOutOfRange()) {
                return fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.page_out_of_range",
                        page, slice.totalPages()));
            }
            rows = slice.items();
        }
        for (Component row : rows) {
            ctx.getSource().sendSuccess(() -> row, false);
        }
        ctx.getSource().sendSuccess(() -> ServerLang.tr(player,
                "message.itemmilestonelog.trends_streak", result.streak()), false);
        if (result.unknownDateCount() > 0) {
            ctx.getSource().sendSuccess(() -> ServerLang.tr(player,
                    "message.itemmilestonelog.trends_unknown_date", result.unknownDateCount()), false);
        }
        if (slice != null) {
            sendPageFooter(ctx.getSource(), slice, Pagination.trendsCommandPrefix(days));
        }
        return rows.size();
    }

    // --------------------------------------------------------- PR-1 F-150-36 recap

    /**
     * SF-9/RB-2: computes the result from existing data only. RETURN uses the pre-login
     * {@code lastSeen} captured by Tracker, falling back to the stored value when no login was seen
     * in this JVM. A data-less player never creates or dirties a PlayerData.
     */
    static CommandAnalysisModel.RecapResult recapResultFor(MilestoneData data, UUID playerId,
                                                           CommandAnalysisModel.RecapKind kind,
                                                           long nowMillis) {
        MilestoneData.PlayerData pd = data.playerOrNull(playerId);
        Map<TargetKey, MilestoneRecord> records = pd != null ? pd.records : Map.of();
        if (kind == CommandAnalysisModel.RecapKind.RETURN) {
            long since = Tracker.preLoginLastSeen(playerId);
            if (since <= 0L && pd != null) {
                since = pd.lastSeen;
            }
            LoginWorldFirstSummaryLogic.Selection selection = LoginWorldFirstSummaryLogic.select(
                    data.worldFirsts(), playerId, since, nowMillis);
            List<CommandAnalysisModel.ReturnCandidate> candidates = new ArrayList<>();
            for (WorldFirstRecord worldFirst : selection.entries()) {
                MilestoneData.PlayerData holder = data.playerOrNull(worldFirst.playerUuid());
                String name = WorldFirstNames.choose(worldFirst.playerUuid(), worldFirst.playerName(),
                        holder != null, holder != null ? holder.lastKnownName : null).name();
                candidates.add(new CommandAnalysisModel.ReturnCandidate(
                        worldFirst.itemId(), worldFirst.count(), name, worldFirst.epochMillis()));
            }
            return CommandAnalysisModel.recapReturn(candidates);
        }
        if (kind == CommandAnalysisModel.RecapKind.DAILY) {
            List<CommandAnalysisModel.RecordValue> values = new ArrayList<>();
            for (Map.Entry<TargetKey, MilestoneRecord> e : records.entrySet()) {
                MilestoneRecord r = e.getValue();
                values.add(new CommandAnalysisModel.RecordValue(e.getKey().itemId(), e.getKey().count(),
                        r.playTicks(), r.day(), r.epochMillis()));
            }
            return CommandAnalysisModel.recapDaily(values, nowMillis);
        }
        Set<TargetKey> effective = Tracker.effectiveTargets(pd);
        int unknown = 0;
        int stillTracked = 0;
        for (Map.Entry<TargetKey, MilestoneRecord> e : records.entrySet()) {
            if (e.getValue().epochMillis() == 0L) {
                unknown++;
                if (effective.contains(e.getKey())) {
                    stillTracked++;
                }
            }
        }
        return CommandAnalysisModel.recapLegacy(unknown, stillTracked);
    }

    private static int recap(CommandContext<CommandSourceStack> ctx, @Nullable String rawKind)
            throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        CommandAnalysisModel.RecapKind kind = rawKind == null
                ? CommandAnalysisModel.RecapKind.RETURN : CommandAnalysisModel.RecapKind.parse(rawKind);
        if (kind == null) {
            kind = CommandAnalysisModel.RecapKind.RETURN;
        }
        MilestoneData data = MilestoneData.get(player.level().getServer());
        ZoneId zone = Config.displayTimeZone();
        CommandAnalysisModel.RecapResult result =
                recapResultFor(data, player.getUUID(), kind, System.currentTimeMillis());
        final CommandAnalysisModel.RecapKind effectiveKind = kind;
        ctx.getSource().sendSuccess(() -> ServerLang.tr(player,
                "message.itemmilestonelog.recap_header", effectiveKind.commandValue()), false);
        if (kind == CommandAnalysisModel.RecapKind.LEGACY) {
            return send(ctx, ServerLang.tr(player, "message.itemmilestonelog.recap_legacy",
                    result.unknownDateCount(), result.stillTrackedCount()));
        }
        if (result.entries().isEmpty()) {
            return send(ctx, ServerLang.tr(player, "message.itemmilestonelog.recap_empty"));
        }
        return sendRecapLines(ctx.getSource(), player, result, kind, zone);
    }

    /** R2-MN-1: renders capped recap rows and the overflow count for both return and daily. */
    static int sendRecapLines(CommandSourceStack source, @Nullable ServerPlayer player,
                             CommandAnalysisModel.RecapResult result,
                             CommandAnalysisModel.RecapKind kind, ZoneId zone) {
        for (CommandAnalysisModel.RecapEntry entry : result.entries()) {
            Component detail = kind == CommandAnalysisModel.RecapKind.RETURN
                    ? Component.literal(entry.holderName() + " ")
                            .append(Component.literal(Playtime.formatDate(entry.epochMillis(), zone)))
                    : Component.literal(Playtime.formatDate(entry.epochMillis(), zone));
            source.sendSuccess(() -> ServerLang.tr(player,
                    "message.itemmilestonelog.recap_entry",
                    MilestoneService.targetDisplayName(new TargetKey(entry.itemId(), entry.count())),
                    detail), false);
        }
        if (result.moreCount() > 0) {
            source.sendSuccess(() -> ServerLang.tr(player, "message.itemmilestonelog.recap_more",
                    result.moreCount()), false);
        }
        return result.entries().size() + (result.moreCount() > 0 ? 1 : 0);
    }


    // ------------------------------------------------------------- F-150-3 unified search

    /**
     * Testable gate for {@code presetsEnabled}: an explicit presets scope fails, scope all silently
     * drops the preset section; targets/records are unaffected either way (D-150-11).
     */
    static String searchPresetMode(TargetFilterModel.SearchScope scope, boolean presetsEnabled) {
        if (scope.failsWhenPresetsDisabled() && !presetsEnabled) {
            return "fail";
        }
        return scope.includesPresets(presetsEnabled) ? "include" : "skip";
    }

    private static int search(CommandContext<CommandSourceStack> ctx, String rawQuery,
                              @Nullable String rawScope, @Nullable Integer page) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        // Same validation and error keys as targets/list filter (F-150-1).
        FilterCriteria criteria = parseCriteria(ctx, player, rawQuery, null);
        if (criteria == null) {
            return 0;
        }
        TargetFilterModel.SearchScope scope = TargetFilterModel.SearchScope.parse(rawScope);
        if (scope == null) {
            return fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.search_invalid_scope"));
        }
        if ("fail".equals(searchPresetMode(scope, Config.PRESETS_ENABLED.get()))) {
            return fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.feature_disabled"));
        }

        MilestoneData.PlayerData pd = dataOrNull(player);
        Map<TargetKey, MilestoneRecord> records = pd != null ? pd.records : Map.of();
        List<TargetFilterModel.SearchCandidate> targetCandidates = new ArrayList<>();
        List<TargetFilterModel.SearchCandidate> recordCandidates = new ArrayList<>();
        List<TargetFilterModel.SearchCandidate> presetCandidates = new ArrayList<>();

        // Scope order and in-scope order match targets/list exactly (D-150-10).
        if (scope == TargetFilterModel.SearchScope.ALL
                || scope == TargetFilterModel.SearchScope.TARGETS) {
            List<TargetKey> sortedTargets = new ArrayList<>(Tracker.effectiveTargets(pd));
            sortedTargets.sort(TargetKey::compareTo);
            for (TargetKey key : sortedTargets) {
                targetCandidates.add(TargetFilterModel.searchTarget(key.itemId(), key.count()));
            }
        }
        if (scope == TargetFilterModel.SearchScope.ALL
                || scope == TargetFilterModel.SearchScope.RECORDS) {
            List<Map.Entry<TargetKey, MilestoneRecord>> sortedRecords = new ArrayList<>(records.entrySet());
            sortedRecords.sort(Comparator.comparingInt((Map.Entry<TargetKey, MilestoneRecord> e) -> e.getValue().playTicks())
                    .thenComparing(Map.Entry::getKey));
            for (Map.Entry<TargetKey, MilestoneRecord> e : sortedRecords) {
                recordCandidates.add(TargetFilterModel.searchRecord(e.getKey().itemId(), e.getKey().count()));
            }
        }
        String lang = MilestoneService.clientLanguage(player);
        if (scope.includesPresets(Config.PRESETS_ENABLED.get())) {
            for (Presets.PresetDefinition preset : Presets.all()) {
                List<String> searchables = new ArrayList<>();
                searchables.add(preset.id());
                searchables.add(preset.resolveDisplayName(lang));
                if (preset.builtin()) {
                    // Built-in JSON names are plain IDs; the lang value is the actual display name.
                    searchables.add(ServerLang.formatForLang(lang, "gui.itemmilestonelog.preset." + preset.id()));
                }
                presetCandidates.add(TargetFilterModel.searchPreset(preset.id(), searchables));
            }
        }

        TargetFilterModel.SearchResult result = TargetFilterModel.searchWithOverflow(
                targetCandidates, recordCandidates, presetCandidates, scope, criteria.tokens());
        if (result.hits().isEmpty()) {
            return send(ctx, ServerLang.tr(player, "message.itemmilestonelog.search_none"));
        }

        Pagination.PageSlice<TargetFilterModel.SearchHit> slice = null;
        List<TargetFilterModel.SearchHit> displayed;
        if (page != null) {
            slice = Pagination.slice(result.hits(), page);
            if (slice.isOutOfRange()) {
                return fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.page_out_of_range",
                        page, slice.totalPages()));
            }
            displayed = slice.items();
        } else {
            displayed = result.hits();
        }

        ZoneId zone = Config.displayTimeZone();
        for (TargetFilterModel.SearchHit hit : displayed) {
            Component label = switch (hit.source()) {
                case TARGET -> ServerLang.tr(player, "message.itemmilestonelog.search_source_target");
                case RECORD -> ServerLang.tr(player, "message.itemmilestonelog.search_source_record");
                case PRESET -> ServerLang.tr(player, "message.itemmilestonelog.search_source_preset");
            };
            Component display = Component.literal(hit.presetId() == null ? hit.itemId() : hit.presetId());
            switch (hit.source()) {
                case TARGET -> display = MilestoneService.targetDisplayName(hit.itemId(), hit.count());
                case RECORD -> {
                    Component name = MilestoneService.targetDisplayName(hit.itemId(), hit.count());
                    MilestoneRecord rec = records.get(TargetKey.of(hit.itemId(), hit.count()));
                    display = rec == null ? name
                            : ServerLang.tr(player, "message.itemmilestonelog.target_done",
                                    name, Playtime.formatRecordComponent(player, rec, zone));
                }
                case PRESET -> {
                    Presets.PresetDefinition preset = Presets.get(hit.presetId());
                    if (preset == null) {
                        display = Component.literal(hit.presetId());
                    } else {
                        // Same columns as preset list, so the ID is always shown (D-150-11).
                        String name = preset.builtin()
                                ? preset.id()
                                : preset.resolveDisplayName(MilestoneService.clientLanguage(player));
                        Component typeLabel = ServerLang.tr(player, switch (preset.kind()) {
                            case BUILTIN -> "message.itemmilestonelog.preset_type_builtin";
                            case FILE -> "message.itemmilestonelog.preset_type_file";
                            case DATAPACK -> "message.itemmilestonelog.preset_type_datapack";
                        });
                        display = ServerLang.tr(player, "message.itemmilestonelog.presets_entry",
                                name, preset.id(), preset.items().size(), typeLabel);
                    }
                }
            }
            Component line = ServerLang.tr(player, "message.itemmilestonelog.search_hit", label, display);
            ctx.getSource().sendSuccess(() -> line, false);
        }
        if (result.moreCount() > 0) {
            send(ctx, ServerLang.tr(player, "message.itemmilestonelog.search_more", result.moreCount()));
        }
        if (slice != null) {
            // PR-1's helper does not add the 'scope' literal; supply it as part of the argument so
            // the generated clickable command parses with the registered tree (see progress report).
            sendPageFooter(ctx.getSource(), slice,
                    Pagination.searchCommandPrefix(rawQuery, "scope " + scope.commandValue()));
        }
        return displayed.size();
    }

    private static int preset(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        return respond(ctx, MilestoneService.applyPreset(player(ctx), name));
    }

    private static int presetRemove(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        return respondWithUndoHint(ctx, MilestoneService.removePresetTargets(player(ctx), name));
    }

    // --------------------------------------------------------- F16 preset progress

    private static int presetProgress(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        if (!Config.PRESETS_ENABLED.get()) {
            return fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.feature_disabled"));
        }
        Presets.PresetDefinition preset = Presets.get(name);
        if (preset == null) {
            return fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.preset_unknown", name,
                    String.join(", ", Presets.names())));
        }
        MilestoneData.PlayerData pd = dataOrNull(player);
        Map<TargetKey, MilestoneRecord> records = pd != null ? pd.records : Map.of();
        // Same R(S) and exact-match counting as the F5 completion judgment.
        Presets.PresetProgress progress = Presets.presetProgress(
                preset, records.keySet(), MilestoneService::isKnownNonAirItem, Presets.PROGRESS_MAX_ROWS);

        send(ctx, ServerLang.tr(player, "message.itemmilestonelog.presetprogress_header",
                MilestoneService.presetDisplayName(player, preset), progress.recorded(), progress.total()));
        if (progress.complete()) {
            send(ctx, ServerLang.tr(player, "message.itemmilestonelog.presetprogress_complete"));
        } else {
            // Possession counts are shown even while recording is paused; creative-carried items count as 0.
            Set<String> carried = Tracker.carriedFromCreative(pd);
            Set<String> progressItemIds = new LinkedHashSet<>();
            for (TargetKey key : progress.unrecordedShown()) {
                if (key.count() > TargetKey.MIN_COUNT && !carried.contains(key.itemId())) {
                    progressItemIds.add(key.itemId());
                }
            }
            Map<String, Integer> possessionCounts = progressItemIds.isEmpty()
                    ? Map.of()
                    : Tracker.countItemsInPossession(player, progressItemIds);
            for (TargetKey key : progress.unrecordedShown()) {
                Component label = MilestoneService.targetDisplayName(key);
                if (key.count() > TargetKey.MIN_COUNT) {
                    int current = carried.contains(key.itemId()) ? 0 : possessionCounts.getOrDefault(key.itemId(), 0);
                    label = ServerLang.tr(player, "message.itemmilestonelog.target_progress", label, current);
                }
                send(ctx, ServerLang.tr(player, "message.itemmilestonelog.presetprogress_entry", label));
            }
            if (progress.unrecordedHidden() > 0) {
                send(ctx, ServerLang.tr(player, "message.itemmilestonelog.presetprogress_more", progress.unrecordedHidden()));
            }
        }
        if (progress.skipped() > 0) {
            send(ctx, ServerLang.tr(player, "message.itemmilestonelog.presetprogress_skipped", progress.skipped()));
        }
        return 1;
    }

    // --------------------------------------------------------- F-140-16 preset show

    private static int presetShow(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSourceStack source = ctx.getSource();
        Presets.PresetDefinition preset = Presets.get(name);
        PresetShowLogic.Result result = PresetShowLogic.show(
                preset != null ? showData(preset, MilestoneService.clientLanguage(source.getPlayer())) : null,
                Config.PRESETS_ENABLED.get(), Presets.PROGRESS_MAX_ROWS);
        return switch (result.status()) {
            case DISABLED -> fail(ctx,
                    ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
            case UNKNOWN -> fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_unknown",
                    name, String.join(", ", Presets.names())));
            case OK -> {
                send(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_show_header",
                        MilestoneService.presetDisplayNameForLang(
                                MilestoneService.clientLanguage(source.getPlayer()), preset),
                        preset.id(), presetKindLabel(source, preset)));
                for (PresetShowLogic.Line line : result.lines()) {
                    send(ctx, ServerLang.tr(source, presetShowLineKey(line.kind()), line.values().toArray()));
                }
                yield 1;
            }
        };
    }

    private static PresetShowLogic.ShowData showData(Presets.PresetDefinition preset, @Nullable String langCode) {
        List<PresetShowLogic.GroupSummary> groups = new ArrayList<>();
        for (PresetGroup group : preset.groups()) {
            groups.add(new PresetShowLogic.GroupSummary(
                    group.name().resolve(langCode, group.id()), group.items().size()));
        }
        return new PresetShowLogic.ShowData(
                preset.description().present() ? preset.description().resolve(langCode, "") : null,
                preset.category(), preset.version(), preset.order(),
                preset.requiredMods(), preset.unlockAfterHours(), preset.nextPresets(),
                groups, preset.items());
    }

    /** Existing {@code preset_type_*} messages, reused by the show header. */
    private static Component presetKindLabel(CommandSourceStack source, Presets.PresetDefinition preset) {
        return ServerLang.tr(source, switch (preset.kind()) {
            case BUILTIN -> "message.itemmilestonelog.preset_type_builtin";
            case FILE -> "message.itemmilestonelog.preset_type_file";
            case DATAPACK -> "message.itemmilestonelog.preset_type_datapack";
        });
    }

    private static String presetShowLineKey(PresetShowLogic.LineKind kind) {
        return switch (kind) {
            case DESCRIPTION -> "message.itemmilestonelog.preset_show_description";
            case CATEGORY -> "message.itemmilestonelog.preset_show_category";
            case VERSION -> "message.itemmilestonelog.preset_show_version";
            case ORDER -> "message.itemmilestonelog.preset_show_order";
            case REQUIRED_MODS -> "message.itemmilestonelog.preset_show_required_mods";
            case UNLOCK_AFTER_HOURS -> "message.itemmilestonelog.preset_show_unlock_after_hours";
            case NEXT_PRESETS -> "message.itemmilestonelog.preset_show_next_presets";
            case GROUP -> "message.itemmilestonelog.preset_show_group";
            case ITEM -> "message.itemmilestonelog.preset_show_item";
            case ITEMS_MORE -> "message.itemmilestonelog.preset_show_more";
        };
    }

    // --------------------------------------------------------- F-140-17 preset diff

    private static int presetDiff(CommandContext<CommandSourceStack> ctx, String nameA, String nameB) {
        CommandSourceStack source = ctx.getSource();
        Presets.PresetDefinition a = Presets.get(nameA);
        Presets.PresetDefinition b = Presets.get(nameB);
        PresetDiffLogic.Result result = PresetDiffLogic.diff(
                a != null ? a.items() : null,
                b != null ? b.items() : null,
                Config.PRESETS_ENABLED.get(), Presets.PROGRESS_MAX_ROWS);
        return switch (result.status()) {
            case DISABLED -> fail(ctx,
                    ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
            case UNKNOWN_A -> fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_unknown",
                    nameA, String.join(", ", Presets.names())));
            case UNKNOWN_B -> fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_unknown",
                    nameB, String.join(", ", Presets.names())));
            case OK -> {
                Component nameAComponent = MilestoneService.presetDisplayNameForLang(
                        MilestoneService.clientLanguage(source.getPlayer()), a);
                Component nameBComponent = MilestoneService.presetDisplayNameForLang(
                        MilestoneService.clientLanguage(source.getPlayer()), b);
                send(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_diff_header",
                        nameAComponent, nameBComponent));
                if (!result.hasDifferences()) {
                    send(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_diff_no_differences"));
                } else {
                    if (!result.onlyA().isEmpty() || result.onlyAHidden() > 0) {
                        send(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_diff_only",
                                nameAComponent));
                        for (PresetDiffLogic.ItemCount item : result.onlyA()) {
                            sendPresetDiffItem(ctx, source, item);
                        }
                        sendOmittedDiffRows(ctx, source, result.onlyAHidden());
                    }
                    if (!result.onlyB().isEmpty() || result.onlyBHidden() > 0) {
                        send(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_diff_only",
                                nameBComponent));
                        for (PresetDiffLogic.ItemCount item : result.onlyB()) {
                            sendPresetDiffItem(ctx, source, item);
                        }
                        sendOmittedDiffRows(ctx, source, result.onlyBHidden());
                    }
                    if (!result.countDifferences().isEmpty() || result.countDifferencesHidden() > 0) {
                        send(ctx, ServerLang.tr(source,
                                "message.itemmilestonelog.preset_diff_counts_header"));
                        for (PresetDiffLogic.CountDifference difference : result.countDifferences()) {
                            send(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_diff_count",
                                    difference.itemId(), difference.countA(), difference.countB()));
                        }
                        sendOmittedDiffRows(ctx, source, result.countDifferencesHidden());
                    }
                }
                yield 1;
            }
        };
    }

    private static void sendPresetDiffItem(CommandContext<CommandSourceStack> ctx, CommandSourceStack source,
                                           PresetDiffLogic.ItemCount item) {
        send(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_show_item",
                item.itemId(), item.count()));
    }

    private static void sendOmittedDiffRows(CommandContext<CommandSourceStack> ctx, CommandSourceStack source, int hidden) {
        if (hidden > 0) {
            send(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_show_more", hidden));
        }
    }

    // --------------------------------------------------------- F-140-18 preset share / importcode

    private static int presetShare(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSourceStack source = ctx.getSource();
        if (!Config.PRESETS_ENABLED.get()) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        Presets.PresetDefinition preset = Presets.get(name);
        if (preset == null) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_unknown",
                    name, String.join(", ", Presets.names())));
        }
        if (preset.items().size() > PresetShareLogic.MAX_ITEMS) {
            // importcode rejects such a code, so never emit one that cannot be imported.
            return fail(ctx, ServerLang.tr(source,
                    "message.itemmilestonelog.preset_importcode_too_many_items",
                    PresetShareLogic.MAX_ITEMS));
        }
        String code = PresetShareLogic.encode(shareData(preset));
        if (code.length() > PresetShareLogic.MAX_CODE_CHARS) {
            return fail(ctx, ServerLang.tr(source,
                    "message.itemmilestonelog.preset_importcode_too_long",
                    code.length(), PresetShareLogic.MAX_CODE_CHARS));
        }
        // One message: the label plus the code, which is click-to-copy.
        MutableComponent message = ServerLang.tr(source, "message.itemmilestonelog.preset_share_message",
                MilestoneService.presetDisplayNameForLang(
                        MilestoneService.clientLanguage(source.getPlayer()), preset),
                preset.items().size());
        message.append(Component.literal(" "));
        message.append(Component.literal(code).withStyle(style -> style
                .withUnderlined(true)
                .withClickEvent(copyToClipboardEvent(code))));
        return send(ctx, message);
    }

    private static PresetShareLogic.ShareData shareData(Presets.PresetDefinition preset) {
        // items() is already includes-expanded (F-140-6); share codes carry the expanded list.
        return new PresetShareLogic.ShareData(preset.id(), preset.name(), preset.items(),
                preset.description(), preset.category(), preset.order(), preset.version(),
                preset.requiredMods(), preset.groups(), preset.nextPresets(), preset.unlockAfterHours());
    }

    private static ClickEvent copyToClipboardEvent(String code) {
        return new ClickEvent.CopyToClipboard(code);
    }

    private static int presetImportCode(CommandContext<CommandSourceStack> ctx, String code) {
        CommandSourceStack source = ctx.getSource();
        // greedyString() keeps the whole IML1:... payload (issue #97); trim only surrounding space.
        MilestoneService.ActionResult result = MilestoneService.importPresetCode(
                source, code != null ? code.trim() : null);
        if (result.success()) {
            MilestoneService.syncAllOnlineGuiPlayers(source.getServer(), null);
            return send(ctx, result.message());
        }
        return fail(ctx, result.message());
    }

    private static int presetList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!Config.PRESETS_ENABLED.get()) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        List<Presets.PresetDefinition> all = Presets.all();
        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.presets_header", all.size()), false);
        for (Presets.PresetDefinition p : all) {
            // F21: the kind column distinguishes built-in, config-folder and data pack presets.
            Component typeLabel = ServerLang.tr(source, switch (p.kind()) {
                case BUILTIN -> "message.itemmilestonelog.preset_type_builtin";
                case FILE -> "message.itemmilestonelog.preset_type_file";
                case DATAPACK -> "message.itemmilestonelog.preset_type_datapack";
            });
            // F54: custom names resolve in the source player's language (en_us priority for
            // console / RCON); built-in rows keep showing the raw ID as before.
            String display = p.builtin()
                    ? p.id()
                    : p.resolveDisplayName(MilestoneService.clientLanguage(source.getPlayer()));
            source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.presets_entry",
                    display, p.id(), p.items().size(), typeLabel), false);
        }
        return all.size();
    }

    private static int presetSaveMissingName(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!Config.PRESETS_ENABLED.get()) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        if (!MilestoneService.canManagePresets(source)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_no_permission"));
        }
        return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_invalid_name", ""));
    }

    private static int presetDeleteMissingName(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!Config.PRESETS_ENABLED.get()) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        if (!MilestoneService.canManagePresets(source)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_no_permission"));
        }
        return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_unknown",
                "", String.join(", ", Presets.names())));
    }

    private static int presetSave(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        MilestoneService.ActionResult result = MilestoneService.savePreset(player, name);
        if (result.success()) {
            MilestoneService.syncAllOnlineGuiPlayers(player.level().getServer(), null);
            return send(ctx, result.message());
        }
        return fail(ctx, result.message());
    }

    private static int presetDelete(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSourceStack source = ctx.getSource();
        MilestoneService.ActionResult result = MilestoneService.deletePreset(source, name);
        if (result.success()) {
            if (source.getServer() != null) {
                MilestoneService.syncAllOnlineGuiPlayers(source.getServer(), null);
            }
            return send(ctx, result.message());
        }
        return fail(ctx, result.message());
    }

    private static int presetReload(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!Config.PRESETS_ENABLED.get()) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        if (!MilestoneService.canManagePresets(source)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_no_permission"));
        }
        int custom = Presets.reload();
        if (source.getServer() != null) {
            MilestoneService.syncAllOnlineGuiPlayers(source.getServer(), null);
        }
        return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_reloaded", Presets.names().size(), custom));
    }

    /**
     * F-150-40 (N178): add/remove one item of a config-folder preset. Only custom (file) presets
     * can be edited, and a protected one refuses every edit. The existing strict validation runs
     * unchanged inside {@link Presets#saveCustom}; no GUI or permission node is introduced.
     */
    private static int presetEdit(CommandContext<CommandSourceStack> ctx, String name, String itemId,
                                  int count, boolean remove) {
        CommandSourceStack source = ctx.getSource();
        if (!Config.PRESETS_ENABLED.get()) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        if (!MilestoneService.canManagePresets(source)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_no_permission"));
        }
        Presets.PresetDefinition preset = Presets.get(name);
        if (preset == null) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_unknown",
                    name, String.join(", ", Presets.names())));
        }
        if (preset.kind() == Presets.PresetKind.BUILTIN) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_edit_builtin"));
        }
        if (preset.kind() == Presets.PresetKind.DATAPACK) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_datapack_protected", name));
        }
        if (!PresetEditLogic.canEdit(PresetEditLogic.Kind.FILE, preset.isProtected())) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_protected", name));
        }
        TargetKey key = TargetKey.of(itemId, count);
        if (key == null || !MilestoneService.isValidItemId(key.itemId())) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_unknown",
                    itemId, String.join(", ", Presets.names())));
        }
        List<String> encoded = new ArrayList<>();
        for (TargetKey existing : preset.items()) {
            encoded.add(existing.toEncodedString());
        }
        String target = key.toEncodedString();
        List<String> updated = remove
                ? (count > TargetKey.MIN_COUNT
                        ? PresetEditLogic.removeEncoded(encoded, target)
                        : PresetEditLogic.removeItemId(encoded, key.itemId()))
                : PresetEditLogic.addEncoded(encoded, target);
        List<TargetKey> items = new ArrayList<>();
        for (String raw : updated) {
            TargetKey parsed = TargetKey.tryParseEncoded(raw);
            if (parsed != null) {
                items.add(parsed);
            }
        }
        try {
            Presets.saveCustom(name, items);
        } catch (IOException | IllegalArgumentException e) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_save_failed", name));
        }
        if (source.getServer() != null) {
            MilestoneService.syncAllOnlineGuiPlayers(source.getServer(), null);
        }
        Component display = MilestoneService.presetDisplayName(source.getPlayer(),
                Presets.get(name) != null ? Presets.get(name) : preset);
        return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_edit_done",
                display, items.size()));
    }

    /** F-150-40 (N178): two-step protect/unprotect of a config-folder preset. */
    private static int presetProtect(CommandContext<CommandSourceStack> ctx, String name,
                                     boolean protect, boolean confirm) {
        CommandSourceStack source = ctx.getSource();
        if (!Config.PRESETS_ENABLED.get()) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        if (!MilestoneService.canManagePresets(source)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_no_permission"));
        }
        Presets.PresetDefinition preset = Presets.get(name);
        if (preset == null) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_unknown",
                    name, String.join(", ", Presets.names())));
        }
        if (preset.kind() == Presets.PresetKind.BUILTIN) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_builtin_protected", name));
        }
        if (preset.kind() == Presets.PresetKind.DATAPACK) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_datapack_protected", name));
        }
        Component display = MilestoneService.presetDisplayName(source.getPlayer(), preset);
        // F-150-40: `protect` applies immediately; only `unprotect` is two-step (confirm).
        if (!protect && !confirm) {
            return send(ctx, ServerLang.tr(source,
                    "message.itemmilestonelog.preset_unprotect_confirm", display));
        }
        try {
            if (!Presets.setProtected(Presets.presetsDir(), name, protect)) {
                return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_save_failed", name));
            }
        } catch (IOException e) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_save_failed", name));
        }
        if (source.getServer() != null) {
            MilestoneService.syncAllOnlineGuiPlayers(source.getServer(), null);
        }
        return send(ctx, ServerLang.tr(source, protect
                ? "message.itemmilestonelog.preset_protect_done"
                : "message.itemmilestonelog.preset_unprotect_done", display));
    }

    private static int reset(CommandContext<CommandSourceStack> ctx, String itemId, int count) throws CommandSyntaxException {
        return respondWithUndoHint(ctx, MilestoneService.resetRecord(player(ctx), itemId, count));
    }

    private static int resetAll(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return respondWithUndoHint(ctx, MilestoneService.resetAllRecords(player(ctx)));
    }

    /** F23: restores the executing player's last reset or bulk removal from the past 10 minutes. */
    private static int undo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return respond(ctx, MilestoneService.undo(player(ctx)));
    }

    /** Like {@link #respond}, then adds the F23 {@code undo_hint} line when the operation stored an undo snapshot. */
    private static int respondWithUndoHint(CommandContext<CommandSourceStack> ctx, MilestoneService.ActionResult result) {
        int status = respond(ctx, result);
        if (result.success() && result.undoable()) {
            send(ctx, ServerLang.tr(ctx.getSource(), "message.itemmilestonelog.undo_hint"));
        }
        return status;
    }

    // --------------------------------------------------------- F4 world firsts

    private static int firstsList(CommandContext<CommandSourceStack> ctx, @Nullable Integer page) {
        CommandSourceStack source = ctx.getSource();
        MilestoneData data = MilestoneData.get(source.getServer());
        Map<TargetKey, WorldFirstRecord> map = data.worldFirsts();
        if (map.isEmpty()) {
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.firsts_empty"));
        }
        List<WorldFirstRecord> sorted = new ArrayList<>(map.values());
        sorted.sort(Comparator.comparingLong(WorldFirstRecord::epochMillis)
                .thenComparingInt(WorldFirstRecord::playTicks)
                .thenComparing(WorldFirstRecord::targetKey));

        Pagination.PageSlice<WorldFirstRecord> slice = null;
        List<WorldFirstRecord> displayed;
        if (page != null) {
            slice = Pagination.slice(sorted, page);
            if (slice.isOutOfRange()) {
                return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.page_out_of_range",
                        page, slice.totalPages()));
            }
            displayed = slice.items();
        } else {
            displayed = sorted;
        }

        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.firsts_header", sorted.size()), false);
        ZoneId zone = Config.displayTimeZone();
        for (WorldFirstRecord wf : displayed) {
            // F47: prefer the holder's current (last known) name; stored data is unchanged.
            MilestoneData.PlayerData holder = data.playerOrNull(wf.playerUuid());
            WorldFirstNames.Choice choice = WorldFirstNames.choose(wf.playerUuid(), wf.playerName(),
                    holder != null, holder != null ? holder.lastKnownName : null);
            Component playerName = choice.renamed()
                    ? ServerLang.tr(source, "message.itemmilestonelog.firsts_name_renamed",
                            Component.literal(choice.name()), Component.literal(choice.recordedName()))
                    : Component.literal(choice.name());
            source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.firsts_entry",
                    MilestoneService.targetDisplayName(wf.targetKey()),
                    playerName,
                    Playtime.formatRecordComponent(source, wf.toMilestoneRecord(), zone)), false);
        }
        if (slice != null) {
            sendPageFooter(source, slice, Pagination.firstsCommandPrefix());
        }
        return displayed.size();
    }

    // --------------------------------------------------------- F-150-4..6 rankings

    /** F-150-4 (N03): per-preset ranking. Same gate as {@code top} plus the presets feature switch. */
    private static int presettop(CommandContext<CommandSourceStack> ctx, String presetId, @Nullable Integer page) {
        CommandSourceStack source = ctx.getSource();
        if (!Config.PRESETS_ENABLED.get() || !canUseRankings(source)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        Presets.PresetDefinition preset = Presets.get(presetId);
        if (preset == null) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_unknown",
                    presetId, String.join(", ", Presets.names())));
        }
        MilestoneData data = MilestoneData.get(source.getServer());
        MilestoneRankings.Viewer viewer = rankingViewer(source);
        List<RankingStatsModel.PresetPlayer> players = new ArrayList<>();
        for (Map.Entry<UUID, MilestoneData.PlayerData> e : data.allPlayers().entrySet()) {
            MilestoneData.PlayerData pd = e.getValue();
            if (pd == null || !MilestoneRankings.isVisible(viewer, e.getKey(), pd)) {
                continue;
            }
            List<RankingStatsModel.RecordValue> records = new ArrayList<>();
            for (Map.Entry<TargetKey, MilestoneRecord> r : pd.records.entrySet()) {
                records.add(new RankingStatsModel.RecordValue(r.getKey().itemId(), r.getKey().count(),
                        r.getValue().playTicks(), r.getValue().epochMillis()));
            }
            players.add(new RankingStatsModel.PresetPlayer(e.getKey(),
                    MilestoneRankings.displayName(e.getKey(), pd), records,
                    pd.completedPresets.contains(presetId)));
        }
        List<RankingStatsModel.TargetRef> targets = new ArrayList<>();
        for (TargetKey key : preset.items()) {
            targets.add(new RankingStatsModel.TargetRef(key.itemId(), key.count()));
        }
        List<RankingStatsModel.PresetRankEntry> ranking =
                RankingStatsModel.presetRanking(presetId, targets, players);
        if (ranking.isEmpty()) {
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.presettop_empty"));
        }

        Pagination.PageSlice<RankingStatsModel.PresetRankEntry> slice = null;
        List<RankingStatsModel.PresetRankEntry> displayed;
        if (page != null) {
            slice = Pagination.slice(ranking, page);
            if (slice.isOutOfRange()) {
                return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.page_out_of_range",
                        page, slice.totalPages()));
            }
            displayed = slice.items();
        } else {
            displayed = ranking.subList(0, Math.min(MilestoneRankings.TOP_LIMIT, ranking.size()));
        }

        // The single header slot for the preset carries both the display name and its ID, as in
        // `preset list` (the other slot is the pre-slice player count; see progress report).
        Component presetName = MilestoneService.presetDisplayNameForLang(
                MilestoneService.clientLanguage(source.getPlayer()), preset)
                .copy().append(" [").append(presetId).append("]");
        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.presettop_header",
                presetName, ranking.size()), false);
        for (RankingStatsModel.PresetRankEntry e : displayed) {
            RankingStatsModel.PresetCandidate c = e.candidate();
            String key = c.completed()
                    ? "message.itemmilestonelog.presettop_entry_done"
                    : "message.itemmilestonelog.presettop_entry";
            source.sendSuccess(() -> ServerLang.tr(source, key,
                    e.rank(), Component.literal(c.name()), c.recorded(), c.total()), false);
        }
        ServerPlayer viewerPlayer = source.getPlayer();
        if (viewerPlayer != null) {
            RankingStatsModel.PresetRankEntry self = null;
            for (RankingStatsModel.PresetRankEntry e : ranking) {
                if (viewerPlayer.getUUID().equals(e.candidate().uuid())) {
                    self = e;
                    break;
                }
            }
            Component selfLine = self == null
                    ? ServerLang.tr(source, "message.itemmilestonelog.presettop_self_none")
                    : ServerLang.tr(source, "message.itemmilestonelog.presettop_self",
                            self.rank(), self.candidate().recorded(), self.candidate().total());
            source.sendSuccess(() -> selfLine, false);
        }
        if (slice != null) {
            sendPageFooter(source, slice, Pagination.presettopCommandPrefix(presetId));
        }
        return displayed.size();
    }

    /** F-150-5 (N04): public world-first holder counts; no lookup gate and no hideFromLookup filter. */
    private static int firstsTop(CommandContext<CommandSourceStack> ctx, @Nullable Integer page) {
        CommandSourceStack source = ctx.getSource();
        MilestoneData data = MilestoneData.get(source.getServer());
        Map<UUID, Component> names = new HashMap<>();
        List<RankingStatsModel.WorldFirstValue> values = new ArrayList<>();
        for (WorldFirstRecord wf : data.worldFirsts().values()) {
            MilestoneData.PlayerData holder = data.playerOrNull(wf.playerUuid());
            WorldFirstNames.Choice choice = WorldFirstNames.choose(wf.playerUuid(), wf.playerName(),
                    holder != null, holder != null ? holder.lastKnownName : null);
            Component name = choice.renamed()
                    ? ServerLang.tr(source, "message.itemmilestonelog.firsts_name_renamed",
                            Component.literal(choice.name()), Component.literal(choice.recordedName()))
                    : Component.literal(choice.name());
            names.putIfAbsent(wf.playerUuid(), name);
            values.add(new RankingStatsModel.WorldFirstValue(wf.playerUuid(), choice.name(),
                    wf.playTicks(), wf.epochMillis()));
        }
        List<RankingStatsModel.FirstCountEntry> ranking = RankingStatsModel.firstsTopByCount(values);
        if (ranking.isEmpty()) {
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.firsts_top_empty"));
        }

        Pagination.PageSlice<RankingStatsModel.FirstCountEntry> slice = null;
        List<RankingStatsModel.FirstCountEntry> displayed;
        if (page != null) {
            slice = Pagination.slice(ranking, page);
            if (slice.isOutOfRange()) {
                return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.page_out_of_range",
                        page, slice.totalPages()));
            }
            displayed = slice.items();
        } else {
            displayed = ranking.subList(0, Math.min(MilestoneRankings.TOP_LIMIT, ranking.size()));
        }

        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.firsts_top_header",
                ranking.size()), false);
        for (RankingStatsModel.FirstCountEntry e : displayed) {
            Component name = names.getOrDefault(e.uuid(), Component.literal(e.name()));
            source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.firsts_top_entry",
                    e.rank(), name, e.count()), false);
        }
        if (slice != null) {
            sendPageFooter(source, slice, Pagination.firstsTopCommandPrefix());
        }
        return displayed.size();
    }

    /**
     * F-150-38 (N167): past holders of one world-first target, newest first. The Brigadier branch
     * already applies {@code Gate.LOOKUP}; the handler repeats the gate so direct calls and future
     * aliases cannot leak another player's personal record. Hidden players are excluded exactly
     * as in {@link MilestoneRankings#isVisible}.
     */
    private static int firstsHistory(CommandContext<CommandSourceStack> ctx, String itemId, int count,
                                     @Nullable Integer page) {
        CommandSourceStack source = ctx.getSource();
        if (!canUseRankings(source)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        TargetKey key = TargetKey.of(itemId, count);
        if (key == null) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.firsts_not_recorded", itemId));
        }
        String encodedKey = key.toEncodedString();
        MilestoneData data = MilestoneData.get(source.getServer());
        MilestoneRankings.Viewer viewer = rankingViewer(source);
        List<HolderEntry> visible = new ArrayList<>();
        for (HolderEntry entry : FirstsHolderHistory.newestFirst(data.holderHistory(encodedKey))) {
            UUID uuid;
            try {
                uuid = UUID.fromString(entry.uuid());
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            MilestoneData.PlayerData holder = data.playerOrNull(uuid);
            if (holder != null && !MilestoneRankings.isVisible(viewer, uuid, holder)) {
                continue;
            }
            visible.add(entry);
        }

        Component label = MilestoneService.targetDisplayName(key);
        if (visible.isEmpty()) {
            source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.firsts_history_header",
                    label, 0), false);
            send(ctx, ServerLang.tr(source, "message.itemmilestonelog.firsts_history_empty", label));
            send(ctx, ServerLang.tr(source, "message.itemmilestonelog.firsts_history_hint"));
            return 1;
        }

        Pagination.PageSlice<HolderEntry> slice = null;
        List<HolderEntry> displayed;
        if (page != null) {
            slice = Pagination.slice(visible, page);
            if (slice.isOutOfRange()) {
                return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.page_out_of_range",
                        page, slice.totalPages()));
            }
            displayed = slice.items();
        } else {
            displayed = visible.subList(0, Math.min(MilestoneRankings.TOP_LIMIT, visible.size()));
        }

        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.firsts_history_header",
                label, visible.size()), false);
        // D-150-93: point at the already-implemented race analysis instead of duplicating N95.
        send(ctx, ServerLang.tr(source, "message.itemmilestonelog.firsts_history_hint"));
        ZoneId zone = Config.displayTimeZone();
        int firstRank = page == null ? 1 : (page - 1) * Pagination.DEFAULT_PAGE_SIZE + 1;
        for (int i = 0; i < displayed.size(); i++) {
            HolderEntry entry = displayed.get(i);
            UUID uuid = UUID.fromString(entry.uuid());
            MilestoneData.PlayerData holder = data.playerOrNull(uuid);
            WorldFirstNames.Choice choice = WorldFirstNames.choose(uuid, entry.name(),
                    holder != null, holder != null ? holder.lastKnownName : null);
            Component name = choice.renamed()
                    ? ServerLang.tr(source, "message.itemmilestonelog.firsts_name_renamed",
                            Component.literal(choice.name()), Component.literal(choice.recordedName()))
                    : Component.literal(choice.name());
            MilestoneRecord record = new MilestoneRecord(entry.playTicks(),
                    MilestoneRecord.UNKNOWN_DAY, entry.epochMillis());
            int rank = firstRank + i;
            source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.firsts_history_entry",
                    rank, name, Playtime.formatRecordComponent(source, record, zone)), false);
        }
        if (slice != null) {
            String prefix = TargetKey.isTagId(itemId)
                    ? Pagination.firstsHistoryTagCommandPrefix(itemId.substring(1), count)
                    : Pagination.firstsHistoryCommandPrefix(itemId, count);
            sendPageFooter(source, slice, prefix);
        }
        return displayed.size();
    }

    /**
     * F-150-10 (N95): one best runner-up per world-first key, ordered by ascending gap.
     * The runner-up is an ordinary personal record, so the lookup gate applies both here and
     * on the Brigadier branch; hidden players are filtered out before the model sees them.
     */
    private static int firstsClose(CommandContext<CommandSourceStack> ctx, @Nullable Integer page) {
        CommandSourceStack source = ctx.getSource();
        if (!canUseRankings(source)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        MilestoneData data = MilestoneData.get(source.getServer());
        MilestoneRankings.Viewer viewer = rankingViewer(source);
        Map<String, Component> winnerNames = new HashMap<>();
        List<RankingStatsModel.CloseWorldFirst> worldFirsts = new ArrayList<>();
        for (Map.Entry<TargetKey, WorldFirstRecord> e : data.worldFirsts().entrySet()) {
            TargetKey key = e.getKey();
            WorldFirstRecord wf = e.getValue();
            MilestoneData.PlayerData holder = data.playerOrNull(wf.playerUuid());
            WorldFirstNames.Choice choice = WorldFirstNames.choose(wf.playerUuid(), wf.playerName(),
                    holder != null, holder != null ? holder.lastKnownName : null);
            Component name = choice.renamed()
                    ? ServerLang.tr(source, "message.itemmilestonelog.firsts_name_renamed",
                            Component.literal(choice.name()), Component.literal(choice.recordedName()))
                    : Component.literal(choice.name());
            winnerNames.putIfAbsent(choice.name(), name);
            worldFirsts.add(new RankingStatsModel.CloseWorldFirst(
                    new RankingStatsModel.TargetRef(key.itemId(), key.count()),
                    wf.playerUuid(), choice.name(), wf.playTicks()));
        }

        List<RankingStatsModel.WhoHasPlayer> players = new ArrayList<>();
        Map<String, Component> runnerNames = new HashMap<>();
        for (Map.Entry<UUID, MilestoneData.PlayerData> e : data.allPlayers().entrySet()) {
            MilestoneData.PlayerData pd = e.getValue();
            if (pd == null || !MilestoneRankings.isVisible(viewer, e.getKey(), pd)) {
                continue;
            }
            List<RankingStatsModel.RecordValue> records = new ArrayList<>();
            for (Map.Entry<TargetKey, MilestoneRecord> r : pd.records.entrySet()) {
                records.add(new RankingStatsModel.RecordValue(r.getKey().itemId(), r.getKey().count(),
                        r.getValue().playTicks(), r.getValue().epochMillis()));
            }
            String displayName = MilestoneRankings.displayName(e.getKey(), pd);
            runnerNames.putIfAbsent(displayName, Component.literal(displayName));
            players.add(new RankingStatsModel.WhoHasPlayer(e.getKey(), displayName, records, List.of()));
        }

        List<RankingStatsModel.CloseEntry> all = RankingStatsModel.firstsClose(worldFirsts, players);
        if (all.isEmpty()) {
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.firsts_close_none"));
        }

        Pagination.PageSlice<RankingStatsModel.CloseEntry> slice = null;
        List<RankingStatsModel.CloseEntry> displayed;
        if (page != null) {
            slice = Pagination.slice(all, page);
            if (slice.isOutOfRange()) {
                return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.page_out_of_range",
                        page, slice.totalPages()));
            }
            displayed = slice.items();
        } else {
            displayed = all.subList(0, Math.min(RankingStatsModel.CLOSE_LIMIT, all.size()));
        }

        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.firsts_close_header",
                all.size()), false);
        for (RankingStatsModel.CloseEntry e : displayed) {
            RankingStatsModel.TargetRef target = e.key();
            Component winner = winnerNames.getOrDefault(e.winnerName(), Component.literal(e.winnerName()));
            Component runner = runnerNames.getOrDefault(e.runnerName(), Component.literal(e.runnerName()));
            source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.firsts_close_entry",
                    MilestoneService.targetDisplayName(new TargetKey(target.itemId(), target.count())),
                    winner, runner, Playtime.format((int) e.gapTicks())), false);
        }
        if (slice != null) {
            sendPageFooter(source, slice, Pagination.firstsCloseCommandPrefix());
        }
        return displayed.size();
    }

    /** Pure gate seam for {@code firsts unclaimed}: presets/all fail while disabled, targets skip presets. */
    static String unclaimedPresetMode(RankingStatsModel.UnclaimedScope scope, boolean presetsEnabled) {
        if (scope == RankingStatsModel.UnclaimedScope.PRESETS
                || scope == RankingStatsModel.UnclaimedScope.ALL) {
            return presetsEnabled ? "include" : "fail";
        }
        return "skip";
    }

    /** F-150-6 (N05): only the caller's own targets/presets plus the public world-first key set. */
    private static int firstsUnclaimed(CommandContext<CommandSourceStack> ctx,
                                       @Nullable String rawScope, @Nullable Integer page)
            throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        CommandSourceStack source = ctx.getSource();
        RankingStatsModel.UnclaimedScope scope = rawScope == null
                ? RankingStatsModel.UnclaimedScope.TARGETS
                : RankingStatsModel.UnclaimedScope.parse(rawScope);
        if (scope == null) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.firsts_unclaimed_invalid_scope",
                    rawScope == null ? "" : rawScope));
        }
        boolean presetsEnabled = Config.PRESETS_ENABLED.get();
        String presetMode = unclaimedPresetMode(scope, presetsEnabled);
        if ("fail".equals(presetMode)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }

        MilestoneData data = MilestoneData.get(source.getServer());
        MilestoneData.PlayerData pd = data.playerOrNull(player.getUUID());
        List<RankingStatsModel.TargetRef> targets = new ArrayList<>();
        if (scope == RankingStatsModel.UnclaimedScope.TARGETS
                || scope == RankingStatsModel.UnclaimedScope.ALL) {
            for (TargetKey key : Tracker.effectiveTargets(pd)) {
                targets.add(new RankingStatsModel.TargetRef(key.itemId(), key.count()));
            }
        }
        List<RankingStatsModel.PresetRef> presets = new ArrayList<>();
        if ("include".equals(presetMode)) {
            for (Presets.PresetDefinition preset : Presets.all()) {
                List<RankingStatsModel.TargetRef> items = new ArrayList<>();
                for (TargetKey key : preset.items()) {
                    items.add(new RankingStatsModel.TargetRef(key.itemId(), key.count()));
                }
                presets.add(new RankingStatsModel.PresetRef(preset.id(), items));
            }
        }
        List<RankingStatsModel.TargetRef> claimed = new ArrayList<>();
        for (TargetKey key : data.worldFirsts().keySet()) {
            claimed.add(new RankingStatsModel.TargetRef(key.itemId(), key.count()));
        }
        RankingStatsModel.UnclaimedResult result =
                RankingStatsModel.unclaimed(scope, targets, presets, claimed);
        if (result.entries().isEmpty()) {
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.firsts_unclaimed_none"));
        }

        Pagination.PageSlice<RankingStatsModel.UnclaimedEntry> slice = null;
        List<RankingStatsModel.UnclaimedEntry> displayed;
        if (page != null) {
            slice = Pagination.slice(result.entries(), page);
            if (slice.isOutOfRange()) {
                return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.page_out_of_range",
                        page, slice.totalPages()));
            }
            displayed = slice.items();
        } else {
            displayed = result.entries();
        }

        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.firsts_unclaimed_header",
                result.entries().size() + result.moreCount()), false);
        for (RankingStatsModel.UnclaimedEntry e : displayed) {
            Component label = MilestoneService.targetDisplayName(new TargetKey(e.itemId(), e.count()));
            if (e.fromPreset()) {
                source.sendSuccess(() -> ServerLang.tr(source,
                        "message.itemmilestonelog.firsts_unclaimed_entry_preset",
                        label, e.presetId() == null ? "" : e.presetId()), false);
            } else {
                source.sendSuccess(() -> ServerLang.tr(source,
                        "message.itemmilestonelog.firsts_unclaimed_entry", label), false);
            }
        }
        if (page == null && result.moreCount() > 0) {
            send(ctx, ServerLang.tr(source, "message.itemmilestonelog.firsts_unclaimed_more",
                    result.moreCount()));
        }
        if (slice != null) {
            sendPageFooter(source, slice, Pagination.firstsUnclaimedCommandPrefix(scope.commandValue()));
        }
        return displayed.size();
    }

    private static int firstsReset(CommandContext<CommandSourceStack> ctx, String itemId, int count) {
        CommandSourceStack source = ctx.getSource();
        MilestoneData data = MilestoneData.get(source.getServer());
        TargetKey key = TargetKey.normalize(itemId, count);
        if (key == null) {
            return fail(ctx, ServerLang.tr(source, MilestoneService.invalidTargetKey(itemId), String.valueOf(itemId)));
        }
        WorldFirstRecord removed = data.worldFirstOrNull(key);
        if (data.removeWorldFirst(key)) {
            if (removed != null) {
                ScoreboardSync.refreshScores(source.getServer(), removed.playerUuid());
            }
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.firsts_reset_done", MilestoneService.targetDisplayName(key)));
        }
        if (!MilestoneService.isValidItemId(key.itemId())) {
            return fail(ctx, ServerLang.tr(source, MilestoneService.invalidTargetKey(itemId), itemId));
        }
        return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.firsts_not_recorded", MilestoneService.targetDisplayName(key)));
    }

    private static int firstsResetAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        int count = MilestoneData.get(source.getServer()).clearWorldFirsts();
        if (count > 0) {
            ScoreboardSync.refreshAll(source.getServer());
        }
        return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.firsts_resetall_done", count));
    }

    // --------------------------------------------------------- F6 export/import

    private static int exportSelf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        if (!Config.EXPORT_IMPORT_ENABLED.get()) {
            return fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.feature_disabled"));
        }
        MilestoneData data = MilestoneData.get(player.level().getServer());
        MilestoneData.PlayerData pd = data.playerOrNull(player.getUUID());
        String playerName = player.getGameProfile().name();
        Path dir = MilestoneExport.exportsDir(player.level().getServer());
        try {
            String relPath = MilestoneExport.exportPlayer(dir, player.getUUID(), playerName, pd);
            return send(ctx, ServerLang.tr(player, "message.itemmilestonelog.export_done", relPath));
        } catch (IOException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Export failed", e);
            return fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.export_failed"));
        }
    }

    private static int importSelf(CommandContext<CommandSourceStack> ctx, String fileName, boolean dryrun) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        if (!Config.EXPORT_IMPORT_ENABLED.get()) {
            return fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.feature_disabled"));
        }
        if (!MilestoneExport.isSafeFileName(fileName)) {
            return fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.import_invalid_name", fileName));
        }
        Path dir = MilestoneExport.exportsDir(player.level().getServer()).normalize();
        Path file = dir.resolve(fileName).normalize();
        if (!file.getParent().equals(dir)) {
            return fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.import_invalid_name", fileName));
        }
        if (!Files.isRegularFile(file)) {
            return fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.import_not_found", fileName));
        }
        try {
            MilestoneExport.ImportPayload payload = MilestoneExport.parseSinglePlayerExport(file);
            CommandSourceStack source = ctx.getSource();
            if (!MilestoneExport.canImport(payload, player.getUUID(),
                    MilestonePermissions.check(source, MilestonePermissions.IMPORT_ALL,
                            Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source)))) {
                return fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.import_not_owner", fileName));
            }
            MinecraftServer server = player.level().getServer();
            MilestoneData data = MilestoneData.get(server);
            if (dryrun) {
                MilestoneData.PlayerData pd = data.playerOrNull(player.getUUID());
                Map<CommandAnalysisModel.TargetRef, Integer> currentRecords = new LinkedHashMap<>();
                Map<CommandAnalysisModel.TargetRef, Integer> importedRecords = new LinkedHashMap<>();
                if (pd != null) {
                    for (Map.Entry<TargetKey, MilestoneRecord> e : pd.records.entrySet()) {
                        currentRecords.put(new CommandAnalysisModel.TargetRef(e.getKey().itemId(), e.getKey().count()),
                                e.getValue().playTicks());
                    }
                }
                for (Map.Entry<TargetKey, MilestoneRecord> e : payload.records().entrySet()) {
                    importedRecords.put(new CommandAnalysisModel.TargetRef(e.getKey().itemId(), e.getKey().count()),
                            e.getValue().playTicks());
                }
                List<CommandAnalysisModel.TargetRef> importedTargets = new ArrayList<>();
                for (TargetKey key : payload.targets()) {
                    importedTargets.add(new CommandAnalysisModel.TargetRef(key.itemId(), key.count()));
                }
                Set<CommandAnalysisModel.TargetRef> importedFirsts = new LinkedHashSet<>();
                return sendDryRun(ctx, fileName, CommandAnalysisModel.importPreview(
                        personalRefs(pd), importedTargets, currentRecords, importedRecords,
                        Set.of(), importedFirsts, MilestoneService.MAX_PERSONAL_TARGETS));
            }
            MilestoneExport.ImportMergeStats stats = MilestoneExport.mergeInto(data, player.getUUID(), payload);
            ScoreboardSync.refreshScores(server, player.getUUID());
            MilestoneService.syncPlayerIfOpen(player);
            return send(ctx, ServerLang.tr(player, "message.itemmilestonelog.import_done",
                    fileName, stats.addedTargets(), stats.updatedRecords()));
        } catch (IllegalArgumentException e) {
            return fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.import_invalid_format", fileName));
        } catch (IOException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Import of {} failed", fileName, e);
            return fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.import_failed", fileName));
        }
    }

    private static int exportAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!Config.EXPORT_IMPORT_ENABLED.get()) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        MinecraftServer server = source.getServer();
        MilestoneData data = MilestoneData.get(server);
        Path dir = MilestoneExport.exportsDir(server);
        try {
            String relPath = MilestoneExport.exportAll(dir, data);
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.exportall_done",
                    data.allPlayers().size(), relPath));
        } catch (IOException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Export-all failed", e);
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.export_failed"));
        }
    }

    private static int importAll(CommandContext<CommandSourceStack> ctx, String fileName, boolean dryrun) {
        if (dryrun) {
            return importAllDryRun(ctx, fileName);
        }
        // F-140-3 (N45): validation and two-stage feedback live in MilestoneImportAll; parsing and
        // merge-plan construction run on the I/O executor, application on the server thread.
        return MilestoneImportAll.run(ctx.getSource(), fileName);
    }

    private static int exportHtmlSelf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        if (!Config.EXPORT_IMPORT_ENABLED.get()) {
            return fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.feature_disabled"));
        }
        MilestoneData data = MilestoneData.get(player.level().getServer());
        MilestoneData.PlayerData pd = data.playerOrNull(player.getUUID());
        String playerName = player.getGameProfile().name();
        Path dir = MilestoneExport.exportsDir(player.level().getServer());
        try {
            String relPath = MilestoneExport.exportPlayerHtml(dir, player.getUUID(), playerName, pd, data);
            return send(ctx, ServerLang.tr(player, "message.itemmilestonelog.export_done", relPath));
        } catch (IOException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] HTML export failed", e);
            return fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.export_failed"));
        }
    }

    private static int exportHtmlAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!Config.EXPORT_IMPORT_ENABLED.get()) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        MinecraftServer server = source.getServer();
        MilestoneData data = MilestoneData.get(server);
        Path dir = MilestoneExport.exportsDir(server);
        try {
            String relPath = MilestoneExport.exportAllHtml(dir, data);
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.exportall_done",
                    data.allPlayers().size(), relPath));
        } catch (IOException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] HTML export-all failed", e);
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.export_failed"));
        }
    }

    private static int exportCsvSelf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        if (!Config.EXPORT_IMPORT_ENABLED.get()) {
            return fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.feature_disabled"));
        }
        MilestoneData data = MilestoneData.get(player.level().getServer());
        MilestoneData.PlayerData pd = data.playerOrNull(player.getUUID());
        String playerName = player.getGameProfile().name();
        Path dir = MilestoneExport.exportsDir(player.level().getServer());
        try {
            String relPath = MilestoneExport.exportPlayerCsv(dir, player.getUUID(), playerName, pd, data);
            return send(ctx, ServerLang.tr(player, "message.itemmilestonelog.export_done", relPath));
        } catch (IOException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] CSV export failed", e);
            return fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.export_failed"));
        }
    }

    private static int exportCsvAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!Config.EXPORT_IMPORT_ENABLED.get()) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        MinecraftServer server = source.getServer();
        MilestoneData data = MilestoneData.get(server);
        Path dir = MilestoneExport.exportsDir(server);
        try {
            String relPath = MilestoneExport.exportAllCsv(dir, data);
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.exportall_done",
                    data.allPlayers().size(), relPath));
        } catch (IOException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] CSV export-all failed", e);
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.export_failed"));
        }
    }

    // --------------------------------------------------------- F11 mute broadcast

    private static int muteBroadcast(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        MilestoneData data = MilestoneData.get(player.level().getServer());
        MilestoneData.PlayerData pd = data.player(player.getUUID());
        pd.muteBroadcasts = !pd.muteBroadcasts;
        data.setDirty();
        String key = pd.muteBroadcasts
                ? "message.itemmilestonelog.mutebroadcast_on"
                : "message.itemmilestonelog.mutebroadcast_off";
        return send(ctx, ServerLang.tr(player, key));
    }

    // --------------------------------------------------------- F15 mute progress alerts

    private static int muteProgress(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        MilestoneData data = MilestoneData.get(player.level().getServer());
        MilestoneData.PlayerData pd = data.player(player.getUUID());
        pd.muteProgressAlerts = !pd.muteProgressAlerts;
        data.setDirty();
        String key = pd.muteProgressAlerts
                ? "message.itemmilestonelog.muteprogress_on"
                : "message.itemmilestonelog.muteprogress_off";
        return send(ctx, ServerLang.tr(player, key));
    }

    /** N116: bare query is read-only; a data-less player follows the server default (SF-9/R2-SF-1). */
    private static int progressDestQuery(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        NotificationDestinationLogic.Choice choice = progressDestChoiceFor(
                MilestoneData.get(player.level().getServer()), player.getUUID());
        return send(ctx, ServerLang.tr(player, "message.itemmilestonelog.progressdest_current",
                NotificationDestinationLogic.label(choice),
                NotificationDestinationLogic.label(Config.progressAlertDestination())));
    }

    private static int progressDestSet(CommandContext<CommandSourceStack> ctx,
                                       NotificationDestinationLogic.Choice choice) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        MilestoneData data = MilestoneData.get(player.level().getServer());
        MilestoneData.PlayerData pd = data.player(player.getUUID());
        pd.progressAlertDestination = choice.name();
        data.setDirty();
        return send(ctx, ServerLang.tr(player, "message.itemmilestonelog.progressdest_set",
                NotificationDestinationLogic.label(choice)));
    }

    /** Read-only view of a player's choice; never inserts or dirties an empty PlayerData. */
    static NotificationDestinationLogic.Choice progressDestChoiceFor(MilestoneData data, UUID playerId) {
        MilestoneData.PlayerData pd = data.playerOrNull(playerId);
        return NotificationDestinationLogic.parseChoice(pd != null ? pd.progressAlertDestination : null);
    }

    // --------------------------------------------------------- F45 Discord opt-out

    private static int discordOptOut(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return respond(ctx, MilestoneService.toggleDiscordOptOut(player(ctx)));
    }

    // --------------------------------------------------------- F14 privacy (hide from lookup/top/recent/stats)

    private static int privacy(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return respond(ctx, MilestoneService.togglePrivacy(player(ctx)));
    }

    // --------------------------------------------------------- F49 Discord test

    // MN-3 test seams; all null in production.
    @Nullable
    static Consumer<Runnable> discordTestSchedulerForTest;
    @Nullable
    static Boolean discordTestOnServerThreadForTest;
    @Nullable
    static Boolean discordExecutorAliveForTest;
    @Nullable
    static Consumer<DiscordTestResult> discordTestAbandonedForTest;

    static void resetDiscordTestHooksForTest() {
        discordTestSchedulerForTest = null;
        discordTestOnServerThreadForTest = null;
        discordExecutorAliveForTest = null;
        discordTestAbandonedForTest = null;
    }

    private static int discordTest(CommandContext<CommandSourceStack> ctx, DiscordRouting.Destination destination) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        ServerPlayer executorPlayer = source.getPlayer();
        UUID executorUuid = executorPlayer != null ? executorPlayer.getUUID() : null;

        DiscordTestResult immediateError = DiscordNotifier.sendTestMessage(
                destination,
                result -> handOffDiscordTestResult(server, source, executorPlayer, executorUuid, result),
                result -> abandonDiscordTestResult(server, source, executorPlayer, executorUuid,
                        "Discord sender shut down before completion"));
        if (immediateError != null) {
            return fail(ctx, formatDiscordTestResult(source.getPlayer(), immediateError));
        }
        return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.discordtest_sending"));
    }

    /**
     * MN-3: hand the completion to the server thread, never apply it inline on the Discord thread.
     * {@code MinecraftServer.execute} runs a task inline during shutdown, so the runnable checks
     * thread ownership before touching the player list or the command source. When no delivery is
     * possible an explicit ABANDONED outcome is logged instead of dropping the result silently.
     */
    static void handOffDiscordTestResult(
            @Nullable MinecraftServer server,
            @Nullable CommandSourceStack source,
            @Nullable ServerPlayer executorPlayer,
            @Nullable UUID executorUuid,
            DiscordTestResult result) {
        handOffDiscordTestResult(server, source, executorPlayer, executorUuid, result,
                "completion could not be delivered");
    }

    /**
     * R2-SF-2: the {@code onAbandoned} callback from the Discord sender. The abandoned result takes
     * the same strictly server-thread handoff as every other result; when the source is gone the
     * delivery runnable keeps the log-only path instead.
     */
    static void abandonDiscordTestResult(
            @Nullable MinecraftServer server,
            @Nullable CommandSourceStack source,
            @Nullable ServerPlayer executorPlayer,
            @Nullable UUID executorUuid,
            String reason) {
        handOffDiscordTestResult(server, source, executorPlayer, executorUuid,
                DiscordTestResult.abandoned(), reason);
    }

    private static void handOffDiscordTestResult(
            @Nullable MinecraftServer server,
            @Nullable CommandSourceStack source,
            @Nullable ServerPlayer executorPlayer,
            @Nullable UUID executorUuid,
            DiscordTestResult result,
            String abandonReason) {
        boolean executorAlive = discordExecutorAliveForTest != null
                ? discordExecutorAliveForTest
                : DiscordNotifier.isExecutorAlive();
        // An abandoned notice no longer needs the Discord sender: only a real server-thread
        // delivery path matters, so a dead executor must not hide the message from the initiator.
        boolean abandonedResult = result.kind() == DiscordTestResult.Kind.ABANDONED;
        if ((server == null && discordTestSchedulerForTest == null)
                || (!abandonedResult && !executorAlive)) {
            logAbandonedDiscordTestResult(result, abandonReason);
            return;
        }
        Runnable delivery = () -> {
            boolean onServerThread = discordTestOnServerThreadForTest != null
                    ? discordTestOnServerThreadForTest
                    : server != null && server.isSameThread();
            if (!onServerThread) {
                logAbandonedDiscordTestResult(result, "inline execution off the server thread");
                return;
            }
            deliverDiscordTestResult(server, source, executorPlayer, executorUuid, result);
        };
        try {
            if (discordTestSchedulerForTest != null) {
                discordTestSchedulerForTest.accept(delivery);
            } else {
                server.execute(delivery);
            }
        } catch (RuntimeException e) {
            logAbandonedDiscordTestResult(result, "server executor rejected delivery");
        }
    }

    /** Logs an explicit abandoned outcome; never touches the source/player from this thread. */
    static void logAbandonedDiscordTestResult(DiscordTestResult result, String reason) {
        DiscordTestResult abandoned = result.kind() == DiscordTestResult.Kind.ABANDONED
                ? result
                : DiscordTestResult.abandoned();
        ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] discordtest result abandoned ({}): {} ({})",
                reason, result.kind(), result.detail());
        if (discordTestAbandonedForTest != null) {
            discordTestAbandonedForTest.accept(abandoned);
        }
    }

    private static void deliverDiscordTestResult(
            MinecraftServer server,
            @Nullable CommandSourceStack source,
            @Nullable ServerPlayer executorPlayer,
            @Nullable UUID executorUuid,
            DiscordTestResult result) {
        if (result.kind() == DiscordTestResult.Kind.ABANDONED) {
            deliverAbandonedDiscordTestResult(source, result);
            return;
        }
        if (executorUuid != null) {
            ServerPlayer online = server.getPlayerList().getPlayer(executorUuid);
            if (online == null) {
                return;
            }
            Component msg = formatDiscordTestResult(online, result);
            if (online == executorPlayer) {
                if (result.isSuccess()) {
                    source.sendSuccess(() -> msg, false);
                } else {
                    source.sendFailure(msg);
                }
            } else {
                online.sendSystemMessage(msg);
            }
            return;
        }
        Component msg = formatDiscordTestResult(null, result);
        if (result.isSuccess()) {
            source.sendSuccess(() -> msg, false);
        } else {
            source.sendFailure(msg);
        }
    }

    /** R2-SF-2: an abandoned result is a failure notice to the initiator, still on the server thread. */
    private static void deliverAbandonedDiscordTestResult(
            @Nullable CommandSourceStack source, DiscordTestResult result) {
        if (source == null) {
            logAbandonedDiscordTestResult(result, "command source no longer exists");
            return;
        }
        source.sendFailure(formatDiscordTestResult(source.getPlayer(), result));
    }

    private static Component formatDiscordTestResult(@Nullable ServerPlayer player, DiscordTestResult result) {
        return switch (result.kind()) {
            case NOT_CONFIGURED -> ServerLang.tr(player, "message.itemmilestonelog.discordtest_not_configured");
            case ENV_MISSING -> ServerLang.tr(player, "message.itemmilestonelog.discordtest_env_missing",
                    Component.literal(result.detail() != null ? result.detail() : ""));
            case INVALID_URL -> ServerLang.tr(player, "message.itemmilestonelog.discordtest_invalid_url");
            case IN_PROGRESS -> ServerLang.tr(player, "message.itemmilestonelog.discordtest_in_progress");
            case SUCCESS -> ServerLang.tr(player, "message.itemmilestonelog.discordtest_success", result.detail());
            case INVALID_WEBHOOK -> ServerLang.tr(player, "message.itemmilestonelog.discordtest_invalid_webhook", result.detail());
            case RATE_LIMITED -> ServerLang.tr(player, "message.itemmilestonelog.discordtest_rate_limited");
            case HTTP_ERROR -> ServerLang.tr(player, "message.itemmilestonelog.discordtest_http_error", result.detail());
            case NETWORK_ERROR -> ServerLang.tr(player, "message.itemmilestonelog.discordtest_network_error", result.detail());
            case ABANDONED -> ServerLang.tr(player, "message.itemmilestonelog.discordtest_abandoned");
        };
    }

    // --------------------------------------------------------- F9 admin

    private record ResolvedPlayer(@Nullable Map.Entry<UUID, MilestoneData.PlayerData> entry, boolean ambiguous) {
    }

    private static ResolvedPlayer resolvePlayerData(MinecraftServer server, String query) {
        MilestoneData data = MilestoneData.get(server);
        Map<UUID, MilestoneData.PlayerData> all = data.allPlayers();
        try {
            UUID uuid = UUID.fromString(query);
            MilestoneData.PlayerData pd = all.get(uuid);
            if (pd != null) {
                return new ResolvedPlayer(Map.entry(uuid, pd), false);
            }
            return new ResolvedPlayer(null, false);
        } catch (IllegalArgumentException ignored) {
        }

        List<Map.Entry<UUID, MilestoneData.PlayerData>> matches = new ArrayList<>();
        for (Map.Entry<UUID, MilestoneData.PlayerData> entry : all.entrySet()) {
            String known = entry.getValue().lastKnownName;
            if (known != null && known.equalsIgnoreCase(query)) {
                matches.add(entry);
            }
        }
        if (matches.size() == 1) {
            return new ResolvedPlayer(matches.get(0), false);
        }
        if (matches.size() > 1) {
            ServerPlayer online = server.getPlayerList().getPlayerByName(query);
            if (online != null) {
                for (Map.Entry<UUID, MilestoneData.PlayerData> m : matches) {
                    if (m.getKey().equals(online.getUUID())) {
                        return new ResolvedPlayer(m, false);
                    }
                }
            }
            return new ResolvedPlayer(null, true);
        }
        ServerPlayer online = server.getPlayerList().getPlayerByName(query);
        if (online != null) {
            MilestoneData.PlayerData pd = all.get(online.getUUID());
            if (pd != null) {
                return new ResolvedPlayer(Map.entry(online.getUUID(), pd), false);
            }
        }
        return new ResolvedPlayer(null, false);
    }

    private static String displayNameFor(UUID uuid, MilestoneData.PlayerData pd) {
        return MilestoneRankings.displayName(uuid, pd);
    }

    private static int adminPlayers(CommandContext<CommandSourceStack> ctx, @Nullable Integer page) {
        CommandSourceStack source = ctx.getSource();
        Map<UUID, MilestoneData.PlayerData> all = MilestoneData.get(source.getServer()).allPlayers();
        if (all.isEmpty()) {
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.admin_players_empty"));
        }
        List<Map.Entry<UUID, MilestoneData.PlayerData>> sorted = new ArrayList<>(all.entrySet());
        sorted.sort(Comparator.comparing(e -> displayNameFor(e.getKey(), e.getValue()), String.CASE_INSENSITIVE_ORDER));

        Pagination.PageSlice<Map.Entry<UUID, MilestoneData.PlayerData>> slice = null;
        List<Map.Entry<UUID, MilestoneData.PlayerData>> displayed;
        if (page != null) {
            slice = Pagination.slice(sorted, page);
            if (slice.isOutOfRange()) {
                return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.page_out_of_range",
                        page, slice.totalPages()));
            }
            displayed = slice.items();
        } else {
            displayed = sorted;
        }

        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.admin_players_header", sorted.size()), false);
        for (Map.Entry<UUID, MilestoneData.PlayerData> e : displayed) {
            String name = displayNameFor(e.getKey(), e.getValue());
            int targetCount = Tracker.effectiveTargets(e.getValue()).size();
            int recordCount = e.getValue().records.size();
            source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.admin_players_entry",
                    name, e.getKey().toString(), targetCount, recordCount), false);
        }
        if (slice != null) {
            sendPageFooter(source, slice, Pagination.adminPlayersCommandPrefix());
        }
        return displayed.size();
    }

    @Nullable
    private static Map.Entry<UUID, MilestoneData.PlayerData> resolveOrReport(CommandSourceStack source, String query) {
        ResolvedPlayer resolved = resolvePlayerData(source.getServer(), query);
        if (resolved.ambiguous()) {
            source.sendFailure(ServerLang.tr(source, "message.itemmilestonelog.admin_player_ambiguous", query));
            return null;
        }
        if (resolved.entry() == null) {
            source.sendFailure(ServerLang.tr(source, "message.itemmilestonelog.admin_player_not_found", query));
            return null;
        }
        return resolved.entry();
    }

    private static int adminList(CommandContext<CommandSourceStack> ctx, String query, @Nullable Integer page) {
        CommandSourceStack source = ctx.getSource();
        Map.Entry<UUID, MilestoneData.PlayerData> target = resolveOrReport(source, query);
        if (target == null) {
            return 0;
        }
        return sendRecordList(ctx, target, page, Pagination.adminListCommandPrefix(query));
    }

    // --------------------------------------------------------- F6 lookup

    /** Names offered for {@code lookup <player>}: only targets this source may actually look up (F6 / F14). */
    private static Set<String> lookupSuggestionNames(CommandSourceStack source, MinecraftServer server) {
        boolean allowLookup = Config.ALLOW_LOOKUP.get();
        boolean privileged = canViewHidden(source);
        ServerPlayer viewer = source.getPlayer();
        UUID viewerId = viewer != null ? viewer.getUUID() : null;
        Set<String> names = new LinkedHashSet<>();
        for (Map.Entry<UUID, MilestoneData.PlayerData> e : MilestoneData.get(server).allPlayers().entrySet()) {
            boolean self = e.getKey().equals(viewerId);
            if (LookupPolicy.shouldSuggest(allowLookup, privileged, self, e.getValue().isHiddenFromLookup())) {
                names.add(displayNameFor(e.getKey(), e.getValue()));
            }
        }
        return names;
    }

    private static int lookup(CommandContext<CommandSourceStack> ctx, String query, @Nullable Integer page) {
        CommandSourceStack source = ctx.getSource();
        boolean allowLookup = Config.ALLOW_LOOKUP.get();
        boolean privileged = canViewHidden(source);
        ServerPlayer viewer = source.getPlayer();
        Map.Entry<UUID, MilestoneData.PlayerData> target;
        if (!LookupPolicy.canLookUpOthers(allowLookup, privileged)) {
            // Only a self lookup can succeed. Resolve silently so not-found / ambiguous replies
            // do not reveal which names have played on this server.
            ResolvedPlayer resolved = resolvePlayerData(source.getServer(), query);
            if (viewer == null || resolved.entry() == null || !resolved.entry().getKey().equals(viewer.getUUID())) {
                return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
            }
            target = resolved.entry();
        } else {
            target = resolveOrReport(source, query);
            if (target == null) {
                return 0;
            }
        }
        boolean self = viewer != null && viewer.getUUID().equals(target.getKey());
        return switch (LookupPolicy.decide(allowLookup, privileged, self, target.getValue().isHiddenFromLookup())) {
            case DISABLED -> fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
            case HIDDEN -> fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.lookup_hidden",
                    displayNameFor(target.getKey(), target.getValue())));
            case ALLOWED -> sendRecordList(ctx, target, page, Pagination.lookupCommandPrefix(query));
        };
    }

    // --------------------------------------------------------- F13 top / recent

    /**
     * F43 viewhidden: the F6 / F14 privilege (the old {@code isPrivileged} check) or the
     * {@code itemmilestonelog.viewhidden} node for player sources.
     */
    private static boolean canViewHidden(CommandSourceStack source) {
        return MilestonePermissions.check(source, MilestonePermissions.VIEWHIDDEN,
                MilestoneService.isPrivileged(source));
    }

    /** Pure gate seam for tests: ranking queries need {@code allowLookup} or the viewhidden privilege. */
    static boolean canUseRankings(boolean allowLookup, boolean privileged) {
        return LookupPolicy.canLookUpOthers(allowLookup, privileged);
    }

    /**
     * Brigadier requirement for {@code firsts close}. Unit tests build the tree with a null
     * source (no server or permission nodes), so that case uses the shipped config default;
     * every real command source uses the live {@code allowLookup} / viewhidden gate.
     */
    private static boolean canUseRankingsForTree(CommandSourceStack source) {
        return source == null
                ? canUseRankings(Config.ALLOW_LOOKUP.getDefault(), false)
                : canUseRankings(source);
    }

    /** Same gate as F6 lookup of other players: {@code allowLookup}, or the viewhidden privilege when it is off. */
    private static boolean canUseRankings(CommandSourceStack source) {
        return canUseRankings(Config.ALLOW_LOOKUP.get(), canViewHidden(source));
    }

    private static MilestoneRankings.Viewer rankingViewer(CommandSourceStack source) {
        ServerPlayer viewer = source.getPlayer();
        return new MilestoneRankings.Viewer(viewer != null ? viewer.getUUID() : null, canViewHidden(source));
    }

    private static int top(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!canUseRankings(source)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        MilestoneRankings.Viewer viewer = rankingViewer(source);
        MilestoneRankings.Ranking<MilestoneRankings.CountEntry> ranking =
                MilestoneRankings.topByCount(MilestoneData.get(source.getServer()).allPlayers(), viewer);
        if (ranking.entries().isEmpty()) {
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.top_empty"));
        }
        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.top_header", ranking.entries().size()), false);
        for (MilestoneRankings.CountEntry e : ranking.entries().subList(0, Math.min(MilestoneRankings.TOP_LIMIT, ranking.entries().size()))) {
            source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.top_entry",
                    e.rank(), Component.literal(e.name()), e.count()), false);
        }
        if (viewer.uuid() != null) {
            MilestoneRankings.CountEntry self = ranking.self();
            Component selfLine = self != null
                    ? ServerLang.tr(source, "message.itemmilestonelog.top_self", self.rank(), self.count())
                    : ServerLang.tr(source, "message.itemmilestonelog.top_self_none");
            source.sendSuccess(() -> selfLine, false);
        }
        return ranking.entries().size();
    }

    private static int topItem(CommandContext<CommandSourceStack> ctx, String itemId, int count) {
        return topItem(ctx, itemId, count, false);
    }

    /** F-150-8 (N91): {@code gaps == false} keeps the legacy rows byte-for-byte unchanged. */
    private static int topItem(CommandContext<CommandSourceStack> ctx, String itemId, int count, boolean gaps) {
        CommandSourceStack source = ctx.getSource();
        if (!canUseRankings(source)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        TargetKey key = TargetKey.normalize(itemId, count);
        if (key == null) {
            return fail(ctx, ServerLang.tr(source, MilestoneService.invalidTargetKey(itemId), String.valueOf(itemId)));
        }
        MilestoneRankings.Viewer viewer = rankingViewer(source);
        MilestoneRankings.Ranking<MilestoneRankings.ItemEntry> ranking =
                MilestoneRankings.topByItem(MilestoneData.get(source.getServer()).allPlayers(), key, viewer);
        // Gaps are computed from the full visibility-filtered ranking, never from world-first data
        // or from hidden players (D-150-26). The self index survives truncation of the display.
        List<RankingStatsModel.GapRow> gapRows = new ArrayList<>();
        int selfIndex = -1;
        if (gaps) {
            for (int i = 0; i < ranking.entries().size(); i++) {
                MilestoneRankings.ItemEntry row = ranking.entries().get(i);
                gapRows.add(new RankingStatsModel.GapRow(row.rank(), row.uuid(), row.name(),
                        row.record().playTicks()));
                if (ranking.self() != null && row.uuid().equals(ranking.self().uuid())) {
                    selfIndex = i;
                }
            }
        }
        RankingStatsModel.GapResult gapResult = gaps
                ? RankingStatsModel.gapsFor(gapRows, selfIndex)
                : null;
        if (ranking.entries().isEmpty()) {
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.top_empty"));
        }
        ZoneId zone = Config.displayTimeZone();
        Component targetName = MilestoneService.targetDisplayName(key);
        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.top_item_header",
                targetName, ranking.entries().size()), false);
        if (gaps) {
            source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.top_item_gaps_note"), false);
        }
        List<MilestoneRankings.ItemEntry> displayed = ranking.entries().subList(0,
                Math.min(MilestoneRankings.TOP_LIMIT, ranking.entries().size()));
        for (int i = 0; i < displayed.size(); i++) {
            MilestoneRankings.ItemEntry e = displayed.get(i);
            if (gaps) {
                RankingStatsModel.TimeGap gap = gapResult.rows().get(i);
                source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.top_item_entry_gaps",
                        e.rank(), Component.literal(e.name()),
                        Playtime.formatRecordComponent(source, e.record(), zone),
                        gapText(source, gap.aboveTicks()), gapText(source, gap.firstTicks())), false);
            } else {
                source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.top_item_entry",
                        e.rank(), Component.literal(e.name()),
                        Playtime.formatRecordComponent(source, e.record(), zone)), false);
            }
        }
        if (viewer.uuid() != null) {
            MilestoneRankings.ItemEntry self = ranking.self();
            Component selfLine;
            if (self == null) {
                selfLine = ServerLang.tr(source, "message.itemmilestonelog.top_item_self_none");
            } else if (gaps) {
                RankingStatsModel.TimeGap gap = gapResult.self();
                selfLine = ServerLang.tr(source, "message.itemmilestonelog.top_item_self_gaps",
                        self.rank(), Playtime.formatRecordComponent(source, self.record(), zone),
                        gapText(source, gap.aboveTicks()), gapText(source, gap.firstTicks()));
            } else {
                selfLine = ServerLang.tr(source, "message.itemmilestonelog.top_item_self",
                        self.rank(), Playtime.formatRecordComponent(source, self.record(), zone));
            }
            source.sendSuccess(() -> selfLine, false);
        }
        return ranking.entries().size();
    }

    /** Renders an unavailable gap as the shared {@code gap_none} literal. */
    private static Component gapText(CommandSourceStack source, long ticks) {
        return ticks < 0L
                ? ServerLang.tr(source, "message.itemmilestonelog.gap_none")
                : Component.literal(Playtime.format((int) ticks));
    }

    private static int recent(CommandContext<CommandSourceStack> ctx, int limit) {
        CommandSourceStack source = ctx.getSource();
        if (!canUseRankings(source)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        MilestoneData data = MilestoneData.get(source.getServer());
        Map<TargetKey, UUID> worldFirstHolders = new HashMap<>();
        for (Map.Entry<TargetKey, WorldFirstRecord> e : data.worldFirsts().entrySet()) {
            worldFirstHolders.put(e.getKey(), e.getValue().playerUuid());
        }
        List<MilestoneRankings.RecentEntry> rows =
                MilestoneRankings.recent(data.allPlayers(), worldFirstHolders, rankingViewer(source), limit);
        if (rows.isEmpty()) {
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.recent_empty"));
        }
        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.recent_header", rows.size()), false);
        ZoneId zone = Config.displayTimeZone();
        for (MilestoneRankings.RecentEntry r : rows) {
            String key = r.worldFirst()
                    ? "message.itemmilestonelog.recent_entry_world_first"
                    : "message.itemmilestonelog.recent_entry";
            source.sendSuccess(() -> ServerLang.tr(source, key, Component.literal(r.name()),
                    MilestoneService.targetDisplayName(r.key()), Playtime.formatRecordComponent(source, r.record(), zone)), false);
        }
        return rows.size();
    }

    /** F38: server-wide totals and most-recorded targets. Same gate and visibility rules as {@code top}. */
    private static int stats(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!canUseRankings(source)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        MilestoneData data = MilestoneData.get(source.getServer());
        MilestoneRankings.ServerStats stats =
                MilestoneRankings.stats(data.allPlayers(), data.worldFirsts().size(), rankingViewer(source));
        if (stats.isEmpty()) {
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.top_empty"));
        }
        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.stats_header"), false);
        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.stats_totals",
                stats.totalRecords(), stats.players(), stats.worldFirsts()), false);
        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.stats_top_header"), false);
        for (MilestoneRankings.KeyCountEntry e : stats.topKeys()) {
            source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.stats_top_entry",
                    e.rank(), MilestoneService.targetDisplayName(e.key()), e.players()), false);
        }
        return stats.totalRecords();
    }

    // --------------------------------------------------------- F-150-7 / F-150-9 item queries

    /** F-150-7 (N49): mean/median/range over every visible holder's playTicks; no paging. */
    private static int statsItem(CommandContext<CommandSourceStack> ctx, String itemId, int count) {
        CommandSourceStack source = ctx.getSource();
        if (!canUseRankings(source)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        TargetKey key = TargetKey.normalize(itemId, count);
        if (key == null) {
            return fail(ctx, ServerLang.tr(source, MilestoneService.invalidTargetKey(itemId), String.valueOf(itemId)));
        }
        MilestoneRankings.Viewer viewer = rankingViewer(source);
        List<Integer> ticks = new ArrayList<>();
        for (Map.Entry<UUID, MilestoneData.PlayerData> e :
                MilestoneData.get(source.getServer()).allPlayers().entrySet()) {
            MilestoneData.PlayerData pd = e.getValue();
            if (pd == null || !MilestoneRankings.isVisible(viewer, e.getKey(), pd)) {
                continue;
            }
            MilestoneRecord record = pd.records.get(key);
            if (record != null) {
                ticks.add(record.playTicks()); // epoch 0 is unknown, not absent (D-150-22)
            }
        }
        RankingStatsModel.ItemTimeStats stats = RankingStatsModel.itemTimes(ticks);
        Component targetName = MilestoneService.targetDisplayName(key);
        if (stats.count() == 0) {
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.stats_item_empty", targetName));
        }
        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.stats_item_header",
                targetName, stats.count()), false);
        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.stats_item_values",
                Playtime.format((int) stats.meanTicks()), Playtime.format((int) stats.medianTicks()),
                Playtime.format((int) stats.minTicks()), Playtime.format((int) stats.maxTicks())), false);
        return stats.count();
    }

    /**
     * F-150-9 (N92): recorded holders (same order/ranks as {@code top <item>}) followed by
     * players with an unrecorded effective target. Both sections use {@code isVisible}.
     */
    private static int whohas(CommandContext<CommandSourceStack> ctx, String itemId, int count,
                              @Nullable Integer page) {
        CommandSourceStack source = ctx.getSource();
        if (!canUseRankings(source)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        TargetKey key = TargetKey.normalize(itemId, count);
        if (key == null) {
            return fail(ctx, ServerLang.tr(source, MilestoneService.invalidTargetKey(itemId), String.valueOf(itemId)));
        }
        MilestoneRankings.Viewer viewer = rankingViewer(source);
        List<RankingStatsModel.WhoHasPlayer> players = new ArrayList<>();
        for (Map.Entry<UUID, MilestoneData.PlayerData> e :
                MilestoneData.get(source.getServer()).allPlayers().entrySet()) {
            MilestoneData.PlayerData pd = e.getValue();
            if (pd == null || !MilestoneRankings.isVisible(viewer, e.getKey(), pd)) {
                continue;
            }
            List<RankingStatsModel.RecordValue> records = new ArrayList<>();
            for (Map.Entry<TargetKey, MilestoneRecord> r : pd.records.entrySet()) {
                records.add(new RankingStatsModel.RecordValue(r.getKey().itemId(), r.getKey().count(),
                        r.getValue().playTicks(), r.getValue().epochMillis()));
            }
            List<RankingStatsModel.TargetRef> targets = new ArrayList<>();
            for (TargetKey target : Tracker.effectiveTargets(pd)) {
                targets.add(new RankingStatsModel.TargetRef(target.itemId(), target.count()));
            }
            players.add(new RankingStatsModel.WhoHasPlayer(e.getKey(),
                    MilestoneRankings.displayName(e.getKey(), pd), records, targets));
        }
        RankingStatsModel.WhoHasResult result = RankingStatsModel.whoHas(players,
                new RankingStatsModel.TargetRef(key.itemId(), key.count()));
        if (result.entries().isEmpty()) {
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.whohas_empty",
                    MilestoneService.targetDisplayName(key)));
        }
        // Header counts describe the uncapped population, even when the 200-row cap applies.
        int recorded = result.recordedCount();
        int targeting = result.targetingCount();

        Pagination.PageSlice<RankingStatsModel.WhoHasEntry> slice = null;
        List<RankingStatsModel.WhoHasEntry> displayed;
        if (page != null) {
            slice = Pagination.slice(result.entries(), page);
            if (slice.isOutOfRange()) {
                return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.page_out_of_range",
                        page, slice.totalPages()));
            }
            displayed = slice.items();
        } else {
            displayed = result.entries();
        }

        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.whohas_header",
                MilestoneService.targetDisplayName(key), recorded, targeting), false);
        for (RankingStatsModel.WhoHasEntry e : displayed) {
            if (e.recorded()) {
                source.sendSuccess(() -> ServerLang.tr(source,
                        "message.itemmilestonelog.whohas_recorded_entry",
                        e.rank(), Component.literal(e.name()),
                        Playtime.format((int) e.playTicks()), e.rank()), false);
            } else {
                source.sendSuccess(() -> ServerLang.tr(source,
                        "message.itemmilestonelog.whohas_targeting_entry",
                        Component.literal(e.name())), false);
            }
        }
        if (page == null && result.moreCount() > 0) {
            send(ctx, ServerLang.tr(source, "message.itemmilestonelog.whohas_more", result.moreCount()));
        }
        if (slice != null) {
            String prefix = key.isTag()
                    ? Pagination.whohastagCommandPrefix(key.itemId(), key.count())
                    : Pagination.whohasCommandPrefix(key.itemId(), key.count());
            sendPageFooter(source, slice, prefix);
        }
        return displayed.size();
    }

    // --------------------------------------------------------- F51 info

    /**
     * F51 {@code /milestonelog info <item> [<count>]}: the viewer's own status, the world first,
     * the recorder count and own rank for one target key, and related tag targets.
     * {@code <item>} is the same {@code IdentifierArgument} as {@code reset}, so records of items
     * from removed mods can be inspected too.
     */
    private static int infoItem(CommandContext<CommandSourceStack> ctx, String itemId, int count,
                                boolean preview) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        TargetKey key = TargetKey.normalize(itemId, count);
        if (key == null) {
            return fail(ctx, ServerLang.tr(player, MilestoneService.invalidTargetKey(itemId),
                    String.valueOf(itemId)));
        }
        return sendInfo(ctx, player, key, preview);
    }

    /**
     * F51 {@code /milestonelog info hand}: the main-hand item with count 1. An empty hand replies
     * with the same message as {@code addhand}.
     */
    private static int infoHand(CommandContext<CommandSourceStack> ctx, boolean preview)
            throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            return fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.addhand_empty"));
        }
        String itemId = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        return sendInfo(ctx, player, TargetKey.of(itemId, TargetKey.MIN_COUNT), preview);
    }

    /** Renders the five F51 info steps for {@code key}; returns the number of lines sent. */
    private static int sendInfo(CommandContext<CommandSourceStack> ctx, ServerPlayer player, TargetKey key,
                                boolean preview) {
        CommandSourceStack source = ctx.getSource();
        MilestoneData data = MilestoneData.get(source.getServer());
        MilestoneData.PlayerData pd = data.playerOrNull(player.getUUID());
        Map<TargetKey, MilestoneRecord> records = pd != null ? pd.records : Map.of();
        Set<TargetKey> effectiveTargets = Tracker.effectiveTargets(pd);
        Set<String> carried = Tracker.carriedFromCreative(pd);
        TagIndex tagIndex = TagTargets.index();
        ZoneId zone = Config.displayTimeZone();
        int lines = 0;

        // 1. Header with the target display name (F12 hover attached by targetDisplayName).
        source.sendSuccess(() -> ServerLang.tr(player, "message.itemmilestonelog.info_header",
                MilestoneService.targetDisplayName(key)), false);
        lines++;

        // Possession is counted only for unrecorded quantity targets (steps 2 and 5).
        List<TargetKey> relatedKeys = ItemInfoLogic.relatedTagKeys(key.itemId(),
                MilestoneService.isKnownNonAirItem(key.itemId()),
                effectiveTargets, records, data.worldFirsts().keySet(), tagIndex);
        Set<TargetKey> progressKeys = new LinkedHashSet<>(relatedKeys);
        progressKeys.add(key);
        Set<String> progressIds = ItemInfoLogic.possessionIds(progressKeys, records, carried);
        Map<String, Integer> possession = progressIds.isEmpty()
                ? Map.of()
                : Tracker.countItemsInPossession(player, progressIds);

        // 2. Own status (recorded / target with possession / not a target).
        Component ownStatus = infoStatusComponent(player,
                ItemInfoLogic.ownStatus(key, records, effectiveTargets, carried, tagIndex, possession), zone);
        source.sendSuccess(() -> ownStatus, false);
        lines++;

        // 3. World first (F47 holder-name rule, F48 record formatting).
        WorldFirstRecord worldFirst = data.worldFirstOrNull(key);
        if (worldFirst != null) {
            MilestoneData.PlayerData holder = data.playerOrNull(worldFirst.playerUuid());
            WorldFirstNames.Choice choice = WorldFirstNames.choose(worldFirst.playerUuid(), worldFirst.playerName(),
                    holder != null, holder != null ? holder.lastKnownName : null);
            Component holderName = choice.renamed()
                    ? ServerLang.tr(player, "message.itemmilestonelog.firsts_name_renamed",
                            Component.literal(choice.name()), Component.literal(choice.recordedName()))
                    : Component.literal(choice.name());
            source.sendSuccess(() -> ServerLang.tr(player, "message.itemmilestonelog.info_world_first",
                    holderName, Playtime.formatRecordComponent(player, worldFirst.toMilestoneRecord(), zone)), false);
        } else {
            source.sendSuccess(() -> ServerLang.tr(player, "message.itemmilestonelog.info_world_first_none"), false);
        }
        lines++;

        // 4. Recorder count and own rank — omitted unless the F6 lookup gate allows it.
        ItemInfoLogic.PlayersLine players = ItemInfoLogic.playersLine(
                data.allPlayers(), key, rankingViewer(source), Config.ALLOW_LOOKUP.get());
        if (players.shown()) {
            Component playersLine = players.ownRank() != null
                    ? ServerLang.tr(player, "message.itemmilestonelog.info_players_ranked",
                            players.players(), players.ownRank())
                    : ServerLang.tr(player, "message.itemmilestonelog.info_players_unranked", players.players());
            source.sendSuccess(() -> playersLine, false);
            lines++;
        }

        // 5. Related tag targets, at most 10 rows plus the overflow count.
        lines += sendInfoRelatedTags(source, player, relatedKeys, records, effectiveTargets, carried,
                tagIndex, possession, zone, preview);
        return lines;
    }

    /**
     * R2-MN-2(b): renders step 5 of {@code info}. This is the real handler seam: the base section is
     * suppressed when preview is active, so the preview block is the only related-tag section then.
     */
    static int sendInfoRelatedTags(
            CommandSourceStack source,
            @Nullable ServerPlayer player,
            List<TargetKey> relatedKeys,
            Map<TargetKey, MilestoneRecord> records,
            Set<TargetKey> effectiveTargets,
            Set<String> carried,
            TagIndex tagIndex,
            Map<String, Integer> possession,
            ZoneId zone,
            boolean preview) {
        ItemInfoLogic.RelatedTags relatedTags = ItemInfoLogic.relatedTagEntries(
                relatedKeys, records, effectiveTargets, carried, tagIndex, possession);
        int lines = 0;
        if (!relatedTags.isEmpty() && !preview) {
            source.sendSuccess(() -> ServerLang.tr(player, "message.itemmilestonelog.info_tag_header"), false);
            lines++;
            for (ItemInfoLogic.TagEntry entry : relatedTags.entries()) {
                Component line = ServerLang.tr(player, "message.itemmilestonelog.info_tag_entry",
                        MilestoneService.targetDisplayName(entry.key()),
                        infoStatusComponent(player, entry.status(), zone));
                source.sendSuccess(() -> line, false);
                lines++;
            }
            if (relatedTags.more() > 0) {
                source.sendSuccess(() -> ServerLang.tr(player, "message.itemmilestonelog.info_tag_more",
                        relatedTags.more()), false);
                lines++;
            }
        }
        if (preview) {
            Map<TargetKey, ItemInfoLogic.TagEntry> byKey = new HashMap<>();
            for (ItemInfoLogic.TagEntry entry : relatedTags.entries()) {
                byKey.put(entry.key(), entry);
            }
            List<CommandAnalysisModel.TargetRef> relatedRefs = relatedKeys.stream()
                    .map(k -> new CommandAnalysisModel.TargetRef(k.itemId(), k.count())).toList();
            Map<CommandAnalysisModel.TargetRef, CommandAnalysisModel.PreviewState> states =
                    infoPreviewStates(relatedRefs, relatedTags.entries());
            CommandAnalysisModel.PreviewResult result = CommandAnalysisModel.infoPreview(relatedRefs, states);
            source.sendSuccess(() -> ServerLang.tr(player, "message.itemmilestonelog.info_preview_header",
                    result.entries().size() + result.moreCount()), false);
            lines++;
            for (CommandAnalysisModel.PreviewTag row : result.entries()) {
                TargetKey previewKey = new TargetKey(row.key().itemId(), row.key().count());
                ItemInfoLogic.TagEntry original = byKey.get(previewKey);
                Component status = original != null
                        ? infoStatusComponent(player, original.status(), zone)
                        : ServerLang.tr(player, "message.itemmilestonelog.info_status_none");
                source.sendSuccess(() -> ServerLang.tr(player, "message.itemmilestonelog.info_tag_entry",
                        MilestoneService.targetDisplayName(previewKey), status), false);
                lines++;
            }
            if (result.moreCount() > 0) {
                source.sendSuccess(() -> ServerLang.tr(player, "message.itemmilestonelog.info_tag_more",
                        result.moreCount()), false);
                lines++;
            }
        }
        return lines;
    }

    /**
     * SF-8: preview states for every related key. Untracked keys get a NONE state so they appear in
     * the preview; displayed entries override the default with their real status by key.
     */
    static Map<CommandAnalysisModel.TargetRef, CommandAnalysisModel.PreviewState> infoPreviewStates(
            List<CommandAnalysisModel.TargetRef> relatedRefs, List<ItemInfoLogic.TagEntry> displayedEntries) {
        Map<CommandAnalysisModel.TargetRef, CommandAnalysisModel.PreviewState> states = new HashMap<>();
        for (CommandAnalysisModel.TargetRef ref : relatedRefs) {
            states.put(ref, new CommandAnalysisModel.PreviewState("none", 0, 0, 0L));
        }
        for (ItemInfoLogic.TagEntry entry : displayedEntries) {
            String statusKey = switch (entry.status().kind()) {
                case RECORDED -> "recorded";
                case TARGET -> "target";
                case TARGET_PROGRESS -> "target_progress";
                case NONE -> "none";
            };
            states.put(new CommandAnalysisModel.TargetRef(entry.key().itemId(), entry.key().count()),
                    new CommandAnalysisModel.PreviewState(statusKey, entry.status().current(),
                            entry.status().goal(), entry.status().record() != null
                            ? entry.status().record().playTicks() : 0L));
        }
        return states;
    }

    /**
     * The viewer's status message: {@code info_status_recorded} (F48 record formatting),
     * {@code info_status_target}, {@code info_status_target_progress} or {@code info_status_none}.
     * A quantity target carried out of creative gets the F50 {@code target_creative_carried} note.
     */
    private static Component infoStatusComponent(ServerPlayer player, ItemInfoLogic.OwnStatus status, ZoneId zone) {
        MutableComponent line = switch (status.kind()) {
            case RECORDED -> ServerLang.tr(player, "message.itemmilestonelog.info_status_recorded",
                    Playtime.formatRecordComponent(player, status.record(), zone));
            case TARGET -> ServerLang.tr(player, "message.itemmilestonelog.info_status_target");
            case TARGET_PROGRESS -> ServerLang.tr(player, "message.itemmilestonelog.info_status_target_progress",
                    status.current(), status.goal());
            case NONE -> ServerLang.tr(player, "message.itemmilestonelog.info_status_none");
        };
        if (status.creativeCarried()) {
            line.append(ServerLang.tr(player, "message.itemmilestonelog.target_creative_carried"));
        }
        return line;
    }

    /** Shared record listing for {@code admin <player> list} and {@code lookup <player>} (same format). */
    private static int sendRecordList(CommandContext<CommandSourceStack> ctx,
                                      Map.Entry<UUID, MilestoneData.PlayerData> target,
                                      @Nullable Integer page,
                                      String commandPrefix) {
        CommandSourceStack source = ctx.getSource();
        String name = displayNameFor(target.getKey(), target.getValue());
        MilestoneData.PlayerData pd = target.getValue();
        if (pd.records.isEmpty()) {
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.admin_no_records", name));
        }
        List<Map.Entry<TargetKey, MilestoneRecord>> entries = new ArrayList<>(pd.records.entrySet());
        entries.sort(Comparator.comparingInt((Map.Entry<TargetKey, MilestoneRecord> e) -> e.getValue().playTicks())
                .thenComparing(Map.Entry::getKey));

        Pagination.PageSlice<Map.Entry<TargetKey, MilestoneRecord>> slice = null;
        List<Map.Entry<TargetKey, MilestoneRecord>> displayed;
        if (page != null) {
            slice = Pagination.slice(entries, page);
            if (slice.isOutOfRange()) {
                return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.page_out_of_range",
                        page, slice.totalPages()));
            }
            displayed = slice.items();
        } else {
            displayed = entries;
        }

        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.admin_records_header", name, entries.size()), false);
        ZoneId zone = Config.displayTimeZone();
        for (var e : displayed) {
            source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.target_done",
                    MilestoneService.targetDisplayName(e.getKey()), Playtime.formatRecordComponent(source, e.getValue(), zone)), false);
        }
        if (slice != null) {
            sendPageFooter(source, slice, commandPrefix);
        }
        return displayed.size();
    }

    private static int adminTargets(CommandContext<CommandSourceStack> ctx, String query, @Nullable Integer page) {
        CommandSourceStack source = ctx.getSource();
        Map.Entry<UUID, MilestoneData.PlayerData> target = resolveOrReport(source, query);
        if (target == null) {
            return 0;
        }
        String name = displayNameFor(target.getKey(), target.getValue());
        MilestoneData.PlayerData pd = target.getValue();
        Set<TargetKey> targets = Tracker.effectiveTargets(pd);
        if (targets.isEmpty()) {
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.admin_no_targets", name));
        }
        List<TargetKey> sorted = new ArrayList<>(targets);
        sorted.sort(TargetKey::compareTo);

        Pagination.PageSlice<TargetKey> slice = null;
        List<TargetKey> displayed;
        if (page != null) {
            slice = Pagination.slice(sorted, page);
            if (slice.isOutOfRange()) {
                return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.page_out_of_range",
                        page, slice.totalPages()));
            }
            displayed = slice.items();
        } else {
            displayed = sorted;
        }

        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.admin_targets_header", name, sorted.size()), false);
        for (TargetKey key : displayed) {
            MilestoneRecord rec = pd.records.get(key);
            Component line = rec != null
                    ? ServerLang.tr(source, "message.itemmilestonelog.target_done", MilestoneService.targetDisplayName(key), Playtime.formatComponent(source, rec.playTicks()))
                    : ServerLang.tr(source, "message.itemmilestonelog.target_pending", MilestoneService.targetDisplayName(key));
            source.sendSuccess(() -> line, false);
        }
        if (slice != null) {
            sendPageFooter(source, slice, Pagination.adminTargetsCommandPrefix(query));
        }
        return displayed.size();
    }

    private record TargetIdentity(UUID uuid, String name) {
    }

    @Nullable
    private static TargetIdentity resolveTargetIdentity(CommandSourceStack source, String query,
                                                        boolean allowOnlineWithoutData) {
        MinecraftServer server = source.getServer();
        ResolvedPlayer resolved = resolvePlayerData(server, query);
        if (resolved.ambiguous()) {
            source.sendFailure(ServerLang.tr(source, "message.itemmilestonelog.admin_player_ambiguous", query));
            return null;
        }
        if (resolved.entry() != null) {
            return new TargetIdentity(resolved.entry().getKey(),
                    displayNameFor(resolved.entry().getKey(), resolved.entry().getValue()));
        }
        if (allowOnlineWithoutData) {
            ServerPlayer online = server.getPlayerList().getPlayerByName(query);
            if (online == null) {
                try {
                    online = server.getPlayerList().getPlayer(UUID.fromString(query));
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (online != null) {
                String profileName = online.getGameProfile().name();
                String name = (profileName != null && !profileName.isBlank()) ? profileName : online.getUUID().toString();
                return new TargetIdentity(online.getUUID(), name);
            }
        }
        source.sendFailure(ServerLang.tr(source, "message.itemmilestonelog.admin_player_not_found", query));
        return null;
    }

    private static int adminAdd(CommandContext<CommandSourceStack> ctx, String query, String itemId, int count) {
        CommandSourceStack source = ctx.getSource();
        TargetIdentity target = resolveTargetIdentity(source, query, true);
        if (target == null) {
            return 0;
        }
        return respond(ctx, MilestoneService.adminAddTarget(
                source.getServer(), source, target.uuid(), target.name(), itemId, count));
    }

    private static int adminRemove(CommandContext<CommandSourceStack> ctx, String query, String itemId, int count) {
        CommandSourceStack source = ctx.getSource();
        Map.Entry<UUID, MilestoneData.PlayerData> target = resolveOrReport(source, query);
        if (target == null) {
            return 0;
        }
        String name = displayNameFor(target.getKey(), target.getValue());
        return respond(ctx, MilestoneService.adminRemoveTarget(
                source.getServer(), source, target.getKey(), name, itemId, count));
    }

    private static int adminPreset(CommandContext<CommandSourceStack> ctx, String query, String presetName) {
        CommandSourceStack source = ctx.getSource();
        if (!Config.PRESETS_ENABLED.get()) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        TargetIdentity target = resolveTargetIdentity(source, query, true);
        if (target == null) {
            return 0;
        }
        return respond(ctx, MilestoneService.adminApplyPreset(
                source.getServer(), source, target.uuid(), target.name(), presetName));
    }

    private static int adminGrant(CommandContext<CommandSourceStack> ctx, String query, String itemId, int count) {
        CommandSourceStack source = ctx.getSource();
        TargetIdentity target = resolveTargetIdentity(source, query, true);
        if (target == null) {
            return 0;
        }
        return respond(ctx, Tracker.grantMilestone(source, target.uuid(), target.name(), itemId, count));
    }

    private static int adminReset(CommandContext<CommandSourceStack> ctx, String query, String itemId, int count) {
        CommandSourceStack source = ctx.getSource();
        Map.Entry<UUID, MilestoneData.PlayerData> target = resolveOrReport(source, query);
        if (target == null) {
            return 0;
        }
        String name = displayNameFor(target.getKey(), target.getValue());
        MilestoneData data = MilestoneData.get(source.getServer());
        TargetKey key = TargetKey.normalize(itemId, count);
        if (key == null) {
            return fail(ctx, ServerLang.tr(source, MilestoneService.invalidTargetKey(itemId), String.valueOf(itemId)));
        }
        if (target.getValue().records.remove(key) != null) {
            target.getValue().progressNotified.remove(key);
            data.setDirty();
            ScoreboardSync.refreshScores(source.getServer(), target.getKey());
            ServerPlayer online = source.getServer().getPlayerList().getPlayer(target.getKey());
            if (online != null) {
                MilestoneService.syncPlayerIfOpen(online);
            }
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.admin_reset_done", name, MilestoneService.targetDisplayName(key)));
        }
        if (!MilestoneService.isValidItemId(key.itemId())) {
            return fail(ctx, ServerLang.tr(source, MilestoneService.invalidTargetKey(itemId), itemId));
        }
        return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.admin_not_recorded", name, MilestoneService.targetDisplayName(key)));
    }

    private static int adminResetAll(CommandContext<CommandSourceStack> ctx, String query) {
        CommandSourceStack source = ctx.getSource();
        Map.Entry<UUID, MilestoneData.PlayerData> target = resolveOrReport(source, query);
        if (target == null) {
            return 0;
        }
        String name = displayNameFor(target.getKey(), target.getValue());
        MilestoneData data = MilestoneData.get(source.getServer());
        MilestoneService.AdminResetAllResult result = MilestoneService.adminResetAll(data, target.getKey());
        int count = result.recordCount();
        if (result.changed()) {
            if (count > 0) {
                ScoreboardSync.refreshScores(source.getServer(), target.getKey());
            }
            ServerPlayer online = source.getServer().getPlayerList().getPlayer(target.getKey());
            if (online != null) {
                MilestoneService.syncPlayerIfOpen(online);
            }
        }
        return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.admin_resetall_done", count, name));
    }

    private static int adminPurge(CommandContext<CommandSourceStack> ctx, String query) {
        CommandSourceStack source = ctx.getSource();
        Map.Entry<UUID, MilestoneData.PlayerData> target = resolveOrReport(source, query);
        if (target == null) {
            return 0;
        }
        UUID uuid = target.getKey();
        String name = displayNameFor(uuid, target.getValue());
        MilestoneData data = MilestoneData.get(source.getServer());
        ScoreboardSync.deleteScores(source.getServer(), uuid);
        if (data.purgePlayer(uuid)) {
            ItemMilestoneLog.LOGGER.info("[ItemMilestoneLog] Admin {} purged all milestone data for {} ({})",
                    source.getTextName(), name, uuid);
            ServerPlayer online = source.getServer().getPlayerList().getPlayer(uuid);
            if (online != null) {
                MilestoneService.syncPlayerIfOpen(online);
            }
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.admin_purge_done", name, uuid.toString()));
        }
        return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.admin_player_not_found", query));
    }

    // --------------------------------------------------------- F37 prunedata

    private static int pruneData(CommandContext<CommandSourceStack> ctx, int days, boolean confirm) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        int count = MilestoneService.pruneInactivePlayers(server, days, confirm);
        if (!confirm) {
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.prunedata_preview", count, days));
        }
        ItemMilestoneLog.LOGGER.info("[ItemMilestoneLog] Admin {} pruned data for {} player(s) (days={})",
                source.getTextName(), count, days);
        return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.prunedata_done", count));
    }

    // --------------------------------------------------------- F24 renameitem

    private static int renameItem(CommandContext<CommandSourceStack> ctx, String oldId, String newId, boolean confirm) {
        CommandSourceStack source = ctx.getSource();
        return respond(ctx, MilestoneService.renameItem(source.getServer(), source, oldId, newId, confirm));
    }

    // --------------------------------------------------------- F-140-19 renametag

    private static int renameTag(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        RenameTagArguments.Parsed args = RenameTagArguments.parse(StringArgumentType.getString(ctx, "tags"));
        if (args == null) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.renametag_bad_arguments"));
        }
        return respond(ctx, MilestoneService.renameTag(source.getServer(), source,
                args.oldTag(), args.newTag(), args.confirm()));
    }

    // --------------------------------------------------------- F29 help

    private static CommandHelp.Context helpContext(CommandSourceStack source) {
        return new CommandHelp.Context(
                source.getPlayer() != null,
                canViewHidden(source),
                Config.PRESETS_ENABLED.get(),
                Config.EXPORT_IMPORT_ENABLED.get(),
                Config.ALLOW_LOOKUP.get(),
                Config.ALLOW_DISCORD_OPT_OUT.get());
    }

    static boolean canUseHelpEntry(
            CommandDispatcher<CommandSourceStack> dispatcher,
            @Nullable CommandSourceStack source,
            CommandHelp.Entry entry) {
        CommandNode<CommandSourceStack> rootNode = dispatcher.getRoot().getChild("milestonelog");
        if (rootNode == null || !rootNode.canUse(source)) {
            return false;
        }
        if (entry.isRoot()) {
            return rootNode.getCommand() != null;
        }
        CommandNode<CommandSourceStack> child = rootNode.getChild(entry.name());
        return child != null && child.canUse(source);
    }

    private static List<CommandHelp.Entry> visibleHelpEntries(
            CommandSourceStack source,
            CommandDispatcher<CommandSourceStack> dispatcher) {
        CommandHelp.Context helpCtx = helpContext(source);
        return CommandHelp.visibleEntries(helpCtx, entry -> canUseHelpEntry(dispatcher, source, entry));
    }

    static List<String> helpUsages(
            CommandDispatcher<CommandSourceStack> dispatcher,
            @Nullable CommandSourceStack source,
            CommandHelp.Entry entry) {
        if (entry.isRoot()) {
            return CommandHelp.formatUsageLines(entry, List.of());
        }
        // G10a: one vanilla greedy string node replaces <oldTag>/<newTag>/confirm, but the
        // syntax players type and the help line are unchanged.
        if ("renametag".equals(entry.name())) {
            return CommandHelp.formatUsageLines(entry, List.of("<oldTag> <newTag> [confirm]"));
        }
        CommandNode<CommandSourceStack> rootNode = dispatcher.getRoot().getChild("milestonelog");
        CommandNode<CommandSourceStack> target = rootNode != null ? rootNode.getChild(entry.name()) : null;
        if (target == null) {
            return CommandHelp.formatUsageLines(entry, List.of());
        }
        Map<CommandNode<CommandSourceStack>, String> smart = dispatcher.getSmartUsage(target, source);
        return CommandHelp.formatUsageLines(entry, smart.values());
    }

    private static int helpList(
            CommandContext<CommandSourceStack> ctx,
            CommandDispatcher<CommandSourceStack> dispatcher) {
        CommandSourceStack source = ctx.getSource();
        List<CommandHelp.Entry> entries = visibleHelpEntries(source, dispatcher);
        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.help_header"), false);
        for (CommandHelp.Entry entry : entries) {
            Component line = ServerLang.tr(source, "message.itemmilestonelog.help_entry",
                    clickableHelpCommand(entry),
                    ServerLang.tr(source, entry.descriptionKey()));
            source.sendSuccess(() -> line, false);
        }
        return entries.size();
    }

    private static int helpCommand(
            CommandContext<CommandSourceStack> ctx,
            CommandDispatcher<CommandSourceStack> dispatcher,
            String subcommand) {
        CommandSourceStack source = ctx.getSource();
        CommandHelp.Context helpCtx = helpContext(source);
        CommandHelp.Entry entry = CommandHelp.findVisible(subcommand, helpCtx,
                e -> canUseHelpEntry(dispatcher, source, e));
        if (entry == null) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.help_unknown",
                    Component.literal(subcommand)));
        }
        Component entryLine = ServerLang.tr(source, "message.itemmilestonelog.help_entry",
                clickableHelpCommand(entry),
                ServerLang.tr(source, entry.descriptionKey()));
        source.sendSuccess(() -> entryLine, false);
        List<String> usages = helpUsages(dispatcher, source, entry);
        for (String usage : usages) {
            source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.help_usage",
                    Component.literal(usage)), false);
        }
        return usages.size();
    }

    // ==========================================================================================
    // PR-2: read-only analytics handlers (F-150-11/33/12/18/15/24/29/39/20)
    // No handler in this section writes player data, changes Config or touches the GUI.
    // ==========================================================================================

    private static CommandAnalysisModel.RecordValue analysisRecord(TargetKey key, MilestoneRecord record) {
        return new CommandAnalysisModel.RecordValue(key.itemId(), key.count(), record.playTicks(),
                record.day(), record.epochMillis());
    }

    private static CommandAnalysisModel.RecordValue analysisRecord(TargetKey key, long epochMillis,
                                                                   int playTicks) {
        return new CommandAnalysisModel.RecordValue(key.itemId(), key.count(), playTicks, 0L, epochMillis);
    }

    private static List<CommandAnalysisModel.RecordValue> analysisRecords(
            Map<TargetKey, MilestoneRecord> records) {
        List<CommandAnalysisModel.RecordValue> out = new ArrayList<>();
        for (Map.Entry<TargetKey, MilestoneRecord> e : records.entrySet()) {
            out.add(analysisRecord(e.getKey(), e.getValue()));
        }
        return out;
    }

    private static CommandAnalysisModel.TargetRef analysisRef(TargetKey key) {
        return new CommandAnalysisModel.TargetRef(key.itemId(), key.count());
    }

    private static Component analysisName(CommandAnalysisModel.TargetRef key) {
        return MilestoneService.targetDisplayName(new TargetKey(key.itemId(), key.count()));
    }

    private static MilestoneData.PlayerData ownData(CommandSourceStack source) {
        ServerPlayer viewer = source.getPlayer();
        return viewer == null ? null : MilestoneData.get(source.getServer()).playerOrNull(viewer.getUUID());
    }

    private static List<Component> renderAndPage(CommandContext<CommandSourceStack> ctx,
                                                 List<Component> lines, @Nullable Integer page,
                                                 String prefix, int moreCount, String moreKey) {
        CommandSourceStack source = ctx.getSource();
        Pagination.PageSlice<Component> slice = null;
        List<Component> displayed;
        if (page != null) {
            slice = Pagination.slice(lines, page);
            if (slice.isOutOfRange()) {
                fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.page_out_of_range",
                        page, slice.totalPages()));
                return List.of();
            }
            displayed = slice.items();
        } else {
            displayed = lines;
        }
        for (Component line : displayed) {
            source.sendSuccess(() -> line, false);
        }
        if (page == null && moreCount > 0) {
            send(ctx, ServerLang.tr(source, moreKey, moreCount));
        }
        if (slice != null) {
            sendPageFooter(source, slice, prefix);
        }
        return displayed;
    }

    // ------------------------------------------------------------- F-150-11 / F-150-33 compare

    private static int compare(CommandContext<CommandSourceStack> ctx, String playerQuery,
                               @Nullable String branchRaw, @Nullable Integer page) {
        CommandSourceStack source = ctx.getSource();
        if (!canUseRankings(source)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        Map.Entry<UUID, MilestoneData.PlayerData> target = resolveOrReport(source, playerQuery);
        if (target == null) {
            return 0;
        }
        ServerPlayer viewer = source.getPlayer();
        UUID targetUuid = target.getKey();
        boolean self = viewer != null && viewer.getUUID().equals(targetUuid);
        if (target.getValue().isHiddenFromLookup() && !self) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.lookup_hidden",
                    displayNameFor(targetUuid, target.getValue())));
        }
        MilestoneData.PlayerData own = ownData(source);
        List<CommandAnalysisModel.RecordValue> a = own == null ? List.of() : analysisRecords(own.records);
        List<CommandAnalysisModel.RecordValue> b = analysisRecords(target.getValue().records);
        CommandAnalysisModel.CompareBranch branch = branchRaw == null ? null
                : CommandAnalysisModel.CompareBranch.parse(branchRaw);
        List<Component> lines = new ArrayList<>();
        int more = 0;
        int commonCount = 0;
        int themCount = 0;
        int youCount = 0;
        String prefix;
        String moreKey = "message.itemmilestonelog.compare_more";
        if (branch == null) {
            prefix = Pagination.compareCommandPrefix(playerQuery);
            CommandAnalysisModel.CompareResult result = CommandAnalysisModel.compare(a, b);
            commonCount = result.common().size();
            themCount = result.onlyThem().size();
            youCount = result.onlyYou().size();
            more = result.moreCount();
            for (CommandAnalysisModel.CompareRow row : result.common()) {
                lines.add(ServerLang.tr(source, "message.itemmilestonelog.compare_common",
                        analysisName(row.key()), Playtime.format(row.selfTicks()),
                        Playtime.format(row.otherTicks())));
            }
            for (CommandAnalysisModel.CompareRow row : result.onlyThem()) {
                lines.add(ServerLang.tr(source, "message.itemmilestonelog.compare_only_them",
                        analysisName(row.key()), Playtime.format(row.otherTicks())));
            }
            for (CommandAnalysisModel.CompareRow row : result.onlyYou()) {
                lines.add(ServerLang.tr(source, "message.itemmilestonelog.compare_only_you",
                        analysisName(row.key()), Playtime.format(row.selfTicks())));
            }
        } else {
            prefix = Pagination.compareCommandPrefix(playerQuery, branch.commandValue());
            MilestoneRankings.Viewer viewerInfo = rankingViewer(source);
            List<CommandAnalysisModel.RivalPlayer> players = new ArrayList<>();
            for (Map.Entry<UUID, MilestoneData.PlayerData> e
                    : MilestoneData.get(source.getServer()).allPlayers().entrySet()) {
                MilestoneData.PlayerData pd = e.getValue();
                if (pd != null && MilestoneRankings.isVisible(viewerInfo, e.getKey(), pd)) {
                    players.add(new CommandAnalysisModel.RivalPlayer(e.getKey(),
                            displayNameFor(e.getKey(), pd), pd.records.size()));
                }
            }
            CommandAnalysisModel.CompareBranchResult result = CommandAnalysisModel.compareBranches(
                    branch, a, b, players, targetUuid, viewer == null ? null : viewer.getUUID(),
                    target.getValue().records.size());
            switch (branch) {
                case MISSING -> {
                    themCount = result.missing().entries().size();
                    more = result.missing().moreCount();
                    for (CommandAnalysisModel.CompareRow row : result.missing().entries()) {
                        lines.add(ServerLang.tr(source, "message.itemmilestonelog.compare_only_them",
                                analysisName(row.key()), Playtime.format(row.otherTicks())));
                    }
                }
                case ROUTE -> {
                    commonCount = result.route().entries().size();
                    more = result.route().moreCount();
                    for (CommandAnalysisModel.RouteEntry row : result.route().entries()) {
                        lines.add(ServerLang.tr(source, "message.itemmilestonelog.compare_route",
                                analysisName(row.key()), Playtime.format(row.selfTicks()),
                                Playtime.format(row.otherTicks()), Playtime.format((int) row.diffTicks())));
                    }
                }
                case RIVAL -> {
                    themCount = result.rivals().entries().size();
                    more = result.rivals().moreCount();
                    for (CommandAnalysisModel.RivalEntry row : result.rivals().entries()) {
                        lines.add(ServerLang.tr(source, "message.itemmilestonelog.compare_rival",
                                Component.literal(row.name()), row.recordCount(), row.gap()));
                    }
                }
            }
        }
        if (lines.isEmpty()) {
            return send(ctx, ServerLang.tr(source, branch == null
                    ? "message.itemmilestonelog.compare_none"
                    : "message.itemmilestonelog.compare_branch_empty"));
        }
        Component header = ServerLang.tr(source, "message.itemmilestonelog.compare_header",
                Component.literal(displayNameFor(targetUuid, target.getValue())),
                commonCount, themCount, youCount);
        source.sendSuccess(() -> header, false);
        return renderAndPage(ctx, lines, page, prefix, more, moreKey).size();
    }

    // ---------------------------------------------------------------- F-150-12 timeline

    private static int timeline(CommandContext<CommandSourceStack> ctx, String itemId, int count,
                                @Nullable Integer page, boolean tag) {
        CommandSourceStack source = ctx.getSource();
        if (!canUseRankings(source)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        TargetKey key = tag ? TargetKey.of(itemId, count) : TargetKey.normalize(itemId, count);
        if (key == null) {
            return fail(ctx, ServerLang.tr(source, MilestoneService.invalidTargetKey(itemId), itemId));
        }
        MilestoneData data = MilestoneData.get(source.getServer());
        MilestoneRankings.Viewer viewer = rankingViewer(source);
        List<CommandAnalysisModel.TimelineHolder> holders = new ArrayList<>();
        CommandAnalysisModel.TimelineHolder worldFirst = null;
        for (Map.Entry<UUID, MilestoneData.PlayerData> e : data.allPlayers().entrySet()) {
            MilestoneData.PlayerData pd = e.getValue();
            if (pd == null || !MilestoneRankings.isVisible(viewer, e.getKey(), pd)) {
                continue;
            }
            MilestoneRecord record = pd.records.get(key);
            if (record != null) {
                holders.add(new CommandAnalysisModel.TimelineHolder(e.getKey(),
                        displayNameFor(e.getKey(), pd), record.playTicks(), record.epochMillis()));
            }
        }
        WorldFirstRecord first = data.worldFirsts().get(key);
        if (first != null) {
            MilestoneData.PlayerData holder = data.playerOrNull(first.playerUuid());
            boolean visible = holder == null
                    || MilestoneRankings.isVisible(viewer, first.playerUuid(), holder);
            if (visible) {
                worldFirst = new CommandAnalysisModel.TimelineHolder(first.playerUuid(),
                        first.playerName(), first.playTicks(), first.epochMillis());
            }
        }
        CommandAnalysisModel.TimelineResult result = CommandAnalysisModel.timeline(holders, worldFirst,
                CommandAnalysisModel.TIMELINE_LIMIT);
        Component targetName = MilestoneService.targetDisplayName(key);
        if (result.entries().isEmpty() && result.worldFirst() == null) {
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.timeline_empty", targetName));
        }
        List<Component> lines = new ArrayList<>();
        if (result.worldFirst() != null) {
            lines.add(ServerLang.tr(source, "message.itemmilestonelog.timeline_worldfirst",
                    result.worldFirst().index(), Component.literal(result.worldFirst().name()),
                    Playtime.format(result.worldFirst().playTicks())));
        }
        for (CommandAnalysisModel.TimelineEntry entry : result.entries()) {
            lines.add(ServerLang.tr(source, "message.itemmilestonelog.timeline_entry",
                    entry.index(), Component.literal(entry.name()),
                    Playtime.format(entry.playTicks()), Playtime.format((int) entry.gapTicks())));
        }
        int total = result.entries().size() + result.moreCount();
        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.timeline_header",
                targetName, total), false);
        String prefix = tag ? Pagination.timelinetagCommandPrefix(key.itemId(), count)
                : Pagination.timelineCommandPrefix(key.itemId(), count);
        return renderAndPage(ctx, lines, page, prefix, result.moreCount(),
                "message.itemmilestonelog.timeline_more").size();
    }

    // ----------------------------------------------------------------- F-150-18 session

    private static int session(CommandContext<CommandSourceStack> ctx, String hoursRaw,
                               @Nullable Integer page, boolean summary) throws CommandSyntaxException {
        Integer hours = CommandAnalysisModel.parseSessionHours(hoursRaw);
        if (hours == null) {
            return fail(ctx, ServerLang.tr(ctx.getSource(), "message.itemmilestonelog.session_invalid_hours",
                    hoursRaw));
        }
        return session(ctx, hours, page, summary);
    }

    private static int session(CommandContext<CommandSourceStack> ctx, int hours,
                               @Nullable Integer page, boolean summary) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = player(ctx);
        MilestoneData.PlayerData pd = ownData(source);
        Map<TargetKey, MilestoneRecord> records = pd == null ? Map.of() : pd.records;
        if (summary) {
            Component line = ServerLang.tr(source, "message.itemmilestonelog.session_summary",
                    records.size(), Playtime.formatComponent(source, Playtime.currentTicks(player)),
                    pd == null ? 0 : pd.completedPresets.size());
            return send(ctx, line);
        }
        List<CommandAnalysisModel.RecordValue> values = new ArrayList<>();
        for (Map.Entry<TargetKey, MilestoneRecord> e : records.entrySet()) {
            values.add(analysisRecord(e.getKey(), e.getValue()));
        }
        CommandAnalysisModel.SessionResult result = CommandAnalysisModel.sessionWindow(
                values, System.currentTimeMillis(), hours);
        if (result.entries().isEmpty()) {
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.session_empty"));
        }
        ZoneId zone = Config.displayTimeZone();
        List<Component> lines = new ArrayList<>();
        for (CommandAnalysisModel.RecordValue value : result.entries()) {
            lines.add(ServerLang.tr(source, "message.itemmilestonelog.session_entry",
                    analysisName(value.key()),
                    Playtime.formatRecordComponent(source,
                            new MilestoneRecord(value.playTicks(), value.day(), value.epochMillis()), zone)));
        }
        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.session_header",
                hours, result.entries().size() + result.moreCount()), false);
        return renderAndPage(ctx, lines, page, page == null
                        ? Pagination.sessionCommandPrefix() : Pagination.sessionCommandPrefix(hours),
                result.moreCount(), "message.itemmilestonelog.session_more").size();
    }

    // -------------------------------------------------------------------- F-150-15 pace

    private static int statsPace(CommandContext<CommandSourceStack> ctx, @Nullable String scopeRaw,
                                 @Nullable Integer page) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        if (!canUseRankings(source)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        CommandAnalysisModel.PaceScope scope = scopeRaw == null ? null
                : CommandAnalysisModel.PaceScope.parse(scopeRaw);
        if (scopeRaw != null && scope == null) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.pace_invalid_scope", scopeRaw));
        }
        if (scope != null) {
            ServerPlayer player = player(ctx);
            MilestoneData.PlayerData pd = ownData(source);
            int recorded = 0;
            int targetTotal = 0;
            int completed = 0;
            int presetTotal = 0;
            if (scope == CommandAnalysisModel.PaceScope.ALL
                    || scope == CommandAnalysisModel.PaceScope.TARGETS) {
                Set<TargetKey> effective = Tracker.effectiveTargets(pd);
                Map<TargetKey, MilestoneRecord> records = pd == null ? Map.of() : pd.records;
                for (TargetKey key : effective) {
                    if (records.containsKey(key)) {
                        recorded++;
                    }
                }
                targetTotal = effective.size();
            }
            if (scope == CommandAnalysisModel.PaceScope.ALL
                    || scope == CommandAnalysisModel.PaceScope.PRESETS) {
                if (!Config.PRESETS_ENABLED.get()) {
                    return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
                }
                completed = pd == null ? 0 : pd.completedPresets.size();
                presetTotal = Presets.all().size();
            }
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.pace_summary",
                    recorded, targetTotal, completed, presetTotal));
        }
        // currentTicks is only defined for an online ServerPlayer; offline players have no elapsed
        // playtime value in SavedData and are therefore not ranked (documented deviation).
        List<CommandAnalysisModel.PacePlayer> players = new ArrayList<>();
        MilestoneRankings.Viewer viewer = rankingViewer(source);
        for (ServerPlayer online : source.getServer().getPlayerList().getPlayers()) {
            MilestoneData.PlayerData pd = MilestoneData.get(source.getServer()).playerOrNull(online.getUUID());
            if (pd == null || !MilestoneRankings.isVisible(viewer, online.getUUID(), pd)) {
                continue;
            }
            players.add(new CommandAnalysisModel.PacePlayer(online.getUUID(),
                    displayNameFor(online.getUUID(), pd), pd.records.size(),
                    Playtime.currentTicks(online)));
        }
        CommandAnalysisModel.PaceResult result = CommandAnalysisModel.pace(players,
                CommandAnalysisModel.PACE_LIMIT);
        if (result.entries().isEmpty()) {
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.top_empty"));
        }
        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.pace_header",
                result.playerCount()), false);
        List<Component> lines = new ArrayList<>();
        for (CommandAnalysisModel.PaceEntry entry : result.entries()) {
            Component rate = entry.milestonesPerHour() < 0L
                    ? ServerLang.tr(source, "message.itemmilestonelog.pace_rate_none")
                    : Component.literal(String.valueOf(entry.milestonesPerHour()));
            lines.add(ServerLang.tr(source, "message.itemmilestonelog.pace_entry",
                    entry.rank(), Component.literal(entry.name()), rate));
        }
        return renderAndPage(ctx, lines, page, Pagination.paceCommandPrefix(null),
                result.moreCount(), "message.itemmilestonelog.pace_more").size();
    }

    // ----------------------------------------------------------------- F-150-24 modstats

    private static int modstats(CommandContext<CommandSourceStack> ctx, @Nullable Integer page) {
        CommandSourceStack source = ctx.getSource();
        if (!canUseRankings(source)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        MilestoneData data = MilestoneData.get(source.getServer());
        MilestoneRankings.Viewer viewer = rankingViewer(source);
        List<CommandAnalysisModel.TargetRef> targets = new ArrayList<>();
        List<CommandAnalysisModel.TargetRef> records = new ArrayList<>();
        for (Map.Entry<UUID, MilestoneData.PlayerData> e : data.allPlayers().entrySet()) {
            MilestoneData.PlayerData pd = e.getValue();
            if (pd == null || !MilestoneRankings.isVisible(viewer, e.getKey(), pd)) {
                continue;
            }
            for (TargetKey key : Tracker.effectiveTargets(pd)) {
                targets.add(analysisRef(key));
            }
            for (TargetKey key : pd.records.keySet()) {
                records.add(analysisRef(key));
            }
        }
        // World-firsts are global and do not pass through the hideFromLookup filter (F-150-4).
        List<CommandAnalysisModel.TargetRef> firsts = new ArrayList<>();
        for (TargetKey key : data.worldFirsts().keySet()) {
            firsts.add(analysisRef(key));
        }
        CommandAnalysisModel.NamespaceResult result = CommandAnalysisModel.namespaceStats(
                targets, records, firsts, CommandAnalysisModel.MODSTATS_LIMIT);
        if (result.entries().isEmpty()) {
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.modstats_empty"));
        }
        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.modstats_header",
                result.entries().size() + result.moreCount()), false);
        List<Component> lines = new ArrayList<>();
        for (CommandAnalysisModel.NamespaceStats row : result.entries()) {
            lines.add(ServerLang.tr(source, "message.itemmilestonelog.modstats_entry",
                    Component.literal(row.namespace()), row.targets(), row.records(), row.firsts()));
        }
        return renderAndPage(ctx, lines, page, Pagination.modstatsCommandPrefix(),
                result.moreCount(), "message.itemmilestonelog.modstats_more").size();
    }

    // ------------------------------------------------------------------ F-150-29 axis

    private static int axisRanking(CommandContext<CommandSourceStack> ctx, String axisRaw,
                                   @Nullable Integer days, String scope,
                                   @Nullable String presetOrItem, int count) {
        CommandSourceStack source = ctx.getSource();
        if (!canUseRankings(source)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        CommandAnalysisModel.RankingAxis axis = CommandAnalysisModel.RankingAxis.parse(axisRaw);
        if (axis == null) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.axis_invalid", axisRaw));
        }
        if (axis == CommandAnalysisModel.RankingAxis.ALL) {
            // The default axis must keep the existing command output byte-for-byte unchanged.
            return switch (scope) {
                case "top" -> top(ctx);
                case "presettop" -> presettop(ctx, presetOrItem == null ? "" : presetOrItem, null);
                default -> stats(ctx);
            };
        }
        if (axis == CommandAnalysisModel.RankingAxis.PERIOD && days == null) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.axis_invalid", axisRaw));
        }
        if ("presettop".equals(scope) && !Config.PRESETS_ENABLED.get()) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        Presets.PresetDefinition preset = "presettop".equals(scope)
                ? Presets.get(presetOrItem == null ? "" : presetOrItem) : null;
        if ("presettop".equals(scope) && preset == null) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_unknown",
                    presetOrItem, String.join(", ", Presets.names())));
        }
        TargetKey itemKey = "top".equals(scope) && presetOrItem != null
                ? TargetKey.normalize(presetOrItem, count) : null;
        MilestoneRankings.Viewer viewer = rankingViewer(source);
        List<CommandAnalysisModel.AxisPlayer> players = new ArrayList<>();
        for (Map.Entry<UUID, MilestoneData.PlayerData> e
                : MilestoneData.get(source.getServer()).allPlayers().entrySet()) {
            MilestoneData.PlayerData pd = e.getValue();
            if (pd == null || !MilestoneRankings.isVisible(viewer, e.getKey(), pd)) {
                continue;
            }
            List<CommandAnalysisModel.RecordValue> records = new ArrayList<>();
            if (preset != null) {
                for (TargetKey key : preset.items()) {
                    MilestoneRecord record = pd.records.get(key);
                    if (record != null) {
                        records.add(analysisRecord(key, record));
                    }
                }
            } else if (itemKey != null) {
                MilestoneRecord record = pd.records.get(itemKey);
                if (record != null) {
                    records.add(analysisRecord(itemKey, record));
                }
            } else {
                records.addAll(analysisRecords(pd.records));
            }
            PlayerTeam team = source.getServer().getScoreboard()
                    .getPlayersTeam(displayNameFor(e.getKey(), pd));
            players.add(new CommandAnalysisModel.AxisPlayer(e.getKey(),
                    displayNameFor(e.getKey(), pd), records,
                    source.getServer().getPlayerList().getPlayer(e.getKey()) != null,
                    team == null ? null : team.getName()));
        }
        CommandAnalysisModel.AxisResult result = CommandAnalysisModel.rankingAxis(players, axis,
                days == null ? 30 : days, System.currentTimeMillis());
        int population = !result.entries().isEmpty() ? result.entries().size() : result.teams().size();
        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.axis_header",
                axis.commandValue(), population), false);
        if (axis == CommandAnalysisModel.RankingAxis.TEAM) {
            for (CommandAnalysisModel.AxisTeamEntry row : result.teams()) {
                Component label = row.team() == null
                        ? ServerLang.tr(source, "message.itemmilestonelog.gap_none")
                        : Component.literal(row.team());
                source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.axis_entry",
                        row.rank(), label, Playtime.format(row.playTicks())), false);
            }
        } else {
            for (CommandAnalysisModel.AxisCountEntry row : result.entries()) {
                source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.axis_entry",
                        row.rank(), Component.literal(row.name()), row.count()), false);
            }
        }
        if (result.skippedUnknown() > 0) {
            source.sendSuccess(() -> ServerLang.tr(source,
                    "message.itemmilestonelog.axis_period_skipped", result.skippedUnknown()), false);
        }
        return population;
    }

    // ------------------------------------------------------------- F-150-39 presetstats

    private static int presetStats(CommandContext<CommandSourceStack> ctx, @Nullable Integer page) {
        CommandSourceStack source = ctx.getSource();
        if (!Config.PRESETS_ENABLED.get() || !canUseRankings(source)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        MilestoneData data = MilestoneData.get(source.getServer());
        MilestoneRankings.Viewer viewer = rankingViewer(source);
        List<CommandAnalysisModel.PresetStatsPlayer> players = new ArrayList<>();
        for (Map.Entry<UUID, MilestoneData.PlayerData> e : data.allPlayers().entrySet()) {
            MilestoneData.PlayerData pd = e.getValue();
            if (pd == null || !MilestoneRankings.isVisible(viewer, e.getKey(), pd)) {
                continue;
            }
            players.add(new CommandAnalysisModel.PresetStatsPlayer(e.getKey(),
                    displayNameFor(e.getKey(), pd), pd.completedPresets, analysisRecords(pd.records)));
        }
        List<String> order = Presets.all().stream().map(Presets.PresetDefinition::id).toList();
        CommandAnalysisModel.PresetStatsResult result = CommandAnalysisModel.presetStats(players, order,
                CommandAnalysisModel.PRESETSTATS_LIMIT);
        if (result.entries().isEmpty()) {
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.presetstats_header", 0));
        }
        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.presetstats_header",
                players.size()), false);
        List<Component> lines = new ArrayList<>();
        for (CommandAnalysisModel.PresetPopularity row : result.entries()) {
            lines.add(ServerLang.tr(source, "message.itemmilestonelog.presetstats_entry",
                    Component.literal(row.presetId()), row.completed(), row.totalPlayers(),
                    row.totalPlayers() == 0 ? 0 : row.completed() * 100 / row.totalPlayers()));
        }
        return renderAndPage(ctx, lines, page, Pagination.presetstatsCommandPrefix(),
                result.moreCount(), "message.itemmilestonelog.presetstats_more").size();
    }

    private static int presetStatsPredict(CommandContext<CommandSourceStack> ctx, String presetId) {
        CommandSourceStack source = ctx.getSource();
        if (!Config.PRESETS_ENABLED.get() || !canUseRankings(source)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        Presets.PresetDefinition preset = Presets.get(presetId);
        if (preset == null) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_unknown",
                    presetId, String.join(", ", Presets.names())));
        }
        MilestoneData data = MilestoneData.get(source.getServer());
        MilestoneRankings.Viewer viewer = rankingViewer(source);
        List<CommandAnalysisModel.PresetStatsPlayer> players = new ArrayList<>();
        for (Map.Entry<UUID, MilestoneData.PlayerData> e : data.allPlayers().entrySet()) {
            MilestoneData.PlayerData pd = e.getValue();
            if (pd == null || !MilestoneRankings.isVisible(viewer, e.getKey(), pd)) {
                continue;
            }
            players.add(new CommandAnalysisModel.PresetStatsPlayer(e.getKey(),
                    displayNameFor(e.getKey(), pd), pd.completedPresets, analysisRecords(pd.records)));
        }
        List<CommandAnalysisModel.TargetRef> items = new ArrayList<>();
        for (TargetKey key : preset.items()) {
            items.add(analysisRef(key));
        }
        CommandAnalysisModel.PresetEstimate estimate =
                CommandAnalysisModel.presetPredict(players, items);
        Component time = estimate.finishers() == 0
                ? ServerLang.tr(source, "message.itemmilestonelog.gap_none")
                : Component.literal(Playtime.format((int) estimate.medianTicks()));
        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.presetstats_predict",
                Component.literal(presetId), time, estimate.finishers()), false);
        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.presetstats_note"), false);
        return 1;
    }

    // ------------------------------------------------------------- F-150-20 presetmatrix

    private static int presetMatrix(CommandContext<CommandSourceStack> ctx, @Nullable Integer page) {
        CommandSourceStack source = ctx.getSource();
        if (!Config.PRESETS_ENABLED.get() || !canUseRankings(source)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        MilestoneData data = MilestoneData.get(source.getServer());
        MilestoneRankings.Viewer viewer = rankingViewer(source);
        List<Presets.PresetDefinition> presets = Presets.all(); // already Presets.LIST_ORDER
        List<String> ids = presets.stream().map(Presets.PresetDefinition::id).toList();
        List<CommandAnalysisModel.MatrixPlayer> players = new ArrayList<>();
        for (Map.Entry<UUID, MilestoneData.PlayerData> e : data.allPlayers().entrySet()) {
            MilestoneData.PlayerData pd = e.getValue();
            if (pd == null || !MilestoneRankings.isVisible(viewer, e.getKey(), pd)) {
                continue;
            }
            Map<String, CommandAnalysisModel.MatrixValue> progress = new java.util.HashMap<>();
            for (Presets.PresetDefinition preset : presets) {
                // preset.items() is the includes-expanded list; no completion time is stored.
                int total = preset.items().size();
                int recorded = 0;
                for (TargetKey key : preset.items()) {
                    if (pd.records.containsKey(key)) {
                        recorded++;
                    }
                }
                progress.put(preset.id(), new CommandAnalysisModel.MatrixValue(recorded, total,
                        pd.completedPresets.contains(preset.id())));
            }
            players.add(new CommandAnalysisModel.MatrixPlayer(e.getKey(),
                    displayNameFor(e.getKey(), pd), progress));
        }
        CommandAnalysisModel.PresetMatrixResult result = CommandAnalysisModel.presetMatrix(players, ids,
                CommandAnalysisModel.PRESETMATRIX_COLUMNS, CommandAnalysisModel.PRESETMATRIX_LIMIT);
        if (result.rows().isEmpty()) {
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.presetmatrix_empty"));
        }
        int columns = Math.min(ids.size(), CommandAnalysisModel.PRESETMATRIX_COLUMNS);
        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.presetmatrix_header",
                players.size(), columns), false);
        List<Component> lines = new ArrayList<>();
        for (CommandAnalysisModel.MatrixRow row : result.rows()) {
            StringBuilder cells = new StringBuilder("[");
            for (int i = 0; i < row.cells().size(); i++) {
                CommandAnalysisModel.MatrixValue cell = row.cells().get(i);
                if (i > 0) {
                    cells.append(' ');
                }
                cells.append(cell.recorded()).append('/').append(cell.total());
                if (cell.completed()) {
                    cells.append('*');
                }
            }
            cells.append(']');
            lines.add(ServerLang.tr(source, "message.itemmilestonelog.presetmatrix_row",
                    Component.literal(row.name()), Component.literal(cells.toString())));
        }
        if (result.omittedPresets() > 0) {
            lines.add(ServerLang.tr(source, "message.itemmilestonelog.presetmatrix_more_presets",
                    result.omittedPresets()));
        }
        return renderAndPage(ctx, lines, page, Pagination.presetmatrixCommandPrefix(),
                result.moreCount(), "message.itemmilestonelog.presetmatrix_more").size();
    }

    // ---------------------------------------- PR-3: write operations (9 items)

    /** Offered share codes live in memory only (D-150-90); a server restart clears them. */
    private static final Map<UUID, String> PENDING_SHARE_CODES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final String TARGET_SHARE_ID = "targetshare";

    private static List<CommandAnalysisModel.TargetRef> personalRefs(@Nullable MilestoneData.PlayerData pd) {
        List<CommandAnalysisModel.TargetRef> refs = new ArrayList<>();
        if (pd != null) {
            for (TargetKey key : pd.targets) {
                refs.add(new CommandAnalysisModel.TargetRef(key.itemId(), key.count()));
            }
        }
        return refs;
    }

    /** Narrow overflow count (already-present keys are not counted as target-limit skips). */
    private static int copyLimitSkipped(Collection<CommandAnalysisModel.TargetRef> source,
                                        @Nullable MilestoneData.PlayerData mine) {
        Set<String> existing = new LinkedHashSet<>();
        if (mine != null) {
            for (TargetKey key : mine.targets) {
                existing.add(key.toEncodedString());
            }
        }
        int capacity = Math.max(0, MilestoneService.MAX_PERSONAL_TARGETS
                - (mine != null ? mine.targets.size() : 0));
        Set<String> seen = new LinkedHashSet<>();
        int skipped = 0;
        for (CommandAnalysisModel.TargetRef ref : source) {
            if (ref == null) {
                continue;
            }
            TargetKey key = TargetKey.normalize(ref.itemId(), ref.count());
            if (key == null || existing.contains(key.toEncodedString()) || !seen.add(key.toEncodedString())) {
                continue;
            }
            if (seen.size() > capacity) {
                skipped++;
            }
        }
        return skipped;
    }

    /** F-150-13: copy another player's PERSONAL targets only; two-step, no undo, target may be offline. */
    private static int copyTargets(CommandContext<CommandSourceStack> ctx, String query, boolean confirm)
            throws CommandSyntaxException {
        ServerPlayer me = player(ctx);
        CommandSourceStack source = ctx.getSource();
        if (!canUseRankings(source)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        Map.Entry<UUID, MilestoneData.PlayerData> other = resolveOrReport(source, query);
        if (other == null) {
            return 0;
        }
        String otherName = displayNameFor(other.getKey(), other.getValue());
        if (!canViewHidden(source) && other.getValue().isHiddenFromLookup()) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.lookup_hidden",
                    Component.literal(otherName)));
        }
        MilestoneData.PlayerData mine = dataOrNull(me);
        List<CommandAnalysisModel.TargetRef> sourceKeys = personalRefs(other.getValue());
        CommandAnalysisModel.CopyPlan plan = CommandAnalysisModel.copyTargets(
                personalRefs(mine), sourceKeys, MilestoneService.MAX_PERSONAL_TARGETS);
        int skipped = copyLimitSkipped(sourceKeys, mine);
        if (!confirm) {
            send(ctx, ServerLang.tr(source, "message.itemmilestonelog.copytargets_preview",
                    plan.copied(), Component.literal(otherName)));
            if (skipped > 0) {
                send(ctx, ServerLang.tr(source, "message.itemmilestonelog.copytargets_skipped", skipped));
            }
            return 1;
        }
        int applied = 0;
        for (CommandAnalysisModel.TargetRef ref : plan.missing()) {
            if (MilestoneService.addTarget(me, ref.itemId(), ref.count()).success()) {
                applied++;
            }
        }
        send(ctx, ServerLang.tr(source, "message.itemmilestonelog.copytargets_done",
                applied, Component.literal(otherName)));
        if (skipped > 0) {
            send(ctx, ServerLang.tr(source, "message.itemmilestonelog.copytargets_skipped", skipped));
        }
        return applied;
    }

    /** F-150-14: admin copies one player's targets to another, reusing the N08 model (no new node). */
    private static int adminCopyFrom(CommandContext<CommandSourceStack> ctx, String targetQuery,
                                     String sourceQuery, boolean confirm) {
        CommandSourceStack source = ctx.getSource();
        Map.Entry<UUID, MilestoneData.PlayerData> target = resolveOrReport(source, targetQuery);
        if (target == null) {
            return 0;
        }
        Map.Entry<UUID, MilestoneData.PlayerData> from = resolveOrReport(source, sourceQuery);
        if (from == null) {
            return 0;
        }
        String targetName = displayNameFor(target.getKey(), target.getValue());
        String fromName = displayNameFor(from.getKey(), from.getValue());
        List<CommandAnalysisModel.TargetRef> sourceKeys = personalRefs(from.getValue());
        CommandAnalysisModel.CopyPlan plan = CommandAnalysisModel.copyTargets(
                personalRefs(target.getValue()), sourceKeys, MilestoneService.MAX_PERSONAL_TARGETS);
        int skipped = copyLimitSkipped(sourceKeys, target.getValue());
        if (!confirm) {
            send(ctx, ServerLang.tr(source, "message.itemmilestonelog.admin_copyfrom_preview",
                    plan.copied(), Component.literal(fromName), Component.literal(targetName)));
            if (skipped > 0) {
                send(ctx, ServerLang.tr(source, "message.itemmilestonelog.copytargets_skipped", skipped));
            }
            return 1;
        }
        int applied = 0;
        for (CommandAnalysisModel.TargetRef ref : plan.missing()) {
            if (MilestoneService.adminAddTarget(source.getServer(), source, target.getKey(), targetName,
                    ref.itemId(), ref.count()).success()) {
                applied++;
            }
        }
        send(ctx, ServerLang.tr(source, "message.itemmilestonelog.admin_copyfrom_done",
                applied, Component.literal(fromName), Component.literal(targetName)));
        if (skipped > 0) {
            send(ctx, ServerLang.tr(source, "message.itemmilestonelog.copytargets_skipped", skipped));
        }
        return applied;
    }

    /** F-150-37: reuse the preset-share code format for a one-off personal target list. */
    private static int shareTargetsOffer(CommandContext<CommandSourceStack> ctx, String query)
            throws CommandSyntaxException {
        ServerPlayer me = player(ctx);
        CommandSourceStack source = ctx.getSource();
        if (!canUseRankings(source)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        Map.Entry<UUID, MilestoneData.PlayerData> other = resolveOrReport(source, query);
        if (other == null) {
            return 0;
        }
        String otherName = displayNameFor(other.getKey(), other.getValue());
        if (!canViewHidden(source) && other.getValue().isHiddenFromLookup()) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.lookup_hidden",
                    Component.literal(otherName)));
        }
        List<TargetKey> items = new ArrayList<>(other.getValue().targets);
        if (items.isEmpty() || items.size() > PresetShareLogic.MAX_ITEMS) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.sharetargets_invalid"));
        }
        String code = PresetShareLogic.encode(new PresetShareLogic.ShareData(
                TARGET_SHARE_ID, PresetName.absent(), items, PresetName.absent(),
                null, 0, null, List.of(), List.of(), List.of(), null));
        if (code.length() > PresetShareLogic.MAX_CODE_CHARS) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.sharetargets_invalid"));
        }
        PENDING_SHARE_CODES.put(me.getUUID(), code);
        MutableComponent message = ServerLang.tr(source, "message.itemmilestonelog.sharetargets_offer",
                Component.literal(otherName), Component.literal(code).withStyle(style -> style
                        .withUnderlined(true).withClickEvent(copyToClipboardEvent(code))));
        return send(ctx, message);
    }

    /** Decodes only {@code targetshare} codes; item entries use the preset-share JSON shape. */
    private static List<CommandAnalysisModel.TargetRef> decodeSharedTargets(String json) {
        List<CommandAnalysisModel.TargetRef> refs = new ArrayList<>();
        JsonArray items = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("items");
        if (items == null) {
            return refs;
        }
        for (JsonElement element : items) {
            TargetKey key = null;
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                key = TargetKey.tryParseEncoded(element.getAsString());
            } else if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                JsonElement id = object.get("item");
                if (id != null && id.isJsonPrimitive()) {
                    JsonElement count = object.get("count");
                    int n = count != null && count.isJsonPrimitive() ? count.getAsInt() : TargetKey.MIN_COUNT;
                    key = TargetKey.normalize(id.getAsString(), n);
                }
            }
            if (key != null) {
                refs.add(new CommandAnalysisModel.TargetRef(key.itemId(), key.count()));
            }
        }
        return refs;
    }

    /** F-150-37: accept applies the same N08 set math, with the same two-step confirm. */
    private static int shareTargetsAccept(CommandContext<CommandSourceStack> ctx, String rawCode, boolean confirm)
            throws CommandSyntaxException {
        ServerPlayer me = player(ctx);
        CommandSourceStack source = ctx.getSource();
        PresetShareLogic.Decoded decoded = PresetShareLogic.decode(rawCode != null ? rawCode.trim() : null, null);
        if (decoded.status() != PresetShareLogic.Status.OK || !TARGET_SHARE_ID.equals(decoded.id())
                || decoded.json() == null) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.sharetargets_invalid"));
        }
        List<CommandAnalysisModel.TargetRef> shared = decodeSharedTargets(decoded.json());
        MilestoneData.PlayerData mine = dataOrNull(me);
        CommandAnalysisModel.CopyPlan plan = CommandAnalysisModel.sharePlan(
                personalRefs(mine), shared, MilestoneService.MAX_PERSONAL_TARGETS);
        if (!confirm) {
            send(ctx, ServerLang.tr(source, "message.itemmilestonelog.copytargets_preview",
                    plan.copied(), Component.literal(TARGET_SHARE_ID)));
            return 1;
        }
        int applied = 0;
        for (CommandAnalysisModel.TargetRef ref : plan.missing()) {
            if (MilestoneService.addTarget(me, ref.itemId(), ref.count()).success()) {
                applied++;
            }
        }
        return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.sharetargets_accepted", applied));
    }

    /** F-150-37: drop pending codes only; already-accepted targets are unaffected. */
    private static int shareTargetsRevoke(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        PENDING_SHARE_CODES.remove(player(ctx).getUUID());
        return send(ctx, ServerLang.tr(ctx.getSource(), "message.itemmilestonelog.sharetargets_revoked"));
    }

    /** F-150-17 / F-150-30: one staged-quantity parser shared by addsteps and bulk addsteps. */
    private static int addSteps(CommandContext<CommandSourceStack> ctx, String itemId, String raw,
                                boolean confirm, boolean fromBulk) throws CommandSyntaxException {
        ServerPlayer me = player(ctx);
        CommandSourceStack source = ctx.getSource();
        List<Integer> counts = CommandAnalysisModel.parseSteps(raw);
        if (counts == null) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.addsteps_invalid_steps",
                    CommandAnalysisModel.MIN_COUNT, CommandAnalysisModel.MAX_COUNT));
        }
        CommandAnalysisModel.StepsPlan plan = CommandAnalysisModel.stepsFor(itemId, counts,
                personalRefs(dataOrNull(me)), MilestoneService.MAX_PERSONAL_TARGETS);
        Component label = Component.literal(itemId);
        if (!confirm) {
            send(ctx, ServerLang.tr(source, "message.itemmilestonelog.addsteps_preview",
                    plan.keys().size(), label));
            if (plan.skipped() > 0) {
                send(ctx, ServerLang.tr(source, "message.itemmilestonelog.addsteps_skipped", plan.skipped()));
            }
            return 1;
        }
        int applied = 0;
        for (CommandAnalysisModel.TargetRef ref : plan.keys()) {
            if (MilestoneService.addTarget(me, ref.itemId(), ref.count()).success()) {
                applied++;
            }
        }
        send(ctx, ServerLang.tr(source, "message.itemmilestonelog.addsteps_done", applied, label));
        if (plan.skipped() > 0) {
            send(ctx, ServerLang.tr(source, "message.itemmilestonelog.addsteps_skipped", plan.skipped()));
        }
        return applied;
    }

    /** F-150-30: read-only count preview of the three bulk actions. */
    private static int bulkPreview(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer me = player(ctx);
        MilestoneData.PlayerData pd = dataOrNull(me);
        Set<CommandAnalysisModel.TargetRef> recorded = new LinkedHashSet<>();
        Set<CommandAnalysisModel.TargetRef> tagItems = new LinkedHashSet<>();
        if (pd != null) {
            for (TargetKey key : pd.records.keySet()) {
                recorded.add(new CommandAnalysisModel.TargetRef(key.itemId(), key.count()));
            }
            for (TargetKey key : pd.targets) {
                if (TargetKey.isTagId(key.itemId())) {
                    for (String item : TagTargets.index().members(key.itemId())) {
                        tagItems.add(new CommandAnalysisModel.TargetRef(item, TargetKey.MIN_COUNT));
                    }
                }
            }
        }
        CommandAnalysisModel.BulkPlan plan = CommandAnalysisModel.bulkPlan(
                personalRefs(pd), recorded, tagItems, MilestoneService.MAX_PERSONAL_TARGETS);
        return send(ctx, ServerLang.tr(ctx.getSource(), "message.itemmilestonelog.bulk_preview",
                plan.stepTargets(), plan.taggedItems(), plan.completedTargets()));
    }

    /** F-150-30: expand a tag into individual item keys; the tag key itself is kept (D-150-76). */
    private static int bulkExpandTag(CommandContext<CommandSourceStack> ctx, String tag, boolean confirm)
            throws CommandSyntaxException {
        ServerPlayer me = player(ctx);
        CommandSourceStack source = ctx.getSource();
        Set<String> members = TagTargets.index().members(tag);
        if (!MilestoneService.isValidItemId(tag) || members.isEmpty()) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.invalid_tag", tag));
        }
        List<CommandAnalysisModel.TargetRef> refs = new ArrayList<>();
        for (String item : members) {
            refs.add(new CommandAnalysisModel.TargetRef(item, TargetKey.MIN_COUNT));
        }
        MilestoneData.PlayerData pd = dataOrNull(me);
        CommandAnalysisModel.CopyPlan plan = CommandAnalysisModel.copyTargets(
                personalRefs(pd), refs, MilestoneService.MAX_PERSONAL_TARGETS);
        if (!confirm) {
            send(ctx, ServerLang.tr(source, "message.itemmilestonelog.addsteps_preview",
                    plan.copied(), Component.literal(tag)));
            return 1;
        }
        int applied = 0;
        for (CommandAnalysisModel.TargetRef ref : plan.missing()) {
            if (MilestoneService.addTarget(me, ref.itemId(), ref.count()).success()) {
                applied++;
            }
        }
        send(ctx, ServerLang.tr(source, "message.itemmilestonelog.bulk_expandtag_done", applied,
                Component.literal(tag)));
        if (plan.skipped() > 0) {
            send(ctx, ServerLang.tr(source, "message.itemmilestonelog.bulk_skipped", plan.skipped()));
        }
        return applied;
    }

    /** F-150-30: remove only completed personal targets; records/world-firsts/presets untouched. */
    private static int bulkCleanup(CommandContext<CommandSourceStack> ctx, boolean confirm)
            throws CommandSyntaxException {
        ServerPlayer me = player(ctx);
        CommandSourceStack source = ctx.getSource();
        MilestoneData data = MilestoneData.get(me.level().getServer());
        MilestoneData.PlayerData pd = data.playerOrNull(me.getUUID());
        List<TargetKey> completed = new ArrayList<>();
        if (pd != null) {
            for (TargetKey key : pd.targets) {
                if (pd.records.containsKey(key)) {
                    completed.add(key);
                }
            }
        }
        if (!confirm) {
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.bulk_cleanup_preview",
                    completed.size()));
        }
        List<TargetKey> removed = new ArrayList<>();
        if (pd != null) {
            for (TargetKey key : completed) {
                if (pd.targets.remove(key)) {
                    removed.add(key);
                    pd.targetMeta.remove(key.toEncodedString());
                }
            }
        }
        if (removed.isEmpty()) {
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.bulk_cleanup_done", 0));
        }
        data.setDirty();
        boolean undoable = MilestoneService.UNDO_SNAPSHOTS.offer(me.getUUID(),
                UndoLogic.Snapshot.bulkRemoval(removed, Map.of(), System.nanoTime()));
        send(ctx, ServerLang.tr(source, "message.itemmilestonelog.bulk_cleanup_done", removed.size()));
        if (undoable) {
            send(ctx, ServerLang.tr(source, "message.itemmilestonelog.undo_hint"));
        }
        return removed.size();
    }

    /** F-150-25: newest-first list of still-valid generations (1 = newest). */
    private static int undoList(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer me = player(ctx);
        CommandSourceStack source = ctx.getSource();
        List<UndoLogic.Snapshot<TargetKey, MilestoneRecord>> snapshots =
                MilestoneService.UNDO_SNAPSHOTS.list(me.getUUID(), System.nanoTime());
        if (snapshots.isEmpty()) {
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.undo_list_empty"));
        }
        int index = 0;
        for (UndoLogic.Snapshot<TargetKey, MilestoneRecord> snapshot : snapshots) {
            Component description = snapshot.kind() == UndoLogic.Kind.RESET
                    ? ServerLang.tr(source, "message.itemmilestonelog.undo_done", snapshot.removedRecords().size())
                    : ServerLang.tr(source, "message.itemmilestonelog.undo_done_targets",
                            snapshot.removedTargets().size());
            long age = Math.max(0L, (System.nanoTime() - snapshot.capturedAtNanos()) / 1_000_000_000L);
            Component ageText = ServerLang.tr(source, "message.itemmilestonelog.playtime_format",
                    age / 3600L, (age % 3600L) / 60L, age % 60L);
            send(ctx, ServerLang.tr(source, "message.itemmilestonelog.undo_list_entry",
                    ++index, description, ageText));
        }
        return snapshots.size();
    }

    /** F-150-25: undo a specific generation; newer generations become invalid (D-150-66). */
    private static int undoGeneration(CommandContext<CommandSourceStack> ctx, int generation)
            throws CommandSyntaxException {
        ServerPlayer me = player(ctx);
        CommandSourceStack source = ctx.getSource();
        long now = System.nanoTime();
        int available = MilestoneService.UNDO_SNAPSHOTS.list(me.getUUID(), now).size();
        if (generation < 1 || generation > available || available == 0) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.undo_generation_invalid",
                    generation, Math.max(1, available)));
        }
        MilestoneService.ActionResult result = MilestoneService.undo(me, generation);
        int status = result.success() ? send(ctx, result.message()) : fail(ctx, result.message());
        if (result.success() && generation < available) {
            send(ctx, ServerLang.tr(source, "message.itemmilestonelog.undo_discarded_later"));
        }
        return status;
    }

    /** F-150-26: four-number preview of an import, with no write to MilestoneData. */
    private static int sendDryRun(CommandContext<CommandSourceStack> ctx, String fileName,
                                  CommandAnalysisModel.ImportPreview preview) {
        CommandSourceStack source = ctx.getSource();
        send(ctx, ServerLang.tr(source, "message.itemmilestonelog.import_dryrun_header", fileName));
        send(ctx, ServerLang.tr(source, "message.itemmilestonelog.import_dryrun_targets", preview.targetsToAdd()));
        send(ctx, ServerLang.tr(source, "message.itemmilestonelog.import_dryrun_records", preview.recordsToOverwrite()));
        send(ctx, ServerLang.tr(source, "message.itemmilestonelog.import_dryrun_firsts", preview.newWorldFirsts()));
        send(ctx, ServerLang.tr(source, "message.itemmilestonelog.import_dryrun_skipped", preview.skipped()));
        return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.import_dryrun_hint"));
    }

    private static int importAllDryRun(CommandContext<CommandSourceStack> ctx, String fileName) {
        CommandSourceStack source = ctx.getSource();
        if (!Config.EXPORT_IMPORT_ENABLED.get()) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        if (!MilestoneExport.isSafeFileName(fileName)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.import_invalid_name", fileName));
        }
        Path dir = MilestoneExport.exportsDir(source.getServer()).normalize();
        Path file = dir.resolve(fileName).normalize();
        if (!file.getParent().equals(dir) || !Files.isRegularFile(file)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.import_not_found", fileName));
        }
        try {
            MilestoneExport.MergePlan plan = MilestoneExport.buildMergePlan(
                    MilestoneExport.parseAllPlayersExport(file));
            MilestoneData data = MilestoneData.get(source.getServer());
            int targets = 0;
            int records = 0;
            int firsts = 0;
            for (MilestoneExport.PlayerMergePlan playerPlan : plan.players()) {
                MilestoneData.PlayerData pd = data.playerOrNull(playerPlan.uuid());
                Set<TargetKey> currentTargets = pd != null ? pd.targets : Set.of();
                int capacity = Math.max(0, MilestoneService.MAX_PERSONAL_TARGETS - currentTargets.size());
                Set<TargetKey> added = new LinkedHashSet<>();
                for (TargetKey key : playerPlan.targets()) {
                    if (!currentTargets.contains(key) && added.add(key) && added.size() <= capacity) {
                        targets++;
                    }
                }
                for (MilestoneExport.RecordUpdate update : playerPlan.recordUpdates()) {
                    MilestoneRecord current = pd != null ? pd.records.get(update.key()) : null;
                    if (current == null || update.record().playTicks() < current.playTicks()) {
                        records++;
                    }
                }
            }
            if (plan.worldFirsts() != null) {
                for (WorldFirstRecord wf : plan.worldFirsts()) {
                    if (!data.worldFirsts().containsKey(wf.targetKey())) {
                        firsts++;
                    }
                }
            }
            return sendDryRun(ctx, fileName, new CommandAnalysisModel.ImportPreview(
                    targets, records, firsts,
                    plan.skippedEntries() + plan.worldFirstSkippedEntries()));
        } catch (IllegalArgumentException e) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.import_invalid_format", fileName));
        } catch (java.io.IOException e) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.import_failed", fileName));
        }
    }

    /** F-150-31: {@code targets meta} / {@code targets meta clear}, sharing the {@code targets} Entry. */
    private static LiteralArgumentBuilder<CommandSourceStack> targetMetaNode() {
        LiteralArgumentBuilder<CommandSourceStack> meta = literal("meta");
        var metaCountNode = argument("metaCount", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                .executes(ctx -> targetMetaApply(ctx, itemIdentifierArg(ctx, "metaItem"),
                        IntegerArgumentType.getInteger(ctx, "metaCount")));
        attachMetaOptions(metaCountNode);
        var metaItemNode = argument("metaItem", itemIdentifierArgumentType())
                .executes(ctx -> targetMetaApply(ctx, itemIdentifierArg(ctx, "metaItem"), TargetKey.MIN_COUNT))
                .then(metaCountNode);
        attachMetaOptions(metaItemNode);
        meta.then(metaItemNode);
        meta.then(literal("clear")
                .then(argument("metaItem", itemIdentifierArgumentType())
                        .executes(ctx -> targetMetaClear(ctx, itemIdentifierArg(ctx, "metaItem"), TargetKey.MIN_COUNT))
                        .then(argument("metaCount", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                .executes(ctx -> targetMetaClear(ctx, itemIdentifierArg(ctx, "metaItem"),
                                        IntegerArgumentType.getInteger(ctx, "metaCount"))))));
        return meta;
    }

    /** Optional trailing options in the documented order: priority, label, pin. */
    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T attachMetaOptions(T node) {
        node.then(literal("priority")
                .then(argument("metaPriority", IntegerArgumentType.integer(0, 9))
                        .executes(ctx -> targetMetaApply(ctx, itemIdentifierArg(ctx, "metaItem"), metaCount(ctx)))
                        .then(literal("label")
                                .then(argument("metaLabel", StringArgumentType.string())
                                        .executes(ctx -> targetMetaApply(ctx,
                                                itemIdentifierArg(ctx, "metaItem"), metaCount(ctx)))
                                        .then(literal("pin")
                                                .then(argument("metaPin", StringArgumentType.word())
                                                        .executes(ctx -> targetMetaApply(ctx,
                                                                itemIdentifierArg(ctx, "metaItem"), metaCount(ctx)))))))));
        node.then(literal("label")
                .then(argument("metaLabel", StringArgumentType.string())
                        .executes(ctx -> targetMetaApply(ctx, itemIdentifierArg(ctx, "metaItem"), metaCount(ctx)))
                        .then(literal("pin")
                                .then(argument("metaPin", StringArgumentType.word())
                                        .executes(ctx -> targetMetaApply(ctx,
                                                itemIdentifierArg(ctx, "metaItem"), metaCount(ctx)))))));
        node.then(literal("pin")
                .then(argument("metaPin", StringArgumentType.word())
                        .executes(ctx -> targetMetaApply(ctx, itemIdentifierArg(ctx, "metaItem"), metaCount(ctx)))));
        return node;
    }

    /** Brigadier 1.3 has no public getArguments(); detect optional arguments via lookup. */
    private static boolean hasArg(CommandContext<CommandSourceStack> ctx, String name) {
        try {
            ctx.getArgument(name, Object.class);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static int metaCount(CommandContext<CommandSourceStack> ctx) {
        return hasArg(ctx, "metaCount")
                ? IntegerArgumentType.getInteger(ctx, "metaCount") : TargetKey.MIN_COUNT;
    }

    private static int targetMetaApply(CommandContext<CommandSourceStack> ctx, String itemId, int count)
            throws CommandSyntaxException {
        ServerPlayer me = player(ctx);
        CommandSourceStack source = ctx.getSource();
        TargetKey key = TargetKey.normalize(itemId, count);
        MilestoneData data = MilestoneData.get(me.level().getServer());
        MilestoneData.PlayerData pd = data.playerOrNull(me.getUUID());
        if (key == null || pd == null || !pd.targets.contains(key)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.target_meta_not_found",
                    Component.literal(itemId)));
        }
        String encoded = key.toEncodedString();
        TargetMeta current = pd.targetMeta.getOrDefault(encoded, TargetMeta.DEFAULT);
        boolean hasPriority = hasArg(ctx, "metaPriority");
        boolean hasLabel = hasArg(ctx, "metaLabel");
        boolean hasPin = hasArg(ctx, "metaPin");
        if (!hasPriority && !hasLabel && !hasPin) {
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.target_meta_query",
                    MilestoneService.targetDisplayName(key), current.priority(),
                    Component.literal(current.label()), current.pinned()));
        }
        int priority = hasPriority ? IntegerArgumentType.getInteger(ctx, "metaPriority") : current.priority();
        String label = hasLabel ? StringArgumentType.getString(ctx, "metaLabel") : current.label();
        boolean pinned = hasPin
                ? "on".equalsIgnoreCase(StringArgumentType.getString(ctx, "metaPin"))
                : current.pinned();
        TargetMeta next = TargetMeta.of(priority, label, pinned);
        if (next.equals(TargetMeta.INVALID)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.target_meta_invalid_label",
                    TargetMeta.MAX_LABEL_LENGTH));
        }
        pd.targetMeta.put(encoded, next);
        data.setDirty();
        return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.target_meta_done",
                MilestoneService.targetDisplayName(key)));
    }

    private static int targetMetaClear(CommandContext<CommandSourceStack> ctx, String itemId, int count)
            throws CommandSyntaxException {
        ServerPlayer me = player(ctx);
        CommandSourceStack source = ctx.getSource();
        TargetKey key = TargetKey.normalize(itemId, count);
        MilestoneData data = MilestoneData.get(me.level().getServer());
        MilestoneData.PlayerData pd = data.playerOrNull(me.getUUID());
        if (key == null || pd == null || !pd.targets.contains(key)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.target_meta_not_found",
                    Component.literal(itemId)));
        }
        if (pd.targetMeta.remove(key.toEncodedString()) == null) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.target_meta_not_found",
                    Component.literal(itemId)));
        }
        data.setDirty();
        return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.target_meta_cleared",
                MilestoneService.targetDisplayName(key)));
    }

    /** F-150-31: server-side pin list, priority descending then TargetKey order, at most 20 rows. */
    private static int pinned(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer me = player(ctx);
        CommandSourceStack source = ctx.getSource();
        MilestoneData.PlayerData pd = dataOrNull(me);
        List<Map.Entry<TargetKey, TargetMeta>> pinned = new ArrayList<>();
        if (pd != null) {
            for (TargetKey key : pd.targets) {
                TargetMeta meta = pd.targetMeta.get(key.toEncodedString());
                if (meta != null && meta.pinned()) {
                    pinned.add(Map.entry(key, meta));
                }
            }
        }
        if (pinned.isEmpty()) {
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.pinned_empty"));
        }
        pinned.sort(Comparator.<Map.Entry<TargetKey, TargetMeta>>comparingInt(e -> e.getValue().priority())
                .reversed().thenComparing(Map.Entry::getKey));
        send(ctx, ServerLang.tr(source, "message.itemmilestonelog.pinned_header", pinned.size()));
        int shown = 0;
        for (Map.Entry<TargetKey, TargetMeta> entry : pinned) {
            if (shown++ >= 20) {
                break;
            }
            TargetKey key = entry.getKey();
            Component label = entry.getValue().label().isEmpty()
                    ? MilestoneService.targetDisplayName(key)
                    : Component.literal(entry.getValue().label());
            int current = Tracker.countItemsInPossession(me, Set.of(key.itemId()))
                    .getOrDefault(key.itemId(), 0);
            Component progress = ServerLang.tr(source, "message.itemmilestonelog.target_progress", label, current);
            send(ctx, ServerLang.tr(source, "message.itemmilestonelog.pinned_entry", label, progress));
        }
        return shown;
    }

    /** F-150-16: granttag writes a record for the tag key without creating a world first. */
    private static int adminGrantTag(CommandContext<CommandSourceStack> ctx, String query,
                                     String tag, int count) {
        CommandSourceStack source = ctx.getSource();
        Map.Entry<UUID, MilestoneData.PlayerData> target = resolveOrReport(source, query);
        if (target == null) {
            return 0;
        }
        if (!MilestoneService.isValidItemId(tag)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.invalid_tag", tag));
        }
        TargetKey key = TargetKey.normalize(tag, count);
        if (key == null) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.invalid_tag", tag));
        }
        MilestoneData data = MilestoneData.get(source.getServer());
        MilestoneData.PlayerData pd = data.playerOrNull(target.getKey());
        if (pd == null) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.admin_player_not_found", query));
        }
        ServerPlayer online = source.getServer().getPlayerList().getPlayer(target.getKey());
        pd.records.put(key, new MilestoneRecord(online != null ? Playtime.currentTicks(online) : 0,
                MilestoneRecord.UNKNOWN_DAY, System.currentTimeMillis()));
        data.setDirty();
        ScoreboardSync.refreshScores(source.getServer(), target.getKey());
        if (online != null) {
            MilestoneService.syncPlayerIfOpen(online);
        }
        return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.admin_granted",
                displayNameFor(target.getKey(), target.getValue()), MilestoneService.targetDisplayName(key)));
    }
}
