package com.pengin0503.itemmilestonelog;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import org.junit.jupiter.api.Test;

import static com.mojang.brigadier.builder.LiteralArgumentBuilder.literal;
import static com.mojang.brigadier.builder.RequiredArgumentBuilder.argument;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for the unquoted {@code #namespace:path} argument used by F-140-19 (#129). */
class TagIdArgumentTest {
    private static CommandDispatcher<Object> dispatcher() {
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        dispatcher.register(literal("renametag")
                .then(argument("oldTag", TagIdArgument.tagId())
                        .then(argument("newTag", TagIdArgument.tagId())
                                .executes(ctx -> 1))));
        return dispatcher;
    }

    /** Parsed argument value from a {@link ParseResults}; {@code getString} needs an executed context. */
    private static String arg(ParseResults<Object> parse, String name) {
        return (String) parse.getContext().getArguments().get(name).getResult();
    }

    @Test
    void parsesTagIdsWithColonAndSlashWithoutQuotes() throws Exception {
        CommandDispatcher<Object> dispatcher = dispatcher();
        ParseResults<Object> parse = dispatcher.parse("renametag #oldmod:gems #newmod:gems", null);

        assertFalse(parse.getReader().canRead(), "The whole tag-ID command must parse");
        assertTrue(parse.getExceptions().isEmpty());
        assertNotNull(parse.getContext().getCommand());
        assertEquals(1, dispatcher.execute(parse));
        assertEquals("#oldmod:gems", arg(parse, "oldTag"));
        assertEquals("#newmod:gems", arg(parse, "newTag"));
    }

    @Test
    void stillAcceptsTheQuotedFormAndStopsAtWhitespace() throws Exception {
        CommandDispatcher<Object> dispatcher = dispatcher();

        ParseResults<Object> quoted = dispatcher.parse("renametag \"#old:a\" '#new:a'", null);
        assertFalse(quoted.getReader().canRead());
        assertTrue(quoted.getExceptions().isEmpty());
        assertEquals("#old:a", arg(quoted, "oldTag"));
        assertEquals("#new:a", arg(quoted, "newTag"));

        ParseResults<Object> extra = dispatcher.parse("renametag #old:a #new:a confirm", null);
        assertTrue(extra.getReader().canRead(), "The argument must not swallow the next token");
        assertEquals("#old:a", arg(extra, "oldTag"));
        assertEquals("#new:a", arg(extra, "newTag"));
    }
}
