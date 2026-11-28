package com.pengin0503.itemmilestonelog.client;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.pengin0503.itemmilestonelog.ClientBackupRotation;
import com.pengin0503.itemmilestonelog.ClientCarryStore;
import com.pengin0503.itemmilestonelog.ClientExportLogic;
import com.pengin0503.itemmilestonelog.Config;
import com.pengin0503.itemmilestonelog.LocalSyncCache;
import com.pengin0503.itemmilestonelog.PinStore;
import com.pengin0503.itemmilestonelog.SearchHistoryStore;
import com.pengin0503.itemmilestonelog.network.SyncMilestonesPayload;
import com.pengin0503.itemmilestonelog.network.TargetEntry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
                                .executes(ClientPinCommands::historyClear)))
                // PR-5: all three subcommands touch client-local files only (no server data).
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("export")
                        .executes(ClientPinCommands::exportHelp)
                        .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                                .<CommandSourceStack, String>argument("format", StringArgumentType.word())
                                .executes(ClientPinCommands::exportData)))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("backup")
                        .executes(ClientPinCommands::backup))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("carry")
                        .executes(ClientPinCommands::carryHelp)
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("export")
                                .executes(ClientPinCommands::carryExport))
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("import")
                                .executes(ClientPinCommands::carryImport)));
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

    private static int exportHelp(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.translatable(
                "command.itemmilestonelog.imlclient.export.help"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int exportData(CommandContext<CommandSourceStack> context) {
        String format = StringArgumentType.getString(context, "format").toLowerCase(Locale.ROOT);
        if (!"json".equals(format) && !"csv".equals(format)) {
            failure(context, "command.itemmilestonelog.imlclient.export.invalid", format);
            return Command.SINGLE_SUCCESS;
        }
        SyncMilestonesPayload live = ClientMilestoneHandler.currentSync();
        LocalSyncCache.Snapshot snapshot = live != null ? ClientLocalOutput.cacheSnapshot(live)
                : LocalSyncCache.read(configDir(), MilestoneHudOverlay.serverKey()).snapshot();
        if (snapshot == null) {
            context.getSource().sendFailure(Component.translatable(
                    "command.itemmilestonelog.imlclient.export.no_sync"));
            return Command.SINGLE_SUCCESS;
        }
        try {
            ClientExportLogic.Result result = ClientExportLogic.export(configDir(),
                    Config.CLIENT_EXPORT_DIR.get(), format, snapshot,
                    live == null || snapshot.offline(), Instant.now(), ZoneId.systemDefault());
            success(context, "message.itemmilestonelog.export_written",
                    result.file().getFileName().toString());
        } catch (IOException | RuntimeException e) {
            failure(context, "command.itemmilestonelog.imlclient.export.failed",
                    String.valueOf(e.getMessage()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int backup(CommandContext<CommandSourceStack> context) {
        if (!Files.exists(LocalSyncCache.fileFor(configDir()))) {
            context.getSource().sendFailure(Component.translatable(
                    "command.itemmilestonelog.imlclient.backup.missing"));
            return Command.SINGLE_SUCCESS;
        }
        try {
            ClientBackupRotation.Result result = ClientBackupRotation.rotate(configDir(),
                    Instant.now(), ZoneId.systemDefault(), Config.CLIENT_BACKUP_GENERATIONS.get());
            if (!result.written()) {
                context.getSource().sendFailure(Component.translatable(
                        "command.itemmilestonelog.imlclient.backup.missing"));
                return Command.SINGLE_SUCCESS;
            }
            success(context, "command.itemmilestonelog.imlclient.backup.written",
                    result.backupFile().getFileName().toString());
        } catch (RuntimeException e) {
            failure(context, "command.itemmilestonelog.imlclient.backup.failed",
                    String.valueOf(e.getMessage()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int carryHelp(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.translatable(
                "command.itemmilestonelog.imlclient.carry.help"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int carryExport(CommandContext<CommandSourceStack> context) {
        List<ClientCarryStore.Entry> pins = new ArrayList<>();
        for (PinStore.Pin pin : PinStore.read(configDir())) {
            pins.add(new ClientCarryStore.Entry(pin.itemId(), pin.count()));
        }
        List<ClientCarryStore.Entry> targets = new ArrayList<>();
        SyncMilestonesPayload live = ClientMilestoneHandler.currentSync();
        if (live != null) {
            for (TargetEntry target : live.targets()) {
                if (target.personal() && !target.defaultTarget()) {
                    targets.add(new ClientCarryStore.Entry(target.itemId(), target.count()));
                }
            }
        }
        try {
            int count = ClientCarryStore.export(configDir(), pins, targets);
            success(context, "command.itemmilestonelog.imlclient.carry.export", String.valueOf(count));
            localOnlyNotice(context);
        } catch (IOException | RuntimeException e) {
            failure(context, "command.itemmilestonelog.imlclient.carry.failed",
                    String.valueOf(e.getMessage()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int carryImport(CommandContext<CommandSourceStack> context) {
        int count = ClientCarryStore.importCandidates(configDir()).size();
        success(context, "command.itemmilestonelog.imlclient.carry.import", String.valueOf(count));
        localOnlyNotice(context);
        return Command.SINGLE_SUCCESS;
    }

    private static void localOnlyNotice(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.translatable(
                "message.itemmilestonelog.carry_local_only"), false);
    }

    private static void success(CommandContext<CommandSourceStack> context, String key, String arg) {
        context.getSource().sendSuccess(() -> Component.translatable(key, arg), false);
    }

    private static void failure(CommandContext<CommandSourceStack> context, String key, String arg) {
        context.getSource().sendFailure(Component.translatable(key, arg));
    }
}
