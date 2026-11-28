package com.pengin0503.itemmilestonelog.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.pengin0503.itemmilestonelog.CommandHelp;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-150-62 (N113): {@code /imlclient pin} is registered on the client dispatcher only. The
 * server command tree and its {@link CommandHelp} table must not gain any of these names.
 */
class PinCommandTreeTest {
    @Test
    void registersOnlyTheClientPinSubtree() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        ClientPinCommands.register(dispatcher);

        CommandNode<CommandSourceStack> root = dispatcher.getRoot().getChild("imlclient");
        assertNotNull(root, "/imlclient must be registered on the client dispatcher");
        assertTrue(root instanceof LiteralCommandNode<?>);
        assertEquals("imlclient", root.getName());
        assertEquals(8, root.getChildren().size(),
                "pin, history, three PR-5 commands, cache, profile and diag are client-registered");

        CommandNode<CommandSourceStack> pin = root.getChild("pin");
        assertNotNull(pin, "pin subtree must exist");
        assertNotNull(pin.getCommand(), "/imlclient pin prints help");
        for (String leaf : new String[]{"add", "remove", "list", "clear"}) {
            assertNotNull(pin.getChild(leaf), leaf + " must be registered");
        }
        assertEquals(4, pin.getChildren().size());

        for (String operation : new String[]{"add", "remove"}) {
            CommandNode<CommandSourceStack> literal = pin.getChild(operation);
            CommandNode<CommandSourceStack> node = literal.getChild("id");
            assertTrue(node instanceof ArgumentCommandNode<?, ?>, operation + " needs an id argument");
            ArgumentCommandNode<CommandSourceStack, ?> argNode = (ArgumentCommandNode<CommandSourceStack, ?>) node;
            assertEquals("id", argNode.getName());
            assertTrue(argNode.getType() instanceof StringArgumentType, "id is a greedy string");
            assertNotNull(argNode.getCommand(), operation + " must execute");
        }
        assertNotNull(pin.getChild("list").getCommand(), "list must execute");
        assertNotNull(pin.getChild("clear").getCommand(), "clear must execute");

        CommandNode<CommandSourceStack> history = root.getChild("history");
        assertNotNull(history, "history subtree must exist");
        assertNotNull(history.getCommand(), "/imlclient history prints help");
        assertNotNull(history.getChild("clear").getCommand(), "history clear must execute");
        assertEquals(1, history.getChildren().size());

        CommandNode<CommandSourceStack> export = root.getChild("export");
        assertNotNull(export, "export subtree must exist");
        assertNotNull(export.getChild("format").getCommand(), "export <format> must execute");
        assertNotNull(root.getChild("backup").getCommand(), "backup must execute");
        CommandNode<CommandSourceStack> carry = root.getChild("carry");
        assertNotNull(carry, "carry subtree must exist");
        assertNotNull(carry.getChild("export").getCommand(), "carry export must execute");
        assertNotNull(carry.getChild("import").getCommand(), "carry import must execute");
        assertNotNull(carry.getChild("list").getCommand(), "carry list must show imported candidates");

        CommandNode<CommandSourceStack> diag = root.getChild("diag");
        assertNotNull(diag, "/imlclient diag must be registered on the client dispatcher only");
        assertNotNull(diag.getCommand(), "/imlclient diag must execute");
        assertEquals(0, diag.getChildren().size(), "diag takes no argument");

        CommandNode<CommandSourceStack> cache = root.getChild("cache");
        assertNotNull(cache, "/imlclient cache must be registered for offline browsing");
        assertNotNull(cache.getCommand(), "/imlclient cache prints help");
        assertNotNull(cache.getChild("open").getCommand(), "cache open must open the offline view");

        CommandNode<CommandSourceStack> profile = root.getChild("profile");
        assertNotNull(profile, "/imlclient profile must be registered (F-150-72)");
        assertNotNull(profile.getCommand(), "/imlclient profile prints help");
        for (String sub : new String[]{"save", "list", "clear"}) {
            assertNotNull(profile.getChild(sub).getCommand(), "profile " + sub + " must execute");
        }
        assertEquals(3, profile.getChildren().size());
    }

    @Test
    void serverHelpSetIsUntouchedByTheClientCommand() {
        assertFalse(CommandHelp.ENTRIES.stream()
                        .anyMatch(e -> "imlclient".equals(e.name()) || "pin".equals(e.name())
                                || "history".equals(e.name()) || "diag".equals(e.name())
                                || "cache".equals(e.name()) || "profile".equals(e.name())),
                "client commands must not enter the server help table");
        assertEquals("/milestonelog", CommandHelp.ROOT_COMMAND);
    }
}
