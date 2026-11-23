package com.pengin0503.itemmilestonelog;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
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
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
        event.getDispatcher().register(root(buildContext));
        // A redirect only forwards what follows "/iml", so the alias needs its own
        // executes() for "/iml" alone to show the summary like "/milestonelog".
        event.getDispatcher().register(LiteralArgumentBuilder.<CommandSourceStack>literal("iml")
                .executes(MilestoneCommands::summary)
                .redirect(event.getDispatcher().getRoot().getChild("milestonelog")));
    }

    private static Set<String> itemIdsOf(Set<TargetKey> keys) {
        Set<String> ids = new LinkedHashSet<>();
        for (TargetKey key : keys) {
            ids.add(key.itemId());
        }
        return ids;
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

    private static LiteralArgumentBuilder<CommandSourceStack> root(CommandBuildContext buildContext) {
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
            if (Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(ctx.getSource())) {
                return SharedSuggestionProvider.suggest(files, builder);
            }
            ServerPlayer sp = ctx.getSource().getPlayer();
            if (sp == null) {
                return builder.buildFuture();
            }
            String ownFile = MilestoneExport.sanitizePlayerFileName(sp.getGameProfile().name());
            return SharedSuggestionProvider.suggest(files.contains(ownFile) ? List.of(ownFile) : List.of(), builder);
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
            return SharedSuggestionProvider.suggest(
                    MilestoneRankings.recordedItemIds(MilestoneData.get(server).allPlayers(), rankingViewer(source)), builder);
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

        return Commands.literal("milestonelog")
                .executes(MilestoneCommands::summary)
                .then(Commands.literal("playtime").executes(MilestoneCommands::playtime))
                .then(Commands.literal("add")
                        .then(Commands.argument("item", ItemArgument.item(buildContext))
                                .executes(ctx -> add(ctx, itemId(ctx), TargetKey.MIN_COUNT))
                                .then(Commands.argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                        .executes(ctx -> add(ctx, itemId(ctx), IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(Commands.literal("addhand")
                        .executes(ctx -> addHand(ctx, TargetKey.MIN_COUNT))
                        .then(Commands.argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                .executes(ctx -> addHand(ctx, IntegerArgumentType.getInteger(ctx, "count")))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("item", IdentifierArgument.id())
                                .suggests(personalTargetSuggestions)
                                .executes(ctx -> remove(ctx, IdentifierArgument.getId(ctx, "item").toString(), TargetKey.MIN_COUNT))
                                .then(Commands.argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                        .suggests(personalTargetCountSuggestions)
                                        .executes(ctx -> remove(ctx, IdentifierArgument.getId(ctx, "item").toString(),
                                                IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(Commands.literal("removeall").executes(MilestoneCommands::removeAll))
                .then(Commands.literal("targets").executes(MilestoneCommands::targets))
                .then(Commands.literal("list").executes(MilestoneCommands::list))
                .then(Commands.literal("preset")
                        .then(Commands.literal("list").executes(MilestoneCommands::presetList))
                        .then(Commands.literal("save")
                                .executes(MilestoneCommands::presetSaveMissingName)
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> presetSave(ctx, StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("delete")
                                .executes(MilestoneCommands::presetDeleteMissingName)
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests(customPresetSuggestions)
                                        .executes(ctx -> presetDelete(ctx, StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("reload")
                                .executes(MilestoneCommands::presetReload))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(presetSuggestions)
                                .executes(ctx -> preset(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("presetprogress")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(presetSuggestions)
                                .executes(ctx -> presetProgress(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("presetremove")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(presetSuggestions)
                                .executes(ctx -> presetRemove(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("reset")
                        .then(Commands.argument("item", IdentifierArgument.id())
                                .suggests(recordedSuggestions)
                                .executes(ctx -> reset(ctx, IdentifierArgument.getId(ctx, "item").toString(), TargetKey.MIN_COUNT))
                                .then(Commands.argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                        .suggests(recordedCountSuggestions)
                                        .executes(ctx -> reset(ctx, IdentifierArgument.getId(ctx, "item").toString(),
                                                IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(Commands.literal("resetall").executes(MilestoneCommands::resetAll))
                .then(Commands.literal("undo").executes(MilestoneCommands::undo))
                .then(Commands.literal("firsts")
                        .executes(MilestoneCommands::firstsList)
                        .then(Commands.literal("reset")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .then(Commands.argument("item", IdentifierArgument.id())
                                        .suggests(worldFirstSuggestions)
                                        .executes(ctx -> firstsReset(ctx, IdentifierArgument.getId(ctx, "item").toString(), TargetKey.MIN_COUNT))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                                .suggests(worldFirstCountSuggestions)
                                                .executes(ctx -> firstsReset(ctx, IdentifierArgument.getId(ctx, "item").toString(),
                                                        IntegerArgumentType.getInteger(ctx, "count"))))))
                        .then(Commands.literal("resetall")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(MilestoneCommands::firstsResetAll)))
                .then(Commands.literal("export").executes(MilestoneCommands::exportSelf))
                .then(Commands.literal("exporthtml").executes(MilestoneCommands::exportHtmlSelf))
                .then(Commands.literal("exportcsv").executes(MilestoneCommands::exportCsvSelf))
                .then(Commands.literal("import")
                        .then(Commands.argument("fileName", StringArgumentType.word())
                                .suggests(exportFileSuggestions)
                                .executes(ctx -> importSelf(ctx, StringArgumentType.getString(ctx, "fileName")))))
                .then(Commands.literal("exportall")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(MilestoneCommands::exportAll))
                .then(Commands.literal("exporthtmlall")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(MilestoneCommands::exportHtmlAll))
                .then(Commands.literal("exportcsvall")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(MilestoneCommands::exportCsvAll))
                .then(Commands.literal("importall")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("fileName", StringArgumentType.word())
                                .suggests(exportFileSuggestions)
                                .executes(ctx -> importAll(ctx, StringArgumentType.getString(ctx, "fileName")))))
                .then(Commands.literal("mutebroadcast").executes(MilestoneCommands::muteBroadcast))
                .then(Commands.literal("muteprogress").executes(MilestoneCommands::muteProgress))
                .then(Commands.literal("discordoptout").executes(MilestoneCommands::discordOptOut))
                .then(Commands.literal("privacy").executes(MilestoneCommands::privacy))
                .then(Commands.literal("discordtest")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(MilestoneCommands::discordTest))
                .then(Commands.literal("lookup")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(lookupPlayerSuggestions)
                                .executes(ctx -> lookup(ctx, StringArgumentType.getString(ctx, "player")))))
                .then(Commands.literal("top")
                        .executes(MilestoneCommands::top)
                        .then(Commands.argument("item", IdentifierArgument.id())
                                .suggests(topItemSuggestions)
                                .executes(ctx -> topItem(ctx, IdentifierArgument.getId(ctx, "item").toString(), TargetKey.MIN_COUNT))
                                .then(Commands.argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                        .suggests(topItemCountSuggestions)
                                        .executes(ctx -> topItem(ctx, IdentifierArgument.getId(ctx, "item").toString(),
                                                IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(Commands.literal("recent")
                        .executes(ctx -> recent(ctx, MilestoneRankings.RECENT_DEFAULT))
                        .then(Commands.argument("n", IntegerArgumentType.integer(MilestoneRankings.RECENT_MIN, MilestoneRankings.RECENT_MAX))
                                .executes(ctx -> recent(ctx, IntegerArgumentType.getInteger(ctx, "n")))))
                .then(Commands.literal("stats")
                        .executes(MilestoneCommands::stats))
                .then(Commands.literal("prunedata")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("days", IntegerArgumentType.integer(PruneDataLogic.MIN_DAYS, PruneDataLogic.MAX_DAYS))
                                .executes(ctx -> pruneData(ctx, IntegerArgumentType.getInteger(ctx, "days"), false))
                                .then(Commands.literal("confirm")
                                        .executes(ctx -> pruneData(ctx, IntegerArgumentType.getInteger(ctx, "days"), true)))))
                .then(Commands.literal("admin")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("players").executes(MilestoneCommands::adminPlayers))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(adminPlayerSuggestions)
                                .then(Commands.literal("list").executes(ctx -> adminList(ctx, StringArgumentType.getString(ctx, "player"))))
                                .then(Commands.literal("targets").executes(ctx -> adminTargets(ctx, StringArgumentType.getString(ctx, "player"))))
                                .then(Commands.literal("add")
                                        .then(Commands.argument("item", ItemArgument.item(buildContext))
                                                .executes(ctx -> adminAdd(ctx, StringArgumentType.getString(ctx, "player"),
                                                        itemId(ctx), TargetKey.MIN_COUNT))
                                                .then(Commands.argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                                        .executes(ctx -> adminAdd(ctx, StringArgumentType.getString(ctx, "player"),
                                                                itemId(ctx), IntegerArgumentType.getInteger(ctx, "count"))))))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("item", IdentifierArgument.id())
                                                .suggests(adminPersonalTargetSuggestions)
                                                .executes(ctx -> adminRemove(ctx, StringArgumentType.getString(ctx, "player"),
                                                        IdentifierArgument.getId(ctx, "item").toString(), TargetKey.MIN_COUNT))
                                                .then(Commands.argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                                        .suggests(adminPersonalTargetCountSuggestions)
                                                        .executes(ctx -> adminRemove(ctx, StringArgumentType.getString(ctx, "player"),
                                                                IdentifierArgument.getId(ctx, "item").toString(),
                                                                IntegerArgumentType.getInteger(ctx, "count"))))))
                                .then(Commands.literal("preset")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .suggests(presetSuggestions)
                                                .executes(ctx -> adminPreset(ctx, StringArgumentType.getString(ctx, "player"),
                                                        StringArgumentType.getString(ctx, "name")))))
                                .then(Commands.literal("grant")
                                        .then(Commands.argument("item", ItemArgument.item(buildContext))
                                                .executes(ctx -> adminGrant(ctx, StringArgumentType.getString(ctx, "player"),
                                                        itemId(ctx), TargetKey.MIN_COUNT))
                                                .then(Commands.argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                                        .executes(ctx -> adminGrant(ctx, StringArgumentType.getString(ctx, "player"),
                                                                itemId(ctx), IntegerArgumentType.getInteger(ctx, "count"))))))
                                .then(Commands.literal("reset")
                                        .then(Commands.argument("item", IdentifierArgument.id())
                                                .suggests(adminRecordedSuggestions)
                                                .executes(ctx -> adminReset(ctx, StringArgumentType.getString(ctx, "player"),
                                                        IdentifierArgument.getId(ctx, "item").toString(), TargetKey.MIN_COUNT))
                                                .then(Commands.argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                                        .suggests(adminRecordedCountSuggestions)
                                                        .executes(ctx -> adminReset(ctx, StringArgumentType.getString(ctx, "player"),
                                                                IdentifierArgument.getId(ctx, "item").toString(),
                                                                IntegerArgumentType.getInteger(ctx, "count"))))))
                                .then(Commands.literal("resetall").executes(ctx -> adminResetAll(ctx, StringArgumentType.getString(ctx, "player"))))
                                .then(Commands.literal("purge").executes(ctx -> adminPurge(ctx, StringArgumentType.getString(ctx, "player"))))));
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

    // ------------------------------------------------------------ commands

    private static int summary(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        MilestoneData.PlayerData pd = dataOrNull(player);
        Set<TargetKey> targets = Tracker.effectiveTargets(pd);
        Map<TargetKey, MilestoneRecord> records = pd != null ? pd.records : Map.of();
        int done = (int) targets.stream().filter(records::containsKey).count();
        send(ctx, ServerLang.tr(player, "message.itemmilestonelog.summary",
                Playtime.format(Playtime.currentTicks(player)), done, targets.size()));
        if (Tracker.recordingPaused(player)) {
            send(ctx, ServerLang.tr(player, "message.itemmilestonelog.summary_creative_paused"));
        }
        return 1;
    }

    private static int playtime(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        return send(ctx, ServerLang.tr(player, "message.itemmilestonelog.playtime",
                Playtime.format(Playtime.currentTicks(player))));
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

    private static int targets(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        MilestoneData.PlayerData pd = dataOrNull(player);
        Set<TargetKey> targets = Tracker.effectiveTargets(pd);
        if (targets.isEmpty()) {
            return send(ctx, ServerLang.tr(player, "message.itemmilestonelog.no_targets"));
        }
        Map<TargetKey, MilestoneRecord> records = pd != null ? pd.records : Map.of();
        List<TargetKey> sorted = new ArrayList<>(targets);
        sorted.sort(TargetKey::compareTo);

        Set<String> carried = Tracker.carriedFromCreative(pd);
        Set<String> progressItemIds = new LinkedHashSet<>();
        for (TargetKey key : sorted) {
            if (key.count() > TargetKey.MIN_COUNT && !records.containsKey(key) && !carried.contains(key.itemId())) {
                progressItemIds.add(key.itemId());
            }
        }
        Map<String, Integer> possessionCounts = Tracker.countItemsInPossession(player, progressItemIds);

        ctx.getSource().sendSuccess(() -> ServerLang.tr(player, "message.itemmilestonelog.targets_header", sorted.size()), false);
        for (TargetKey key : sorted) {
            MilestoneRecord rec = records.get(key);
            Component line;
            if (rec != null) {
                line = ServerLang.tr(player, "message.itemmilestonelog.target_done",
                        MilestoneService.targetDisplayName(key), Playtime.format(rec.playTicks()));
            } else {
                boolean isCarried = carried.contains(key.itemId());
                Component label = MilestoneService.targetDisplayName(key);
                if (key.count() > TargetKey.MIN_COUNT) {
                    int current = isCarried ? 0 : possessionCounts.getOrDefault(key.itemId(), 0);
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
        return sorted.size();
    }

    private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        MilestoneData.PlayerData pd = dataOrNull(player);
        if (pd == null || pd.records.isEmpty()) {
            return send(ctx, ServerLang.tr(player, "message.itemmilestonelog.no_records"));
        }
        List<Map.Entry<TargetKey, MilestoneRecord>> entries = new ArrayList<>(pd.records.entrySet());
        entries.sort(Comparator.comparingInt((Map.Entry<TargetKey, MilestoneRecord> e) -> e.getValue().playTicks())
                .thenComparing(Map.Entry::getKey));
        ctx.getSource().sendSuccess(() -> ServerLang.tr(player, "message.itemmilestonelog.records_header", entries.size()), false);
        ZoneId zone = Config.displayTimeZone();
        for (var e : entries) {
            Component line = ServerLang.tr(player, "message.itemmilestonelog.target_done",
                    MilestoneService.targetDisplayName(e.getKey()), Playtime.formatRecord(e.getValue(), zone));
            ctx.getSource().sendSuccess(() -> line, false);
        }
        return entries.size();
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

    private static int presetList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!Config.PRESETS_ENABLED.get()) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        List<Presets.PresetDefinition> all = Presets.all();
        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.presets_header", all.size()), false);
        for (Presets.PresetDefinition p : all) {
            Component typeLabel = ServerLang.tr(source, p.builtin()
                    ? "message.itemmilestonelog.preset_type_builtin"
                    : "message.itemmilestonelog.preset_type_file");
            String display = p.builtin() ? p.id() : p.displayName();
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
        if (!MilestoneService.isPrivileged(source)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_no_permission"));
        }
        return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.preset_invalid_name", ""));
    }

    private static int presetDeleteMissingName(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!Config.PRESETS_ENABLED.get()) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        if (!MilestoneService.isPrivileged(source)) {
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
        if (!MilestoneService.isPrivileged(source)) {
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

    private static int firstsList(CommandContext<CommandSourceStack> ctx) {
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
        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.firsts_header", sorted.size()), false);
        ZoneId zone = Config.displayTimeZone();
        for (WorldFirstRecord wf : sorted) {
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
                    Playtime.formatRecord(wf.toMilestoneRecord(), zone)), false);
        }
        return sorted.size();
    }

    private static int firstsReset(CommandContext<CommandSourceStack> ctx, String itemId, int count) {
        CommandSourceStack source = ctx.getSource();
        MilestoneData data = MilestoneData.get(source.getServer());
        TargetKey key = TargetKey.normalize(itemId, count);
        if (key == null) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.invalid_item", String.valueOf(itemId)));
        }
        if (data.removeWorldFirst(key)) {
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.firsts_reset_done", MilestoneService.targetDisplayName(key)));
        }
        if (!MilestoneService.isValidItemId(key.itemId())) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.invalid_item", itemId));
        }
        return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.firsts_not_recorded", MilestoneService.targetDisplayName(key)));
    }

    private static int firstsResetAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        int count = MilestoneData.get(source.getServer()).clearWorldFirsts();
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
            if (!MilestoneExport.canImport(payload, player.getUUID(),
                    Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(ctx.getSource()))) {
                return fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.import_not_owner", fileName));
            }
            MilestoneData data = MilestoneData.get(player.level().getServer());
            MilestoneExport.ImportMergeStats stats = MilestoneExport.mergeInto(data, player.getUUID(), payload);
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
        CommandSourceStack source = ctx.getSource();
        if (!Config.EXPORT_IMPORT_ENABLED.get()) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        if (!MilestoneExport.isSafeFileName(fileName)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.import_invalid_name", fileName));
        }
        MinecraftServer server = source.getServer();
        Path dir = MilestoneExport.exportsDir(server).normalize();
        Path file = dir.resolve(fileName).normalize();
        if (!file.getParent().equals(dir)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.import_invalid_name", fileName));
        }
        if (!Files.isRegularFile(file)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.import_not_found", fileName));
        }
        try {
            MilestoneExport.AllPlayersImportPayload payload = MilestoneExport.parseAllPlayersExport(file);
            MilestoneData data = MilestoneData.get(server);
            MilestoneExport.ImportAllMergeStats stats = MilestoneExport.mergeAllInto(data, payload);
            MilestoneService.syncAllOnlineGuiPlayers(server, null);
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.importall_done",
                    fileName,
                    stats.importedPlayers(),
                    stats.addedTargets(),
                    stats.updatedRecords(),
                    stats.updatedWorldFirsts(),
                    stats.skippedEntries()));
        } catch (IllegalArgumentException e) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.import_invalid_format", fileName));
        } catch (IOException e) {
            ItemMilestoneLog.LOGGER.warn("[ItemMilestoneLog] Import-all of {} failed", fileName, e);
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.import_failed", fileName));
        }
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

    private static int discordTest(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        ServerPlayer executorPlayer = source.getPlayer();
        UUID executorUuid = executorPlayer != null ? executorPlayer.getUUID() : null;

        DiscordTestResult immediateError = DiscordNotifier.sendTestMessage(result -> {
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

    private static int adminPlayers(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Map<UUID, MilestoneData.PlayerData> all = MilestoneData.get(source.getServer()).allPlayers();
        if (all.isEmpty()) {
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.admin_players_empty"));
        }
        List<Map.Entry<UUID, MilestoneData.PlayerData>> sorted = new ArrayList<>(all.entrySet());
        sorted.sort(Comparator.comparing(e -> displayNameFor(e.getKey(), e.getValue()), String.CASE_INSENSITIVE_ORDER));
        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.admin_players_header", sorted.size()), false);
        for (Map.Entry<UUID, MilestoneData.PlayerData> e : sorted) {
            String name = displayNameFor(e.getKey(), e.getValue());
            int targetCount = Tracker.effectiveTargets(e.getValue()).size();
            int recordCount = e.getValue().records.size();
            source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.admin_players_entry",
                    name, e.getKey().toString(), targetCount, recordCount), false);
        }
        return sorted.size();
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

    private static int adminList(CommandContext<CommandSourceStack> ctx, String query) {
        CommandSourceStack source = ctx.getSource();
        Map.Entry<UUID, MilestoneData.PlayerData> target = resolveOrReport(source, query);
        if (target == null) {
            return 0;
        }
        return sendRecordList(ctx, target);
    }

    // --------------------------------------------------------- F6 lookup

    /** Names offered for {@code lookup <player>}: only targets this source may actually look up (F6 / F14). */
    private static Set<String> lookupSuggestionNames(CommandSourceStack source, MinecraftServer server) {
        boolean allowLookup = Config.ALLOW_LOOKUP.get();
        boolean privileged = MilestoneService.isPrivileged(source);
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

    private static int lookup(CommandContext<CommandSourceStack> ctx, String query) {
        CommandSourceStack source = ctx.getSource();
        boolean allowLookup = Config.ALLOW_LOOKUP.get();
        boolean privileged = MilestoneService.isPrivileged(source);
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
            case ALLOWED -> sendRecordList(ctx, target);
        };
    }

    // --------------------------------------------------------- F13 top / recent

    /** Same gate as F6 lookup of other players: {@code allowLookup}, or {@code isPrivileged} when it is off. */
    private static boolean canUseRankings(CommandSourceStack source) {
        return LookupPolicy.canLookUpOthers(Config.ALLOW_LOOKUP.get(), MilestoneService.isPrivileged(source));
    }

    private static MilestoneRankings.Viewer rankingViewer(CommandSourceStack source) {
        ServerPlayer viewer = source.getPlayer();
        return new MilestoneRankings.Viewer(viewer != null ? viewer.getUUID() : null, MilestoneService.isPrivileged(source));
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
        CommandSourceStack source = ctx.getSource();
        if (!canUseRankings(source)) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.feature_disabled"));
        }
        TargetKey key = TargetKey.normalize(itemId, count);
        if (key == null) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.invalid_item", String.valueOf(itemId)));
        }
        MilestoneRankings.Viewer viewer = rankingViewer(source);
        MilestoneRankings.Ranking<MilestoneRankings.ItemEntry> ranking =
                MilestoneRankings.topByItem(MilestoneData.get(source.getServer()).allPlayers(), key, viewer);
        if (ranking.entries().isEmpty()) {
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.top_empty"));
        }
        ZoneId zone = Config.displayTimeZone();
        Component targetName = MilestoneService.targetDisplayName(key);
        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.top_item_header",
                targetName, ranking.entries().size()), false);
        for (MilestoneRankings.ItemEntry e : ranking.entries().subList(0, Math.min(MilestoneRankings.TOP_LIMIT, ranking.entries().size()))) {
            source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.top_item_entry",
                    e.rank(), Component.literal(e.name()), Playtime.formatRecord(e.record(), zone)), false);
        }
        if (viewer.uuid() != null) {
            MilestoneRankings.ItemEntry self = ranking.self();
            Component selfLine = self != null
                    ? ServerLang.tr(source, "message.itemmilestonelog.top_item_self", self.rank(), Playtime.formatRecord(self.record(), zone))
                    : ServerLang.tr(source, "message.itemmilestonelog.top_item_self_none");
            source.sendSuccess(() -> selfLine, false);
        }
        return ranking.entries().size();
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
                    MilestoneService.targetDisplayName(r.key()), Playtime.formatRecord(r.record(), zone)), false);
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

    /** Shared record listing for {@code admin <player> list} and {@code lookup <player>} (same format). */
    private static int sendRecordList(CommandContext<CommandSourceStack> ctx, Map.Entry<UUID, MilestoneData.PlayerData> target) {
        CommandSourceStack source = ctx.getSource();
        String name = displayNameFor(target.getKey(), target.getValue());
        MilestoneData.PlayerData pd = target.getValue();
        if (pd.records.isEmpty()) {
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.admin_no_records", name));
        }
        List<Map.Entry<TargetKey, MilestoneRecord>> entries = new ArrayList<>(pd.records.entrySet());
        entries.sort(Comparator.comparingInt((Map.Entry<TargetKey, MilestoneRecord> e) -> e.getValue().playTicks())
                .thenComparing(Map.Entry::getKey));
        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.admin_records_header", name, entries.size()), false);
        ZoneId zone = Config.displayTimeZone();
        for (var e : entries) {
            source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.target_done",
                    MilestoneService.targetDisplayName(e.getKey()), Playtime.formatRecord(e.getValue(), zone)), false);
        }
        return entries.size();
    }

    private static int adminTargets(CommandContext<CommandSourceStack> ctx, String query) {
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
        source.sendSuccess(() -> ServerLang.tr(source, "message.itemmilestonelog.admin_targets_header", name, sorted.size()), false);
        for (TargetKey key : sorted) {
            MilestoneRecord rec = pd.records.get(key);
            Component line = rec != null
                    ? ServerLang.tr(source, "message.itemmilestonelog.target_done", MilestoneService.targetDisplayName(key), Playtime.format(rec.playTicks()))
                    : ServerLang.tr(source, "message.itemmilestonelog.target_pending", MilestoneService.targetDisplayName(key));
            source.sendSuccess(() -> line, false);
        }
        return sorted.size();
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
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.invalid_item", String.valueOf(itemId)));
        }
        if (target.getValue().records.remove(key) != null) {
            target.getValue().progressNotified.remove(key);
            data.setDirty();
            ServerPlayer online = source.getServer().getPlayerList().getPlayer(target.getKey());
            if (online != null) {
                MilestoneService.syncPlayerIfOpen(online);
            }
            return send(ctx, ServerLang.tr(source, "message.itemmilestonelog.admin_reset_done", name, MilestoneService.targetDisplayName(key)));
        }
        if (!MilestoneService.isValidItemId(key.itemId())) {
            return fail(ctx, ServerLang.tr(source, "message.itemmilestonelog.invalid_item", itemId));
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
        MilestoneData.PlayerData pd = target.getValue();
        int count = pd.records.size();
        boolean clearedProgress = !pd.progressNotified.isEmpty();
        boolean clearedCompletedPresets = !pd.completedPresets.isEmpty();
        if (count > 0) {
            pd.records.clear();
        }
        if (clearedProgress) {
            pd.progressNotified.clear();
        }
        if (clearedCompletedPresets) {
            pd.completedPresets.clear();
        }
        if (count > 0 || clearedProgress || clearedCompletedPresets) {
            data.setDirty();
            if (count > 0) {
                ServerPlayer online = source.getServer().getPlayerList().getPlayer(target.getKey());
                if (online != null) {
                    MilestoneService.syncPlayerIfOpen(online);
                }
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
}
