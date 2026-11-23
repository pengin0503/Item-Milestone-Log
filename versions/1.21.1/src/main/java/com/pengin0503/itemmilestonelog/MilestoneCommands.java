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
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
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
            MilestoneData.PlayerData pd = MilestoneData.get(sp.server).playerOrNull(sp.getUUID());
            return SharedSuggestionProvider.suggest(pd != null ? itemIdsOf(pd.targets) : Set.of(), builder);
        };
        SuggestionProvider<CommandSourceStack> personalTargetCountSuggestions = (ctx, builder) -> {
            ServerPlayer sp = ctx.getSource().getPlayer();
            if (sp == null) {
                return builder.buildFuture();
            }
            MilestoneData.PlayerData pd = MilestoneData.get(sp.server).playerOrNull(sp.getUUID());
            if (pd == null) {
                return builder.buildFuture();
            }
            String itemId = ResourceLocationArgument.getId(ctx, "item").toString();
            return SharedSuggestionProvider.suggest(countsForItem(pd.targets, itemId), builder);
        };
        SuggestionProvider<CommandSourceStack> recordedSuggestions = (ctx, builder) -> {
            ServerPlayer sp = ctx.getSource().getPlayer();
            if (sp == null) {
                return builder.buildFuture();
            }
            MilestoneData.PlayerData pd = MilestoneData.get(sp.server).playerOrNull(sp.getUUID());
            return SharedSuggestionProvider.suggest(pd != null ? itemIdsOf(pd.records.keySet()) : Set.of(), builder);
        };
        SuggestionProvider<CommandSourceStack> recordedCountSuggestions = (ctx, builder) -> {
            ServerPlayer sp = ctx.getSource().getPlayer();
            if (sp == null) {
                return builder.buildFuture();
            }
            MilestoneData.PlayerData pd = MilestoneData.get(sp.server).playerOrNull(sp.getUUID());
            if (pd == null) {
                return builder.buildFuture();
            }
            String itemId = ResourceLocationArgument.getId(ctx, "item").toString();
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
            String itemId = ResourceLocationArgument.getId(ctx, "item").toString();
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
            if (ctx.getSource().hasPermission(2)) {
                return SharedSuggestionProvider.suggest(files, builder);
            }
            ServerPlayer sp = ctx.getSource().getPlayer();
            if (sp == null) {
                return builder.buildFuture();
            }
            String ownFile = MilestoneExport.sanitizePlayerFileName(sp.getGameProfile().getName());
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
            String itemId = ResourceLocationArgument.getId(ctx, "item").toString();
            return SharedSuggestionProvider.suggest(countsForItem(resolved.entry.getValue().records.keySet(), itemId), builder);
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
                        .then(Commands.argument("item", ResourceLocationArgument.id())
                                .suggests(personalTargetSuggestions)
                                .executes(ctx -> remove(ctx, ResourceLocationArgument.getId(ctx, "item").toString(), TargetKey.MIN_COUNT))
                                .then(Commands.argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                        .suggests(personalTargetCountSuggestions)
                                        .executes(ctx -> remove(ctx, ResourceLocationArgument.getId(ctx, "item").toString(),
                                                IntegerArgumentType.getInteger(ctx, "count"))))))
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
                .then(Commands.literal("reset")
                        .then(Commands.argument("item", ResourceLocationArgument.id())
                                .suggests(recordedSuggestions)
                                .executes(ctx -> reset(ctx, ResourceLocationArgument.getId(ctx, "item").toString(), TargetKey.MIN_COUNT))
                                .then(Commands.argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                        .suggests(recordedCountSuggestions)
                                        .executes(ctx -> reset(ctx, ResourceLocationArgument.getId(ctx, "item").toString(),
                                                IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(Commands.literal("resetall").executes(MilestoneCommands::resetAll))
                .then(Commands.literal("firsts")
                        .executes(MilestoneCommands::firstsList)
                        .then(Commands.literal("reset")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("item", ResourceLocationArgument.id())
                                        .suggests(worldFirstSuggestions)
                                        .executes(ctx -> firstsReset(ctx, ResourceLocationArgument.getId(ctx, "item").toString(), TargetKey.MIN_COUNT))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                                .suggests(worldFirstCountSuggestions)
                                                .executes(ctx -> firstsReset(ctx, ResourceLocationArgument.getId(ctx, "item").toString(),
                                                        IntegerArgumentType.getInteger(ctx, "count"))))))
                        .then(Commands.literal("resetall")
                                .requires(src -> src.hasPermission(2))
                                .executes(MilestoneCommands::firstsResetAll)))
                .then(Commands.literal("export").executes(MilestoneCommands::exportSelf))
                .then(Commands.literal("import")
                        .then(Commands.argument("fileName", StringArgumentType.word())
                                .suggests(exportFileSuggestions)
                                .executes(ctx -> importSelf(ctx, StringArgumentType.getString(ctx, "fileName")))))
                .then(Commands.literal("exportall")
                        .requires(src -> src.hasPermission(2))
                        .executes(MilestoneCommands::exportAll))
                .then(Commands.literal("mutebroadcast").executes(MilestoneCommands::muteBroadcast))
                .then(Commands.literal("admin")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("players").executes(MilestoneCommands::adminPlayers))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(adminPlayerSuggestions)
                                .then(Commands.literal("list").executes(ctx -> adminList(ctx, StringArgumentType.getString(ctx, "player"))))
                                .then(Commands.literal("targets").executes(ctx -> adminTargets(ctx, StringArgumentType.getString(ctx, "player"))))
                                .then(Commands.literal("reset")
                                        .then(Commands.argument("item", ResourceLocationArgument.id())
                                                .suggests(adminRecordedSuggestions)
                                                .executes(ctx -> adminReset(ctx, StringArgumentType.getString(ctx, "player"),
                                                        ResourceLocationArgument.getId(ctx, "item").toString(), TargetKey.MIN_COUNT))
                                                .then(Commands.argument("count", IntegerArgumentType.integer(TargetKey.MIN_COUNT, TargetKey.MAX_COUNT))
                                                        .suggests(adminRecordedCountSuggestions)
                                                        .executes(ctx -> adminReset(ctx, StringArgumentType.getString(ctx, "player"),
                                                                ResourceLocationArgument.getId(ctx, "item").toString(),
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
        return MilestoneData.get(player.server).playerOrNull(player.getUUID());
    }

    private static String itemId(CommandContext<CommandSourceStack> ctx) {
        Item item = ItemArgument.getItem(ctx, "item").getItem();
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
        return send(ctx, ServerLang.tr(player, "message.itemmilestonelog.summary",
                Playtime.format(Playtime.currentTicks(player)), done, targets.size()));
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
                Component label = MilestoneService.targetDisplayName(key);
                if (key.count() > TargetKey.MIN_COUNT) {
                    int current = carried.contains(key.itemId()) ? 0 : possessionCounts.getOrDefault(key.itemId(), 0);
                    label = ServerLang.tr(player, "message.itemmilestonelog.target_progress", label, current);
                }
                line = ServerLang.tr(player, "message.itemmilestonelog.target_pending", label);
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
            MilestoneService.syncAllOnlineGuiPlayers(player.server, null);
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
        return respond(ctx, MilestoneService.resetRecord(player(ctx), itemId, count));
    }

    private static int resetAll(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return respond(ctx, MilestoneService.resetAllRecords(player(ctx)));
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
            String playerName = (wf.playerName() == null || wf.playerName().isEmpty())
                    ? wf.playerUuid().toString()
                    : wf.playerName();
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
        MilestoneData data = MilestoneData.get(player.server);
        MilestoneData.PlayerData pd = data.playerOrNull(player.getUUID());
        String playerName = player.getGameProfile().getName();
        Path dir = MilestoneExport.exportsDir(player.server);
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
        Path dir = MilestoneExport.exportsDir(player.server).normalize();
        Path file = dir.resolve(fileName).normalize();
        if (!file.getParent().equals(dir)) {
            return fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.import_invalid_name", fileName));
        }
        if (!Files.isRegularFile(file)) {
            return fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.import_not_found", fileName));
        }
        try {
            MilestoneExport.ImportPayload payload = MilestoneExport.parseSinglePlayerExport(file);
            if (!MilestoneExport.canImport(payload, player.getUUID(), ctx.getSource().hasPermission(2))) {
                return fail(ctx, ServerLang.tr(player, "message.itemmilestonelog.import_not_owner", fileName));
            }
            MilestoneData data = MilestoneData.get(player.server);
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

    // --------------------------------------------------------- F11 mute broadcast

    private static int muteBroadcast(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        MilestoneData data = MilestoneData.get(player.server);
        MilestoneData.PlayerData pd = data.player(player.getUUID());
        pd.muteBroadcasts = !pd.muteBroadcasts;
        data.setDirty();
        String key = pd.muteBroadcasts
                ? "message.itemmilestonelog.mutebroadcast_on"
                : "message.itemmilestonelog.mutebroadcast_off";
        return send(ctx, ServerLang.tr(player, key));
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
        return (pd.lastKnownName != null && !pd.lastKnownName.isBlank()) ? pd.lastKnownName : uuid.toString();
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
        int count = target.getValue().records.size();
        if (count > 0) {
            target.getValue().records.clear();
            data.setDirty();
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
}
