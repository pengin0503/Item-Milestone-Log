package com.pengin0503.itemmilestonelog;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.StringArgumentType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.mojang.brigadier.builder.LiteralArgumentBuilder.literal;
import static com.mojang.brigadier.builder.RequiredArgumentBuilder.argument;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G10a / issue #146: renametag uses a vanilla greedy string, so these tests cover the token
 * splitting and validation that used to live in the custom (non-decodable) TagIdArgument.
 */
class RenameTagArgumentsTest {
    private static RenameTagArguments.Parsed parsed(String raw) {
        return RenameTagArguments.parse(raw);
    }

    @Test
    void parsesUnquotedTagIdsWithHashColonAndSlash() {
        RenameTagArguments.Parsed p = parsed("#c:ingots/iron #c:ingots/steel");
        assertNotNull(p);
        assertEquals("#c:ingots/iron", p.oldTag());
        assertEquals("#c:ingots/steel", p.newTag());
        assertFalse(p.confirm());

        p = parsed("#oldmod:gems #newmod:gems");
        assertNotNull(p);
        assertEquals("#oldmod:gems", p.oldTag());
        assertEquals("#newmod:gems", p.newTag());
    }

    @Test
    void stripsOnePairOfSurroundingQuotesPerToken() {
        RenameTagArguments.Parsed doubleQuoted = parsed("\"#c:ingots/iron\" '#c:ingots/steel'");
        assertNotNull(doubleQuoted);
        assertEquals("#c:ingots/iron", doubleQuoted.oldTag());
        assertEquals("#c:ingots/steel", doubleQuoted.newTag());

        RenameTagArguments.Parsed mixed = parsed("#c:ingots/iron \"#c:ingots/steel\"");
        assertNotNull(mixed);
        assertEquals("#c:ingots/iron", mixed.oldTag());
        assertEquals("#c:ingots/steel", mixed.newTag());

        // Only one pair is stripped; a doubly quoted token is not silently accepted as a tag.
        RenameTagArguments.Parsed twice = parsed("\"\"#a:b\"\" #c:d");
        assertNotNull(twice);
        assertEquals("\"#a:b\"", twice.oldTag());
    }

    @Test
    void acceptsConfirmOnlyAsTheExactThirdToken() {
        RenameTagArguments.Parsed confirm = parsed("#c:ingots/iron #c:ingots/steel confirm");
        assertNotNull(confirm);
        assertEquals("#c:ingots/iron", confirm.oldTag());
        assertEquals("#c:ingots/steel", confirm.newTag());
        assertTrue(confirm.confirm());

        assertNull(parsed("#c:ingots/iron #c:ingots/steel Confirm"), "literal confirm was case sensitive");
        assertNull(parsed("#c:ingots/iron #c:ingots/steel \"confirm\""), "quoted confirm is not the literal");
        assertNotNull(parsed("\"#c:ingots/iron\" \"#c:ingots/steel\" confirm"),
                "quoted tag IDs still work together with confirm");
    }

    @Test
    void rejectsMissingAndExtraTokens() {
        assertNull(parsed(""), "zero tokens");
        assertNull(parsed("   \t "), "whitespace only");
        assertNull(parsed("#c:ingots/iron"), "only one token");
        assertNull(parsed("  #c:ingots/iron  "), "one token with padding");
        assertNull(parsed("#a:b #c:d #e:f"), "three tag tokens");
        assertNull(parsed("#a:b #c:d confirm confirm"), "four tokens");
        assertNull(parsed("#a:b #c:d extra confirm"), "confirm must be last after exactly two tags");
    }

    @Test
    void splitsOnAnyWhitespaceAndKeepsTokenOffsets() {
        List<RenameTagArguments.Token> tokens = RenameTagArguments.split("  #a:b\t #c:d/e  ");
        assertEquals(2, tokens.size());
        assertEquals("#a:b", tokens.get(0).value());
        assertEquals(2, tokens.get(0).start());
        assertEquals("#c:d/e", tokens.get(1).value());
        assertEquals(8, tokens.get(1).start());
    }

    @Test
    void suggestionCursorTracksTheFirstThenSecondToken() {
        RenameTagArguments.Cursor first = RenameTagArguments.cursorForSuggestions("#c:i");
        assertEquals(0, first.tokenIndex());
        assertEquals(0, first.tokenStart());
        assertEquals("#c:i", first.prefix());
        assertEquals(0, first.quote());

        RenameTagArguments.Cursor second = RenameTagArguments.cursorForSuggestions("#c:ingots/iron #");
        assertEquals(1, second.tokenIndex());
        assertEquals("#c:ingots/iron ".length(), second.tokenStart());
        assertEquals("#", second.prefix());

        RenameTagArguments.Cursor freshSecond = RenameTagArguments.cursorForSuggestions("#c:ingots/iron ");
        assertEquals(1, freshSecond.tokenIndex());
        assertEquals("#c:ingots/iron ".length(), freshSecond.tokenStart());
        assertEquals("", freshSecond.prefix());

        RenameTagArguments.Cursor quoted = RenameTagArguments.cursorForSuggestions("\"#c:i");
        assertEquals(0, quoted.tokenIndex());
        assertEquals(0, quoted.tokenStart());
        assertEquals("#c:i", quoted.prefix());
        assertEquals('"', quoted.quote());
    }

    @Test
    void greedyStringCapturesTheWholeRemainderAndTheSplitValidatesIt() throws Exception {
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        dispatcher.register(literal("renametag")
                .then(argument("tags", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            RenameTagArguments.Parsed p = RenameTagArguments.parse(
                                    StringArgumentType.getString(ctx, "tags"));
                            return p == null ? 0 : 1;
                        })));

        ParseResults<Object> unquoted = dispatcher.parse("renametag #c:ingots/iron #c:ingots/steel", null);
        assertFalse(unquoted.getReader().canRead(), "greedy string must consume the whole line");
        assertTrue(unquoted.getExceptions().isEmpty());
        assertEquals(1, dispatcher.execute(unquoted));
        assertEquals("#c:ingots/iron #c:ingots/steel",
                StringArgumentType.getString(unquoted.getContext().build("root"), "tags"));

        ParseResults<Object> quoted = dispatcher.parse("renametag \"#c:ingots/iron\" \"#c:ingots/steel\"", null);
        assertFalse(quoted.getReader().canRead());
        assertEquals(1, dispatcher.execute(quoted));

        ParseResults<Object> extra = dispatcher.parse("renametag #a:b #c:d #e:f", null);
        assertFalse(extra.getReader().canRead());
        assertEquals(0, dispatcher.execute(extra), "extra token must be rejected by validation");
    }
}
