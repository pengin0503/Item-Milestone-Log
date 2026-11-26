package com.pengin0503.itemmilestonelog.compat.v121_9;

import com.pengin0503.itemmilestonelog.compat.ChatCompat;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * 1.21.5+ {@link ChatCompat}: {@link ClickEvent} / {@link HoverEvent} are records since
 * 1.21.5 ({@code ClickEvent.RunCommand}, {@code ClickEvent.SuggestCommand},
 * {@code HoverEvent.ShowItem}).
 */
public final class ChatCompatImpl implements ChatCompat {
    @Override
    public ClickEvent runCommand(String command) {
        return new ClickEvent.RunCommand(command);
    }

    @Override
    public ClickEvent suggestCommand(String command) {
        return new ClickEvent.SuggestCommand(command);
    }

    @Override
    public ClickEvent copyToClipboard(String value) {
        return new ClickEvent.CopyToClipboard(value);
    }

    @Override
    @Nullable
    public String suggestCommandValue(@Nullable ClickEvent event) {
        return event instanceof ClickEvent.SuggestCommand suggest ? suggest.command() : null;
    }

    @Override
    public HoverEvent showItem(ItemStack stack) {
        return new HoverEvent.ShowItem(stack);
    }
}
