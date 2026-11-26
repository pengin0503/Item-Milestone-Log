package com.pengin0503.itemmilestonelog;

import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import java.util.Collection;
import java.util.List;

/**
 * Brigadier argument for F-140-19 tag IDs such as {@code #c:ingots/iron}.
 *
 * <p>{@code StringArgumentType.word()} / {@code string()} only read
 * {@code 0-9A-Za-z_-.+} when unquoted, so a normal tag ID stops at {@code #} or {@code :} and the
 * command never parses (issue #129). This type reads an unquoted token up to whitespace, which
 * accepts the {@code #namespace:path} form exactly as suggestions offer it. Quoted input keeps
 * working through Brigadier's normal quoted-string reader, and the value is still validated by
 * {@code MilestoneService.normalizeTagId} at execution time so invalid formats get the usual
 * error message instead of a parse failure.
 */
public final class TagIdArgument implements ArgumentType<String> {
    private static final TagIdArgument INSTANCE = new TagIdArgument();
    private static final SimpleCommandExceptionType EXPECTED_TAG = new SimpleCommandExceptionType(
            new LiteralMessage("Expected a tag ID"));

    private TagIdArgument() {
    }

    public static TagIdArgument tagId() {
        return INSTANCE;
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        if (!reader.canRead()) {
            throw EXPECTED_TAG.createWithContext(reader);
        }
        char first = reader.peek();
        if (first == '"' || first == '\'') {
            return reader.readString();
        }
        int start = reader.getCursor();
        while (reader.canRead() && !Character.isWhitespace(reader.peek())) {
            reader.skip();
        }
        return reader.getString().substring(start, reader.getCursor());
    }

    @Override
    public Collection<String> getExamples() {
        return List.of("#c:ingots/iron", "#minecraft:planks");
    }
}
