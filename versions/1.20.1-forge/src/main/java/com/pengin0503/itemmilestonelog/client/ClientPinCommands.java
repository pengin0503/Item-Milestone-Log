package com.pengin0503.itemmilestonelog.client;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.pengin0503.itemmilestonelog.PinStore;
import com.pengin0503.itemmilestonelog.SearchHistoryStore;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.List;

/**
 * F-150-62 (N113): client-only {@code /imlclient pin} command tree. It is registered through
 * {@code RegisterClientCommandsEvent} (D-150-185) — the server command registry, permissions and
 * {@code CommandHelp} are untouched. Pins are stored locally and only ids present in the current
 * sync snapshot can be added.
 */
public final class ClientPinCommands {
    static final String ROOT = "imlclient";
    static final String PIN = "pin";

    private ClientPinCommands() {
    }

    /** Builds the tree so a plain JUnit test can assert its shape without a Minecraft runtime. */
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return LiteralArgumentBuilder.<CommandSourceStack>literal(ROOT)
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal(PIN)
                        .executes(ClientPinCommands::help)
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("add")
                                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                                        .<CommandSourceStack, String>argument("id", StringArgumentType.greedyString())
                                        .executes(ClientPinCommands::add)))
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("remove")
                                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                                        .<CommandSourceStack, String>argument("id", StringArgumentType.greedyString())
                                        .executes(ClientPinCommands::remove)))
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("list")
                                .executes(ClientPinCommands::list))
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("clear")
                                .executes(ClientPinCommands::clear)))
                // F-150-84: client-only history cleanup; no server command or help entry.
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("history")
                        .executes(ClientPinCommands::historyHelp)
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("clear")
                                .executes(ClientPinCommands::historyClear)));
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(build());
    }

    private static Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    private static int help(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.translatable(
                "command.itemmilestonelog.imlclient.pin.help"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int add(CommandContext<CommandSourceStack> context) {
        String raw = StringArgumentType.getString(context, "id");
        PinStore.AddResult result = PinStore.add(configDir(), raw, MilestoneHudOverlay.availableIds());
        switch (result.outcome()) {
            case ADDED -> {
                MilestoneHudOverlay.onPinsChanged();
                success(context, "command.itemmilestonelog.imlclient.pin.add", raw);
            }
            case DUPLICATE -> failure(context, "command.itemmilestonelog.imlclient.pin.duplicate", raw);
            case INVALID_ID -> failure(context, "command.itemmilestonelog.imlclient.pin.invalid", raw);
            case NO_SYNC -> context.getSource().sendFailure(Component.translatable(
                    "message.itemmilestonelog.pin_no_sync"));
            case NOT_IN_SYNC -> failure(context,
                    "command.itemmilestonelog.imlclient.pin.not_in_sync", raw);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int remove(CommandContext<CommandSourceStack> context) {
        String raw = StringArgumentType.getString(context, "id");
        if (PinStore.remove(configDir(), raw)) {
            MilestoneHudOverlay.onPinsChanged();
            success(context, "command.itemmilestonelog.imlclient.pin.remove", raw);
        } else {
            failure(context, "command.itemmilestonelog.imlclient.pin.invalid", raw);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        List<PinStore.Pin> pins = PinStore.read(configDir());
        if (pins.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.translatable(
                    "command.itemmilestonelog.imlclient.pin.list_empty"), false);
            return Command.SINGLE_SUCCESS;
        }
        MutableComponent lines = Component.literal("");
        for (PinStore.Pin pin : pins) {
            lines.append(Component.literal("\n- " + pin.encoded()));
        }
        context.getSource().sendSuccess(() -> Component.translatable(
                "command.itemmilestonelog.imlclient.pin.list", pins.size()).append(lines), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int clear(CommandContext<CommandSourceStack> context) {
        int count = PinStore.read(configDir()).size();
        PinStore.clear(configDir());
        MilestoneHudOverlay.onPinsChanged();
        context.getSource().sendSuccess(() -> Component.translatable(
                "command.itemmilestonelog.imlclient.pin.clear", count), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int historyHelp(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.translatable(
                "command.itemmilestonelog.imlclient.history.help"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int historyClear(CommandContext<CommandSourceStack> context) {
        int count = SearchHistoryStore.read(configDir()).size();
        SearchHistoryStore.clear(configDir());
        context.getSource().sendSuccess(() -> Component.translatable(
                "command.itemmilestonelog.imlclient.history.clear", count), false);
        return Command.SINGLE_SUCCESS;
    }

    private static void success(CommandContext<CommandSourceStack> context, String key, String arg) {
        context.getSource().sendSuccess(() -> Component.translatable(key, arg), false);
    }

    private static void failure(CommandContext<CommandSourceStack> context, String key, String arg) {
        context.getSource().sendFailure(Component.translatable(key, arg));
    }
}
