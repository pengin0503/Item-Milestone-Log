package com.pengin0503.itemmilestonelog;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * /milestonelog (alias /iml) - manage personal target items and view records.
 * All commands are usable by every player for their own data; no OP required.
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

    private static LiteralArgumentBuilder<CommandSourceStack> root(CommandBuildContext buildContext) {
        SuggestionProvider<CommandSourceStack> presetSuggestions = (ctx, builder) ->
                SharedSuggestionProvider.suggest(Presets.names(), builder);
        SuggestionProvider<CommandSourceStack> personalTargetSuggestions = (ctx, builder) -> {
            ServerPlayer sp = ctx.getSource().getPlayer();
            if (sp == null) {
                return builder.buildFuture();
            }
            MilestoneData.PlayerData pd = MilestoneData.get(sp.server).playerOrNull(sp.getUUID());
            return SharedSuggestionProvider.suggest(pd != null ? pd.targets : Set.of(), builder);
        };
        SuggestionProvider<CommandSourceStack> recordedSuggestions = (ctx, builder) -> {
            ServerPlayer sp = ctx.getSource().getPlayer();
            if (sp == null) {
                return builder.buildFuture();
            }
            MilestoneData.PlayerData pd = MilestoneData.get(sp.server).playerOrNull(sp.getUUID());
            return SharedSuggestionProvider.suggest(pd != null ? pd.records.keySet() : Set.of(), builder);
        };

        return Commands.literal("milestonelog")
                .executes(MilestoneCommands::summary)
                .then(Commands.literal("playtime").executes(MilestoneCommands::playtime))
                .then(Commands.literal("add")
                        .then(Commands.argument("item", ItemArgument.item(buildContext))
                                .executes(ctx -> add(ctx, itemId(ctx)))))
                .then(Commands.literal("addhand").executes(MilestoneCommands::addHand))
                .then(Commands.literal("remove")
                        .then(Commands.argument("item", ResourceLocationArgument.id())
                                .suggests(personalTargetSuggestions)
                                .executes(ctx -> remove(ctx, ResourceLocationArgument.getId(ctx, "item").toString()))))
                .then(Commands.literal("targets").executes(MilestoneCommands::targets))
                .then(Commands.literal("list").executes(MilestoneCommands::list))
                .then(Commands.literal("preset")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(presetSuggestions)
                                .executes(ctx -> preset(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("reset")
                        .then(Commands.argument("item", ResourceLocationArgument.id())
                                .suggests(recordedSuggestions)
                                .executes(ctx -> reset(ctx, ResourceLocationArgument.getId(ctx, "item").toString()))))
                .then(Commands.literal("resetall").executes(MilestoneCommands::resetAll));
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

    // ------------------------------------------------------------ commands

    private static int summary(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        MilestoneData.PlayerData pd = dataOrNull(player);
        Set<String> targets = Tracker.effectiveTargets(pd);
        Map<String, Integer> records = pd != null ? pd.records : Map.of();
        // int, not long: a long argument is sent as an NBT long, which some clients and
        // chat relays cannot render (mineflayer shows it as an empty string).
        int done = (int) targets.stream().filter(records::containsKey).count();
        return send(ctx, ServerLang.tr("message.itemmilestonelog.summary",
                Playtime.format(Playtime.currentTicks(player)), done, targets.size()));
    }

    private static int playtime(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        return send(ctx, ServerLang.tr("message.itemmilestonelog.playtime",
                Playtime.format(Playtime.currentTicks(player))));
    }

    private static int add(CommandContext<CommandSourceStack> ctx, String itemId) throws CommandSyntaxException {
        return send(ctx, MilestoneService.addTarget(player(ctx), itemId).message());
    }

    private static int addHand(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return send(ctx, MilestoneService.addHandTarget(player(ctx)).message());
    }

    private static int remove(CommandContext<CommandSourceStack> ctx, String itemId) throws CommandSyntaxException {
        return send(ctx, MilestoneService.removeTarget(player(ctx), itemId).message());
    }

    private static int targets(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        MilestoneData.PlayerData pd = dataOrNull(player);
        Set<String> targets = Tracker.effectiveTargets(pd);
        if (targets.isEmpty()) {
            return send(ctx, ServerLang.tr("message.itemmilestonelog.no_targets"));
        }
        Map<String, Integer> records = pd != null ? pd.records : Map.of();
        List<String> sorted = new ArrayList<>(targets);
        sorted.sort(String::compareTo);
        ctx.getSource().sendSuccess(() -> ServerLang.tr("message.itemmilestonelog.targets_header", sorted.size()), false);
        for (String id : sorted) {
            Integer ticks = records.get(id);
            Component line = ticks != null
                    ? ServerLang.tr("message.itemmilestonelog.target_done", MilestoneService.itemName(id), Playtime.format(ticks))
                    : ServerLang.tr("message.itemmilestonelog.target_pending", MilestoneService.itemName(id));
            ctx.getSource().sendSuccess(() -> line, false);
        }
        return sorted.size();
    }

    private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        MilestoneData.PlayerData pd = dataOrNull(player);
        if (pd == null || pd.records.isEmpty()) {
            return send(ctx, ServerLang.tr("message.itemmilestonelog.no_records"));
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(pd.records.entrySet());
        entries.sort(java.util.Comparator.comparingInt(Map.Entry::getValue));
        ctx.getSource().sendSuccess(() -> ServerLang.tr("message.itemmilestonelog.records_header", entries.size()), false);
        for (var e : entries) {
            Component line = ServerLang.tr("message.itemmilestonelog.target_done", MilestoneService.itemName(e.getKey()), Playtime.format(e.getValue()));
            ctx.getSource().sendSuccess(() -> line, false);
        }
        return entries.size();
    }

    private static int preset(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        return send(ctx, MilestoneService.applyPreset(player(ctx), name).message());
    }

    private static int reset(CommandContext<CommandSourceStack> ctx, String itemId) throws CommandSyntaxException {
        return send(ctx, MilestoneService.resetRecord(player(ctx), itemId).message());
    }

    private static int resetAll(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return send(ctx, MilestoneService.resetAllRecords(player(ctx)).message());
    }
}
