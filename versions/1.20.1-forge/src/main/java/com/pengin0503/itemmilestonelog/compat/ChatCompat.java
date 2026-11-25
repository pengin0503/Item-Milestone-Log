package com.pengin0503.itemmilestonelog.compat;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * N252: chat {@link ClickEvent} / {@link HoverEvent} construction and inspection.
 * MC 1.21.5 turned these events into records ({@code ClickEvent.RunCommand},
 * {@code ClickEvent.SuggestCommand}, {@code HoverEvent.ShowItem}), so they go through
 * this interface.
 */
public interface ChatCompat {
    /** A click event that runs {@code command}. */
    ClickEvent runCommand(String command);

    /** A click event that suggests {@code command} in the chat input. */
    ClickEvent suggestCommand(String command);

    /** The command of a suggest-command click event, or {@code null} for any other event. */
    @Nullable
    String suggestCommandValue(@Nullable ClickEvent event);

    /** A hover event that shows {@code stack}'s tooltip. */
    HoverEvent showItem(ItemStack stack);
}
