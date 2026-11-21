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
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;
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
        event.getDispatcher().register(LiteralArgumentBuilder.<CommandSourceStack>literal("iml")
                .redirect(event.getDispatcher().getRoot().getChild("milestonelog")));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root(CommandBuildContext buildContext) {
        SuggestionProvider<CommandSourceStack> presetSuggestions = (ctx, builder) ->
                SharedSuggestionProvider.suggest(Presets.PRESETS.keySet(), builder);

        return Commands.literal("milestonelog")
                .executes(MilestoneCommands::summary)
                .then(Commands.literal("playtime").executes(MilestoneCommands::playtime))
                .then(Commands.literal("add")
                        .then(Commands.argument("item", ItemArgument.item(buildContext))
                                .executes(ctx -> add(ctx, itemId(ctx)))))
                .then(Commands.literal("addhand").executes(MilestoneCommands::addHand))
                .then(Commands.literal("remove")
                        .then(Commands.argument("item", ItemArgument.item(buildContext))
                                .executes(ctx -> remove(ctx, itemId(ctx)))))
                .then(Commands.literal("targets").executes(MilestoneCommands::targets))
                .then(Commands.literal("list").executes(MilestoneCommands::list))
                .then(Commands.literal("preset")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(presetSuggestions)
                                .executes(ctx -> preset(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("reset")
                        .then(Commands.argument("item", ItemArgument.item(buildContext))
                                .executes(ctx -> reset(ctx, itemId(ctx)))))
                .then(Commands.literal("resetall").executes(MilestoneCommands::resetAll));
    }

    // ------------------------------------------------------------- helpers

    private static ServerPlayer player(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return ctx.getSource().getPlayerOrException();
    }

    private static MilestoneData.PlayerData data(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        return MilestoneData.get(player.server).player(player.getUUID());
    }

    private static String itemId(CommandContext<CommandSourceStack> ctx) {
        Item item = ItemArgument.getItem(ctx, "item").getItem();
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static Component itemName(String itemId) {
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
        return item.getDescription();
    }

    private static int send(CommandContext<CommandSourceStack> ctx, Component msg) {
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    // ------------------------------------------------------------ commands

    private static int summary(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        MilestoneData.PlayerData pd = data(ctx);
        Set<String> targets = Tracker.effectiveTargets(pd);
        long done = targets.stream().filter(pd.records::containsKey).count();
        return send(ctx, Component.translatable("message.itemmilestonelog.summary",
                Playtime.format(Playtime.currentTicks(player)), done, targets.size()));
    }

    private static int playtime(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        return send(ctx, Component.translatable("message.itemmilestonelog.playtime",
                Playtime.format(Playtime.currentTicks(player))));
    }

    private static int add(CommandContext<CommandSourceStack> ctx, String itemId) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        MilestoneData.PlayerData pd = data(ctx);
        if (!pd.targets.add(itemId)) {
            return send(ctx, Component.translatable("message.itemmilestonelog.already_target", itemName(itemId)));
        }
        MilestoneData.get(player.server).setDirty();
        return send(ctx, Component.translatable("message.itemmilestonelog.added", itemName(itemId)));
    }

    private static int addHand(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            return send(ctx, Component.translatable("message.itemmilestonelog.addhand_empty"));
        }
        String id = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        return add(ctx, id);
    }

    private static int remove(CommandContext<CommandSourceStack> ctx, String itemId) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        MilestoneData.PlayerData pd = data(ctx);
        if (!pd.targets.remove(itemId)) {
            return send(ctx, Component.translatable("message.itemmilestonelog.not_target", itemName(itemId)));
        }
        MilestoneData.get(player.server).setDirty();
        return send(ctx, Component.translatable("message.itemmilestonelog.removed", itemName(itemId)));
    }

    private static int targets(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        MilestoneData.PlayerData pd = data(ctx);
        Set<String> targets = Tracker.effectiveTargets(pd);
        if (targets.isEmpty()) {
            return send(ctx, Component.translatable("message.itemmilestonelog.no_targets"));
        }
        List<String> sorted = new ArrayList<>(targets);
        sorted.sort(String::compareTo);
        ctx.getSource().sendSuccess(() -> Component.translatable("message.itemmilestonelog.targets_header", sorted.size()), false);
        for (String id : sorted) {
            Integer ticks = pd.records.get(id);
            Component line = ticks != null
                    ? Component.translatable("message.itemmilestonelog.target_done", itemName(id), Playtime.format(ticks))
                    : Component.translatable("message.itemmilestonelog.target_pending", itemName(id));
            ctx.getSource().sendSuccess(() -> line, false);
        }
        return sorted.size();
    }

    private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        MilestoneData.PlayerData pd = data(ctx);
        if (pd.records.isEmpty()) {
            return send(ctx, Component.translatable("message.itemmilestonelog.no_records"));
        }
        List<java.util.Map.Entry<String, Integer>> entries = new ArrayList<>(pd.records.entrySet());
        entries.sort(java.util.Comparator.comparingInt(java.util.Map.Entry::getValue));
        ctx.getSource().sendSuccess(() -> Component.translatable("message.itemmilestonelog.records_header", entries.size()), false);
        for (var e : entries) {
            Component line = Component.translatable("message.itemmilestonelog.target_done", itemName(e.getKey()), Playtime.format(e.getValue()));
            ctx.getSource().sendSuccess(() -> line, false);
        }
        return entries.size();
    }

    private static int preset(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        MilestoneData.PlayerData pd = data(ctx);
        List<String> items = Presets.PRESETS.get(name);
        if (items == null) {
            return send(ctx, Component.translatable("message.itemmilestonelog.preset_unknown", name,
                    String.join(", ", Presets.PRESETS.keySet())));
        }
        int added = 0;
        int skippedMissing = 0;
        for (String id : items) {
            if (!BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(id))) {
                skippedMissing++;
                continue;
            }
            if (pd.targets.add(id)) {
                added++;
            }
        }
        MilestoneData.get(player.server).setDirty();
        return send(ctx, Component.translatable("message.itemmilestonelog.preset_applied", name, added, skippedMissing));
    }

    private static int reset(CommandContext<CommandSourceStack> ctx, String itemId) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        MilestoneData.PlayerData pd = data(ctx);
        if (pd.records.remove(itemId) == null) {
            return send(ctx, Component.translatable("message.itemmilestonelog.not_recorded", itemName(itemId)));
        }
        MilestoneData.get(player.server).setDirty();
        return send(ctx, Component.translatable("message.itemmilestonelog.reset_done", itemName(itemId)));
    }

    private static int resetAll(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        MilestoneData.PlayerData pd = data(ctx);
        int count = pd.records.size();
        pd.records.clear();
        MilestoneData.get(player.server).setDirty();
        return send(ctx, Component.translatable("message.itemmilestonelog.resetall_done", count));
    }
}
