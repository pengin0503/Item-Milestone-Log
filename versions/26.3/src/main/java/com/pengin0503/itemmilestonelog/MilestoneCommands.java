package com.pengin0503.itemmilestonelog;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
                .then(literal("targets")
                        .executes(ctx -> targets(ctx, Pagination.TargetFilter.ALL, null))
                        .then(literal("pending")
                                .executes(ctx -> targets(ctx, Pagination.TargetFilter.PENDING, null))
                                .then(argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> targets(ctx, Pagination.TargetFilter.PENDING,
                                                IntegerArgumentType.getInteger(ctx, "page"))))
                                .then(filterBranch(Pagination.TargetFilter.PENDING, kindSuggestions))
                                .then(kindBranch(Pagination.TargetFilter.PENDING, kindSuggestions)))
                        .then(literal("done")
                                .executes(ctx -> targets(ctx, Pagination.TargetFilter.DONE, null))
                                .then(argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> targets(ctx, Pagination.TargetFilter.DONE,
                                                IntegerArgumentType.getInteger(ctx, "page"))))
                                .then(filterBranch(Pagination.TargetFilter.DONE, kindSuggestions))
                                .then(kindBranch(Pagination.TargetFilter.DONE, kindSuggestions)))
                        .then(argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> targets(ctx, Pagination.TargetFilter.ALL,
                                        IntegerArgumentType.getInteger(ctx, "page"))))
                        .then(filterBranch(Pagination.TargetFilter.ALL, kindSuggestions))
                        .then(kindBranch(Pagination.TargetFilter.ALL, kindSuggestions)))
                .then(literal("list")
                        .executes(ctx -> list(ctx, null))
                        .then(argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> list(ctx, IntegerArgumentType.getInteger(ctx, "page"))))
                        .then(literal("filter")
                                .then(argument("query", StringArgumentType.string())
                                        .executes(ctx -> list(ctx, null, StringArgumentType.getString(ctx, "query")))
                                        .then(argument("page", IntegerArgumentType.integer(1))
                                                .executes(ctx -> list(ctx, IntegerArgumentType.getInteger(ctx, "page"),
                                                        StringArgumentType.getString(ctx, "query")))))))
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
                .then(literal("undo").executes(MilestoneCommands::undo))
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
                .then(literal("presettop")
                        .then(argument("preset", StringArgumentType.word())
                                .suggests(presetSuggestions)
                                .executes(ctx -> presettop(ctx, StringArgumentType.getString(ctx, "preset"), null))
                                .then(argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> presettop(ctx, StringArgumentType.getString(ctx, "preset"),
                                                IntegerArgumentType.getInteger(ctx, "page"))))))
                .then(literal("info")
                        .then(argument("item", IdentifierArgument.id())
                                .suggests(infoItemSuggestions)
                                .executes(ctx -> infoItem(ctx, IdentifierArgument.getId(ctx, "item").toString(), TargetKey.MIN_COUNT))
                                .then(argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                        .suggests(infoItemCountSuggestions)
                                        .executes(ctx -> infoItem(ctx, IdentifierArgument.getId(ctx, "item").toString(),
                                                IntegerArgumentType.getInteger(ctx, "count")))))
                        .then(literal("hand")
                                .executes(MilestoneCommands::infoHand)))
                .then(literal("export").executes(MilestoneCommands::exportSelf))
                .then(literal("exporthtml").executes(MilestoneCommands::exportHtmlSelf))
                .then(literal("exportcsv").executes(MilestoneCommands::exportCsvSelf))
                .then(literal("import")
                        .then(argument("fileName", StringArgumentType.word())
                                .suggests(exportFileSuggestions)
                                .executes(ctx -> importSelf(ctx, StringArgumentType.getString(ctx, "fileName")))))
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
                                .executes(ctx -> importAll(ctx, StringArgumentType.getString(ctx, "fileName")))))
                .then(literal("mutebroadcast").executes(MilestoneCommands::muteBroadcast))
                .then(literal("muteprogress").executes(MilestoneCommands::muteProgress))
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
                .then(literal("top")
                        .executes(MilestoneCommands::top)
                        .then(argument("item", IdentifierArgument.id())
                                .suggests(topItemSuggestions)
                                .executes(ctx -> topItem(ctx, IdentifierArgument.getId(ctx, "item").toString(), TargetKey.MIN_COUNT, false))
                                .then(literal("gaps")
                                        .executes(ctx -> topItem(ctx, IdentifierArgument.getId(ctx, "item").toString(),
                                                TargetKey.MIN_COUNT, true)))
                                .then(argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                        .suggests(topItemCountSuggestions)
                                        .executes(ctx -> topItem(ctx, IdentifierArgument.getId(ctx, "item").toString(),
                                                IntegerArgumentType.getInteger(ctx, "count"), false))
                                        .then(literal("gaps")
                                                .executes(ctx -> topItem(ctx, IdentifierArgument.getId(ctx, "item").toString(),
                                                        IntegerArgumentType.getInteger(ctx, "count"), true))))))
                .then(literal("recent")
                        .executes(ctx -> recent(ctx, MilestoneRankings.RECENT_DEFAULT))
                        .then(argument("n", IntegerArgumentType.integer(MilestoneRankings.RECENT_MIN, MilestoneRankings.RECENT_MAX))
                                .executes(ctx -> recent(ctx, IntegerArgumentType.getInteger(ctx, "n")))))
                .then(literal("stats")
                        .executes(MilestoneCommands::stats)
                        .then(argument("item", IdentifierArgument.id())
                                .suggests(topItemSuggestions)
                                .executes(ctx -> statsItem(ctx, IdentifierArgument.getId(ctx, "item").toString(), TargetKey.MIN_COUNT))
                                .then(argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                        .suggests(topItemCountSuggestions)
                                        .executes(ctx -> statsItem(ctx, IdentifierArgument.getId(ctx, "item").toString(),
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
    private record FilterCriteria(List<String> tokens,
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
                suffix.append("filter \"").append(rawQuery).append('"');
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
            return rawQuery == null ? "" : "filter \"" + rawQuery + '"';
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

    /** Fresh {@code filter <query> [kind <kind>] [<page>]} branch for one record-state filter. */
    private static LiteralArgumentBuilder<CommandSourceStack> filterBranch(
            Pagination.TargetFilter filter, SuggestionProvider<CommandSourceStack> kindSuggestions) {
        return literal("filter")
                .then(argument("query", StringArgumentType.string())
                        .executes(ctx -> targets(ctx, filter, null, StringArgumentType.getString(ctx, "query"), null))
                        .then(argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> targets(ctx, filter, IntegerArgumentType.getInteger(ctx, "page"),
                                        StringArgumentType.getString(ctx, "query"), null)))
                        .then(literal("kind")
                                .then(argument("kind", StringArgumentType.word())
                                        .suggests(kindSuggestions)
                                        .executes(ctx -> targets(ctx, filter, null,
                                                StringArgumentType.getString(ctx, "query"),
                                                StringArgumentType.getString(ctx, "kind")))
                                        .then(argument("page", IntegerArgumentType.integer(1))
                                                .executes(ctx -> targets(ctx, filter,
                                                        IntegerArgumentType.getInteger(ctx, "page"),
                                                        StringArgumentType.getString(ctx, "query"),
                                                        StringArgumentType.getString(ctx, "kind")))))));
    }

    /** Fresh standalone {@code kind <kind> [<page>]} branch for one record-state filter. */
    private static LiteralArgumentBuilder<CommandSourceStack> kindBranch(
            Pagination.TargetFilter filter, SuggestionProvider<CommandSourceStack> kindSuggestions) {
        return literal("kind")
                .then(argument("kind", StringArgumentType.word())
                        .suggests(kindSuggestions)
                        .executes(ctx -> targets(ctx, filter, null, null, StringArgumentType.getString(ctx, "kind")))
                        .then(argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> targets(ctx, filter, IntegerArgumentType.getInteger(ctx, "page"),
                                        null, StringArgumentType.getString(ctx, "kind")))));
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
        return targets(ctx, filter, page, null, null);
    }

    private static int targets(CommandContext<CommandSourceStack> ctx,
                               Pagination.TargetFilter filter,
                               @Nullable Integer page,
                               @Nullable String rawQuery,
                               @Nullable String kindArg) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        FilterCriteria criteria = parseCriteria(ctx, player, rawQuery, kindArg);
        if (criteria == null) {
            return 0;
        }
        MilestoneData.PlayerData pd = dataOrNull(player);
        Set<TargetKey> targets = Tracker.effectiveTargets(pd);
        if (targets.isEmpty()) {
            return send(ctx, ServerLang.tr(player, criteria.active()
                    ? "message.itemmilestonelog.targets_filter_none"
                    : "message.itemmilestonelog.no_targets"));
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
            if (criteria.active()) {
                return send(ctx, ServerLang.tr(player, "message.itemmilestonelog.targets_filter_none"));
            }
            String emptyKey = filter == Pagination.TargetFilter.PENDING
                    ? "message.itemmilestonelog.targets_pending_none"
                    : "message.itemmilestonelog.targets_done_none";
            return send(ctx, ServerLang.tr(player, emptyKey));
        }
        sorted.sort(TargetKey::compareTo);

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
        for (TargetKey key : displayed) {
            // Tag totals already exclude creative-carried members (F8b), so tags are always counted.
            if (key.count() > TargetKey.MIN_COUNT && !records.containsKey(key)
                    && (key.isTag() || !carried.contains(key.itemId()))) {
                progressItemIds.add(key.itemId());
            }
        }
        Map<String, Integer> possessionCounts = progressItemIds.isEmpty()
                ? Map.of()
                : Tracker.countItemsInPossession(player, progressItemIds);

        String headerKey = switch (filter) {
            case ALL -> "message.itemmilestonelog.targets_header";
            case PENDING -> "message.itemmilestonelog.targets_pending_header";
            case DONE -> "message.itemmilestonelog.targets_done_header";
        };
        int headerCount = sorted.size();
        ctx.getSource().sendSuccess(() -> ServerLang.tr(player, headerKey, headerCount), false);
        for (TargetKey key : displayed) {
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
            sendPageFooter(ctx.getSource(), slice, Pagination.targetsCommandPrefix(filter, criteria.targetSuffix()));
        }
        return displayed.size();
    }

    private static int list(CommandContext<CommandSourceStack> ctx, @Nullable Integer page) throws CommandSyntaxException {
        return list(ctx, page, null);
    }

    private static int list(CommandContext<CommandSourceStack> ctx, @Nullable Integer page,
                            @Nullable String rawQuery) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        FilterCriteria criteria = parseCriteria(ctx, player, rawQuery, null);
        if (criteria == null) {
            return 0;
        }
        MilestoneData.PlayerData pd = dataOrNull(player);
        if (pd == null || pd.records.isEmpty()) {
            return send(ctx, ServerLang.tr(player, criteria.active()
                    ? "message.itemmilestonelog.list_filter_none"
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
            return send(ctx, ServerLang.tr(player, "message.itemmilestonelog.list_filter_none"));
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

        int headerCount = entries.size();
        ctx.getSource().sendSuccess(() -> ServerLang.tr(player, "message.itemmilestonelog.records_header", headerCount), false);
        ZoneId zone = Config.displayTimeZone();
        for (var e : displayed) {
            Component line = ServerLang.tr(player, "message.itemmilestonelog.target_done",
                    MilestoneService.targetDisplayName(e.getKey()), Playtime.formatRecordComponent(player, e.getValue(), zone));
            ctx.getSource().sendSuccess(() -> line, false);
        }
        if (slice != null) {
            sendPageFooter(ctx.getSource(), slice, Pagination.listCommandPrefix(criteria.listSuffix()));
        }
        return displayed.size();
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

    private static int importSelf(CommandContext<CommandSourceStack> ctx, String fileName) throws CommandSyntaxException {
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

    private static int importAll(CommandContext<CommandSourceStack> ctx, String fileName) {
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

    // --------------------------------------------------------- F45 Discord opt-out

    private static int discordOptOut(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return respond(ctx, MilestoneService.toggleDiscordOptOut(player(ctx)));
    }

    // --------------------------------------------------------- F14 privacy (hide from lookup/top/recent/stats)

    private static int privacy(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return respond(ctx, MilestoneService.togglePrivacy(player(ctx)));
    }

    // --------------------------------------------------------- F49 Discord test

    private static int discordTest(CommandContext<CommandSourceStack> ctx, DiscordRouting.Destination destination) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        ServerPlayer executorPlayer = source.getPlayer();
        UUID executorUuid = executorPlayer != null ? executorPlayer.getUUID() : null;

        DiscordTestResult immediateError = DiscordNotifier.sendTestMessage(destination, result -> {
            if (server == null) {
                return;
            }
            server.execute(() -> deliverDiscordTestResult(server, source, executorPlayer, executorUuid, result));
        });
        if (immediateError != null) {
            return fail(ctx, formatDiscordTestResult(source.getPlayer(), immediateError));
        }
        return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.discordtest_sending"));
    }

    private static void deliverDiscordTestResult(
            MinecraftServer server,
            CommandSourceStack source,
            @Nullable ServerPlayer executorPlayer,
            @Nullable UUID executorUuid,
            DiscordTestResult result) {
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
                        Playtime.format((int) e.playTicks())), false);
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
    private static int infoItem(CommandContext<CommandSourceStack> ctx, String itemId, int count)
            throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        TargetKey key = TargetKey.normalize(itemId, count);
        if (key == null) {
            return fail(ctx, ServerLang.tr(player, MilestoneService.invalidTargetKey(itemId),
                    String.valueOf(itemId)));
        }
        return sendInfo(ctx, player, key);
    }

    /**
     * F51 {@code /milestonelog info hand}: the main-hand item with count 1. An empty hand replies
     * with the same message as {@code addhand}.
     */
    private static int infoHand(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            return fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.addhand_empty"));
        }
        String itemId = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        return sendInfo(ctx, player, TargetKey.of(itemId, TargetKey.MIN_COUNT));
    }

    /** Renders the five F51 info steps for {@code key}; returns the number of lines sent. */
    private static int sendInfo(CommandContext<CommandSourceStack> ctx, ServerPlayer player, TargetKey key) {
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
        ItemInfoLogic.RelatedTags relatedTags = ItemInfoLogic.relatedTagEntries(
                relatedKeys, records, effectiveTargets, carried, tagIndex, possession);
        if (!relatedTags.isEmpty()) {
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
        return lines;
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
}
